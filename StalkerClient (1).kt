package com.stk.playerapp

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

data class Channel(val name: String, val cmd: String)
data class Genre(val id: String, val title: String)
data class Account(val expiry: String, val daysLeft: Long?)

class StalkerClient(portal: String, private val mac: String, hint: String? = null) {
    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    private val fast = http.newBuilder().callTimeout(8, TimeUnit.SECONDS).build()
    private val endpoints: List<String>
    var endpoint = ""
        private set
    private var token = ""

    init {
        var p = portal.trim().trimEnd('/')
        if (!p.startsWith("http")) p = "http://$p"
        p = p.removeSuffix("/c").removeSuffix("/server/load.php")
        val all = listOf("$p/server/load.php", "$p/stalker_portal/server/load.php", "$p/portal.php")
        endpoints = if (!hint.isNullOrBlank() && hint in all) listOf(hint) + all.filter { it != hint } else all
    }

    private fun raw(query: String, ep: String = endpoint, client: OkHttpClient = http): Any? {
        val req = Request.Builder()
            .url("$ep?$query&JsHttpRequest=1-xml")
            .header("User-Agent", "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG250 stbapp ver: 4 rev: 2116 Mobile Safari/533.3")
            .header("X-User-Agent", "Model: MAG250; Link: WiFi")
            .header("Cookie", "mac=$mac; stb_lang=en; timezone=GMT")
            .apply { if (token.isNotEmpty()) header("Authorization", "Bearer $token") }
            .build()
        client.newCall(req).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw java.io.IOException("HTTP ${r.code}")
            val js = runCatching { JSONObject(body).opt("js") }.getOrNull()
            if (js == null && query.contains("handshake")) throw java.io.IOException("رد غير صالح: " + body.take(80).replace("\n", " "))
            return js
        }
    }

    private fun call(query: String, ep: String = endpoint, client: OkHttpClient = http): JSONObject? = raw(query, ep, client) as? JSONObject

    /** تسجيل دخول سريع: handshake + get_profile فقط (بدون انتظار القنوات) */
    fun login() {
        val errors = mutableListOf<String>()
        for (ep in endpoints) {
            try {
                val t = call("type=stb&action=handshake&token=", ep, fast)?.optString("token").orEmpty()
                if (t.isNotEmpty()) { endpoint = ep; token = t; break }
                errors += "${ep.substringAfter("//")}: لا يوجد token"
            } catch (e: Exception) {
                errors += "${ep.substringAfter("//")}: ${e.message ?: e.javaClass.simpleName}"
            }
        }
        if (endpoint.isEmpty()) error("فشل handshake\n" + errors.joinToString("\n"))
        runCatching { call("type=stb&action=get_profile&hd=1&ver=ImageDescription:%200.2.18-r14-pub-250;&num_banks=2&stb_type=MAG250&client_type=STB&image_version=218&video_out=hdmi&auth_second_step=1&not_valid_token=0", client = fast) }
    }

    fun account(): Account {
        val js = runCatching { call("type=account_info&action=get_main_info", client = fast) }.getOrNull()
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

    fun genres(): List<Genre> {
        val arr = raw("type=itv&action=get_genres") as? JSONArray ?: return emptyList()
        val out = mutableListOf<Genre>()
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { out += Genre(it.optString("id"), it.optString("title")) }
        return out
    }

    private fun parse(arr: JSONArray?): List<Channel> {
        val out = mutableListOf<Channel>()
        if (arr == null) return out
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { out += Channel(it.optString("name"), it.optString("cmd")) }
        return out
    }

    /** صفحة من القنوات: تُرجع القنوات وعدد الصفحات */
    fun channelsPage(genre: String, page: Int): Pair<List<Channel>, Int> {
        val g = URLEncoder.encode(genre, "UTF-8")
        val js = call("type=itv&action=get_ordered_list&genre=$g&force_ch_link_check=&fav=0&sortby=number&hd=0&p=$page") ?: return Pair(emptyList(), 1)
        val list = parse(js.optJSONArray("data"))
        val total = js.optString("total_items").toIntOrNull() ?: list.size
        val per = js.optString("max_page_items").toIntOrNull() ?: list.size
        val pages = if (per > 0) (total + per - 1) / per else 1
        return Pair(list, pages.coerceIn(1, 300))
    }

    /** بحث مباشر على السيرفر: يُظهر الاسم حتى قبل اكتمال تحميل القنوات */
    fun search(q: String): List<Channel> {
        val enc = URLEncoder.encode(q, "UTF-8")
        val out = mutableListOf<Channel>()
        var page = 1
        var max = 1
        while (page <= max && page <= 3) {
            val js = call("type=itv&action=get_ordered_list&genre=*&search=$enc&force_ch_link_check=&fav=0&sortby=number&hd=0&p=$page") ?: break
            val list = parse(js.optJSONArray("data"))
            out += list
            val total = js.optString("total_items").toIntOrNull() ?: list.size
            val per = js.optString("max_page_items").toIntOrNull() ?: list.size
            max = if (per > 0) (total + per - 1) / per else 1
            page++
            if (list.isEmpty()) break
        }
        return out.filter { it.name.contains(q, true) }
    }

    fun allChannels(): List<Channel> = parse(call("type=itv&action=get_all_channels")?.optJSONArray("data"))

    fun streamUrl(cmd: String): String {
        val enc = URLEncoder.encode(cmd, "UTF-8")
        val resolved = call("type=itv&action=create_link&cmd=$enc&forced_storage=undefined&disable_ad=0")?.optString("cmd").orEmpty().ifBlank { cmd }
        return resolved.substringAfter(' ', resolved).trim()
    }
}
