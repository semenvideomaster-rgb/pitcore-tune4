package com.virtualmixer.app

import android.annotation.SuppressLint
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioPlaybackConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import org.json.JSONObject
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 28) {
            try { HiddenApiBypass.addHiddenApiExemptions("L") } catch (_: Throwable) { }
        }
    }
}

/**
 * Per-app audio through Shizuku (shell rights, no root):
 * finds every app that plays sound or records from the mic,
 * and sets each app's volume multiplier (hidden PlayerProxy.setVolume).
 */
object AppEngine {
    @Volatile var ready = false
        private set
    @Volatile var status = "Нет подключения"
        private set
    @Volatile var playingUids: Set<Int> = emptySet()
        private set
    @Volatile var recordingUids: Set<Int> = emptySet()
        private set

    /** uid -> gain 0..1 for apps placed on a mixer channel */
    val gains = ConcurrentHashMap<Int, Float>()
    /** uid -> package, for apps on channels */
    val pkgOf = ConcurrentHashMap<Int, String>()

    private var service: Any? = null
    private var mPlay: Method? = null
    private var mRec: Method? = null
    private val applied = HashMap<Int, Float>()
    private var lastFull = 0L
    private var lastRec = 0L

    private val cfgCls = AudioPlaybackConfiguration::class.java
    private val mUid: Method by lazy { cfgCls.getDeclaredMethod("getClientUid").apply { isAccessible = true } }
    private val mPiid: Method by lazy { cfgCls.getDeclaredMethod("getPlayerInterfaceId").apply { isAccessible = true } }
    private val mState: Method by lazy { cfgCls.getDeclaredMethod("getPlayerState").apply { isAccessible = true } }
    private val mProxy: Method by lazy { cfgCls.getDeclaredMethod("getPlayerProxy").apply { isAccessible = true } }
    private val mSetVol: Method by lazy {
        Class.forName("android.media.PlayerProxy")
            .getDeclaredMethod("setVolume", Float::class.javaPrimitiveType)
            .apply { isAccessible = true }
    }
    private val mRecUid: Method? by lazy {
        try {
            Class.forName("android.media.AudioRecordingConfiguration")
                .getDeclaredMethod("getClientUid").apply { isAccessible = true }
        } catch (_: Throwable) { null }
    }

    /** 0 = Shizuku not running, 1 = needs permission, 2 = ok */
    fun shizukuState(): Int = try {
        when {
            !Shizuku.pingBinder() -> 0
            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> 1
            else -> 2
        }
    } catch (t: Throwable) { 0 }

    @Synchronized
    fun connect(): Boolean {
        if (ready) return true
        if (shizukuState() != 2) { status = "Shizuku не подключён"; return false }
        return try {
            val binder = ShizukuBinderWrapper(SystemServiceHelper.getSystemService(Context.AUDIO_SERVICE))
            val stub = Class.forName("android.media.IAudioService\$Stub")
            val svc = stub.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
                ?: throw IllegalStateException("AudioService недоступен")
            val mp = svc.javaClass.getMethod("getActivePlaybackConfigurations")
            mp.invoke(svc)
            mRec = try { svc.javaClass.getMethod("getActiveRecordingConfigurations") } catch (_: Throwable) { null }
            service = svc; mPlay = mp
            applied.clear()
            ready = true
            status = "Подключено"
            true
        } catch (t: Throwable) {
            val c = t.cause ?: t
            status = "Ошибка: " + (c.message ?: c.javaClass.simpleName)
            ready = false; service = null
            false
        }
    }

    fun disconnect() { ready = false; service = null; playingUids = emptySet(); recordingUids = emptySet() }

    private fun call(m: Method?): List<*>? {
        val s = service ?: return null
        if (m == null) return null
        return try { m.invoke(s) as? List<*> } catch (t: Throwable) {
            disconnect(); status = "Связь с Shizuku потеряна"; null
        }
    }

    @Synchronized
    fun tick(myUid: Int) {
        if (!ready && !connect()) return
        val list = call(mPlay)?.filterIsInstance<AudioPlaybackConfiguration>() ?: return
        val now = SystemClock.uptimeMillis()
        val full = now - lastFull > 2000
        if (full) lastFull = now
        val act = HashSet<Int>()
        val seen = HashSet<Int>()
        for (c in list) {
            try {
                val uid = mUid.invoke(c) as Int
                val piid = mPiid.invoke(c) as Int
                val st = mState.invoke(c) as Int
                if (st == 2 && uid != myUid && uid >= 10000) act.add(uid)
                seen.add(piid)
                val g = gains[uid]
                val last = applied[piid]
                if (g == null && last == null) continue
                val target = g ?: 1f
                if (last == null || abs(last - target) > 0.0005f || full) {
                    val proxy = mProxy.invoke(c) ?: continue
                    mSetVol.invoke(proxy, target)
                    if (g == null) applied.remove(piid) else applied[piid] = target
                }
            } catch (_: Throwable) { }
        }
        applied.keys.retainAll(seen)
        playingUids = act

        if (now - lastRec > 700) {
            lastRec = now
            val recs = HashSet<Int>()
            val m = mRecUid
            if (m != null) {
                call(mRec)?.forEach { rc ->
                    try {
                        val uid = m.invoke(rc) as Int
                        if (uid != myUid && uid >= 10000) recs.add(uid)
                    } catch (_: Throwable) { }
                }
            }
            recordingUids = recs
        }
    }

    @Synchronized
    fun resetAll() {
        val list = call(mPlay)?.filterIsInstance<AudioPlaybackConfiguration>() ?: return
        for (c in list) {
            try {
                val piid = mPiid.invoke(c) as Int
                if (!applied.containsKey(piid)) continue
                val proxy = mProxy.invoke(c) ?: continue
                mSetVol.invoke(proxy, 1f)
            } catch (_: Throwable) { }
        }
        applied.clear()
    }
}

object Notif {
    private const val CH = "mixer"
    fun ensure(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CH) == null) {
            nm.createNotificationChannel(NotificationChannel(CH, "Virtual Mixer", NotificationManager.IMPORTANCE_LOW))
        }
    }
    fun build(c: Context, text: String): Notification {
        val pi = PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(c, CH)
            .setSmallIcon(R.drawable.ic_note)
            .setContentTitle("Virtual Mixer")
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }
}

/** Keeps app volumes applied while you are inside other apps. */
class MixerService : Service() {
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private val loop = object : Runnable {
        override fun run() {
            AppEngine.tick(applicationInfo.uid)
            handler?.postDelayed(this, 200)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Notif.ensure(this)
        val n = Notif.build(this, "Микшер работает: громкость приложений под контролем")
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(1, n)
        val t = HandlerThread("mixer-engine"); t.start(); thread = t
        handler = Handler(t.looper).also { it.post(loop) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        handler?.removeCallbacksAndMessages(null); handler = null
        try { AppEngine.resetAll() } catch (_: Throwable) { }
        thread?.quitSafely()
        super.onDestroy()
    }
}

/** Per-app level meters: captures each channel app's sound (Android playback capture) only to measure it. */
class MeterService : Service() {
    companion object {
        @Volatile var running = false
            private set
        @Volatile var sink: ((String) -> Unit)? = null
    }

    private var projection: MediaProjection? = null
    @Volatile private var run = false
    private var worker: Thread? = null
    private val main = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    @Suppress("DEPRECATION")
    private fun dataOf(i: Intent?): Intent? =
        if (Build.VERSION.SDK_INT >= 33) i?.getParcelableExtra("data", Intent::class.java) else i?.getParcelableExtra("data")

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Notif.ensure(this)
        startForeground(2, Notif.build(this, "Индикаторы уровня приложений"), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        if (run) return START_NOT_STICKY
        val data = dataOf(intent)
        val code = intent?.getIntExtra("code", 0) ?: 0
        if (data == null) { stopSelf(); return START_NOT_STICKY }
        try {
            val mpm = getSystemService(MediaProjectionManager::class.java)
            val p = mpm.getMediaProjection(code, data) ?: throw IllegalStateException("projection")
            projection = p
            p.registerCallback(object : MediaProjection.Callback() { override fun onStop() { stopSelf() } }, main)
            run = true; running = true
            worker = Thread({ loop(p) }, "app-meters").also { it.start() }
        } catch (t: Throwable) { stopSelf() }
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun makeRecord(p: MediaProjection, uid: Int): AudioRecord? = try {
        val cfg = AudioPlaybackCaptureConfiguration.Builder(p).addMatchingUid(uid).build()
        val fmt = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(24000).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build()
        val r = AudioRecord.Builder().setAudioFormat(fmt).setBufferSizeInBytes(24000)
            .setAudioPlaybackCaptureConfig(cfg).build()
        r.startRecording(); r
    } catch (_: Throwable) { null }

    private fun loop(p: MediaProjection) {
        val recs = HashMap<Int, AudioRecord?>()
        val peaks = HashMap<Int, Float>()
        val buf = ShortArray(2048)
        var lastSend = 0L
        while (run) {
            val want = AppEngine.gains.keys.toSet()
            for (u in recs.keys.toList()) if (u !in want) { try { recs[u]?.release() } catch (_: Throwable) { }; recs.remove(u); peaks.remove(u) }
            for (u in want) if (!recs.containsKey(u)) recs[u] = makeRecord(p, u)
            for ((u, r) in recs) {
                if (r == null) continue
                val n = try { r.read(buf, 0, buf.size, AudioRecord.READ_NON_BLOCKING) } catch (_: Throwable) { 0 }
                var pk = 0
                for (i in 0 until n) { val a = abs(buf[i].toInt()); if (a > pk) pk = a }
                if (n > 0) peaks[u] = maxOf(pk / 32768f, (peaks[u] ?: 0f) * 0.85f)
                else peaks[u] = (peaks[u] ?: 0f) * 0.9f
            }
            val now = SystemClock.uptimeMillis()
            if (now - lastSend > 60) {
                lastSend = now
                val o = JSONObject()
                for ((u, v) in peaks) { val pkg = AppEngine.pkgOf[u] ?: continue; o.put(pkg, (v * 1000).toInt() / 1000.0) }
                sink?.invoke(o.toString())
            }
            try { Thread.sleep(15) } catch (_: InterruptedException) { break }
        }
        for (r in recs.values) try { r?.release() } catch (_: Throwable) { }
    }

    override fun onDestroy() {
        run = false
        try { worker?.join(1000) } catch (_: Throwable) { }
        try { projection?.stop() } catch (_: Throwable) { }
        running = false
        super.onDestroy()
    }
}
