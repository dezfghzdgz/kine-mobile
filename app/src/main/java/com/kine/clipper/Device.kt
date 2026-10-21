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
    @Volatile private var cachedOlder: Boolean? = null

    private fun ramGb(context: Context): Double = try {
        val mi = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java)?.getMemoryInfo(mi)
        mi.totalMem / 1_073_741_824.0
    } catch (_: Exception) {
        0.0
    }

    /**
     * Třída telefonu: LOW = málo paměti (< 3,5 GB, tj. „3 GB“ a míň), Android Go nebo
     * nejvýš 4 jádra; HIGH = výkonnostní třída médií Androidu 12+ nebo 7+ GB a 8 jader
     * u telefonu, který není starší (viz [older] - pětiletý telefon s 8 GB dřív dostal
     * nastavení jako nový a hřál).
     */
    fun tier(context: Context): Tier {
        cachedTier?.let { return it }
        val t = try {
            val am = context.getSystemService(ActivityManager::class.java)
            val ram = ramGb(context)
            val cores = Runtime.getRuntime().availableProcessors()
            val perf = if (Build.VERSION.SDK_INT >= 31) Build.VERSION.MEDIA_PERFORMANCE_CLASS else 0
            when {
                am?.isLowRamDevice == true || (ram > 0.1 && ram < 3.5) || cores <= 4 -> Tier.LOW
                perf >= 31 || (ram >= 7.0 && cores >= 8 && !older(context)) -> Tier.HIGH
                else -> Tier.MID
            }
        } catch (_: Exception) {
            Tier.MID
        }
        cachedTier = t
        return t
    }

    /**
     * Starší telefon (typicky 4-6 let): Android 11 a starší (telefony, které nedostaly
     * Android 12), nebo bez výkonnostní třídy médií a s pamětí „4 GB“ a míň. V Auto
     * dostane 540p místo 720p - méně práce pro kodér i skládání obrazu, telefon tolik nehřeje.
     */
    fun older(context: Context): Boolean {
        cachedOlder?.let { return it }
        val o = try {
            SizeMath.olderDevice(Build.VERSION.SDK_INT, if (Build.VERSION.SDK_INT >= 31) Build.VERSION.MEDIA_PERFORMANCE_CLASS else 0, ramGb(context))
        } catch (_: Exception) {
            false
        }
        cachedOlder = o
        return o
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
    fun videoPlans(contentW: Int, contentH: Int, short: Int, fps: Int, tier: Tier, eco: Boolean = false): List<VideoPlan> {
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
                val want = SizeMath.bitrate(w, h, f, tier == Tier.LOW, eco)
                val br = try { vc.bitrateRange.clamp(want) } catch (_: Exception) { want }
                out.add(VideoPlan(info.name, w, h, f, br, info.isHardwareAccelerated))
                // Z každého kodéru nejlepší rozměr a jeden menší (kdyby ten větší při spuštění neprošel).
                if (++added >= 2) break
            }
            if (out.size >= 6) break
        }
        val (fw, fh) = SizeMath.fit(contentW, contentH, min(short, 480), 16)
        val ff = min(30, fps)
        out.add(VideoPlan(null, fw, fh, ff, SizeMath.bitrate(fw, fh, ff, true, eco), false))
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
     * (sousední snímky jsou si podobné); 1,5-12 Mb/s. Šetrný režim ([eco]) ~0,10 a od
     * 1,2 Mb/s - kodér má méně práce, telefon tolik nehřeje.
     */
    fun bitrate(w: Int, h: Int, fps: Int, low: Boolean, eco: Boolean = false): Int {
        val bpp = if (eco) 0.10 else if (low) 0.13 else 0.12
        val f = if (fps > 40) fps * 0.75 else fps.toDouble()
        return (w.toDouble() * h * f * bpp).roundToInt().coerceIn(if (eco) 1_200_000 else 1_500_000, 12_000_000)
    }

    /** Starší telefon: Android 11 a starší, nebo bez výkonnostní třídy médií a s pamětí „4 GB“ a míň. */
    fun olderDevice(sdk: Int, mediaPerformanceClass: Int, ramGb: Double): Boolean =
        // „6 GB“ telefon hlásí ~5,4-5,8 GiB - hranice 5 GB, ať nový 6GB telefon není „starší“.
        sdk <= 30 || (mediaPerformanceClass == 0 && ramGb > 0.1 && ramGb < 5.0)

    /** Kratší strana v Auto: slabý telefon 480p, starší 540p, jinak 720p. */
    fun autoShort(low: Boolean, older: Boolean): Int = if (low) 480 else if (older) 540 else 720

    /**
     * Horký telefon: kratší strana podle stupně (0 normální, 1 teplý, 2 horký, 3 velmi
     * horký). Teplý jen ubere datový tok (rozměr stejný), horký o stupeň menší obraz,
     * velmi horký o dva - nejméně 360p.
     */
    fun coolShort(short: Int, level: Int): Int {
        if (level < 2) return short
        val ladder = listOf(1080, 720, 540, 480, 360)
        val idx = ladder.indexOfFirst { it <= short }.let { if (it < 0) ladder.size - 1 else it }
        return ladder[minOf(ladder.size - 1, idx + (level - 1))].coerceAtMost(short)
    }

    /** Na šířku? (pro otočení telefonu během nahrávání) */
    fun landscape(w: Int, h: Int): Boolean = w > h

    /** Snímky za sekundu při horkém telefonu: horký nejvýš 24, velmi horký 20. */
    fun coolFps(fps: Int, level: Int): Int = when {
        level >= 3 -> minOf(fps, 20)
        level >= 2 -> minOf(fps, 24)
        else -> fps
    }

    /** Kolik datového toku zůstane při horkém telefonu (teplý 75 %, velmi horký 60 %). */
    fun coolBitrateFactor(level: Int): Double = when {
        level >= 3 -> 0.6
        level >= 1 -> 0.75
        else -> 1.0
    }
}

/**
 * Jak horký je telefon (čistý výpočet - jde ověřit mimo telefon). Bere stav teploty od
 * systému (PowerManager, Android 10+, ne každý telefon ho hlásí) a teplotu baterie
 * (hlásí každý telefon) - platí vyšší z obou.
 */
object Heat {
    /** Stav systému (PowerManager.THERMAL_STATUS_*) a baterie (°C, null = nevíme) -> stupeň 0-3. */
    fun level(thermalStatus: Int, batteryC: Float?): Int {
        val fromSystem = when {
            thermalStatus >= 4 -> 3 // CRITICAL a víc
            thermalStatus >= 3 -> 2 // SEVERE
            thermalStatus >= 2 -> 1 // MODERATE
            else -> 0
        }
        val fromBattery = when {
            batteryC == null -> 0
            batteryC >= 46f -> 3
            batteryC >= 43f -> 2
            batteryC >= 40f -> 1
            else -> 0
        }
        return maxOf(fromSystem, fromBattery)
    }

    /** Teplota baterie, pod kterou se smí stupeň snížit (o 1,5 °C níž než hranice - žádné cukání). */
    fun coolEnough(level: Int, batteryC: Float?): Boolean {
        if (batteryC == null) return true
        val limit = when (level) {
            3 -> 46f
            2 -> 43f
            1 -> 40f
            else -> return true
        }
        return batteryC < limit - 1.5f
    }

    /** Jak dlouho musí být telefon chladnější, než se kvalita vrátí o stupeň výš. */
    const val COOL_DOWN_MS = 60_000L

    /**
     * Další stupeň: zahřátí platí hned, ochlazení až po [COOL_DOWN_MS] klidu (a jen o jeden
     * stupeň). [lowerSince] = od kdy je naměřeno méně (0 = není). Vrací (stupeň, lowerSince).
     */
    fun next(current: Int, measured: Int, batteryC: Float?, lowerSince: Long, now: Long): Pair<Int, Long> {
        if (measured >= current) return (if (measured > current) measured else current) to 0L
        if (!coolEnough(current, batteryC)) return current to 0L
        val since = if (lowerSince == 0L) now else lowerSince
        return if (now - since >= COOL_DOWN_MS) (current - 1) to 0L else current to since
    }
}
