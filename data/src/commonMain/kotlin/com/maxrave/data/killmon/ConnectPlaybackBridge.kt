@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.maxrave.data.killmon

import com.maxrave.common.MERGING_DATA_TYPE
import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.data.model.connect.ConnectPlayback
import com.maxrave.domain.data.model.connect.ConnectSession
import com.maxrave.domain.data.model.connect.ConnectTrack
import com.maxrave.domain.data.model.searchResult.songs.Artist
import com.maxrave.domain.data.model.searchResult.songs.Thumbnail
import com.maxrave.domain.data.player.GenericMediaItem
import com.maxrave.domain.data.player.GenericMediaMetadata
import com.maxrave.domain.mediaservice.handler.MediaPlayerHandler
import com.maxrave.domain.mediaservice.handler.PlaylistType
import com.maxrave.domain.mediaservice.handler.QueueData
import com.maxrave.domain.repository.ListenTogetherRepository
import com.maxrave.logger.Logger
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Mantiene la presencia y aplica la transferencia.
 * El audio suena solo en el dispositivo activo. Este puente pausa el local cuando otro tiene el mando
 * y carga la cola cuando el mando llega aquí. Una sala JAM no se toca: es otro protocolo.
 */
internal class ConnectPlaybackBridge(
    private val connect: AccountConnectRepositoryImpl,
    private val handler: MediaPlayerHandler,
    private val jam: ListenTogetherRepository,
    private val scope: CoroutineScope,
) {
    private var started = false
    private var wasPlaying = false
    private var applying = false
    private var ackedTransfer = 0L
    private var ackedControl = 0L
    private var mirroredKey: String? = null

    fun start() {
        if (started) return
        started = true
        scope.launch {
            while (isActive) {
                if (connect.ensureReady()) break
                delay(200)
            }
            wasPlaying = withContext(Dispatchers.Main) { handler.player.playWhenReady }
            while (isActive) {
                connect.awaitKick(HEARTBEAT_MS)
                try {
                    step()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Logger.e(TAG, "No se pudo sincronizar el dispositivo", e)
                }
            }
        }
    }

    private suspend fun step() {
        val inJam = jam.room.value.inRoom
        val local = withContext(Dispatchers.Main) { localPlayback() }
        connect.localPlayback = local
        val before = connect.state.value
        val playing = withContext(Dispatchers.Main) { handler.player.playWhenReady }
        val edge = playing && !wasPlaying && !applying
        wasPlaying = playing
        val active = before.session?.activeDeviceId
        // Play local mientras se sigue al otro aparato: eso trae la sesión aquí, no publica la cola vieja.
        if (edge && !inJam && active != null && active != before.deviceId && before.isFollowingRemote()) {
            // El play local no debe soltar la cola vieja: se pausa y se pide la sesión remota.
            yieldLocal()
            connect.transferTo(before.deviceId)
            return
        }
        val pending = before.command
        val pendingSession = before.session
        if (!inJam && pending?.type == "transfer" && pendingSession != null && pending.revision > ackedTransfer) {
            applyTransfer(pendingSession)
            ackedTransfer = pending.revision
            wasPlaying = pendingSession.isPlaying
            connect.exchange(ConnectReport(ackTransfer = ackedTransfer))
            return
        }
        val publish = !inJam && local != null && (active == null || active == before.deviceId)
        connect.exchange(
            ConnectReport(
                playback = if (publish) local else null,
                ackTransfer = ackedTransfer.takeIf { it > 0L },
                ackControl = ackedControl.takeIf { it > 0L },
            ),
        )
        val state = connect.state.value
        if (!state.signedIn || inJam) {
            mirroredKey = null
            return
        }
        val session = state.session
        val command = state.command
        if (command?.type == "transfer" && session != null && command.revision > ackedTransfer) {
            applyTransfer(session)
            ackedTransfer = command.revision
            wasPlaying = session.isPlaying
        } else if (command?.type == "control" && command.revision > ackedControl && session?.activeDeviceId == state.deviceId) {
            applyControl(command.action, command.index ?: session.index, command.positionMs ?: session.positionMs)
            ackedControl = command.revision
        } else if (state.isFollowingRemote() && session != null) {
            yieldLocal()
            withContext(Dispatchers.Main) { mirrorQueue(session) }
        }
    }

    private suspend fun yieldLocal() {
        withContext(Dispatchers.Main) {
            val player = handler.player
            if (!player.playWhenReady && !player.isPlaying) return@withContext
            applying = true
            try {
                player.pause()
                player.playWhenReady = false
                wasPlaying = false
            } finally {
                applying = false
            }
        }
    }

    private suspend fun applyTransfer(session: ConnectSession) {
        if (session.queue.isEmpty()) return
        withContext(Dispatchers.Main) {
            applying = true
            try {
                val player = handler.player
                player.playWhenReady = false
                player.shuffleModeEnabled = false
                player.clearMediaItems()
                session.queue.forEach { player.addMediaItem(mediaItem(it)) }
                val index = session.index.coerceIn(0, session.queue.lastIndex)
                player.seekTo(index, session.estimatedPositionMs(Clock.System.now().toEpochMilliseconds()))
                player.prepare()
                player.playWhenReady = session.isPlaying
                handler.setQueueData(queueOf(session))
                handler.setCurrentSongIndex(index)
                mirroredKey = queueKey(session)
            } finally {
                applying = false
            }
        }
    }

    private suspend fun applyControl(
        action: String?,
        index: Int,
        positionMs: Long,
    ) {
        withContext(Dispatchers.Main) {
            applying = true
            try {
                val player = handler.player
                when (action) {
                    "play" -> {
                        player.play()
                        wasPlaying = true
                    }
                    "pause" -> {
                        player.pause()
                        wasPlaying = false
                    }
                    "seek" -> player.seekTo(positionMs.coerceAtLeast(0L))
                    "next", "previous" -> {
                        if (player.mediaItemCount > 0) player.seekTo(index.coerceIn(0, player.mediaItemCount - 1), positionMs.coerceAtLeast(0L))
                        handler.setCurrentSongIndex(index)
                    }
                }
            } finally {
                applying = false
            }
        }
    }

    private fun mirrorQueue(session: ConnectSession) {
        val key = queueKey(session)
        val shown = handler.queueData.value?.data?.listTracks?.map { it.videoId }
        if (key == mirroredKey && shown == session.queue.map { it.videoId }) return
        mirroredKey = key
        handler.setQueueData(queueOf(session))
        handler.setCurrentSongIndex(session.index.coerceIn(0, session.queue.lastIndex))
    }

    private fun localPlayback(): ConnectPlayback? {
        val fromQueue =
            handler.queueData.value?.data?.listTracks.orEmpty().mapNotNull { track ->
                if (!VIDEO.matches(track.videoId)) return@mapNotNull null
                ConnectTrack(
                    videoId = track.videoId,
                    title = track.title,
                    artist = track.artists.orEmpty().joinToString(", ") { it.name },
                    thumbnail = track.thumbnails?.lastOrNull()?.url.orEmpty(),
                    durationMs = (track.durationSeconds ?: 0) * 1000L,
                )
            }.take(MAX_QUEUE)
        if (fromQueue.isNotEmpty()) {
            val current = bare(handler.player.currentMediaItem?.mediaId.orEmpty())
            var index = fromQueue.indexOfFirst { it.videoId == current }
            if (index < 0) index = handler.player.currentMediaItemIndex.coerceIn(0, fromQueue.lastIndex)
            return ConnectPlayback(
                queue = fromQueue,
                index = index,
                positionMs = handler.player.currentPosition.coerceAtLeast(0L),
                isPlaying = handler.player.playWhenReady,
            )
        }
        val item = handler.player.currentMediaItem ?: return null
        val id = bare(item.mediaId)
        if (!VIDEO.matches(id)) return null
        return ConnectPlayback(
            queue =
                listOf(
                    ConnectTrack(
                        videoId = id,
                        title = item.metadata.title.orEmpty(),
                        artist = item.metadata.artist.orEmpty(),
                        thumbnail = item.metadata.artworkUri.orEmpty(),
                        durationMs = handler.player.duration.coerceAtLeast(0L),
                    ),
                ),
            index = 0,
            positionMs = handler.player.currentPosition.coerceAtLeast(0L),
            isPlaying = handler.player.playWhenReady,
        )
    }

    private fun queueOf(session: ConnectSession): QueueData.Data =
        QueueData.Data(
            listTracks = session.queue.map { it.toTrack() },
            playlistName = "Killmon",
            playlistType = PlaylistType.PLAYLIST,
        )

    private companion object {
        const val TAG = "KillmonConnect"
        const val HEARTBEAT_MS = 8_000L
        const val MAX_QUEUE = 40
        val VIDEO = Regex("^[A-Za-z0-9_-]{11}$")

        fun bare(id: String): String = id.removePrefix(MERGING_DATA_TYPE.VIDEO).removePrefix(MERGING_DATA_TYPE.SONG)

        fun queueKey(session: ConnectSession): String =
            session.revision.toString() + ":" + session.index + ":" + session.queue.joinToString(",") { it.videoId }

        fun mediaItem(track: ConnectTrack) =
            GenericMediaItem(
                mediaId = track.videoId,
                uri = "https://music.youtube.com/watch?v=${track.videoId}",
                metadata =
                    GenericMediaMetadata(
                        title = track.title,
                        artist = track.artist,
                        artworkUri = track.thumbnail.ifBlank { "https://i.ytimg.com/vi/${track.videoId}/hqdefault.jpg" },
                        description = MERGING_DATA_TYPE.SONG,
                    ),
                customCacheKey = track.videoId,
            )
    }
}

private fun ConnectTrack.toTrack(): Track {
    val url = thumbnail.ifBlank { "https://i.ytimg.com/vi/$videoId/hqdefault.jpg" }
    return Track(
        album = null,
        artists = artist.split(", ").filter { it.isNotBlank() }.map { Artist(id = null, name = it) },
        duration = null,
        durationSeconds = (durationMs / 1000L).toInt().takeIf { it > 0 },
        isAvailable = true,
        isExplicit = false,
        likeStatus = null,
        thumbnails = listOf(Thumbnail(544, url, 544)),
        title = title.ifBlank { videoId },
        videoId = videoId,
        videoType = null,
        category = null,
        feedbackTokens = null,
        resultType = null,
    )
}
