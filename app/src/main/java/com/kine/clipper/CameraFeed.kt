package com.kine.clipper

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Handler
import android.util.Range
import android.util.Size
import android.view.Surface

/**
 * Kamera do klipu (Camera2): přední nebo zadní, malý náhled (nejvýš [maxHeight] px,
 * starší telefony 360p) rovnou do SurfaceTexture kompozitoru, snímky z rozsahu, který
 * kamera opravdu umí (nejvýš [maxFps]). Když kameru drží jiná appka nebo se odpojí,
 * zkouší se znovu každých 10 s; klip se mezitím nahrává bez ní. [suspend] / [resume]
 * = pauza (horký telefon).
 */
class CameraFeed(context: Context, private val front: Boolean, private val handler: Handler, private val maxHeight: Int, private val maxFps: Int, private val onState: (String) -> Unit) {
    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var surface: Surface? = null
    @Volatile private var closed = false
    @Volatile private var suspended = false
    private var retry: Runnable? = null

    /** Rozměr náhledu, orientace čipu a rozsah snímků - kompozitor podle toho ořízne a otočí. */
    data class Info(val id: String, val size: Size, val sensorOrientation: Int, val front: Boolean, val fps: Range<Int>?)

    val info: Info? = pick()

    private fun pick(): Info? {
        return try {
            for (id in manager.cameraIdList) {
                val c = manager.getCameraCharacteristics(id)
                val facing = c.get(CameraCharacteristics.LENS_FACING) ?: continue
                val wantFacing = if (front) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
                if (facing != wantFacing) continue
                val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: continue
                val sizes = map.getOutputSizes(SurfaceTexture::class.java) ?: continue
                val size = chooseSize(sizes.toList())
                val fps = chooseFps(c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES))
                return Info(id, size, c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0, front, fps)
            }
            null
        } catch (e: Exception) {
            Log.e("kamera: seznam kamer", e)
            null
        }
    }

    /** Nejvýš [maxHeight] px, nejradši 16:9, jinak největší, co se vejde - kamera je v obraze malá. */
    private fun chooseSize(sizes: List<Size>): Size {
        val fitting = sizes.filter { it.height <= maxHeight && it.width <= maxHeight * 16 / 9 + 16 }
        val wide = fitting.filter { kotlin.math.abs(it.width.toFloat() / it.height - 16f / 9f) < 0.05f }
        val pool = if (wide.isNotEmpty()) wide else if (fitting.isNotEmpty()) fitting else sizes
        return if (pool === sizes) sizes.minByOrNull { it.width * it.height } ?: Size(640, 480) else pool.maxByOrNull { it.width * it.height } ?: Size(640, 480)
    }

    /** Rozsah snímků, který kamera umí: nejvyšší strop do [maxFps], s co nejnižším spodkem (tma = delší expozice). */
    private fun chooseFps(ranges: Array<Range<Int>>?): Range<Int>? {
        val ok = ranges?.filter { it.upper <= maxFps } ?: return null
        if (ok.isEmpty()) return null
        val top = ok.maxOf { it.upper }
        return ok.filter { it.upper == top }.minByOrNull { it.lower }
    }

    @SuppressLint("MissingPermission")
    fun open(target: Surface) {
        surface = target
        if (closed || suspended) return
        val i = info
        if (i == null) {
            onState("missing")
            return
        }
        onState("starting")
        try {
            manager.openCamera(i.id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (closed || suspended) {
                        camera.close()
                        return
                    }
                    device = camera
                    startSession(camera, target, i)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    if (device === camera) device = null
                    onState("error:disconnected")
                    scheduleRetry()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    if (device === camera) device = null
                    onState("error:$error")
                    scheduleRetry()
                }
            }, handler)
        } catch (e: Exception) {
            Log.e("kamera: otevření", e)
            onState("error:${e.message}")
            scheduleRetry()
        }
    }

    private fun startSession(camera: CameraDevice, target: Surface, i: Info) {
        try {
            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(target)
                if (i.fps != null) set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, i.fps)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            }
            val config = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                listOf(OutputConfiguration(target)),
                { r -> handler.post(r) },
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        if (closed || suspended) {
                            s.close()
                            return
                        }
                        session = s
                        try {
                            s.setRepeatingRequest(request.build(), null, handler)
                            onState("on")
                        } catch (e: Exception) {
                            Log.e("kamera: náhled", e)
                            onState("error:${e.message}")
                            scheduleRetry()
                        }
                    }

                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        onState("error:configure")
                        scheduleRetry()
                    }
                },
            )
            camera.createCaptureSession(config)
        } catch (e: Exception) {
            Log.e("kamera: relace", e)
            onState("error:${e.message}")
            scheduleRetry()
        }
    }

    private fun scheduleRetry() {
        if (closed || suspended) return
        retry?.let { handler.removeCallbacks(it) }
        val r = Runnable {
            if (closed || suspended) return@Runnable
            val t = surface ?: return@Runnable
            closeDevice()
            open(t)
        }
        retry = r
        handler.postDelayed(r, 10_000)
    }

    private fun closeDevice() {
        try { session?.close() } catch (_: Exception) { }
        session = null
        try { device?.close() } catch (_: Exception) { }
        device = null
    }

    /** Pauza (horký telefon): kamera se zavře, klip jede dál bez ní. */
    fun suspend() {
        if (closed || suspended) return
        suspended = true
        retry?.let { handler.removeCallbacks(it) }
        closeDevice()
        onState("paused")
    }

    fun resume() {
        if (closed || !suspended) return
        suspended = false
        surface?.let { open(it) }
    }

    fun close() {
        closed = true
        retry?.let { handler.removeCallbacks(it) }
        closeDevice()
    }
}
