package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.opengl.GLUtils
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.view.TextureView
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * Capa transparente sobre el pager que, mientras se pasa de página, dibuja la hoja doblándose como
 * papel: la esquina se levanta, la hoja se enrolla en un cilindro que avanza hasta el lomo y la
 * parte ya pasada queda volteada encima, con el reverso transparentando la página.
 *
 * Sin reflejar se pasa como un libro occidental (se levanta el borde derecho); para derecha a
 * izquierda se dibuja reflejado y se levanta el borde izquierdo.
 */
@SuppressLint("ViewConstructor")
class PageCurlView(
    context: Context,
    rtl: Boolean,
    private val backgroundColor: Int,
) : TextureView(context), TextureView.SurfaceTextureListener {

    // TextureView y no SurfaceView: es transparente desde el primer momento, así que no da un
    // pestañeo negro al aparecer ni puede tapar las páginas mientras no dibuja
    private val renderer = CurlRenderer(if (rtl) -1f else 1f)
    private val glThread = HandlerThread("PageCurl").apply { start() }
    private val glHandler = Handler(glThread.looper)
    private var egl: EglState? = null

    @Volatile private var progress = 0f

    @Volatile private var active = false

    private val drawRunnable = Runnable {
        val state = egl ?: return@Runnable
        renderer.onDrawFrame()
        state.swap()
    }

    init {
        isOpaque = false
        surfaceTextureListener = this
        // Los toques siguen llegando al pager de debajo
        isClickable = false
        isFocusable = false
    }

    /**
     * Empieza un pase: [turning] es lo que se ve antes de pasar y [under] lo que queda después.
     * Con [spread] (doble página) gira solo media pantalla sobre el lomo del centro, con la página
     * nueva en el reverso.
     */
    fun begin(turning: Bitmap, turningRect: RectF, under: Bitmap, underRect: RectF, spread: Boolean) {
        glHandler.post { renderer.setPages(turning, turningRect, under, underRect, spread) }
        active = true
        requestRender()
    }

    /** 0 = hoja plana, 1 = hoja pasada del todo. */
    fun setProgress(value: Float) {
        progress = value.coerceIn(0f, 1f)
        requestRender()
    }

    /** Vuelve a dejar ver el pager. */
    fun end() {
        active = false
        requestRender()
    }

    private fun requestRender() {
        glHandler.removeCallbacks(drawRunnable)
        glHandler.post(drawRunnable)
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        glHandler.post {
            egl = runCatching { EglState(surface) }
                .onFailure { logcat(LogPriority.ERROR, it) { "Page curl EGL setup failed" } }
                .getOrNull()
                ?: return@post
            renderer.onSurfaceCreated()
            renderer.onSurfaceChanged(width, height)
            drawRunnable.run()
        }
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        glHandler.post {
            if (egl == null) return@post
            renderer.onSurfaceChanged(width, height)
            drawRunnable.run()
        }
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        // Se libera en el hilo de dibujo cuando ya no se usa
        glHandler.post {
            egl?.release()
            egl = null
            surface.release()
        }
        return false
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        glThread.quitSafely()
    }

    /** Contexto OpenGL ES 3 propio sobre la textura de la vista. */
    private class EglState(texture: SurfaceTexture) {
        private val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        private val context: EGLContext
        private val surface: EGLSurface

        init {
            val version = IntArray(2)
            check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize" }
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            val attribs = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_DEPTH_SIZE, 16,
                EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
                EGL14.EGL_NONE,
            )
            check(EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, count, 0) && count[0] > 0) {
                "eglChooseConfig"
            }
            val config = configs[0]!!
            context = EGL14.eglCreateContext(
                display,
                config,
                EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE),
                0,
            )
            surface = EGL14.eglCreateWindowSurface(display, config, texture, intArrayOf(EGL14.EGL_NONE), 0)
            check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "eglMakeCurrent" }
        }

        fun swap() {
            EGL14.eglSwapBuffers(display, surface)
        }

        fun release() {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, surface)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglReleaseThread()
        }
    }

    private inner class CurlRenderer(private val mirror: Float) {

        private var program = 0
        private var spreadProgram = 0
        private var spread = false

        // Zona de la página dentro de la pantalla (0..1); fuera de ella la hoja no se dibuja
        private var topRect = RectF(0f, 0f, 1f, 1f)
        private var underRect = RectF(0f, 0f, 1f, 1f)

        private fun setRects(program: Int) {
            GLES30.glUniform4f(GLES30.glGetUniformLocation(program, "uTopRect"), topRect.left, topRect.top, topRect.right, topRect.bottom)
            GLES30.glUniform4f(
                GLES30.glGetUniformLocation(program, "uUnderRect"),
                underRect.left,
                underRect.top,
                underRect.right,
                underRect.bottom,
            )
        }
        private var spreadMesh: FloatBuffer? = null
        private var spreadVertexCount = 0
        private var turningTex = 0
        private var underTex = 0
        private var hasPages = false
        private var mesh: FloatBuffer? = null
        private var vertexCount = 0
        private var viewWidth = 1
        private var viewHeight = 1
        private var pw = 1f
        private val proj = FloatArray(16)
        private val view = FloatArray(16)
        private val vp = FloatArray(16)

        fun setPages(turning: Bitmap, turningRect: RectF, under: Bitmap, underRect: RectF, spread: Boolean) {
            this.spread = spread
            topRect = turningRect
            underRect = underRect
            if (turningTex == 0) {
                val ids = IntArray(2)
                GLES30.glGenTextures(2, ids, 0)
                turningTex = ids[0]
                underTex = ids[1]
            }
            upload(turningTex, turning)
            upload(underTex, under)
            // Las imágenes son del lector, que las reutiliza: no se reciclan aquí
            hasPages = true
        }

        private fun upload(id: Int, bitmap: Bitmap) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, id)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
        }

        fun onSurfaceCreated() {
            program = buildProgram(VS, FS)
            spreadProgram = buildProgram(SPREAD_VS, SPREAD_FS)
            turningTex = 0
            hasPages = false
        }

        fun onSurfaceChanged(width: Int, height: Int) {
            GLES30.glViewport(0, 0, width, height)
            viewWidth = width
            viewHeight = height
            // La hoja ocupa exactamente la pantalla
            pw = 2f * PX * width / height.toFloat()
            buildMesh()
        }

        fun onDrawFrame() {
            if (!active || !hasPages) {
                GLES30.glClearColor(0f, 0f, 0f, 0f)
                GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
                return
            }
            GLES30.glClearColor(
                Color.red(backgroundColor) / 255f,
                Color.green(backgroundColor) / 255f,
                Color.blue(backgroundColor) / 255f,
                1f,
            )
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
            GLES30.glEnable(GLES30.GL_DEPTH_TEST)
            GLES30.glDisable(GLES30.GL_CULL_FACE)

            val t = progress
            if (spread) {
                drawSpread(t)
                return
            }
            val tanV = tan(FOVY / 2f)
            val dist = PX / tanV
            val cz = pw / 2f * mirror
            Matrix.perspectiveM(proj, 0, Math.toDegrees(FOVY.toDouble()).toFloat(), viewWidth / viewHeight.toFloat(), 0.3f, 100f)
            Matrix.setLookAtM(view, 0, 0f, dist, cz, 0f, 0f, cz, 1f, 0f, 0f)
            Matrix.multiplyMM(vp, 0, proj, 0, view, 0)

            // Pliegue algo inclinado (la esquina va por delante) que se endereza al acabar
            val radius = pw * RADIUS_FACTOR
            val phi = TILT * (1f - 0.6f * t)
            val nx = sin(phi)
            val nz = cos(phi)
            val corners = floatArrayOf(-PX * nx, PX * nx, -PX * nx + pw * nz, PX * nx + pw * nz)
            val pMin = corners.min()
            val pMax = corners.max()
            val c = pMax + (pMin - PI.toFloat() * radius / 2f - radius - pMax) * t

            GLES30.glUseProgram(program)
            fun loc(name: String) = GLES30.glGetUniformLocation(program, name)
            GLES30.glUniformMatrix4fv(loc("uVP"), 1, false, vp, 0)
            GLES30.glUniform2f(loc("uN"), nx, nz)
            GLES30.glUniform1f(loc("uC"), c)
            GLES30.glUniform1f(loc("uR"), radius)
            GLES30.glUniform1f(loc("uMirror"), mirror)
            GLES30.glUniform3f(loc("uEye"), 0f, dist, cz)
            GLES30.glUniform3f(loc("uPaper"), 0.96f, 0.94f, 0.9f)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, turningTex)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, underTex)
            GLES30.glUniform1i(loc("uTop"), 0)
            GLES30.glUniform1i(loc("uUnder"), 1)
            setRects(if (spread) spreadProgram else program)

            val buffer = mesh ?: return
            buffer.position(0)
            GLES30.glEnableVertexAttribArray(0)
            GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, STRIDE * 4, buffer)
            buffer.position(3)
            GLES30.glEnableVertexAttribArray(1)
            GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, STRIDE * 4, buffer)
            buffer.position(5)
            GLES30.glEnableVertexAttribArray(2)
            GLES30.glVertexAttribPointer(2, 1, GLES30.GL_FLOAT, false, STRIDE * 4, buffer)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, vertexCount)
            (0..2).forEach { GLES30.glDisableVertexAttribArray(it) }
        }

        /** Doble página: la hoja de un lado gira sobre el lomo del centro hasta el otro lado. */
        private fun drawSpread(t: Float) {
            val dist = PX / tan(FOVY / 2f)
            Matrix.perspectiveM(proj, 0, Math.toDegrees(FOVY.toDouble()).toFloat(), viewWidth / viewHeight.toFloat(), 0.3f, 100f)
            Matrix.setLookAtM(view, 0, 0f, dist, 0f, 0f, 0f, 0f, 1f, 0f, 0f)
            Matrix.multiplyMM(vp, 0, proj, 0, view, 0)

            GLES30.glUseProgram(spreadProgram)
            fun loc(name: String) = GLES30.glGetUniformLocation(spreadProgram, name)
            GLES30.glUniformMatrix4fv(loc("uVP"), 1, false, vp, 0)
            GLES30.glUniform1f(loc("uT"), t)
            GLES30.glUniform1f(loc("uHalf"), pw / 2f)
            GLES30.glUniform1f(loc("uMirror"), mirror)
            GLES30.glUniform3f(loc("uEye"), 0f, dist, 0f)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, turningTex)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, underTex)
            GLES30.glUniform1i(loc("uTop"), 0)
            GLES30.glUniform1i(loc("uUnder"), 1)
            setRects(if (spread) spreadProgram else program)

            val buffer = spreadMesh ?: return
            buffer.position(0)
            GLES30.glEnableVertexAttribArray(0)
            GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, STRIDE * 4, buffer)
            buffer.position(3)
            GLES30.glEnableVertexAttribArray(1)
            GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, STRIDE * 4, buffer)
            buffer.position(5)
            GLES30.glEnableVertexAttribArray(2)
            GLES30.glVertexAttribPointer(2, 1, GLES30.GL_FLOAT, false, STRIDE * 4, buffer)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, spreadVertexCount)
            (0..2).forEach { GLES30.glDisableVertexAttribArray(it) }
        }

        /** Página de debajo y hoja que se dobla, subdividida para que el pliegue sea suave. */
        private fun buildMesh() {
            val data = ArrayList<Float>()
            fun grid(turn: Float, y: Float, nz: Int, nx: Int) {
                fun put(x: Float, z: Float) {
                    data += x; data += y; data += z
                    data += z / pw; data += (PX - x) / (2f * PX)
                    data += turn
                }
                for (j in 0 until nx) {
                    val x0 = -PX + 2f * PX * j / nx
                    val x1 = -PX + 2f * PX * (j + 1) / nx
                    for (i in 0 until nz) {
                        val a = pw * i / nz
                        val b = pw * (i + 1) / nz
                        put(x0, a); put(x1, a); put(x1, b)
                        put(x0, a); put(x1, b); put(x0, b)
                    }
                }
            }
            grid(0f, 0f, 24, 12)
            grid(1f, 0.003f, 56, 28)
            vertexCount = data.size / STRIDE
            mesh = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(data.toFloatArray())
                position(0)
            }

            // Doble página: centrada en el lomo (z = 0); la hoja es la mitad derecha sin reflejar
            val half = pw / 2f
            val spreadData = ArrayList<Float>()
            fun spreadGrid(turn: Float, y: Float, z0: Float, z1: Float, nz: Int) {
                fun put(x: Float, z: Float) {
                    spreadData += x; spreadData += y; spreadData += z
                    spreadData += (z + half) / pw; spreadData += (PX - x) / (2f * PX)
                    spreadData += turn
                }
                for (i in 0 until nz) {
                    val a = z0 + (z1 - z0) * i / nz
                    val b = z0 + (z1 - z0) * (i + 1) / nz
                    put(-PX, a); put(PX, a); put(PX, b)
                    put(-PX, a); put(PX, b); put(-PX, b)
                }
            }
            spreadGrid(0f, 0f, -half, half, 2)
            spreadGrid(1f, 0.003f, 0f, half, 48)
            spreadVertexCount = spreadData.size / STRIDE
            spreadMesh = ByteBuffer.allocateDirect(spreadData.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(spreadData.toFloatArray())
                position(0)
            }
        }

        private fun buildProgram(vs: String, fs: String): Int {
            fun shader(type: Int, src: String): Int {
                val s = GLES30.glCreateShader(type)
                GLES30.glShaderSource(s, src)
                GLES30.glCompileShader(s)
                val ok = IntArray(1)
                GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
                if (ok[0] == 0) logcat(LogPriority.ERROR) { "Curl shader: " + GLES30.glGetShaderInfoLog(s) }
                return s
            }
            val p = GLES30.glCreateProgram()
            GLES30.glAttachShader(p, shader(GLES30.GL_VERTEX_SHADER, vs))
            GLES30.glAttachShader(p, shader(GLES30.GL_FRAGMENT_SHADER, fs))
            GLES30.glLinkProgram(p)
            return p
        }
    }

    private companion object {
        const val PX = 3.8f
        const val FOVY = 0.62f
        const val STRIDE = 6
        const val RADIUS_FACTOR = 0.13f
        const val TILT = 0.3f

        const val VS = """#version 300 es
precision highp float;
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec2 aUv;
layout(location = 2) in float aTurn;
uniform mat4 uVP;
uniform vec2 uN;
uniform float uC, uR, uMirror;
out vec3 vN; out vec2 vUv; out vec3 vP; out float vTurn; out float vS;
void main() {
    vec2 q = vec2(aPos.x, aPos.z);
    float s = dot(q, uN) - uC;
    vec3 P = aPos;
    vec3 N = vec3(0.0, 1.0, 0.0);
    if (aTurn > 0.5 && s > 0.0) {
        float halfTurn = 3.14159265 * uR;
        if (s < halfTurn) {
            // enrollada en el cilindro del pliegue
            float th = s / uR;
            vec2 q2 = q + (uR * sin(th) - s) * uN;
            P = vec3(q2.x, aPos.y + uR * (1.0 - cos(th)), q2.y);
            N = vec3(-sin(th) * uN.x, cos(th), -sin(th) * uN.y);
        } else {
            // ya pasada: plana y volteada encima
            vec2 q2 = q + (halfTurn - 2.0 * s) * uN;
            P = vec3(q2.x, aPos.y + 2.0 * uR, q2.y);
            N = vec3(0.0, -1.0, 0.0);
        }
    }
    vS = s;
    P.z *= uMirror;
    N.z *= uMirror;
    vN = N; vUv = aUv; vP = P; vTurn = aTurn;
    gl_Position = uVP * vec4(P, 1.0);
}"""

        const val SPREAD_VS = """#version 300 es
precision highp float;
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec2 aUv;
layout(location = 2) in float aTurn;
uniform mat4 uVP;
uniform float uT, uHalf, uMirror;
out vec3 vN; out vec2 vUv; out vec3 vP; out float vTurn; out float vZ;
void main() {
    vec3 P = aPos;
    vec3 N = vec3(0.0, 1.0, 0.0);
    if (aTurn > 0.5) {
        // gira sobre el lomo y el borde libre va por delante, curvando el papel
        float zr = clamp(aPos.z / uHalf, 0.0, 1.0);
        float frac = clamp(uT + 0.35 * sin(3.14159265 * uT) * pow(zr, 1.3), 0.0, 1.0);
        float a = 3.14159265 * frac;
        float c = cos(a), s = sin(a);
        // eje un poco por encima para que al caer quede sobre la otra página
        float y = aPos.y - 0.006;
        P = vec3(aPos.x, aPos.z * s + y * c + 0.006, aPos.z * c - y * s);
        N = vec3(0.0, c, -s);
    }
    vZ = aPos.z;
    P.z *= uMirror;
    N.z *= uMirror;
    vN = N; vUv = aUv; vP = P; vTurn = aTurn;
    gl_Position = uVP * vec4(P, 1.0);
}"""

        const val SPREAD_FS = """#version 300 es
precision highp float;
in vec3 vN; in vec2 vUv; in vec3 vP; in float vTurn; in float vZ;
uniform sampler2D uTop, uUnder;
uniform vec3 uEye;
uniform float uMirror, uT;
uniform vec4 uTopRect, uUnderRect;
out vec4 o;
bool inside(vec2 uv, vec4 r) {
    return uv.x >= r.x && uv.x <= r.z && uv.y >= r.y && uv.y <= r.w;
}
void main() {
    vec3 n = normalize(vN);
    vec3 V = normalize(uEye - vP);
    bool front = dot(n, V) >= 0.0;
    if (!front) n = -n;
    vec3 col;
    if (vTurn < 0.5) {
        // un lado sigue mostrando la pareja de antes y el otro ya la de después
        vec2 uv = vUv;
        if (uMirror < 0.0) uv.x = 1.0 - uv.x;
        bool before = vZ < 0.0;
        col = before ? texture(uTop, uv).rgb : texture(uUnder, uv).rgb;
        // sombra de la hoja levantada, más fuerte junto al lomo y solo sobre la página
        if (inside(uv, before ? uTopRect : uUnderRect)) {
            col *= 1.0 - 0.3 * sin(3.14159265 * uT) * exp(-abs(vZ) * 0.9);
        }
    } else {
        // anverso: la página que se va; reverso: la página nueva que cae al otro lado
        vec2 uv = front ? vUv : vec2(1.0 - vUv.x, vUv.y);
        if (uMirror < 0.0) uv.x = 1.0 - uv.x;
        // solo se dobla la página, no el fondo de alrededor
        if (!inside(uv, front ? uTopRect : uUnderRect)) discard;
        vec3 base = front ? texture(uTop, uv).rgb : texture(uUnder, uv).rgb;
        float shade = mix(0.55, 1.0, max(n.y, 0.0));
        vec3 L = normalize(vec3(-0.3, 1.0, 0.4 * uMirror));
        float spec = pow(max(dot(n, normalize(L + V)), 0.0), 30.0) * 0.15 * (1.0 - n.y);
        col = base * shade + spec;
    }
    o = vec4(col, 1.0);
}"""

        const val FS = """#version 300 es
precision highp float;
in vec3 vN; in vec2 vUv; in vec3 vP; in float vTurn; in float vS;
uniform sampler2D uTop, uUnder;
uniform vec3 uEye, uPaper;
uniform float uMirror, uR;
uniform vec4 uTopRect, uUnderRect;
out vec4 o;
bool inside(vec2 uv, vec4 r) {
    return uv.x >= r.x && uv.x <= r.z && uv.y >= r.y && uv.y <= r.w;
}
void main() {
    vec3 n = normalize(vN);
    vec3 V = normalize(uEye - vP);
    bool front = dot(n, V) >= 0.0;
    if (!front) n = -n;
    vec2 uv = vUv;
    if (uMirror < 0.0) uv.x = 1.0 - uv.x;
    vec3 col;
    if (vTurn < 0.5) {
        // sombra de la hoja levantada sobre la página de debajo
        float sh = vS > 0.0 && inside(uv, uUnderRect) ? 0.45 * exp(-vS / (2.5 * uR)) : 0.0;
        col = texture(uUnder, uv).rgb * (1.0 - sh);
    } else {
        // solo se dobla la página, no el fondo de alrededor
        if (!inside(uv, uTopRect)) discard;
        // el reverso es papel con la página transparentándose
        vec3 base = front ? texture(uTop, uv).rgb : mix(uPaper, texture(uTop, uv).rgb, 0.15);
        float shade = mix(0.5, 1.0, max(n.y, 0.0));
        vec3 L = normalize(vec3(-0.3, 1.0, 0.4 * uMirror));
        float spec = pow(max(dot(n, normalize(L + V)), 0.0), 30.0) * 0.18 * (1.0 - n.y);
        col = base * shade + spec;
    }
    o = vec4(col, 1.0);
}"""
    }
}
