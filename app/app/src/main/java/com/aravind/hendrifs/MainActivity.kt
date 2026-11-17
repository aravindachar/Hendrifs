package com.aravind.hendrifs

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The UI is the Hendrifs design page (assets/www/index.html) in a full-screen WebView.
 * It talks to this activity through `HendrifsNative.send(json)`; the activity pushes amp state
 * back with `PT.onState(...)`. All USB work happens in [AmpController].
 */
class MainActivity : ComponentActivity() {
    private val amp = AmpController()
    private lateinit var usb: UsbManager
    private lateinit var web: WebView
    private lateinit var library: LibraryStore
    private var pageReady = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = intent.usbDevice() ?: return
            when (intent.action) {
                ACTION_PERMISSION ->
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) open(device)
                    else amp.say("USB permission was not given. Tap the status light to try again.")
                UsbManager.ACTION_USB_DEVICE_DETACHED ->
                    if (device.vendorId == IdCoreProtocol.VENDOR_ID && !amp.state.value.demo) { amp.disconnect(); amp.say("Amp unplugged") }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usb = getSystemService(USB_SERVICE) as UsbManager
        library = LibraryStore(filesDir)
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
                override fun onPageFinished(view: WebView, url: String) { pageReady = true; pushState(amp.state.value); pushLibrary() }
            }
            loadUrl("file:///android_asset/www/index.html")
        }
        setContentView(web)

        ContextCompat.registerReceiver(this, receiver,
            IntentFilter().apply { addAction(ACTION_PERMISSION); addAction(UsbManager.ACTION_USB_DEVICE_DETACHED) },
            ContextCompat.RECEIVER_NOT_EXPORTED)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Push UI state whenever something other than the raw log changes.
                amp.state.map { it.copy(log = emptyList()) }.distinctUntilChanged().collect { pushState(it) }
            }
        }

        // Launched by plugging the amp in: Android has already granted permission.
        intent.usbDevice()?.let(::open) ?: findAndConnect(quiet = true)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.usbDevice()?.let(::open)
    }

    override fun onDestroy() {
        unregisterReceiver(receiver)
        amp.disconnect()
        web.destroy()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- USB

    private fun findAndConnect(quiet: Boolean = false): Boolean {
        val device = usb.deviceList.values.firstOrNull { it.vendorId == IdCoreProtocol.VENDOR_ID }
        if (device == null) {
            if (!quiet) amp.say("No Blackstar amp found. Check the USB-C OTG cable and that the amp is switched on.")
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
        runCatching { UsbHidTransport(usb, device) }
            .onSuccess { amp.connect(it, demo = false); amp.say("Connected to ${it.description.substringBefore(" ·")}") }
            .onFailure { amp.disconnect(); amp.say("Could not open the amp: ${it.message}") }
    }

    // ---------------------------------------------------------------- JS bridge

    private inner class Bridge {
        @JavascriptInterface
        fun send(json: String) {
            val cmd = runCatching { JSONObject(json) }.getOrNull() ?: return
            runOnUiThread { runCatching { handle(cmd) }.onFailure { amp.say("Error: ${it.message}") } }
        }
    }

    private fun handle(c: JSONObject) {
        when (c.getString("a")) {
            "connect" -> findAndConnect()
            "demo" -> { amp.connect(FakeAmpTransport(), demo = true); amp.say("Demo amp: nothing is sent over USB") }
            "disconnect" -> amp.disconnect()
            "refresh" -> amp.refresh()
            "set" -> Control.byName(c.getString("c"))?.let { amp.set(it, c.getInt("v")) }
            "global" -> amp.setGlobal(c.getInt("id"), c.getInt("v"))
            "stomp" -> amp.stomp(c.getInt("fx"))
            "patch" -> amp.selectPatch(c.getInt("slot"))
            "store" -> if (c.optBoolean("unlocked")) amp.storeCurrent(c.getInt("slot"), c.getString("name"))
                       else amp.say("Memory is write-protected. Flip the switch on the rear panel first.")
            "raw" -> runCatching { IdCoreProtocol.parseHex(c.getString("hex")) }.onSuccess(amp::send).onFailure { amp.say("Bad hex: ${it.message}") }
            "log" -> js("PT.onLog(${JSONObject.quote(amp.logAsText().takeLast(60_000))})")
            "clearLog" -> amp.clearLog()
            "shareLog" -> share("Hendrifs USB log", amp.logAsText())
            "dismiss" -> amp.say(null)
            "haptic" -> web.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
            "libSave" -> { library.savePatch(c.getString("name"), c.optString("genre", "Other"), amp.state.value.values); pushLibrary(); amp.say("Saved to the library") }
            "libLoad" -> library.get(c.getLong("id"))?.let { e -> if (e.kind == "patch") { amp.apply(e.values); amp.say("Loaded ${e.name}") } }
            "libDelete" -> { library.delete(c.getLong("id")); pushLibrary() }
            "libShare" -> library.get(c.getLong("id"))?.let { share("Hendrifs: ${it.name}", library.toJson(it).toString(2)) }
            "backup" -> backup()
            "restore" -> if (c.optBoolean("unlocked")) restore(c.getLong("id"))
                         else amp.say("Memory is write-protected. Flip the switch on the rear panel first.")
        }
    }

    private fun backup() {
        val s = amp.state.value
        val slots = (1..IdCoreProtocol.SLOTS).mapNotNull { i -> s.presets[i]?.let { (s.names[i] ?: "PATCH $i") to it } }
        if (slots.size < IdCoreProtocol.SLOTS) { amp.say("Still reading patches (${slots.size}/6). Tap Refresh, then try again."); amp.refresh(); return }
        library.saveBackup("Backup " + SimpleDateFormat("d MMM HH:mm", Locale.US).format(Date()), slots)
        pushLibrary(); amp.say("All 6 patches backed up on this tablet")
    }

    private fun restore(id: Long) {
        val e = library.get(id) ?: return
        if (e.kind != "backup") { amp.say("Only backups can be restored"); return }
        e.backup.forEachIndexed { i, (name, raw) -> amp.restore(i + 1, name, raw) }
        amp.say("Restoring 6 patches…")
    }

    private fun share(subject: String, text: String) {
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"; putExtra(Intent.EXTRA_SUBJECT, subject); putExtra(Intent.EXTRA_TEXT, text)
        }, subject))
    }

    // ---------------------------------------------------------------- native → page

    private fun js(code: String) { if (pageReady) web.evaluateJavascript(code, null) }

    private fun pushState(s: AmpState) {
        val o = JSONObject().apply {
            put("connected", s.connected); put("demo", s.demo); put("link", s.link)
            put("model", s.link.substringBefore(" ·"))
            put("slot", s.slot ?: 0); put("manual", s.manual)
            put("names", JSONArray((1..IdCoreProtocol.SLOTS).map { s.names[it] ?: "" }))
            put("presetsRead", s.presets.size)
            put("values", JSONObject().apply { s.values.forEach { (c, v) -> put(c.name, v) } })
            put("globals", JSONObject().apply { s.globals.forEach { (k, v) -> put(k.toString(), v) } })
            put("tuner", JSONObject().put("on", s.tunerOn).put("note", s.tunerNote).put("cents", s.tunerCents))
            put("message", s.message ?: JSONObject.NULL)
        }
        js("PT.onState($o)")
    }

    private fun pushLibrary() = js("PT.onLibrary(${JSONArray(library.all().reversed().map { library.toJson(it) })})")

    companion object {
        private const val ACTION_PERMISSION = "com.aravind.hendrifs.USB_PERMISSION"

        private fun Intent.usbDevice(): UsbDevice? =
            if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            else @Suppress("DEPRECATION") getParcelableExtra(UsbManager.EXTRA_DEVICE)
    }
}
