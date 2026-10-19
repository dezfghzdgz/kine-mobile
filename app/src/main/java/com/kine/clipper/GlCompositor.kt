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
 * Skládání obrazu: obrazovka (z VirtualDisplay) + kamera (Camera2) do jednoho
 * snímku pro kodér (jeho vstupní Surface). OpenGL ES 2 na vlastním vlákně;
 * oba zdroje jdou přes SurfaceTexture (externí textury). Kreslí se, když
 * přijde nový snímek obrazovky nebo kamery, nejvýš `fps` za sekundu - když se
 * nic nehýbe, nekreslí se nic a kodér si poslední snímek opakuje sám.
 */
class GlCompositor(private val output: Surface, private val width: Int, private val height: Int, fps: Int, private val layout: () -> CameraLayout?) {
    private val thread = HandlerThread("kine-gl")
    private lateinit var handler: Handler
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
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
    /** Sem kreslí VirtualDisplay. */
    @Volatile var screenSurface: Surface? = null
        private set
    /** Sem kreslí kamera (Camera2). */
    @Volatile var cameraSurface: Surface? = null
        private set
    @Volatile private var screenNew = false
    @Volatile private var cameraNew = false
    @Volatile private var cameraLive = false
    private var renderPosted = false
    private var lastRenderNs = 0L
    private val frameNs = 1_000_000_000L / fps.coerceIn(10, 60)
    private val matrix = FloatArray(16)
    private val positions = floatBuffer(8)
    private val texCoords = floatBuffer(8)
    private val locals = floatBuffer(8).apply { put(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)); position(0) }
    @Volatile private var running = false

    /** Spustí GL vlákno; `onReady` dostane povrchy pro obrazovku a kameru (na GL vlákně). */
    fun start(cameraWidth: Int, cameraHeight: Int, onReady: (Surface, Surface) -> Unit) {
        thread.start()
        handler = Handler(thread.looper)
        running = true
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
                onReady(screenSurface!!, cameraSurface!!)
            } catch (e: Exception) {
                Log.e("GL: start selhal", e)
            }
        }
    }

    /** Kamera přestala dávat snímky (vypnutá, odpojená) - do obrazu se nekreslí. */
    fun cameraStopped() {
        cameraLive = false
        scheduleRender()
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
            // Časy musí růst (kodér je bere jako čas snímku).
            if (pts <= lastRenderNs) pts = lastRenderNs + 1_000_000L
            lastRenderNs = pts
            GLES20.glViewport(0, 0, width, height)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            screen.getTransformMatrix(matrix)
            drawQuad(textures[0], matrix, -1f, -1f, 1f, 1f, 0, false, 1f, 1f, false)
            val cam = layout()
            if (cam != null && cameraLive && cam.w > 0.02f && cam.h > 0.02f) {
                camera.getTransformMatrix(matrix)
                val left = cam.x * 2f - 1f
                val right = (cam.x + cam.w) * 2f - 1f
                val top = 1f - cam.y * 2f
                val bottom = 1f - (cam.y + cam.h) * 2f
                // Ořez bez deformace: poměr kamery (po otočení) vs. poměr rámečku v px.
                val rotated = cam.rotation % 180 != 0
                val camAspect = if (rotated) cam.cameraHeight.toFloat() / cam.cameraWidth else cam.cameraWidth.toFloat() / cam.cameraHeight
                val boxAspect = (cam.w * width) / (cam.h * height)
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
     * transformace SurfaceTexture.
     */
    private fun drawQuad(texture: Int, texMatrix: FloatArray, left: Float, bottom: Float, right: Float, top: Float, rotation: Int, mirror: Boolean, sx: Float, sy: Float, circle: Boolean) {
        positions.clear()
        positions.put(floatArrayOf(left, bottom, right, bottom, left, top, right, top))
        positions.position(0)
        texCoords.clear()
        val rad = Math.toRadians(rotation.toDouble())
        val c = cos(rad).toFloat()
        val s = sin(rad).toFloat()
        val base = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)
        val v = FloatArray(4)
        val out = FloatArray(4)
        for (i in 0 until 4) {
            var u = (base[i * 2] - 0.5f) * sx
            var t = (base[i * 2 + 1] - 0.5f) * sy
            if (mirror) u = -u
            // Otočení po směru hodin v souřadnicích obrazu (osa t míří nahoru).
            val ru = u * c + t * s
            val rt = -u * s + t * c
            v[0] = ru + 0.5f
            v[1] = rt + 0.5f
            v[2] = 0f
            v[3] = 1f
            android.opengl.Matrix.multiplyMV(out, 0, texMatrix, 0, v, 0)
            texCoords.put(out[0])
            texCoords.put(out[1])
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
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        if (!EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, num, 0) || num[0] == 0) throw RuntimeException("eglChooseConfig")
        val config = configs[0]!!
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        if (context === EGL14.EGL_NO_CONTEXT) throw RuntimeException("eglCreateContext")
        eglSurface = EGL14.eglCreateWindowSurface(display, config, output, intArrayOf(EGL14.EGL_NONE), 0)
        if (eglSurface === EGL14.EGL_NO_SURFACE) throw RuntimeException("eglCreateWindowSurface")
        if (!EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) throw RuntimeException("eglMakeCurrent")
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
