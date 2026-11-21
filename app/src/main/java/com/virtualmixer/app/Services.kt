package com.virtualmixer.app

import android.annotation.SuppressLint
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiInputPort
import android.media.midi.MidiManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.widget.Toast
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.locks.LockSupport

/** Keeps the per-app volumes applied while you are inside other apps. */
class MixerService : Service() {
    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    private val loop = object : Runnable {
        override fun run() {
            Engine.tick(applicationInfo.uid)
            handler?.postDelayed(this, 250)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Notif.ensure(this)
        val n = Notif.build(this, "Микшер работает: громкость приложений под контролем")
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
        val t = HandlerThread("mixer-engine")
        t.start()
        thread = t
        handler = Handler(t.looper).also { it.post(loop) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        handler?.removeCallbacksAndMessages(null)
        handler = null
        try { Engine.resetAll() } catch (_: Throwable) { }
        thread?.quitSafely()
        super.onDestroy()
    }
}

/** Records the sound of all apps (Android playback capture) to a WAV file in Music/VirtualMixer. */
class RecordService : Service() {
    companion object {
        @Volatile var recording = false
            private set
        @Volatile var startedAt = 0L
            private set
        private const val RATE = 48000
    }

    private var projection: MediaProjection? = null
    private var rec: AudioRecord? = null
    private var worker: Thread? = null
    @Volatile private var run = false
    private val main = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    @Suppress("DEPRECATION")
    private fun dataOf(i: Intent?): Intent? =
        if (Build.VERSION.SDK_INT >= 33) i?.getParcelableExtra("data", Intent::class.java)
        else i?.getParcelableExtra("data")

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Notif.ensure(this)
        startForeground(2, Notif.build(this, "● Идёт запись микса"), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        if (run) return START_NOT_STICKY
        val code = intent?.getIntExtra("code", 0) ?: 0
        val data = dataOf(intent)
        if (data == null) { stopSelf(); return START_NOT_STICKY }
        try {
            val mpm = getSystemService(MediaProjectionManager::class.java)
            val p = mpm.getMediaProjection(code, data) ?: throw IllegalStateException("нет разрешения")
            projection = p
            p.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stopSelf() }
            }, main)
            val cfg = AudioPlaybackCaptureConfiguration.Builder(p)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val fmt = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()
            val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            val r = AudioRecord.Builder()
                .setAudioFormat(fmt)
                .setBufferSizeInBytes(maxOf(minBuf, 8192) * 4)
                .setAudioPlaybackCaptureConfig(cfg)
                .build()
            rec = r
            r.startRecording()
            run = true
            recording = true
            startedAt = SystemClock.elapsedRealtime()
            worker = Thread({ writeLoop(r) }, "recorder").also { it.start() }
        } catch (t: Throwable) {
            say("Не удалось начать запись: " + (t.message ?: t.javaClass.simpleName))
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun writeLoop(r: AudioRecord) {
        val tmp = File(cacheDir, "rec_tmp.wav")
        var total = 0L
        try {
            BufferedOutputStream(FileOutputStream(tmp)).use { out ->
                out.write(ByteArray(44))
                val buf = ByteArray(16384)
                while (run) {
                    val n = r.read(buf, 0, buf.size)
                    if (n > 0) { out.write(buf, 0, n); total += n }
                }
            }
            writeHeader(tmp, total)
            val name = publish(tmp)
            say("Запись сохранена: Музыка/VirtualMixer/$name")
        } catch (t: Throwable) {
            say("Ошибка записи: " + (t.message ?: t.javaClass.simpleName))
        }
    }

    private fun writeHeader(f: File, dataLen: Long) {
        val ch = 2; val bits = 16
        val b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()); b.putInt((36 + dataLen).toInt()); b.put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()); b.putInt(16); b.putShort(1); b.putShort(ch.toShort())
        b.putInt(RATE); b.putInt(RATE * ch * bits / 8); b.putShort((ch * bits / 8).toShort()); b.putShort(bits.toShort())
        b.put("data".toByteArray()); b.putInt(dataLen.toInt())
        RandomAccessFile(f, "rw").use { it.seek(0); it.write(b.array()) }
    }

    private fun publish(tmp: File): String {
        val name = "VirtualMixer_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".wav"
        val v = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "audio/wav")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/VirtualMixer")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), v)
            ?: throw IOException("не удалось создать файл")
        val os = contentResolver.openOutputStream(uri) ?: throw IOException("не удалось открыть файл")
        os.use { o -> FileInputStream(tmp).use { it.copyTo(o) } }
        v.clear()
        v.put(MediaStore.MediaColumns.IS_PENDING, 0)
        contentResolver.update(uri, v, null, null)
        tmp.delete()
        return name
    }

    private fun say(msg: String) {
        main.post { Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show() }
    }

    override fun onDestroy() {
        run = false
        try { rec?.stop() } catch (_: Throwable) { }
        try { worker?.join(5000) } catch (_: Throwable) { }
        try { rec?.release() } catch (_: Throwable) { }
        try { projection?.stop() } catch (_: Throwable) { }
        rec = null; projection = null; worker = null
        recording = false
        super.onDestroy()
    }
}

/** Sends MIDI Clock (24 PPQN) + Start/Stop to every MIDI input on the phone: USB synths and synth apps. */
object MidiClock {
    @Volatile var bpm = 120.0
    @Volatile var enabled = false
        private set
    @Volatile var playing = false
        private set
    @Volatile var deviceNames: List<String> = emptyList()
        private set

    private val ports = CopyOnWriteArrayList<MidiInputPort>()
    private val devices = CopyOnWriteArrayList<MidiDevice>()
    private val opened = HashSet<Int>()
    private var mm: MidiManager? = null
    private val main = Handler(Looper.getMainLooper())

    private val callback = object : MidiManager.DeviceCallback() {
        override fun onDeviceAdded(device: MidiDeviceInfo) { open(device) }
        override fun onDeviceRemoved(device: MidiDeviceInfo) { opened.remove(device.id) }
    }

    @Suppress("DEPRECATION")
    fun enable(ctx: Context) {
        if (enabled) return
        val m = ctx.getSystemService(Context.MIDI_SERVICE) as? MidiManager ?: return
        mm = m
        enabled = true
        m.registerDeviceCallback(callback, main)
        for (info in m.devices) open(info)
        startThread()
    }

    private fun open(info: MidiDeviceInfo) {
        val m = mm ?: return
        if (!enabled || info.inputPortCount == 0 || !opened.add(info.id)) return
        m.openDevice(info, { dev ->
            if (dev == null) { opened.remove(info.id); return@openDevice }
            val port = dev.openInputPort(0)
            if (port == null) {
                try { dev.close() } catch (_: Throwable) { }
                opened.remove(info.id)
                return@openDevice
            }
            devices.add(dev); ports.add(port)
            val name = info.properties.getString(MidiDeviceInfo.PROPERTY_NAME) ?: "MIDI"
            deviceNames = deviceNames + name
            if (playing) send(byteArrayOf(0xFA.toByte()))
        }, main)
    }

    private fun send(b: ByteArray) {
        for (p in ports) {
            try { p.send(b, 0, b.size) } catch (e: Exception) { ports.remove(p) }
        }
    }

    private fun startThread() {
        Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            val tick = byteArrayOf(0xF8.toByte())
            var next = System.nanoTime()
            while (enabled) {
                next += (60_000_000_000.0 / (bpm * 24.0)).toLong()
                val now = System.nanoTime()
                if (next < now - 50_000_000L) next = now
                val wait = next - System.nanoTime()
                if (wait > 2_000_000L) LockSupport.parkNanos(wait - 1_000_000L)
                while (System.nanoTime() < next) {
                    // short spin for a tight clock
                }
                send(tick)
            }
        }, "midi-clock").start()
    }

    fun start() {
        playing = true
        send(byteArrayOf(0xF2.toByte(), 0, 0))
        send(byteArrayOf(0xFA.toByte()))
    }

    fun stop() {
        playing = false
        send(byteArrayOf(0xFC.toByte()))
    }

    fun disable() {
        if (!enabled) return
        if (playing) stop()
        enabled = false
        try { mm?.unregisterDeviceCallback(callback) } catch (_: Throwable) { }
        for (p in ports) try { p.close() } catch (_: Throwable) { }
        for (d in devices) try { d.close() } catch (_: Throwable) { }
        ports.clear(); devices.clear(); opened.clear()
        deviceNames = emptyList()
    }
}
