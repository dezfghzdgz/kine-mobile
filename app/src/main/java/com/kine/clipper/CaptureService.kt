package com.kine.clipper

import android.Manifest
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Point
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings as SysSettings
import android.util.DisplayMetrics
import android.view.Display
import android.widget.Toast
import java.io.File
import java.util.UUID
import kotlin.math.abs
import kotlin.math.min

/**
 * Snímání na pozadí (služba na popředí: obrazovka + kamera + mikrofon): drží posledních
 * N sekund obrazovky (s kamerou v obraze a zvukem) a na klepnutí bubliny / tlačítko
 * v oznámení z nich udělá klip do Galerie.
 *
 * Stabilita (hlavně starší telefony): veškerá těžká práce (kodéry, kamera, OpenGL,
 * zastavení) běží na vlastním vlákně „kine-pipe“, hlavní vlákno (rozhraní, bublina)
 * nikdy nečeká. Start i stop hlídá časovač - když se něco zasekne, appka to řekne
 * místo „Spouští se…“ navždy. Bublina je vidět hned po povolení nahrávání. Bez kamery
 * jde obrazovka rovnou do kodéru (bez OpenGL). Po otočení telefonu (hra na šířku) se
 * kodér přenastaví na nový rozměr - klip není zmenšený v černém rámu. Spadlý kodér
 * se zkusí obnovit, horký telefon vypne kameru a ubere datový tok.
 */
class CaptureService : Service() {
    companion object {
        /** Služba na popředí s mikrofonem / kamerou ještě před oknem „nahrávat obrazovku“ (viz [sensorTypes]). */
        const val ACTION_PREPARE = "com.kine.clipper.PREPARE"
        const val ACTION_START = "com.kine.clipper.START"
        const val ACTION_STOP = "com.kine.clipper.STOP"
        const val ACTION_CLIP = "com.kine.clipper.CLIP"
        /** Nastavení bubliny se změnilo / přibylo oprávnění - ukázat ji znovu. */
        const val ACTION_BUBBLE = "com.kine.clipper.BUBBLE"
        const val ACTION_STATE = "com.kine.clipper.STATE"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"

        const val IDLE = "idle"
        const val PREPARING = "preparing"
        const val STARTING = "starting"
        const val RUNNING = "running"
        const val STOPPING = "stopping"

        @Volatile var state = IDLE
            private set
        /** Proč se snímání nespustilo / skončilo (ukazuje appka až do dalšího startu). */
        @Volatile var error: String? = null
            private set
        /** Co nejde, ale nahrává se dál (bez zvuku, horký telefon…). */
        @Volatile var warning: String? = null
            private set
        /** Rozměr a snímky, které kodér opravdu dělá (např. „1600×720 · 30 fps“). */
        @Volatile var info = ""
            private set
        @Volatile var cameraState = ""
            private set
        /** Od kdy se nahrává (SystemClock.elapsedRealtime), 0 = nenahrává se. */
        @Volatile var startedAt = 0L
            private set
        @Volatile var bubbleShown = false
            private set
        val active: Boolean get() = state == PREPARING || state == STARTING || state == RUNNING

        /**
         * Typy služby pro mikrofon / kameru podle nastavení a oprávnění (0 = nic). Appka podle toho
         * službu spustí ještě před oknem „nahrávat obrazovku“, dokud je vepředu: na Androidu 14+ se
         * výsledek (hlavně „Jedna aplikace“) vrací, když je appka už v pozadí - a z pozadí by
         * mikrofon ani kamera nešly.
         */
        fun sensorTypes(context: Context, settings: Settings): Int {
            var t = 0
            if ((settings.gameAudio || settings.microphone) && context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            if (settings.camera != "off" && context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            }
            return t
        }
    }

    private lateinit var settings: Settings
    private val main = Handler(Looper.getMainLooper())
    private var session: Session? = null
    private var bubble: Bubble? = null
    private var shake: ShakeDetector? = null
    private var clipBusy = false
    private val warnings = LinkedHashSet<Int>()
    private val startWatchdog = Runnable { if (state == STARTING) fail(getString(R.string.err_timeout)) }
    private val finishWatchdog = Runnable { finish() }
    private val prepareWatchdog = Runnable {
        if (session == null && state == PREPARING) {
            Log.w("povolení nahrávání obrazovky nepřišlo - konec přípravy")
            finish()
        }
    }
    private val clipWatchdog = Runnable {
        clipBusy = false
        bubble?.setState(Bubble.State.READY)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PREPARE -> if (session == null) prepare()
            ACTION_START -> {
                val code = intent.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE)
                @Suppress("DEPRECATION")
                val data = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                if (session != null) return START_NOT_STICKY
                if (code == Int.MIN_VALUE || data == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                start(code, data)
            }
            ACTION_CLIP -> if (session != null) clip() else stopSelf()
            ACTION_STOP -> stop()
            ACTION_BUBBLE -> if (session != null) refreshBubble() else stopSelf()
            else -> if (session == null) stopSelf()
        }
        return START_NOT_STICKY
    }

    // ---- start / stop (hlavní vlákno) ----

    /** Ještě vepředu: služba na popředí s mikrofonem / kamerou (bez obrazovky - ta potřebuje povolení). */
    private fun prepare() {
        val types = sensorTypes(this, settings)
        // Spuštěno obyčejným startService (appka je vepředu) - když to nejde, prostě skončit.
        if (types == 0) {
            stopSelf()
            return
        }
        try {
            startForeground(Notifications.ID_CAPTURE, Notifications.capture(this, getString(R.string.state_starting), getString(R.string.notif_starting_text), false), types)
        } catch (e: Exception) {
            Log.e("příprava služby", e)
            stopSelf()
            return
        }
        error = null
        state = PREPARING
        main.removeCallbacks(prepareWatchdog)
        main.postDelayed(prepareWatchdog, 120_000)
        broadcast()
    }

    private fun start(resultCode: Int, data: Intent) {
        main.removeCallbacks(finishWatchdog)
        main.removeCallbacks(prepareWatchdog)
        error = null
        warning = null
        warnings.clear()
        info = ""
        cameraState = ""
        val audioPerm = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val camPerm = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val wantAudio = settings.gameAudio || settings.microphone
        val wantCamera = settings.camera != "off"
        var useAudio = wantAudio && audioPerm
        var useCamera = wantCamera && camPerm
        if (wantAudio && !audioPerm) warnings.add(R.string.warn_no_audio)
        if (wantCamera && !camPerm) warnings.add(R.string.warn_no_camera)
        // Android 14: nejdřív služba na popředí s typem mediaProjection, pak teprve projekce.
        // Typ mikrofon / kamera jen s oprávněním (jinak by Android službu shodil).
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        if (useAudio) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        if (useCamera) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        state = STARTING
        val notification = Notifications.capture(this, getString(R.string.state_starting), getString(R.string.notif_starting_text), false)
        try {
            try {
                startForeground(Notifications.ID_CAPTURE, notification, types)
            } catch (e: Exception) {
                if (types == ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION) throw e
                // Android 14+: mikrofon / kamera z pozadí nejdou (appka už nebyla vepředu) - aspoň obrazovka.
                Log.w("služba s mikrofonem / kamerou nejde (${e.message}) - jen obrazovka")
                useAudio = false
                useCamera = false
                warnings.add(R.string.warn_background_start)
                startForeground(Notifications.ID_CAPTURE, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            }
        } catch (e: Exception) {
            Log.e("služba na popředí", e)
            error = getString(R.string.err_generic, e.message ?: e::class.java.simpleName)
            finish()
            return
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val proj = try {
            mpm.getMediaProjection(resultCode, data)
        } catch (e: Exception) {
            Log.e("projekce", e)
            null
        }
        if (proj == null) {
            fail(getString(R.string.status_no_projection))
            return
        }
        publishWarnings()
        val s = Session(proj, useAudio, useCamera)
        session = s
        s.begin()
        // Bublina hned (oranžově bliká, dokud se snímání nerozjede) - je vidět, že appka běží.
        showBubble(Bubble.State.STARTING)
        if (settings.shakeToClip) shake = ShakeDetector(this) { clip() }.also { it.start() }
        main.postDelayed(startWatchdog, 15_000)
        updateNotification()
        broadcast()
    }

    private fun onRunning(s: Session) {
        if (session !== s || state != STARTING) return
        main.removeCallbacks(startWatchdog)
        state = RUNNING
        startedAt = SystemClock.elapsedRealtime()
        if (bubble == null) showBubble(Bubble.State.READY) else bubble?.setState(Bubble.State.READY)
        updateNotification()
        broadcast()
    }

    /** Konec s chybou (hlavní vlákno): chyba zůstane vidět v appce. */
    private fun fail(message: String) {
        Log.e("snímání: $message")
        error = message
        stop()
    }

    fun stop() {
        val s = session
        if (s == null) {
            finish()
            return
        }
        if (state == STOPPING) return
        state = STOPPING
        main.removeCallbacks(startWatchdog)
        hideBubble()
        shake?.stop()
        shake = null
        broadcast()
        s.shutdown { main.post { if (session === s) finish() } }
        // Kdyby se úklid zasekl (ovladač kamery / kodéru), služba stejně skončí.
        main.postDelayed(finishWatchdog, 8000)
    }

    private fun finish() {
        main.removeCallbacks(finishWatchdog)
        main.removeCallbacks(prepareWatchdog)
        main.removeCallbacks(startWatchdog)
        main.removeCallbacks(clipWatchdog)
        session = null
        clipBusy = false
        hideBubble()
        shake?.stop()
        shake = null
        state = IDLE
        startedAt = 0L
        cameraState = ""
        info = ""
        // Klipy, které čekají na nahrání, teď (po hře) na Kine.
        try {
            val lib = Library.get(this)
            if (settings.autoUpload && settings.loggedIn && lib.list().any { it.uploadState == "none" }) {
                for (c in lib.list()) if (c.uploadState == "none") lib.update(c.id) { it.uploadState = "queued" }
                UploadService.kick(this)
            }
        } catch (e: Exception) {
            Log.w("automatické nahrání: ${e.message}")
        }
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) { }
        broadcast()
        stopSelf()
    }

    // ---- klip ----

    private fun clip() {
        val s = session ?: return
        if (state != RUNNING) return
        if (clipBusy) return
        val wr = s.writer ?: return
        clipBusy = true
        bubble?.setState(Bubble.State.SAVING)
        val seconds = settings.clipSeconds
        if (!wr.requestClip { segments -> makeClip(segments, seconds) }) {
            clipBusy = false
            return
        }
        // Kdyby klíčový snímek nepřišel (kodér stojí), vzít to, co je.
        s.pipe.postDelayed({ wr.forcePendingClip() }, 2500)
        main.postDelayed(clipWatchdog, 30_000)
    }

    /** Na vlákně „kine-clip“: slepit kousky do souboru v Galerii. */
    private fun makeClip(segments: List<Segment>, seconds: Int) {
        try {
            val title = ClipMaker.defaultTitle()
            val r = ClipMaker.make(this, segments, seconds, title)
            val clip = Clip(UUID.randomUUID().toString(), r.uri.toString(), title, System.currentTimeMillis(), r.durationMs, r.sizeBytes, r.width, r.height)
            Library.get(this).add(clip)
            Log.i("klip: $title (${r.durationMs / 1000} s, ${r.sizeBytes / 1024} kB, ${r.width}x${r.height})")
            main.post {
                main.removeCallbacks(clipWatchdog)
                clipBusy = false
                bubble?.setState(Bubble.State.SAVED)
                try {
                    getSystemService(NotificationManager::class.java)?.notify(Notifications.ID_CLIP, Notifications.clipSaved(this, getString(R.string.clip_saved, (r.durationMs / 1000).toInt())))
                } catch (_: Exception) {
                    // bez oznámení
                }
                broadcast()
            }
        } catch (e: Throwable) {
            Log.e("klip se nepovedl", e)
            val msg = if (segments.isEmpty()) getString(R.string.clip_too_early) else getString(R.string.clip_failed, e.message ?: e::class.java.simpleName)
            main.post {
                main.removeCallbacks(clipWatchdog)
                clipBusy = false
                bubble?.setState(Bubble.State.FAILED)
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                broadcast()
            }
        }
    }

    // ---- bublina ----

    private fun showBubble(st: Bubble.State) {
        if (!settings.bubble || bubble != null) return
        if (!SysSettings.canDrawOverlays(this)) {
            bubbleShown = false
            return
        }
        val b = Bubble(this, settings, { clip() }, { openApp() })
        b.show(st)
        if (b.shown) {
            bubble = b
            bubbleShown = true
        }
    }

    private fun hideBubble() {
        bubble?.hide()
        bubble = null
        bubbleShown = false
    }

    private fun refreshBubble() {
        hideBubble()
        if (active) showBubble(if (state == RUNNING) Bubble.State.READY else Bubble.State.STARTING)
        updateNotification()
        broadcast()
    }

    private fun openApp() {
        try {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        } catch (e: Exception) {
            Log.w("appku nejde otevřít: ${e.message}")
        }
    }

    // ---- stav pro appku a oznámení ----

    private fun addWarning(res: Int) = main.post {
        if (warnings.add(res)) publishWarnings()
    }

    private fun removeWarning(res: Int) = main.post {
        if (warnings.remove(res)) publishWarnings()
    }

    private fun publishWarnings() {
        warning = if (warnings.isEmpty()) null else warnings.joinToString(" · ") { getString(it) }
        broadcast()
    }

    private fun updateNotification() {
        if (state == IDLE) return
        val title = when (state) {
            RUNNING -> getString(R.string.notif_recording, settings.clipSeconds)
            STOPPING -> getString(R.string.state_stopping)
            else -> getString(R.string.state_starting)
        }
        val text = when {
            state != RUNNING -> getString(R.string.notif_starting_text)
            bubble != null -> getString(R.string.notif_text_bubble)
            else -> getString(R.string.notif_text)
        }
        try {
            getSystemService(NotificationManager::class.java)?.notify(Notifications.ID_CAPTURE, Notifications.capture(this, title, text, state == RUNNING))
        } catch (_: Exception) {
            // nevadí
        }
    }

    private fun broadcast() {
        sendBroadcast(Intent(ACTION_STATE).setPackage(packageName))
    }

    /** Krátká zpráva pro člověka místo technické výjimky. */
    private fun friendly(e: Throwable): String = when {
        e is SecurityException -> getString(R.string.err_permission)
        e is MediaCodec.CodecException || (e.message ?: "").contains("codec", ignoreCase = true) || (e.message ?: "").contains("H.264") ->
            getString(R.string.err_encoder, e.message ?: e::class.java.simpleName)
        else -> getString(R.string.err_generic, e.message ?: e::class.java.simpleName)
    }

    override fun onDestroy() {
        val s = session
        if (s != null) {
            session = null
            s.shutdown { }
        }
        hideBubble()
        shake?.stop()
        shake = null
        if (state != IDLE) {
            state = IDLE
            startedAt = 0L
            broadcast()
        }
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ---- jedno snímání (vše těžké na vlákně kine-pipe) ----

    private inner class Session(val projection: MediaProjection, val useAudio: Boolean, val useCamera: Boolean) {
        private val thread = HandlerThread("kine-pipe").also { it.start() }
        val pipe = Handler(thread.looper)
        @Volatile var stopped = false
        @Volatile var writer: SegmentWriter? = null
        @Volatile private var venc: VideoEncoder? = null
        @Volatile private var camLayout: CameraLayout? = null
        private var vd: VirtualDisplay? = null
        private var aenc: AudioEncoder? = null
        private var comp: GlCompositor? = null
        private var camera: CameraFeed? = null
        private var audio: AudioCapture? = null
        private var dpi = 320
        private var contentW = 0
        private var contentH = 0
        private var contentFromCallback = false
        private var screenW = 0
        private var screenH = 0
        private var tier = Device.Tier.MID
        private var shortSide = 720
        private var fps = 30
        private var reconfiguring = false
        private var crashRecoveries = 0
        private var lastRecoverAt = 0L
        private var lastRotation = -1
        private var hot = false
        private var thermal: PowerManager.OnThermalStatusChangedListener? = null
        private val dm: DisplayManager = getSystemService(DisplayManager::class.java)

        private val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                Log.i("projekce ukončená systémem")
                main.post { if (session === this@Session) stop() }
            }

            // Android 14+: přesný rozměr nahrávaného obsahu (i „jedna aplikace“, i otočení).
            override fun onCapturedContentResize(width: Int, height: Int) {
                pipe.post { onContent(width, height, true) }
            }

            override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
                Log.i("nahrávaný obsah ${if (isVisible) "vidět" else "schovaný"}")
            }
        }

        private val rotateCheck = Runnable {
            if (stopped) return@Runnable
            val d = dm.getDisplay(Display.DEFAULT_DISPLAY) ?: return@Runnable
            val p = realSize(d)
            // Otočení o 180° (na šířku z jedné strany na druhou) nemění rozměr - kamera se ale musí otočit.
            if (d.rotation != lastRotation) {
                lastRotation = d.rotation
                updateCamLayout()
            }
            if (p.x != screenW || p.y != screenH) {
                screenW = p.x
                screenH = p.y
                main.post { bubble?.relayout() }
                updateCamLayout()
                if (!contentFromCallback) onContent(p.x, p.y, false)
            }
        }

        private val displayListener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {}
            override fun onDisplayRemoved(displayId: Int) {}
            override fun onDisplayChanged(displayId: Int) {
                if (displayId != Display.DEFAULT_DISPLAY || stopped) return
                // Chodí i při změně obnovovací frekvence - až po chvíli klidu a jen při změně rozměru.
                pipe.removeCallbacks(rotateCheck)
                pipe.postDelayed(rotateCheck, 400)
            }
        }

        private val reconfigureLater = Runnable { reconfigure(false) }

        fun begin() {
            // Android 14: callback před createVirtualDisplay.
            projection.registerCallback(callback, main)
            pipe.post { setup() }
        }

        private fun setup() {
            try {
                val ctx = this@CaptureService
                tier = Device.tier(ctx)
                shortSide = settings.shortSide(tier)
                fps = settings.targetFps(tier)
                Log.i("start: ${Device.describe(ctx)}; kvalita ${settings.quality} -> ${shortSide}p / $fps fps, kamera ${if (useCamera) settings.camera else "ne"}, zvuk ${if (useAudio) "hra ${settings.gameAudio} + mikrofon ${settings.microphone}" else "ne"}")
                val display = dm.getDisplay(Display.DEFAULT_DISPLAY) ?: throw IllegalStateException("no display")
                val size = realSize(display)
                screenW = size.x
                screenH = size.y
                lastRotation = display.rotation
                contentW = size.x
                contentH = size.y
                val metrics = DisplayMetrics()
                @Suppress("DEPRECATION")
                display.getRealMetrics(metrics)
                dpi = metrics.densityDpi

                // Zvuk (vzorkování podle mikrofonu) - když nejde, nahrává se bez něj.
                var a: AudioCapture? = null
                if (useAudio) {
                    a = AudioCapture(projection, settings.gameAudio, settings.microphone)
                    if (!a.open()) {
                        a = null
                        addWarning(R.string.warn_audio_failed)
                    } else {
                        if (settings.gameAudio && !a.gameOn) addWarning(R.string.warn_game_audio_failed)
                        if (settings.microphone && !a.micOn) addWarning(R.string.warn_mic_failed)
                    }
                }
                if (stopped) {
                    a?.stop()
                    return
                }
                // Kousky do vlastní složky (zbytky po spadlém snímání pryč).
                val root = File(cacheDir, "segments")
                root.listFiles()?.forEach { it.deleteRecursively() }
                val dir = File(root, "s" + SystemClock.elapsedRealtime())
                val keepUs = (settings.clipSeconds + 12) * 1_000_000L
                var wr = SegmentWriter(dir, keepUs, a != null) { venc?.requestKeyFrame() }
                if (a != null) {
                    try {
                        aenc = AudioEncoder(a.rate, 2, if (tier == Device.Tier.LOW) 96_000 else 128_000, wr)
                        audio = a
                    } catch (e: Exception) {
                        Log.e("kodér zvuku nejde", e)
                        a.stop()
                        addWarning(R.string.warn_audio_failed)
                        wr = SegmentWriter(dir, keepUs, false) { venc?.requestKeyFrame() }
                    }
                }
                writer = wr

                // Obraz: kodér podle toho, co telefon zvládne.
                val plans = Device.videoPlans(contentW, contentH, shortSide, fps, tier)
                Log.i("možnosti kodéru: " + plans.joinToString { "${it.codec ?: "výchozí"} ${it.width}x${it.height}@${it.fps}" })
                val v = VideoEncoder.open(plans, wr) { e -> pipe.post { recoverCrash(e) } }
                venc = v
                if (stopped) return
                aenc?.let { ae ->
                    try {
                        ae.start()
                    } catch (e: Exception) {
                        // Zvuk je volitelný - bez něj se nahrává dál.
                        Log.e("kodér zvuku nejde spustit", e)
                        aenc = null
                        audio?.stop()
                        audio = null
                        try { ae.stop() } catch (_: Exception) { }
                        addWarning(R.string.warn_audio_failed)
                    }
                }

                var direct = true
                if (useCamera) direct = !startWithCamera(v)
                if (direct) {
                    vd = projection.createVirtualDisplay("kine-clipper", v.width, v.height, dpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, v.inputSurface, null, pipe)
                }
                aenc?.let { audio?.start(it) }
                dm.registerDisplayListener(displayListener, pipe)
                registerThermal()
                info = describe(v)
                Log.i("snímání běží: ${v.name} ${v.width}x${v.height} @ ${v.plan.fps} fps, ${v.plan.bitrate / 1000} kb/s, " +
                    (if (comp != null) "s kamerou (OpenGL)" else "obrazovka rovnou do kodéru") + ", zvuk ${aenc?.sampleRate ?: 0} Hz")
                main.post { onRunning(this) }
            } catch (e: Throwable) {
                Log.e("start snímání", e)
                val msg = friendly(e)
                main.post { if (session === this) fail(msg) }
            }
        }

        /** Kamera přes OpenGL. False = nejde (telefon to neumí) - pokračuje se bez kamery. */
        private fun startWithCamera(v: VideoEncoder): Boolean {
            val ctx = this@CaptureService
            val low = tier == Device.Tier.LOW
            val cam = CameraFeed(ctx, settings.camera == "front", pipe, if (low) 360 else 480, if (low) 24 else 30) { st -> onCameraState(st) }
            if (cam.info == null) {
                onCameraState("missing")
                return false
            }
            val c = GlCompositor(v.inputSurface, v.width, v.height, v.plan.fps) { camLayout }
            return try {
                val size = cam.info.size
                val (screenSurface, cameraSurface) = c.start(size.width, size.height)
                comp = c
                camera = cam
                updateCamLayout()
                vd = projection.createVirtualDisplay("kine-clipper", v.width, v.height, dpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, screenSurface, null, pipe)
                cam.open(cameraSurface)
                true
            } catch (e: Exception) {
                if (vd != null) throw e
                Log.e("kamera v obraze nejde (OpenGL) - nahrává se bez ní", e)
                try { c.stop() } catch (_: Exception) { }
                comp = null
                camera = null
                cam.close()
                addWarning(R.string.warn_camera_failed)
                false
            }
        }

        private fun describe(v: VideoEncoder): String = "${v.width}×${v.height} · ${v.plan.fps} fps" + if (v.plan.hardware) "" else " · SW"

        private fun onCameraState(st: String) {
            cameraState = st
            if (st != "on") comp?.cameraStopped()
            Log.i("kamera: $st")
            main.post {
                updateNotification()
                broadcast()
            }
        }

        /** Rozměr nahrávaného obsahu se změnil (otočení, „jedna aplikace“) - případně přenastavit kodér. */
        private fun onContent(w: Int, h: Int, fromCallback: Boolean) {
            if (w <= 0 || h <= 0 || stopped) return
            if (fromCallback) contentFromCallback = true
            // Kdyby systém hlásil rozměr už zmenšený (v souřadnicích VirtualDisplay), vzít jen poměr stran.
            val longScreen = maxOf(screenW, screenH)
            val k = if (maxOf(w, h) < longScreen * 0.6) longScreen.toDouble() / maxOf(w, h) else 1.0
            contentW = (w * k).toInt()
            contentH = (h * k).toInt()
            val v = venc ?: return
            if (SizeMath.landscape(contentW, contentH) == SizeMath.landscape(v.width, v.height)) {
                // Stejná orientace a skoro stejný poměr stran - nic.
                val want = contentW.toDouble() / contentH
                val have = v.width.toDouble() / v.height
                if (abs(want - have) / have < 0.08) return
            }
            pipe.removeCallbacks(reconfigureLater)
            pipe.postDelayed(reconfigureLater, 350)
        }

        /** Nový kodér s rozměrem podle obsahu (otočení), nebo stejný znovu (obnova po pádu). */
        private fun reconfigure(force: Boolean) {
            if (stopped || reconfiguring) return
            val old = venc ?: return
            val wr = writer ?: return
            val plans = Device.videoPlans(contentW, contentH, shortSide, fps, tier)
            val best = plans.first()
            if (!force && best.width == old.width && best.height == old.height) return
            reconfiguring = true
            Log.i("obraz: ${old.width}x${old.height} -> ${best.width}x${best.height}" + if (force) " (obnova kodéru)" else "")
            try {
                val c = comp
                if (c == null) vd?.surface = null else c.pause()
                venc = null
                old.stop()
                wr.newEpoch()
                val nv = VideoEncoder.open(plans, wr) { e -> pipe.post { recoverCrash(e) } }
                venc = nv
                if (c == null) {
                    vd?.resize(nv.width, nv.height, dpi)
                    vd?.surface = nv.inputSurface
                } else {
                    c.setOutput(nv.inputSurface, nv.width, nv.height)
                    vd?.resize(nv.width, nv.height, dpi)
                }
                updateCamLayout()
                info = describe(nv)
                main.post {
                    updateNotification()
                    broadcast()
                }
            } catch (e: Throwable) {
                Log.e("přenastavení obrazu", e)
                val msg = friendly(e)
                main.post { if (session === this) fail(msg) }
            } finally {
                reconfiguring = false
            }
        }

        /** Kodér spadl za běhu: obnovit (nejvýš 3×, ne častěji než po 20 s), jinak konec s chybou. */
        private fun recoverCrash(e: Exception) {
            if (stopped) return
            val now = SystemClock.elapsedRealtime()
            if (crashRecoveries >= 3 || (crashRecoveries > 0 && now - lastRecoverAt < 20_000)) {
                val msg = getString(R.string.err_encoder, e.message ?: e::class.java.simpleName)
                main.post { if (session === this) fail(msg) }
                return
            }
            crashRecoveries++
            lastRecoverAt = now
            Log.w("obnova kodéru po pádu ($crashRecoveries)")
            reconfigure(true)
        }

        /** Kde a jak velká je kamera v obraze (podle nastavení, čipu a otočení telefonu). */
        private fun updateCamLayout() {
            val info = camera?.info
            val v = venc
            if (info == null || v == null) {
                camLayout = null
                return
            }
            val w = v.width
            val h = v.height
            val displayRotation = when (dm.getDisplay(Display.DEFAULT_DISPLAY)?.rotation) {
                android.view.Surface.ROTATION_90 -> 90
                android.view.Surface.ROTATION_180 -> 180
                android.view.Surface.ROTATION_270 -> 270
                else -> 0
            }
            val auto = if (info.front) (info.sensorOrientation + displayRotation) % 360 else (info.sensorOrientation - displayRotation + 360) % 360
            val rotation = if (settings.cameraRotation >= 0) settings.cameraRotation else auto
            val rotated = rotation % 180 != 0
            val camAspect = if (rotated) info.size.height.toFloat() / info.size.width else info.size.width.toFloat() / info.size.height
            val circle = settings.cameraShape == "circle"
            // Velikost jako podíl kratší strany (na šířku i na výšku stejně velká kamera).
            val wPx = settings.cameraScale * min(w, h) * (if (w > h) 1.25f else 1f)
            var hPx = if (circle) wPx else wPx / camAspect
            var wFrac = wPx / w
            var hFrac = hPx / h
            if (hFrac > 0.5f) {
                // Kamera nikdy víc než polovina výšky.
                val k = 0.5f / hFrac
                hFrac = 0.5f
                wFrac *= k
                hPx = hFrac * h
            }
            val margin = 0.03f * min(w, h)
            val corner = settings.cameraCorner
            val x = if (corner.endsWith("left")) margin / w else 1f - wFrac - margin / w
            val y = if (corner.startsWith("top")) margin / h else 1f - hFrac - margin / h
            camLayout = CameraLayout(x, y, wFrac, hFrac, circle, settings.cameraMirror && info.front, rotation, info.size.width, info.size.height)
        }

        private fun registerThermal() {
            val pm = getSystemService(PowerManager::class.java) ?: return
            val l = PowerManager.OnThermalStatusChangedListener { status -> pipe.post { onThermal(status) } }
            try {
                pm.addThermalStatusListener(l)
                thermal = l
            } catch (e: Exception) {
                Log.w("teplota: ${e.message}")
            }
        }

        /** Horký telefon: kamera pauza, méně snímků do kompozice, nižší datový tok; po vychladnutí zpátky. */
        private fun onThermal(status: Int) {
            if (stopped) return
            if (!hot && status >= PowerManager.THERMAL_STATUS_SEVERE) {
                hot = true
                Log.w("telefon je horký ($status) - šetřím")
                camera?.suspend()
                comp?.setMaxFps(20)
                venc?.let { it.setBitrate((it.plan.bitrate * 0.7).toInt()) }
                addWarning(R.string.warn_hot)
            } else if (hot && status <= PowerManager.THERMAL_STATUS_LIGHT) {
                hot = false
                Log.i("telefon vychladl ($status)")
                camera?.resume()
                comp?.setMaxFps(fps)
                venc?.let { it.setBitrate(it.plan.bitrate) }
                removeWarning(R.string.warn_hot)
            }
        }

        /** Konec: všechno uklidit na vlákně kine-pipe, pak [done]. */
        fun shutdown(done: () -> Unit) {
            stopped = true
            pipe.removeCallbacksAndMessages(null)
            val posted = pipe.post {
                teardown()
                done()
                thread.quitSafely()
            }
            if (!posted) done()
        }

        private fun teardown() {
            try { dm.unregisterDisplayListener(displayListener) } catch (_: Exception) { }
            thermal?.let { l -> try { getSystemService(PowerManager::class.java)?.removeThermalStatusListener(l) } catch (_: Exception) { } }
            thermal = null
            // Pořadí: zdroje -> displej -> skládání -> kodéry -> zásobník -> projekce.
            try { audio?.stop() } catch (e: Exception) { Log.w("stop zvuk: ${e.message}") }
            try { camera?.close() } catch (e: Exception) { Log.w("stop kamera: ${e.message}") }
            try { vd?.release() } catch (e: Exception) { Log.w("stop displej: ${e.message}") }
            try { comp?.stop() } catch (e: Exception) { Log.w("stop GL: ${e.message}") }
            try { venc?.stop() } catch (e: Exception) { Log.w("stop kodér: ${e.message}") }
            try { aenc?.stop() } catch (e: Exception) { Log.w("stop kodér zvuku: ${e.message}") }
            try { writer?.stop() } catch (e: Exception) { Log.w("stop zásobník: ${e.message}") }
            try { projection.unregisterCallback(callback) } catch (_: Exception) { }
            try { projection.stop() } catch (e: Exception) { Log.w("stop projekce: ${e.message}") }
            audio = null; camera = null; vd = null; comp = null; venc = null; aenc = null; writer = null
            Log.i("snímání skončilo")
        }
    }

    private fun realSize(d: Display): Point {
        val p = Point()
        @Suppress("DEPRECATION")
        d.getRealSize(p)
        return p
    }
}

/**
 * Zatřesení telefonem = klip. Dvě rázná trhnutí (zrychlení přes ~2,4 g nad tíhu) během
 * 700 ms, pak 3 s klid - jedno škubnutí při hraní to nespustí. Akcelerometr běží jen
 * během snímání (SENSOR_DELAY_GAME, pár mW).
 */
class ShakeDetector(context: Context, private val onShake: () -> Unit) : SensorEventListener {
    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private var firstPeakAt = 0L
    private var lastFiredAt = 0L
    private var above = false

    fun start() {
        val s = sensor ?: run { Log.w("zatřesení: telefon nemá akcelerometr"); return }
        sm.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME)
    }

    fun stop() {
        sm.unregisterListener(this)
    }

    override fun onSensorChanged(e: SensorEvent) {
        val g = SensorManager.GRAVITY_EARTH
        val x = e.values[0] / g
        val y = e.values[1] / g
        val z = e.values[2] / g
        val magnitude = Math.sqrt((x * x + y * y + z * z).toDouble())
        val now = System.currentTimeMillis()
        val strong = magnitude > 3.4
        if (strong && !above) {
            above = true
            if (now - firstPeakAt <= 700 && firstPeakAt > 0) {
                firstPeakAt = 0
                if (now - lastFiredAt > 3000) {
                    lastFiredAt = now
                    onShake()
                }
            } else {
                firstPeakAt = now
            }
        } else if (!strong && magnitude < 1.6) {
            above = false
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) { }
}
