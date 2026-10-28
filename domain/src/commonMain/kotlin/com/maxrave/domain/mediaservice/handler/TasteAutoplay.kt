package com.maxrave.domain.mediaservice.handler

import com.maxrave.domain.data.model.browse.album.Track

/**
 * What to append when a queue the user picked — Favorites, a playlist, an album — is about to run
 * out, so playback keeps going the way a radio does.
 *
 * The handler still asks YouTube for the tracks. This only decides which video ids are worth
 * queuing: the radio of the song that is playing, then radios seeded from taste (a recent like, a
 * song by a most-played artist), minus anything already in the queue or played recently.
 */
object TasteAutoplay {
    /** Append at most this many tracks per batch. YouTube's own radio page is larger. */
    const val MAX_APPEND = 20

    /**
     * How many tracks from the current song's radio to keep before mixing taste in, when taste
     * actually contributed something. The rest of the batch is taste, and if taste is short the
     * current radio fills back up to [MAX_APPEND].
     */
    const val CURRENT_BEFORE_TASTE = 14

    /**
     * Top up when this many tracks are left, counting the one that is playing. Two remaining is
     * the song in progress plus one after it — enough time to fetch the next batch before the
     * queue ends.
     */
    const val LOW_WATER = 2

    private val VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$")

    fun isVideoId(id: String): Boolean = VIDEO_ID.matches(id)

    /** A song the listener has played, reduced to what seed-picking needs. */
    data class PlayedSong(
        val videoId: String,
        val artistIds: List<String>,
    )

    /** One extra radio from likes, and one from the artists the listener plays most. */
    data class Seeds(
        val likedVideoId: String?,
        val artistVideoId: String?,
    )

    fun shouldTopUp(
        queueSize: Int,
        currentIndex: Int,
    ): Boolean {
        if (queueSize <= 0 || currentIndex < 0) return false
        val remaining = queueSize - currentIndex
        return remaining in 0..LOW_WATER
    }

    /**
     * [recentLikedVideoIds] is newest first. [topArtistIds] is most-played first. The artist seed
     * prefers a song by one of those artists, and falls back to any other played song so a library
     * with no artist ids still moves the radio off the current track.
     */
    fun pickSeeds(
        currentVideoId: String,
        recentLikedVideoIds: List<String>,
        mostPlayed: List<PlayedSong>,
        topArtistIds: List<String>,
    ): Seeds {
        val liked =
            recentLikedVideoIds.firstOrNull { it != currentVideoId && isVideoId(it) }
        val ranked = topArtistIds.filter { it.isNotBlank() }.toSet()
        val artistFromRank =
            if (ranked.isEmpty()) {
                null
            } else {
                mostPlayed.firstOrNull { song ->
                    song.videoId != currentVideoId &&
                        song.videoId != liked &&
                        isVideoId(song.videoId) &&
                        song.artistIds.any { it in ranked }
                }?.videoId
            }
        val artist =
            artistFromRank
                ?: mostPlayed.firstOrNull { song ->
                    song.videoId != currentVideoId && song.videoId != liked && isVideoId(song.videoId)
                }?.videoId
        return Seeds(likedVideoId = liked, artistVideoId = artist)
    }

    /**
     * Ids to append, current radio first. Invalid ids and anything in [exclude] are dropped.
     * Duplicates across the pages count once. At most [MAX_APPEND].
     */
    fun merge(
        currentRadio: List<String>,
        tasteRadios: List<List<String>>,
        exclude: Set<String>,
    ): List<String> {
        val blocked = HashSet<String>(exclude.size + MAX_APPEND)
        blocked.addAll(exclude)
        val current = ArrayList<String>()
        for (id in currentRadio) {
            if (!isVideoId(id) || !blocked.add(id)) continue
            current.add(id)
        }
        val taste = ArrayList<String>()
        for (page in tasteRadios) {
            for (id in page) {
                if (!isVideoId(id) || !blocked.add(id)) continue
                taste.add(id)
            }
        }
        if (taste.isEmpty()) return current.take(MAX_APPEND)
        val head = current.take(CURRENT_BEFORE_TASTE)
        val fromTaste = taste.take((MAX_APPEND - head.size).coerceAtLeast(0))
        val tail =
            current.drop(head.size).take((MAX_APPEND - head.size - fromTaste.size).coerceAtLeast(0))
        return head + fromTaste + tail
    }

    fun pickTracks(
        current: List<Track>,
        taste: List<List<Track>>,
        exclude: Set<String>,
    ): List<Track> {
        val ids = merge(current.map { it.videoId }, taste.map { page -> page.map { it.videoId } }, exclude)
        if (ids.isEmpty()) return emptyList()
        val byId = LinkedHashMap<String, Track>()
        for (track in current) {
            if (track.videoId !in byId) byId[track.videoId] = track
        }
        for (track in taste.flatten()) {
            if (track.videoId !in byId) byId[track.videoId] = track
        }
        return ids.mapNotNull { byId[it] }
    }
}
