package com.stk.playerapp

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private var launch by mutableStateOf<Pair<String, String>?>(null)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        readIntent(intent)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { Surface(Modifier.fillMaxSize()) { App(launch) { launch = null } } } }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); readIntent(intent) }
    /** ola يرسل: domain = البورتال ، address = MAC */
    private fun readIntent(i: Intent?) {
        val a = i?.getStringExtra("domain")?.trim(); val b = i?.getStringExtra("address")?.trim()
        if (a.isNullOrBlank() || b.isNullOrBlank()) return
        val mac = Regex("(?i)^([0-9A-F]{2}:){5}[0-9A-F]{2}$")
        launch = if (mac.matches(a)) b to a.uppercase() else a to b.uppercase()
    }
}

@Composable
fun App(launch: Pair<String, String>?, onConsumed: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("p", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    var portal by remember { mutableStateOf(prefs.getString("portal", "") ?: "") }
    var mac by remember { mutableStateOf(prefs.getString("mac", "00:1A:79:") ?: "") }
    var client by remember { mutableStateOf<StalkerClient?>(null) }
    var account by remember { mutableStateOf<Account?>(null) }
    var channels by remember { mutableStateOf(listOf<Channel>()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val player = remember { ExoPlayer.Builder(ctx).build() }
    DisposableEffect(Unit) { onDispose { player.release() } }

    fun login(p: String, m: String) {
        loading = true; error = ""
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val c = StalkerClient(p, m.trim()); c.login(); Triple(c, c.account(), c.channels())
                }
            }.onSuccess { (c, a, ch) ->
                prefs.edit().putString("portal", p).putString("mac", m).apply()
                account = a; channels = ch; client = c
            }.onFailure { error = it.message ?: "فشل تسجيل الدخول" }
            loading = false
        }
    }
    // تسجيل دخول تلقائي عند الاستلام من ola
    LaunchedEffect(launch) {
        launch?.let { (p, m) -> player.stop(); client = null; portal = p; mac = m; onConsumed(); login(p, m) }
    }

    if (client == null) {
        Column(Modifier.fillMaxSize().padding(24.dp).systemBarsPadding(), verticalArrangement = Arrangement.Center) {
            Text("Stalker Player", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(24.dp))
            OutlinedTextField(portal, { portal = it }, label = { Text("رابط البورتال") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(mac, { mac = it.uppercase() }, label = { Text("MAC") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(20.dp))
            Button(enabled = !loading && portal.isNotBlank() && mac.length >= 17, modifier = Modifier.fillMaxWidth(), onClick = {
                login(portal, mac)
            }) { if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text("تسجيل الدخول") }
            if (loading) Text("جاري تسجيل الدخول…", modifier = Modifier.padding(top = 12.dp))
            if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp))
        }
        return
    }

    var url by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    LaunchedEffect(url) { url?.let { player.setMediaItem(MediaItem.fromUri(Uri.parse(it))); player.prepare(); player.play() } }

    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        AndroidView(factory = { PlayerView(it).apply { this.player = player } },
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f))
        account?.let { a ->
            val expired = a.daysLeft != null && a.daysLeft < 0
            Card(Modifier.fillMaxWidth().padding(12.dp)) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("ينتهي الاشتراك: ${a.expiry}")
                        a.daysLeft?.let { Text(if (expired) "الاشتراك منتهي" else "المتبقي: $it يوم",
                            color = if (expired || it < 7) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) }
                    }
                    TextButton(onClick = { player.stop(); client = null }) { Text("خروج") }
                }
            }
        }
        OutlinedTextField(query, { query = it }, placeholder = { Text("بحث عن قناة") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp))
        LazyColumn(Modifier.weight(1f)) {
            items(channels.filter { it.name.contains(query, true) }) { ch ->
                Text(ch.name, modifier = Modifier.fillMaxWidth().clickable {
                    scope.launch { url = runCatching { withContext(Dispatchers.IO) { client!!.streamUrl(ch.cmd) } }.getOrNull() }
                }.padding(16.dp))
                HorizontalDivider()
            }
        }
    }
}
