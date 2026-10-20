package com.kine.clipper

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings as SysSettings
import android.view.Display
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import kotlin.math.abs
import kotlin.math.min

/**
 * Plovoucí tlačítko Klip přes hru (okno přes jiné aplikace). Ukáže se hned po
 * povolení nahrávání (oranžově bliká, dokud se snímání nerozjede), pak je tyrkysové.
 * Klepnutí = klip (zabliká oranžově, pak zeleně = uloženo, červeně = nepovedlo se),
 * tažení = posun (po puštění přiskočí k bližšímu okraji, pozice se pamatuje i po
 * otočení), dlouhé podržení = otevřít appku.
 *
 * Vzhled „Tlačítko“ (výchozí): kulaté tlačítko 52 dp - dobře vidět. Vzhled „Proužek“:
 * tenká čárka u okraje obrazovky (plocha na dotyk je větší, ale průhledná) - v klipu
 * skoro není vidět. Úplně mimo klip je bublina, když na Androidu 14+ v okně systému
 * zvolíš „Jedna aplikace“ (nahrává se jen hra).
 */
class Bubble(base: Context, private val settings: Settings, private val onClip: () -> Unit, private val onLongPress: () -> Unit) {
    enum class State { STARTING, READY, SAVING, SAVED, FAILED }

    /** Android 11+: okno přes „window context“ - správné rozměry, lišty a výřez obrazovky i ze služby. */
    private val context: Context = windowContext(base)
    private val wm = context.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val density = context.resources.displayMetrics.density
    private val edge = settings.bubbleLook == "edge"
    private var view: BubbleView? = null
    private var params: WindowManager.LayoutParams? = null
    private var snapAnim: ValueAnimator? = null
    private val backToReady = Runnable { setState(State.READY) }

    var state = State.STARTING
        private set
    val shown: Boolean get() = view != null

    private fun dp(v: Float): Int = (v * density).toInt()
    private val winW: Int get() = if (edge) dp(26f) else dp(60f)
    private val winH: Int get() = if (edge) dp(80f) else dp(60f)

    /** Zobrazit (hlavní vlákno). Bez oprávnění „přes jiné aplikace“ nic. */
    @SuppressLint("ClickableViewAccessibility", "RtlHardcoded")
    fun show(initial: State) {
        if (view != null || !SysSettings.canDrawOverlays(context)) return
        state = initial
        val v = BubbleView(context, edge, density)
        v.state = initial
        v.contentDescription = context.getString(R.string.clip_now)
        val p = WindowManager.LayoutParams(
            winW, winH,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            if (Build.VERSION.SDK_INT >= 30) {
                // Souřadnice od rohu celé obrazovky; lištám a výřezu se bublina vyhne sama (area()).
                fitInsetsTypes = 0
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }
        place(p, v)
        val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var downAt = 0L
        var moved = false
        var longFired = false
        val longPress = Runnable {
            if (!moved) {
                longFired = true
                v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                onLongPress()
            }
        }
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    snapAnim?.cancel()
                    downX = e.rawX; downY = e.rawY; startX = p.x; startY = p.y
                    downAt = SystemClock.uptimeMillis(); moved = false; longFired = false
                    v.setPressedLook(true)
                    main.postDelayed(longPress, 650)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!moved && (abs(dx) > slop || abs(dy) > slop)) {
                        moved = true
                        main.removeCallbacks(longPress)
                    }
                    if (moved) {
                        p.x = (startX + dx).toInt()
                        p.y = (startY + dy).toInt()
                        update(v, p)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    main.removeCallbacks(longPress)
                    v.setPressedLook(false)
                    if (moved) snap(v, p)
                    else if (e.actionMasked == MotionEvent.ACTION_UP && !longFired && SystemClock.uptimeMillis() - downAt < 600) tap(v)
                    true
                }
                else -> false
            }
        }
        try {
            wm.addView(v, p)
            view = v
            params = p
            v.hello()
        } catch (e: Exception) {
            Log.e("bublina: nejde zobrazit", e)
        }
    }

    private fun tap(v: BubbleView) {
        when (state) {
            // Ještě se nenahrává - zatřást „ne“, klip by byl prázdný.
            State.STARTING -> v.nope()
            State.SAVING -> { }
            else -> {
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                onClip()
            }
        }
    }

    /** Stav (z kteréhokoli vlákna): barva a blikání; uloženo / chyba se po chvíli vrátí na „připraveno“. */
    fun setState(s: State) {
        main.post {
            main.removeCallbacks(backToReady)
            state = s
            val v = view ?: return@post
            v.state = s
            if (s == State.SAVED) v.pop()
            if (s == State.FAILED) v.nope()
            if (s == State.SAVED || s == State.FAILED) main.postDelayed(backToReady, 1400)
        }
    }

    /** Po otočení / změně obrazovky: zpátky na zapamatovaný okraj a výšku. */
    fun relayout() {
        main.post {
            val v = view ?: return@post
            val p = params ?: return@post
            snapAnim?.cancel()
            place(p, v)
            update(v, p)
        }
    }

    fun hide() {
        main.removeCallbacks(backToReady)
        snapAnim?.cancel()
        val v = view ?: return
        view = null
        v.stopAnimations()
        try {
            wm.removeView(v)
        } catch (_: Exception) {
            // už není
        }
    }

    private fun screen(): Point {
        val pt = Point()
        try {
            val d = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
            @Suppress("DEPRECATION")
            d.getRealSize(pt)
        } catch (_: Exception) {
            pt.x = context.resources.displayMetrics.widthPixels
            pt.y = context.resources.displayMetrics.heightPixels
        }
        return pt
    }

    private fun margin(): Int = if (edge) 0 else dp(6f)

    /**
     * Kam bublina smí: obrazovka bez stavové a navigační lišty a výřezu kamery (Android 11+,
     * i na šířku s lištou po straně), na Androidu 10 celá obrazovka s okraji nahoře a dole.
     */
    private fun area(): Rect {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                val m = wm.currentWindowMetrics
                val b = m.bounds
                val i = m.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                val r = Rect(b.left + i.left, b.top + i.top, b.right - i.right, b.bottom - i.bottom)
                if (r.width() > winW * 2 && r.height() > winH * 2) return r
            } catch (_: Exception) {
                // níž náhrada
            }
        }
        val s = screen()
        return Rect(0, dp(28f), s.x, s.y - dp(40f))
    }

    private fun place(p: WindowManager.LayoutParams, v: BubbleView) {
        val a = area()
        val left = settings.bubbleSide == "left"
        p.x = if (left) a.left + margin() else a.right - winW - margin()
        val top = a.top + dp(8f)
        val bottom = maxOf(top, a.bottom - winH - dp(8f))
        p.y = (top + (bottom - top) * settings.bubbleY).toInt().coerceIn(top, bottom)
        v.side = if (left) "left" else "right"
    }

    private fun update(v: View, p: WindowManager.LayoutParams) {
        try { wm.updateViewLayout(v, p) } catch (_: Exception) { }
    }

    /** Po puštění k bližšímu okraji (animovaně) a zapamatovat. */
    private fun snap(v: BubbleView, p: WindowManager.LayoutParams) {
        val a = area()
        val left = p.x + winW / 2 < (a.left + a.right) / 2
        val targetX = if (left) a.left + margin() else a.right - winW - margin()
        val top = a.top + dp(8f)
        val bottom = maxOf(top, a.bottom - winH - dp(8f))
        p.y = p.y.coerceIn(top, bottom)
        settings.bubbleSide = if (left) "left" else "right"
        settings.bubbleY = if (bottom > top) (p.y - top).toFloat() / (bottom - top) else 0.3f
        v.side = settings.bubbleSide
        val from = p.x
        snapAnim = ValueAnimator.ofInt(from, targetX).apply {
            duration = 180
            addUpdateListener {
                p.x = it.animatedValue as Int
                update(v, p)
            }
            start()
        }
    }
}

private fun windowContext(base: Context): Context {
    if (Build.VERSION.SDK_INT < 30) return base
    return try {
        val d = base.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY) ?: return base
        base.createDisplayContext(d).createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
    } catch (e: Exception) {
        Log.w("bublina: window context nejde (${e.message})")
        base
    }
}

/** Kresba bubliny (kruh s ikonou nůžek, nebo tenký proužek u okraje). */
@SuppressLint("ViewConstructor")
private class BubbleView(context: Context, private val edge: Boolean, private val density: Float) : View(context) {
    var state = Bubble.State.STARTING
        set(value) {
            field = value
            updatePulse()
            invalidate()
        }
    var side = "right"
        set(value) {
            field = value
            invalidate()
        }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE6121417.toInt() }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
    }
    private val pill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val icon: Drawable? = context.getDrawable(R.drawable.ic_clip)?.mutate()?.apply { setTint(Color.WHITE) }
    private val rect = RectF()
    private var pulse: ValueAnimator? = null
    private var glow = 1f
    private var pressedLook = false

    private fun stateColor(): Int = when (state) {
        Bubble.State.STARTING, Bubble.State.SAVING -> Palette.AMBER
        Bubble.State.SAVED -> Palette.GREEN
        Bubble.State.FAILED -> Palette.RED
        Bubble.State.READY -> Palette.BRAND
    }

    /** Oranžové pulzování, dokud se čeká (start / ukládání klipu). */
    private fun updatePulse() {
        val busy = state == Bubble.State.STARTING || state == Bubble.State.SAVING
        if (busy && pulse == null) {
            pulse = ValueAnimator.ofFloat(1f, 0.35f).apply {
                duration = 550
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                addUpdateListener {
                    glow = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        } else if (!busy) {
            pulse?.cancel()
            pulse = null
            glow = 1f
        }
    }

    fun setPressedLook(on: Boolean) {
        pressedLook = on
        invalidate()
    }

    /** Při objevení krátce „vyskočí“, ať je vidět, kde je. */
    fun hello() {
        scaleX = 0.5f
        scaleY = 0.5f
        animate().scaleX(1f).scaleY(1f).setDuration(320).setInterpolator(OvershootInterpolator(2.2f)).start()
    }

    fun pop() {
        scaleX = 1.18f
        scaleY = 1.18f
        animate().scaleX(1f).scaleY(1f).setDuration(260).start()
    }

    fun nope() {
        val d = 5f * density
        animate().cancel()
        translationX = 0f
        animate().translationX(d).setDuration(50).withEndAction {
            animate().translationX(-d).setDuration(70).withEndAction {
                animate().translationX(0f).setDuration(50).start()
            }.start()
        }.start()
    }

    fun stopAnimations() {
        pulse?.cancel()
        pulse = null
        animate().cancel()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val col = stateColor()
        if (edge) {
            val pw = (if (pressedLook) 8f else 6f) * density
            val ph = 56f * density
            val left = if (side == "left") 0f else w - pw
            rect.set(left, (h - ph) / 2f, left + pw, (h + ph) / 2f)
            pill.color = col
            pill.alpha = (235 * glow).toInt().coerceIn(60, 235)
            c.drawRoundRect(rect, pw / 2f, pw / 2f, pill)
            return
        }
        val r = min(w, h) / 2f - 4f * density
        val k = if (pressedLook) 0.92f else 1f
        c.save()
        c.scale(k, k, w / 2f, h / 2f)
        c.drawCircle(w / 2f, h / 2f, r, fill)
        ring.color = col
        ring.alpha = (255 * glow).toInt().coerceIn(70, 255)
        c.drawCircle(w / 2f, h / 2f, r - ring.strokeWidth / 2f, ring)
        icon?.let {
            val s = (24f * density).toInt()
            val l = ((w - s) / 2f).toInt()
            val t = ((h - s) / 2f).toInt()
            it.setBounds(l, t, l + s, t + s)
            it.draw(c)
        }
        c.restore()
    }
}
