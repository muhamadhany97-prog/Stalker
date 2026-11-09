package com.stk.playerapp

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

data class Channel(val name: String, val cmd: String)
data class Account(val expiry: String, val daysLeft: Long?)

class StalkerClient(portal: String, private val mac: String) {
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    private val endpoints: List<String>
    private var endpoint = ""
    private var token = ""

    init {
        var p = portal.trim().trimEnd('/')
        if (!p.startsWith("http")) p = "http://$p"
        p = p.removeSuffix("/c").removeSuffix("/server/load.php")
        endpoints = listOf("$p/server/load.php", "$p/stalker_portal/server/load.php", "$p/portal.php")
    }

    private fun call(query: String, ep: String = endpoint): JSONObject? {
        val req = Request.Builder()
            .url("$ep?$query&JsHttpRequest=1-xml")
            .header("User-Agent", "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG250 stbapp ver: 4 rev: 2116 Mobile Safari/533.3")
            .header("X-User-Agent", "Model: MAG250; Link: WiFi")
            .header("Cookie", "mac=$mac; stb_lang=en; timezone=GMT")
            .apply { if (token.isNotEmpty()) header("Authorization", "Bearer $token") }
            .build()
        http.newCall(req).execute().use { r ->
            val body = r.body?.string() ?: return null
            return runCatching { JSONObject(body).optJSONObject("js") }.getOrNull()
        }
    }

    /** تسجيل الدخول: handshake + get_profile */
    fun login() {
        for (ep in endpoints) {
            val js = runCatching { call("type=stb&action=handshake&token=", ep) }.getOrNull()
            val t = js?.optString("token").orEmpty()
            if (t.isNotEmpty()) { endpoint = ep; token = t; break }
        }
        if (endpoint.isEmpty()) error("تعذر الاتصال بالبورتال أو MAC غير صحيح")
        call("type=stb&action=get_profile&hd=1&ver=ImageDescription:%200.2.18-r14-pub-250;&num_banks=2&stb_type=MAG250&client_type=STB&image_version=218&video_out=hdmi&auth_second_step=1&not_valid_token=0")
    }

    /** تاريخ انتهاء الاشتراك */
    fun account(): Account {
        val js = call("type=account_info&action=get_main_info")
        val raw = listOf("end_date", "expire_billing_date", "phone").map { js?.optString(it).orEmpty() }
            .firstOrNull { it.isNotBlank() && it != "0000-00-00 00:00:00" } ?: "غير محدد"
        val days = runCatching {
            val fmts = listOf("yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd", "MMMM d, yyyy, hh:mm a", "MMMM d, yyyy")
            var d: Date? = null
            for (f in fmts) { d = runCatching { SimpleDateFormat(f, Locale.ENGLISH).parse(raw) }.getOrNull(); if (d != null) break }
            d?.let { TimeUnit.MILLISECONDS.toDays(it.time - System.currentTimeMillis()) }
        }.getOrNull()
        return Account(raw, days)
    }

    fun channels(): List<Channel> {
        val out = mutableListOf<Channel>()
        fun parse(arr: org.json.JSONArray?) {
            if (arr == null) return
            for (i in 0 until arr.length()) arr.getJSONObject(i).let { out += Channel(it.optString("name"), it.optString("cmd")) }
        }
        parse(call("type=itv&action=get_all_channels")?.optJSONArray("data"))
        if (out.isEmpty()) { // بعض البورتالات تدعم الصفحات فقط
            for (p in 1..40) {
                val js = call("type=itv&action=get_ordered_list&genre=*&force_ch_link_check=&fav=0&sortby=number&hd=0&p=$p") ?: break
                val before = out.size
                parse(js.optJSONArray("data"))
                if (out.size == before) break
            }
        }
        return out
    }

    fun streamUrl(cmd: String): String {
        val enc = URLEncoder.encode(cmd, "UTF-8")
        val resolved = call("type=itv&action=create_link&cmd=$enc&forced_storage=undefined&disable_ad=0")?.optString("cmd").orEmpty().ifBlank { cmd }
        return resolved.substringAfter(' ', resolved).trim()
    }
}
