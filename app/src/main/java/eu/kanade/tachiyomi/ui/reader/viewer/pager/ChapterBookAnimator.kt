package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.OvershootInterpolator

/**
 * Animación al pasar de capítulo: el manga actual se cierra sobre el lomo y se aleja, y entra
 * otro manga cerrado por el lado de lectura que se abre ya en el capítulo siguiente.
 */
object ChapterBookAnimator {

    private const val CLOSED_ANGLE = 75f
    private const val CLOSED_SCALE = 0.8f
    private const val CLOSE_DURATION = 260L
    private const val OPEN_DURATION = 420L

    fun play(view: View, rtl: Boolean) {
        val width = view.width.toFloat()
        if (width == 0f) return
        // Izquierda a derecha: lomo a la izquierda y el siguiente libro llega por la derecha
        val side = if (rtl) -1f else 1f

        view.animate().cancel()
        view.cameraDistance = width * 14f
        view.pivotX = if (rtl) width else 0f
        view.pivotY = view.height / 2f

        view.animate()
            .rotationY(side * CLOSED_ANGLE)
            .scaleX(CLOSED_SCALE)
            .scaleY(CLOSED_SCALE)
            .translationX(-side * width * 0.25f)
            .alpha(0f)
            .setDuration(CLOSE_DURATION)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction {
                // El nuevo manga aparece cerrado al otro lado...
                view.translationX = side * width * 0.25f
                view.rotationY = side * CLOSED_ANGLE
                view.animate()
                    .rotationY(0f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .translationX(0f)
                    .alpha(1f)
                    .setDuration(OPEN_DURATION)
                    .setInterpolator(OvershootInterpolator(0.6f))
                    .withEndAction { reset(view) }
                    .start()
            }
            .start()
    }

    fun reset(view: View) {
        view.animate().cancel()
        view.rotationY = 0f
        view.scaleX = 1f
        view.scaleY = 1f
        view.translationX = 0f
        view.alpha = 1f
    }
}
