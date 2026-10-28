@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.maxrave.data.killmon

import com.maxrave.common.MERGING_DATA_TYPE
import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.data.model.connect.ConnectCommand
import com.maxrave.domain.data.model.connect.ConnectPlayback
import com.maxrave.domain.data.model.connect.ConnectSession
import com.maxrave.domain.data.model.connect.ConnectTrack
import com.maxrave.domain.data.model.connect.connectPollIntervalMs
import com.maxrave.domain.data.model.connect.controlAckIncludesPlayback
import com.maxrave.domain.data.model.connect.followerNeedsSilence
import com.maxrave.domain.data.model.connect.reportedTrackMatches
import com.maxrave.domain.data.model.connect.shouldPublishPlayback
import com.maxrave.domain.data.model.connect.toTransferLoad
import com.maxrave.domain.data.model.connect.transferAckIncludesPlayback
import com.maxrave.domain.data.model.connect.wasPlayingAfterControl
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.mediaservice.handler.PlayerEvent
import com.maxrave.domain.data.model.searchResult.songs.Artist
import com.maxrave.domain.data.model.searchResult.songs.Thumbnail
import com.maxrave.domain.data.player.GenericMediaItem
import com.maxrave.domain.data.player.GenericMediaMetadata
import com.maxrave.domain.mediaservice.handler.MediaPlayerHandler
import com.maxrave.domain.mediaservice.handler.PlaylistType
import com.maxrave.domain.mediaservice.handler.QueueData
import com.maxrave.domain.mediaservice.player.MediaPlayerListener
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
    private val dataStore: DataStoreManager,
    private val scope: CoroutineScope,
) {
    private var started = false
    private var wasPlaying = false
    private var applying = false
    private var silenced = false
    private var ackedTransfer = 0L
    private var ackedControl = 0L
    private var mirroredKey: String? = null
    private var lastPlayerKickMs = 0L
    private var suppressPlaybackUntil = 0L
    private var expectVideoId: String? = null

    fun start() {
        if (started) return
        started = true
        handler.player.addListener(
            object : MediaPlayerListener {
                override fun onIsPlayingChanged(isPlaying: Boolean) = kickFromPlayer()

                override fun onSeeked(positionMs: Long) = kickFromPlayer()

                override fun onMediaItemTransition(
                    mediaItem: GenericMediaItem?,
                    reason: Int,
                ) = kickFromPlayer()
            },
        )
        scope.launch {
            while (isActive) {
                if (connect.ensureReady()) break
                delay(200)
            }
            wasPlaying = withContext(Dispatchers.Main) { handler.player.playWhenReady }
            while (isActive) {
                try {
                    step()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Logger.e(TAG, "No se pudo sincronizar el dispositivo", e)
                }
                val live = connect.state.value.let { it.session != null || it.devices.size > 1 }
                connect.awaitKick(connectPollIntervalMs(live))
            }
        }
    }

    private fun kickFromPlayer() {
        if (applying) return
        val now = Clock.System.now().toEpochMilliseconds()
        if (now - lastPlayerKickMs < 400L) return
        lastPlayerKickMs = now
        connect.refresh()
    }

    private suspend fun step() {
        val inJam = jam.room.value.inRoom
        val before = connect.state.value
        val playing = withContext(Dispatchers.Main) { handler.player.playWhenReady }
        val edge = playing && !wasPlaying && !applying
        wasPlaying = playing
        val active = before.session?.activeDeviceId
        // Play local mientras se sigue al otro aparato: eso trae la sesión aquí, no publica la cola vieja.
        if (edge && !inJam && active != null && active != before.deviceId && before.isFollowingRemote()) {
            yieldLocal()
            connect.transferTo(before.deviceId)
            return
        }
        if (!inJam && applyPending(before.command, before.session, before.deviceId)) return
        // Sin playback: un comando que llegó en este intervalo no se pisa con la posición vieja.
        connect.exchange(ackReport())
        val state = connect.state.value
        if (!state.signedIn || inJam) {
            mirroredKey = null
            silenced = false
            return
        }
        if (applyPending(state.command, state.session, state.deviceId)) return
        val local = withContext(Dispatchers.Main) { localPlayback() }
        connect.localPlayback = local
        val now = Clock.System.now().toEpochMilliseconds()
        if (local != null && state.publishesLocalAudio(inJam) && canPublish(local, now)) {
            silenced = false
            connect.exchange(ackReport(playback = local))
        }
        val after = connect.state.value
        val session = after.session
        if (after.isFollowingRemote() && session != null) {
            yieldLocal()
            silenceLocal()
            withContext(Dispatchers.Main) { mirrorQueue(session) }
        } else {
            silenced = false
        }
    }

    private fun ackReport(playback: ConnectPlayback? = null) =
        ConnectReport(
            playback = playback,
            ackTransfer = ackedTransfer.takeIf { it > 0L },
            ackControl = ackedControl.takeIf { it > 0L },
        )

    /** Aplica transfer o control pendiente. Devuelve true si este paso ya publicó el resultado. */
    private suspend fun applyPending(
        command: ConnectCommand?,
        session: ConnectSession?,
        deviceId: String,
    ): Boolean {
        if (command == null || session == null) return false
        if (command.type == "transfer" && command.revision > ackedTransfer) {
            applyTransfer(session)
            ackedTransfer = command.revision
            silenced = false
            wasPlaying = session.isPlaying
            // El player aún no está en la pista nueva. Mandar localPlayback aquí cambia el videoId.
            val playback = if (transferAckIncludesPlayback()) withContext(Dispatchers.Main) { localPlayback() } else null
            connect.exchange(ackReport(playback = playback))
            return true
        }
        if (command.type == "control" && command.revision > ackedControl && session.activeDeviceId == deviceId) {
            val appliedVolume = applyControl(command, session)
            ackedControl = command.revision
            val ready = withContext(Dispatchers.Main) { handler.player.playWhenReady }
            wasPlaying = wasPlayingAfterControl(command.action, session.isPlaying, ready)
            suppressPlaybackUntil = Clock.System.now().toEpochMilliseconds() + 400L
            if (appliedVolume != null) dataStore.setPlayerVolume(appliedVolume)
            // The ack confirms the command. Playback goes on the next heartbeat, after the backend
            // has the new play/pause. Sending both in one presence let the old is_playing win.
            val playback = if (controlAckIncludesPlayback()) withContext(Dispatchers.Main) { localPlayback() } else null
            connect.exchange(ackReport(playback = playback))
            return true
        }
        return false
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

    /** El seguidor no abre un segundo stream. Si la cola local reaparece, se vacía otra vez. */
    private suspend fun silenceLocal() {
        withContext(Dispatchers.Main) {
            val player = handler.player
            val noisy = followerNeedsSilence(player.mediaItemCount, player.playWhenReady, player.isPlaying)
            if (silenced && !noisy) return@withContext
            applying = true
            try {
                if (player.mediaItemCount > 0) player.clearMediaItems()
                player.playWhenReady = false
                silenced = true
                wasPlaying = false
            } finally {
                applying = false
            }
        }
    }

    private fun canPublish(local: ConnectPlayback, nowMs: Long): Boolean {
        val id = local.queue.getOrNull(local.index)?.videoId
        if (!reportedTrackMatches(id, expectVideoId)) return false
        if (!shouldPublishPlayback(nowMs, suppressPlaybackUntil)) return false
        expectVideoId = null
        return true
    }

    private suspend fun applyTransfer(session: ConnectSession) {
        val load = session.toTransferLoad(Clock.System.now().toEpochMilliseconds()) ?: return
        expectVideoId = load.videoId
        withContext(Dispatchers.Main) {
            applying = true
            try {
                handler.player.loadPlaylistAt(
                    items = session.queue.map { mediaItem(it) },
                    index = load.index,
                    positionMs = load.positionMs,
                    play = load.play,
                )
                handler.setQueueData(queueOf(session))
                handler.setCurrentSongIndex(load.index)
                mirroredKey = queueKey(session)
            } finally {
                applying = false
            }
        }
    }

    private suspend fun applyControl(
        command: ConnectCommand,
        session: ConnectSession,
    ): Float? {
        val action = command.action
        val index = command.index ?: session.index
        val positionMs = command.positionMs ?: session.positionMs
        var appliedVolume: Float? = null
        withContext(Dispatchers.Main) {
            applying = true
            try {
                val player = handler.player
                when (action) {
                    "play" -> {
                        player.audioFocusLost = false
                        player.playWhenReady = true
                    }
                    "pause" -> {
                        player.playWhenReady = false
                    }
                    "seek" -> player.seekTo(positionMs.coerceAtLeast(0L))
                    "next", "previous" -> {
                        if (player.mediaItemCount > 0) player.seekTo(index.coerceIn(0, player.mediaItemCount - 1), positionMs.coerceAtLeast(0L))
                        handler.setCurrentSongIndex(index)
                    }
                    "volume" -> {
                        val level = (command.volume ?: session.volume).coerceIn(0f, 1f)
                        handler.onPlayerEvent(PlayerEvent.UpdateVolume(level))
                        appliedVolume = level
                    }
                }
            } finally {
                applying = false
            }
        }
        return appliedVolume
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
                volume = handler.player.volume.coerceIn(0f, 1f),
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
            volume = handler.player.volume.coerceIn(0f, 1f),
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
        const val MAX_QUEUE = 40
        val VIDEO = Regex("^[A-Za-z0-9_-]{11}$")

        fun bare(id: String): String = id.removePrefix(MERGING_DATA_TYPE.VIDEO).removePrefix(MERGING_DATA_TYPE.SONG)

        fun queueKey(session: ConnectSession): String =
            session.revision.toString() + ":" + session.index + ":" + session.queue.joinToString(",") { it.videoId }

        fun mediaItem(track: ConnectTrack) =
            GenericMediaItem(
                mediaId = track.videoId,
                uri = track.videoId,
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
