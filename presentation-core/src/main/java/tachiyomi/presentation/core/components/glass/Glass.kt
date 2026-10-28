package tachiyomi.presentation.core.components.glass

import android.os.Build
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Ajustes del efecto "liquid glass".
 *
 * @param enabled si es false las superficies de cristal se dibujan opacas.
 * @param blurRadius desenfoque del contenido que queda detrás del cristal (Android 12+).
 * @param opacity opacidad del tinte sobre el desenfoque, de 0 (transparente) a 1 (opaco).
 */
@Immutable
data class GlassStyle(
    val enabled: Boolean = true,
    val blurRadius: Dp = 24.dp,
    val opacity: Float = 0.45f,
    val tint: Color? = null,
)

val LocalGlassStyle = staticCompositionLocalOf { GlassStyle() }

/** Cuánto del color elegido se mezcla con la superficie del cristal. */
private const val GLASS_TINT_AMOUNT = 0.3f

/**
 * Espacio extra que las pantallas deben dejar abajo porque hay una barra flotante encima.
 * El [tachiyomi.presentation.core.components.material.Scaffold] lo suma a su padding inferior.
 */
val LocalBottomOverlayPadding = compositionLocalOf { 0.dp }

/** true cuando la pantalla principal usa la barra de navegación flotante de cristal. */
val LocalGlassNavigation = compositionLocalOf { false }

/** Captura lo que se dibuja en una zona para que las superficies de cristal lo desenfoquen. */
@Stable
class GlassBackdrop {
    internal var layer: GraphicsLayer? by mutableStateOf(null)
    internal var positionInRoot by mutableStateOf(Offset.Zero)
}

@Composable
fun rememberGlassBackdrop(): GlassBackdrop = remember { GlassBackdrop() }

/**
 * Marca el contenido que se verá desenfocado detrás del cristal.
 * Una [GlassSurface] que use este [backdrop] no puede estar dentro de este mismo contenido.
 */
@Composable
fun Modifier.glassBackdropSource(backdrop: GlassBackdrop): Modifier {
    val layer = rememberGraphicsLayer()
    SideEffect { backdrop.layer = layer }
    return this
        .onGloballyPositioned { backdrop.positionInRoot = it.positionInRoot() }
        .drawWithContent {
            layer.record { this@drawWithContent.drawContent() }
            drawLayer(layer)
        }
}

@Composable
fun GlassSurface(
    backdrop: GlassBackdrop?,
    shape: Shape,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val style = LocalGlassStyle.current
    val colorScheme = MaterialTheme.colorScheme
    val blurLayer = rememberGraphicsLayer()
    var positionInRoot by remember { mutableStateOf(Offset.Zero) }

    val canBlur = style.enabled && backdrop != null && style.blurRadius > 0.dp &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val tint = if (style.enabled) {
        val base = style.tint?.let { lerp(colorScheme.surface, it, GLASS_TINT_AMOUNT) } ?: colorScheme.surface
        base.copy(alpha = style.opacity)
    } else {
        colorScheme.surfaceColorAtElevation(3.dp)
    }
    val rimLight = if (style.enabled) {
        Brush.verticalGradient(
            listOf(Color.White.copy(alpha = 0.45f), Color.White.copy(alpha = 0.08f)),
        )
    } else {
        Brush.verticalGradient(listOf(colorScheme.outlineVariant, colorScheme.outlineVariant))
    }

    Box(
        modifier = modifier
            .onGloballyPositioned { positionInRoot = it.positionInRoot() }
            .clip(shape)
            .drawBehind {
                val source = backdrop?.layer
                if (canBlur && backdrop != null && source != null) {
                    val radius = style.blurRadius.toPx()
                    blurLayer.renderEffect = BlurEffect(radius, radius, TileMode.Clamp)
                    blurLayer.record {
                        val offset = backdrop.positionInRoot - positionInRoot
                        translate(offset.x, offset.y) { drawLayer(source) }
                    }
                    drawLayer(blurLayer)
                }
                drawRect(tint)
            }
            .border(1.dp, rimLight, shape),
        content = content,
    )
}
