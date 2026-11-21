package com.virtualmixer.app

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioPlaybackConfiguration
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.lang.reflect.Method
import kotlin.math.abs

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 28) {
            try { HiddenApiBypass.addHiddenApiExemptions("L") } catch (_: Throwable) { }
        }
        Mixer.load(this)
    }
}

/** One fader of the mixer, bound to one app (by package / uid). */
class Channel(val index: Int) {
    @Volatile var pkg: String? = null
    @Volatile var uid: Int = -1
    @Volatile var label: String = ""
    @Volatile var vol: Float = 0.8f
    @Volatile var mute: Boolean = false
    @Volatile var solo: Boolean = false

    /** Volume multiplier sent to the app's players (0..1). */
    fun gain(anySolo: Boolean): Float =
        if (mute || (anySolo && !solo)) 0f else vol * vol
}

object Mixer {
    val channels: List<Channel> = List(10) { Channel(it) }
    @Volatile var bpm: Double = 120.0

    private fun prefs(c: Context) = c.getSharedPreferences("mixer", Context.MODE_PRIVATE)

    fun load(c: Context) {
        val p = prefs(c)
        for (ch in channels) {
            ch.pkg = p.getString("p${ch.index}", null)
            ch.vol = p.getFloat("v${ch.index}", 0.8f)
            ch.mute = p.getBoolean("m${ch.index}", false)
            ch.solo = p.getBoolean("s${ch.index}", false)
            resolve(c, ch)
        }
        bpm = p.getFloat("bpm", 120f).toDouble()
    }

    fun save(c: Context) {
        val e = prefs(c).edit()
        for (ch in channels) {
            e.putString("p${ch.index}", ch.pkg)
            e.putFloat("v${ch.index}", ch.vol)
            e.putBoolean("m${ch.index}", ch.mute)
            e.putBoolean("s${ch.index}", ch.solo)
        }
        e.putFloat("bpm", bpm.toFloat())
        e.apply()
    }

    fun firstRun(c: Context): Boolean {
        val p = prefs(c)
        if (p.getBoolean("seen", false)) return false
        p.edit().putBoolean("seen", true).apply()
        return true
    }

    fun resolve(c: Context, ch: Channel) {
        val pkg = ch.pkg
        if (pkg == null) { ch.uid = -1; ch.label = ""; return }
        try {
            val ai = c.packageManager.getApplicationInfo(pkg, 0)
            ch.uid = ai.uid
            ch.label = c.packageManager.getApplicationLabel(ai).toString()
        } catch (e: Exception) {
            ch.pkg = null; ch.uid = -1; ch.label = ""
        }
    }

    fun anySolo(): Boolean = channels.any { it.solo && it.uid >= 0 }
    fun channelFor(uid: Int): Channel? = channels.firstOrNull { it.uid == uid }
}

/**
 * Talks to the system AudioService through Shizuku (shell privileges).
 * Lists every active audio player on the phone and sets a per-player
 * volume multiplier (the same hidden API MIUI uses for per-app volume).
 */
object Engine {
    @Volatile var ready = false
        private set
    @Volatile var status = "Нет подключения"
        private set
    @Volatile var activeUids: Set<Int> = emptySet()
        private set

    private var service: Any? = null
    private var mGet: Method? = null
    private val applied = HashMap<Int, Float>()
    private var lastFull = 0L

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

    private const val STATE_STARTED = 2

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
            val m = svc.javaClass.getMethod("getActivePlaybackConfigurations")
            m.invoke(svc)
            service = svc; mGet = m
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

    fun disconnect() { ready = false; service = null; activeUids = emptySet() }

    private fun configs(): List<AudioPlaybackConfiguration>? {
        val s = service ?: return null
        val m = mGet ?: return null
        return try {
            (m.invoke(s) as? List<*>)?.filterIsInstance<AudioPlaybackConfiguration>()
        } catch (t: Throwable) {
            disconnect(); status = "Связь с Shizuku потеряна"; null
        }
    }

    /** Called ~4 times a second by MixerService. */
    @Synchronized
    fun tick(myUid: Int) {
        if (!ready && !connect()) { activeUids = emptySet(); return }
        val list = configs() ?: return
        val now = SystemClock.uptimeMillis()
        val full = now - lastFull > 2000
        if (full) lastFull = now
        val anySolo = Mixer.anySolo()
        val act = HashSet<Int>()
        val seen = HashSet<Int>()
        for (c in list) {
            try {
                val uid = mUid.invoke(c) as Int
                val piid = mPiid.invoke(c) as Int
                val st = mState.invoke(c) as Int
                if (st == STATE_STARTED && uid != myUid) act.add(uid)
                seen.add(piid)
                val ch = Mixer.channelFor(uid)
                val last = applied[piid]
                if (ch == null && last == null) continue
                val target = ch?.gain(anySolo) ?: 1f
                if (last == null || abs(last - target) > 0.0005f || full) {
                    val proxy = mProxy.invoke(c) ?: continue
                    mSetVol.invoke(proxy, target)
                    if (ch == null) applied.remove(piid) else applied[piid] = target
                }
            } catch (_: Throwable) { }
        }
        applied.keys.retainAll(seen)
        activeUids = act
    }

    /** Give every app its normal volume back. */
    @Synchronized
    fun resetAll() {
        val list = configs() ?: return
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
        val pi = PendingIntent.getActivity(
            c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(c, CH)
            .setSmallIcon(R.drawable.ic_note)
            .setContentTitle("Virtual Mixer")
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }
}
