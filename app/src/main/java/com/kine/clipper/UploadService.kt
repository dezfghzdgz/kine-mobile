package com.kine.clipper

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.IBinder
import java.io.FileInputStream

/**
 * Nahrávání klipů na Kine na pozadí (služba na popředí s průběhem v oznámení):
 * lístek od Kine, soubor po kusech (tus) do Cloudflare Stream, potvrzení,
 * pak Kine video zpracuje. Fronta = klipy se stavem "queued" v knihovně.
 */
class UploadService : Service() {
    companion object {
        const val ACTION_KICK = "com.kine.clipper.UPLOAD"
        private const val CHUNK = 8 * 1024 * 1024
        @Volatile var active = false
            private set

        fun kick(context: Context) {
            context.startForegroundService(Intent(context, UploadService::class.java).setAction(ACTION_KICK))
        }
    }

    private var thread: Thread? = null
    @Volatile private var stopRequested = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startForeground(Notifications.ID_UPLOAD, Notifications.upload(this, getString(R.string.upload_preparing), -1), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } catch (e: Exception) {
            Log.e("nahrávání: služba", e)
            stopSelf()
            return START_NOT_STICKY
        }
        if (thread == null) {
            active = true
            thread = Thread({ run() }, "kine-upload").also { it.start() }
        }
        return START_NOT_STICKY
    }

    private fun run() {
        val settings = Settings(this)
        val api = KineApi(this, settings)
        val lib = Library.get(this)
        try {
            while (!stopRequested) {
                val next = lib.queued().lastOrNull() ?: break
                uploadOne(api, lib, next, settings)
            }
        } finally {
            active = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun progress(text: String, percent: Int) {
        try {
            getSystemService(NotificationManager::class.java)?.notify(Notifications.ID_UPLOAD, Notifications.upload(this, text, percent))
        } catch (_: Exception) {
            // nevadí
        }
    }

    private fun uploadOne(api: KineApi, lib: Library, clip: Clip, settings: Settings) {
        lib.update(clip.id) { it.uploadState = "uploading"; it.uploadPercent = 0; it.error = null }
        progress(clip.title, 0)
        try {
            val uri = Uri.parse(clip.uri)
            val pfd = contentResolver.openFileDescriptor(uri, "r") ?: throw IllegalStateException("file missing")
            pfd.use { fd ->
                val size = fd.statSize
                if (size <= 0) throw IllegalStateException("empty file")
                val target = api.createUploadUrl(size, if (clip.durationMs > 0) clip.durationMs / 1000.0 else null)
                lib.update(clip.id) { it.videoId = target.videoId }
                FileInputStream(fd.fileDescriptor).use { input ->
                    val channel = input.channel
                    if (target.mode == "tus") {
                        var offset = 0L
                        val buffer = ByteArray(CHUNK)
                        var failures = 0
                        while (offset < size) {
                            if (stopRequested) throw IllegalStateException("stopped")
                            val length = minOf(CHUNK.toLong(), size - offset).toInt()
                            channel.position(offset)
                            var read = 0
                            while (read < length) {
                                val n = input.read(buffer, read, length - read)
                                if (n <= 0) break
                                read += n
                            }
                            try {
                                offset = api.tusPatch(target.uploadUrl, offset, buffer, read)
                                failures = 0
                            } catch (e: KineApiError) {
                                if (e.status in 400..499 && e.status != 409 && e.status != 429) throw e
                                failures++
                                if (failures > 6) throw e
                                Log.w("nahrávání: kus nešel (${e.message}), zkouším znovu za chvíli")
                                Thread.sleep(3000L * failures)
                                offset = try { api.tusOffset(target.uploadUrl) } catch (_: Exception) { offset }
                            } catch (e: Exception) {
                                failures++
                                if (failures > 6) throw e
                                Log.w("nahrávání: ${e.message} - znovu za chvíli")
                                Thread.sleep(3000L * failures)
                                offset = try { api.tusOffset(target.uploadUrl) } catch (_: Exception) { offset }
                            }
                            val percent = ((offset * 100) / size).toInt()
                            lib.update(clip.id) { it.uploadPercent = percent }
                            progress(clip.title, percent)
                        }
                    } else {
                        channel.position(0)
                        api.basicUpload(target.uploadUrl, size) { out -> input.copyTo(out, 1024 * 1024) }
                    }
                }
                progress(getString(R.string.upload_confirming), 100)
                val fresh = lib.get(clip.id) ?: clip
                val id = api.confirm(fresh, settings.visibility)
                val url = "${settings.siteUrl}/watch/$id"
                lib.update(clip.id) { it.uploadState = "done"; it.uploadPercent = 100; it.url = url }
                Log.i("nahráno: ${clip.title} -> $url")
                // Zpracování u Cloudflare chvíli trvá - zeptat se párkrát, ať se video na Kine objeví (stav 'ready' nastavuje Kine při dotazu).
                for (i in 0 until 24) {
                    if (stopRequested) break
                    try {
                        if (api.status(target.videoId) == "ready") break
                    } catch (_: Exception) {
                        // další pokus
                    }
                    Thread.sleep(5000)
                }
            }
        } catch (e: Exception) {
            Log.e("nahrávání ${clip.title}", e)
            val msg = e.message ?: "error"
            lib.update(clip.id) { it.uploadState = "error"; it.error = msg }
            if (e is KineApiError && e.status == 401) {
                // Odhlášeno - ostatní ve frontě by dopadly stejně.
                for (c in lib.queued()) lib.update(c.id) { it.uploadState = "error"; it.error = msg }
            }
            clip.videoId?.let { id -> try { api.cancelUpload(id) } catch (_: Exception) { } }
        }
    }

    override fun onDestroy() {
        stopRequested = true
        super.onDestroy()
    }
}
