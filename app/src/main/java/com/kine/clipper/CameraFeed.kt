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
 * Kamera do klipu (Camera2): přední nebo zadní, náhled 16:9 do 720p rovnou do
 * SurfaceTexture kompozitoru. Když kameru drží jiná appka nebo se odpojí,
 * zkouší se znovu každých 10 s; klip se mezitím nahrává bez ní.
 */
class CameraFeed(context: Context, private val front: Boolean, private val handler: Handler, private val onState: (String) -> Unit) {
    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var surface: Surface? = null
    @Volatile private var closed = false
    private var retry: Runnable? = null

    /** Rozměr náhledu a orientace čipu - kompozitor podle toho ořízne a otočí. */
    data class Info(val id: String, val size: Size, val sensorOrientation: Int, val front: Boolean)

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
                return Info(id, size, c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0, front)
            }
            null
        } catch (e: Exception) {
            Log.e("kamera: seznam kamer", e)
            null
        }
    }

    /** Nejvýš 720p, nejradši 16:9, jinak největší, co se vejde (kamera je v obraze malá). */
    private fun chooseSize(sizes: List<Size>): Size {
        val fitting = sizes.filter { it.height <= 720 && it.width <= 1280 }
        val wide = fitting.filter { kotlin.math.abs(it.width.toFloat() / it.height - 16f / 9f) < 0.05f }
        val pool = if (wide.isNotEmpty()) wide else if (fitting.isNotEmpty()) fitting else sizes
        return pool.maxByOrNull { it.width * it.height } ?: Size(1280, 720)
    }

    @SuppressLint("MissingPermission")
    fun open(target: Surface) {
        surface = target
        val i = info
        if (i == null) {
            onState("missing")
            return
        }
        onState("starting")
        try {
            manager.openCamera(i.id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (closed) {
                        camera.close()
                        return
                    }
                    device = camera
                    startSession(camera, target)
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

    private fun startSession(camera: CameraDevice, target: Surface) {
        try {
            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(target)
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(24, 30))
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            }
            val config = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                listOf(OutputConfiguration(target)),
                { r -> handler.post(r) },
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        if (closed) {
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
        if (closed) return
        retry?.let { handler.removeCallbacks(it) }
        val r = Runnable {
            if (closed) return@Runnable
            val t = surface ?: return@Runnable
            closeDevice()
            open(t)
        }
        retry = r
        handler.postDelayed(r, 10_000)
    }

    private fun closeDevice() {
        try {
            session?.close()
        } catch (_: Exception) {
            // nevadí
        }
        session = null
        try {
            device?.close()
        } catch (_: Exception) {
            // nevadí
        }
        device = null
    }

    fun close() {
        closed = true
        retry?.let { handler.removeCallbacks(it) }
        closeDevice()
    }
}
