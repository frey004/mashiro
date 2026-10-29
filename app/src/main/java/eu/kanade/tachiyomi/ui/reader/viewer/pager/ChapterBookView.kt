package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import android.os.SystemClock
import android.view.MotionEvent
import androidx.core.graphics.scale
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tan

/**
 * Paso de capítulo como un manga en 3D: la página actual se aleja hasta ser la de un libro abierto
 * sobre la mesa, el libro se cierra hoja tras hoja, sale de escena, entra otro cerrado que se abre
 * en el capítulo siguiente y la cámara baja hasta su página, que coincide con la del lector.
 *
 * Sin girar, el libro se cierra hacia la izquierda con el lomo a la derecha (manga japonés);
 * para izquierda a derecha se dibuja reflejado.
 */
@SuppressLint("ViewConstructor")
class ChapterBookView(
    context: Context,
    rtl: Boolean,
    backgroundColor: Int,
    /** Solo entra un manga cerrado y se abre (al abrir un capítulo desde fuera del lector). */
    private val openOnly: Boolean,
    currentPage: Bitmap?,
    otherPage: Bitmap?,
    /** Página actual y la de al lado del capítulo nuevo; null si aún no están listas. */
    private val requestNewPages: () -> Pair<Bitmap?, Bitmap?>?,
    private val onFinished: () -> Unit,
) : GLSurfaceView(context) {

    private val renderer = BookRenderer(
        mirror = if (rtl) 1f else -1f,
        background = backgroundColor,
        oldPages = currentPage?.let { it to otherPage },
    )

    private val requestRunnable = object : Runnable {
        override fun run() {
            if (finished) return
            val pages = requestNewPages()
            if (pages != null) renderer.deliverNewPages(pages) else postDelayed(this, RETRY_DELAY)
        }
    }

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        holder.setFormat(PixelFormat.TRANSLUCENT)
        setZOrderMediaOverlay(true)
        setRenderer(renderer)
        renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
    }

    fun setCover(cover: Bitmap) {
        queueEvent { renderer.setCover(cover) }
    }

    /** Páginas del capítulo para las hojas, en orden de lectura desde la que queda a la vista. */
    fun setSheetPages(newBook: Boolean, pages: List<Bitmap>) {
        queueEvent { renderer.setSheetPages(newBook, pages) }
    }

    /** Tocar salta la animación. */
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP) finish()
        return true
    }

    private var finished = false

    private fun finish() {
        if (finished) return
        finished = true
        removeCallbacks(requestRunnable)
        onFinished()
    }

    private inner class BookRenderer(
        private val mirror: Float,
        private val background: Int,
        oldPages: Pair<Bitmap, Bitmap?>?,
    ) : GLSurfaceView.Renderer {

        @Volatile private var pendingOld: Pair<Bitmap, Bitmap?>? = oldPages

        @Volatile private var pendingNew: Pair<Bitmap?, Bitmap?>? = null

        @Volatile private var pendingCover: Bitmap? = null
        private val pendingSheets = ArrayDeque<Pair<Boolean, Bitmap>>()
        private var newPagesRequested = false
        private var gaveUp = false
        private var newPagesShown = false

        private var program = 0
        private var shadowProgram = 0
        private var objects = emptyList<BookObject>()

        private var texPaper = 0
        private var texEdge = 0
        private var texCover = 0
        private var texEndpaper = 0
        private var texRight = 0
        private var texLeft = 0
        private val oldSheetTex = mutableListOf<Int>()
        private val newSheetTex = mutableListOf<Int>()

        private var viewWidth = 1
        private var viewHeight = 1
        private var pw = 3f // ancho de una página, ajustado a la pantalla en onSurfaceChanged

        private var startTime = 0L
        private var pausedFor = 0L
        private var pauseStart = 0L

        private val proj = FloatArray(16)
        private val view = FloatArray(16)
        private val vp = FloatArray(16)
        private val quad = floatBuffer(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            program = buildProgram(BOOK_VS, BOOK_FS)
            shadowProgram = buildProgram(SHADOW_VS, SHADOW_FS)
            texPaper = solidTexture(PAPER)
            texEdge = solidTexture(EDGE)
            texEndpaper = solidTexture(Color.rgb(196, 188, 176))
            texCover = texEndpaper
            texRight = texPaper
            texLeft = texPaper
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            GLES30.glViewport(0, 0, width, height)
            viewWidth = width
            viewHeight = height
            // La página tiene la proporción de la pantalla para que al final encaje con el lector
            pw = 2f * PX * width / height.toFloat()
            objects = buildBook()
        }

        fun setCover(cover: Bitmap) {
            pendingCover = cover
        }

        fun setSheetPages(newBook: Boolean, pages: List<Bitmap>) {
            pages.forEach { pendingSheets.addLast(newBook to it) }
        }

        fun deliverNewPages(pages: Pair<Bitmap?, Bitmap?>) {
            pendingNew = pages
        }

        override fun onDrawFrame(gl: GL10?) {
            uploadPending()
            val now = SystemClock.uptimeMillis()
            if (startTime == 0L) startTime = now

            // El modo de solo abrir empieza con el manga nuevo entrando en escena
            val origin = if (openOnly) SWAP_MID else 0L
            var tm = now - startTime - pausedFor + origin
            // Antes de abrir espera a tener las páginas del capítulo nuevo
            if (tm >= SWAP_END && !newPagesShown && !gaveUp) {
                val waiting = pendingNew == null
                if (waiting) {
                    if (pauseStart == 0L) pauseStart = now
                    if (now - pauseStart < if (openOnly) MAX_WAIT_OPEN else MAX_WAIT) tm = SWAP_END else gaveUp = true
                }
                if ((!waiting || gaveUp) && pauseStart != 0L) {
                    pausedFor += now - pauseStart
                    pauseStart = 0L
                    tm = now - startTime - pausedFor + origin
                }
            }
            if (!newPagesRequested && (openOnly || tm >= CLOSE_END)) {
                newPagesRequested = true
                post(requestRunnable)
            }

            // t: 0 abierto, 1 cerrado. zoom: 1 = la página llena la pantalla
            val t: Float
            val zoom: Float
            var offset = 0f
            val exit = pw * 2.6f
            when {
                tm < CLOSE_END -> {
                    zoom = 1f - ease(tm / ZOOM_OUT.toFloat())
                    t = ((tm - CLOSE_START) / CLOSE_LEN.toFloat()).coerceIn(0f, 1f)
                }
                tm < SWAP_END -> {
                    t = 1f
                    zoom = 0f
                    val k = (tm - CLOSE_END) / SWAP_LEN.toFloat()
                    offset = if (k < 0.5f) {
                        exit * ease(k * 2f)
                    } else {
                        showNewPages()
                        -exit * (1f - ease((k - 0.5f) * 2f))
                    }
                }
                tm < OPEN_END -> {
                    showNewPages()
                    val k = tm - SWAP_END
                    t = 1f - (k / OPEN_LEN.toFloat()).coerceIn(0f, 1f)
                    zoom = ease((k - OPEN_LEN + 250f) / ZOOM_IN.toFloat())
                }
                else -> {
                    post { finish() }
                    t = 0f
                    zoom = 1f
                }
            }
            draw(t, zoom, offset)
        }

        private fun showNewPages() {
            if (newPagesShown) return
            val (current, other) = pendingNew ?: return
            newPagesShown = true
            // Las texturas viejas se liberan con el contexto al terminar la animación
            current?.let { texRight = bitmapTexture(it) }
            texLeft = other?.let(::bitmapTexture) ?: texPaper
        }

        private fun uploadPending() {
            pendingOld?.let { (current, other) ->
                pendingOld = null
                texRight = bitmapTexture(current)
                texLeft = other?.let(::bitmapTexture) ?: texPaper
            }
            pendingCover?.let { cover ->
                pendingCover = null
                texCover = bitmapTexture(cropToAspect(cover, pw / (2f * PX)))
                texEndpaper = solidTexture(averageColor(cover))
            }
            // Pocas por fotograma para no dar tirones
            repeat(2) {
                val (newBook, bitmap) = pendingSheets.removeFirstOrNull() ?: return
                (if (newBook) newSheetTex else oldSheetTex) += bitmapTexture(bitmap)
                bitmap.recycle()
            }
        }

        /** Textura de la página [index] del manga que se ve ahora (0 = la que queda a la vista). */
        private fun sheetTexture(index: Int): Int {
            val list = if (newPagesShown) newSheetTex else oldSheetTex
            if (list.isEmpty()) return if (index % 2 == 0) texLeft else texRight
            return list[index % list.size]
        }

        private fun draw(t: Float, zoom: Float, offset: Float) {
            GLES30.glClearColor(
                Color.red(background) / 255f,
                Color.green(background) / 255f,
                Color.blue(background) / 255f,
                1f,
            )
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
            val asp = viewWidth / viewHeight.toFloat()

            // Centra el libro mientras se pliega: abierto ocupa las dos mitades, cerrado solo una
            val zMin = -pw - EDGE_EXT
            // La tapa es la última en cerrarse y la que marca lo que ocupa el libro
            val coverOpen = ((1f - t) / (1f - LAG)).coerceIn(0f, 1f)
            val coverT = 1f - smooth(coverOpen)
            val tf = min(1f, coverT * 2f)
            val zHi = pw + EDGE_EXT + (0.35f - pw - EDGE_EXT) * smooth(tf)
            val shift = (zMin + zHi) / 2f
            val span = zHi - zMin

            // Cámara de mesa (como en la maqueta) y cámara cenital sobre la página actual
            val tanV = tan(FOVY / 2f)
            val deskDist = max(span * 0.62f / (tanV * asp), PX * 1.35f / tanV)
            val deskTarget = floatArrayOf(0f, 0.4f + 0.3f * coverT, 0f)
            val fillDist = PX / tanV
            val fillTarget = floatArrayOf(0f, H, (pw / 2f - shift) * mirror)
            val z = zoom
            val target = FloatArray(3) { deskTarget[it] + (fillTarget[it] - deskTarget[it]) * z }
            val pitch = DESK_PITCH + (PI.toFloat() / 2f - DESK_PITCH) * z
            val dist = exp(ln(deskDist) + (ln(fillDist) - ln(deskDist)) * z)
            val eye = floatArrayOf(
                target[0] - cos(pitch) * dist,
                target[1] + sin(pitch) * dist,
                target[2],
            )
            Matrix.perspectiveM(proj, 0, Math.toDegrees(FOVY.toDouble()).toFloat(), asp, 0.5f, 400f)
            Matrix.setLookAtM(view, 0, eye[0], eye[1], eye[2], target[0], target[1], target[2], 1f, 0f, 0f)
            Matrix.multiplyMM(vp, 0, proj, 0, view, 0)

            // Sombra de contacto
            GLES30.glDisable(GLES30.GL_DEPTH_TEST)
            GLES30.glEnable(GLES30.GL_BLEND)
            GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
            GLES30.glUseProgram(shadowProgram)
            GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(shadowProgram, "uVP"), 1, false, vp, 0)
            val za = (zMin - shift + offset) * mirror
            val zb = (zHi - shift + offset) * mirror
            GLES30.glUniform4f(
                GLES30.glGetUniformLocation(shadowProgram, "uRect"),
                -PX - EDGE_EXT,
                min(za, zb),
                PX + EDGE_EXT,
                max(za, zb),
            )
            GLES30.glUniform1f(GLES30.glGetUniformLocation(shadowProgram, "uAlpha"), 0.35f * (1f - z))
            quad.position(0)
            GLES30.glEnableVertexAttribArray(0)
            GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, quad)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
            GLES30.glDisableVertexAttribArray(0)

            // Libro
            GLES30.glDisable(GLES30.GL_BLEND)
            GLES30.glEnable(GLES30.GL_DEPTH_TEST)
            GLES30.glDisable(GLES30.GL_CULL_FACE)
            GLES30.glUseProgram(program)
            fun loc(name: String) = GLES30.glGetUniformLocation(program, name)
            GLES30.glUniformMatrix4fv(loc("uVP"), 1, false, vp, 0)
            GLES30.glUniform1f(loc("uT"), t)
            GLES30.glUniform1f(loc("uLag"), LAG)
            GLES30.glUniform1f(loc("uH"), H)
            GLES30.glUniform1f(loc("uW"), SPINE_W)
            GLES30.glUniform1f(loc("uPW"), pw)
            GLES30.glUniform1f(loc("uShiftZ"), shift)
            GLES30.glUniform1f(loc("uOffset"), offset)
            GLES30.glUniform1f(loc("uMirror"), mirror)
            // Sin luces al final para que la página quede igual que en el lector
            GLES30.glUniform1f(loc("uUnlit"), z.pow(3))
            GLES30.glUniform3f(loc("uEye"), eye[0], eye[1], eye[2])
            GLES30.glUniform1i(loc("uFront"), 0)
            GLES30.glUniform1i(loc("uBack"), 1)
            objects.forEach { obj ->
                GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, obj.front())
                GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, obj.back())
                obj.buffer.position(0)
                GLES30.glEnableVertexAttribArray(0)
                GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, STRIDE * 4, obj.buffer)
                obj.buffer.position(3)
                GLES30.glEnableVertexAttribArray(1)
                GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, STRIDE * 4, obj.buffer)
                obj.buffer.position(6)
                GLES30.glEnableVertexAttribArray(2)
                GLES30.glVertexAttribPointer(2, 2, GLES30.GL_FLOAT, false, STRIDE * 4, obj.buffer)
                obj.buffer.position(8)
                GLES30.glEnableVertexAttribArray(3)
                GLES30.glVertexAttribPointer(3, 1, GLES30.GL_FLOAT, false, STRIDE * 4, obj.buffer)
                GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, obj.count)
            }
            (0..3).forEach { GLES30.glDisableVertexAttribArray(it) }
        }

        /**
         * Mitad izquierda fija (tapa trasera y bloque de páginas) y mitad derecha que gira sobre
         * el lomo: la tapa primero y luego cada hoja con su propio retraso, como en la maqueta.
         */
        private fun buildBook(): List<BookObject> {
            val list = mutableListOf<BookObject>()
            val bx = PX + EDGE_EXT
            // Tapa trasera, fija
            list += BookObject(
                grid(-bx, bx, -pw - EDGE_EXT, 0f, 0f, up = true, lv = -1f, segments = 4, spine = false) { z ->
                    (z + pw + EDGE_EXT) / (pw + EDGE_EXT)
                },
                front = { texEndpaper },
                back = { texCover },
            )
            // Bloque de páginas izquierdo: cara superior con página y cantos de papel
            list += BookObject(
                grid(-PX, PX, -pw, 0f, H, up = true, lv = -1f, segments = 4, spine = false) { z -> (z + pw) / pw },
                front = { texLeft },
                back = { texPaper },
            )
            list += BookObject(leftBlockSides(), front = { texEdge }, back = { texEdge })
            // Tapa delantera: la primera en abrirse y la última en cerrarse
            list += BookObject(
                grid(-bx, bx, 0f, pw + EDGE_EXT, 0f, up = true, lv = -1f, segments = 30, spine = true) { z ->
                    z / (pw + EDGE_EXT)
                },
                front = { texEndpaper },
                back = { texCover },
            )
            // Hojas: la de arriba es la página que se ve al estar abierto y las de debajo siguen el
            // capítulo, dos páginas por hoja (anverso y reverso)
            for (i in 0 until SHEETS) {
                val y = H * (i + 1) / SHEETS
                val fromTop = SHEETS - 1 - i
                list += BookObject(
                    grid(-PX, PX, 0f, pw, y, up = true, lv = i / (SHEETS - 1f), segments = 30, spine = true) { z ->
                        z / pw
                    },
                    front = if (fromTop == 0) ({ texRight }) else ({ sheetTexture(fromTop * 2) }),
                    back = { sheetTexture(fromTop * 2 + 1) },
                )
            }
            return list
        }

        private fun leftBlockSides(): FloatBuffer {
            val v = mutableListOf<Float>()
            // Canto exterior (z = -pw), canto cercano (x = -PX) y lejano (x = PX)
            quadVerts(v, floatArrayOf(-PX, 0f, -pw), floatArrayOf(PX, 0f, -pw), floatArrayOf(PX, H, -pw), floatArrayOf(-PX, H, -pw), floatArrayOf(0f, 0f, -1f))
            quadVerts(v, floatArrayOf(-PX, 0f, -pw), floatArrayOf(-PX, 0f, 0f), floatArrayOf(-PX, H, 0f), floatArrayOf(-PX, H, -pw), floatArrayOf(-1f, 0f, 0f))
            quadVerts(v, floatArrayOf(PX, 0f, -pw), floatArrayOf(PX, 0f, 0f), floatArrayOf(PX, H, 0f), floatArrayOf(PX, H, -pw), floatArrayOf(1f, 0f, 0f))
            return floatBuffer(v.toFloatArray())
        }

        private fun quadVerts(out: MutableList<Float>, a: FloatArray, b: FloatArray, c: FloatArray, d: FloatArray, n: FloatArray) {
            for (p in listOf(a, b, c, a, c, d)) {
                out += p[0]; out += p[1]; out += p[2]
                out += n[0]; out += n[1]; out += n[2]
                out += 0.5f; out += 0.5f; out += -1f
            }
        }

        /** Rejilla plana a lo largo del ancho (z), más densa junto al lomo para que se curve bien. */
        private fun grid(
            x0: Float,
            x1: Float,
            z0: Float,
            z1: Float,
            y: Float,
            up: Boolean,
            lv: Float,
            segments: Int,
            spine: Boolean,
            u: (Float) -> Float,
        ): FloatBuffer {
            val ny = if (up) 1f else -1f
            val zs = FloatArray(segments + 1) { i ->
                val f = i / segments.toFloat()
                z0 + (z1 - z0) * if (spine) f.pow(1.4f) else f
            }
            val v = FloatArray(segments * 6 * STRIDE)
            var k = 0
            fun put(x: Float, z: Float) {
                v[k++] = x; v[k++] = y; v[k++] = z
                v[k++] = 0f; v[k++] = ny; v[k++] = 0f
                v[k++] = u(z); v[k++] = (x1 - x) / (x1 - x0)
                v[k++] = lv
            }
            for (i in 0 until segments) {
                val za = zs[i]
                val zb = zs[i + 1]
                put(x0, za); put(x1, za); put(x1, zb)
                put(x0, za); put(x1, zb); put(x0, zb)
            }
            return floatBuffer(v)
        }
    }

    private class BookObject(
        val buffer: FloatBuffer,
        val front: () -> Int,
        val back: () -> Int,
    ) {
        val count = buffer.capacity() / STRIDE
    }

    companion object {
        private const val PX = 3.8f // media altura de página
        private const val H = 0.4f // grosor de cada bloque de páginas
        private const val EDGE_EXT = 0.12f // lo que sobresalen las tapas
        private const val SPINE_W = 0.6f
        private const val SHEETS = 10
        private const val LAG = 0.8f // cuanto mayor, más separadas giran las hojas
        private const val FOVY = 0.62f
        private const val DESK_PITCH = 0.95f
        private const val STRIDE = 9

        private const val ZOOM_OUT = 700L
        private const val CLOSE_START = 350L
        private const val CLOSE_LEN = 2800L
        private const val CLOSE_END = CLOSE_START + CLOSE_LEN
        private const val SWAP_LEN = 800L
        private const val SWAP_END = CLOSE_END + SWAP_LEN
        private const val SWAP_MID = CLOSE_END + SWAP_LEN / 2
        private const val OPEN_LEN = 3400L
        private const val ZOOM_IN = 800L
        private const val OPEN_END = SWAP_END + OPEN_LEN - 250L + ZOOM_IN
        private const val MAX_WAIT = 2500L
        private const val MAX_WAIT_OPEN = 8000L
        private const val RETRY_DELAY = 250L

        private val PAPER = Color.rgb(244, 240, 232)
        private val EDGE = Color.rgb(232, 226, 212)

        fun isSupported(context: Context): Boolean {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
            return am.deviceConfigurationInfo.reqGlEsVersion >= 0x30000
        }

        private fun ease(x: Float): Float {
            val k = x.coerceIn(0f, 1f)
            return if (k < 0.5f) 4f * k * k * k else 1f - (-2f * k + 2f).pow(3) / 2f
        }

        private fun smooth(x: Float): Float = x * x * (3f - 2f * x)

        private fun floatBuffer(data: FloatArray): FloatBuffer =
            ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(data)
                position(0)
            }

        private fun buildProgram(vs: String, fs: String): Int {
            fun shader(type: Int, src: String): Int {
                val s = GLES30.glCreateShader(type)
                GLES30.glShaderSource(s, src)
                GLES30.glCompileShader(s)
                val ok = IntArray(1)
                GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
                if (ok[0] == 0) logcat(LogPriority.ERROR) { "Book shader: " + GLES30.glGetShaderInfoLog(s) }
                return s
            }
            val p = GLES30.glCreateProgram()
            GLES30.glAttachShader(p, shader(GLES30.GL_VERTEX_SHADER, vs))
            GLES30.glAttachShader(p, shader(GLES30.GL_FRAGMENT_SHADER, fs))
            GLES30.glBindAttribLocation(p, 0, "aPos")
            GLES30.glLinkProgram(p)
            return p
        }

        private fun newTexture(): Int {
            val ids = IntArray(1)
            GLES30.glGenTextures(1, ids, 0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ids[0])
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            return ids[0]
        }

        private fun bitmapTexture(bitmap: Bitmap): Int {
            val id = newTexture()
            GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
            GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
            return id
        }

        private fun solidTexture(color: Int): Int {
            val bmp = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
            return bitmapTexture(bmp).also { bmp.recycle() }
        }

        private fun cropToAspect(src: Bitmap, aspect: Float): Bitmap {
            val srcAspect = src.width / src.height.toFloat()
            val (w, h) = if (srcAspect > aspect) {
                (src.height * aspect).toInt() to src.height
            } else {
                src.width to (src.width / aspect).toInt()
            }
            val cropped = Bitmap.createBitmap(src, (src.width - w) / 2, (src.height - h) / 2, w.coerceAtLeast(1), h.coerceAtLeast(1))
            return if (cropped.width > 1024) cropped.scale(1024, (1024 / aspect).toInt()) else cropped
        }

        private fun averageColor(src: Bitmap): Int {
            val px = src.scale(1, 1).getPixel(0, 0)
            // Más oscuro, como el reverso de cartón de una tapa
            return Color.rgb(Color.red(px) * 3 / 4, Color.green(px) * 3 / 4, Color.blue(px) * 3 / 4)
        }

        private const val BOOK_VS = """#version 300 es
precision highp float;
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec3 aNor;
layout(location = 2) in vec2 aUv;
layout(location = 3) in float aLv;
uniform mat4 uVP;
uniform float uT, uLag, uH, uW, uPW, uShiftZ, uOffset, uMirror;
out vec3 vN; out vec2 vUv; out vec3 vP;
// cada capa se abre en su propia ventana: la tapa primero y luego hoja tras hoja
float layerT(float lv) {
    float o = 1.0 - uT;
    float L = lv < 0.0 ? 0.0 : uLag * lv;
    float ol = clamp((o - L) / (1.0 - uLag), 0.0, 1.0);
    return 1.0 - ol * ol * (3.0 - 2.0 * ol);
}
void main() {
    float s = smoothstep(0.0, uW, aPos.z);
    float tl = layerT(aLv);
    float frac = tl;
    if (aLv >= 0.0) {
        float zr = clamp(aPos.z / uPW, 0.0, 1.0);
        frac = min(1.0, tl + 0.24 * sin(3.14159265 * tl) * pow(zr, 1.5));
    }
    float a = 3.14159265 * frac * s;
    float c = cos(a), sn = sin(a);
    vec2 p = vec2(aPos.z, aPos.y - uH);
    vec2 r = vec2(p.x * c - p.y * sn, p.x * sn + p.y * c);
    vec3 P = vec3(aPos.x, r.y + uH, (r.x - uShiftZ + uOffset) * uMirror);
    vec2 nr = vec2(aNor.z * c - aNor.y * sn, aNor.z * sn + aNor.y * c);
    vN = normalize(vec3(aNor.x, nr.y, nr.x * uMirror));
    vUv = aUv; vP = P;
    gl_Position = uVP * vec4(P, 1.0);
}"""

        private const val BOOK_FS = """#version 300 es
precision highp float;
in vec3 vN; in vec2 vUv; in vec3 vP;
uniform sampler2D uFront, uBack;
uniform vec3 uEye;
uniform float uMirror, uUnlit;
out vec4 o;
void main() {
    vec3 n = normalize(vN);
    vec3 V = normalize(uEye - vP);
    bool front = dot(n, V) >= 0.0;
    if (!front) n = -n;
    vec2 uv = front ? vUv : vec2(1.0 - vUv.x, vUv.y);
    if (uMirror < 0.0) uv.x = 1.0 - uv.x;
    vec3 base = front ? texture(uFront, uv).rgb : texture(uBack, uv).rgb;
    vec3 L = normalize(vec3(-0.45, 1.0, 0.35));
    vec3 L2 = normalize(vec3(0.6, 0.5, -0.8));
    float hemi = 0.5 + 0.5 * n.y;
    vec3 amb = mix(vec3(0.42, 0.40, 0.42), vec3(0.62, 0.64, 0.70), hemi);
    float d1 = max(dot(n, L), 0.0), d2 = max(dot(n, L2), 0.0);
    float spec = pow(max(dot(n, normalize(L + V)), 0.0), 48.0) * 0.08;
    vec3 col = base * (amb + vec3(1.0, 0.97, 0.92) * d1 * 0.62 + vec3(0.75, 0.82, 1.0) * d2 * 0.18) + spec;
    col = pow(col, vec3(0.97));
    o = vec4(mix(col, base, uUnlit), 1.0);
}"""

        private const val SHADOW_VS = """#version 300 es
layout(location = 0) in vec2 aPos;
uniform mat4 uVP; uniform vec4 uRect;
out vec2 vQ;
void main() {
    vec2 q = mix(uRect.xy - 1.6, uRect.zw + 1.6, aPos * 0.5 + 0.5);
    vQ = q;
    gl_Position = uVP * vec4(q.x, -0.02, q.y, 1.0);
}"""

        private const val SHADOW_FS = """#version 300 es
precision highp float;
in vec2 vQ;
uniform vec4 uRect; uniform float uAlpha;
out vec4 o;
void main() {
    vec2 c = (uRect.xy + uRect.zw) * 0.5, h = (uRect.zw - uRect.xy) * 0.5;
    vec2 d = abs(vQ - c) - h;
    float sd = length(max(d, 0.0)) + min(max(d.x, d.y), 0.0);
    o = vec4(0.0, 0.0, 0.0, (1.0 - smoothstep(-0.35, 1.25, sd)) * uAlpha);
}"""
    }
}
