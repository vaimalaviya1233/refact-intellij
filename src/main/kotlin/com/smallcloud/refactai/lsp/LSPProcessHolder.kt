package com.smallcloud.refactai.lsp

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.io.FileUtil.*
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.messages.MessageBus
import com.intellij.util.messages.Topic
import com.smallcloud.refactai.Resources
import com.smallcloud.refactai.Resources.binPrefix
import com.smallcloud.refactai.account.AccountManagerChangedNotifier
import com.smallcloud.refactai.io.InferenceGlobalContextChangedNotifier
import com.smallcloud.refactai.notifications.emitError
import com.smallcloud.refactai.panes.sharedchat.events.*
import com.smallcloud.refactai.settings.AppSettingsState
import org.apache.hc.core5.concurrent.ComplexFuture
import java.net.URI
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlin.io.path.Path
import com.smallcloud.refactai.account.AccountManager.Companion.instance as AccountManager
import com.smallcloud.refactai.io.InferenceGlobalContext.Companion.instance as InferenceGlobalContext


private fun getExeSuffix(): String {
    if (SystemInfo.isWindows) return ".exe"
    return ""
}
interface LSPProcessHolderChangedNotifier {
    fun capabilitiesChanged(newCaps: LSPCapabilities) {}

    fun xDebugLSPPortChanged(newPort: Int?) {}
    companion object {
        val TOPIC = Topic.create(
                "Connection Changed Notifier",
                LSPProcessHolderChangedNotifier::class.java
        )
    }
}

class LSPProcessHolder: Disposable {
    private var process: Process? = null
    private var lastConfig: LSPConfig? = null
    private val logger = Logger.getInstance("LSPProcessHolder")
    private val scheduler = AppExecutorUtil.createBoundedScheduledExecutorService(
            "SMCLSPLoggerScheduler", 1
    )
    private var loggerTask: Future<*>? = null
    private val schedulerCaps = AppExecutorUtil.createBoundedScheduledExecutorService(
        "SMCLSPCapsRequesterScheduler", 1
    )
    private var capsTask: Future<*>? = null
    private val messageBus: MessageBus = ApplicationManager.getApplication().messageBus


    var xDebugLSPPort: Int?
        get() { return AppSettingsState.instance.xDebugLSPPort }
        set(newValue) {
            if (newValue == AppSettingsState.instance.xDebugLSPPort) return
            messageBus
                .syncPublisher(LSPProcessHolderChangedNotifier.TOPIC)
                .xDebugLSPPortChanged(newValue)
            AppExecutorUtil.getAppScheduledExecutorService().submit {
                settingsChanged()
            }
        }

    fun startup() {
        messageBus
                .connect(this)
                .subscribe(AccountManagerChangedNotifier.TOPIC, object : AccountManagerChangedNotifier {
                    override fun apiKeyChanged(newApiKey: String?) {
                        AppExecutorUtil.getAppScheduledExecutorService().submit {
                            settingsChanged()
                        }
                    }
                })
        messageBus
                .connect(this)
                .subscribe(InferenceGlobalContextChangedNotifier.TOPIC, object : InferenceGlobalContextChangedNotifier {
                    override fun userInferenceUriChanged(newUrl: String?) {
                        AppExecutorUtil.getAppScheduledExecutorService().submit {
                            settingsChanged()
                        }
                    }
                })

        Companion::class.java.getResourceAsStream(
                "/bin/${binPrefix}/refact-lsp${getExeSuffix()}").use { input ->
            if (input == null) {
                emitError("LSP server is not found for host operating system, please contact support")
            } else {
                for (i in 0..4) {
                    try {
                        val path = Paths.get(BIN_PATH)
                        path.parent.toFile().mkdirs()
                        Files.copy(input, path, StandardCopyOption.REPLACE_EXISTING)
                        setExecutable(path.toFile())
                        break
                    } catch (e: Exception) {
                        logger.warn(e.message)
                    }
                }
            }
        }
        settingsChanged()

        capsTask = schedulerCaps.scheduleWithFixedDelay({
            capabilities = getCaps()
            if (capabilities.cloudName.isNotEmpty()) {
                capsTask?.cancel(true)
                schedulerCaps.scheduleWithFixedDelay({
                    capabilities = getCaps()
                }, 15, 15, TimeUnit.MINUTES)
            }
        }, 0, 3, TimeUnit.SECONDS)
    }


    private fun settingsChanged() {
        synchronized(this) {
            terminate()
            if (xDebugLSPPort != null) return
            val address = if (InferenceGlobalContext.inferenceUri == null) "Refact" else
                InferenceGlobalContext.inferenceUri
            val newConfig = LSPConfig(
                address = address,
                apiKey = AccountManager.apiKey,
                port = (32000..32199).random(),
                clientVersion = "${Resources.client}-${Resources.version}/${Resources.jbBuildVersion}",
                useTelemetry = true,
                deployment = InferenceGlobalContext.deploymentMode
            )
            startProcess(newConfig)
        }
    }

    val lspIsWorking: Boolean
        get() = xDebugLSPPort != null || process?.isAlive == true

    var capabilities: LSPCapabilities = LSPCapabilities()
        set(newValue) {
            if (newValue == field) return
            field = newValue
            ApplicationManager.getApplication()
                    .messageBus
                    .syncPublisher(LSPProcessHolderChangedNotifier.TOPIC)
                    .capabilitiesChanged(field)
        }

    private fun startProcess(config: LSPConfig) {
        if (config == lastConfig) return

        lastConfig = config
        capabilities = LSPCapabilities()
        terminate()
        if (lastConfig == null || !lastConfig!!.isValid) return
        logger.warn("LSP start_process " + BIN_PATH + " " + lastConfig!!.toArgs())
        var attempt = 0
        while (attempt < 5) {
            try {
                process = GeneralCommandLine(listOf(BIN_PATH) + lastConfig!!.toArgs())
                    .withRedirectErrorStream(true)
                    .createProcess()
                process!!.waitFor(3, TimeUnit.SECONDS)
                break
            } catch (e: Exception) {
                attempt++
                logger.warn("LSP start_process didn't start attempt=${attempt}")
                if (attempt == 5) {
                    throw e
                }
            }
        }
        loggerTask = scheduler.submit {
            val reader = process!!.inputStream.bufferedReader()
            var line = reader.readLine()
            while (line != null) {
                logger.warn("\n$line")
                line = reader.readLine()
            }
        }
        process!!.onExit().thenAcceptAsync { process1 ->
            logger.warn("LSP bad_things_happened " +
                    process1.inputStream.bufferedReader().use { it.readText() })
        }
        try {
            InferenceGlobalContext.connection.ping(url)
            capabilities = getCaps()
        } catch (e: Exception) {
            logger.warn("LSP bad_things_happened " + e.message)
        }
    }

    private fun safeTerminate() {
        InferenceGlobalContext.connection.get(URI(
            "http://127.0.0.1:${lastConfig!!.port}/v1/graceful-shutdown")).get().get()
    }

    private fun terminate() {
        process?.let {
            try {
                safeTerminate()
                if (it.waitFor(3, TimeUnit.SECONDS)) {
                    logger.info("LSP SIGTERM")
                    it.destroy()
                }
                process = null
            } catch (_: Exception) {}
        }
    }

    companion object {
        private val BIN_PATH = Path(getTempDirectory(),
            ApplicationInfo.getInstance().build.toString().replace(Regex("[^A-Za-z0-9 ]"), "_") +
            "_refact_lsp${getExeSuffix()}").toString()
        // here ?
        @JvmStatic
        val instance: LSPProcessHolder
            get() = ApplicationManager.getApplication().getService(LSPProcessHolder::class.java)
    }

    override fun dispose() {
        terminate()
        delete(Path(BIN_PATH))
        scheduler.shutdown()
        schedulerCaps.shutdown()
    }

    fun buildInfo(): String {
        var res = ""
        InferenceGlobalContext.connection.get(url.resolve("/build_info"),
            dataReceiveEnded = {},
            errorDataReceived = {}).also {
            try {
                res = it.get().get() as String
                logger.debug("build_info request finished")
            } catch (e: Exception) {
                logger.debug("build_info ${e.message}")
            }
        }
        return res
    }

    val url: URI
        get() {
            val port = xDebugLSPPort?: lastConfig?.port ?: return URI("")

            return URI("http://127.0.0.1:${port}/")
        }
    private fun getCaps(): LSPCapabilities {
        var res = LSPCapabilities()
        InferenceGlobalContext.connection.get(url.resolve("/v1/caps"),
                dataReceiveEnded = {},
                errorDataReceived = {}).also {
            var requestFuture: ComplexFuture<*>? = null
            try {
                requestFuture = it.get() as ComplexFuture
                val out = requestFuture.get()
                logger.warn("LSP caps_received " + out)
                val gson = Gson()
                res = gson.fromJson(out as String, LSPCapabilities::class.java)
                logger.debug("caps_received request finished")
            } catch (e: Exception) {
                logger.debug("caps_received ${e.message}")
            }
            return res
        }
    }

    fun fetchCaps(): Future<LSPCapabilities> {
        // check this.capabilities, if it's not empty use it, otherwise fetch it from server
         val res = InferenceGlobalContext.connection.get(
            url.resolve("/v1/caps"),
            dataReceiveEnded = {},
            errorDataReceived = {}
        )

        return res.thenApply {
            val body = it.get() as String
            Gson().fromJson(body, LSPCapabilities::class.java)
        }
    }

    fun fetchSystemPrompts(): Future<SystemPromptMap> {
        val res = InferenceGlobalContext.connection.get(
            url.resolve("/v1/customization"),
            dataReceiveEnded = {},
            errorDataReceived = {}
        )
        val json = res.thenApply {
            val body = it.get() as String
            val type: SystemPromptMap = HashMap<String, SystemPrompt>()
            Gson().fromJson<SystemPromptMap>(body, type::class.java)
        }

        return json
    }

    fun fetchCommandCompletion(query: String, cursor: Int, count: Int, trigger: String?): Future<CommandCompletionResponse> {
        val queryOrTrigger = trigger ?: query
        val place = trigger?.length ?: count
        val requestBody = Gson().toJson(mapOf("query" to queryOrTrigger, "cursor" to place, "top_n" to count))

        val res = InferenceGlobalContext.connection.post(
            url.resolve("/v1/completion"),
            requestBody,
        )
        // could have detail message
        val json = res.thenApply {
            val body = it.get() as String
            // handle error
            // if(body.startsWith("detail"))
            Gson().fromJson<CommandCompletionResponse>(body, CommandCompletionResponse::class.java)
        }

        return json
    }

    fun fetchCommandPreview(query: String): Future<Array<Events.AtCommands.Preview.PreviewContent>> {
        val requestBody = Gson().toJson(mapOf("query" to query))
        val response = InferenceGlobalContext.connection.post(
            url.resolve("/v1/at-command-preview"),
            requestBody
        )

        val json = response.thenApply {
            val responseBody = it.get() as String
            if (responseBody.startsWith("detail")) {
                Array(0) { Events.AtCommands.Preview.PreviewContent("") }
            } else {
                Gson().fromJson<Array<Events.AtCommands.Preview.PreviewContent>>(
                    responseBody,
                    Array<Events.AtCommands.Preview.PreviewContent>::class.java
                )
            }
        }

        return json
    }

    fun sendChat(
        id: String,
        messages: ChatMessages,
        model: String,
        dataReceived: (String, String) -> Unit,
        dataReceiveEnded: (String) -> Unit,
        errorDataReceived: (JsonObject) -> Unit,
        failedDataReceiveEnded: (Throwable?) -> Unit,
    ): Future<Void> {
        val parameters = mapOf("max_new_tokens" to 1000)
        // TODO: figure out why properties on the parent class are missing from json serialization
        val requestBody = Gson().toJson(mapOf(
            "messages" to messages.map{ mapOf("role" to it.role, "content" to it.content) },
            "model" to model,
            "parameters" to parameters,
            "stream" to true
        ))

        println("send_chat $requestBody")
        val headers = mapOf("Authorization" to "Bearer ${AccountManager.apiKey}")
        val response = InferenceGlobalContext.connection.post(
            url.resolve("/v1/chat"),
            requestBody,
            headers = headers,
            dataReceived = dataReceived,
            dataReceiveEnded = dataReceiveEnded,
            errorDataReceived = errorDataReceived,
            failedDataReceiveEnded = failedDataReceiveEnded,
            requestId = id,
//            dataReceived = {p0, p1 -> println("chat_request_received $p0 $p1")},
//            dataReceiveEnded = {str -> println("chat_request_ended $str")},
//            errorDataReceived = {e -> println("chat_request_error $e")},
//            failedDataReceiveEnded = {e -> println("chat_request_failed_ended $e")}
        )

         return response.thenApply {
            it.get()
             null

        }

    }
    // chat ?
    // prompts?
    // statistics?
}