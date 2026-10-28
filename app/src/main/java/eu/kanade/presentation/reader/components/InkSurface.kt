package eu.kanade.presentation.reader.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

private val RootLight = Color(0xFFFFE37C)
private val RootMid = Color(0xFFD9A93A)
private val RootDark = Color(0xFF7A5414)
private val RootOutline = Color(0xFF1E1404)
private val InkBand = Color(0xFF0E0904)

/**
 * Barra del lector hecha de raíces: un haz de raíces gruesas y doradas, trenzadas entre sí, que ocupa
 * todo el ancho sin esquinas redondeadas. Algunas ramitas sobresalen por arriba y por abajo.
 *
 * @param seed cambia la forma de las raíces para que cada barra sea distinta.
 */
@Composable
fun InkSurface(
    modifier: Modifier = Modifier,
    seed: Int = 0,
    content: @Composable BoxScope.() -> Unit,
) {
    // Texto e iconos claros sobre las raíces
    val colorScheme = MaterialTheme.colorScheme.copy(
        onSurface = Color.White,
        onSurfaceVariant = Color.White.copy(alpha = 0.85f),
        onBackground = Color.White,
    )
    Box(modifier = modifier.drawBehind { drawRootBand(seed) }) {
        MaterialTheme(colorScheme = colorScheme) {
            CompositionLocalProvider(LocalContentColor provides Color.White) {
                content()
            }
        }
    }
}

private fun DrawScope.drawRootBand(seed: Int) {
    val w = size.width
    val h = size.height
    if (w <= 0f || h <= 0f) return

    // Fondo de tinta entre las raíces
    drawRect(
        Brush.verticalGradient(
            listOf(InkBand.copy(alpha = 0.92f), Color(0xFF1C1307).copy(alpha = 0.88f), InkBand.copy(alpha = 0.92f)),
        ),
    )

    val strands = 8
    for (k in 0 until strands) {
        drawStrand(k, strands, seed, w, h)
    }

    // Ramitas que se escapan por los bordes
    for (i in 0 until 6) {
        val fx = ((i * 0.17f + seed * 0.11f) % 1f) * 0.9f + 0.05f
        val up = (i + seed) % 2 == 0
        drawTendril(Offset(w * fx, if (up) 0f else h), up, h, i + seed)
    }

    // Velo central para que los iconos se lean bien
    drawRect(
        Brush.verticalGradient(
            listOf(Color.Transparent, InkBand.copy(alpha = 0.45f), Color.Transparent),
        ),
    )
}

/** Una raíz que cruza la barra ondulando; cada una con fase distinta para que se trencen. */
private fun DrawScope.drawStrand(k: Int, strands: Int, seed: Int, w: Float, h: Float) {
    val steps = 64
    val phase = (k * 0.85f + seed * 0.6f) * PI.toFloat()
    val waves = 1.6f + 0.35f * ((k + seed) % 3)
    val amplitude = h * (0.26f + 0.06f * (k % 3))
    val baseThickness = h * (0.16f + 0.05f * ((k + 2) % 3))
    val center = h * (0.5f + 0.16f * ((k.toFloat() / (strands - 1)) - 0.5f))

    val top = ArrayList<Offset>(steps + 1)
    val bottom = ArrayList<Offset>(steps + 1)
    val spine = Path()
    val barkA = Path()
    val barkB = Path()
    for (i in 0..steps) {
        val t = i / steps.toFloat()
        val x = -w * 0.04f + t * w * 1.08f
        val angle = t * waves * 2f * PI.toFloat() + phase
        val y = center + amplitude * sin(angle)
        // Se ensancha en las curvas y se estrecha al cruzarse, como una raíz enroscada
        val thickness = baseThickness * (0.45f + 0.55f * abs(sin(angle * 0.5f + k * 0.7f)))
        top += Offset(x, y - thickness / 2f)
        bottom += Offset(x, y + thickness / 2f)
        val barkOffset = thickness * 0.22f * sin(t * 40f + k)
        if (i == 0) {
            spine.moveTo(x, y)
            barkA.moveTo(x, y - thickness * 0.25f + barkOffset)
            barkB.moveTo(x, y + thickness * 0.25f - barkOffset)
        } else {
            spine.lineTo(x, y)
            barkA.lineTo(x, y - thickness * 0.25f + barkOffset)
            barkB.lineTo(x, y + thickness * 0.25f - barkOffset)
        }
    }
    val body = Path().apply {
        moveTo(top.first().x, top.first().y)
        top.drop(1).forEach { lineTo(it.x, it.y) }
        bottom.asReversed().forEach { lineTo(it.x, it.y) }
        close()
    }
    // Brillo alrededor
    drawPath(spine, RootLight.copy(alpha = 0.12f), style = Stroke(width = baseThickness * 2f, cap = StrokeCap.Round))
    // Relleno dorado
    drawPath(
        body,
        Brush.verticalGradient(
            listOf(RootLight, RootMid, RootDark),
            startY = center - amplitude - baseThickness,
            endY = center + amplitude + baseThickness,
        ),
        alpha = 0.78f,
    )
    // Corteza: vetas oscuras y una clara
    drawPath(barkA, RootDark.copy(alpha = 0.7f), style = Stroke(width = 1.3f, cap = StrokeCap.Round))
    drawPath(barkB, RootDark.copy(alpha = 0.55f), style = Stroke(width = 1.1f, cap = StrokeCap.Round))
    drawPath(spine, RootLight.copy(alpha = 0.6f), style = Stroke(width = 1.2f, cap = StrokeCap.Round))
    // Contorno de tinta
    drawPath(body, RootOutline, style = Stroke(width = 2.4f, join = StrokeJoin.Round))
}

/** Ramita fina y curva que sale del borde y se afina hasta la punta. */
private fun DrawScope.drawTendril(start: Offset, up: Boolean, h: Float, variant: Int) {
    val dir = if (up) -1f else 1f
    val length = h * (0.35f + 0.1f * (variant % 3))
    val sway = if (variant % 2 == 0) 1f else -1f
    val segments = 8
    var prev = start
    for (i in 1..segments) {
        val t = i / segments.toFloat()
        val point = Offset(
            start.x + sway * length * 0.5f * sin(t * PI.toFloat() * 0.9f),
            start.y + dir * length * t,
        )
        val width = 6f * (1f - t) + 1f
        drawLine(RootOutline, prev, point, strokeWidth = width + 2f, cap = StrokeCap.Round)
        drawLine(RootMid, prev, point, strokeWidth = width, cap = StrokeCap.Round)
        prev = point
    }
}
