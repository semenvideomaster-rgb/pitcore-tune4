package com.virtualmixer.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import rikka.shizuku.Shizuku
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : Activity() {

    companion object {
        const val BG = 0xFF0A0B0D.toInt()
        const val ACC = 0xFF22D3EE.toInt()
        const val GREEN = 0xFF4ADE80.toInt()
        const val RED = 0xFFFF3B5C.toInt()
        const val YEL = 0xFFFACC15.toInt()
        const val OFF = 0xFF262A31.toInt()
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        const val REQ_PROJ = 21
        const val REQ_MIC = 11
    }

    private class Strip(
        val ch: Channel, val icon: ImageView, val name: TextView, val led: View,
        val pct: TextView, val fader: FaderView, val mute: TextView, val solo: TextView
    )

    private data class AppItem(val pkg: String?, val label: String, val playing: Boolean)

    private val ui = Handler(Looper.getMainLooper())
    private val strips = ArrayList<Strip>()
    private val tapTimes = ArrayList<Long>()
    private lateinit var shizukuBtn: TextView
    private lateinit var bpmText: TextView
    private lateinit var playBtn: TextView
    private lateinit var midiBtn: TextView
    private lateinit var recBtn: TextView
    private lateinit var recTime: TextView
    private lateinit var masterFader: FaderView
    private lateinit var masterPct: TextView

    private val permListener = Shizuku.OnRequestPermissionResultListener { _, result ->
        ui.post {
            if (result == PackageManager.PERMISSION_GRANTED) {
                Engine.disconnect(); ensureService()
                toast("Shizuku разрешён, микшер подключается")
            }
            updateShizuku()
        }
    }
    private val binderListener = Shizuku.OnBinderReceivedListener { ui.post { updateShizuku(); ensureService() } }
    private val deadListener = Shizuku.OnBinderDeadListener { Engine.disconnect(); ui.post { updateShizuku() } }

    private val refresher = object : Runnable {
        override fun run() {
            refresh()
            ui.postDelayed(this, 250)
        }
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        setContentView(buildUi())
        Shizuku.addBinderReceivedListenerSticky(binderListener)
        Shizuku.addBinderDeadListener(deadListener)
        Shizuku.addRequestPermissionResultListener(permListener)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 10)
        }
        ensureService()
        ui.post(refresher)
        if (Mixer.firstRun(this)) ui.postDelayed({ showHelp() }, 400)
    }

    override fun onResume() {
        super.onResume()
        strips.forEach { bindStrip(it) }
        syncMaster()
    }

    override fun onPause() {
        super.onPause()
        Mixer.save(this)
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        Shizuku.removeBinderReceivedListener(binderListener)
        Shizuku.removeBinderDeadListener(deadListener)
        Shizuku.removeRequestPermissionResultListener(permListener)
        super.onDestroy()
    }

    private fun ensureService() {
        try { startForegroundService(Intent(this, MixerService::class.java)) } catch (_: Throwable) { }
    }

    // ---------------------------------------------------------------- helpers

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun bg(color: Int, radius: Int = 6): GradientDrawable = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat(); setStroke(dp(1), Color.BLACK)
    }

    private fun btn(label: String, onColor: Int, small: Boolean = false, click: (TextView) -> Unit): TextView =
        TextView(this).apply {
            text = label
            setTextColor(0xFFD7DBE3.toInt())
            textSize = if (small) 11f else 13f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.06f
            gravity = Gravity.CENTER
            setPadding(dp(11), dp(8), dp(11), dp(8))
            background = bg(OFF)
            tag = onColor
            setOnClickListener { click(this) }
        }

    private fun setOn(v: TextView, on: Boolean) {
        val c = v.tag as Int
        v.background = if (on) bg(c) else bg(OFF)
        v.setTextColor(if (on) 0xFF05080A.toInt() else 0xFFD7DBE3.toInt())
    }

    private fun gap(end: Int = 8) = LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = dp(end) }

    private fun led(v: View, color: Int) {
        v.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
    }

    private fun pctText(v: Float): String = if (v <= 0.001f) "OFF" else "${(v * 100).roundToInt()}%"

    private fun labelOf(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) { pkg }

    // ---------------------------------------------------------------- UI

    private fun buildUi(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(BG) }

        val barScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(0xFF15171B.toInt())
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(6), dp(10), dp(6))
        }
        barScroll.addView(bar)
        root.addView(barScroll, LinearLayout.LayoutParams(MATCH, WRAP))

        bar.addView(TextView(this).apply {
            text = "VIRTUAL MIXER"
            setTextColor(ACC); textSize = 16f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            letterSpacing = 0.12f
        }, gap(12))
        shizukuBtn = btn("SHIZUKU", GREEN) { onShizuku() }
        bar.addView(shizukuBtn, gap(14))
        bar.addView(btn("−", ACC) { setBpm(Math.ceil(Mixer.bpm) - 1) }, gap(4))
        bpmText = TextView(this).apply {
            setTextColor(ACC); textSize = 18f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = bg(0xFF04070A.toInt())
        }
        bar.addView(bpmText, gap(4))
        bar.addView(btn("+", ACC) { setBpm(Math.floor(Mixer.bpm) + 1) }, gap())
        bar.addView(btn("TAP", ACC) { tap() }, gap(14))
        playBtn = btn("▶ START", GREEN) { onPlay() }
        bar.addView(playBtn, gap())
        midiBtn = btn("MIDI SYNC", YEL) { onMidi() }
        bar.addView(midiBtn, gap(14))
        recBtn = btn("● REC", RED) { onRec() }
        bar.addView(recBtn, gap(4))
        recTime = TextView(this).apply {
            text = "00:00"; setTextColor(RED); textSize = 14f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = bg(0xFF04070A.toInt())
        }
        bar.addView(recTime, gap(14))
        bar.addView(btn("AUTO", ACC) { autoAssign() }, gap())
        bar.addView(btn("?", ACC) { showHelp() }, gap())
        bar.addView(btn("ВЫХОД", RED) { quit() }, gap(0))

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        root.addView(body, LinearLayout.LayoutParams(MATCH, 0, 1f))

        val sc = HorizontalScrollView(this).apply { isFillViewport = true; isHorizontalScrollBarEnabled = false }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        sc.addView(row, FrameLayout.LayoutParams(WRAP, MATCH))
        body.addView(sc, LinearLayout.LayoutParams(0, MATCH, 1f))
        for (ch in Mixer.channels) {
            row.addView(makeStrip(ch), LinearLayout.LayoutParams(dp(84), MATCH).apply { marginEnd = dp(5) })
        }
        body.addView(makeMaster(), LinearLayout.LayoutParams(dp(96), MATCH).apply { marginStart = dp(4) })

        setBpm(Mixer.bpm, save = false)
        return root
    }

    private fun panel(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(4), dp(6), dp(4), dp(6))
        background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0xFF1C1F24.toInt(), 0xFF131519.toInt())
        ).apply { cornerRadius = dp(10).toFloat(); setStroke(dp(1), Color.BLACK) }
    }

    private fun makeStrip(ch: Channel): View {
        val hue = Color.HSVToColor(floatArrayOf(((ch.index * 36 + 185) % 360).toFloat(), 0.75f, 1f))
        val s = panel()
        s.addView(View(this).apply { setBackgroundColor(hue) },
            LinearLayout.LayoutParams(dp(46), dp(3)).apply { bottomMargin = dp(5) })

        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(TextView(this).apply {
            text = String.format(Locale.US, "%02d", ch.index + 1)
            setTextColor(Color.WHITE); textSize = 14f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        })
        val ledV = View(this)
        led(ledV, 0xFF20262E.toInt())
        head.addView(ledV, LinearLayout.LayoutParams(dp(9), dp(9)).apply { marginStart = dp(8) })
        s.addView(head)

        val icon = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = bg(0xFF05080A.toInt(), 10)
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        s.addView(icon, LinearLayout.LayoutParams(dp(52), dp(52)).apply { topMargin = dp(5) })
        val name = TextView(this).apply {
            setTextColor(ACC); textSize = 11f; gravity = Gravity.CENTER
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END; typeface = Typeface.DEFAULT_BOLD
        }
        s.addView(name, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(3) })
        icon.setOnClickListener { showPicker(ch) }
        name.setOnClickListener { showPicker(ch) }

        val pct = TextView(this).apply {
            setTextColor(0xFF9AA1AC.toInt()); textSize = 11f; gravity = Gravity.CENTER
            typeface = Typeface.MONOSPACE
        }
        s.addView(pct)

        val fader = FaderView(this, hue)
        fader.defaultValue = 0.8f
        s.addView(fader, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = dp(4); bottomMargin = dp(6) })

        val mute = btn("MUTE", RED, true) { v -> ch.mute = !ch.mute; setOn(v, ch.mute); Mixer.save(this@MainActivity) }
        val solo = btn("SOLO", YEL, true) { v -> ch.solo = !ch.solo; setOn(v, ch.solo); Mixer.save(this@MainActivity) }
        s.addView(mute, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(4) })
        s.addView(solo, LinearLayout.LayoutParams(MATCH, WRAP))

        val st = Strip(ch, icon, name, ledV, pct, fader, mute, solo)
        fader.onChange = { v -> ch.vol = v; pct.text = pctText(v) }
        strips.add(st)
        bindStrip(st)
        return s
    }

    private fun bindStrip(st: Strip) {
        val ch = st.ch
        val pkg = ch.pkg
        if (pkg == null) {
            st.icon.setImageResource(android.R.drawable.ic_input_add)
            st.icon.setColorFilter(0xFF4B5563.toInt())
            st.name.text = "ВЫБРАТЬ"
            st.name.setTextColor(0xFF6F7682.toInt())
        } else {
            st.icon.clearColorFilter()
            try { st.icon.setImageDrawable(packageManager.getApplicationIcon(pkg)) } catch (_: Exception) { }
            st.name.text = ch.label
            st.name.setTextColor(ACC)
        }
        st.fader.setValue(ch.vol)
        st.pct.text = pctText(ch.vol)
        setOn(st.mute, ch.mute)
        setOn(st.solo, ch.solo)
    }

    private fun makeMaster(): View {
        val am = getSystemService(AudioManager::class.java)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val s = panel()
        s.addView(View(this).apply { setBackgroundColor(Color.WHITE) },
            LinearLayout.LayoutParams(dp(46), dp(3)).apply { bottomMargin = dp(5) })
        s.addView(TextView(this).apply {
            text = "MASTER"; setTextColor(Color.WHITE); textSize = 13f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        })
        s.addView(TextView(this).apply {
            text = "громкость\nтелефона"; setTextColor(0xFF6F7682.toInt()); textSize = 10f; gravity = Gravity.CENTER
        })
        masterPct = TextView(this).apply {
            setTextColor(0xFF9AA1AC.toInt()); textSize = 11f; gravity = Gravity.CENTER; typeface = Typeface.MONOSPACE
        }
        s.addView(masterPct, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(4) })
        masterFader = FaderView(this, Color.WHITE)
        masterFader.defaultValue = 0.6f
        masterFader.onChange = { v ->
            try { am.setStreamVolume(AudioManager.STREAM_MUSIC, (v * max).roundToInt(), 0) } catch (_: Throwable) { }
            masterPct.text = pctText(v)
        }
        s.addView(masterFader, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = dp(4); bottomMargin = dp(6) })
        s.addView(btn("СБРОС", ACC, true) { resetMutes() }, LinearLayout.LayoutParams(MATCH, WRAP))
        syncMaster()
        return s
    }

    private fun syncMaster() {
        if (!::masterFader.isInitialized) return
        val am = getSystemService(AudioManager::class.java)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val v = am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
        masterFader.setValue(v)
        masterPct.text = pctText(v)
    }

    private fun refresh() {
        val active = Engine.activeUids
        for (st in strips) {
            val on = st.ch.uid >= 0 && st.ch.uid in active
            led(st.led, if (on) GREEN else 0xFF20262E.toInt())
        }
        updateShizuku()
        setOn(recBtn, RecordService.recording)
        recTime.text = if (RecordService.recording) {
            val s = (SystemClock.elapsedRealtime() - RecordService.startedAt) / 1000
            String.format(Locale.US, "%02d:%02d", s / 60, s % 60)
        } else "00:00"
        setOn(midiBtn, MidiClock.enabled)
        setOn(playBtn, MidiClock.playing)
        playBtn.text = if (MidiClock.playing) "■ STOP" else "▶ START"
    }

    private var lastShizuku = -1
    private fun updateShizuku() {
        val st = Engine.shizukuState()
        val code = if (st == 2 && Engine.ready) 3 else st
        if (code == lastShizuku) return
        lastShizuku = code
        when (code) {
            0 -> { shizukuBtn.text = "SHIZUKU: ВЫКЛ"; shizukuBtn.tag = RED; setOn(shizukuBtn, true) }
            1 -> { shizukuBtn.text = "РАЗРЕШИТЬ SHIZUKU"; shizukuBtn.tag = YEL; setOn(shizukuBtn, true) }
            2 -> { shizukuBtn.text = "ПОДКЛЮЧЕНИЕ…"; shizukuBtn.tag = YEL; setOn(shizukuBtn, true) }
            else -> { shizukuBtn.text = "● ПОДКЛЮЧЕНО"; shizukuBtn.tag = GREEN; setOn(shizukuBtn, true) }
        }
    }

    // ---------------------------------------------------------------- actions

    private fun onShizuku() {
        when (Engine.shizukuState()) {
            0 -> showHelp()
            1 -> try { Shizuku.requestPermission(7) } catch (t: Throwable) { showHelp() }
            else -> toast("Shizuku: " + Engine.status)
        }
    }

    private fun showPicker(ch: Channel) {
        val pm = packageManager
        val my = packageName
        val playing = LinkedHashSet<String>()
        for (u in Engine.activeUids) {
            pm.getPackagesForUid(u)?.forEach { if (it != my) playing.add(it) }
        }
        val items = ArrayList<AppItem>()
        items.add(AppItem(null, "— Пустой канал —", false))
        for (p in playing) items.add(AppItem(p, labelOf(p), true))
        @Suppress("DEPRECATION")
        val launch = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName }
            .distinct()
            .filter { it != my && it !in playing }
            .map { AppItem(it, labelOf(it), false) }
            .sortedBy { it.label.lowercase(Locale.getDefault()) }
        items.addAll(launch)

        val list = ListView(this)
        list.adapter = object : BaseAdapter() {
            override fun getCount(): Int = items.size
            override fun getItem(position: Int): Any = items[position]
            override fun getItemId(position: Int): Long = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val item = items[position]
                val row = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(14), dp(8), dp(14), dp(8))
                }
                val iv = ImageView(this@MainActivity)
                item.pkg?.let { p -> try { iv.setImageDrawable(pm.getApplicationIcon(p)) } catch (_: Exception) { } }
                row.addView(iv, LinearLayout.LayoutParams(dp(36), dp(36)))
                row.addView(TextView(this@MainActivity).apply {
                    text = item.label; setTextColor(Color.WHITE); textSize = 15f
                    setPadding(dp(12), 0, dp(8), 0)
                }, LinearLayout.LayoutParams(0, WRAP, 1f))
                if (item.playing) {
                    row.addView(TextView(this@MainActivity).apply {
                        text = "● ЗВУЧИТ"; setTextColor(GREEN); textSize = 12f; typeface = Typeface.DEFAULT_BOLD
                    })
                }
                return row
            }
        }
        val dlg = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Канал ${ch.index + 1}: выберите приложение")
            .setView(list)
            .setNegativeButton("Отмена", null)
            .create()
        list.setOnItemClickListener { _, _, pos, _ ->
            assign(ch, items[pos].pkg)
            dlg.dismiss()
        }
        dlg.show()
    }

    private fun assign(ch: Channel, pkg: String?) {
        if (pkg != null) {
            for (o in Mixer.channels) if (o !== ch && o.pkg == pkg) { o.pkg = null; Mixer.resolve(this, o) }
        }
        ch.pkg = pkg
        Mixer.resolve(this, ch)
        Mixer.save(this)
        strips.forEach { bindStrip(it) }
    }

    private fun autoAssign() {
        if (!Engine.ready) { toast("Сначала подключите Shizuku (кнопка вверху)"); return }
        var n = 0
        for (u in Engine.activeUids) {
            if (u < 10000) continue
            if (Mixer.channels.any { it.uid == u }) continue
            val pkg = packageManager.getPackagesForUid(u)?.firstOrNull { it != packageName } ?: continue
            val free = Mixer.channels.firstOrNull { it.pkg == null } ?: break
            free.pkg = pkg
            Mixer.resolve(this, free)
            n++
        }
        Mixer.save(this)
        strips.forEach { bindStrip(it) }
        toast(if (n == 0) "Новых звучащих приложений нет. Включите звук в нужном приложении и нажмите AUTO ещё раз"
              else "Добавлено приложений: $n")
    }

    private fun resetMutes() {
        for (c in Mixer.channels) { c.mute = false; c.solo = false }
        Mixer.save(this)
        strips.forEach { bindStrip(it) }
    }

    private fun setBpm(b: Double, save: Boolean = true) {
        Mixer.bpm = Math.round(b.coerceIn(40.0, 240.0) * 10) / 10.0
        MidiClock.bpm = Mixer.bpm
        bpmText.text = String.format(Locale.US, "%.1f", Mixer.bpm)
        if (save) Mixer.save(this)
    }

    private fun tap() {
        val now = SystemClock.uptimeMillis()
        tapTimes.removeAll { now - it > 2000 }
        tapTimes.add(now)
        if (tapTimes.size >= 2) {
            val t = tapTimes.takeLast(5)
            val avg = (t.last() - t.first()).toDouble() / (t.size - 1)
            if (avg > 0) setBpm(60000.0 / avg)
        }
    }

    private fun onMidi() {
        if (MidiClock.enabled) {
            MidiClock.disable(); refresh(); toast("MIDI Clock выключен"); return
        }
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_MIDI)) {
            toast("Это устройство не поддерживает MIDI"); return
        }
        MidiClock.bpm = Mixer.bpm
        MidiClock.enable(this)
        refresh()
        ui.postDelayed({
            val names = MidiClock.deviceNames
            toast(
                if (names.isEmpty()) "MIDI Clock включён, но MIDI-устройств пока нет. Подключите синтезатор (USB-OTG) или откройте синт-приложение с MIDI-входом"
                else "MIDI Clock → " + names.joinToString(", ")
            )
        }, 1500)
    }

    private fun onPlay() {
        if (!MidiClock.enabled) { toast("Сначала включите MIDI SYNC"); return }
        if (MidiClock.playing) MidiClock.stop() else MidiClock.start()
        refresh()
    }

    private fun onRec() {
        if (RecordService.recording) {
            stopService(Intent(this, RecordService::class.java)); return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC); return
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        toast("Выберите «Весь экран», чтобы записать звук всех приложений")
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_PROJ)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PROJ && resultCode == RESULT_OK && data != null) {
            val i = Intent(this, RecordService::class.java).putExtra("code", resultCode).putExtra("data", data)
            try { startForegroundService(i) } catch (t: Throwable) { toast("Не удалось начать запись") }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MIC && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) onRec()
    }

    private fun quit() {
        MidiClock.disable()
        if (RecordService.recording) stopService(Intent(this, RecordService::class.java))
        stopService(Intent(this, MixerService::class.java))
        Mixer.save(this)
        finishAndRemoveTask()
    }

    private fun showHelp() {
        val tv = TextView(this).apply {
            setTextColor(0xFFD1D5DB.toInt()); textSize = 14f
            setPadding(dp(20), dp(12), dp(20), dp(12))
            text = HELP
        }
        val sv = ScrollView(this)
        sv.addView(tv)
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Virtual Mixer — как пользоваться")
            .setView(sv)
            .setPositiveButton("Понятно", null)
            .show()
    }
}

private const val HELP = """КАК ЭТО РАБОТАЕТ
Каждому из 10 фейдеров назначается приложение: синтезатор, драм-машина, YouTube, плеер и т.д. Фейдер меняет громкость только этого приложения. Зелёный огонёк над каналом горит, когда приложение звучит.

1. ОДИН РАЗ: НАСТРОЙТЕ SHIZUKU
Android не даёт обычным приложениям менять громкость других приложений. Бесплатное приложение Shizuku даёт такое право без root.
• Установите Shizuku из Google Play.
• Включите режим разработчика: Настройки → Сведения о телефоне → Сведения о ПО → 7 раз нажмите «Номер сборки».
• Настройки → Параметры разработчика → включите «Отладка по Wi-Fi» (нужна любая Wi-Fi сеть).
• Откройте Shizuku → «Сопряжение». Затем в «Отладка по Wi-Fi» нажмите «Подключить устройство с кодом сопряжения» и введите код в уведомлении Shizuku. Потом в Shizuku нажмите «Запустить».
• Вернитесь сюда, нажмите кнопку SHIZUKU вверху → «Разрешить».
После перезагрузки телефона в Shizuku снова нажмите «Запустить».

2. НАЗНАЧЬТЕ ПРИЛОЖЕНИЯ
Нажмите на иконку канала и выберите приложение. Сверху списка — приложения, которые звучат прямо сейчас («● ЗВУЧИТ»). Кнопка AUTO сама разложит все звучащие приложения по свободным каналам.
MUTE — заглушить, SOLO — слушать только выбранные каналы. Двойной тап по фейдеру — вернуть 80%. MASTER — общая громкость телефона.
Микшер работает и в фоне: переключайтесь в синтезатор или YouTube, громкости сохранятся.

3. ЗАПИСЬ (● REC)
Записывает звук всех приложений в WAV: Музыка/VirtualMixer. В окне разрешения выберите «Весь экран». Некоторые приложения запрещают запись своего звука — в записи они будут тихими.

4. MIDI SYNC
Отправляет MIDI Clock и Start/Stop на все MIDI-входы: синтезаторы и драм-машины по USB-OTG и синт-приложения на телефоне, у которых есть MIDI-вход. Задайте BPM (− / + или TAP), включите MIDI SYNC и нажмите START. В синтезаторе выберите синхронизацию от внешнего MIDI Clock.

ВЫХОД — выключает микшер и возвращает всем приложениям обычную громкость."""
