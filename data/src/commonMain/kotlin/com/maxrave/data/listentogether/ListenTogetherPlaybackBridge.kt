package com.maxrave.data.listentogether

import com.maxrave.common.MERGING_DATA_TYPE
import com.maxrave.domain.data.player.JamPlaybackRouter
import com.maxrave.domain.data.player.GenericMediaItem
import com.maxrave.domain.data.player.GenericMediaMetadata
import com.maxrave.domain.mediaservice.handler.MediaPlayerHandler
import com.maxrave.domain.mediaservice.player.MediaPlayerListener
import com.maxrave.domain.repository.ListenTogetherRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.put
import org.simpmusic.listentogether.*

/** Reuses the inherited player bridge, with S2 audio and the same barrier for host and guests. */
class ListenTogetherPlaybackBridge(
    private val repository: ListenTogetherRepository,
    private val session: ListenTogetherSession,
    private val handler: MediaPlayerHandler,
    private val scope: CoroutineScope,
) {
    private var started = false
    private var applying = false
    private var loadedSeq = -1L
    private var readySeq = -1L
    private var endedSeq = -1L
    private var lastClock = 0L
    private var savedParameters = com.maxrave.domain.data.player.GenericPlaybackParameters()
    private var savedSkipSilence = false
    private var savedShuffle = false
    private var savedRepeat = 0
    private var lastRoomCode: String? = null
    private var lastRevisionTime = 0L
    private val listener = object : MediaPlayerListener {
        override fun onSeeked(positionMs: Long) {
            val room = session.state.value
            if (!applying && room.inRoom && room.isHost && !room.loading) session.command("seek") { put("position", positionMs / 1000.0) }
        }
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            val room = session.state.value
            if (!applying && room.inRoom && room.isHost && !room.loading && loadedSeq == room.seq && handler.player.playbackState == 3 && isPlaying == handler.player.playWhenReady) {
                session.command(if (isPlaying) "play" else "pause") { put("position", handler.player.currentPosition / 1000.0) }
            }
        }
    }
    private val actions = object : JamPlaybackRouter.Actions {
        override fun add(item: GenericMediaItem) {
            session.suggestTrack(TrackInfo(id = item.mediaId, title = item.metadata.title.orEmpty(), artist = item.metadata.artist.orEmpty(), thumbnail = item.metadata.artworkUri.orEmpty()))
        }
        override fun control(type: String, positionMs: Long?) {
            if (!session.state.value.isHost) return
            session.command(type) { positionMs?.let { put("position", it.coerceAtLeast(0L) / 1000.0) } }
        }
    }
    fun start() {
        if (started) return
        started = true
        scope.launch(Dispatchers.Main) { handler.player.addListener(listener) }
        scope.launch {
            session.state.map { Triple(it.roomCode, it.seq, it.connection is ConnectionState.Connected) to it.selfUserId }.distinctUntilChanged().collectLatest { (key, selfId) ->
                val (code, seq, connected) = key
                if (code != null && selfId.isBlank()) return@collectLatest
                JamPlaybackRouter.actions = if (code == null) null else actions
                if (code == null) {
                    withContext(Dispatchers.Main) {
                        handler.player.crossfadeSuppressed = false
                        if (lastRoomCode != null) {
                            applying = true
                            handler.player.pause(); handler.clearMediaItems()
                            handler.player.playbackParameters = savedParameters
                            handler.player.skipSilenceEnabled = savedSkipSilence
                            handler.player.shuffleModeEnabled = savedShuffle; handler.player.repeatMode = savedRepeat
                            applying = false
                        }
                    }
                    lastRoomCode = null; loadedSeq = -1; readySeq = -1
                    return@collectLatest
                }
                if (!connected) {
                    loadedSeq = -1; readySeq = -1
                    withContext(Dispatchers.Main) { applying = true; handler.player.pause(); applying = false }
                    return@collectLatest
                }
                if (lastRoomCode != code) {
                    val wasEmpty = session.state.value.currentTrack == null
                    val current = withContext(Dispatchers.Main) {
                        savedParameters = handler.player.playbackParameters
                        savedSkipSilence = handler.player.skipSilenceEnabled
                        savedShuffle = handler.player.shuffleModeEnabled; savedRepeat = handler.player.repeatMode
                        handler.player.crossfadeSuppressed = true
                        applying = true
                        val item = handler.player.currentMediaItem
                        handler.player.pause()
                        applying = false
                        item
                    }
                    lastRoomCode = code
                    if (wasEmpty && session.state.value.isHost && current != null && Regex("^[A-Za-z0-9_-]{11}$").matches(current.mediaId)) {
                        session.suggestTrack(TrackInfo(id = current.mediaId, title = current.metadata.title.orEmpty(), artist = current.metadata.artist.orEmpty()))
                    }
                }
                val track = session.state.value.currentTrack ?: run {
                    withContext(Dispatchers.Main) { applying = true; handler.player.pause(); handler.clearMediaItems(); applying = false }
                    return@collectLatest
                }
                withContext(Dispatchers.Main) { applying = true; handler.player.pause(); applying = false }
                try {
                    val url = session.audioUrl(track.id)
                    withContext(Dispatchers.Main) {
                        applying = true
                        try {
                            handler.clearMediaItems()
                            handler.addMediaItem(GenericMediaItem(
                                mediaId = track.id, uri = url,
                                metadata = GenericMediaMetadata(title = track.title, artist = track.artist, artworkUri = track.thumbnail, description = MERGING_DATA_TYPE.SONG),
                                customCacheKey = "jam:$code:$seq:${track.id}",
                            ), playWhenReady = false)
                            handler.player.shuffleModeEnabled = false; handler.player.repeatMode = 0
                            handler.player.playbackParameters = com.maxrave.domain.data.player.GenericPlaybackParameters(1f, 1f)
                            if (!session.state.value.loading) handler.player.seekTo(session.positionAt(session.state.value.position, session.state.value.isPlaying))
                            loadedSeq = seq; readySeq = -1; endedSeq = -1
                        } finally { applying = false }
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { session.playbackError("No se pudo cargar el audio de la JAM. Reintenta entrando a la sala.") }
            }
        }
        scope.launch(Dispatchers.Main) {
            while (isActive) {
                delay(150)
                val room = session.state.value
                if (!room.inRoom || room.connection !is ConnectionState.Connected || loadedSeq != room.seq) continue
                val player = handler.player
                // Any external player navigation is stopped; the room has one server-selected item.
                if (player.currentMediaItem?.mediaId != room.currentTrack?.id) { player.pause(); continue }
                player.skipSilenceEnabled = false
                if (player.playbackParameters.speed != 1f) player.playbackParameters = com.maxrave.domain.data.player.GenericPlaybackParameters()
                val target = session.positionAt(room.position, room.isPlaying)
                if (readySeq != room.seq && JamSync.ready(player.playbackState, player.bufferedPosition, player.currentPosition, player.duration)) {
                    readySeq = room.seq
                    session.reportBufferReady(room.currentTrack?.id.orEmpty(), room.seq)
                    // Late joiners land at the current host time after buffering, not their arrival time.
                    if (!room.loading) { applying = true; player.seekTo(target); applying = false }
                }
                applying = true
                try {
                    val shouldPlay = readySeq == room.seq && !room.loading && room.isPlaying
                    if (!room.isHost && readySeq == room.seq && JamSync.needsSeek(player.currentPosition, target)) player.seekTo(target)
                    // Host follows explicit server transport/gate changes; its real clock remains authoritative.
                    if (room.isHost && room.lastActionServerTime != lastRevisionTime) {
                        if (JamSync.needsSeek(player.currentPosition, target)) player.seekTo(target)
                        lastRevisionTime = room.lastActionServerTime
                    }
                    if (player.playWhenReady != shouldPlay) player.playWhenReady = shouldPlay
                    val now = session.serverNow() ?: 0L
                    if (room.isHost && shouldPlay && now - lastClock >= 1000) {
                        lastClock = now
                        if (player.playbackState == 4 && endedSeq != room.seq) { endedSeq = room.seq; session.command("ended") { put("seq", room.seq) } }
                        else session.command("pos") { put("seq", room.seq); put("position", player.currentPosition / 1000.0); put("t", now) }
                    }
                } finally { applying = false }
            }
        }
    }
}
