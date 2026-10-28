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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

private val InkShape = RoundedCornerShape(28.dp)
private val InkColor = Color(0xFF0D0D12)

/**
 * Superficie flotante "de tinta" para las barras del lector: fondo negro tinta translúcido con
 * raíces finas que crecen desde el borde, teñidas con el color del tema.
 *
 * @param rootsFromTop si las raíces nacen del borde superior (barra de abajo) o del inferior (barra de arriba).
 */
@Composable
fun InkSurface(
    modifier: Modifier = Modifier,
    rootsFromTop: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    val veinColor = MaterialTheme.colorScheme.primary
    Box(
        modifier = modifier
            .clip(InkShape)
            .background(
                Brush.verticalGradient(
                    listOf(InkColor.copy(alpha = 0.92f), InkColor.copy(alpha = 0.80f)),
                ),
            )
            .drawBehind { drawInkRoots(veinColor, rootsFromTop) }
            .border(1.dp, Color.White.copy(alpha = 0.12f), InkShape),
    ) {
        CompositionLocalProvider(LocalContentColor provides Color.White) {
            content()
        }
    }
}

/** Dibuja ramas finas que se abren desde un borde, como tinta que se extiende sobre papel. */
private fun DrawScope.drawInkRoots(color: Color, fromTop: Boolean) {
    val w = size.width
    val h = size.height
    val baseY = if (fromTop) 0f else h
    val dir = if (fromTop) 1f else -1f
    // Puntos de nacimiento repartidos a lo ancho; cada raíz se curva y se ramifica una vez
    val seeds = listOf(0.08f, 0.27f, 0.51f, 0.73f, 0.92f)
    seeds.forEachIndexed { i, fx ->
        val startX = w * fx
        val sway = if (i % 2 == 0) 1f else -1f
        val length = h * (0.55f + 0.1f * (i % 3))
        val main = Path().apply {
            moveTo(startX, baseY)
            cubicTo(
                startX + sway * w * 0.03f, baseY + dir * length * 0.35f,
                startX - sway * w * 0.04f, baseY + dir * length * 0.65f,
                startX + sway * w * 0.05f, baseY + dir * length,
            )
        }
        drawPath(main, color.copy(alpha = 0.30f), style = Stroke(width = 2.2f, cap = StrokeCap.Round))
        val branch = Path().apply {
            val bx = startX + sway * w * 0.01f
            val by = baseY + dir * length * 0.4f
            moveTo(bx, by)
            quadraticTo(
                bx + sway * w * 0.05f, by + dir * length * 0.15f,
                bx + sway * w * 0.08f, by + dir * length * 0.35f,
            )
        }
        drawPath(branch, color.copy(alpha = 0.20f), style = Stroke(width = 1.2f, cap = StrokeCap.Round))
        drawCircle(color.copy(alpha = 0.25f), radius = 2.5f, center = Offset(startX, baseY + dir * 1.5f))
    }
}
