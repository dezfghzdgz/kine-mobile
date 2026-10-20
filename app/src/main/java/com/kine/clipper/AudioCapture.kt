package com.kine.clipper

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection

/**
 * Zvuk do klipu: zvuk hry (AudioPlaybackCapture - co hraje telefon; hra to může
 * zakázat, pak je ticho) a mikrofon, smíchané do jedné stereo stopy pro kodér AAC.
 * Vzorkování 48 kHz, a když ho mikrofon neumí (starší telefony), 44,1 kHz pro obojí.
 * Hodiny: zvuk hry (čte se blokově), mikrofon se k němu přimíchá, kolik ho zrovna je;
 * bez zvuku hry jsou hodinami mikrofon. Chyba zvuku nikdy neshodí nahrávání obrazu.
 */
class AudioCapture(private val projection: MediaProjection, private val useGame: Boolean, private val useMic: Boolean) {
    private var game: AudioRecord? = null
    private var mic: AudioRecord? = null
    @Volatile private var running = false
    private var thread: Thread? = null
    private var encoder: AudioEncoder? = null

    /** Vzorkování, na kterém se zdroje opravdu otevřely. */
    var rate = 48_000
        private set
    /** Co se opravdu podařilo otevřít. */
    var gameOn = false
        private set
    var micOn = false
        private set

    companion object {
        const val FRAMES = 1024
    }

    /** Otevře zdroje (48 kHz, jinak 44,1 kHz). False = žádný zvuk nejde. */
    fun open(): Boolean {
        for (r in intArrayOf(48_000, 44_100)) {
            val ok = tryOpen(r)
            // Mikrofon chtěný, ale v 48 kHz nejde - zkusit všechno v 44,1 kHz.
            if (ok && (!useMic || mic != null || r == 44_100)) {
                rate = r
                return true
            }
            releaseAll()
        }
        return false
    }

    @SuppressLint("MissingPermission")
    private fun tryOpen(r: Int): Boolean {
        val stereo = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(r).setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build()
        if (useGame) {
            try {
                val stereoMin = AudioRecord.getMinBufferSize(r, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
                val config = AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build()
                val rec = AudioRecord.Builder().setAudioPlaybackCaptureConfig(config).setAudioFormat(stereo)
                    .setBufferSizeInBytes(maxOf(stereoMin * 2, FRAMES * 4 * 8)).build()
                if (rec.state == AudioRecord.STATE_INITIALIZED) {
                    game = rec
                    gameOn = true
                } else {
                    rec.release()
                    Log.w("zvuk hry ($r Hz): AudioRecord se nepodařilo připravit")
                }
            } catch (e: Exception) {
                Log.e("zvuk hry nejde zachytit ($r Hz)", e)
            }
        }
        if (useMic) {
            try {
                val monoMin = AudioRecord.getMinBufferSize(r, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                if (monoMin > 0) {
                    val rec = AudioRecord(MediaRecorder.AudioSource.MIC, r, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(monoMin * 2, FRAMES * 2 * 8))
                    if (rec.state == AudioRecord.STATE_INITIALIZED) {
                        mic = rec
                        micOn = true
                    } else {
                        rec.release()
                        Log.w("mikrofon ($r Hz): AudioRecord se nepodařilo připravit")
                    }
                } else Log.w("mikrofon: $r Hz telefon neumí")
            } catch (e: Exception) {
                Log.e("mikrofon nejde otevřít ($r Hz)", e)
            }
        }
        return game != null || mic != null
    }

    private fun releaseAll() {
        try { game?.release() } catch (_: Exception) { }
        try { mic?.release() } catch (_: Exception) { }
        game = null
        mic = null
        gameOn = false
        micOn = false
    }

    /** Spustí čtení do kodéru (na vlastním vlákně). */
    fun start(encoder: AudioEncoder) {
        if (game == null && mic == null) return
        this.encoder = encoder
        running = true
        thread = Thread({ loop() }, "kine-audio").also { it.start() }
    }

    private fun loop() {
        val g = game
        val m = mic
        val enc = encoder ?: return
        try {
            g?.startRecording()
            m?.startRecording()
        } catch (e: Exception) {
            Log.e("zvuk: start", e)
            running = false
            releaseAll()
            return
        }
        val stereoBytes = FRAMES * 2 * 2
        val gameBuf = ByteArray(stereoBytes)
        val micMono = ByteArray(FRAMES * 2 * 2)
        val out = ByteArray(stereoBytes)
        var micCarry = 0
        var errors = 0
        try {
            while (running) {
                if (g != null) {
                    val n = g.read(gameBuf, 0, stereoBytes)
                    if (n <= 0) {
                        if (n < 0) {
                            if (++errors == 1 || errors % 200 == 0) Log.w("zvuk hry: čtení vrátilo $n")
                            Thread.sleep(20)
                        }
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
                        enc.write(out, n, pts)
                    } else {
                        enc.write(gameBuf, n, pts)
                    }
                } else {
                    // Hodinami je mikrofon (mono) - přečte se blokově tolik snímků, kolik má stereo kousek.
                    val n = m!!.read(micMono, 0, FRAMES * 2)
                    if (n <= 0) {
                        if (n < 0) {
                            if (++errors == 1 || errors % 200 == 0) Log.w("mikrofon: čtení vrátilo $n")
                            Thread.sleep(20)
                        }
                        continue
                    }
                    upmix(micMono, n, out)
                    enc.write(out, n * 2, ptsFor(n / 2))
                }
            }
        } catch (e: Throwable) {
            Log.e("zvuk: smyčka", e)
        }
        try { g?.stop() } catch (_: Exception) { }
        try { m?.stop() } catch (_: Exception) { }
        releaseAll()
    }

    private fun ptsFor(frames: Int): Long = System.nanoTime() / 1000L - frames.toLong() * 1_000_000L / rate

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

    /** Konec: počká na vlákno (čtení se do ~20 ms vrátí). Když se zdroje nespustily, jen je uvolní. */
    fun stop() {
        running = false
        val t = thread
        if (t == null) {
            releaseAll()
            return
        }
        t.join(1200)
    }
}
