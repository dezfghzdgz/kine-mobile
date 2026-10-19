package com.kine.clipper

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection

/**
 * Zvuk do klipu: zvuk hry (AudioPlaybackCapture - co hraje telefon, Android
 * 10+; hra to může zakázat, pak je ticho) a mikrofon, smíchané do jedné
 * stereo stopy 48 kHz pro kodér AAC. Hodiny: zvuk hry (čte se blokově),
 * mikrofon se k němu přimíchá, kolik ho zrovna je; bez zvuku hry jsou hodinami
 * mikrofon.
 */
class AudioCapture(private val projection: MediaProjection, private val useGame: Boolean, private val useMic: Boolean, private val encoder: AudioEncoder) {
    private var game: AudioRecord? = null
    private var mic: AudioRecord? = null
    @Volatile private var running = false
    private var thread: Thread? = null
    /** Co se opravdu podařilo otevřít. */
    var gameOn = false
        private set
    var micOn = false
        private set

    companion object {
        const val RATE = 48_000
        const val FRAMES = 1024
    }

    @SuppressLint("MissingPermission")
    fun start() {
        val stereo = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build()
        val stereoMin = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        if (useGame) {
            try {
                val config = AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build()
                val r = AudioRecord.Builder().setAudioPlaybackCaptureConfig(config).setAudioFormat(stereo).setBufferSizeInBytes(maxOf(stereoMin * 4, FRAMES * 4 * 8)).build()
                if (r.state == AudioRecord.STATE_INITIALIZED) {
                    game = r
                    gameOn = true
                } else {
                    r.release()
                    Log.w("zvuk hry: AudioRecord se nepodařilo připravit")
                }
            } catch (e: Exception) {
                Log.e("zvuk hry nejde zachytit", e)
            }
        }
        if (useMic) {
            try {
                val monoMin = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                val r = AudioRecord(MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(monoMin * 4, FRAMES * 2 * 8))
                if (r.state == AudioRecord.STATE_INITIALIZED) {
                    mic = r
                    micOn = true
                } else {
                    r.release()
                    Log.w("mikrofon: AudioRecord se nepodařilo připravit")
                }
            } catch (e: Exception) {
                Log.e("mikrofon nejde otevřít", e)
            }
        }
        if (game == null && mic == null) return
        running = true
        thread = Thread({ loop() }, "kine-audio").also { it.start() }
    }

    private fun loop() {
        val g = game
        val m = mic
        try {
            g?.startRecording()
            m?.startRecording()
        } catch (e: Exception) {
            Log.e("zvuk: start", e)
            running = false
            return
        }
        val stereoBytes = FRAMES * 2 * 2
        val gameBuf = ByteArray(stereoBytes)
        val micMono = ByteArray(FRAMES * 2 * 2)
        val out = ByteArray(stereoBytes)
        var micCarry = 0
        while (running) {
            var n: Int
            if (g != null) {
                n = g.read(gameBuf, 0, stereoBytes)
                if (n <= 0) {
                    if (n < 0) {
                        Log.w("zvuk hry: čtení vrátilo $n")
                        Thread.sleep(20)
                    }
                    continue
                }
            } else {
                // Hodinami je mikrofon (mono) - přečte se blokově tolik snímků, kolik má stereo kousek.
                n = m!!.read(micMono, 0, FRAMES * 2)
                if (n <= 0) {
                    if (n < 0) Thread.sleep(20)
                    continue
                }
                upmix(micMono, n, out)
                encoder.write(out, n * 2, ptsFor(n / 2))
                continue
            }
            // Čas prvního vzorku: teď minus délka kousku (čtení vrací, co už dohrálo).
            val frames = n / 4
            val pts = ptsFor(frames)
            if (m != null) {
                // Mikrofon neblokově: přimíchat, kolik ho je (zbytek čeká na příště).
                val want = frames * 2 - micCarry
                val got = if (want > 0) m.read(micMono, micCarry, want, AudioRecord.READ_NON_BLOCKING) else 0
                val have = micCarry + (if (got > 0) got else 0)
                val micFrames = minOf(have / 2, frames)
                mix(gameBuf, n, micMono, micFrames, out)
                // Co zbylo (mikrofon předběhl), přesunout na začátek.
                val used = micFrames * 2
                micCarry = have - used
                if (micCarry > 0) System.arraycopy(micMono, used, micMono, 0, micCarry)
                encoder.write(out, n, pts)
            } else {
                encoder.write(gameBuf, n, pts)
            }
        }
        try {
            g?.stop()
            m?.stop()
        } catch (_: Exception) {
            // nevadí
        }
        g?.release()
        m?.release()
    }

    private fun ptsFor(frames: Int): Long = System.nanoTime() / 1000L - frames.toLong() * 1_000_000L / RATE

    /** Mono 16 bit -> stereo (oba kanály stejně). */
    private fun upmix(mono: ByteArray, bytes: Int, out: ByteArray) {
        var o = 0
        var i = 0
        while (i + 1 < bytes) {
            out[o] = mono[i]
            out[o + 1] = mono[i + 1]
            out[o + 2] = mono[i]
            out[o + 3] = mono[i + 1]
            o += 4
            i += 2
        }
    }

    /** Stereo hra + mono mikrofon (micFrames snímků) -> stereo, s ořezem špiček. */
    private fun mix(game: ByteArray, gameBytes: Int, micMono: ByteArray, micFrames: Int, out: ByteArray) {
        val frames = gameBytes / 4
        for (f in 0 until frames) {
            val mi = if (f < micFrames) ((micMono[f * 2 + 1].toInt() shl 8) or (micMono[f * 2].toInt() and 0xff)).toShort().toInt() else 0
            for (ch in 0 until 2) {
                val idx = f * 4 + ch * 2
                val gs = ((game[idx + 1].toInt() shl 8) or (game[idx].toInt() and 0xff)).toShort().toInt()
                val v = (gs + mi).coerceIn(-32768, 32767)
                out[idx] = (v and 0xff).toByte()
                out[idx + 1] = ((v shr 8) and 0xff).toByte()
            }
        }
    }

    fun stop() {
        running = false
        thread?.join(1500)
    }
}
