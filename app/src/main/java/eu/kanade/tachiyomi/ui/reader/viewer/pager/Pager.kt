package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.content.Context
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.viewpager.widget.ViewPager.SCROLL_STATE_DRAGGING
import androidx.viewpager.widget.ViewPager.SimpleOnPageChangeListener
import androidx.viewpager.widget.DirectionalViewPager
import eu.kanade.tachiyomi.ui.reader.viewer.GestureDetectorWithLongTap
import kotlin.math.abs

/**
 * Pager implementation that listens for tap and long tap and allows temporarily disabling touch
 * events in order to work with child views that need to disable touch events on this parent. The
 * pager can also be declared to be vertical by creating it with [isHorizontal] to false.
 */
open class Pager(
    context: Context,
    isHorizontal: Boolean = true,
) : DirectionalViewPager(context, isHorizontal) {

    /**
     * Tap listener function to execute when a tap is detected.
     */
    var tapListener: ((MotionEvent) -> Unit)? = null

    /**
     * Long tap listener function to execute when a long tap is detected.
     */
    var longTapListener: ((MotionEvent) -> Boolean)? = null

    /** Ve todos los toques antes que el pager (para que la hoja doblada siga al dedo). */
    var touchObserver: ((MotionEvent) -> Unit)? = null

    /**
     * Gesture listener that implements tap and long tap events.
     */
    private val gestureListener = object : GestureDetectorWithLongTap.Listener() {
        override fun onSingleTapConfirmed(ev: MotionEvent): Boolean {
            tapListener?.invoke(ev)
            return true
        }

        override fun onLongTapConfirmed(ev: MotionEvent) {
            val listener = longTapListener
            if (listener != null && listener.invoke(ev)) {
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            }
        }
    }

    /**
     * Gesture detector which handles motion events.
     */
    private val gestureDetector = GestureDetectorWithLongTap(context, gestureListener)

    /**
     * Whether the gesture detector is currently enabled.
     */
    private var isGestureDetectorEnabled = true

    /**
     * Dispatches a touch event.
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        touchObserver?.invoke(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                swipeDownX = ev.x
                swipeDownY = ev.y
                swipeStartItem = currentItem
                draggedThisGesture = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> draggedThisGesture = false
        }
        val handled = super.dispatchTouchEvent(ev)
        if (ev.actionMasked == MotionEvent.ACTION_UP) finishShortSwipe(ev)
        if (isGestureDetectorEnabled) {
            gestureDetector.onTouchEvent(ev)
        }
        return handled
    }

    private var swipeDownX = 0f
    private var swipeDownY = 0f
    private var swipeStartItem = 0
    private var draggedThisGesture = false

    init {
        addOnPageChangeListener(
            object : SimpleOnPageChangeListener() {
                override fun onPageScrollStateChanged(state: Int) {
                    if (state == SCROLL_STATE_DRAGGING) draggedThisGesture = true
                }
            },
        )
    }

    /**
     * Basta un deslizamiento corto para pasar de página: si el pager arrastraba la página pero iba
     * a volver a la misma, se lleva a la de al lado. Solo cuenta cuando el pager estaba
     * arrastrando, así que mover una imagen con zoom no cambia de página.
     */
    private fun finishShortSwipe(ev: MotionEvent) {
        if (!draggedThisGesture || currentItem != swipeStartItem || width == 0) return
        val dx = ev.x - swipeDownX
        val dy = ev.y - swipeDownY
        if (abs(dx) < width * SHORT_SWIPE_FRACTION || abs(dx) < abs(dy) * 1.2f) return
        val target = swipeStartItem + if (dx < 0) 1 else -1
        val count = adapter?.count ?: return
        if (target in 0 until count) setCurrentItem(target, true)
    }

    /**
     * Whether the given [ev] should be intercepted. Only used to prevent crashes when child
     * views manipulate [requestDisallowInterceptTouchEvent].
     */
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        return try {
            super.onInterceptTouchEvent(ev)
        } catch (e: IllegalArgumentException) {
            false
        }
    }

    /**
     * Handles a touch event. Only used to prevent crashes when child views manipulate
     * [requestDisallowInterceptTouchEvent].
     */
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        return try {
            super.onTouchEvent(ev)
        } catch (e: NullPointerException) {
            false
        } catch (e: IndexOutOfBoundsException) {
            false
        } catch (e: IllegalArgumentException) {
            false
        }
    }

    /**
     * Executes the given key event when this pager has focus. Just do nothing because the reader
     * already dispatches key events to the viewer and has more control than this method.
     */
    override fun executeKeyEvent(event: KeyEvent): Boolean {
        // Disable viewpager's default key event handling
        return false
    }

    /**
     * Enables or disables the gesture detector.
     */
    fun setGestureDetectorEnabled(enabled: Boolean) {
        isGestureDetectorEnabled = enabled
    }
}

/** Parte del ancho que hay que deslizar para pasar de página. */
private const val SHORT_SWIPE_FRACTION = 0.12f
