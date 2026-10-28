package eu.kanade.presentation.reader.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

private val InkShape = RoundedCornerShape(28.dp)
private val InkColor = Color(0xFF0B0B10)

private val RootLight = Color(0xFFFFE27A)
private val RootMid = Color(0xFFE2B541)
private val RootDark = Color(0xFF8C6418)
private val RootOutline = Color(0xFF2B1D07)

/**
 * Superficie flotante "de tinta" para las barras del lector: fondo negro tinta translúcido atravesado
 * por raíces gruesas y doradas que se retuercen y entrelazan a lo largo de la barra.
 *
 * @param seed cambia la forma de las raíces para que cada barra sea distinta.
 */
@Composable
fun InkSurface(
    modifier: Modifier = Modifier,
    seed: Int = 0,
    content: @Composable BoxScope.() -> Unit,
) {
    // Texto e iconos claros sobre el fondo oscuro
    val colorScheme = MaterialTheme.colorScheme.copy(
        onSurface = Color.White,
        onSurfaceVariant = Color.White.copy(alpha = 0.8f),
        onBackground = Color.White,
    )
    Box(
        modifier = modifier
            .clip(InkShape)
            .background(InkColor.copy(alpha = 0.88f))
            .drawBehind { drawIntertwinedRoots(seed) }
            .background(
                // Velo para que las raíces no compitan con los iconos
                Brush.verticalGradient(
                    listOf(InkColor.copy(alpha = 0.35f), InkColor.copy(alpha = 0.10f), InkColor.copy(alpha = 0.35f)),
                ),
            )
            .border(1.dp, RootMid.copy(alpha = 0.35f), InkShape),
    ) {
        MaterialTheme(colorScheme = colorScheme) {
            CompositionLocalProvider(LocalContentColor provides Color.White) {
                content()
            }
        }
    }
}

/**
 * Varias hebras que recorren la barra de lado a lado ondulando con fases distintas, de modo que se
 * cruzan como raíces trenzadas. Cada hebra tiene grosor variable, relleno dorado con degradado,
 * contorno oscuro, una veta clara en el centro y un halo suave.
 */
private fun DrawScope.drawIntertwinedRoots(seed: Int) {
    val w = size.width
    val h = size.height
    if (w <= 0f || h <= 0f) return
    val strands = 5
    val steps = 48
    for (k in 0 until strands) {
        val phase = (k * 1.3f + seed * 0.7f) * PI.toFloat()
        val waves = 1.2f + 0.25f * ((k + seed) % 3)
        val amplitude = h * (0.22f + 0.05f * (k % 2))
        val baseThickness = h * (0.10f + 0.03f * ((k + 1) % 3))
        val center = h / 2f + h * 0.06f * (k - strands / 2f) / strands

        val top = ArrayList<Offset>(steps + 1)
        val bottom = ArrayList<Offset>(steps + 1)
        val spine = Path()
        for (i in 0..steps) {
            val t = i / steps.toFloat()
            val x = -w * 0.05f + t * w * 1.1f
            val angle = t * waves * 2f * PI.toFloat() + phase
            val y = center + amplitude * sin(angle)
            // Más gruesa en el centro de cada onda, más fina donde se cruza
            val thickness = baseThickness * (0.55f + 0.45f * kotlin.math.abs(sin(angle * 0.5f + k)))
            top += Offset(x, y - thickness / 2f)
            bottom += Offset(x, y + thickness / 2f)
            if (i == 0) spine.moveTo(x, y) else spine.lineTo(x, y)
        }
        val body = Path().apply {
            moveTo(top.first().x, top.first().y)
            top.drop(1).forEach { lineTo(it.x, it.y) }
            bottom.asReversed().forEach { lineTo(it.x, it.y) }
            close()
        }
        // Halo
        drawPath(spine, RootLight.copy(alpha = 0.10f), style = Stroke(width = baseThickness * 2.2f, cap = StrokeCap.Round))
        // Relleno dorado
        drawPath(
            body,
            Brush.verticalGradient(
                listOf(RootLight, RootMid, RootDark),
                startY = center - amplitude - baseThickness,
                endY = center + amplitude + baseThickness,
            ),
            alpha = 0.55f,
        )
        // Contorno oscuro estilo tinta
        drawPath(body, RootOutline.copy(alpha = 0.85f), style = Stroke(width = 1.6f, join = StrokeJoin.Round))
        // Veta clara en el centro
        drawPath(spine, RootLight.copy(alpha = 0.45f), style = Stroke(width = 1.2f, cap = StrokeCap.Round))
    }
    // Chispas doradas sueltas
    for (i in 0 until 10) {
        val fx = ((i * 37 + seed * 13) % 100) / 100f
        val fy = ((i * 61 + seed * 7) % 100) / 100f
        drawCircle(RootLight.copy(alpha = 0.45f), radius = 1.6f + (i % 3), center = Offset(w * fx, h * fy))
    }
}
