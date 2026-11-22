package com.virtualmixer.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiInputPort
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Base64
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.locks.LockSupport

class MainActivity : Activity() {

    private lateinit var web: WebView
    private var pendingPermission: PermissionRequest? = null
    private val main = Handler(Looper.getMainLooper())

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK

        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        web = WebView(this)
        web.setBackgroundColor(Color.rgb(10, 11, 13))
        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = false
            setSupportZoom(false)
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                loader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                request.url.host != "appassets.androidplatform.net"
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                main.post { handlePermission(request) }
            }
        }
        web.addJavascriptInterface(Bridge(), "AndroidMixer")
        Midi.listener = { msg ->
            main.post { web.evaluateJavascript("window.__nativeMidi&&window.__nativeMidi('$msg')", null) }
        }
        Midi.changed = {
            main.post { web.evaluateJavascript("window.__midiChanged&&window.__midiChanged()", null) }
        }
        setContentView(web)
        hideBars()
        web.loadUrl("https://appassets.androidplatform.net/assets/index.html")
    }

    private fun hideBars() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideBars()
    }

    private fun handlePermission(request: PermissionRequest) {
        val wantsAudio = request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
        if (!wantsAudio) { request.deny(); return }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
        } else {
            pendingPermission?.deny()
            pendingPermission = request
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val req = pendingPermission ?: return
        pendingPermission = null
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            req.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
        } else {
            req.deny()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        moveTaskToBack(true)
    }

    override fun onDestroy() {
        Midi.close()
        Midi.listener = null
        Midi.changed = null
        web.destroy()
        super.onDestroy()
    }

    /** Functions the mixer page calls as window.AndroidMixer.* */
    inner class Bridge {
        @JavascriptInterface fun midiOpen(): Boolean = Midi.open(this@MainActivity)
        @JavascriptInterface fun midiDevices(): String = Midi.devicesJson()
        @JavascriptInterface fun midiSend(csv: String) = Midi.sendCsv(csv)
        @JavascriptInterface fun clockEnable(on: Boolean) = Midi.clockEnable(on)
        @JavascriptInterface fun setBpm(bpm: Double) { if (bpm in 20.0..400.0) Midi.bpm = bpm }
        @JavascriptInterface fun clockStart() = Midi.start()
        @JavascriptInterface fun clockStop() = Midi.stop()

        @JavascriptInterface
        fun saveFile(base64: String, name: String): String = try {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            val v = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "audio/webm")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/VirtualMixer")
            }
            val uri: Uri = contentResolver.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), v)
                ?: throw IllegalStateException("insert")
            contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: throw IllegalStateException("stream")
            "Сохранено: Музыка/VirtualMixer/$name"
        } catch (t: Throwable) {
            "Не удалось сохранить: " + (t.message ?: t.javaClass.simpleName)
        }
    }
}

/** Android MIDI: clock out (tight native timing) to every MIDI input, and messages in from every MIDI output. */
object Midi {
    @Volatile var bpm = 120.0
    @Volatile private var clockOn = false
    @Volatile private var running = false
    var listener: ((String) -> Unit)? = null
    var changed: (() -> Unit)? = null

    private val inPorts = CopyOnWriteArrayList<MidiInputPort>()
    private val outPorts = CopyOnWriteArrayList<MidiOutputPort>()
    private val devices = CopyOnWriteArrayList<MidiDevice>()
    private val outNames = CopyOnWriteArrayList<String>()
    private val inNames = CopyOnWriteArrayList<String>()
    private val opened = HashSet<Int>()
    private var mm: MidiManager? = null
    private var thread: Thread? = null
    private val main = Handler(Looper.getMainLooper())

    private val callback = object : MidiManager.DeviceCallback() {
        override fun onDeviceAdded(device: MidiDeviceInfo) { openDevice(device) }
        override fun onDeviceRemoved(device: MidiDeviceInfo) {
            opened.remove(device.id)
            val n = nameOf(device)
            outNames.remove(n); inNames.remove(n)
            changed?.invoke()
        }
    }

    private fun nameOf(info: MidiDeviceInfo): String =
        info.properties.getString(MidiDeviceInfo.PROPERTY_NAME) ?: "MIDI ${info.id}"

    @Suppress("DEPRECATION")
    fun open(ctx: Activity): Boolean {
        if (mm != null) return true
        if (!ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_MIDI)) return false
        val m = ctx.getSystemService(MidiManager::class.java) ?: return false
        mm = m
        main.post {
            m.registerDeviceCallback(callback, main)
            for (info in m.devices) openDevice(info)
        }
        return true
    }

    private fun openDevice(info: MidiDeviceInfo) {
        val m = mm ?: return
        if (!opened.add(info.id)) return
        m.openDevice(info, { dev ->
            if (dev == null) { opened.remove(info.id); return@openDevice }
            devices.add(dev)
            val name = nameOf(info)
            if (info.inputPortCount > 0) {
                dev.openInputPort(0)?.let { inPorts.add(it); outNames.add(name) }
            }
            if (info.outputPortCount > 0) {
                dev.openOutputPort(0)?.let { port ->
                    port.connect(Parser())
                    outPorts.add(port); inNames.add(name)
                }
            }
            changed?.invoke()
        }, main)
    }

    fun devicesJson(): String = JSONObject()
        .put("outs", JSONArray(outNames.toList()))
        .put("ins", JSONArray(inNames.toList()))
        .toString()

    private fun send(b: ByteArray) {
        for (p in inPorts) {
            try { p.send(b, 0, b.size) } catch (e: Exception) { inPorts.remove(p) }
        }
    }

    fun sendCsv(csv: String) {
        val b = csv.split(',').mapNotNull { it.trim().toIntOrNull()?.toByte() }.toByteArray()
        if (b.isNotEmpty()) send(b)
    }

    fun clockEnable(on: Boolean) {
        if (on == clockOn) return
        clockOn = on
        if (on) {
            thread = Thread({
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
                val tick = byteArrayOf(0xF8.toByte())
                var next = System.nanoTime()
                while (clockOn) {
                    next += (60_000_000_000.0 / (bpm * 24.0)).toLong()
                    val now = System.nanoTime()
                    if (next < now - 50_000_000L) next = now
                    val wait = next - System.nanoTime()
                    if (wait > 2_000_000L) LockSupport.parkNanos(wait - 1_000_000L)
                    while (System.nanoTime() < next) {
                        // spin for the last millisecond
                    }
                    send(tick)
                }
            }, "midi-clock").also { it.start() }
        } else {
            if (running) stop()
        }
    }

    fun start() {
        running = true
        send(byteArrayOf(0xF2.toByte(), 0, 0))
        send(byteArrayOf(0xFA.toByte()))
    }

    fun stop() {
        running = false
        send(byteArrayOf(0xFC.toByte()))
    }

    fun close() {
        clockOn = false
        try { mm?.unregisterDeviceCallback(callback) } catch (_: Throwable) { }
        for (p in inPorts) try { p.close() } catch (_: Throwable) { }
        for (p in outPorts) try { p.close() } catch (_: Throwable) { }
        for (d in devices) try { d.close() } catch (_: Throwable) { }
        inPorts.clear(); outPorts.clear(); devices.clear(); opened.clear(); outNames.clear(); inNames.clear()
        mm = null
    }

    /** Splits the incoming byte stream into complete MIDI messages and hands them to the page. */
    private class Parser : MidiReceiver() {
        private var status = 0
        private val buf = IntArray(3)
        private var have = 0
        private var need = 0

        override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) {
            for (k in offset until offset + count) {
                val b = msg[k].toInt() and 0xFF
                if (b >= 0xF8) { emit(intArrayOf(b)); continue }
                if (b >= 0x80) {
                    status = b; have = 0
                    need = when {
                        b < 0xC0 || b in 0xE0..0xEF -> 2
                        b < 0xE0 -> 1
                        b == 0xF2 -> 2
                        b == 0xF1 || b == 0xF3 -> 1
                        else -> 0
                    }
                    if (need == 0) { if (b != 0xF0 && b != 0xF7) emit(intArrayOf(b)); status = 0 }
                    continue
                }
                if (status == 0) continue
                buf[have++] = b
                if (have >= need) {
                    emit(if (need == 1) intArrayOf(status, buf[0]) else intArrayOf(status, buf[0], buf[1]))
                    have = 0
                    if (status >= 0xF0) status = 0
                }
            }
        }

        private fun emit(m: IntArray) { listener?.invoke(m.joinToString(",")) }
    }
}
