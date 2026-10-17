package com.kurdradio.byd

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.MutableContextWrapper
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.view.ViewGroup
import android.widget.Toast
import android.webkit.WebChromeClient
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
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
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
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaController
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionToken
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

// =====================================================================
//  DATA
// =====================================================================

data class Station(val name: String, val url: String, val kind: String)

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("p", Context.MODE_PRIVATE)

    var color: Int
        get() = sp.getInt("color", 0xFF00B3A4.toInt())
        set(v) { sp.edit().putInt("color", v).apply() }

    /** 0 = system, 1 = dark, 2 = light */
    var themeMode: Int
        get() = sp.getInt("themeMode", 0)
        set(v) { sp.edit().putInt("themeMode", v).apply() }

    var radioPkg: String?
        get() = sp.getString("radioPkg", null)
        set(v) { sp.edit().putString("radioPkg", v).apply() }

    var cacheTime: Long
        get() = sp.getLong("cacheTime", 0L)
        set(v) { sp.edit().putLong("cacheTime", v).apply() }

    var lastTab: Int
        get() = sp.getInt("lastTab", 0)
        set(v) { sp.edit().putInt("lastTab", v).apply() }

    var lastKind: String
        get() = sp.getString("lastKind", "ku") ?: "ku"
        set(v) { sp.edit().putString("lastKind", v).apply() }

    var ytUrl: String?
        get() = sp.getString("ytUrl", null)
        set(v) { sp.edit().putString("ytUrl", v).apply() }

    var langInit: Boolean
        get() = sp.getBoolean("langInit", false)
        set(v) { sp.edit().putBoolean("langInit", v).apply() }

    fun saveStations(key: String, list: List<Station>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("n", it.name).put("u", it.url).put("k", it.kind)) }
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
    private val fallbackHosts = listOf(
        "https://de1.api.radio-browser.info",
        "https://de2.api.radio-browser.info",
        "https://fi1.api.radio-browser.info",
        "https://nl1.api.radio-browser.info",
        "https://at1.api.radio-browser.info"
    )
    @Volatile private var hostList: List<String>? = null
    @Volatile private var good: String? = null

    private fun httpGet(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 6000
        c.readTimeout = 20000
        c.setRequestProperty("User-Agent", "KurdRadio/1.0")
        return c.inputStream.bufferedReader().use { it.readText() }
    }

    /** asks the directory for its live servers (the fixed ones are sometimes down) */
    @Synchronized
    private fun hosts(): List<String> {
        hostList?.let { return it }
        val found = try {
            val a = JSONArray(httpGet("https://all.api.radio-browser.info/json/servers"))
            (0 until a.length()).map { "https://" + a.getJSONObject(it).getString("name") }.distinct().shuffled()
        } catch (e: Exception) { emptyList() }
        val list = (found + fallbackHosts).distinct()
        hostList = list
        return list
    }

    private fun get(path: String): String {
        val order = listOfNotNull(good) + hosts().filter { it != good }
        for (h in order.take(6)) {
            try {
                val r = httpGet(h + path)
                good = h
                return r
            } catch (e: Exception) { /* next host */ }
        }
        throw java.io.IOException("network")
    }

    private fun parse(json: String, force: Boolean, out: MutableMap<String, Station>) {
        val a = JSONArray(json)
        val words = listOf("kurd", "کورد", "sorani", "kurmanji", "badini", "سۆرانی")
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            val url = o.optString("url_resolved").ifBlank { o.optString("url") }
            val name = o.optString("name").trim()
            if (!url.startsWith("http") || name.isBlank()) continue
            val meta = (name + " " + o.optString("language") + " " + o.optString("tags")).lowercase()
            val kurd = force || words.any { meta.contains(it) }
            if (kurd || !out.containsKey(url)) out[url] = Station(name, url, if (kurd) "ku" else "ar")
        }
    }

    /** Light download (about 10 requests) - done at most once a day. */
    suspend fun fetch(): List<Station> = withContext(Dispatchers.IO) {
        val q = "&hidebroken=true&order=clickcount&reverse=true"
        val jobs = listOf(
            "/json/stations/search?countrycode=IQ&limit=1000$q" to false,
            "/json/stations/search?language=kurdish&limit=600$q" to true,
            "/json/stations/search?language=sorani&limit=300$q" to true,
            "/json/stations/search?language=kurmanji&limit=300$q" to true,
            "/json/stations/search?tag=kurdish&limit=300$q" to true,
            "/json/stations/search?name=kurd&limit=300$q" to true,
            "/json/stations/search?name=erbil&limit=100$q" to true,
            "/json/stations/search?name=hawler&limit=100$q" to true,
            "/json/stations/search?name=sulaymaniyah&limit=100$q" to true,
            "/json/stations/search?name=duhok&limit=100$q" to true
        )
        val results = coroutineScope {
            jobs.map { (path, force) ->
                async { runCatching { get(path) }.getOrNull()?.let { it to force } }
            }.awaitAll()
        }
        val others = LinkedHashMap<String, Station>()
        for (r in results) {
            if (r != null) try { parse(r.first, r.second, others) } catch (e: Exception) { }
        }

        // your own curated list: stations.json in the GitHub repo (no app update needed)
        val result = LinkedHashMap<String, Station>()
        if (BuildConfig.GH_REPO.isNotBlank()) {
            try {
                val txt = httpGet("https://raw.githubusercontent.com/${BuildConfig.GH_REPO}/main/stations.json")
                val a = JSONArray(txt)
                for (i in 0 until a.length()) {
                    val o = a.getJSONObject(i)
                    val url = o.optString("url")
                    val name = o.optString("name")
                    if (url.startsWith("http") && name.isNotBlank()) {
                        result[url] = Station(name, url, o.optString("kind", "ku"))
                    }
                }
            } catch (e: Exception) { }
        }
        for ((u, st) in others) if (!result.containsKey(u)) result[u] = st
        if (result.isEmpty()) throw java.io.IOException("empty")
        result.values.toList()
    }

    /** Live search of the whole online directory by name. */
    suspend fun search(q: String): List<Station> = withContext(Dispatchers.IO) {
        val enc = java.net.URLEncoder.encode(q.trim(), "UTF-8")
        val json = get("/json/stations/search?name=$enc&limit=200&hidebroken=true&order=clickcount&reverse=true")
        val out = LinkedHashMap<String, Station>()
        parse(json, false, out)
        out.values.toList()
    }
}

// =====================================================================
//  UPDATER (GitHub Releases)
// =====================================================================

object Updater {
    data class Info(val code: Int, val name: String, val apk: String?, val notes: String, val total: Int)

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

    /**
     * Tries 3 ways to install the APK. Returns null when an installer was started,
     * otherwise a short message for the user.
     */
    suspend fun install(ctx: Context, file: File, url: String): String? {
        // 0) "install unknown apps" permission
        if (Build.VERSION.SDK_INT >= 26 && !ctx.packageManager.canRequestPackageInstalls()) {
            try {
                ctx.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                return ctx.getString(R.string.allow_install_hint)
            } catch (e: Exception) { /* no such settings screen on this head unit: keep trying */ }
        }
        // 1) the normal Android installer screen
        try {
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return null
        } catch (e: Exception) { /* no installer screen: try the session API */ }
        // 2) PackageInstaller session
        try {
            withContext(Dispatchers.IO) { sessionInstall(ctx, file) }
            return null
        } catch (e: Exception) { /* fall back to a manual install */ }
        // 3) save to Downloads + open the link in the browser
        val saved = withContext(Dispatchers.IO) { saveToDownloads(ctx, file) }
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) { }
        return if (saved) ctx.getString(R.string.update_saved) else ctx.getString(R.string.update_error)
    }

    private fun sessionInstall(ctx: Context, file: File) {
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val id = pi.createSession(params)
        val session = pi.openSession(id)
        try {
            file.inputStream().use { input ->
                session.openWrite("update", 0, file.length()).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            val intent = Intent(ctx, InstallReceiver::class.java).setAction("com.kurdradio.byd.INSTALL_RESULT")
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pending = PendingIntent.getBroadcast(ctx, id, intent, flags)
            session.commit(pending.intentSender)
        } finally {
            session.close()
        }
    }

    private fun saveToDownloads(ctx: Context, file: File): Boolean {
        if (Build.VERSION.SDK_INT < 29) return false
        return try {
            val cv = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, "BYDRadio.apk")
                put(MediaStore.Downloads.MIME_TYPE, "application/vnd.android.package-archive")
                put(MediaStore.Downloads.RELATIVE_PATH, "Download")
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: return false
            ctx.contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
            true
        } catch (e: Exception) { false }
    }
}

/** Receives the result of a PackageInstaller session and opens the confirm screen. */
class InstallReceiver : BroadcastReceiver() {
    @Suppress("DEPRECATION")
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm: Intent? = intent.getParcelableExtra(Intent.EXTRA_INTENT)
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(confirm)
                }
            }
            PackageInstaller.STATUS_SUCCESS -> { }
            else -> Toast.makeText(
                ctx, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Install failed", Toast.LENGTH_LONG
            ).show()
        }
    }
}

// =====================================================================
//  PLAYBACK SERVICE  (BYD media center + steering wheel buttons)
// =====================================================================

fun Station.toItem(): MediaItem = MediaItem.Builder()
    .setMediaId(url)
    .setUri(url)
    .setMediaMetadata(
        MediaMetadata.Builder().setTitle(name).setArtist("Kurd Radio")
            .setIsPlayable(true).setIsBrowsable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION).build()
    ).build()

fun Station.toBrowseItem(parent: String): MediaItem = MediaItem.Builder()
    .setMediaId("$parent|$url")
    .setUri(url)
    .setMediaMetadata(
        MediaMetadata.Builder().setTitle(name).setArtist("Kurd Radio")
            .setIsPlayable(true).setIsBrowsable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION).build()
    ).build()

fun folderItem(id: String, title: String): MediaItem = MediaItem.Builder()
    .setMediaId(id)
    .setMediaMetadata(
        MediaMetadata.Builder().setTitle(title).setIsBrowsable(true).setIsPlayable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED).build()
    ).build()

/** The queue is limited to a window around the chosen station (keeps IPC small). */
fun windowOf(list: List<Station>, index: Int, size: Int = 150): Pair<List<Station>, Int> {
    if (list.size <= size) return list to index
    val start = (index - size / 2).coerceIn(0, list.size - size)
    return list.subList(start, start + size) to (index - start)
}

@OptIn(UnstableApi::class)
class PlaybackService : MediaLibraryService() {
    private var session: MediaLibrarySession? = null
    private var errCount = 0

    private fun lists(): Map<String, List<Station>> {
        val p = Prefs(this)
        val all = (p.loadStations("custom") + p.loadStations("cache")).distinctBy { it.url }
        return linkedMapOf(
            "ku" to all.filter { it.kind == "ku" },
            "ar" to all.filter { it.kind == "ar" },
            "fav" to p.loadStations("favs")
        )
    }

    private fun folderTitle(id: String): String = when (id) {
        "ku" -> getString(R.string.kurdish)
        "ar" -> getString(R.string.arabic)
        "fav" -> getString(R.string.favorites_short)
        else -> getString(R.string.app_name)
    }

    private fun resolve(it: MediaItem): MediaItem =
        if (it.localConfiguration == null) it.buildUpon().setUri(it.mediaId.substringAfter("|")).build() else it

    private inner class Callback : MediaLibrarySession.Callback {
        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: MediaLibraryService.LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(folderItem("root", getString(R.string.app_name)), params))

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: MediaLibraryService.LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val ls = lists()
            val items: List<MediaItem> =
                if (parentId == "root") ls.keys.map { folderItem(it, folderTitle(it)) }
                else ls[parentId].orEmpty().map { it.toBrowseItem(parentId) }
            val from = if (pageSize == Int.MAX_VALUE) 0 else page * pageSize
            val pageItems = items.drop(from).take(pageSize)
            return Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.copyOf(pageItems), params))
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val ls = lists()
            if (ls.containsKey(mediaId) || mediaId == "root") {
                return Futures.immediateFuture(LibraryResult.ofItem(folderItem(mediaId, folderTitle(mediaId)), null))
            }
            val key = mediaId.substringBefore("|")
            val url = mediaId.substringAfter("|")
            val st = ls[key].orEmpty().firstOrNull { it.url == url }
            return if (st != null) Futures.immediateFuture(LibraryResult.ofItem(st.toBrowseItem(key), null))
            else Futures.immediateFuture(LibraryResult.ofError<MediaItem>(LibraryResult.RESULT_ERROR_BAD_VALUE))
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>
        ): ListenableFuture<MutableList<MediaItem>> =
            Futures.immediateFuture(mediaItems.map { resolve(it) }.toMutableList())

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            // one station picked from the car's media list => queue the whole list (next/prev work)
            if (mediaItems.size == 1 && mediaItems[0].mediaId.contains("|")) {
                val key = mediaItems[0].mediaId.substringBefore("|")
                val url = mediaItems[0].mediaId.substringAfter("|")
                val list = lists()[key].orEmpty()
                val idx = list.indexOfFirst { it.url == url }
                if (idx >= 0) {
                    val (sub, i) = windowOf(list, idx)
                    return Futures.immediateFuture(
                        MediaSession.MediaItemsWithStartPosition(sub.map { it.toItem() }, i, C.TIME_UNSET)
                    )
                }
            }
            return Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(mediaItems.map { resolve(it) }, startIndex, startPositionMs)
            )
        }
    }

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
        player.repeatMode = Player.REPEAT_MODE_ALL
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                // broken stream => jump to the next station (max 5 in a row)
                if (errCount++ < 5 && player.hasNextMediaItem()) {
                    player.seekToNextMediaItem()
                    player.prepare()
                    player.play()
                }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) { if (isPlaying) errCount = 0 }
        })
        session = MediaLibrarySession.Builder(this, player, Callback()).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onDestroy() {
        session?.run { player.release(); release() }
        session = null
        super.onDestroy()
    }
}

/** Keeps the process alive so YouTube (WebView) audio continues in the background. */
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

/** One WebView that stays alive (and full size) so YouTube never breaks when switching tabs. */
object YT {
    var web: WebView? = null

    fun get(ctx: Context, startUrl: String? = null): WebView {
        web?.let { return it }
        val w = WebView(MutableContextWrapper(ctx.applicationContext))
        w.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }
        w.webViewClient = WebViewClient()
        w.webChromeClient = WebChromeClient()
        w.loadUrl(if (startUrl != null && startUrl.contains("youtube.com")) startUrl else "https://m.youtube.com")
        web = w
        return w
    }
}

// =====================================================================
//  ACTIVITY
// =====================================================================

enum class Tab(val icon: String, val label: Int) {
    ONLINE("📻", R.string.tab_online),
    OFFLINE("📴", R.string.tab_offline),
    YOUTUBE("▶️", R.string.tab_youtube),
    SETTINGS("⚙️", R.string.tab_settings)
}

data class AppInfo(val label: String, val pkg: String)

fun looksLikeRadio(a: AppInfo): Boolean = listOf("radio", "fm", "راديو", "ڕادیۆ").any {
    a.pkg.lowercase().contains(it) || a.label.lowercase().contains(it)
}

class MainActivity : AppCompatActivity() {
    lateinit var prefs: Prefs
    private val scope: CoroutineScope = MainScope()

    var controller by mutableStateOf<MediaController?>(null)
    var nowTitle by mutableStateOf("")
    var nowUrl by mutableStateOf("")
    var isPlaying by mutableStateOf(false)
    var accent by mutableStateOf(0)
    var themeMode by mutableStateOf(0)
    var tab by mutableStateOf(Tab.ONLINE)
    var ytStarted by mutableStateOf(false)
    val favs = mutableStateListOf<Station>()
    val custom = mutableStateListOf<Station>()
    var stations by mutableStateOf<List<Station>>(emptyList())
    var loading by mutableStateOf(false)
    var loadError by mutableStateOf(false)
    var scanning by mutableStateOf(false)
    var onlineKind by mutableStateOf("ku")
    var onlineQuery by mutableStateOf("")
    var offlineQuery by mutableStateOf("")
    val listStates = mapOf("ku" to LazyListState(), "ar" to LazyListState(), "fav" to LazyListState(), "all" to LazyListState())
    val offlineListState = LazyListState()
    var searchResults by mutableStateOf<List<Station>>(emptyList())
    var searching by mutableStateOf(false)
    var playerStatus by mutableStateOf("")
    private var searchJob: Job? = null
    private var pending: Pair<List<Station>, Int>? = null
    private var retry = 0
    var updateInfo by mutableStateOf<Updater.Info?>(null)
    var updateProgress by mutableStateOf(-1)
    var updateError by mutableStateOf(false)
    var updateDetail by mutableStateOf("")

    private val handler = Handler(Looper.getMainLooper())
    private val scanStep = object : Runnable {
        override fun run() {
            if (!scanning) return
            controller?.seekToNextMediaItem()
            handler.postDelayed(this, 7000)
        }
    }

    private val notifPerm =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        accent = prefs.color
        themeMode = prefs.themeMode
        favs.addAll(prefs.loadStations("favs"))
        custom.addAll(prefs.loadStations("custom"))
        tab = Tab.values().getOrElse(prefs.lastTab) { Tab.ONLINE }
        onlineKind = prefs.lastKind
        if (tab == Tab.YOUTUBE || YT.web != null) startYoutubeService()

        if (!prefs.langInit) {
            prefs.langInit = true
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("ckb"))
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)

        connectPlayer()
        initStations()
        checkUpdateSilently()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val w = YT.web
                if (tab == Tab.YOUTUBE && w != null && w.canGoBack()) w.goBack()
                else moveTaskToBack(true)
            }
        })

        setContent { App(this) }
    }

    override fun onStop() {
        prefs.ytUrl = YT.web?.url
        // keep YouTube audio going while the app is in the background
        YT.web?.let { it.onResume(); it.resumeTimers() }
        super.onStop()
    }

    fun selectTab(t: Tab) {
        tab = t
        prefs.lastTab = t.ordinal
        if (t == Tab.YOUTUBE) {
            controller?.pause()
            startYoutubeService()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(scanStep)
        scope.cancel()
        super.onDestroy()
    }

    // ---------- stations ----------
    private fun initStations() {
        scope.launch {
            val c = withContext(Dispatchers.Default) { prefs.loadStations("cache") }
            stations = c
            loadStations()
        }
    }

    fun loadStations(force: Boolean = false) {
        val fresh = stations.size >= 30 &&
            System.currentTimeMillis() - prefs.cacheTime < 24L * 3600 * 1000
        if (!force && fresh) return
        scope.launch {
            loading = true
            loadError = false
            try {
                if (!force && stations.isNotEmpty()) delay(2000) // let the UI settle first
                val r = StationRepo.fetch()
                stations = r
                withContext(Dispatchers.IO) {
                    prefs.saveStations("cache", r)
                    if (r.size >= 30) prefs.cacheTime = System.currentTimeMillis()
                }
            } catch (e: Exception) {
                loadError = stations.isEmpty()
            }
            loading = false
        }
    }

    fun searchRemote(q: String) {
        searchJob?.cancel()
        if (q.trim().length < 2) { searchResults = emptyList(); searching = false; return }
        searchJob = scope.launch {
            delay(500)
            searching = true
            try {
                searchResults = StationRepo.search(q)
            } catch (e: Exception) {
                searchResults = emptyList()
            }
            searching = false
        }
    }

    fun allStations(): List<Station> = (custom.toList() + stations).distinctBy { it.url }

    fun isCustom(s: Station) = custom.any { it.url == s.url }

    fun addCustom(s: Station) {
        if (custom.none { it.url == s.url }) custom.add(0, s)
        prefs.saveStations("custom", custom.toList())
    }

    fun removeCustom(s: Station) {
        custom.removeAll { it.url == s.url }
        prefs.saveStations("custom", custom.toList())
    }

    fun toggleFav(s: Station) {
        val i = favs.indexOfFirst { it.url == s.url }
        if (i >= 0) favs.removeAt(i) else favs.add(s)
        prefs.saveStations("favs", favs.toList())
    }

    // ---------- player ----------
    private fun connectPlayer() {
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val f = MediaController.Builder(this, token).buildAsync()
        f.addListener({
            try {
                val c = f.get()
                c.addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(p: Boolean) { isPlaying = p }
                    override fun onMediaItemTransition(item: MediaItem?, reason: Int) { syncNow(item) }
                })
                syncNow(c.currentMediaItem)
                if (c.currentMediaItem == null) {
                    // service was restarted: show the last station, play button resumes it
                    prefs.loadStations("last").firstOrNull()?.let { nowTitle = it.name; nowUrl = it.url }
                }
                isPlaying = c.isPlaying
                controller = c
                playerStatus = ""
                pending?.let { pending = null; playList(it.first, it.second) }
            } catch (e: Exception) {
                playerStatus = "Player: " + (e.cause?.message ?: e.message ?: e.javaClass.simpleName)
                if (retry++ < 5) handler.postDelayed({ connectPlayer() }, 2000)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun syncNow(item: MediaItem?) {
        if (item == null) return
        nowUrl = item.localConfiguration?.uri?.toString() ?: item.mediaId
        nowTitle = item.mediaMetadata.title?.toString() ?: ""
        val kind = (stations.firstOrNull { it.url == nowUrl } ?: custom.firstOrNull { it.url == nowUrl })?.kind ?: "ku"
        prefs.saveStations("last", listOf(Station(nowTitle, nowUrl, kind)))
    }

    /** Sets the whole list as the queue, so steering-wheel next/previous work. */
    fun playList(list: List<Station>, index: Int) {
        val c = controller
        if (c == null) { pending = list to index; return }
        if (index !in list.indices) return
        val (sub, idx) = windowOf(list, index)
        c.setMediaItems(sub.map { it.toItem() }, idx, 0L)
        c.prepare()
        c.play()
        nowTitle = list[index].name
        nowUrl = list[index].url
    }

    fun playManual(list: List<Station>, index: Int) {
        stopScan()
        playList(list, index)
    }

    fun step(d: Int) {
        val c = controller ?: return
        stopScan()
        if (d > 0) c.seekToNextMediaItem() else c.seekToPreviousMediaItem()
    }

    fun togglePlay() {
        val c = controller ?: return
        if (c.mediaItemCount == 0) { resumeLast(); return }
        if (c.isPlaying) c.pause() else c.play()
    }

    private fun resumeLast() {
        val last = prefs.loadStations("last").firstOrNull() ?: return
        val list = allStations().filter { it.kind == last.kind }.ifEmpty { listOf(last) }
        val idx = list.indexOfFirst { it.url == last.url }
        if (idx >= 0) playList(list, idx) else playList(listOf(last), 0)
    }

    /** Auto scan: each station plays ~7 seconds; tap stop to keep the current one. */
    fun startScan(list: List<Station>) {
        if (list.isEmpty()) return
        val cur = list.indexOfFirst { it.url == nowUrl }
        playList(list, if (cur >= 0) (cur + 1) % list.size else 0)
        scanning = true
        handler.removeCallbacks(scanStep)
        handler.postDelayed(scanStep, 7000)
    }

    fun stopScan() {
        scanning = false
        handler.removeCallbacks(scanStep)
    }

    // ---------- in-app update (shows a dialog by itself when a new release exists) ----------
    fun checkUpdateSilently() {
        scope.launch {
            try {
                val r = Updater.check()
                if (r != null && r.code > BuildConfig.VERSION_CODE) updateInfo = r
            } catch (e: Exception) { }
        }
    }

    fun runUpdate() {
        val info = updateInfo ?: return
        val url = info.apk
        if (url == null) { updateError = true; updateDetail = getString(R.string.no_apk); return }
        scope.launch {
            updateError = false
            updateDetail = ""
            try {
                updateProgress = 0
                val f = Updater.download(this@MainActivity, url) { updateProgress = it }
                updateProgress = -1
                val msg = Updater.install(this@MainActivity, f, url)
                if (msg != null) updateDetail = msg
            } catch (e: Exception) {
                updateProgress = -1
                updateError = true
                updateDetail = "${e.javaClass.simpleName}: ${e.message}"
            }
        }
    }

    // ---------- misc ----------
    fun startYoutubeService() {
        if (ytStarted) return
        ytStarted = true
        ContextCompat.startForegroundService(this, Intent(this, YouTubeService::class.java))
    }

    fun setLanguage(tag: String) {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
    }

    fun changeAccent(c: Int) { accent = c; prefs.color = c }

    fun changeTheme(m: Int) { themeMode = m; prefs.themeMode = m }

    // ---------- FM / AM (the car's own radio app) ----------
    private val knownRadioPkgs = listOf(
        "com.byd.radio", "com.byd.fmradio", "com.byd.dilink.radio", "com.byd.dilink.fmradio",
        "com.byd.carradio", "com.byd.tuner", "com.android.fmradio", "com.mediatek.fmradio",
        "com.sprd.fmradio", "com.android.car.radio", "com.qualcomm.fmradio"
    )

    fun installedApps(): List<AppInfo> {
        val pm = packageManager
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(i, 0)
            .map { AppInfo(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
            .distinctBy { it.pkg }
            .sortedWith(compareBy({ !looksLikeRadio(it) }, { it.label.lowercase() }))
    }

    fun launchPkg(pkg: String): Boolean {
        val i = packageManager.getLaunchIntentForPackage(pkg) ?: return false
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(i)
        return true
    }

    /** true = launched; false => UI asks the user to pick the radio app once. */
    fun openCarRadio(): Boolean {
        prefs.radioPkg?.let { if (launchPkg(it)) return true }
        for (p in knownRadioPkgs) if (launchPkg(p)) { prefs.radioPkg = p; return true }
        val found = installedApps().filter { looksLikeRadio(it) }
        if (found.size == 1 && launchPkg(found[0].pkg)) { prefs.radioPkg = found[0].pkg; return true }
        return false
    }
}

// =====================================================================
//  UI
// =====================================================================

data class Pal(val sidebar: Color, val card: Color)

val LocalPal = staticCompositionLocalOf { Pal(Color(0xFF0B1117), Color(0xFF1F2E3B)) }

val carColors = listOf(
    0xFF00B3A4, 0xFF1E88E5, 0xFFE53935, 0xFFFFFFFF,
    0xFFFB8C00, 0xFF7CB342, 0xFF8E24AA, 0xFFFDD835
).map { it.toInt() }

@Composable
fun App(act: MainActivity) {
    val dark = when (act.themeMode) {
        1 -> true
        2 -> false
        else -> isSystemInDarkTheme()
    }
    var accent = Color(act.accent)
    if (!dark && accent.luminance() > 0.85f) accent = Color(0xFF00897B)
    val onAcc = if (accent.luminance() > 0.5f) Color.Black else Color.White
    val scheme = if (dark) darkColorScheme(
        primary = accent, secondary = accent, onPrimary = onAcc,
        background = Color(0xFF101820), surface = Color(0xFF16212B),
        onSurface = Color.White, onBackground = Color.White
    ) else lightColorScheme(
        primary = accent, secondary = accent, onPrimary = onAcc,
        background = Color(0xFFF4F7FA), surface = Color.White,
        onSurface = Color(0xFF101820), onBackground = Color(0xFF101820)
    )
    val pal = if (dark) Pal(Color(0xFF0B1117), Color(0xFF1F2E3B)) else Pal(Color(0xFFE3E9EF), Color(0xFFDDE5EC))
    CompositionLocalProvider(LocalPal provides pal) {
        MaterialTheme(colorScheme = scheme) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { Root(act) }
            val info = act.updateInfo
            if (info != null) {
                AlertDialog(
                    onDismissRequest = { if (act.updateProgress < 0) act.updateInfo = null },
                    title = { Text(stringResource(R.string.update_available, info.name)) },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (info.notes.isNotBlank()) Text(info.notes, fontSize = 16.sp)
                            if (act.updateProgress in 0..99) {
                                Text(stringResource(R.string.downloading, act.updateProgress), fontSize = 18.sp)
                            }
                            if (act.updateError) {
                                Text(stringResource(R.string.update_error), fontSize = 16.sp,
                                    color = MaterialTheme.colorScheme.error)
                            }
                            if (act.updateDetail.isNotBlank()) Text(act.updateDetail, fontSize = 15.sp)
                        }
                    },
                    confirmButton = {
                        TextButton(
                            onClick = { act.runUpdate() },
                            enabled = act.updateProgress !in 0..99
                        ) { Text(stringResource(R.string.download_install), fontSize = 18.sp) }
                    },
                    dismissButton = {
                        TextButton(onClick = { act.updateInfo = null }) {
                            Text(stringResource(R.string.close), fontSize = 18.sp)
                        }
                    }
                )
            }
        }
    }
}

@Composable
fun Root(act: MainActivity) {
    val realDir = LocalLayoutDirection.current
    // the sidebar is always on the LEFT; content follows the language direction
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(Modifier.fillMaxSize()) {
            Sidebar(act)
            CompositionLocalProvider(LocalLayoutDirection provides realDir) {
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    // YouTube stays full-size underneath so it never breaks
                    if (act.ytStarted) {
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { ctx ->
                                YT.get(ctx, act.prefs.ytUrl).also { (it.parent as? ViewGroup)?.removeView(it) }
                            }
                        )
                    }
                    if (act.tab != Tab.YOUTUBE) {
                        Box(
                            Modifier.fillMaxSize()
                                .background(MaterialTheme.colorScheme.background)
                                .pointerInput(Unit) {
                                    awaitPointerEventScope { while (true) { awaitPointerEvent() } }
                                }
                        ) {
                            when (act.tab) {
                                Tab.ONLINE -> OnlineScreen(act)
                                Tab.OFFLINE -> OfflineScreen(act)
                                Tab.SETTINGS -> SettingsScreen(act)
                                else -> {}
                            }
                        }
                        MiniPlayer(act, Modifier.align(Alignment.BottomCenter))
                    }
                    if (act.playerStatus.isNotBlank()) {
                        Text(
                            act.playerStatus,
                            Modifier.align(Alignment.TopCenter).padding(8.dp)
                                .background(Color(0xCCB00020)).padding(8.dp),
                            color = Color.White, fontSize = 14.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun Sidebar(act: MainActivity) {
    val accent = MaterialTheme.colorScheme.primary
    Column(
        Modifier.width(120.dp).fillMaxHeight().background(LocalPal.current.sidebar).padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Tab.values().forEach { t ->
            val sel = act.tab == t
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (sel) accent.copy(alpha = 0.22f) else Color.Transparent)
                    .clickable { act.selectTab(t) }.padding(vertical = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(t.icon, fontSize = 34.sp)
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(t.label), fontSize = 13.sp,
                    color = if (sel) accent else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
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
            .background(LocalPal.current.card).padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(if (act.scanning) "🔍" else "📻", fontSize = 26.sp)
        Spacer(Modifier.width(12.dp))
        Text(act.nowTitle, Modifier.weight(1f), fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("⏮", fontSize = 30.sp, modifier = Modifier.clickable { act.step(-1) }.padding(10.dp))
        Box(
            Modifier.size(56.dp).clip(CircleShape).background(accent).clickable { act.togglePlay() },
            contentAlignment = Alignment.Center
        ) { Text(if (act.isPlaying) "⏸" else "▶", fontSize = 26.sp, color = MaterialTheme.colorScheme.onPrimary) }
        Text("⏭", fontSize = 30.sp, modifier = Modifier.clickable { act.step(1) }.padding(10.dp))
    }
}

@Composable
fun StationRow(act: MainActivity, s: Station, list: List<Station>, index: Int) {
    val fav = act.favs.any { it.url == s.url }
    val playing = act.nowUrl == s.url
    Row(
        Modifier.fillMaxWidth().clickable { act.playManual(list, index) }
            .background(if (playing) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else Color.Transparent)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("📻", fontSize = 26.sp)
        Spacer(Modifier.width(14.dp))
        Text(s.name, Modifier.weight(1f), fontSize = 21.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (act.isCustom(s)) {
            Text("🗑", fontSize = 24.sp, modifier = Modifier.clickable { act.removeCustom(s) }.padding(8.dp))
        }
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
    val kind = act.onlineKind
    val query = act.onlineQuery
    var showAdd by remember { mutableStateOf(false) }
    val pal = LocalPal.current

    LaunchedEffect(kind, query) { if (kind == "all") act.searchRemote(query) }

    val all = remember(act.stations, act.custom.size) { act.allStations() }
    val base = remember(kind, all, act.favs.size, act.searchResults) {
        when (kind) {
            "fav" -> act.favs.toList()
            "all" -> act.searchResults
            else -> all.filter { it.kind == kind }
        }
    }
    val shown = remember(base, query, kind) {
        if (kind == "all" || query.isBlank()) base else base.filter { it.name.contains(query, ignoreCase = true) }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            listOf(
                "ku" to "🟢 " + stringResource(R.string.kurdish),
                "ar" to "🔵 " + stringResource(R.string.arabic),
                "fav" to "⭐ " + stringResource(R.string.favorites_short),
                "all" to "🌐"
            ).forEach { (k, l) ->
                val sel = kind == k
                Button(
                    onClick = { act.onlineKind = k; act.prefs.lastKind = k },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (sel) MaterialTheme.colorScheme.primary else pal.card,
                        contentColor = if (sel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                    )
                ) { Text(l, fontSize = 20.sp, modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)) }
            }
            Text("➕", fontSize = 28.sp, modifier = Modifier.clickable { showAdd = true }.padding(8.dp))
            Text("🔄", fontSize = 28.sp, modifier = Modifier.clickable { act.loadStations(true) }.padding(8.dp))
            Text("${shown.size}", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        }
        SearchScanBar(act, query, { act.onlineQuery = it }, shown)
        if (act.loading || (kind == "all" && act.searching)) {
            Text(stringResource(R.string.loading), Modifier.padding(20.dp), fontSize = 20.sp)
        }
        if (kind == "all" && !act.searching && shown.isEmpty()) {
            Text(stringResource(R.string.search_online_hint), Modifier.padding(20.dp), fontSize = 18.sp)
        }
        if (act.loadError) {
            Text(stringResource(R.string.no_net), Modifier.padding(20.dp), fontSize = 20.sp)
            Button(onClick = { act.loadStations(true) }, modifier = Modifier.padding(horizontal = 20.dp)) {
                Text(stringResource(R.string.retry), fontSize = 18.sp)
            }
        }
        if (kind == "fav" && shown.isEmpty()) Text(stringResource(R.string.no_favs), Modifier.padding(20.dp), fontSize = 18.sp)
        LazyColumn(Modifier.fillMaxSize(), state = act.listStates.getValue(kind), contentPadding = PaddingValues(bottom = 100.dp)) {
            itemsIndexed(shown) { i, s -> StationRow(act, s, shown, i) }
        }
    }

    if (showAdd) {
        var name by remember { mutableStateOf("") }
        var url by remember { mutableStateOf("") }
        var k by remember { mutableStateOf("ku") }
        AlertDialog(
            onDismissRequest = { showAdd = false },
            confirmButton = {
                TextButton(onClick = {
                    if (name.isNotBlank() && url.trim().startsWith("http")) {
                        act.addCustom(Station(name.trim(), url.trim(), k))
                        showAdd = false
                    }
                }) { Text(stringResource(R.string.save), fontSize = 18.sp) }
            },
            dismissButton = {
                TextButton(onClick = { showAdd = false }) { Text(stringResource(R.string.close), fontSize = 18.sp) }
            },
            title = { Text(stringResource(R.string.add_station)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = name, onValueChange = { name = it }, singleLine = true,
                        label = { Text(stringResource(R.string.station_name)) }
                    )
                    OutlinedTextField(
                        value = url, onValueChange = { url = it }, singleLine = true,
                        label = { Text(stringResource(R.string.station_url)) }
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        listOf("ku" to R.string.kurdish, "ar" to R.string.arabic).forEach { (key, lbl) ->
                            Button(
                                onClick = { k = key },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (k == key) MaterialTheme.colorScheme.primary else pal.card,
                                    contentColor = if (k == key) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                                )
                            ) { Text(stringResource(lbl), fontSize = 18.sp) }
                        }
                    }
                }
            }
        )
    }
}

@Composable
fun OfflineScreen(act: MainActivity) {
    var showPicker by remember { mutableStateOf(false) }
    val query = act.offlineQuery
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
                Text(stringResource(R.string.fm_am_desc), fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
            }
            Text("⋯", fontSize = 30.sp, modifier = Modifier.clickable { showPicker = true }.padding(10.dp))
        }
        Text("⭐ " + stringResource(R.string.favorites), Modifier.padding(horizontal = 20.dp, vertical = 8.dp), fontSize = 20.sp,
            color = MaterialTheme.colorScheme.primary)
        SearchScanBar(act, query, { act.offlineQuery = it }, shown)
        if (act.favs.isEmpty()) {
            Text(stringResource(R.string.no_favs), Modifier.padding(20.dp), fontSize = 18.sp)
        }
        LazyColumn(Modifier.fillMaxSize(), state = act.offlineListState, contentPadding = PaddingValues(bottom = 100.dp)) {
            itemsIndexed(shown) { i, s -> StationRow(act, s, shown, i) }
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
                            Text(a.pkg, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                        }
                    }
                }
            }
        )
    }
}

@Composable
fun ChoiceButton(text: String, selected: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primary else LocalPal.current.card,
            contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
        )
    ) { Text(text, fontSize = 20.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)) }
}

@Composable
fun SettingsScreen(act: MainActivity) {
    val scope = rememberCoroutineScope()
    var info by remember { mutableStateOf<Updater.Info?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf(-1) }
    val sChecking = stringResource(R.string.checking)
    val sUpToDate = stringResource(R.string.up_to_date)
    val sErr = stringResource(R.string.update_error)
    val noApk = stringResource(R.string.no_apk)

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp, 20.dp, 20.dp, 100.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
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
        item { Section(stringResource(R.string.theme)) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ChoiceButton("🌓 " + stringResource(R.string.theme_system), act.themeMode == 0) { act.changeTheme(0) }
                ChoiceButton("🌙 " + stringResource(R.string.theme_dark), act.themeMode == 1) { act.changeTheme(1) }
                ChoiceButton("☀️ " + stringResource(R.string.theme_light), act.themeMode == 2) { act.changeTheme(2) }
            }
        }
        item { Section(stringResource(R.string.theme_color)) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                carColors.forEach { c ->
                    Box(
                        Modifier.size(54.dp).clip(CircleShape).background(Color(c))
                            .border(
                                if (act.accent == c) 4.dp else 1.dp,
                                if (act.accent == c) MaterialTheme.colorScheme.onSurface else Color.Gray,
                                CircleShape
                            )
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
                item {
                    Text(stringResource(R.string.update_available, i.name), fontSize = 22.sp,
                        color = MaterialTheme.colorScheme.primary)
                }
                if (i.notes.isNotBlank()) item {
                    Text(i.notes, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                }
                item {
                    Button(onClick = {
                        if (i.apk == null) { status = noApk } else { act.updateInfo = i; act.runUpdate() }
                    }) { Text(stringResource(R.string.download_install), fontSize = 20.sp, modifier = Modifier.padding(8.dp)) }
                }
            }
        }
    }
}

@Composable
fun Section(text: String) {
    Text(text, fontSize = 22.sp, color = MaterialTheme.colorScheme.primary)
}
