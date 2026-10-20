package com.kine.clipper

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/**
 * Hotový kousek záznamu: soubor MP4 (obraz + zvuk). Časy v µs (hodiny System.nanoTime):
 * [startUs] = první (klíčový) snímek obrazu, [endUs] = začátek dalšího kousku. MP4 má
 * časy od nuly - ClipMaker je podle prvního snímku přepočítá zpátky na společné hodiny.
 * [epoch] se zvedne, když se kodér vymění (otočení telefonu = jiný rozměr obrazu) -
 * klip se lepí jen z kousků se stejným rozměrem.
 */
data class Segment(val file: File, val startUs: Long, val endUs: Long, val epoch: Int) {
    val durationUs: Long get() = endUs - startUs
}

/**
 * Zásobník posledních sekund: kodéry sypou kousky do MediaMuxeru, který se každé ~4 s
 * (na klíčovém snímku) vymění za nový soubor. Starší soubory, než je potřeba, se mažou.
 * Klip se dělá z hotových souborů (ClipMaker) - proto se při žádosti o klip kousek hned
 * uzavře ([requestClip]).
 *
 * Každý soubor začíná klíčovým snímkem obrazu (první vzorek - časy pak sedí na všech
 * verzích Androidu). Zvuk, který dorazí po přepnutí, ale patří ještě před ten klíčový
 * snímek, jde do předchozího souboru; ten se zavře, až zvuk čas přepnutí dožene (pár
 * desítek ms) - na švech kousků tak nejsou díry ve zvuku ani přeskoky v obraze.
 */
class SegmentWriter(private val dir: File, private val keepUs: Long, private val withAudio: Boolean, private val requestKeyFrame: () -> Unit) : EncodedSink {
    /** Otevřený soubor, do kterého se zapisuje. */
    private class Part(val muxer: MediaMuxer, val file: File, val videoTrack: Int, val audioTrack: Int, val startUs: Long, val epoch: Int) {
        var lastVideoUs = startUs
        var lastAudioUs = -1L
        var endUs = startUs
    }

    private val lock = Any()
    private var videoFormat: MediaFormat? = null
    private var audioFormat: MediaFormat? = null
    private var cur: Part? = null
    /** Předchozí soubor čeká na zvuk do času přepnutí ([Part.endUs]). */
    private var prev: Part? = null
    private var wantRotate = false
    private var keyFrameAsked = 0L
    private var waitAudioSinceUs = -1L
    private var warnedNoAudio = false
    private val segments = ArrayList<Segment>()
    private var counter = 0
    private var epoch = 0
    private var pendingClip: ((List<Segment>) -> Unit)? = null

    val segmentUs = 4_000_000L

    init {
        dir.mkdirs()
        for (f in dir.listFiles() ?: emptyArray()) f.delete()
    }

    /**
     * Klip: uzavřít rozdělaný kousek co nejdřív (klíčový snímek přijde do sekundy) a pak
     * zavolat [onReady] s hotovými kousky (na vlastním vlákně). False = klip už se dělá.
     */
    fun requestClip(onReady: (List<Segment>) -> Unit): Boolean {
        synchronized(lock) {
            if (pendingClip != null) return false
            pendingClip = onReady
            wantRotate = true
            keyFrameAsked = System.nanoTime()
        }
        requestKeyFrame()
        return true
    }

    /** Klip čeká moc dlouho na klíčový snímek (kodér stojí, obraz se nehýbe) - vzít, co je. */
    fun forcePendingClip() {
        synchronized(lock) {
            if (pendingClip == null) return
            closeAll()
            deliverPending()
        }
    }

    /** Kodér se mění (jiný rozměr): uzavřít kousky, další jsou nová „epocha“. */
    fun newEpoch() {
        synchronized(lock) {
            closeAll()
            videoFormat = null
            epoch++
            deliverPending()
        }
    }

    override fun onVideoFormat(format: MediaFormat) {
        synchronized(lock) {
            // Formát se změnil uprostřed kousku (kodér se sám restartoval) - kousky uzavřít.
            closeAll()
            videoFormat = format
            deliverPending()
        }
    }

    override fun onAudioFormat(format: MediaFormat) {
        synchronized(lock) { audioFormat = format }
    }

    override fun onVideo(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        synchronized(lock) {
            val t = info.presentationTimeUs
            val key = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
            // Zvuk se zasekl - předchozí soubor nečeká věčně (nejvýš půl sekundy obrazu).
            val p = prev
            if (p != null && t - p.endUs > 500_000L) finishPrev()
            var c = cur
            if (c == null) {
                // Kousek začíná klíčovým snímkem, až je známý formát obrazu (a zvuku, když se nahrává).
                if (!key || videoFormat == null) return
                var audioNow = withAudio && audioFormat != null
                if (withAudio && audioFormat == null) {
                    // Zvuk ještě nepřišel - chvíli počkat, pak radši kousek bez zvuku než žádný obraz.
                    if (waitAudioSinceUs < 0) waitAudioSinceUs = t
                    if (t - waitAudioSinceUs < 2_000_000L) return
                    if (!warnedNoAudio) {
                        warnedNoAudio = true
                        Log.w("zvuk nepřichází - kousky zatím bez zvuku")
                    }
                    audioNow = false
                }
                c = open(t, audioNow) ?: return
                cur = c
            } else if (key && (wantRotate || t - c.startUs >= segmentUs)) {
                finishPrev()
                c.endUs = t
                val next = open(t, withAudio && audioFormat != null)
                if (next == null) {
                    // Nový soubor nejde (plná paměť?) - aspoň ten rozdělaný uzavřít, ať jde klip.
                    cur = null
                    finish(c)
                    deliverPending()
                    return
                }
                cur = next
                if (c.audioTrack >= 0) prev = c else {
                    finish(c)
                    deliverPending()
                }
                c = next
            } else if (!wantRotate && t - c.startUs >= segmentUs - 300_000 && System.nanoTime() - keyFrameAsked > 500_000_000L) {
                // Kousek se blíží ke konci - vyžádat klíčový snímek, ať hranice sedí.
                keyFrameAsked = System.nanoTime()
                requestKeyFrame()
            }
            try {
                c.muxer.writeSampleData(c.videoTrack, buffer, info)
                c.lastVideoUs = t
            } catch (e: Exception) {
                Log.e("zápis obrazu", e)
            }
        }
    }

    override fun onAudio(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        synchronized(lock) {
            val t = info.presentationTimeUs
            val p = prev
            if (p != null) {
                // Zvuk z doby před přepnutím patří ještě do předchozího souboru.
                if (t < p.endUs) {
                    writeAudio(p, buffer, info)
                    return
                }
                finishPrev()
            }
            val c = cur ?: return
            // Zvuk před prvním snímkem souboru (hned po startu / výměně kodéru) se zahodí.
            if (t < c.startUs) return
            writeAudio(c, buffer, info)
        }
    }

    private fun writeAudio(part: Part, buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (part.audioTrack < 0) return
        // MediaMuxer chce rostoucí časy v každé stopě.
        if (info.presentationTimeUs <= part.lastAudioUs) info.presentationTimeUs = part.lastAudioUs + 1
        try {
            part.muxer.writeSampleData(part.audioTrack, buffer, info)
            part.lastAudioUs = info.presentationTimeUs
        } catch (e: Exception) {
            Log.e("zápis zvuku", e)
        }
    }

    private fun open(startUs: Long, audio: Boolean): Part? {
        val f = File(dir, "seg-%06d.mp4".format(counter++))
        var m: MediaMuxer? = null
        return try {
            val mux = MediaMuxer(f.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            m = mux
            val vt = mux.addTrack(videoFormat!!)
            val at = if (audio) mux.addTrack(audioFormat!!) else -1
            mux.start()
            wantRotate = false
            Part(mux, f, vt, at, startUs, epoch)
        } catch (e: Exception) {
            Log.e("nový kousek záznamu nejde otevřít", e)
            try { m?.release() } catch (_: Exception) { }
            f.delete()
            null
        }
    }

    /** Uzavřít soubor a přidat ho do zásobníku (konec = [Part.endUs], u rozdělaného poslední snímek). */
    private fun finish(part: Part) {
        val endUs = if (part.endUs > part.startUs) part.endUs else part.lastVideoUs
        var ok = true
        try {
            part.muxer.stop()
        } catch (e: Exception) {
            // Kousek bez jediného vzorku některé stopy (hned po startu) - soubor by byl rozbitý, zahodit.
            Log.w("kousek záznamu se nezavřel čistě: ${e.message}")
            ok = false
        }
        try { part.muxer.release() } catch (_: Exception) { }
        if (ok && part.file.length() > 0 && endUs > part.startUs) {
            segments.add(Segment(part.file, part.startUs, endUs, part.epoch))
            prune()
        } else part.file.delete()
    }

    private fun finishPrev() {
        val p = prev ?: return
        prev = null
        finish(p)
        deliverPending()
    }

    private fun closeAll() {
        finishPrev()
        val c = cur ?: return
        cur = null
        c.endUs = c.lastVideoUs
        finish(c)
    }

    /** Čeká klip? Dát mu hotové kousky (na vlastním vlákně - lepení chvíli trvá). */
    private fun deliverPending() {
        val cb = pendingClip ?: return
        // Přepnutí na klip ještě neproběhlo (čeká se na klíčový snímek) - počkat.
        if (wantRotate && cur != null) return
        pendingClip = null
        val list = ArrayList(segments)
        Thread({ cb(list) }, "kine-clip").start()
    }

    /** Smazat kousky, které už klip nemůže potřebovat (zásobník = délka klipu + rezerva). */
    private fun prune() {
        var total = 0L
        for (s in segments) total += s.durationUs
        while (segments.size > 1 && total - segments.first().durationUs >= keepUs) {
            val old = segments.removeAt(0)
            total -= old.durationUs
            old.file.delete()
        }
    }

    fun stop() {
        synchronized(lock) {
            pendingClip = null
            for (part in listOfNotNull(prev, cur)) {
                try { part.muxer.stop() } catch (_: Exception) { }
                try { part.muxer.release() } catch (_: Exception) { }
                part.file.delete()
            }
            prev = null
            cur = null
            for (s in segments) s.file.delete()
            segments.clear()
            dir.delete()
        }
    }
}
