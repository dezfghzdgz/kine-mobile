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
 * Kodér obrazu H.264 se vstupem přes Surface (kreslí do něj GlCompositor).
 * Klíčový snímek každou sekundu (kvůli krájení na kousky) a opakování
 * posledního snímku, když se na obrazovce nic nehýbe (jinak by kodér nic
 * nevydával a klip by měl díry).
 */
class VideoEncoder(val width: Int, val height: Int, private val fps: Int, bitrate: Int, private val sink: EncodedSink) {
    private val codec: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    val inputSurface: Surface
    @Volatile private var running = false
    private var thread: Thread? = null

    init {
        fun format(extras: Boolean) = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            if (extras) {
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000L)
            }
        }
        try {
            codec.configure(format(true), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (e: Exception) {
            // Některé kodéry nevezmou VBR / opakování snímku - zkusit základ.
            Log.w("kodér obrazu: základní nastavení (${e.message})")
            codec.reset()
            codec.configure(format(false), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        inputSurface = codec.createInputSurface()
    }

    fun start() {
        codec.start()
        running = true
        thread = Thread({ drain() }, "kine-venc").also { it.start() }
    }

    /** Další snímek bude klíčový (začátek nového kousku / klip hned). */
    fun requestKeyFrame() {
        try {
            codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
        } catch (e: Exception) {
            Log.w("kodér: klíčový snímek nejde vyžádat: ${e.message}")
        }
    }

    private fun drain() {
        val info = MediaCodec.BufferInfo()
        while (running) {
            val index = try {
                codec.dequeueOutputBuffer(info, 20_000)
            } catch (e: Exception) {
                Log.e("kodér obrazu spadl", e)
                break
            }
            when {
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> sink.onVideoFormat(codec.outputFormat)
                index >= 0 -> {
                    val buffer = codec.getOutputBuffer(index)
                    if (buffer != null && info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        sink.onVideo(buffer, info)
                    }
                    codec.releaseOutputBuffer(index, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                }
            }
        }
    }

    fun stop() {
        running = false
        try {
            codec.signalEndOfInputStream()
        } catch (_: Exception) {
            // kodér už neběží
        }
        thread?.join(1500)
        try {
            codec.stop()
        } catch (_: Exception) {
            // nevadí
        }
        codec.release()
        inputSurface.release()
    }
}

/**
 * Kodér zvuku AAC (48 kHz, stereo). PCM 16 bit mu dává AudioCapture i s časem
 * prvního vzorku (µs, stejné hodiny jako obraz - System.nanoTime).
 */
class AudioEncoder(val sampleRate: Int, val channels: Int, bitrate: Int, private val sink: EncodedSink) {
    private val codec: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    private val info = MediaCodec.BufferInfo()
    private var started = false
    private var dropped = 0

    init {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
    }

    fun start() {
        codec.start()
        started = true
    }

    /** PCM 16 bit (prokládané kanály), `ptsUs` = čas prvního vzorku. Volá se z jednoho vlákna (AudioCapture). */
    fun write(pcm: ByteArray, length: Int, ptsUs: Long) {
        if (!started) return
        var offset = 0
        while (offset < length) {
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
        if (!started) return
        started = false
        try {
            codec.stop()
        } catch (_: Exception) {
            // nevadí
        }
        codec.release()
    }
}
