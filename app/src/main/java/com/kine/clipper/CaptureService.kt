package com.kine.clipper

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.provider.Settings as SysSettings
import android.util.DisplayMetrics
import android.view.Display
import java.io.File
import java.util.UUID
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Snímání na pozadí (služba na popředí: obrazovka + kamera + mikrofon):
 * drží posledních N sekund obrazovky (s kamerou v obraze a zvukem) a na
 * klepnutí bubliny / tlačítko v oznámení z nich udělá klip do Galerie.
 */
class CaptureService : Service() {
    companion object {
        const val ACTION_START = "com.kine.clipper.START"
        const val ACTION_STOP = "com.kine.clipper.STOP"
        const val ACTION_CLIP = "com.kine.clipper.CLIP"
        const val ACTION_STATE = "com.kine.clipper.STATE"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"

        @Volatile var running = false
            private set
        @Volatile var statusText = ""
            private set
        @Volatile var cameraState = ""
            private set
        @Volatile var lastClipTitle: String? = null
            private set
    }

    private lateinit var settings: Settings
    private val main = Handler(Looper.getMainLooper())
    private var workThread: HandlerThread? = null
    private var work: Handler? = null
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var videoEncoder: VideoEncoder? = null
    private var audioEncoder: AudioEncoder? = null
    private var writer: SegmentWriter? = null
    private var compositor: GlCompositor? = null
    private var camera: CameraFeed? = null
    private var audio: AudioCapture? = null
    private var bubble: Bubble? = null
    @Volatile private var clipInProgress = false
    private var stopping = false
    private var encodeWidth = 0
    private var encodeHeight = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        workThread = HandlerThread("kine-capture").also { it.start() }
        work = Handler(workThread!!.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                if (running) return START_NOT_STICKY
                val code = intent.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE)
                @Suppress("DEPRECATION")
                val data = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                if (code == Int.MIN_VALUE || data == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                startCapture(code, data)
            }
            ACTION_CLIP -> clipNow()
            ACTION_STOP -> stopCapture()
            else -> if (!running) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startCapture(resultCode: Int, data: Intent) {
        // Android 14: nejdřív služba na popředí s typem mediaProjection, pak teprve projekce.
        val cameraOn = settings.camera != "off"
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        if (settings.microphone || settings.gameAudio) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        if (cameraOn) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        try {
            startForeground(Notifications.ID_CAPTURE, Notifications.capture(this, getString(R.string.status_starting), cameraOn), types)
        } catch (e: Exception) {
            Log.e("služba na popředí", e)
            stopSelf()
            return
        }
        running = true
        statusText = getString(R.string.status_starting)
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
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
        projection = proj
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.i("projekce ukončená systémem")
                main.post { stopCapture() }
            }
        }, main)
        try {
            setupPipeline(proj)
        } catch (e: Exception) {
            Log.e("start snímání", e)
            fail(e.message ?: "error")
        }
    }

    private fun setupPipeline(proj: MediaProjection) {
        val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val display = dm.getDisplay(Display.DEFAULT_DISPLAY)
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getRealMetrics(metrics)
        val realW = metrics.widthPixels
        val realH = metrics.heightPixels
        val short = min(realW, realH)
        val scale = min(1.0, settings.maxHeight.toDouble() / short)
        encodeWidth = even((realW * scale).roundToInt())
        encodeHeight = even((realH * scale).roundToInt())
        val fps = settings.fps
        val bitrate = settings.videoMbps * 1_000_000
        Log.i("snímání: obrazovka ${realW}x$realH -> ${encodeWidth}x$encodeHeight @ $fps fps, $bitrate b/s, kamera ${settings.camera}, zvuk hry ${settings.gameAudio}, mikrofon ${settings.microphone}")

        val withAudio = settings.gameAudio || settings.microphone
        val dir = File(cacheDir, "segments")
        val wr = SegmentWriter(dir, (settings.clipSeconds + 8) * 1_000_000L, withAudio) { videoEncoder?.requestKeyFrame() }
        wr.onSegmentClosed = { onSegmentClosed() }
        writer = wr
        val venc = VideoEncoder(encodeWidth, encodeHeight, fps, bitrate, wr)
        videoEncoder = venc
        if (withAudio) {
            audioEncoder = AudioEncoder(AudioCapture.RATE, 2, 128_000, wr)
        }

        val cameraOn = settings.camera != "off"
        val cam = if (cameraOn) CameraFeed(this, settings.camera == "front", work!!) { state -> onCameraState(state) } else null
        camera = cam
        val camSize = cam?.info?.size
        val camW = camSize?.width ?: 1280
        val camH = camSize?.height ?: 720
        val comp = GlCompositor(venc.inputSurface, encodeWidth, encodeHeight, fps) { cameraLayout(display) }
        compositor = comp
        venc.start()
        audioEncoder?.start()
        comp.start(camW, camH) { screenSurface, cameraSurface ->
            main.post {
                if (stopping) return@post
                try {
                    virtualDisplay = proj.createVirtualDisplay("kine-clipper", encodeWidth, encodeHeight, metrics.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, screenSurface, null, main)
                } catch (e: Exception) {
                    Log.e("virtuální displej", e)
                    fail(e.message ?: "display")
                    return@post
                }
                if (cam != null) {
                    if (cam.info == null) onCameraState("missing") else work!!.post { cam.open(cameraSurface) }
                }
                val ae = audioEncoder
                if (ae != null) {
                    val ac = AudioCapture(proj, settings.gameAudio, settings.microphone, ae)
                    audio = ac
                    ac.start()
                    Log.i("zvuk: hra ${ac.gameOn}, mikrofon ${ac.micOn}")
                }
                if (settings.bubble && SysSettings.canDrawOverlays(this)) {
                    bubble = Bubble(this) { clipNow() }.also { it.show() }
                }
                statusText = getString(R.string.status_running, settings.clipSeconds)
                updateNotification()
                broadcast()
            }
        }
    }

    private fun even(n: Int): Int = maxOf(2, n - (n % 2))

    /** Kamera v obraze podle nastavení: velikost (podíl šířky), roh, tvar, otočení podle čipu a telefonu. */
    private fun cameraLayout(display: Display): CameraLayout? {
        val cam = camera ?: return null
        val info = cam.info ?: return null
        if (encodeWidth <= 0 || encodeHeight <= 0) return null
        val displayRotation = when (display.rotation) {
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
        val wPx = settings.cameraScale * encodeWidth
        var hPx = if (circle) wPx else wPx / camAspect
        var wFrac = wPx / encodeWidth
        var hFrac = hPx / encodeHeight
        if (hFrac > 0.5f) {
            // Kamera nikdy víc než polovina výšky.
            val k = 0.5f / hFrac
            hFrac = 0.5f
            wFrac *= k
            hPx = hFrac * encodeHeight
        }
        val margin = 0.03f * min(encodeWidth, encodeHeight)
        val x = if (settings.cameraCorner.endsWith("left")) margin / encodeWidth else 1f - wFrac - margin / encodeWidth
        val y = if (settings.cameraCorner.startsWith("top")) margin / encodeHeight else 1f - hFrac - margin / encodeHeight
        return CameraLayout(x, y, wFrac, hFrac, circle, settings.cameraMirror && info.front, rotation, info.size.width, info.size.height)
    }

    private fun onCameraState(state: String) {
        cameraState = state
        if (state != "on") compositor?.cameraStopped()
        Log.i("kamera: $state")
        main.post {
            updateNotification()
            broadcast()
        }
    }

    /** Klip: uzavřít rozdělaný kousek a z hotových udělat soubor. */
    fun clipNow() {
        if (!running || stopping) return
        if (clipInProgress) return
        val wr = writer ?: return
        clipInProgress = true
        bubble?.flash(false)
        wr.closeSoon()
    }

    private fun onSegmentClosed() {
        if (!clipInProgress) return
        clipInProgress = false
        val wr = writer ?: return
        val segments = wr.snapshot()
        try {
            val title = ClipMaker.defaultTitle()
            val result = ClipMaker.make(this, segments, settings.clipSeconds, title)
            val clip = Clip(UUID.randomUUID().toString(), result.uri.toString(), title, System.currentTimeMillis(), result.durationMs, result.sizeBytes, result.width, result.height)
            Library.get(this).add(clip)
            lastClipTitle = title
            Log.i("klip: $title (${result.durationMs / 1000} s, ${result.sizeBytes / 1024} kB)")
            bubble?.flash(true)
            main.post {
                try {
                    getSystemService(android.app.NotificationManager::class.java)?.notify(Notifications.ID_CLIP, Notifications.clipSaved(this, getString(R.string.clip_saved_text, result.durationMs / 1000)))
                } catch (_: Exception) {
                    // bez oznámení
                }
                broadcast()
            }
        } catch (e: Exception) {
            Log.e("klip se nepovedl", e)
            main.post {
                statusText = getString(R.string.clip_failed, e.message ?: "")
                updateNotification()
                broadcast()
            }
        }
    }

    private fun updateNotification() {
        try {
            getSystemService(android.app.NotificationManager::class.java)?.notify(Notifications.ID_CAPTURE, Notifications.capture(this, statusText, cameraState == "on"))
        } catch (_: Exception) {
            // nevadí
        }
    }

    private fun broadcast() {
        sendBroadcast(Intent(ACTION_STATE).setPackage(packageName))
    }

    private fun fail(message: String) {
        statusText = message
        Log.e("snímání: $message")
        stopCapture()
    }

    fun stopCapture() {
        if (stopping) return
        stopping = true
        running = false
        bubble?.hide()
        bubble = null
        // Pořadí: zdroje -> displej -> skládání -> kodéry -> zásobník -> projekce.
        try { audio?.stop() } catch (e: Exception) { Log.w("stop zvuk: ${e.message}") }
        try { camera?.close() } catch (e: Exception) { Log.w("stop kamera: ${e.message}") }
        try { virtualDisplay?.release() } catch (e: Exception) { Log.w("stop displej: ${e.message}") }
        try { compositor?.stop() } catch (e: Exception) { Log.w("stop GL: ${e.message}") }
        try { videoEncoder?.stop() } catch (e: Exception) { Log.w("stop kodér: ${e.message}") }
        try { audioEncoder?.stop() } catch (e: Exception) { Log.w("stop kodér zvuku: ${e.message}") }
        try { writer?.stop() } catch (e: Exception) { Log.w("stop zásobník: ${e.message}") }
        try { projection?.stop() } catch (e: Exception) { Log.w("stop projekce: ${e.message}") }
        audio = null; camera = null; virtualDisplay = null; compositor = null; videoEncoder = null; audioEncoder = null; writer = null; projection = null
        cameraState = ""
        if (statusText.isEmpty() || statusText == getString(R.string.status_starting) || statusText.startsWith(getString(R.string.status_running_prefix))) statusText = getString(R.string.status_stopped)
        broadcast()
        // Klipy, které čekají na nahrání, teď (po hře) na Kine.
        if (settings.autoUpload && Library.get(this).list().any { it.uploadState == "none" }) {
            val lib = Library.get(this)
            for (c in lib.list()) if (c.uploadState == "none") lib.update(c.id) { it.uploadState = "queued" }
            UploadService.kick(this)
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (running) stopCapture()
        workThread?.quitSafely()
        super.onDestroy()
    }
}
