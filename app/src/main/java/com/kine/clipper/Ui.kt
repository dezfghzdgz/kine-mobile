package com.kine.clipper

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.LruCache
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import java.util.concurrent.Executors

/** Barvy appky (tmavé jako Kine; text vždycky s vlastní barvou - na žádném telefonu bílé na bílém). */
object Palette {
    const val BG = 0xFF0B0C0E.toInt()
    const val CARD = 0xFF16181C.toInt()
    const val CARD_HI = 0xFF22252B.toInt()
    const val LINE = 0xFF2C3038.toInt()
    const val TEXT = 0xFFF2F4F7.toInt()
    const val DIM = 0xFFA6ACB6.toInt()
    const val FAINT = 0xFF6E7480.toInt()
    const val BRAND = 0xFF2EE6C4.toInt()
    const val BRAND_SOFT = 0x262EE6C4
    const val ON_BRAND = 0xFF04201B.toInt()
    const val RED = 0xFFFF5A5F.toInt()
    const val RED_SOFT = 0x26FF5A5F
    const val AMBER = 0xFFFFB547.toInt()
    const val AMBER_SOFT = 0x26FFB547
    const val GREEN = 0xFF3DDC84.toInt()
}

/** Tlačítko z ikony a popisku (jde přepsat za běhu - Start / Stop). */
class Btn(val root: LinearLayout, val icon: ImageView, val label: TextView)

/**
 * Stavební kostky rozhraní (bez knihoven): karty, tlačítka, přepínače, segmenty,
 * otazníček s vysvětlivkou. Heslovitě - víc textu jen po klepnutí na „?“.
 */
class Ui(private val ctx: Context) {
    private val density = ctx.resources.displayMetrics.density

    enum class Kind { PRIMARY, DANGER, GHOST, OUTLINE }

    fun dp(v: Int): Int = (v * density + 0.5f).toInt()

    fun round(color: Int, radiusDp: Int, stroke: Int = 0, strokeDp: Float = 0f): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radiusDp * density
        setColor(color)
        if (strokeDp > 0f) setStroke((strokeDp * density + 0.5f).toInt(), stroke)
    }

    fun oval(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    /** Pozadí s vlnkou po dotyku (tvar podle pozadí). */
    fun pressable(bg: Drawable, ripple: Int = 0x33FFFFFF): Drawable = RippleDrawable(ColorStateList.valueOf(ripple), bg, null)

    fun text(s: CharSequence, size: Float = 15f, color: Int = Palette.TEXT, bold: Boolean = false): TextView = TextView(ctx).apply {
        text = s
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    fun icon(res: Int, color: Int, sizeDp: Int = 20): ImageView = ImageView(ctx).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(color)
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

    fun lp(w: Int, h: Int, weight: Float = 0f): LinearLayout.LayoutParams = LinearLayout.LayoutParams(w, h, weight)
    fun matchWrap(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    fun vbox(): LinearLayout = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    fun hbox(): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    fun space(hDp: Int): View = View(ctx).apply { layoutParams = LinearLayout.LayoutParams(1, dp(hDp)) }

    fun divider(): View = View(ctx).apply {
        setBackgroundColor(Palette.LINE)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply {
            topMargin = dp(4)
            bottomMargin = dp(4)
        }
    }

    /** Karta s nadpisem (ikona + text) - jedna skupina nastavení / stav. */
    fun card(title: String? = null, iconRes: Int? = null): LinearLayout = vbox().apply {
        background = round(Palette.CARD, 18)
        setPadding(dp(16), dp(14), dp(16), dp(14))
        layoutParams = matchWrap().apply { bottomMargin = dp(12) }
        if (title != null) {
            val head = hbox()
            if (iconRes != null) head.addView(icon(iconRes, Palette.BRAND, 18), lp(dp(18), dp(18)).apply { marginEnd = dp(8) })
            head.addView(text(title, 13f, Palette.DIM, true).apply { letterSpacing = 0.04f })
            addView(head, matchWrap().apply { bottomMargin = dp(8) })
        }
    }

    fun button(label: String, iconRes: Int?, kind: Kind, onClick: () -> Unit): Btn {
        val root = hbox().apply {
            gravity = Gravity.CENTER
            minimumHeight = dp(52)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
        val ic = ImageView(ctx)
        val tv = text(label, 16f, Palette.TEXT, true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        root.addView(ic, lp(dp(20), dp(20)).apply { marginEnd = dp(8) })
        root.addView(tv)
        val b = Btn(root, ic, tv)
        style(b, label, iconRes, kind)
        return b
    }

    fun style(b: Btn, label: String, iconRes: Int?, kind: Kind) {
        val (bg, fg, stroke) = when (kind) {
            Kind.PRIMARY -> Triple(Palette.BRAND, Palette.ON_BRAND, 0)
            Kind.DANGER -> Triple(Palette.RED_SOFT, Palette.RED, Palette.RED)
            Kind.GHOST -> Triple(Palette.CARD_HI, Palette.TEXT, 0)
            Kind.OUTLINE -> Triple(0x00000000, Palette.BRAND, Palette.LINE)
        }
        b.root.background = pressable(round(bg, 14, stroke, if (stroke != 0) 1.5f else 0f), if (kind == Kind.PRIMARY) 0x33000000 else 0x33FFFFFF)
        b.label.text = label
        b.label.setTextColor(fg)
        if (iconRes != null) {
            b.icon.setImageResource(iconRes)
            b.icon.imageTintList = ColorStateList.valueOf(fg)
            b.icon.visibility = View.VISIBLE
        } else b.icon.visibility = View.GONE
    }

    /** Malé kulaté tlačítko jen s ikonou (akce u klipu). */
    fun iconButton(res: Int, color: Int, description: String, onClick: () -> Unit): ImageView = ImageView(ctx).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(color)
        val pad = dp(8)
        setPadding(pad, pad, pad, pad)
        background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), null, oval(0xFFFFFFFF.toInt()))
        contentDescription = description
        isClickable = true
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(dp(38), dp(38))
    }

    /** Segmenty (jedna volba z několika) - čitelnější než rozbalovací seznam. */
    fun segmented(options: List<String>, selected: Int, onPick: (Int) -> Unit): LinearLayout {
        val box = hbox().apply {
            background = round(Palette.CARD_HI, 12)
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }
        val items = ArrayList<TextView>()
        fun paint(sel: Int) {
            items.forEachIndexed { i, t ->
                val on = i == sel
                t.setTextColor(if (on) Palette.ON_BRAND else Palette.DIM)
                t.background = if (on) round(Palette.BRAND, 10) else null
                t.typeface = if (on) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            }
        }
        options.forEachIndexed { i, label ->
            val t = TextView(ctx).apply {
                text = label
                textSize = 14f
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(4), dp(9), dp(4), dp(9))
                setOnClickListener {
                    paint(i)
                    onPick(i)
                }
            }
            items.add(t)
            box.addView(t, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        paint(selected.coerceIn(0, options.size - 1))
        return box
    }

    /** Popisek nad ovládáním (segmenty, posuvník), volitelně s „?“. */
    fun field(label: String, control: View, hint: String? = null): LinearLayout {
        val box = vbox().apply { layoutParams = matchWrap().apply { topMargin = dp(6); bottomMargin = dp(6) } }
        val head = hbox()
        head.addView(text(label, 14f, Palette.TEXT), lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        var hintView: TextView? = null
        if (hint != null) {
            hintView = hintText(hint)
            head.addView(hintToggle(hintView))
        }
        box.addView(head, matchWrap().apply { bottomMargin = dp(6) })
        hintView?.let { box.addView(it) }
        box.addView(control, matchWrap())
        return box
    }

    /** Řádek s přepínačem (celý řádek jde klepnout), volitelně ikona a „?“. */
    fun switchRow(label: String, iconRes: Int?, checked: Boolean, hint: String? = null, onChange: (Boolean) -> Unit): LinearLayout {
        val box = vbox()
        val row = hbox().apply { minimumHeight = dp(50) }
        if (iconRes != null) row.addView(icon(iconRes, Palette.DIM, 22), lp(dp(22), dp(22)).apply { marginEnd = dp(14) })
        row.addView(text(label, 15f), lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        var hintView: TextView? = null
        if (hint != null) {
            hintView = hintText(hint)
            row.addView(hintToggle(hintView), lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6) })
        }
        val sw = Switch(ctx).apply {
            isChecked = checked
            showText = false
            thumbTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(Palette.BRAND, 0xFFC9CED6.toInt()))
            trackTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(0x802EE6C4.toInt(), 0x66FFFFFF))
            setOnCheckedChangeListener { _, on -> onChange(on) }
        }
        row.addView(sw)
        row.setOnClickListener { sw.toggle() }
        box.addView(row, matchWrap())
        hintView?.let { box.addView(it) }
        return box
    }

    fun seek(max: Int, value: Int, onChange: (Int) -> Unit): SeekBar = SeekBar(ctx).apply {
        this.max = max
        progress = value.coerceIn(0, max)
        progressTintList = ColorStateList.valueOf(Palette.BRAND)
        thumbTintList = ColorStateList.valueOf(Palette.BRAND)
        progressBackgroundTintList = ColorStateList.valueOf(Palette.LINE)
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) onChange(p) }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun hintText(hint: String): TextView = text(hint, 13f, Palette.DIM).apply {
        visibility = View.GONE
        background = round(Palette.CARD_HI, 10)
        setPadding(dp(10), dp(8), dp(10), dp(8))
        layoutParams = matchWrap().apply { bottomMargin = dp(6) }
    }

    /** „?“ - klepnutí ukáže / schová vysvětlivku. */
    private fun hintToggle(target: TextView): TextView = TextView(ctx).apply {
        text = "?"
        textSize = 12f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setTextColor(Palette.DIM)
        background = round(Palette.CARD_HI, 11, Palette.LINE, 1f)
        val s = dp(22)
        layoutParams = LinearLayout.LayoutParams(s, s)
        minWidth = s
        minHeight = s
        setOnClickListener { target.visibility = if (target.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
    }

    /** Čip (rychlé zapnutí / vypnutí na hlavní obrazovce). */
    fun chip(iconRes: Int, label: String, on: Boolean, enabled: Boolean, onClick: () -> Unit): LinearLayout = hbox().apply {
        val fg = if (on) Palette.BRAND else Palette.FAINT
        background = pressable(round(if (on) Palette.BRAND_SOFT else Palette.CARD_HI, 16, if (on) 0x662EE6C4 else 0, 1f))
        setPadding(dp(10), dp(7), dp(12), dp(7))
        addView(icon(iconRes, fg, 16), lp(dp(16), dp(16)).apply { marginEnd = dp(6) })
        addView(text(label, 13f, if (on) Palette.TEXT else Palette.FAINT, on))
        alpha = if (enabled) 1f else 0.55f
        isClickable = true
        setOnClickListener { onClick() }
    }
}

/** Náhledy klipů z Galerie (malé, v paměti nejvýš ~6 MB, načítají se mimo hlavní vlákno). */
class Thumbs(private val ctx: Context) {
    private val cache = object : LruCache<String, Bitmap>(6 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val missing = HashSet<String>()
    private val main = Handler(Looper.getMainLooper())
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "kine-thumbs").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }

    fun load(uri: String, into: ImageView) {
        into.tag = uri
        val hit = cache.get(uri)
        if (hit != null) {
            into.setImageBitmap(hit)
            return
        }
        into.setImageDrawable(null)
        if (uri in missing || exec.isShutdown) return
        try {
            exec.execute { decode(uri, into) }
        } catch (_: Exception) {
            // appka se zrovna zavírá
        }
    }

    private fun decode(uri: String, into: ImageView) {
        val bmp = try {
            ctx.contentResolver.loadThumbnail(Uri.parse(uri), Size(320, 180), null)
        } catch (_: Exception) {
            null
        }
        main.post {
            if (bmp != null) cache.put(uri, bmp) else missing.add(uri)
            if (into.tag == uri && bmp != null) into.setImageBitmap(bmp)
        }
    }

    fun forget(uri: String) {
        cache.remove(uri)
        missing.remove(uri)
    }

    fun close() {
        exec.shutdownNow()
    }
}
