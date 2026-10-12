package com.kurdradio.byd

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.MutableContextWrapper
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.os.LocaleListCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionToken
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

// ===================== Data =====================
data class Station(val name: String, val url: String, val kind: String)

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("p", Context.MODE_PRIVATE)

    var color: Int
        get() = sp.getInt("color", 0xFF00B3A4.toInt())
        set(v) { sp.edit().putInt("color", v).apply() }

    var radioPkg: String?
        get() = sp.getString("radioPkg", null)
        set(v) { sp.edit().putString("radioPkg", v).apply() }

    var langInit: Boolean
        get() = sp.getBoolean("langInit", false)
        set(v) { sp.edit().putBoolean("langInit", v).apply() }

    fun saveStations(key: String, list: List<Station>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("n", it.name).put("u", it.url).put("k", it.kind))
        }
        sp.edit().putString(key, arr.toString()).apply()
    }

    fun loadStations(key: String): List<Station> {
        val s = sp.getString(key, null) ?: return emptyList()
        return try {
            val a = JSONArray(s)
            (0 until a.length()).map {
                val o = a.getJSONObject(it)
                Station(o.getString("n"), o.getString("u"), o.getString("k"))
            }
        } catch (e: Exception) { emptyList() }
    }
}

object StationRepo {
    private val hosts = listOf(
        "https://de1.api.radio-browser.info",
        "https://nl1.api.radio-browser.info",
        "https://at1.api.radio-browser.info"
    )

    private fun get(path: String): String {
        for (h in hosts) {
            try {
                val c = URL(h + path).openConnection() as HttpURLConnection
                c.connectTimeout = 8000
                c.readTimeout = 20000
                c.setRequestProperty("User-Agent", "KurdRadio/1.0")
                return c.inputStream.bufferedReader().use { it.readText() }
            } catch (e: Exception) { /* try next host */ }
        }
        throw java.io.IOException("network")
    }

    suspend fun fetch(): List<Station> = withContext(Dispatchers.IO) {
        val out = LinkedHashMap<String, Station>()
        fun add(json: String, forceKurdish: Boolean) {
            val a = JSONArray(json)
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val url = o.optString("url_resolved").ifBlank { o.optString("url") }
                val name = o.optString("name").trim()
                if (url.isBlank() || name.isBlank()) continue
                val meta = (name + " " + o.optString("language") + " " + o.optString("tags")).lowercase()
                val kurd = forceKurdish || meta.contains("kurd") || meta.contains("کورد")
                if (kurd || !out.containsKey(url)) {
                    out[url] = Station(name, url, if (kurd) "ku" else "ar")
                }
            }
        }
        add(get("/json/stations/search?countrycode=IQ&hidebroken=true&order=clickcount&reverse=true&limit=500"), false)
        try {
            add(get("/json/stations/search?language=kurdish&hidebroken=true&order=clickcount&reverse=true&limit=300"), true)
        } catch (e: Exception) { }
        out.values.toList()
    }
}


// ===================== Updater =====================
object Updater {
    data class Info(val code: Int, val name: String, val apk: String?, val notes: String, val total: Int)

    /** Reads GitHub Releases of the repo. Every release = one update. */
    suspend fun check(): Info? = withContext(Dispatchers.IO) {
        val repo = BuildConfig.GH_REPO
        if (repo.isBlank()) return@withContext null
        val c = URL("https://api.github.com/repos/$repo/releases?per_page=100").openConnection() as HttpURLConnection
        c.connectTimeout = 10000
        c.readTimeout = 15000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        val arr = JSONArray(c.inputStream.bufferedReader().use { it.readText() })
        if (arr.length() == 0) return@withContext null
        val first = arr.getJSONObject(0)
        val tag = first.getString("tag_name")
        val code = tag.filter { it.isDigit() }.toIntOrNull() ?: 0
        val assets = first.getJSONArray("assets")
        var apk: String? = null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.getString("name").endsWith(".apk")) apk = a.getString("browser_download_url")
        }
        Info(code, "1.0.$code", apk, first.optString("body"), arr.length())
    }

    suspend fun download(ctx: Context, url: String, onProgress: (Int) -> Unit): File =
        withContext(Dispatchers.IO) {
            val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
            val file = File(dir, "update.apk")
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 15000
            c.readTimeout = 30000
            val total = c.contentLength.toLong()
            c.inputStream.use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(32 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) onProgress((done * 100 / total).toInt())
                    }
                }
            }
            file
        }

    fun install(ctx: Context, file: File) {
        if (Build.VERSION.SDK_INT >= 26 && !ctx.packageManager.canRequestPackageInstalls()) {
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}


// ===================== Services =====================
/** Radio playback + MediaSession => shows up in the BYD media section and steering wheel buttons work. */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val ds = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent("KurdRadio/1.0")
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(ds))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA).build(), true
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        session = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        session?.run { player.release(); release() }
        session = null
        super.onDestroy()
    }
}

/** Keeps the process alive so YouTube (WebView) audio continues when the app is in the background. */
class YouTubeService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("yt", "YouTube", NotificationManager.IMPORTANCE_LOW))
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, "yt")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("YouTube")
            .setContentIntent(pi)
            .build()
        startForeground(2, n)
        return START_STICKY
    }
}

/** One WebView that survives tab changes so playback never stops. */
object YT {
    var web: WebView? = null

    fun get(ctx: Context): WebView {
        web?.let { return it }
        val w = WebView(MutableContextWrapper(ctx.applicationContext))
        w.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }
        w.webViewClient = WebViewClient()
        w.loadUrl("https://m.youtube.com")
        web = w
        return w
    }
}


// ===================== MainActivity =====================
enum class Tab(val icon: String, val label: Int) {
    ONLINE("📻", R.string.tab_online),
    OFFLINE("📴", R.string.tab_offline),
    YOUTUBE("▶️", R.string.tab_youtube),
    SETTINGS("⚙️", R.string.tab_settings)
}

data class AppInfo(val label: String, val pkg: String)

class MainActivity : AppCompatActivity() {
    lateinit var prefs: Prefs
    var controller by mutableStateOf<MediaController?>(null)
    var nowTitle by mutableStateOf("")
    var nowUrl by mutableStateOf("")
    var isPlaying by mutableStateOf(false)
    var accent by mutableStateOf(0)
    var tab by mutableStateOf(Tab.ONLINE)
    var ytStarted by mutableStateOf(false)
    val favs = mutableStateListOf<Station>()
    var scanning by mutableStateOf(false)
    var currentList: List<Station> = emptyList()
    private var scanList: List<Station> = emptyList()
    private var scanIdx = 0
    private val handler = Handler(Looper.getMainLooper())
    private val scanStep = object : Runnable {
        override fun run() {
            if (!scanning || scanList.isEmpty()) { scanning = false; return }
            play(scanList[scanIdx])
            scanIdx = (scanIdx + 1) % scanList.size
            handler.postDelayed(this, 7000)
        }
    }

    private val notifPerm =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        accent = prefs.color
        favs.addAll(prefs.loadStations("favs"))

        if (!prefs.langInit) {
            prefs.langInit = true
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("ckb"))
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)

        connectPlayer()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val w = YT.web
                if (tab == Tab.YOUTUBE && w != null && w.canGoBack()) w.goBack()
                else { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
            }
        })

        setContent { App(this) }
    }

    private fun connectPlayer() {
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val f = MediaController.Builder(this, token).buildAsync()
        f.addListener({
            val c = f.get()
            c.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(p: Boolean) { isPlaying = p }
                override fun onMediaMetadataChanged(m: MediaMetadata) {
                    m.title?.toString()?.let { nowTitle = it }
                }
            })
            controller = c
        }, ContextCompat.getMainExecutor(this))
    }

    fun play(s: Station) {
        val c = controller ?: return
        nowTitle = s.name
        nowUrl = s.url
        c.setMediaItem(
            MediaItem.Builder().setUri(s.url)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(s.name).setArtist("Kurd Radio").build())
                .build()
        )
        c.prepare()
        c.play()
    }

    /** Auto scan: plays each station ~7 seconds, tap stop to keep the current one. */
    fun startScan(list: List<Station>) {
        if (list.isEmpty()) return
        currentList = list
        scanList = list
        val cur = list.indexOfFirst { it.url == nowUrl }
        scanIdx = if (cur >= 0) (cur + 1) % list.size else 0
        scanning = true
        handler.removeCallbacks(scanStep)
        handler.post(scanStep)
    }

    fun stopScan() { scanning = false; handler.removeCallbacks(scanStep) }

    fun playManual(s: Station, list: List<Station>) {
        stopScan()
        currentList = list
        play(s)
    }

    fun step(d: Int) {
        val l = currentList
        if (l.isEmpty()) return
        stopScan()
        val i = l.indexOfFirst { it.url == nowUrl }
        val ni = if (i < 0) 0 else (i + d + l.size) % l.size
        play(l[ni])
    }

    fun togglePlay() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else c.play()
    }

    override fun onDestroy() { handler.removeCallbacks(scanStep); super.onDestroy() }

    fun toggleFav(s: Station) {
        val i = favs.indexOfFirst { it.url == s.url }
        if (i >= 0) favs.removeAt(i) else favs.add(s)
        prefs.saveStations("favs", favs.toList())
    }

    fun startYoutubeService() {
        if (ytStarted) return
        ytStarted = true
        ContextCompat.startForegroundService(this, Intent(this, YouTubeService::class.java))
    }

    fun setLanguage(tag: String) {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
    }

    fun changeAccent(c: Int) { accent = c; prefs.color = c }

    // ---------- FM / AM (car's own radio app) ----------
    private val knownRadioPkgs = listOf(
        "com.byd.radio", "com.byd.fmradio", "com.byd.dilink.radio",
        "com.android.fmradio", "com.mediatek.fmradio", "com.sprd.fmradio"
    )

    fun installedApps(): List<AppInfo> {
        val pm = packageManager
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        fun isRadio(a: AppInfo) = listOf("radio", "fm", "راديو", "ڕادیۆ").any {
            a.pkg.lowercase().contains(it) || a.label.lowercase().contains(it)
        }
        return pm.queryIntentActivities(i, 0)
            .map { AppInfo(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
            .distinctBy { it.pkg }
            .sortedWith(compareBy({ !isRadio(it) }, { it.label.lowercase() }))
    }

    fun launchPkg(pkg: String): Boolean {
        val i = packageManager.getLaunchIntentForPackage(pkg) ?: return false
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(i)
        return true
    }

    /** Returns true if launched; false => UI must ask the user to pick the app. */
    fun openCarRadio(): Boolean {
        prefs.radioPkg?.let { if (launchPkg(it)) return true }
        for (p in knownRadioPkgs) if (launchPkg(p)) { prefs.radioPkg = p; return true }
        return false
    }
}


// ===================== Ui =====================
val carColors = listOf(
    0xFF00B3A4, 0xFF1E88E5, 0xFFE53935, 0xFFFFFFFF,
    0xFFFB8C00, 0xFF7CB342, 0xFF8E24AA, 0xFFFDD835
).map { it.toInt() }

@Composable
fun App(act: MainActivity) {
    val accent = Color(act.accent)
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = accent, secondary = accent,
            background = Color(0xFF101820), surface = Color(0xFF16212B),
            onSurface = Color.White, onBackground = Color.White
        )
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { Root(act) }
    }
}

@Composable
fun Root(act: MainActivity) {
    val realDir = LocalLayoutDirection.current
    // Sidebar is always on the LEFT, content follows the language direction
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(Modifier.fillMaxSize()) {
            Sidebar(act)
            CompositionLocalProvider(LocalLayoutDirection provides realDir) {
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    if (act.ytStarted) {
                        val m = if (act.tab == Tab.YOUTUBE) Modifier.fillMaxSize() else Modifier.size(1.dp)
                        AndroidView(
                            modifier = m,
                            factory = { ctx ->
                                YT.get(ctx).also { (it.parent as? ViewGroup)?.removeView(it) }
                            }
                        )
                    }
                    if (act.tab != Tab.YOUTUBE) {
                        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                            when (act.tab) {
                                Tab.ONLINE -> OnlineScreen(act)
                                Tab.OFFLINE -> OfflineScreen(act)
                                Tab.SETTINGS -> SettingsScreen(act)
                                else -> {}
                            }
                        }
                    }
                    MiniPlayer(act, Modifier.align(Alignment.BottomCenter))
                }
            }
        }
    }
}

@Composable
fun Sidebar(act: MainActivity) {
    val accent = MaterialTheme.colorScheme.primary
    Column(
        Modifier.width(120.dp).fillMaxHeight().background(Color(0xFF0B1117)).padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Tab.values().forEach { t ->
            val sel = act.tab == t
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (sel) accent.copy(alpha = 0.22f) else Color.Transparent)
                    .clickable {
                        act.tab = t
                        if (t == Tab.YOUTUBE) act.startYoutubeService()
                    }.padding(vertical = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(t.icon, fontSize = 34.sp)
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(t.label), fontSize = 13.sp,
                    color = if (sel) accent else Color.White.copy(alpha = 0.8f),
                    maxLines = 2, overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun MiniPlayer(act: MainActivity, modifier: Modifier) {
    if (act.nowTitle.isBlank()) return
    val accent = MaterialTheme.colorScheme.primary
    Row(
        modifier.fillMaxWidth().padding(12.dp).clip(RoundedCornerShape(20.dp))
            .background(Color(0xFF1F2E3B)).padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(if (act.scanning) "🔍" else "📻", fontSize = 26.sp)
        Spacer(Modifier.width(12.dp))
        Text(act.nowTitle, Modifier.weight(1f), fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("⏮", fontSize = 30.sp, modifier = Modifier.clickable { act.step(-1) }.padding(10.dp))
        Box(
            Modifier.size(56.dp).clip(CircleShape).background(accent).clickable { act.togglePlay() },
            contentAlignment = Alignment.Center
        ) { Text(if (act.isPlaying) "⏸" else "▶", fontSize = 26.sp, color = Color.Black) }
        Text("⏭", fontSize = 30.sp, modifier = Modifier.clickable { act.step(1) }.padding(10.dp))
    }
}

@Composable
fun StationRow(act: MainActivity, s: Station, list: List<Station>) {
    val fav = act.favs.any { it.url == s.url }
    val playing = act.nowUrl == s.url
    Row(
        Modifier.fillMaxWidth().clickable { act.playManual(s, list) }
            .background(if (playing) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else Color.Transparent)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("📻", fontSize = 26.sp)
        Spacer(Modifier.width(14.dp))
        Text(s.name, Modifier.weight(1f), fontSize = 21.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            if (fav) "⭐" else "☆", fontSize = 30.sp,
            modifier = Modifier.clickable { act.toggleFav(s) }.padding(10.dp)
        )
    }
}

@Composable
fun SearchScanBar(act: MainActivity, query: String, onQuery: (String) -> Unit, list: List<Station>) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        OutlinedTextField(
            value = query, onValueChange = onQuery, singleLine = true,
            placeholder = { Text("🔎 " + stringResource(R.string.search_hint)) },
            modifier = Modifier.weight(1f)
        )
        Button(onClick = { if (act.scanning) act.stopScan() else act.startScan(list) }) {
            Text(
                if (act.scanning) "⏹ " + stringResource(R.string.stop_scan) else "🔍 " + stringResource(R.string.scan),
                fontSize = 20.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
            )
        }
    }
    if (act.scanning) {
        Text(stringResource(R.string.scanning_hint), Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            fontSize = 16.sp, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
fun OnlineScreen(act: MainActivity) {
    var all by remember { mutableStateOf(act.prefs.loadStations("cache")) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    var kind by remember { mutableStateOf("ku") }
    var query by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    fun load() {
        scope.launch {
            loading = true; error = false
            try {
                val r = StationRepo.fetch()
                if (r.isNotEmpty()) { all = r; act.prefs.saveStations("cache", r) }
            } catch (e: Exception) { error = all.isEmpty() }
            loading = false
        }
    }
    LaunchedEffect(Unit) { load() }

    val base = if (kind == "fav") act.favs.toList() else all.filter { it.kind == kind }
    val shown = base.filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf("ku" to "🟢 " + stringResource(R.string.kurdish),
                   "ar" to "🔵 " + stringResource(R.string.arabic),
                   "fav" to "⭐ " + stringResource(R.string.favorites_short)).forEach { (k, l) ->
                val sel = kind == k
                Button(
                    onClick = { kind = k },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (sel) MaterialTheme.colorScheme.primary else Color(0xFF1F2E3B),
                        contentColor = if (sel) Color.Black else Color.White
                    )
                ) { Text(l, fontSize = 20.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)) }
            }
        }
        SearchScanBar(act, query, { query = it }, shown)
        if (loading && all.isEmpty()) Text(stringResource(R.string.loading), Modifier.padding(20.dp), fontSize = 20.sp)
        if (error) {
            Text(stringResource(R.string.no_net), Modifier.padding(20.dp), fontSize = 20.sp)
            Button(onClick = { load() }, modifier = Modifier.padding(horizontal = 20.dp)) {
                Text(stringResource(R.string.retry), fontSize = 18.sp)
            }
        }
        if (kind == "fav" && shown.isEmpty()) Text(stringResource(R.string.no_favs), Modifier.padding(20.dp), fontSize = 18.sp)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 100.dp)) {
            items(shown) { StationRow(act, it, shown) }
        }
    }
}

@Composable
fun OfflineScreen(act: MainActivity) {
    var showPicker by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val shown = act.favs.filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp).clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
                .clickable { if (!act.openCarRadio()) showPicker = true }
                .padding(22.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("📡", fontSize = 40.sp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.fm_am), fontSize = 26.sp)
                Text(stringResource(R.string.fm_am_desc), fontSize = 16.sp, color = Color.White.copy(alpha = 0.7f))
            }
            Text("⋯", fontSize = 30.sp, modifier = Modifier.clickable { showPicker = true }.padding(10.dp))
        }
        Text("⭐ " + stringResource(R.string.favorites), Modifier.padding(horizontal = 20.dp, vertical = 8.dp), fontSize = 20.sp,
            color = MaterialTheme.colorScheme.primary)
        SearchScanBar(act, query, { query = it }, shown)
        if (act.favs.isEmpty()) {
            Text(stringResource(R.string.no_favs), Modifier.padding(20.dp), fontSize = 18.sp)
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 100.dp)) {
            items(shown) { StationRow(act, it, shown) }
        }
    }
    if (showPicker) {
        val apps = remember { act.installedApps() }
        AlertDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = { TextButton(onClick = { showPicker = false }) { Text(stringResource(R.string.close), fontSize = 18.sp) } },
            title = { Text(stringResource(R.string.choose_radio_app)) },
            text = {
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    items(apps) { a ->
                        Column(Modifier.fillMaxWidth().clickable {
                            act.prefs.radioPkg = a.pkg
                            act.launchPkg(a.pkg)
                            showPicker = false
                        }.padding(vertical = 10.dp)) {
                            Text(a.label, fontSize = 20.sp)
                            Text(a.pkg, fontSize = 12.sp, color = Color.White.copy(alpha = 0.5f))
                        }
                    }
                }
            }
        )
    }
}

@Composable
fun SettingsScreen(act: MainActivity) {
    val scope = rememberCoroutineScope()
    var info by remember { mutableStateOf<Updater.Info?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf(-1) }
    val ctx = act
    val sChecking = stringResource(R.string.checking)
    val sUpToDate = stringResource(R.string.up_to_date)
    val sErr = stringResource(R.string.update_error)

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp, 20.dp, 20.dp, 100.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Section(stringResource(R.string.language)) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf("ckb" to "کوردی", "ar" to "العربية", "en" to "English").forEach { (tag, name) ->
                    Button(onClick = { act.setLanguage(tag) }) {
                        Text(name, fontSize = 20.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
                    }
                }
            }
        }
        item { Section(stringResource(R.string.theme_color)) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                carColors.forEach { c ->
                    Box(
                        Modifier.size(54.dp).clip(CircleShape).background(Color(c))
                            .border(if (act.accent == c) 4.dp else 1.dp,
                                if (act.accent == c) Color.White else Color.Gray, CircleShape)
                            .clickable { act.changeAccent(c) }
                    )
                }
            }
        }
        item { Section(stringResource(R.string.updates)) }
        item { Text(stringResource(R.string.current_version, BuildConfig.VERSION_NAME), fontSize = 20.sp) }
        item {
            Button(onClick = {
                scope.launch {
                    status = sChecking
                    try {
                        val r = Updater.check()
                        info = r
                        status = if (r == null) sErr else if (r.code <= BuildConfig.VERSION_CODE) sUpToDate else null
                    } catch (e: Exception) { status = sErr }
                }
            }) { Text(stringResource(R.string.check_update), fontSize = 20.sp, modifier = Modifier.padding(8.dp)) }
        }
        status?.let { item { Text(it, fontSize = 20.sp) } }
        info?.let { i ->
            item { Text(stringResource(R.string.updates_total, i.total), fontSize = 20.sp) }
            if (i.code > BuildConfig.VERSION_CODE) {
                item { Text(stringResource(R.string.update_available, i.name), fontSize = 22.sp,
                    color = MaterialTheme.colorScheme.primary) }
                if (i.notes.isNotBlank()) item { Text(i.notes, fontSize = 16.sp, color = Color.White.copy(alpha = 0.7f)) }
                item {
                    val noApk = stringResource(R.string.no_apk)
                    if (progress in 0..99) {
                        Text(stringResource(R.string.downloading, progress), fontSize = 20.sp)
                    } else {
                        Button(onClick = {
                            val url = i.apk
                            if (url == null) { status = noApk; return@Button }
                            scope.launch {
                                try {
                                    progress = 0
                                    val f = Updater.download(ctx, url) { progress = it }
                                    progress = -1
                                    Updater.install(ctx, f)
                                } catch (e: Exception) { progress = -1; status = sErr }
                            }
                        }) { Text(stringResource(R.string.download_install), fontSize = 20.sp, modifier = Modifier.padding(8.dp)) }
                    }
                }
            }
        }
    }
}

@Composable
fun Section(text: String) {
    Text(text, fontSize = 22.sp, color = MaterialTheme.colorScheme.primary)
}