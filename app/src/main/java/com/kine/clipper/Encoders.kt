package com.kine.clipper

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.view.Surface
import java.nio.ByteBuffer

/** Hotové kousky videa / zvuku z kodérů (volá se z vláken kodérů). */
interface EncodedSink {
    fun onVideoFormat(format: MediaFormat)
    fun onVideo(buffer: ByteBuffer, info: MediaCodec.BufferInfo)
    fun onAudioFormat(format: MediaFormat)
    fun onAudio(buffer: ByteBuffer, info: MediaCodec.BufferInfo)
}

/**
 * Kodér obrazu H.264 se vstupem přes Surface (kreslí do něj rovnou VirtualDisplay,
 * nebo GlCompositor, když je v obraze kamera). Klíčový snímek každou sekundu (kvůli
 * krájení na kousky), opakování posledního snímku, když se nic nehýbe, a strop
 * snímků za sekundu (displej 90/120 Hz by jinak kódoval až 120 snímků).
 * Otevírá se přes [open] - zkouší možnosti od nejlepší, dokud jedna neprojde.
 */
class VideoEncoder private constructor(
    private val codec: MediaCodec,
    val plan: Device.VideoPlan,
    private val sink: EncodedSink,
    private val onFatal: (Exception) -> Unit,
) {
    val width: Int get() = plan.width
    val height: Int get() = plan.height
    val name: String = try { codec.name } catch (_: Exception) { plan.codec ?: "?" }
    val inputSurface: Surface = codec.createInputSurface()
    @Volatile private var running = false
    @Volatile private var stopping = false
    private var thread: Thread? = null

    private fun start() {
        codec.start()
        running = true
        thread = Thread({ drain() }, "kine-venc").also { it.start() }
    }

    /** Další snímek bude klíčový (začátek nového kousku / klip hned). */
    fun requestKeyFrame() {
        if (!running || stopping) return
        try {
            codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
        } catch (e: Exception) {
            Log.w("kodér: klíčový snímek nejde vyžádat: ${e.message}")
        }
    }

    /** Změna datového toku za běhu (horký telefon -> méně). Některé kodéry to ignorují. */
    fun setBitrate(bps: Int) {
        if (!running || stopping) return
        try {
            codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bps) })
        } catch (e: Exception) {
            Log.w("kodér: datový tok nejde změnit: ${e.message}")
        }
    }

    private fun drain() {
        val info = MediaCodec.BufferInfo()
        try {
            while (running) {
                val index = codec.dequeueOutputBuffer(info, 20_000)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    sink.onVideoFormat(codec.outputFormat)
                    continue
                }
                if (index < 0) continue
                val buffer = codec.getOutputBuffer(index)
                if (buffer != null && info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                    buffer.position(info.offset)
                    buffer.limit(info.offset + info.size)
                    sink.onVideo(buffer, info)
                }
                codec.releaseOutputBuffer(index, false)
                if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
            }
        } catch (e: Exception) {
            // Kodér spadl za běhu (např. si ho systém vzal pro jinou appku) - služba ho zkusí obnovit.
            if (running && !stopping) {
                Log.e("kodér obrazu spadl", e)
                running = false
                onFatal(e)
            }
        }
    }

    fun stop() {
        if (stopping) return
        stopping = true
        try {
            codec.signalEndOfInputStream()
        } catch (_: Exception) {
            // kodér už neběží
        }
        thread?.join(700)
        running = false
        thread?.join(400)
        try { codec.stop() } catch (_: Exception) { }
        try { codec.release() } catch (_: Exception) { }
        try { inputSurface.release() } catch (_: Exception) { }
    }

    companion object {
        private const val AVC = MediaFormat.MIMETYPE_VIDEO_AVC

        /**
         * Zkusí možnosti od nejlepší; vrátí první, která se dá nastavit i spustit (běžící kodér).
         * Starší telefony často selžou až při spuštění (málo prostředků) - pak menší / softwarový.
         */
        fun open(plans: List<Device.VideoPlan>, sink: EncodedSink, onFatal: (Exception) -> Unit): VideoEncoder {
            var last: Exception? = null
            for (p in plans) {
                var codec: MediaCodec? = null
                try {
                    val c = if (p.codec != null) MediaCodec.createByCodecName(p.codec) else MediaCodec.createEncoderByType(AVC)
                    codec = c
                    var full = true
                    try {
                        c.configure(format(p, true), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    } catch (e: Exception) {
                        // Některé kodéry nevezmou VBR / opakování snímku / strop snímků - zkusit základ.
                        Log.w("kodér ${p.codec}: základní nastavení (${e.message})")
                        c.reset()
                        c.configure(format(p, false), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                        full = false
                    }
                    val enc = VideoEncoder(c, p, sink, onFatal)
                    try {
                        enc.start()
                    } catch (e: Exception) {
                        try { enc.inputSurface.release() } catch (_: Exception) { }
                        throw e
                    }
                    Log.i("kodér obrazu: ${enc.name} ${p.width}x${p.height} @ ${p.fps} fps, ${p.bitrate / 1000} kb/s" + if (full) "" else " (základ)")
                    return enc
                } catch (e: Exception) {
                    last = e
                    Log.w("kodér ${p.codec ?: "výchozí"} ${p.width}x${p.height}@${p.fps} nejde: ${e.message}")
                    try { codec?.release() } catch (_: Exception) { }
                }
            }
            throw last ?: IllegalStateException("no H.264 encoder")
        }

        private fun format(p: Device.VideoPlan, full: Boolean): MediaFormat = MediaFormat.createVideoFormat(AVC, p.width, p.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, p.bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, p.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            // Bez B-snímků: časy jdou po sobě (klip se lepí bez překódování), kodér to má lehčí.
            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            if (full) {
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000L)
                setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, p.fps.toFloat())
                setInteger(MediaFormat.KEY_PRIORITY, 0)
            }
        }
    }
}

/**
 * Kodér zvuku AAC (48 nebo 44,1 kHz podle toho, co umí mikrofon; stereo). PCM 16 bit mu
 * dává AudioCapture i s časem prvního vzorku (µs, stejné hodiny jako obraz - System.nanoTime).
 */
class AudioEncoder(val sampleRate: Int, val channels: Int, bitrate: Int, private val sink: EncodedSink) {
    private val codec: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    private val info = MediaCodec.BufferInfo()
    @Volatile private var started = false
    private var dropped = 0

    init {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
        }
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (e: Exception) {
            codec.release()
            throw e
        }
    }

    fun start() {
        codec.start()
        started = true
    }

    /** PCM 16 bit (prokládané kanály), `ptsUs` = čas prvního vzorku. Volá se z jednoho vlákna (AudioCapture). */
    fun write(pcm: ByteArray, length: Int, ptsUs: Long) {
        if (!started) return
        try {
            var offset = 0
            while (offset < length && started) {
                val index = codec.dequeueInputBuffer(10_000)
                if (index < 0) {
                    dropped++
                    if (dropped == 1 || dropped % 100 == 0) Log.w("kodér zvuku nestíhá - vynecháno $dropped kousků")
                    return
                }
                val input = codec.getInputBuffer(index) ?: return
                input.clear()
                val n = minOf(input.remaining(), length - offset)
                input.put(pcm, offset, n)
                val framePts = ptsUs + (offset.toLong() * 1_000_000L) / (sampleRate.toLong() * channels * 2)
                codec.queueInputBuffer(index, 0, n, framePts, 0)
                offset += n
                drain()
            }
        } catch (e: IllegalStateException) {
            // Kodér se zrovna zastavuje (konec snímání) - zbytek zvuku zahodit.
            if (started) Log.w("kodér zvuku: ${e.message}")
        }
    }

    private fun drain() {
        while (true) {
            val index = codec.dequeueOutputBuffer(info, 0)
            when {
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> sink.onAudioFormat(codec.outputFormat)
                index >= 0 -> {
                    val buffer = codec.getOutputBuffer(index)
                    if (buffer != null && info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        sink.onAudio(buffer, info)
                    }
                    codec.releaseOutputBuffer(index, false)
                }
                else -> return
            }
        }
    }

    fun stop() {
        if (!started) {
            try { codec.release() } catch (_: Exception) { }
            return
        }
        started = false
        try { codec.stop() } catch (_: Exception) { }
        try { codec.release() } catch (_: Exception) { }
    }
}
