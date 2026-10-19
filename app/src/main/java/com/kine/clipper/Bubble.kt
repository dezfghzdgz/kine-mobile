package com.kine.clipper

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import kotlin.math.abs

/**
 * Plovoucí tlačítko Klip přes hru (okno přes jiné aplikace). Tažením se
 * posouvá, klepnutím uloží klip; po uložení krátce zabliká.
 */
class Bubble(private val context: Context, private val onClip: () -> Unit) {
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var view: ImageView? = null
    private var params: WindowManager.LayoutParams? = null
    private val main = Handler(Looper.getMainLooper())

    fun show() {
        if (view != null || !Settings.canDrawOverlays(context)) return
        val density = context.resources.displayMetrics.density
        val size = (56 * density).toInt()
        val iv = ImageView(context).apply {
            setImageResource(R.drawable.ic_stat_kine)
            setBackgroundResource(R.drawable.bubble_bg)
            val pad = (14 * density).toInt()
            setPadding(pad, pad, pad, pad)
            contentDescription = "Clip"
            alpha = 0.85f
        }
        val p = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = context.resources.displayMetrics.widthPixels - size - (12 * density).toInt()
            y = context.resources.displayMetrics.heightPixels / 3
        }
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var downAt = 0L
        var moved = false
        iv.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = p.x; startY = p.y; downAt = System.currentTimeMillis(); moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (abs(dx) > 8 * density || abs(dy) > 8 * density) moved = true
                    if (moved) {
                        p.x = (startX + dx).toInt()
                        p.y = (startY + dy).toInt()
                        try { wm.updateViewLayout(v, p) } catch (_: Exception) { }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved && System.currentTimeMillis() - downAt < 600) {
                        v.performClick()
                        onClip()
                    }
                    true
                }
                else -> false
            }
        }
        try {
            wm.addView(iv, p)
            view = iv
            params = p
        } catch (e: Exception) {
            Log.e("bublina: nejde zobrazit", e)
        }
    }

    /** Krátké bliknutí (klip uložen / zpracovává se). */
    fun flash(ok: Boolean) {
        val v = view ?: return
        main.post {
            v.alpha = 1f
            v.scaleX = 1.25f
            v.scaleY = 1.25f
            v.animate().alpha(0.85f).scaleX(1f).scaleY(1f).setDuration(if (ok) 500 else 900).start()
        }
    }

    fun setVisible(visible: Boolean) {
        val v = view ?: return
        main.post { v.visibility = if (visible) View.VISIBLE else View.GONE }
    }

    fun hide() {
        val v = view ?: return
        view = null
        try {
            wm.removeView(v)
        } catch (_: Exception) {
            // už není
        }
    }
}
