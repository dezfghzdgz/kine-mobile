package com.kine.clipper

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.provider.MediaStore
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Klip z posledních N sekund: hotové kousky záznamu (Segment) se slepí bez
 * překódování do jednoho MP4 (MediaExtractor -> MediaMuxer). Každý kousek má v MP4
 * časy od nuly - tady se přepočtou na společné hodiny (podle prvního snímku), začátek
 * se usekne na klíčovém snímku co nejblíž N sekund od konce. Soubor jde do Galerie
 * (MediaStore, složka Movies/Kine) - odtud se i nahrává.
 */
object ClipMaker {
    data class Result(val uri: Uri, val displayName: String, val durationMs: Long, val sizeBytes: Long, val width: Int, val height: Int)

    fun make(context: Context, all: List<Segment>, seconds: Int, title: String): Result {
        if (all.isEmpty()) throw IllegalStateException("nothing recorded yet")
        // Jen kousky se stejným rozměrem jako poslední (po otočení telefonu se kodér vyměnil).
        val epoch = all.last().epoch
        val segments = all.filter { it.epoch == epoch }
        val endUs = segments.last().endUs
        val wantStartUs = endUs - seconds * 1_000_000L
        // Kousky od toho, který obsahuje začátek klipu.
        var first = segments.indexOfLast { it.startUs <= wantStartUs }
        if (first < 0) first = 0
        val used = segments.subList(first, segments.size)
        val tmp = File(context.cacheDir, "clip-${System.currentTimeMillis()}.mp4")
        var width = 0
        var height = 0
        try {
            val durationUs = remux(used, wantStartUs, tmp) { w, h -> width = w; height = h }
            val uri = saveToGallery(context, tmp, title)
            return Result(uri, title, durationUs / 1000, tmp.length(), width, height)
        } finally {
            tmp.delete()
        }
    }

    /** Slepení: vrátí délku výsledku (µs). Obraz začíná prvním klíčovým snímkem >= cut, zvuk od stejného času. */
    private fun remux(segments: List<Segment>, cutUs: Long, out: File, onSize: (Int, Int) -> Unit): Long {
        val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var videoOut = -1
        var audioOut = -1
        var started = false
        var baseUs = 0L
        var videoStarted = false
        var lastVideoUs = -1L
        var lastAudioUs = -1L
        var buffer: ByteBuffer? = null
        val info = MediaCodec.BufferInfo()
        try {
            for (seg in segments) {
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(seg.file.absolutePath)
                    var videoIn = -1
                    var audioIn = -1
                    var maxSample = 0
                    for (t in 0 until extractor.trackCount) {
                        val f = extractor.getTrackFormat(t)
                        val mime = f.getString(MediaFormat.KEY_MIME) ?: ""
                        if (f.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) maxSample = maxOf(maxSample, f.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
                        if (mime.startsWith("video/") && videoIn < 0) {
                            videoIn = t
                            if (!started) {
                                videoOut = muxer.addTrack(f)
                                onSize(f.getInteger(MediaFormat.KEY_WIDTH), f.getInteger(MediaFormat.KEY_HEIGHT))
                            }
                        } else if (mime.startsWith("audio/") && audioIn < 0) {
                            audioIn = t
                            if (!started) audioOut = muxer.addTrack(f)
                        }
                    }
                    if (videoIn < 0) continue
                    if (!started) {
                        muxer.start()
                        started = true
                    }
                    // Vyrovnávací paměť podle největšího vzorku (klíčový snímek 720p bývá pár set kB).
                    val need = (if (maxSample > 0) maxSample else 2 * 1024 * 1024).coerceIn(512 * 1024, 8 * 1024 * 1024)
                    if (buffer == null || buffer.capacity() < need) buffer = ByteBuffer.allocateDirect(need)
                    val buf = buffer!!
                    // MP4 kousku má časy od nuly (nebo původní) - posun zpátky na společné hodiny
                    // podle prvního snímku obrazu (= klíčový snímek v čase seg.startUs).
                    extractor.selectTrack(videoIn)
                    val firstVideo = extractor.sampleTime
                    val shift = seg.startUs - (if (firstVideo >= 0) firstVideo else 0L)
                    if (audioIn >= 0 && audioOut >= 0) extractor.selectTrack(audioIn)
                    while (true) {
                        buf.clear()
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) break
                        val track = extractor.sampleTrackIndex
                        val time = extractor.sampleTime + shift
                        val sync = (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                        if (track == videoIn) {
                            if (!videoStarted) {
                                // Obraz před řezem a do prvního klíčového snímku pryč.
                                if (time < cutUs || !sync) {
                                    extractor.advance()
                                    continue
                                }
                                videoStarted = true
                                baseUs = time
                            }
                            val pts = time - baseUs
                            if (pts >= 0 && pts > lastVideoUs) {
                                info.set(0, size, pts, if (sync) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                                muxer.writeSampleData(videoOut, buf, info)
                                lastVideoUs = pts
                            }
                        } else if (track == audioIn && audioOut >= 0 && videoStarted) {
                            var pts = time - baseUs
                            if (pts >= 0) {
                                if (pts <= lastAudioUs) pts = lastAudioUs + 1
                                info.set(0, size, pts, 0)
                                muxer.writeSampleData(audioOut, buf, info)
                                lastAudioUs = pts
                            }
                        }
                        extractor.advance()
                    }
                } finally {
                    extractor.release()
                }
            }
        } finally {
            try {
                if (started) muxer.stop()
            } catch (e: Exception) {
                Log.w("klip: muxer.stop: ${e.message}")
            }
            muxer.release()
        }
        if (!started || !videoStarted) throw IllegalStateException("empty clip")
        return lastVideoUs
    }

    /** Do Galerie: Movies/Kine/<název>.mp4 (IS_PENDING, dokud se kopíruje). */
    private fun saveToGallery(context: Context, file: File, title: String): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, safeName(title) + ".mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Kine")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: throw IllegalStateException("MediaStore insert failed")
        try {
            resolver.openOutputStream(uri)!!.use { out -> FileInputStream(file).use { it.copyTo(out, 256 * 1024) } }
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            try { resolver.delete(uri, null, null) } catch (_: Exception) { }
            throw e
        }
        return uri
    }

    fun defaultTitle(): String = "Kine " + SimpleDateFormat("yyyy-MM-dd HH-mm-ss", Locale.US).format(Date())

    private fun safeName(title: String): String = title.replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), "-").trim().ifEmpty { "Kine" }.take(100)
}
