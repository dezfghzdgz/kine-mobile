package com.kine.clipper

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.IBinder
import android.os.SystemClock
import java.io.FileInputStream

/**
 * Nahrávání klipů na Kine na pozadí (služba na popředí s průběhem v oznámení):
 * lístek od Kine, soubor po kusech (tus) do Cloudflare Stream, potvrzení,
 * pak Kine video zpracuje. Fronta = klipy se stavem "queued" v knihovně.
 *
 * Po nahrání se služba doptává, až je video na Kine zpracované (s id videa
 * NA KINE - do 0.4.1 se ptala s id od Cloudflare, Kine video nenašla a klip
 * zůstal viset jako „zpracovává se“: u Cloudflare byl, na Kine nikde).
 */
class UploadService : Service() {
    companion object {
        const val ACTION_KICK = "com.kine.clipper.UPLOAD"
        /** Kus pro tus (Cloudflare chce aspoň 5 MiB, násobek 256 KiB); telefony s málo pamětí 5 MiB. */
        private const val CHUNK = 8 * 1024 * 1024
        private const val CHUNK_LOW = 5 * 1024 * 1024
        @Volatile var active = false
            private set
        /** Jak dlouho po nahrání čekat, až Kine klip zpracuje (krátký klip ~10-60 s). */
        private const val READY_WAIT_MS = 10 * 60 * 1000L

        /**
         * Spustit frontu. Android 12+ nedovolí službu na popředí spustit z pozadí (např. Stop
         * z oznámení během hry) - pak klipy zůstanou ve frontě a pošlou se po otevření appky.
         */
        fun kick(context: Context): Boolean = try {
            context.startForegroundService(Intent(context, UploadService::class.java).setAction(ACTION_KICK))
            true
        } catch (e: Exception) {
            Log.w("nahrávání: teď nejde spustit (${e.message}) - zkusí se po otevření appky")
            false
        }
    }

    private var thread: Thread? = null
    @Volatile private var stopRequested = false
    /** Klipy nahrané v tomhle běhu, na které se čeká, až je Kine zpracuje. */
    private val watching = LinkedHashSet<String>()

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
                val next = lib.queued().lastOrNull()
                if (next != null) {
                    uploadOne(api, lib, next, settings)
                    continue
                }
                // Fronta je prázdná: počkat, až Kine nahrané klipy zpracuje (nebo přibude další klip).
                if (!waitReady(api, lib)) break
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

    /**
     * Doptává se Kine, až nahrané klipy zpracuje (stav „ready“ - teprve pak jsou na Kine
     * vidět). Po 5 s, později po 15 s, nejvýš [READY_WAIT_MS]. True = mezitím přibyl další
     * klip ve frontě (nahrávat dál), false = konec.
     */
    private fun waitReady(api: KineApi, lib: Library): Boolean {
        if (watching.isEmpty()) return false
        progress(getString(R.string.upload_processing), -1)
        val until = SystemClock.elapsedRealtime() + READY_WAIT_MS
        var round = 0
        while (!stopRequested && watching.isNotEmpty() && SystemClock.elapsedRealtime() < until) {
            if (lib.queued().isNotEmpty()) return true
            Thread.sleep(if (round < 12) 5000L else 15000L)
            round++
            for (id in watching.toList()) {
                if (stopRequested) break
                val c = lib.get(id)
                val kineId = c?.kineVideoId()
                if (c == null || kineId == null || c.uploadState != "done") {
                    watching.remove(id)
                    continue
                }
                val st = try { api.status(kineId) } catch (_: Exception) { continue }
                when (st) {
                    "ready" -> {
                        watching.remove(id)
                        lib.update(id) { it.ready = true }
                        Log.i("Kine klip zpracovala: ${c.title}")
                    }
                    "failed" -> {
                        watching.remove(id)
                        lib.update(id) { it.uploadState = "error"; it.error = getString(R.string.upload_failed_processing) }
                        Log.w("Kine klip nezpracovala: ${c.title}")
                    }
                    "not-found" -> {
                        // Na Kine smazané: chyba (ručně jde nahrát znovu) - automatické nahrávání bere jen
                        // nenahrané klipy, smazaný klip by jinak sám šel na Kine znovu.
                        watching.remove(id)
                        lib.update(id) { it.uploadState = "error"; it.error = getString(R.string.upload_gone); it.url = null; it.kineId = null; it.ready = false }
                    }
                }
            }
        }
        if (watching.isNotEmpty()) Log.w("Kine klipy ještě zpracovává (${watching.size}) - appka se zeptá znovu po otevření")
        watching.clear()
        return !stopRequested && lib.queued().isNotEmpty()
    }

    /**
     * Uložení na Kine. Výpadek sítě / chyba serveru = zkusit znovu (Kine potvrzení stejného
     * nahrání vrátí totéž video - nic se nezdvojí); jinak by soubor u Cloudflare zůstal
     * neuložený a nové nahrání by udělalo duplikát.
     */
    private fun confirmWithRetry(api: KineApi, clip: Clip, visibility: String): String {
        var attempt = 0
        while (true) {
            try {
                return api.confirm(clip, visibility)
            } catch (e: Exception) {
                val transient = e !is KineApiError || e.status >= 500 || e.status == 429
                attempt++
                if (!transient || attempt >= 4 || stopRequested) throw e
                Log.w("uložení na Kine nevyšlo (${e.message}) - znovu za chvíli")
                Thread.sleep(4000L * attempt)
            }
        }
    }

    private fun uploadOne(api: KineApi, lib: Library, clip: Clip, settings: Settings) {
        lib.update(clip.id) { it.uploadState = "uploading"; it.uploadPercent = 0; it.error = null }
        progress(clip.title, 0)
        var confirmed = false
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
                        val chunk = if (Device.isLowRam(this)) CHUNK_LOW else CHUNK
                        val buffer = ByteArray(chunk)
                        var failures = 0
                        while (offset < size) {
                            if (stopRequested) throw IllegalStateException("stopped")
                            val length = minOf(chunk.toLong(), size - offset).toInt()
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
                val visibility = fresh.visibility ?: settings.visibility
                val id = confirmWithRetry(api, fresh, visibility)
                confirmed = true
                val url = "${settings.siteUrl}/watch/$id"
                lib.update(clip.id) {
                    it.uploadState = "done"; it.uploadPercent = 100; it.url = url
                    it.kineId = id; it.ready = false; it.visibility = visibility; it.error = null
                    it.uploadedAt = System.currentTimeMillis()
                }
                Log.i("nahráno ($visibility): ${clip.title} -> $url")
                // Zpracování u Cloudflare chvíli trvá - na stav se služba doptá, až nahraje celou frontu (waitReady).
                watching.add(clip.id)
            }
        } catch (e: Throwable) {
            Log.e("nahrávání ${clip.title}", e)
            // Video už je na Kine uložené - chyba až potom klip nerozbije.
            if (confirmed) return
            val msg = e.message ?: "error"
            lib.update(clip.id) { it.uploadState = "error"; it.error = msg }
            if (e is KineApiError && e.status == 401) {
                // Odhlášeno - ostatní ve frontě by dopadly stejně.
                for (c in lib.queued()) lib.update(c.id) { it.uploadState = "error"; it.error = msg }
            }
            // Nepovedené nahrání ať na Kine nedrží místo; uložené video (potvrzené) se nikdy neruší.
            if (!confirmed) clip.videoId?.let { id -> try { api.cancelUpload(id) } catch (_: Exception) { } }
        }
    }

    override fun onDestroy() {
        stopRequested = true
        super.onDestroy()
    }
}
