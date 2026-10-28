package com.maxrave.domain.data.model.connect

import com.maxrave.common.MERGING_DATA_TYPE
import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.data.model.searchResult.songs.Artist
import com.maxrave.domain.data.model.searchResult.songs.Thumbnail
import com.maxrave.domain.data.player.GenericMediaItem

private val CONNECT_VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$")

/**
 * Mientras otro aparato tiene el audio, un play local no debe tomar el mando.
 * El router lo instala el puente. Si no hay sesión remota, [redirect] deja sonar aquí.
 */
object ConnectPlayRouter {
    fun interface Offer {
        fun offer(tracks: List<Track>, index: Int): Boolean
    }

    var offer: Offer? = null

    /** True cuando este aparato sigue a otro. Lo escribe el puente en cada arranque. */
    var followingRemote: () -> Boolean = { false }

    fun blocksLocal(): Boolean = followingRemote()

    /**
     * Manda la cola al aparato activo y pide al caller que no abra stream local.
     * Devuelve true aunque la cola venga vacía: en ese caso tampoco hay que sonar aquí.
     */
    fun redirect(tracks: List<Track>, index: Int): Boolean {
        if (!blocksLocal()) return false
        if (tracks.isNotEmpty()) offer?.offer(tracks, index)
        return true
    }
}

fun bareConnectMediaId(mediaId: String): String =
    mediaId.removePrefix(MERGING_DATA_TYPE.VIDEO).removePrefix(MERGING_DATA_TYPE.SONG)

fun Track.toConnectTrack(): ConnectTrack? {
    if (!CONNECT_VIDEO_ID.matches(videoId)) return null
    return ConnectTrack(
        videoId = videoId,
        title = title,
        artist = artists.orEmpty().joinToString(", ") { it.name },
        thumbnail = thumbnails?.lastOrNull()?.url.orEmpty(),
        durationMs = (durationSeconds ?: 0) * 1000L,
    )
}

fun GenericMediaItem.asConnectPlayTrack(): Track {
    val id = bareConnectMediaId(mediaId)
    val art = metadata.artworkUri
    return Track(
        album = null,
        artists = listOf(Artist(id = null, name = metadata.artist.orEmpty())),
        duration = null,
        durationSeconds = null,
        isAvailable = true,
        isExplicit = false,
        likeStatus = null,
        thumbnails = art?.takeIf { it.isNotBlank() }?.let { listOf(Thumbnail(height = 544, url = it, width = 544)) },
        title = metadata.title.orEmpty().ifBlank { id },
        videoId = id,
        videoType = null,
        category = null,
        feedbackTokens = null,
        resultType = null,
    )
}

/** Cola que hay que mandar: la que ya contiene la pista elegida, o esa pista sola. */
fun queueForRemotePlay(queue: List<Track>, played: Track): List<Track> =
    if (queue.any { it.videoId == played.videoId }) queue else listOf(played)

fun indexForRemotePlay(queue: List<Track>, videoId: String, requested: Int?): Int {
    if (queue.isEmpty()) return 0
    if (requested != null && requested in queue.indices && queue[requested].videoId == videoId) return requested
    val found = queue.indexOfFirst { it.videoId == videoId }
    return if (found >= 0) found else requested?.coerceIn(0, queue.lastIndex) ?: 0
}

/**
 * Carga en el aparato que ya suena. No cambia el activo.
 * [index] es el de [tracks] antes de filtrar ids que el protocolo no acepta.
 */
fun connectPlaybackFromTracks(tracks: List<Track>, index: Int, volume: Float = 1f): ConnectPlayback? {
    val chosen = tracks.getOrNull(index)?.videoId
    val queue = tracks.mapNotNull { it.toConnectTrack() }.take(40)
    if (queue.isEmpty()) return null
    val target =
        if (chosen != null) {
            queue.indexOfFirst { it.videoId == chosen }.let { if (it >= 0) it else 0 }
        } else {
            index.coerceIn(0, queue.lastIndex)
        }
    return ConnectPlayback(
        queue = queue,
        index = target,
        positionMs = 0L,
        isPlaying = true,
        volume = volume.coerceIn(0f, 1f),
    )
}

/** La misma cola y el mismo índice que ya suena no se reenvían: un flanco local no reinicia la pista. */
fun shouldPushLocalPick(local: ConnectPlayback?, session: ConnectSession?): Boolean {
    if (local == null || session == null) return false
    val picked = local.queue.getOrNull(local.index)?.videoId ?: return false
    val current = session.currentTrack()?.videoId
    if (picked != current) return true
    return local.index != session.index
}

/**
 * Un seek sobre la pista que ya está cargada mueve `time-pos`.
 * `seekTo(index, position)` en mpv y en ExoPlayer con crossfade suelta el handle y vuelve a abrir el archivo.
 */
enum class LocalPlayRoute { LOCAL, REMOTE_LOAD }

/** Un toque con otro aparato activo no toma el mando: la pista se carga allí. */
fun localPlayRoute(followingRemote: Boolean): LocalPlayRoute =
    if (followingRemote) LocalPlayRoute.REMOTE_LOAD else LocalPlayRoute.LOCAL

fun seekAppliesInPlace(
    action: String?,
    currentMediaId: String?,
    targetVideoId: String?,
    currentIndex: Int,
    targetIndex: Int,
    itemCount: Int,
): Boolean {
    if (action != "seek" || itemCount <= 0) return false
    if (!targetVideoId.isNullOrBlank() && bareConnectMediaId(currentMediaId.orEmpty()) == targetVideoId) return true
    return currentIndex == targetIndex.coerceIn(0, itemCount - 1)
}
