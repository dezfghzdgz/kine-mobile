package com.kine.clipper

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/** Hotový kousek záznamu: soubor MP4 (obraz + zvuk) a od kdy do kdy je (µs, hodiny System.nanoTime). */
data class Segment(val file: File, val startUs: Long, val endUs: Long) {
    val durationUs: Long get() = endUs - startUs
}

/**
 * Zásobník posledních sekund: kodéry sypou kousky do MediaMuxeru, který se
 * každých pár sekund (na klíčovém snímku) vymění za nový soubor. Starší
 * soubory než je potřeba se mažou. Klip se dělá z hotových souborů
 * (ClipMaker) - proto se při žádosti o klip kousek hned uzavře.
 */
class SegmentWriter(private val dir: File, private val keepUs: Long, private val withAudio: Boolean, private val requestKeyFrame: () -> Unit) : EncodedSink {
    private val lock = Any()
    private var videoFormat: MediaFormat? = null
    private var audioFormat: MediaFormat? = null
    private var muxer: MediaMuxer? = null
    private var file: File? = null
    private var videoTrack = -1
    private var audioTrack = -1
    private var segmentStartUs = 0L
    private var lastVideoUs = 0L
    private var lastAudioUs = -1L
    private var wantRotate = false
    private var keyFrameAsked = 0L
    private val segments = ArrayList<Segment>()
    private var counter = 0
    /** Po uzavření kousku (klip čeká na hotový soubor). */
    @Volatile var onSegmentClosed: ((Segment) -> Unit)? = null

    val segmentUs = 4_000_000L

    init {
        dir.mkdirs()
        for (f in dir.listFiles() ?: emptyArray()) f.delete()
    }

    fun snapshot(): List<Segment> = synchronized(lock) { ArrayList(segments) }

    /** Uzavřít rozdělaný kousek co nejdřív (klíčový snímek přijde do sekundy) - pro klip. */
    fun closeSoon() {
        synchronized(lock) {
            wantRotate = true
            keyFrameAsked = System.nanoTime()
        }
        requestKeyFrame()
    }

    override fun onVideoFormat(format: MediaFormat) {
        synchronized(lock) { videoFormat = format }
    }

    override fun onAudioFormat(format: MediaFormat) {
        synchronized(lock) { audioFormat = format }
    }

    private fun ready(): Boolean = videoFormat != null && (!withAudio || audioFormat != null)

    override fun onVideo(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        synchronized(lock) {
            val key = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
            if (muxer == null) {
                // První soubor začíná klíčovým snímkem, až jsou známé oba formáty.
                if (!key || !ready()) return
                open(info.presentationTimeUs)
            } else if (key && (wantRotate || info.presentationTimeUs - segmentStartUs >= segmentUs)) {
                close(info.presentationTimeUs)
                open(info.presentationTimeUs)
            } else if (!wantRotate && info.presentationTimeUs - segmentStartUs >= segmentUs - 300_000 && System.nanoTime() - keyFrameAsked > 500_000_000L) {
                // Kousek se blíží ke konci - vyžádat klíčový snímek, ať hranice sedí.
                keyFrameAsked = System.nanoTime()
                requestKeyFrame()
            }
            val m = muxer ?: return
            try {
                m.writeSampleData(videoTrack, buffer, info)
                lastVideoUs = info.presentationTimeUs
            } catch (e: Exception) {
                Log.e("zápis obrazu", e)
            }
        }
    }

    override fun onAudio(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        synchronized(lock) {
            val m = muxer ?: return
            if (audioTrack < 0) return
            // MediaMuxer chce rostoucí časy v každé stopě.
            if (info.presentationTimeUs <= lastAudioUs) info.presentationTimeUs = lastAudioUs + 1
            try {
                m.writeSampleData(audioTrack, buffer, info)
                lastAudioUs = info.presentationTimeUs
            } catch (e: Exception) {
                Log.e("zápis zvuku", e)
            }
        }
    }

    private fun open(startUs: Long) {
        val f = File(dir, "seg-%06d.mp4".format(counter++))
        try {
            val m = MediaMuxer(f.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            videoTrack = m.addTrack(videoFormat!!)
            audioTrack = if (withAudio && audioFormat != null) m.addTrack(audioFormat!!) else -1
            m.start()
            muxer = m
            file = f
            segmentStartUs = startUs
            lastVideoUs = startUs
            lastAudioUs = -1L
            wantRotate = false
        } catch (e: Exception) {
            Log.e("nový kousek záznamu nejde otevřít", e)
            muxer = null
            file = null
        }
    }

    private fun close(endUs: Long) {
        val m = muxer ?: return
        val f = file
        muxer = null
        var ok = true
        try {
            m.stop()
        } catch (e: Exception) {
            // Kousek bez jediného vzorku některé stopy (hned po startu) - soubor by byl rozbitý, zahodit.
            Log.w("kousek záznamu se nezavřel čistě: ${e.message}")
            ok = false
        }
        m.release()
        if (ok && f != null && f.length() > 0) {
            val seg = Segment(f, segmentStartUs, endUs)
            segments.add(seg)
            prune()
            val cb = onSegmentClosed
            if (cb != null) Thread({ cb(seg) }, "kine-seg").start()
        } else f?.delete()
    }

    /** Smazat kousky, které už klip nemůže potřebovat (zásobník = délka klipu + rezerva). */
    private fun prune() {
        var total = 0L
        for (s in segments.asReversed()) total += s.durationUs
        while (segments.size > 1 && total - segments.first().durationUs >= keepUs) {
            val old = segments.removeAt(0)
            total -= old.durationUs
            old.file.delete()
        }
    }

    fun stop() {
        synchronized(lock) {
            close(lastVideoUs)
            for (s in segments) s.file.delete()
            segments.clear()
        }
    }
}
