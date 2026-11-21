package com.virtualmixer.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View

/** Vertical DJ-style fader. Drag up/down, double-tap = default. */
class FaderView(ctx: Context, private val accent: Int) : View(ctx) {
    var value = 0.8f
        private set
    var defaultValue = 0.8f
    var onChange: ((Float) -> Unit)? = null

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val d = resources.displayMetrics.density
    private val thumbH get() = 28 * d
    private var startY = 0f
    private var startV = 0f
    private var lastTap = 0L

    fun setValue(v: Float, notify: Boolean = false) {
        value = v.coerceIn(0f, 1f)
        invalidate()
        if (notify) onChange?.invoke(value)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val pad = thumbH / 2
        val cx = w / 2
        p.shader = null
        p.color = 0xFF2C3138.toInt()
        p.strokeWidth = d
        for (i in 0..10) {
            val y = pad + (h - 2 * pad) * i / 10f
            val len = if (i % 5 == 0) 14 * d else 10 * d
            c.drawLine(cx - 6 * d - len, y, cx - 6 * d, y, p)
            c.drawLine(cx + 6 * d, y, cx + 6 * d + len, y, p)
        }
        p.color = 0xFF000000.toInt()
        c.drawRoundRect(cx - 3 * d, pad, cx + 3 * d, h - pad, 3 * d, 3 * d, p)
        val ty = pad + (h - 2 * pad) * (1 - value)
        p.color = accent
        c.drawRoundRect(cx - 2 * d, ty, cx + 2 * d, h - pad, 2 * d, 2 * d, p)
        val tw = minOf(w * 0.92f, 46 * d)
        p.shader = LinearGradient(
            0f, ty - pad, 0f, ty + pad,
            intArrayOf(0xFFE6E9EF.toInt(), 0xFF9AA0AA.toInt(), 0xFF4F545D.toInt(), 0xFF9AA0AA.toInt(), 0xFFD4D8DE.toInt()),
            floatArrayOf(0f, 0.4f, 0.5f, 0.6f, 1f), Shader.TileMode.CLAMP
        )
        c.drawRoundRect(cx - tw / 2, ty - pad, cx + tw / 2, ty + pad, 5 * d, 5 * d, p)
        p.shader = null
        p.color = 0xFF111111.toInt()
        c.drawRect(cx - tw / 2 + 5 * d, ty - d, cx + tw / 2 - 5 * d, ty + d, p)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val usable = (height - thumbH).coerceAtLeast(1f)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                startY = e.y; startV = value
                val now = SystemClock.uptimeMillis()
                if (now - lastTap < 300) { setValue(defaultValue, true); startV = value }
                lastTap = now
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                setValue(startV - (e.y - startY) / usable, true)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(e)
    }
}
