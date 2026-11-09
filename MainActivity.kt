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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.clickable { onClick() }
    ) {
        Text(
            text,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
        )
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
    var genres by remember { mutableStateOf(listOf<Genre>()) }
    var genreId by remember { mutableStateOf("*") }
    var channels by remember { mutableStateOf(listOf<Channel>()) }
    var chLoading by remember { mutableStateOf(false) }
    var chError by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var url by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var remote by remember { mutableStateOf(listOf<Channel>()) }
    var searching by remember { mutableStateOf(false) }
    val gen = remember { intArrayOf(0) }
    val player = remember { ExoPlayer.Builder(ctx).build() }
    DisposableEffect(Unit) { onDispose { player.release() } }
    LaunchedEffect(url) { url?.let { player.setMediaItem(MediaItem.fromUri(Uri.parse(it))); player.prepare(); player.play() } }

    // بحث مباشر على السيرفر أثناء الكتابة (يظهر الاسم حتى لو لم تُحمَّل صفحته بعد)
    LaunchedEffect(query, client) {
        val c = client
        if (c == null || query.trim().length < 2) { remote = emptyList(); searching = false }
        else {
            delay(400)
            searching = true
            remote = runCatching { withContext(Dispatchers.IO) { c.search(query.trim()) } }.getOrDefault(emptyList())
            searching = false
        }
    }

    // تحميل القنوات صفحة صفحة: تظهر أول صفحة فوراً والباقي يكمل بالخلفية
    fun loadGenre(c: StalkerClient, id: String) {
        val my = ++gen[0]
        genreId = id; channels = emptyList(); chError = ""; chLoading = true
        scope.launch {
            try {
                val acc = mutableListOf<Channel>()
                var page = 1
                var max = 1
                while (page <= max) {
                    val (list, mx) = withContext(Dispatchers.IO) { c.channelsPage(id, page) }
                    if (my != gen[0]) return@launch
                    max = mx; acc += list; channels = acc.toList(); page++
                    if (list.isEmpty()) break
                }
                if (acc.isEmpty() && id == "*") {
                    val all = withContext(Dispatchers.IO) { c.allChannels() }
                    if (my == gen[0]) channels = all
                }
                if (my == gen[0] && channels.isEmpty()) chError = "لا توجد قنوات (قد يكون الاشتراك منتهياً)"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (my == gen[0]) chError = e.message ?: "تعذر تحميل القنوات"
            } finally {
                if (my == gen[0]) chLoading = false
            }
        }
    }

    // دخول سريع: بعد handshake مباشرة يفتح التطبيق، والباقي يتحمل بالخلفية
    fun login(p: String, m: String) {
        loading = true; error = ""
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val c = StalkerClient(p, m.trim(), prefs.getString("ep_" + p, null))
                    c.login()
                    c
                }
            }.onSuccess { c ->
                prefs.edit().putString("portal", p).putString("mac", m).putString("ep_" + p, c.endpoint).apply()
                account = null; genres = emptyList(); client = c; loading = false
                scope.launch { account = runCatching { withContext(Dispatchers.IO) { c.account() } }.getOrNull() ?: Account("غير محدد", null) }
                scope.launch { genres = runCatching { withContext(Dispatchers.IO) { c.genres() } }.getOrDefault(emptyList()) }
                loadGenre(c, "*")
            }.onFailure {
                error = it.message ?: "فشل تسجيل الدخول"; loading = false
            }
        }
    }

    // تسجيل دخول تلقائي عند الاستلام من ola
    LaunchedEffect(launch) {
        launch?.let { (p, m) -> player.stop(); gen[0]++; client = null; portal = p; mac = m; onConsumed(); login(p, m) }
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

    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        AndroidView(factory = { PlayerView(it).apply { this.player = player } },
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f))
        Card(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    val a = account
                    if (a == null) {
                        Text("جاري جلب معلومات الاشتراك…")
                    } else {
                        val d = a.daysLeft
                        Text("ينتهي الاشتراك: ${a.expiry}")
                        if (d != null) Text(
                            if (d < 0) "الاشتراك منتهي" else "المتبقي: $d يوم",
                            color = if (d < 7) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                        )
                    }
                }
                TextButton(onClick = { player.stop(); gen[0]++; client = null }) { Text("خروج") }
            }
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Chip("الكل", genreId == "*") { client?.let { loadGenre(it, "*") } } }
            items(genres.filter { it.id != "*" && it.title.isNotBlank() }) { g ->
                Chip(g.title, genreId == g.id) { client?.let { loadGenre(it, g.id) } }
            }
        }
        OutlinedTextField(query, { query = it }, placeholder = { Text("بحث عن قناة") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp))
        val shown = if (query.isBlank()) channels else (channels.filter { it.name.contains(query, true) } + remote).distinctBy { it.cmd }
        if (searching) Text("جاري البحث في السيرفر…", modifier = Modifier.padding(horizontal = 16.dp))
        if (chLoading) Text("جاري تحميل القنوات… ${channels.size}", modifier = Modifier.padding(horizontal = 16.dp))
        if (chError.isNotEmpty()) Text(chError, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp))
        LazyColumn(Modifier.weight(1f)) {
            items(shown) { ch ->
                Text(ch.name, modifier = Modifier.fillMaxWidth().clickable {
                    scope.launch { url = runCatching { withContext(Dispatchers.IO) { client!!.streamUrl(ch.cmd) } }.getOrNull() }
                }.padding(16.dp))
                HorizontalDivider()
            }
        }
    }
}
