package com.kine.clipper

import android.app.ActivityManager
import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Telefon a jeho kodér. Starší / slabší telefony dostanou v „Auto“ menší obraz (480p),
 * ať hra ani telefon nesekají; rozměr, snímky a datový tok se vždycky ověří proti tomu,
 * co kodér H.264 opravdu umí (meze, zarovnání na 16 px, výkon) - když první volba
 * neprojde, zkusí se menší a nakonec softwarový kodér.
 */
object Device {
    enum class Tier { LOW, MID, HIGH }

    /** Jedna možnost pro kodér obrazu (od nejlepší). [codec] null = výchozí kodér systému. */
    data class VideoPlan(val codec: String?, val width: Int, val height: Int, val fps: Int, val bitrate: Int, val hardware: Boolean)

    private const val AVC = MediaFormat.MIMETYPE_VIDEO_AVC

    @Volatile private var cachedTier: Tier? = null

    /**
     * Třída telefonu: LOW = málo paměti (< 3,5 GB, tj. „3 GB“ a míň), Android Go nebo
     * nejvýš 4 jádra; HIGH = výkonnostní třída médií Androidu 12+ nebo 7+ GB a 8 jader.
     */
    fun tier(context: Context): Tier {
        cachedTier?.let { return it }
        val t = try {
            val am = context.getSystemService(ActivityManager::class.java)
            val mi = ActivityManager.MemoryInfo()
            am?.getMemoryInfo(mi)
            val ramGb = mi.totalMem / 1_073_741_824.0
            val cores = Runtime.getRuntime().availableProcessors()
            val perf = if (Build.VERSION.SDK_INT >= 31) Build.VERSION.MEDIA_PERFORMANCE_CLASS else 0
            when {
                am?.isLowRamDevice == true || (ramGb > 0.1 && ramGb < 3.5) || cores <= 4 -> Tier.LOW
                perf >= 31 || (ramGb >= 7.0 && cores >= 8) -> Tier.HIGH
                else -> Tier.MID
            }
        } catch (_: Exception) {
            Tier.MID
        }
        cachedTier = t
        return t
    }

    fun isLowRam(context: Context): Boolean = try {
        context.getSystemService(ActivityManager::class.java)?.isLowRamDevice == true
    } catch (_: Exception) {
        false
    }

    /** Krátký popis telefonu do deníku (po problému je vidět, na čem to běželo). */
    fun describe(context: Context): String {
        val am = context.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo()
        try { am?.getMemoryInfo(mi) } catch (_: Exception) { }
        val ram = "%.1f".format(mi.totalMem / 1_073_741_824.0)
        return "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), RAM $ram GB, " +
            "${Runtime.getRuntime().availableProcessors()} jader, třída ${tier(context)}"
    }

    /** Kodéry H.264 se vstupem přes Surface - hardwarové napřed, softwarové až na konec. */
    private fun encoders(): List<MediaCodecInfo> = try {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { info -> info.isEncoder && info.supportedTypes.any { it.equals(AVC, ignoreCase = true) } }
            .sortedBy { if (it.isHardwareAccelerated) 0 else if (it.isSoftwareOnly) 2 else 1 }
    } catch (e: Exception) {
        Log.w("kodéry: seznam nejde načíst (${e.message})")
        emptyList()
    }

    /**
     * Možnosti pro kodér od nejlepší: kratší strana [short] (pak menší stupně), poměr
     * stran jako obsah obrazovky, jen co kodér umí. Na konci vždycky výchozí kodér
     * v 480p / 30 fps (poslední záchrana).
     */
    fun videoPlans(contentW: Int, contentH: Int, short: Int, fps: Int, tier: Tier): List<VideoPlan> {
        val out = ArrayList<VideoPlan>()
        val steps = SizeMath.steps(short)
        for (info in encoders()) {
            val caps = try { info.getCapabilitiesForType(AVC) } catch (_: Exception) { null } ?: continue
            if (caps.colorFormats?.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface) != true) continue
            val vc = caps.videoCapabilities ?: continue
            var added = 0
            for (s in steps) {
                val (w, h) = SizeMath.fit(contentW, contentH, s, 16)
                val ok = try { vc.isSizeSupported(w, h) } catch (_: Exception) { false }
                if (!ok) continue
                val f = pickFps(vc, w, h, fps) ?: continue
                val br = try { vc.bitrateRange.clamp(SizeMath.bitrate(w, h, f, tier == Tier.LOW)) } catch (_: Exception) { SizeMath.bitrate(w, h, f, tier == Tier.LOW) }
                out.add(VideoPlan(info.name, w, h, f, br, info.isHardwareAccelerated))
                // Z každého kodéru nejlepší rozměr a jeden menší (kdyby ten větší při spuštění neprošel).
                if (++added >= 2) break
            }
            if (out.size >= 6) break
        }
        val (fw, fh) = SizeMath.fit(contentW, contentH, min(short, 480), 16)
        out.add(VideoPlan(null, fw, fh, 30, SizeMath.bitrate(fw, fh, 30, true), false))
        // Hardwarové nejdřív (i menší rozměr na hardwaru je lepší než větší v softwaru).
        return out.sortedWith(compareBy<VideoPlan> { if (it.hardware) 0 else 1 }.thenByDescending { min(it.width, it.height) }).distinct()
    }

    /** Snímky za sekundu, které kodér v tomhle rozměru zvládne (60 -> 30 -> 24). */
    private fun pickFps(vc: MediaCodecInfo.VideoCapabilities, w: Int, h: Int, want: Int): Int? {
        for (f in listOf(want, 30, 24).filter { it <= max(want, 24) }.distinct()) {
            val ok = try { vc.areSizeAndRateSupported(w, h, f.toDouble()) } catch (_: Exception) { false }
            if (!ok) continue
            // Výkon - jen když ho kodér hlásí (PerformancePoint pokrývá rozměr i snímky).
            val points = try { vc.supportedPerformancePoints } catch (_: Exception) { null }
            if (!points.isNullOrEmpty()) {
                val need = MediaCodecInfo.VideoCapabilities.PerformancePoint(w, h, f)
                if (points.none { it.covers(need) }) continue
            }
            return f
        }
        return null
    }
}

/** Čistý výpočet rozměrů a datového toku (bez Androidu - jde ověřit i mimo telefon). */
object SizeMath {
    /** Stupně kratší strany od [short] dolů (720 -> 540 -> 480 -> 360). */
    fun steps(short: Int): List<Int> = (listOf(short) + listOf(1080, 720, 540, 480, 360).filter { it < short }).distinct()

    /**
     * Rozměr s kratší stranou nejvýš [short] (nikdy víc než obsah - nezvětšuje se),
     * poměr stran jako obsah, obě strany zarovnané dolů na [align] px.
     */
    fun fit(contentW: Int, contentH: Int, short: Int, align: Int): Pair<Int, Int> {
        val cw = max(2, contentW)
        val ch = max(2, contentH)
        val cShort = min(cw, ch)
        val s = min(short, cShort)
        val scale = s.toDouble() / cShort
        var w = (cw * scale).roundToInt()
        var h = (ch * scale).roundToInt()
        w = max(align, w - w % align)
        h = max(align, h - h % align)
        return w to h
    }

    /**
     * Datový tok: ~0,12 bitu na pixel a snímek (hry se hodně hýbou), 60 fps jen ~1,5×
     * (sousední snímky jsou si podobné); 1,5-12 Mb/s.
     */
    fun bitrate(w: Int, h: Int, fps: Int, low: Boolean): Int {
        val bpp = if (low) 0.13 else 0.12
        val f = if (fps > 40) fps * 0.75 else fps.toDouble()
        return (w.toDouble() * h * f * bpp).roundToInt().coerceIn(1_500_000, 12_000_000)
    }

    /** Na šířku? (pro otočení telefonu během nahrávání) */
    fun landscape(w: Int, h: Int): Boolean = w > h
}
