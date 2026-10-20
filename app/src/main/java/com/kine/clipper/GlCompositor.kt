package com.kine.clipper

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.cos
import kotlin.math.sin

/** Kde a jak je kamera v obraze (podíly výsledného obrazu, levý horní roh; stejně jako v appce do PC). */
data class CameraLayout(
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
    val circle: Boolean,
    val mirror: Boolean,
    /** O kolik stupňů otočit obraz kamery (po směru hodin), ať je vzhůru nohama správně. */
    val rotation: Int,
    /** Rozměr obrazu kamery (px), ať se ořízne bez deformace. */
    val cameraWidth: Int,
    val cameraHeight: Int,
)

/**
 * Skládání obrazu, jen když je v klipu kamera: obrazovka (z VirtualDisplay) + kamera
 * (Camera2) do jednoho snímku pro kodér (jeho vstupní Surface). OpenGL ES 2 na vlastním
 * vlákně; oba zdroje přes SurfaceTexture. Kreslí se, jen když přijde nový snímek,
 * nejvýš `fps` za sekundu. Bez kamery se OpenGL vůbec nespouští (obrazovka jde rovnou
 * do kodéru - méně práce pro starší telefony). Výstup jde vyměnit za běhu ([setOutput])
 * - po otočení telefonu je nový kodér s otočeným rozměrem.
 */
class GlCompositor(private var output: Surface, width: Int, height: Int, fps: Int, private val layout: () -> CameraLayout?) {
    private val thread = HandlerThread("kine-gl")
    private lateinit var handler: Handler
    @Volatile private var width = width
    @Volatile private var height = height
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var config: EGLConfig? = null
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var program = 0
    private var aPos = 0
    private var aTex = 0
    private var aLocal = 0
    private var uTex = 0
    private var uCircle = 0
    private val textures = IntArray(2)
    private var screenTexture: SurfaceTexture? = null
    private var cameraTexture: SurfaceTexture? = null
    private var screenSurface: Surface? = null
    private var cameraSurface: Surface? = null
    private var screenNew = false
    private var cameraNew = false
    private var cameraLive = false
    private var renderPosted = false
    private var lastRenderNs = 0L
    @Volatile private var frameNs = 1_000_000_000L / fps.coerceIn(10, 60)
    private val matrix = FloatArray(16)
    private val positions = floatBuffer(8)
    private val texCoords = floatBuffer(8)
    private val locals = floatBuffer(8).apply { put(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)); position(0) }
    private val quad = FloatArray(8)
    private val vIn = FloatArray(4)
    private val vOut = FloatArray(4)
    @Volatile private var running = false
    /** Výstup se zrovna vyměňuje - snímky se jen odeberou, nekreslí se. */
    private var paused = false

    /** Spustí GL vlákno a počká na povrchy pro obrazovku a kameru (nejvýš [timeoutMs]). */
    fun start(cameraWidth: Int, cameraHeight: Int, timeoutMs: Long = 4000): Pair<Surface, Surface> {
        thread.start()
        handler = Handler(thread.looper)
        running = true
        val done = CountDownLatch(1)
        var error: Exception? = null
        handler.post {
            try {
                initEgl()
                initGl()
                GLES20.glGenTextures(2, textures, 0)
                for (t in textures) {
                    GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, t)
                    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
                }
                val screen = SurfaceTexture(textures[0]).apply {
                    setDefaultBufferSize(width, height)
                    setOnFrameAvailableListener({ screenNew = true; scheduleRender() }, handler)
                }
                val camera = SurfaceTexture(textures[1]).apply {
                    setDefaultBufferSize(cameraWidth, cameraHeight)
                    setOnFrameAvailableListener({ cameraNew = true; cameraLive = true; scheduleRender() }, handler)
                }
                screenTexture = screen
                cameraTexture = camera
                screenSurface = Surface(screen)
                cameraSurface = Surface(camera)
            } catch (e: Exception) {
                error = e
            }
            done.countDown()
        }
        if (!done.await(timeoutMs, TimeUnit.MILLISECONDS)) throw IllegalStateException("OpenGL start timeout")
        error?.let { throw it }
        return screenSurface!! to cameraSurface!!
    }

    /** Kamera přestala dávat snímky (vypnutá, odpojená, horký telefon) - do obrazu se nekreslí. */
    fun cameraStopped() {
        if (!running) return
        handler.post {
            cameraLive = false
            screenNew = true
            scheduleRender()
        }
    }

    /** Méně snímků za sekundu (horký telefon). */
    fun setMaxFps(fps: Int) {
        frameNs = 1_000_000_000L / fps.coerceIn(10, 60)
    }

    /** Přestat kreslit do kodéru (před jeho výměnou). Počká, než doběhne rozkreslený snímek. */
    fun pause() {
        if (!running) return
        val done = CountDownLatch(1)
        handler.post {
            paused = true
            done.countDown()
        }
        done.await(1500, TimeUnit.MILLISECONDS)
    }

    /** Nový výstup (kodér s jiným rozměrem po otočení) a zase kreslit. */
    fun setOutput(surface: Surface, w: Int, h: Int) {
        if (!running) return
        val done = CountDownLatch(1)
        var error: Exception? = null
        handler.post {
            try {
                val cfg = config ?: throw IllegalStateException("EGL config")
                val fresh = EGL14.eglCreateWindowSurface(display, cfg, surface, intArrayOf(EGL14.EGL_NONE), 0)
                if (fresh === EGL14.EGL_NO_SURFACE) throw IllegalStateException("eglCreateWindowSurface 0x" + Integer.toHexString(EGL14.eglGetError()))
                if (!EGL14.eglMakeCurrent(display, fresh, fresh, context)) throw IllegalStateException("eglMakeCurrent")
                if (eglSurface !== EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, eglSurface)
                eglSurface = fresh
                output = surface
                width = w
                height = h
                screenTexture?.setDefaultBufferSize(w, h)
                lastRenderNs = 0L
                paused = false
            } catch (e: Exception) {
                error = e
            }
            done.countDown()
        }
        if (!done.await(2000, TimeUnit.MILLISECONDS)) throw IllegalStateException("OpenGL output timeout")
        error?.let { throw it }
    }

    private fun scheduleRender() {
        if (!running || renderPosted) return
        renderPosted = true
        val wait = (lastRenderNs + frameNs - System.nanoTime()) / 1_000_000L
        if (wait > 0) handler.postDelayed({ render() }, wait) else handler.post { render() }
    }

    private fun render() {
        renderPosted = false
        if (!running) return
        val screen = screenTexture ?: return
        val camera = cameraTexture ?: return
        if (!screenNew && !cameraNew) return
        try {
            var pts = System.nanoTime()
            if (screenNew) {
                screenNew = false
                screen.updateTexImage()
                if (screen.timestamp > 0) pts = screen.timestamp
            }
            if (cameraNew) {
                cameraNew = false
                camera.updateTexImage()
            }
            // Při výměně kodéru se snímky jen odeberou (ať se VirtualDisplay nezasekne).
            if (paused) return
            // Časy musí růst (kodér je bere jako čas snímku).
            if (pts <= lastRenderNs) pts = lastRenderNs + 1_000_000L
            lastRenderNs = pts
            val w = width
            val h = height
            GLES20.glViewport(0, 0, w, h)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            screen.getTransformMatrix(matrix)
            drawQuad(textures[0], matrix, -1f, -1f, 1f, 1f, 0, false, 1f, 1f, false)
            val cam = if (cameraLive) layout() else null
            if (cam != null && cam.w > 0.02f && cam.h > 0.02f) {
                camera.getTransformMatrix(matrix)
                val left = cam.x * 2f - 1f
                val right = (cam.x + cam.w) * 2f - 1f
                val top = 1f - cam.y * 2f
                val bottom = 1f - (cam.y + cam.h) * 2f
                // Ořez bez deformace: poměr kamery (po otočení) vs. poměr rámečku v px.
                val rotated = cam.rotation % 180 != 0
                val camAspect = if (rotated) cam.cameraHeight.toFloat() / cam.cameraWidth else cam.cameraWidth.toFloat() / cam.cameraHeight
                val boxAspect = (cam.w * w) / (cam.h * h)
                var sx = 1f
                var sy = 1f
                if (camAspect > boxAspect) sx = boxAspect / camAspect else sy = camAspect / boxAspect
                drawQuad(textures[1], matrix, left, bottom, right, top, cam.rotation, cam.mirror, sx, sy, cam.circle)
            }
            EGLExt.eglPresentationTimeANDROID(display, eglSurface, pts)
            EGL14.eglSwapBuffers(display, eglSurface)
        } catch (e: Exception) {
            Log.e("GL: kreslení", e)
        }
    }

    /**
     * Čtverec `left/bottom/right/top` v souřadnicích GL; souřadnice textury se
     * spočítají tady: ořez kolem středu (sx, sy), zrcadlo, otočení, pak
     * transformace SurfaceTexture. Bez nových polí na každý snímek (méně práce pro GC).
     */
    private fun drawQuad(texture: Int, texMatrix: FloatArray, left: Float, bottom: Float, right: Float, top: Float, rotation: Int, mirror: Boolean, sx: Float, sy: Float, circle: Boolean) {
        quad[0] = left; quad[1] = bottom; quad[2] = right; quad[3] = bottom
        quad[4] = left; quad[5] = top; quad[6] = right; quad[7] = top
        positions.clear()
        positions.put(quad)
        positions.position(0)
        texCoords.clear()
        val rad = Math.toRadians(rotation.toDouble())
        val c = cos(rad).toFloat()
        val s = sin(rad).toFloat()
        for (i in 0 until 4) {
            var u = (BASE[i * 2] - 0.5f) * sx
            val t = (BASE[i * 2 + 1] - 0.5f) * sy
            if (mirror) u = -u
            // Otočení po směru hodin v souřadnicích obrazu (osa t míří nahoru).
            vIn[0] = u * c + t * s + 0.5f
            vIn[1] = -u * s + t * c + 0.5f
            vIn[2] = 0f
            vIn[3] = 1f
            android.opengl.Matrix.multiplyMV(vOut, 0, texMatrix, 0, vIn, 0)
            texCoords.put(vOut[0])
            texCoords.put(vOut[1])
        }
        texCoords.position(0)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        GLES20.glUniform1i(uTex, 0)
        GLES20.glUniform1i(uCircle, if (circle) 1 else 0)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, positions)
        GLES20.glEnableVertexAttribArray(aTex)
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 0, texCoords)
        GLES20.glEnableVertexAttribArray(aLocal)
        GLES20.glVertexAttribPointer(aLocal, 2, GLES20.GL_FLOAT, false, 0, locals)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisableVertexAttribArray(aTex)
        GLES20.glDisableVertexAttribArray(aLocal)
    }

    private fun initEgl() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display === EGL14.EGL_NO_DISPLAY) throw RuntimeException("EGL display")
        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) throw RuntimeException("eglInitialize")
        // RGBA + „recordable“ (pro kodér); starší čipy někdy chtějí bez alfy, úplně staré bez recordable.
        val tries = listOf(
            intArrayOf(EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL_RECORDABLE_ANDROID, 1, EGL14.EGL_NONE),
            intArrayOf(EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL_RECORDABLE_ANDROID, 1, EGL14.EGL_NONE),
            intArrayOf(EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_NONE),
        )
        var lastError = "eglChooseConfig"
        for (attribs in tries) {
            val configs = arrayOfNulls<EGLConfig>(1)
            val num = IntArray(1)
            if (!EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, num, 0) || num[0] == 0) continue
            val cfg = configs[0] ?: continue
            val ctx = EGL14.eglCreateContext(display, cfg, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
            if (ctx === EGL14.EGL_NO_CONTEXT) { lastError = "eglCreateContext"; continue }
            val surf = EGL14.eglCreateWindowSurface(display, cfg, output, intArrayOf(EGL14.EGL_NONE), 0)
            if (surf === EGL14.EGL_NO_SURFACE) {
                lastError = "eglCreateWindowSurface 0x" + Integer.toHexString(EGL14.eglGetError())
                EGL14.eglDestroyContext(display, ctx)
                continue
            }
            if (!EGL14.eglMakeCurrent(display, surf, surf, ctx)) {
                lastError = "eglMakeCurrent"
                EGL14.eglDestroySurface(display, surf)
                EGL14.eglDestroyContext(display, ctx)
                continue
            }
            config = cfg
            context = ctx
            eglSurface = surf
            return
        }
        throw RuntimeException(lastError)
    }

    private fun initGl() {
        val vs = compile(GLES20.GL_VERTEX_SHADER, VERTEX)
        val fs = compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT)
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vs)
        GLES20.glAttachShader(program, fs)
        GLES20.glLinkProgram(program)
        val linked = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0)
        if (linked[0] == 0) throw RuntimeException("GL program: " + GLES20.glGetProgramInfoLog(program))
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        aTex = GLES20.glGetAttribLocation(program, "aTex")
        aLocal = GLES20.glGetAttribLocation(program, "aLocal")
        uTex = GLES20.glGetUniformLocation(program, "uTex")
        uCircle = GLES20.glGetUniformLocation(program, "uCircle")
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) throw RuntimeException("GL shader: " + GLES20.glGetShaderInfoLog(shader))
        return shader
    }

    fun stop() {
        running = false
        if (!thread.isAlive) return
        handler.post {
            try {
                screenTexture?.release()
                cameraTexture?.release()
                screenSurface?.release()
                cameraSurface?.release()
                if (display !== EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                    if (eglSurface !== EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, eglSurface)
                    if (context !== EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                    EGL14.eglTerminate(display)
                }
            } catch (e: Exception) {
                Log.w("GL: úklid: ${e.message}")
            }
            thread.quitSafely()
        }
        thread.join(2000)
    }

    companion object {
        private const val EGL_RECORDABLE_ANDROID = 0x3142
        private val BASE = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)
        private const val VERTEX = """
            attribute vec2 aPos;
            attribute vec2 aTex;
            attribute vec2 aLocal;
            varying vec2 vTex;
            varying vec2 vLocal;
            void main() {
              gl_Position = vec4(aPos, 0.0, 1.0);
              vTex = aTex;
              vLocal = aLocal;
            }
        """
        private const val FRAGMENT = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTex;
            varying vec2 vLocal;
            uniform samplerExternalOES uTex;
            uniform int uCircle;
            void main() {
              if (uCircle == 1 && distance(vLocal, vec2(0.5, 0.5)) > 0.5) discard;
              gl_FragColor = texture2D(uTex, vTex);
            }
        """

        private fun floatBuffer(count: Int): FloatBuffer = ByteBuffer.allocateDirect(count * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    }
}
