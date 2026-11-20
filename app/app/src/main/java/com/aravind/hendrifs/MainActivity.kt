package com.aravind.hendrifs

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.aravind.hendrifs.protocol.IdCoreProtocol
import com.aravind.hendrifs.protocol.IdCoreProtocol.Control
import com.aravind.hendrifs.usb.FakeAmpTransport
import com.aravind.hendrifs.usb.UsbHidTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The UI is the Hendrifs page (assets/www/index.html) in a full-screen WebView.
 * It talks to this activity through `HendrifsNative.send(json)`; the activity pushes amp state
 * back with `PT.onState(...)` (only the parts that changed, at most once per frame) and `PT.onTuner(...)`.
 * All USB work happens in [AmpController].
 */
class MainActivity : ComponentActivity() {
    private val amp = AmpController()
    private lateinit var usb: UsbManager
    private lateinit var web: WebView
    private lateinit var library: LibraryStore
    private lateinit var tunerAudio: TunerAudio
    private var songs = JSONArray()
    private var pageReady = false
    private val main = Handler(Looper.getMainLooper())
    private var reconnectTry = 0
    private var wantAudioTuner = false
    private var pendingExport: Pair<String, String>? = null

    private val sent = HashMap<String, String>()   // last JSON pushed per state field

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = intent.usbDevice() ?: return
            when (intent.action) {
                ACTION_PERMISSION ->
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) open(device)
                    else amp.say("USB permission was not given. Tap the status lamp to try again.")
                UsbManager.ACTION_USB_DEVICE_ATTACHED ->
                    if (device.vendorId == IdCoreProtocol.VENDOR_ID && !amp.isConnected) { reconnectTry = 0; findAndConnect(quiet = true) }
                UsbManager.ACTION_USB_DEVICE_DETACHED ->
                    if (device.vendorId == IdCoreProtocol.VENDOR_ID && !amp.state.value.demo) {
                        main.removeCallbacksAndMessages(RECONNECT)
                        stopAudioTuner()
                        amp.disconnect(); amp.say("Amp unplugged")
                    }
            }
        }
    }

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startAudioTuner() else amp.say("Using the amp's own tuner. Allow the microphone for a finer needle.")
    }

    private val openBstar = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach(::importBstar)
    }

    private val saveBstar = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val (_, xml) = pendingExport ?: return@registerForActivityResult
        pendingExport = null
        if (uri == null) return@registerForActivityResult
        runCatching { contentResolver.openOutputStream(uri)!!.use { it.write(xml.toByteArray()) } }
            .onSuccess { amp.say("Saved. Open it in Architect on your computer.") }
            .onFailure { amp.say("Could not save the file.") }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            Log.e("Hendrifs", "crash in ${t.name}", e)
            getSharedPreferences("crash", MODE_PRIVATE).edit().putString("last", "${t.name}: ${e.stackTraceToString().take(4000)}").commit()
            previous?.uncaughtException(t, e)
        }

        usb = getSystemService(USB_SERVICE) as UsbManager
        library = LibraryStore(filesDir)
        tunerAudio = TunerAudio(this) { note, cents, hz -> amp.audioTuner(note, cents, hz) }
        songs = runCatching { JSONArray(assets.open("presets/songs.json").bufferedReader().readText()) }.getOrDefault(JSONArray())
        loadCommunityPatches()
        amp.onLinkLost = { main.post { stopAudioTuner(); scheduleReconnect() } }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)   // a rehearsal tool: don't sleep mid-song
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        web = WebView(this).apply {
            setBackgroundColor(Color.parseColor("#121110"))
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false          // assets are still readable through file:///android_asset
            settings.allowContentAccess = false
            settings.mediaPlaybackRequiresUserGesture = true
            settings.textZoom = 100                   // the design is pixel-exact; ignore system font scaling
            isHapticFeedbackEnabled = true
            overScrollMode = WebView.OVER_SCROLL_NEVER
            addJavascriptInterface(Bridge(), "HendrifsNative")
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest) = true  // stay inside the app
                override fun onPageFinished(view: WebView, url: String) {
                    pageReady = true; sent.clear()
                    js("PT.onSongs($songs)"); pushLibrary(); pushState(amp.state.value)
                }
            }
            loadUrl("file:///android_asset/www/index.html")
        }
        setContentView(web)

        ContextCompat.registerReceiver(this, receiver,
            IntentFilter().apply { addAction(ACTION_PERMISSION); addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED); addAction(UsbManager.ACTION_USB_DEVICE_DETACHED) },
            ContextCompat.RECEIVER_NOT_EXPORTED)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { amp.state.collect { pushState(it); delay(16) } }        // StateFlow keeps only the newest: at most one push per frame
                launch { amp.tuner.collect { pushTuner(it); delay(30) } }
            }
        }

        handleIntent(intent)
        if (intent.usbDevice() == null) findAndConnect(quiet = true)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        intent.usbDevice()?.let { reconnectTry = 0; open(it); return }
        when (intent.action) {
            Intent.ACTION_VIEW -> intent.data?.let(::importBstar)
            Intent.ACTION_SEND -> intent.streamUri()?.let(::importBstar)
        }
    }

    override fun onPause() {
        super.onPause()
        stopAudioTuner()   // never keep the microphone open in the background
    }

    override fun onResume() {
        super.onResume()
        if (wantAudioTuner && amp.state.value.tunerOn) startAudioTuner()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        unregisterReceiver(receiver)
        stopAudioTuner()
        amp.disconnect()
        web.destroy()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- USB

    private fun findAndConnect(quiet: Boolean = false): Boolean {
        val device = usb.deviceList.values.firstOrNull { it.vendorId == IdCoreProtocol.VENDOR_ID }
        if (device == null) {
            if (!quiet) amp.say("No Blackstar amp found. Check the USB-C cable and that the amp is switched on.")
            return false
        }
        if (usb.hasPermission(device)) open(device)
        else {
            val pi = PendingIntent.getBroadcast(this, 0, Intent(ACTION_PERMISSION).setPackage(packageName),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            usb.requestPermission(device, pi)
        }
        return true
    }

    private fun open(device: UsbDevice) {
        main.removeCallbacksAndMessages(RECONNECT)
        runCatching { UsbHidTransport(usb, device) }
            .onSuccess { reconnectTry = 0; amp.connect(it, demo = false); amp.say("Connected to ${it.description.substringBefore(" ·")}") }
            .onFailure {
                amp.disconnect()
                Log.w("Hendrifs", "open failed", it)
                if (reconnectTry < 3) scheduleReconnect() else amp.say("Could not open the amp. Unplug it, plug it back in, and tap the status lamp.")
            }
    }

    /** Retries 1 s, 2 s, 4 s after the link drops, as long as the amp is still plugged in. */
    private fun scheduleReconnect() {
        if (reconnectTry >= 3) { amp.say("Lost the amp. Check the cable, then tap the status lamp."); return }
        val wait = 1000L shl reconnectTry
        reconnectTry++
        amp.say("Connection dropped. Reconnecting…")
        main.postAtTime({ if (!amp.isConnected) findAndConnect(quiet = true) }, RECONNECT, android.os.SystemClock.uptimeMillis() + wait)
    }

    // ---------------------------------------------------------------- tuner

    private fun tuner(on: Boolean) {
        amp.setTuner(on)
        wantAudioTuner = on
        if (!on) { stopAudioTuner(); return }
        if (amp.state.value.demo) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startAudioTuner()
        else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun startAudioTuner() {
        if (!amp.state.value.tunerOn || tunerAudio.isRunning) return
        if (!tunerAudio.start()) Log.i("Hendrifs", "no USB audio input; using the amp's tuner readings")
    }

    private fun stopAudioTuner() { if (tunerAudio.isRunning) tunerAudio.stop() }

    // ---------------------------------------------------------------- .bstar

    /**
     * Architect Community patches bundled at build time (.bstar files in assets/community, see tools/sync_community.py).
     * They go first on the Songs shelf under "Community", exactly as their authors made them.
     */
    private fun loadCommunityPatches() {
        val files = runCatching { assets.list("community")?.filter { it.endsWith(".bstar", true) } }.getOrNull().orEmpty()
        val list = files.sorted().mapNotNull { f ->
            runCatching { assets.open("community/$f").use(BstarFormat::parse) }.getOrNull()?.let { b ->
                JSONObject().put("id", "community-" + f.lowercase().replace(Regex("[^a-z0-9]+"), "-"))
                    .put("name", b.name).put("artist", b.creator.ifEmpty { "Community" }).put("genre", "Community")
                    .put("values", JSONObject().apply { b.values.forEach { (c, v) -> put(c.name, v) } })
            }
        }
        if (list.isEmpty()) return
        val merged = JSONArray(); list.forEach(merged::put)
        for (i in 0 until songs.length()) merged.put(songs.get(i))
        songs = merged
    }

    private fun importBstar(uri: Uri) {
        lifecycleScope.launch {
            val p = withContext(Dispatchers.IO) { runCatching { contentResolver.openInputStream(uri)!!.use(BstarFormat::parse) } }
            p.onSuccess { b ->
                library.savePatch(b.name, b.genre, b.values, b.creator, "import"); pushLibrary()
                amp.say("Imported \"${b.name}\" into the library")
            }.onFailure { amp.say("That file isn't an Architect patch (.bstar).") }
        }
    }

    // ---------------------------------------------------------------- JS bridge

    private inner class Bridge {
        @JavascriptInterface
        fun send(json: String) {
            val cmd = runCatching { JSONObject(json) }.getOrNull() ?: return
            runOnUiThread {
                runCatching { handle(cmd) }.onFailure {
                    Log.e("Hendrifs", "command ${cmd.optString("a")} failed", it)
                    amp.say("Something went wrong. Tap Settings → Service → USB log for details.")
                }
            }
        }
    }

    private fun songValues(o: JSONObject): Map<Control, Int> {
        val v = o.getJSONObject("values")
        return buildMap { v.keys().forEach { k -> Control.byName(k)?.let { put(it, v.getInt(k)) } } }
    }

    private fun song(id: String): JSONObject? = (0 until songs.length()).map { songs.getJSONObject(it) }.firstOrNull { it.getString("id") == id }

    private fun handle(c: JSONObject) {
        when (c.getString("a")) {
            "connect" -> { reconnectTry = 0; findAndConnect() }
            "demo" -> { amp.connect(FakeAmpTransport(), demo = true); amp.say("Demo amp: nothing is sent over USB") }
            "disconnect" -> { main.removeCallbacksAndMessages(RECONNECT); stopAudioTuner(); amp.disconnect() }
            "refresh" -> amp.refresh()
            "set" -> Control.byName(c.getString("c"))?.let { amp.set(it, c.getInt("v")) }
            "global" -> if (c.getInt("id") == IdCoreProtocol.Global.TUNER) tuner(c.getInt("v") == 1) else amp.setGlobal(c.getInt("id"), c.getInt("v"))
            "tuner" -> tuner(c.getBoolean("on"))
            "tunerRef" -> tunerAudio.reference = c.getDouble("hz").coerceIn(415.0, 466.0)
            "stomp" -> amp.stomp(c.getInt("fx"))
            "patch" -> amp.selectPatch(c.getInt("slot"))
            "store" -> if (c.optBoolean("unlocked")) amp.storeCurrent(c.getInt("slot"), c.getString("name"))
                       else amp.say("Saving to the amp is locked. Flip the Write switch on the rear panel first.")
            "raw" -> runCatching { IdCoreProtocol.parseHex(c.getString("hex")) }.onSuccess(amp::send).onFailure { amp.say("Bad hex: ${it.message}") }
            "log" -> js("PT.onLog(${JSONObject.quote(amp.logAsText().takeLast(60_000))})")
            "clearLog" -> amp.clearLog()
            "shareLog" -> share("Hendrifs USB log", amp.logAsText() + crashNote())
            "dismiss" -> amp.say(null)
            "haptic" -> web.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
            "songLoad" -> song(c.getString("id"))?.let { s -> amp.apply(songValues(s)); amp.say("${s.getString("name")} · ${s.optString("artist")}") }
            "songSave" -> song(c.getString("id"))?.let { s ->
                library.savePatch(s.getString("name"), s.optString("genre", "Other"), songValues(s), s.optString("artist"), "builtin")
                pushLibrary(); amp.say("Copied to your library")
            }
            "libSave" -> { library.savePatch(c.getString("name"), c.optString("genre", "Other"), amp.state.value.values); pushLibrary(); amp.say("Saved to the library") }
            "libLoad" -> library.get(c.getLong("id"))?.let { e -> if (e.kind == "patch") { amp.apply(e.values); amp.say("Loaded ${e.name}") } }
            "libDelete" -> { library.delete(c.getLong("id")); pushLibrary() }
            "libShare" -> library.get(c.getLong("id"))?.let { e ->
                if (e.kind != "patch") { amp.say("Only single patches can be exported"); return }
                pendingExport = e.name to BstarFormat.write(e.name, e.genre, e.values)
                saveBstar.launch(e.name.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().ifEmpty { "patch" } + ".bstar")
            }
            "import" -> openBstar.launch(arrayOf("*/*"))
            "backup" -> backup()
            "restore" -> if (c.optBoolean("unlocked")) restore(c.getLong("id"))
                         else amp.say("Saving to the amp is locked. Flip the Write switch on the rear panel first.")
        }
    }

    private fun crashNote(): String = getSharedPreferences("crash", MODE_PRIVATE).getString("last", null)?.let { "\n\n--- last crash ---\n$it" } ?: ""

    private fun backup() {
        val s = amp.state.value
        val slots = (1..IdCoreProtocol.SLOTS).mapNotNull { i -> amp.patchTable(i)?.let { (s.names[i] ?: "Patch $i") to it } }
        if (slots.size < IdCoreProtocol.SLOTS) { amp.say("Still reading patches (${slots.size}/6). Try again in a moment."); amp.refresh(); return }
        library.saveBackup("Backup " + SimpleDateFormat("d MMM HH:mm", Locale.US).format(Date()), slots)
        pushLibrary(); amp.say("All 6 patches backed up on this device")
    }

    private fun restore(id: Long) {
        val e = library.get(id) ?: return
        if (e.kind != "backup") { amp.say("Only backups can be restored"); return }
        e.backup.forEachIndexed { i, (name, t) -> amp.restore(i + 1, name, t) }
        amp.say("Restoring 6 patches…")
    }

    private fun share(subject: String, text: String) {
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"; putExtra(Intent.EXTRA_SUBJECT, subject); putExtra(Intent.EXTRA_TEXT, text)
        }, subject))
    }

    // ---------------------------------------------------------------- native → page

    private fun js(code: String) { if (pageReady) web.evaluateJavascript(code, null) }

    /** Sends only the fields that changed since the last push. */
    private fun pushState(s: AmpState) {
        if (!pageReady) return
        val fields = linkedMapOf<String, Any>(
            "connected" to s.connected, "demo" to s.demo, "link" to s.link, "model" to s.link.substringBefore(" ·"),
            "version" to s.version, "ready" to s.ready,
            "slot" to (s.slot ?: 0),
            "names" to JSONArray((1..IdCoreProtocol.SLOTS).map { s.names[it] ?: "" }),
            "presetsRead" to s.patchesRead,
            "values" to JSONObject().apply { s.values.forEach { (c, v) -> put(c.name, v) } },
            "globals" to JSONObject().apply { s.globals.forEach { (k, v) -> if (v != 0) put(k.toString(), v) } },
            "tunerOn" to s.tunerOn,
            "message" to (s.message ?: JSONObject.NULL),
        )
        val diff = JSONObject()
        fields.forEach { (k, v) ->
            val str = if (v is String) JSONObject.quote(v) else v.toString()
            if (sent[k] != str) { sent[k] = str; diff.put(k, v) }
        }
        if (diff.length() > 0) js("PT.onState($diff)")
    }

    private fun pushTuner(t: TunerState) {
        js("PT.onTuner(${JSONObject().put("on", t.on).put("note", t.note).put("cents", t.cents).put("hz", t.hz).put("source", t.source)})")
    }

    private fun pushLibrary() = js("PT.onLibrary(${JSONArray(library.all().reversed().map { library.toJson(it) })})")

    companion object {
        private const val ACTION_PERMISSION = "com.aravind.hendrifs.USB_PERMISSION"
        private val RECONNECT = Any()

        private fun Intent.usbDevice(): UsbDevice? =
            if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            else @Suppress("DEPRECATION") getParcelableExtra(UsbManager.EXTRA_DEVICE)

        private fun Intent.streamUri(): Uri? =
            if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else @Suppress("DEPRECATION") getParcelableExtra(Intent.EXTRA_STREAM)
    }
}
