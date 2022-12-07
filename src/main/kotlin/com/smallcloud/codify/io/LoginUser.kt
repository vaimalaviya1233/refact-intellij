package com.smallcloud.codify.io

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import com.intellij.ide.BrowserUtil
import com.smallcloud.codify.Resources.default_activate_api_url
import com.smallcloud.codify.settings.AppSettingsState
//import net.minidev.json.JSONObject
import com.google.gson.JsonObject
import com.intellij.openapi.diagnostic.Logger
import java.lang.reflect.Type

private fun generate_ticket(): String {
    return (Math.random() * 1e16).toLong().toString(36) + "-" + (Math.random() * 1e16).toLong().toString(36)
}

fun login() {
    val is_logined = AppSettingsState.instance.is_logined()
    if (is_logined) {
        return
    }
    AppSettingsState.instance.ticket = generate_ticket()
    BrowserUtil.browse("https://codify.smallcloud.ai/authentication?token=${AppSettingsState.instance.ticket}")
}

fun logout() {
    AppSettingsState.instance.token = null
    AppSettingsState.instance.userLogged = null
}

fun check_login() {
    Logger.getInstance("check_login").warn("check_login")
    val is_logined = AppSettingsState.instance.is_logined()
    if (is_logined) {
        return
    }

    val url = default_activate_api_url
    val ticket = AppSettingsState.instance.ticket
    val token = AppSettingsState.instance.token
    val userLogged = AppSettingsState.instance.userLogged
    val headers = mutableMapOf(
        "Content-Type" to "application/json",
        "Authorization" to  ""
    )
    if (token.isNullOrEmpty() || (!ticket.isNullOrEmpty() && !userLogged.isNullOrEmpty())) {
        headers["Authorization"] = "codify-${ticket}"
    } else {
        if (is_logined) {
            headers["Authorization"] = "Bearer ${token}"
        } else {
            return
        }
    }
    val out = sendRequest(url, "GET", headers, request_properties = mapOf(
            "redirect" to "follow",
            "cache" to "no-cache",
            "referrer" to "no-referrer"
    ))
    val gson = Gson()
    val body = gson.fromJson(out.body, JsonObject::class.java)
    val retcode = body.get("retcode").asString

    if (retcode == "TICKET-SAVEKEY") {
        AppSettingsState.instance.token = body.get("secret_api_key").asString
        AppSettingsState.instance.personalizeAndImprove = body.get("fine_tune")?.asBoolean == true
    }

    if (retcode == "TICKET-SAVEKEY" || retcode == "OK") {
        AppSettingsState.instance.userLogged = body.get("account").asString
        AppSettingsState.instance.ticket = null
    }
}
