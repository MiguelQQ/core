package com.maxrave.data.mediaservice

import com.maxrave.domain.data.entities.SongEntity
import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.mediaservice.handler.TasteAutoplay
import com.maxrave.domain.repository.AnalyticsRepository
import com.maxrave.domain.repository.SongRepository
import com.maxrave.domain.utils.Resource
import com.maxrave.logger.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One radio batch for the endless queue: watch-next of the playing track (`RDAMVM…` via
 * [SongRepository.getRelatedData]), plus the same call seeded from a recent like and from a song
 * by a most-played artist. Runs off the caller thread. A second call while one is in flight
 * returns [TasteRadioOutcome.Busy] instead of starting another fetch.
 */
internal class TasteRadioLoader(
    private val songRepository: SongRepository,
    private val analyticsRepository: AnalyticsRepository,
) {
    private val gate = Mutex()

    suspend fun load(
        seedVideoId: String,
        queueIds: Set<String>,
    ): TasteRadioOutcome {
        if (!gate.tryLock()) {
            Logger.d(TAG, "Taste autoplay skipped: fetch already running")
            return TasteRadioOutcome.Busy
        }
        try {
            return withContext(Dispatchers.IO) {
                loadLocked(seedVideoId, queueIds)
            }
        } finally {
            gate.unlock()
        }
    }

    private suspend fun loadLocked(
        seedVideoId: String,
        queueIds: Set<String>,
    ): TasteRadioOutcome {
        try {
            val recent = recentVideoIds()
            val liked = likedSongs()
            val mostPlayed = mostPlayedSongs()
            val topArtists = topArtistIds(mostPlayed)
            val seeds =
                TasteAutoplay.pickSeeds(
                    currentVideoId = seedVideoId,
                    recentLikedVideoIds = liked.map { it.videoId },
                    mostPlayed =
                        mostPlayed.map { song ->
                            TasteAutoplay.PlayedSong(song.videoId, song.artistId.orEmpty())
                        },
                    topArtistIds = topArtists,
                )
            val attempts = ArrayList<RelatedPage>(3)
            val primary = related(seedVideoId)
            attempts.add(primary)
            val likedPage = seeds.likedVideoId?.let { related(it).also { page -> attempts.add(page) } }
            val artistPage = seeds.artistVideoId?.let { related(it).also { page -> attempts.add(page) } }
            val picked =
                TasteAutoplay.pickTracks(
                    current = primary.tracks,
                    taste = listOfNotNull(likedPage?.tracks, artistPage?.tracks),
                    exclude = queueIds + recent + setOf(seedVideoId),
                )
            if (picked.isEmpty()) {
                val failed = attempts.isNotEmpty() && attempts.all { it.failed }
                Logger.w(
                    TAG,
                    if (failed) {
                        "Taste autoplay failed for $seedVideoId"
                    } else {
                        "Taste autoplay found nothing new for $seedVideoId"
                    },
                )
                return if (failed) TasteRadioOutcome.Failed else TasteRadioOutcome.Empty
            }
            val radioId = if (TasteAutoplay.isVideoId(seedVideoId)) "RDAMVM$seedVideoId" else null
            Logger.d(
                TAG,
                "Taste autoplay: ${picked.size} tracks, seed=$seedVideoId, like=${seeds.likedVideoId}, artist=${seeds.artistVideoId}",
            )
            return TasteRadioOutcome.Ready(
                TasteRadioBatch(
                    tracks = picked,
                    continuation = if (radioId != null) primary.continuation else null,
                    radioPlaylistId = radioId,
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(TAG, "Taste autoplay failed: ${e.message}")
            return TasteRadioOutcome.Failed
        }
    }

    private suspend fun recentVideoIds(): Set<String> =
        try {
            analyticsRepository.getPlaybackEventsByOffset(0, RECENT_PLAYS).first().map { it.videoId }.toSet()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(TAG, "Taste autoplay recent plays unavailable: ${e.message}")
            emptySet()
        }

    private suspend fun likedSongs() =
        try {
            songRepository.getLikedSongs().first().take(LIKED_LOOKBACK)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(TAG, "Taste autoplay likes unavailable: ${e.message}")
            emptyList()
        }

    private suspend fun mostPlayedSongs() =
        try {
            songRepository.getMostPlayedSongs().first().take(MOST_PLAYED_LOOKBACK)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(TAG, "Taste autoplay most played unavailable: ${e.message}")
            emptyList()
        }

    private suspend fun topArtistIds(mostPlayed: List<SongEntity>): List<String> {
        val fromLibrary =
            mostPlayed
                .flatMap { it.artistId.orEmpty() }
                .groupingBy { it }
                .eachCount()
                .entries
                .sortedByDescending { it.value }
                .map { it.key }
        val fromAnalytics =
            try {
                analyticsRepository.queryTopArtistsLastXDays(ARTIST_WINDOW_DAYS).first().map { it.channelId }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w(TAG, "Taste autoplay top artists unavailable: ${e.message}")
                emptyList()
            }
        return (fromAnalytics + fromLibrary).filter { it.isNotBlank() }.distinct().take(TOP_ARTISTS)
    }

    private suspend fun related(videoId: String): RelatedPage {
        if (!TasteAutoplay.isVideoId(videoId)) {
            return RelatedPage(emptyList(), null, failed = true)
        }
        return try {
            val resource =
                withTimeoutOrNull(RELATED_TIMEOUT_MS) {
                    songRepository.getRelatedData(videoId).first()
                }
            if (resource == null) {
                Logger.w(TAG, "Taste autoplay timed out for $videoId")
                return RelatedPage(emptyList(), null, failed = true)
            }
            when (resource) {
                is Resource.Success ->
                    RelatedPage(
                        tracks = resource.data?.first.orEmpty(),
                        continuation = resource.data?.second,
                        failed = false,
                    )

                is Resource.Error -> {
                    Logger.w(TAG, "Taste autoplay related failed for $videoId: ${resource.message}")
                    RelatedPage(emptyList(), null, failed = true)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(TAG, "Taste autoplay related failed for $videoId: ${e.message}")
            RelatedPage(emptyList(), null, failed = true)
        }
    }

    private data class RelatedPage(
        val tracks: List<Track>,
        val continuation: String?,
        val failed: Boolean,
    )

    private companion object {
        const val TAG = "TasteAutoplay"
        const val RELATED_TIMEOUT_MS = 15_000L
        const val RECENT_PLAYS = 80
        const val LIKED_LOOKBACK = 15
        const val MOST_PLAYED_LOOKBACK = 40
        const val ARTIST_WINDOW_DAYS = 90
        const val TOP_ARTISTS = 5
    }
}

internal data class TasteRadioBatch(
    val tracks: List<Track>,
    val continuation: String?,
    /** `RDAMVM` of the playing track, when that id is a YouTube video id. */
    val radioPlaylistId: String?,
)

internal sealed interface TasteRadioOutcome {
    data class Ready(
        val batch: TasteRadioBatch,
    ) : TasteRadioOutcome

    data object Busy : TasteRadioOutcome

    /** YouTube answered, and every track was already queued or recently played. */
    data object Empty : TasteRadioOutcome

    /** The radio calls failed or timed out. */
    data object Failed : TasteRadioOutcome
}
