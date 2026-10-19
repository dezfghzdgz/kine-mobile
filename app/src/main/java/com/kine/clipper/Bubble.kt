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
 *
 * Nenápadný režim (discreet, výchozí): místo plného tlačítka malá tečka, která po
 * 1,5 s klidu zprůhlední na čtvrtinu - v klipu je sotva vidět, prst ji ale trefí
 * (plocha na dotyk zůstává 56 dp). Po dotyku se zase rozsvítí. Úplně mimo záznam
 * je bublina jen tehdy, když se na Androidu 14+ nahrává „jedna aplikace“.
 */
class Bubble(private val context: Context, private val onClip: () -> Unit, private val discreet: Boolean = true) {
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var view: ImageView? = null
    private var params: WindowManager.LayoutParams? = null
    private val main = Handler(Looper.getMainLooper())
    private val restAlpha = if (discreet) 0.25f else 0.85f
    private val dim = Runnable { view?.animate()?.alpha(restAlpha)?.setDuration(400)?.start() }

    /** Po dotyku / klipu plná viditelnost, po chvíli klidu zase ztlumit (jen nenápadný režim). */
    private fun wake(v: View) {
        main.removeCallbacks(dim)
        v.animate().cancel()
        v.alpha = if (discreet) 0.95f else 1f
        if (discreet) main.postDelayed(dim, 1500)
    }

    fun show() {
        if (view != null || !Settings.canDrawOverlays(context)) return
        val density = context.resources.displayMetrics.density
        val size = (56 * density).toInt()
        val iv = ImageView(context).apply {
            setImageResource(R.drawable.ic_stat_kine)
            setBackgroundResource(R.drawable.bubble_bg)
            // Nenápadná: kresba 28 dp uprostřed plochy 56 dp (pozadí se zmenší s paddingem).
            val pad = ((if (discreet) 18 else 14) * density).toInt()
            setPadding(pad, pad, pad, pad)
            if (discreet) {
                scaleX = 0.6f
                scaleY = 0.6f
            }
            contentDescription = "Clip"
            alpha = restAlpha
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
                    wake(v)
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
            if (discreet) {
                iv.alpha = 0.95f
                main.postDelayed(dim, 2500)
            }
        } catch (e: Exception) {
            Log.e("bublina: nejde zobrazit", e)
        }
    }

    /** Krátké bliknutí (klip uložen / zpracovává se). */
    fun flash(ok: Boolean) {
        val v = view ?: return
        main.post {
            main.removeCallbacks(dim)
            v.animate().cancel()
            val base = if (discreet) 0.6f else 1f
            v.alpha = 1f
            v.scaleX = base * 1.25f
            v.scaleY = base * 1.25f
            v.animate().alpha(if (discreet) 0.95f else 0.85f).scaleX(base).scaleY(base).setDuration(if (ok) 500 else 900).start()
            if (discreet) main.postDelayed(dim, 1500)
        }
    }

    fun setVisible(visible: Boolean) {
        val v = view ?: return
        main.post { v.visibility = if (visible) View.VISIBLE else View.GONE }
    }

    fun hide() {
        main.removeCallbacks(dim)
        val v = view ?: return
        view = null
        try {
            wm.removeView(v)
        } catch (_: Exception) {
            // už není
        }
    }
}
