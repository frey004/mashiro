package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams
import androidx.core.graphics.drawable.toBitmap
import androidx.core.view.children
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.viewpager.widget.ViewPager
import coil3.asDrawable
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.model.ChapterTransition
import eu.kanade.tachiyomi.ui.reader.model.InsertPage
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import eu.kanade.tachiyomi.ui.reader.viewer.Viewer
import eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation.NavigationRegion
import eu.kanade.tachiyomi.util.system.readerBackgroundColor
import eu.kanade.tachiyomi.widget.ViewPagerAdapter
import eu.kanade.tachiyomi.source.model.Page
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mihon.app.di.appGraph
import tachiyomi.core.common.util.system.logcat
import kotlin.math.min

/**
 * Implementation of a [Viewer] to display pages with a [ViewPager].
 */
@Suppress("LeakingThis")
abstract class PagerViewer(val activity: ReaderActivity) : Viewer {

    val graph by lazy { activity.appGraph }
    val downloadManager by lazy { graph.downloadManager }
    val readerPreferences by lazy { graph.readerPreferences }

    private val scope = MainScope()

    /**
     * View pager used by this viewer. It's abstract to implement L2R, R2L and vertical pagers on
     * top of this class.
     */
    val pager = createPager()

    /**
     * Configuration used by the pager, like allow taps, scale mode on images, page transitions...
     */
    val config = PagerConfig(this, scope, readerPreferences)

    /**
     * Adapter of the pager.
     */
    private val adapter = PagerViewerAdapter(this)

    /**
     * Currently active item. It can be a chapter page or a chapter transition.
     */
    private var currentPage: Any? = null

    /**
     * Viewer chapters to set when the pager enters idle mode. Otherwise, if the view was settling
     * or dragging, there'd be a noticeable and annoying jump.
     */
    private var awaitingIdleViewerChapters: ViewerChapters? = null

    /**
     * Whether the view pager is currently in idle mode. It sets the awaiting chapters if setting
     * this field to true.
     */
    private var isIdle = true
        set(value) {
            field = value
            if (value) {
                awaitingIdleViewerChapters?.let { viewerChapters ->
                    setChaptersInternal(viewerChapters)
                    awaitingIdleViewerChapters = null
                    if (viewerChapters.currChapter.pages?.size == 1) {
                        adapter.nextTransition?.to?.let(activity::requestPreloadChapter)
                    }
                }
            }
        }

    private val pagerListener = object : ViewPager.SimpleOnPageChangeListener() {
        override fun onPageSelected(position: Int) {
            if (!activity.isScrollingThroughPages) {
                activity.hideMenu()
            }
            onPageChange(position)
        }

        override fun onPageScrollStateChanged(state: Int) {
            isIdle = state == ViewPager.SCROLL_STATE_IDLE
        }
    }

    init {
        pager.isVisible = false // Don't layout the pager yet
        pager.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        pager.isFocusable = false
        pager.offscreenPageLimit = 1
        pager.id = R.id.reader_pager
        pager.adapter = adapter
        pager.addOnPageChangeListener(pagerListener)
        pager.tapListener = { event ->
            val viewPosition = IntArray(2)
            pager.getLocationOnScreen(viewPosition)
            val viewPositionRelativeToWindow = IntArray(2)
            pager.getLocationInWindow(viewPositionRelativeToWindow)
            val pos = PointF(
                (event.rawX - viewPosition[0] + viewPositionRelativeToWindow[0]) / pager.width,
                (event.rawY - viewPosition[1] + viewPositionRelativeToWindow[1]) / pager.height,
            )
            when (config.navigator.getAction(pos)) {
                NavigationRegion.MENU -> activity.toggleMenu()
                NavigationRegion.NEXT -> moveToNext()
                NavigationRegion.PREV -> moveToPrevious()
                NavigationRegion.RIGHT -> moveRight()
                NavigationRegion.LEFT -> moveLeft()
            }
        }
        pager.longTapListener = f@{
            if (activity.viewModel.state.value.menuVisible || config.longTapEnabled) {
                val item = adapter.items.getOrNull(pager.currentItem)
                if (item is ReaderPage) {
                    activity.onPageLongTap(item)
                    return@f true
                }
            }
            false
        }

        config.dualPageSplitChangedListener = { enabled ->
            if (!enabled) {
                cleanupPageSplit()
            }
        }

        config.imagePropertyChangedListener = {
            refreshAdapter()
        }

        config.pageTurnEffectChangedListener = ::applyPageTurnEffect

        config.navigationModeChangedListener = {
            val showOnStart = config.navigationOverlayOnStart || config.forceNavigationOverlay
            activity.binding.navigationOverlay.setNavigation(config.navigator, showOnStart)
        }
    }

    /** Efecto de pasar hoja en lectura horizontal. */
    private fun applyPageTurnEffect(enabled: Boolean) {
        if (enabled && this !is VerticalPagerViewer) {
            val rtl = this is R2LPagerViewer
            // La hoja que gira es la de menor índice en izquierda a derecha y la de mayor en derecha a izquierda
            pager.setPageTransformer(!rtl, PageTurnTransformer(rtl) { activity.readerBackgroundColor(config.theme) })
        } else {
            pager.setPageTransformer(false, null)
            pager.children.forEach(PageTurnTransformer::reset)
        }
    }

    override fun destroy() {
        super.destroy()
        scope.cancel()
        bookView?.let { activity.binding.readerContainer.removeView(it) }
        bookView = null
    }

    private var bookView: ChapterBookView? = null

    private companion object {
        const val SHEET_PAGES = 20
        const val SHEET_PAGE_WIDTH = 400
    }
    private var lastPageSnapshot: Bitmap? = null

    /** Captura lo que muestra la vista de [item] si sigue montada en el pager. */
    private fun snapshotOf(item: Any): Bitmap? {
        val holder = pager.children.firstOrNull { (it as? ViewPagerAdapter.PositionableView)?.item == item }
            ?: return null
        if (holder.width == 0 || holder.height == 0) return null
        val scale = min(1f, 1080f / holder.width)
        val bitmap = Bitmap.createBitmap(
            (holder.width * scale).toInt(),
            (holder.height * scale).toInt(),
            Bitmap.Config.ARGB_8888,
        )
        Canvas(bitmap).apply {
            drawColor(activity.readerBackgroundColor(config.theme))
            scale(scale, scale)
            holder.draw(this)
        }
        return bitmap
    }

    /**
     * Cierra el manga del capítulo terminado y abre otro en el capítulo siguiente. Con
     * [openOnly] solo entra un manga cerrado y se abre en el capítulo actual.
     */
    private fun playChapterBook(previous: Any?, openOnly: Boolean = false) {
        if (bookView != null || !ChapterBookView.isSupported(activity)) return
        val shown = if (openOnly) null else previous?.let(::snapshotOf) ?: return
        val rtl = this is R2LPagerViewer
        val background = activity.readerBackgroundColor(config.theme)
        // Si se venía de la pantalla de transición, al lado queda la última página del capítulo
        val other = lastPageSnapshot.takeIf { previous is ChapterTransition }
        lastPageSnapshot = null
        val oldChapter = when (previous) {
            is ReaderPage -> previous.chapter
            is ChapterTransition.Next -> previous.from
            else -> null
        }
        var readyChecks = 0
        val container = activity.binding.readerContainer
        val view = ChapterBookView(
            context = activity,
            rtl = rtl,
            backgroundColor = background,
            openOnly = openOnly,
            currentPage = shown,
            otherPage = other,
            requestNewPages = request@{
                val current = adapter.items.getOrNull(pager.currentItem)
                // Espera a que la página esté cargada y dibujada para que el final encaje
                if (current is ReaderPage && current.status != Page.State.Ready) return@request null
                if (current is ReaderPage && readyChecks++ < 2) return@request null
                val next = adapter.items.getOrNull(pager.currentItem + if (rtl) -1 else 1)
                (current?.let(::snapshotOf) ?: return@request null) to next?.let(::snapshotOf)
            },
            onFinished = {
                bookView?.let(container::removeView)
                bookView = null
            },
        )
        bookView = view
        container.addView(
            view,
            container.indexOfChild(activity.binding.viewerContainer) + 1,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
        val sheetWidth = min(pager.width.takeIf { it > 0 } ?: 720, SHEET_PAGE_WIDTH)
        val sheetHeight = sheetWidth * (pager.height.takeIf { it > 0 } ?: 1560) / (pager.width.takeIf { it > 0 } ?: 720)
        scope.launch {
            // Hojas del manga que se cierra: del final del capítulo hacia atrás
            oldChapter?.pages?.asReversed()?.let { pages ->
                view.setSheetPages(false, decodeSheetPages(pages, background, sheetWidth, sheetHeight))
            }
            // Hojas del manga que se abre: el capítulo desde su primera página
            val newChapter = (adapter.items.getOrNull(pager.currentItem) as? ReaderPage)?.chapter
            newChapter?.pages?.let { pages ->
                view.setSheetPages(true, decodeSheetPages(pages, background, sheetWidth, sheetHeight))
            }
        }
        val manga = activity.viewModel.manga ?: return
        scope.launch {
            val result = activity.imageLoader.execute(
                ImageRequest.Builder(activity)
                    .data(manga)
                    .size(720)
                    .allowHardware(false)
                    .build(),
            )
            val cover = (result as? SuccessResult)?.image?.asDrawable(activity.resources)?.toBitmap()
            if (cover != null) bookView?.setCover(cover)
        }
    }

    /** Decodifica en pequeño las páginas ya descargadas, centradas sobre el fondo del lector. */
    private suspend fun decodeSheetPages(
        pages: List<ReaderPage>,
        background: Int,
        width: Int,
        height: Int,
    ): List<Bitmap> = withContext(Dispatchers.IO) {
        pages.asSequence()
            .filter { it !is InsertPage && it.status == Page.State.Ready && it.stream != null }
            .take(SHEET_PAGES)
            .mapNotNull { page ->
                runCatching {
                    val stream = page.stream!!
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    stream().use { BitmapFactory.decodeStream(it, null, bounds) }
                    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
                    var sample = 1
                    while (bounds.outWidth / (sample * 2) >= width) sample *= 2
                    val image = stream().use {
                        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
                    } ?: return@runCatching null
                    val out = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
                    val fit = min(width / image.width.toFloat(), height / image.height.toFloat())
                    val w = image.width * fit
                    val h = image.height * fit
                    Canvas(out).apply {
                        drawColor(background)
                        drawBitmap(
                            image,
                            null,
                            RectF((width - w) / 2f, (height - h) / 2f, (width + w) / 2f, (height + h) / 2f),
                            Paint(Paint.FILTER_BITMAP_FLAG),
                        )
                    }
                    image.recycle()
                    out
                }.getOrNull()
            }
            .toList()
    }

    /**
     * Creates a new ViewPager.
     */
    abstract fun createPager(): Pager

    /**
     * Returns the view this viewer uses.
     */
    override fun getView(): View {
        return pager
    }

    /**
     * Returns the PagerPageHolder for the provided page
     */
    private fun getPageHolder(page: ReaderPage): PagerPageHolder? =
        pager.children
            .filterIsInstance(PagerPageHolder::class.java)
            .firstOrNull { it.item == page }

    /**
     * Called when a new page (either a [ReaderPage] or [ChapterTransition]) is marked as active
     */
    private fun onPageChange(position: Int) {
        val page = adapter.items.getOrNull(position)
        if (page != null && currentPage != page) {
            val allowPreload = checkAllowPreload(page as? ReaderPage)
            val forward = when {
                currentPage is ReaderPage && page is ReaderPage -> {
                    // if both pages have the same number, it's a split page with an InsertPage
                    if (page.number == (currentPage as ReaderPage).number) {
                        // the InsertPage is always the second in the reading direction
                        page is InsertPage
                    } else {
                        page.number > (currentPage as ReaderPage).number
                    }
                }
                currentPage is ChapterTransition.Prev && page is ReaderPage ->
                    false
                else -> true
            }
            // Paso al capítulo siguiente: se cierra este manga y se abre el siguiente
            val entersNextChapter = page is ReaderPage &&
                (page.chapter == (currentPage as? ChapterTransition.Next)?.to ||
                    (currentPage is ReaderPage && page.chapter == adapter.nextTransition?.to))
            val bookEnabled = config.chapterBookAnimation && this !is VerticalPagerViewer
            if (bookEnabled && page is ChapterTransition.Next) {
                // La última página sigue montada junto a la transición: se guarda para el libro
                lastPageSnapshot = page.from.pages?.lastOrNull()?.let(::snapshotOf)
            }
            if (entersNextChapter && bookEnabled) {
                playChapterBook(previous = currentPage)
            }
            currentPage = page
            when (page) {
                is ReaderPage -> onReaderPageSelected(page, allowPreload, forward)
                is ChapterTransition -> onTransitionSelected(page)
            }
        }
    }

    private fun checkAllowPreload(page: ReaderPage?): Boolean {
        // Page is transition page - preload allowed
        page ?: return true

        // Initial opening - preload allowed
        currentPage ?: return true

        // Allow preload for
        // 1. Going to next chapter from chapter transition
        // 2. Going between pages of same chapter
        // 3. Next chapter page
        return when (page.chapter) {
            (currentPage as? ChapterTransition.Next)?.to -> true
            (currentPage as? ReaderPage)?.chapter -> true
            adapter.nextTransition?.to -> true
            else -> false
        }
    }

    /**
     * Called when a [ReaderPage] is marked as active. It notifies the
     * activity of the change and requests the preload of the next chapter if this is the last page.
     */
    private fun onReaderPageSelected(page: ReaderPage, allowPreload: Boolean, forward: Boolean) {
        val pages = page.chapter.pages ?: return
        logcat { "onReaderPageSelected: ${page.number}/${pages.size}" }
        activity.onPageSelected(page)

        // Notify holder of page change
        getPageHolder(page)?.onPageSelected(forward)

        // Skip preload on inserts it causes unwanted page jumping
        if (page is InsertPage) {
            return
        }

        // Preload next chapter once we're within the last 5 pages of the current chapter
        val inPreloadRange = pages.size - page.number < 5
        if (inPreloadRange && allowPreload && page.chapter == adapter.currentChapter) {
            logcat { "Request preload next chapter because we're at page ${page.number} of ${pages.size}" }
            adapter.nextTransition?.to?.let(activity::requestPreloadChapter)
        }
    }

    /**
     * Called when a [ChapterTransition] is marked as active. It request the
     * preload of the destination chapter of the transition.
     */
    private fun onTransitionSelected(transition: ChapterTransition) {
        logcat { "onTransitionSelected: $transition" }
        val toChapter = transition.to
        if (toChapter != null) {
            logcat { "Request preload destination chapter because we're on the transition" }
            activity.requestPreloadChapter(toChapter)
        } else if (transition is ChapterTransition.Next) {
            // No more chapters, show menu because the user is probably going to close the reader
            activity.showMenu()
        }
    }

    /**
     * Tells this viewer to set the given [chapters] as active. If the pager is currently idle,
     * it sets the chapters immediately, otherwise they are saved and set when it becomes idle.
     */
    override fun setChapters(chapters: ViewerChapters) {
        if (isIdle) {
            setChaptersInternal(chapters)
        } else {
            awaitingIdleViewerChapters = chapters
        }
    }

    /**
     * Sets the active [chapters] on this pager.
     */
    private fun setChaptersInternal(chapters: ViewerChapters) {
        // Remove listener so the change in item doesn't trigger it
        pager.removeOnPageChangeListener(pagerListener)

        val forceTransition = config.alwaysShowChapterTransition ||
            adapter.items.getOrNull(pager.currentItem) is ChapterTransition
        // Se abre un capítulo sin llegar a él pasando páginas (desde la ficha del manga o el menú)
        val chapterOfCurrent = when (val item = currentPage) {
            is ReaderPage -> item.chapter
            is ChapterTransition -> item.from
            else -> null
        }
        val opensChapter = config.chapterBookAnimation && this !is VerticalPagerViewer &&
            chapterOfCurrent != chapters.currChapter
        adapter.setChapters(chapters, forceTransition)

        // Layout the pager once a chapter is being set
        if (pager.isGone) {
            logcat { "Pager first layout" }
            val pages = chapters.currChapter.pages ?: return
            moveToPage(pages[min(chapters.currChapter.requestedPage, pages.lastIndex)])
            pager.isVisible = true
        }

        pager.addOnPageChangeListener(pagerListener)
        // Manually call onPageChange to update the UI
        onPageChange(pager.currentItem)
        if (opensChapter) playChapterBook(previous = null, openOnly = true)
    }

    /**
     * Tells this viewer to move to the given [page].
     */
    override fun moveToPage(page: ReaderPage) {
        val position = adapter.items.indexOf(page)
        if (position != -1) {
            val currentPosition = pager.currentItem
            pager.setCurrentItem(position, true)
            // manually call onPageChange since ViewPager listener is not triggered in this case
            if (currentPosition == position) {
                onPageChange(position)
            }
        } else {
            logcat { "Page $page not found in adapter" }
        }
    }

    /**
     * Moves to the next page.
     */
    open fun moveToNext() {
        moveRight()
    }

    /**
     * Moves to the previous page.
     */
    open fun moveToPrevious() {
        moveLeft()
    }

    /**
     * Moves to the page at the right.
     */
    protected open fun moveRight() {
        if (pager.currentItem != adapter.count - 1) {
            val holder = (currentPage as? ReaderPage)?.let(::getPageHolder)
            if (holder != null && config.navigateToPan && holder.canPanRight()) {
                holder.panRight()
            } else {
                pager.setCurrentItem(pager.currentItem + 1, config.usePageTransitions)
            }
        }
    }

    /**
     * Moves to the page at the left.
     */
    protected open fun moveLeft() {
        if (pager.currentItem != 0) {
            val holder = (currentPage as? ReaderPage)?.let(::getPageHolder)
            if (holder != null && config.navigateToPan && holder.canPanLeft()) {
                holder.panLeft()
            } else {
                pager.setCurrentItem(pager.currentItem - 1, config.usePageTransitions)
            }
        }
    }

    /**
     * Moves to the page at the top (or previous).
     */
    protected open fun moveUp() {
        moveToPrevious()
    }

    /**
     * Moves to the page at the bottom (or next).
     */
    protected open fun moveDown() {
        moveToNext()
    }

    /**
     * Resets the adapter in order to recreate all the views. Used when a image configuration is
     * changed.
     */
    private fun refreshAdapter() {
        val currentItem = pager.currentItem
        adapter.refresh()
        pager.adapter = adapter
        pager.setCurrentItem(currentItem, false)
    }

    /**
     * Called from the containing activity when a key [event] is received. It should return true
     * if the event was handled, false otherwise.
     */
    override fun handleKeyEvent(event: KeyEvent): Boolean {
        val isUp = event.action == KeyEvent.ACTION_UP
        val ctrlPressed = event.metaState.and(KeyEvent.META_CTRL_ON) > 0

        when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                if (!config.volumeKeysEnabled || activity.viewModel.state.value.menuVisible) {
                    return false
                } else if (isUp) {
                    if (!config.volumeKeysInverted) moveDown() else moveUp()
                }
            }
            KeyEvent.KEYCODE_VOLUME_UP -> {
                if (!config.volumeKeysEnabled || activity.viewModel.state.value.menuVisible) {
                    return false
                } else if (isUp) {
                    if (!config.volumeKeysInverted) moveUp() else moveDown()
                }
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (isUp) {
                    if (ctrlPressed) moveToNext() else moveRight()
                }
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (isUp) {
                    if (ctrlPressed) moveToPrevious() else moveLeft()
                }
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> if (isUp) moveDown()
            KeyEvent.KEYCODE_DPAD_UP -> if (isUp) moveUp()
            KeyEvent.KEYCODE_PAGE_DOWN -> if (isUp) moveDown()
            KeyEvent.KEYCODE_PAGE_UP -> if (isUp) moveUp()
            KeyEvent.KEYCODE_MENU -> if (isUp) activity.toggleMenu()
            else -> return false
        }
        return true
    }

    /**
     * Called from the containing activity when a generic motion [event] is received. It should
     * return true if the event was handled, false otherwise.
     */
    override fun handleGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.source and InputDevice.SOURCE_CLASS_POINTER != 0) {
            when (event.action) {
                MotionEvent.ACTION_SCROLL -> {
                    if (event.getAxisValue(MotionEvent.AXIS_VSCROLL) < 0.0f) {
                        moveDown()
                    } else {
                        moveUp()
                    }
                    return true
                }
            }
        }
        return false
    }

    fun onPageSplit(currentPage: ReaderPage, newPage: InsertPage) {
        activity.runOnUiThread {
            // Need to insert on UI thread else images will go blank
            adapter.onPageSplit(currentPage, newPage)
        }
    }

    private fun cleanupPageSplit() {
        adapter.cleanupPageSplit()
    }
}
