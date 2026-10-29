package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.view.View
import androidx.viewpager.widget.ViewPager
import kotlin.math.abs

/**
 * Efecto de pasar hoja: la página que se va gira sobre el lomo como una hoja de papel y la
 * siguiente espera quieta debajo. En lectura de derecha a izquierda el lomo está a la derecha.
 *
 * Usar con `reverseDrawingOrder = !rtl` para que la hoja que gira quede encima.
 */
class PageTurnTransformer(
    private val rtl: Boolean,
    private val backgroundColor: () -> Int,
) : ViewPager.PageTransformer {

    override fun transformPage(page: View, position: Float) {
        val width = page.width.toFloat()
        page.cameraDistance = width * CAMERA_DISTANCE_FACTOR

        if (abs(position) >= 1f) {
            // Fuera de pantalla o justo debajo de la página actual: no debe asomar por los márgenes
            page.alpha = 0f
            page.translationX = 0f
            page.rotationY = 0f
            page.scaleX = 1f
            page.scaleY = 1f
            return
        }

        // Fondo opaco para que la página de debajo no se vea a través de los márgenes de la imagen
        if (page.background == null) page.setBackgroundColor(backgroundColor())
        page.alpha = 1f
        // Todas las páginas quedan fijas en su sitio; solo cambia su giro
        page.translationX = -position * width

        val turning = if (rtl) position > 0f else position < 0f
        if (turning) {
            page.pivotX = if (rtl) width else 0f
            page.pivotY = page.height / 2f
            page.rotationY = MAX_ANGLE * position
            page.scaleX = 1f
            page.scaleY = 1f
        } else {
            // La página de debajo se acerca un poco mientras se descubre
            val scale = UNDER_SCALE + (1f - UNDER_SCALE) * (1f - abs(position))
            page.rotationY = 0f
            page.scaleX = scale
            page.scaleY = scale
        }
    }

    companion object {
        private const val MAX_ANGLE = 90f
        private const val UNDER_SCALE = 0.94f
        private const val CAMERA_DISTANCE_FACTOR = 12f

        /** Deja las páginas como estaban al quitar el efecto. */
        fun reset(page: View) {
            page.alpha = 1f
            page.translationX = 0f
            page.rotationY = 0f
            page.scaleX = 1f
            page.scaleY = 1f
        }
    }
}
