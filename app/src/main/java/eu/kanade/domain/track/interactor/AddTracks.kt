package eu.kanade.domain.track.interactor

import dev.zacsweers.metro.Inject
import eu.kanade.domain.track.model.toDbTrack
import eu.kanade.domain.track.model.toDomainTrack
import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.track.EnhancedTracker
import eu.kanade.tachiyomi.data.track.Tracker
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.source.Source
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import mihon.core.metro.AppCoroutineScope
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.interactor.UpsertTrack
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

@Inject
class AddTracks(
    private val upsertTrack: UpsertTrack,
    private val syncChapterProgressWithTrack: SyncChapterProgressWithTrack,
    private val getChaptersByMangaId: GetChaptersByMangaId,
    private val trackerManager: TrackerManager,
    private val getHistory: GetHistory,
    private val getTracks: GetTracks,
    private val getLibraryManga: GetLibraryManga,
    private val trackPreferences: TrackPreferences,
    @AppCoroutineScope private val appScope: CoroutineScope,
) {

    // TODO: update all trackers based on common data
    suspend fun bind(tracker: Tracker, item: Track, mangaId: Long) = withContext(Dispatchers.IO) {
        val allChapters = getChaptersByMangaId.await(mangaId)
        val hasReadChapters = allChapters.any { it.read }
        tracker.bind(item, hasReadChapters)

        var track = item.toDomainTrack(idRequired = false) ?: return@withContext

        upsertTrack.await(track)

        // TODO: merge into [SyncChapterProgressWithTrack]?
        // Update chapter progress if newer chapters marked read locally
        if (hasReadChapters) {
            val latestLocalReadChapterNumber = allChapters
                .sortedBy { it.chapterNumber }
                .takeWhile { it.read }
                .lastOrNull()
                ?.chapterNumber ?: -1.0

            if (latestLocalReadChapterNumber > track.lastChapterRead) {
                track = track.copy(
                    lastChapterRead = latestLocalReadChapterNumber,
                )
                tracker.setRemoteLastChapterRead(track.toDbTrack(), latestLocalReadChapterNumber.toInt())
            }

            if (track.startDate == null) {
                val firstReadChapterDate = getHistory.await(mangaId)
                    .mapNotNull { it.readAt }
                    .minOrNull()

                firstReadChapterDate?.let {
                    val startDate = firstReadChapterDate.toEpochMilliseconds()
                    track = track.copy(
                        startDate = Instant.fromEpochMilliseconds(startDate),
                    )
                    tracker.setRemoteStartDate(track.toDbTrack(), startDate)
                }
            }
        }

        syncChapterProgressWithTrack.await(mangaId, track, tracker)
    }

    suspend fun bindEnhancedTrackers(manga: Manga, source: Source) = withContext(Dispatchers.IO) {
        trackerManager.loggedInTrackers()
            .filterIsInstance<EnhancedTracker>()
            .filter { it.accept(source) }
            .forEach { service ->
                try {
                    service.match(manga)?.let { track ->
                        track.manga_id = manga.id
                        (service as Tracker).bind(track)
                        upsertTrack.await(track.toDomainTrack(idRequired = false)!!)

                        syncChapterProgressWithTrack.await(
                            manga.id,
                            track.toDomainTrack(idRequired = false)!!,
                            service,
                        )
                    }
                } catch (e: Exception) {
                    logcat(
                        LogPriority.WARN,
                        e,
                    ) { "Could not match manga: ${manga.title} with service $service" }
                }
            }

        if (trackPreferences.autoTrackOnLibraryAdd.get()) {
            bindAutomaticTrackers(manga)
        }
    }

    /**
     * Vincula el manga con los servicios de seguimiento con sesión iniciada (AniList, MyAnimeList…)
     * en los que aún no está, buscándolo por su título.
     *
     * @return número de servicios vinculados.
     */
    suspend fun bindAutomaticTrackers(manga: Manga): Int = withContext(Dispatchers.IO) {
        var bound = 0
        pendingAutomaticTrackers(manga).forEach { tracker ->
            try {
                val match = bestMatch(manga.title, tracker.search(manga.title)) ?: return@forEach
                match.manga_id = manga.id
                bind(tracker, match, manga.id)
                bound++
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "Could not auto track manga: ${manga.title} with service $tracker" }
            }
        }
        bound
    }

    /** Vincula en segundo plano todos los mangas de la biblioteca que aún no tienen seguimiento. */
    fun bindAutomaticTrackersForLibraryInBackground(onFinished: suspend (linkedManga: Int) -> Unit) {
        appScope.launch {
            var linked = 0
            getLibraryManga.await()
                .distinctBy { it.manga.id }
                .forEach { libraryManga ->
                    if (pendingAutomaticTrackers(libraryManga.manga).isEmpty()) return@forEach
                    if (bindAutomaticTrackers(libraryManga.manga) > 0) linked++
                    // Evita superar el límite de peticiones de los servicios
                    delay(1.5.seconds)
                }
            onFinished(linked)
        }
    }

    private suspend fun pendingAutomaticTrackers(manga: Manga): List<Tracker> {
        val trackedIds = getTracks.await(manga.id).map { it.trackerId }.toSet()
        return trackerManager.loggedInTrackers()
            .filter { it !is EnhancedTracker && it.id !in trackedIds }
    }

    private fun bestMatch(title: String, results: List<TrackSearch>): TrackSearch? {
        val wanted = title.normalizedTitle()
        return results.firstOrNull { it.title.normalizedTitle() == wanted }
            ?: results.firstOrNull {
                val candidate = it.title.normalizedTitle()
                candidate.isNotEmpty() && (candidate.contains(wanted) || wanted.contains(candidate))
            }
            ?: results.firstOrNull()
    }

    private fun String.normalizedTitle() = lowercase().filter { it.isLetterOrDigit() }
}
