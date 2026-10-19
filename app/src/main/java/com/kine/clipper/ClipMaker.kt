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
 * překódování do jednoho MP4 (MediaExtractor -> MediaMuxer, časy od nuly),
 * začátek se usekne na klíčovém snímku co nejblíž N sekund od konce. Soubor
 * jde do Galerie (MediaStore, složka Movies/Kine) - odtud se i nahrává.
 */
object ClipMaker {
    data class Result(val uri: Uri, val displayName: String, val durationMs: Long, val sizeBytes: Long, val width: Int, val height: Int)

    fun make(context: Context, segments: List<Segment>, seconds: Int, title: String): Result {
        if (segments.isEmpty()) throw IllegalStateException("no segments")
        val endUs = segments.last().endUs
        val wantStartUs = endUs - seconds * 1_000_000L
        // Kousky od toho, který obsahuje začátek klipu.
        var first = segments.indexOfLast { it.startUs <= wantStartUs }
        if (first < 0) first = 0
        val used = segments.subList(first, segments.size)
        val tmp = File(context.cacheDir, "clip-${System.currentTimeMillis()}.mp4")
        var width = 0
        var height = 0
        var durationUs = 0L
        try {
            durationUs = remux(used, wantStartUs, tmp) { w, h -> width = w; height = h }
            val uri = saveToGallery(context, tmp, title)
            return Result(uri, title, durationUs / 1000, tmp.length(), width, height)
        } finally {
            tmp.delete()
        }
    }

    /** Slepení: vrátí délku výsledku (µs). Obraz začíná na prvním klíčovém snímku >= cut, zvuk od stejného času. */
    private fun remux(segments: List<Segment>, cutUs: Long, out: File, onSize: (Int, Int) -> Unit): Long {
        val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var videoOut = -1
        var audioOut = -1
        var started = false
        var baseUs = -1L
        var lastVideoUs = 0L
        var lastAudioUs = -1L
        var videoStartedAtKey = false
        val buffer = ByteBuffer.allocateDirect(4 * 1024 * 1024)
        val info = MediaCodec.BufferInfo()
        try {
            for ((index, seg) in segments.withIndex()) {
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(seg.file.absolutePath)
                    var videoIn = -1
                    var audioIn = -1
                    for (t in 0 until extractor.trackCount) {
                        val f = extractor.getTrackFormat(t)
                        val mime = f.getString(MediaFormat.KEY_MIME) ?: ""
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
                    extractor.selectTrack(videoIn)
                    if (audioIn >= 0 && audioOut >= 0) extractor.selectTrack(audioIn)
                    while (true) {
                        buffer.clear()
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) break
                        val track = extractor.sampleTrackIndex
                        val time = extractor.sampleTime
                        val sync = (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                        if (track == videoIn) {
                            // První kousek: přeskočit obraz před řezem až po klíčový snímek.
                            if (!videoStartedAtKey) {
                                if (index == 0 && (time < cutUs || !sync)) {
                                    extractor.advance()
                                    continue
                                }
                                if (!sync) {
                                    extractor.advance()
                                    continue
                                }
                                videoStartedAtKey = true
                                baseUs = time
                            }
                            info.set(0, size, time - baseUs, if (sync) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                            if (info.presentationTimeUs < lastVideoUs) info.presentationTimeUs = lastVideoUs
                            muxer.writeSampleData(videoOut, buffer, info)
                            lastVideoUs = info.presentationTimeUs
                        } else if (track == audioIn && audioOut >= 0 && videoStartedAtKey) {
                            if (time < baseUs) {
                                extractor.advance()
                                continue
                            }
                            info.set(0, size, time - baseUs, 0)
                            if (info.presentationTimeUs <= lastAudioUs) info.presentationTimeUs = lastAudioUs + 1
                            muxer.writeSampleData(audioOut, buffer, info)
                            lastAudioUs = info.presentationTimeUs
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
        if (!started || !videoStartedAtKey) throw IllegalStateException("empty clip")
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
            resolver.openOutputStream(uri)!!.use { out -> FileInputStream(file).use { it.copyTo(out, 1024 * 1024) } }
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        return uri
    }

    fun defaultTitle(): String = "Kine " + SimpleDateFormat("yyyy-MM-dd HH-mm-ss", Locale.US).format(Date())

    private fun safeName(title: String): String = title.replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), "-").trim().ifEmpty { "Kine" }.take(100)
}
