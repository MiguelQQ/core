package com.maxrave.data.listentogether

import com.maxrave.common.MERGING_DATA_TYPE
import com.maxrave.domain.data.player.JamPlaybackRouter
import com.maxrave.domain.data.player.PlayerError
import com.maxrave.domain.data.player.GenericMediaItem
import com.maxrave.domain.data.player.GenericMediaMetadata
import com.maxrave.domain.mediaservice.handler.MediaPlayerHandler
import com.maxrave.domain.mediaservice.player.MediaPlayerListener
import com.maxrave.domain.repository.ListenTogetherRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.put
import org.simpmusic.listentogether.*

/** Reuses the inherited player bridge, with local YouTube resolution and the same barrier for host and guests. */
class ListenTogetherPlaybackBridge(
    private val repository: ListenTogetherRepository,
    private val session: ListenTogetherSession,
    private val handler: MediaPlayerHandler,
    private val scope: CoroutineScope,
    private val songs: com.maxrave.domain.repository.SongRepository,
) {
    private var started = false
    private var applying = false
    private var loadedSeq = -1L
    private var readySeq = -1L
    private var endedSeq = -1L
    private var lastClock = 0L
    private var bufferingSeq = -1L
    private var savedParameters = com.maxrave.domain.data.player.GenericPlaybackParameters()
    private var savedSkipSilence = false
    private var savedShuffle = false
    private var savedRepeat = 0
    private var lastRoomCode: String? = null
    private var lastRevisionTime = 0L
    private val listener = object : MediaPlayerListener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            val room = session.state.value
            if (!applying && room.inRoom && !room.loading && loadedSeq == room.seq &&
                playbackState == 2 && bufferingSeq != room.seq) {
                bufferingSeq = room.seq
                session.command("buffering") { put("seq", room.seq); put("position", handler.player.currentPosition / 1000.0) }
            }
            reportReady()
        }
        override fun onPlayerError(error: PlayerError) {
            val room = session.state.value
            if (room.inRoom && handler.player.currentMediaItem?.mediaId == room.currentTrack?.id) {
                readySeq = -1L
                session.markPlaybackFailed()
            }
        }
        override fun onSeeked(positionMs: Long) {
            val room = session.state.value
            if (!applying && room.inRoom && !room.loading) session.command("seek") { put("position", positionMs / 1000.0) }
        }
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            val room = session.state.value
            if (!applying && room.inRoom && !room.loading && loadedSeq == room.seq && handler.player.playbackState == 3 && isPlaying == handler.player.playWhenReady) {
                session.command(if (isPlaying) "play" else "pause") { put("position", handler.player.currentPosition / 1000.0) }
            }
        }
    }
    private val actions = object : JamPlaybackRouter.Actions {
        override fun add(item: GenericMediaItem) {
            session.suggestTrack(TrackInfo(id = item.mediaId, title = item.metadata.title.orEmpty(), artist = item.metadata.artist.orEmpty(), thumbnail = item.metadata.artworkUri.orEmpty()))
        }
        override fun play(item: GenericMediaItem) {
            session.suggestTrack(TrackInfo(id = item.mediaId, title = item.metadata.title.orEmpty(), artist = item.metadata.artist.orEmpty(), thumbnail = item.metadata.artworkUri.orEmpty()), play = true)
        }
        override fun queue(type: String, index: Int, to: Int?) {
            val room = session.state.value
            val qid = room.queueIds.getOrNull(index) ?: return
            session.command(type) { put("qid", qid); to?.let { put("to", it) } }
        }
        override fun control(type: String, positionMs: Long?) {
            session.command(type) { positionMs?.let { put("position", it.coerceAtLeast(0L) / 1000.0) } }
        }
    }
    // Called on Main, both by the player event and the polling fallback.
    private fun reportReady() {
        val room = session.state.value
        if (room.connection is ConnectionState.Connected && loadedSeq == room.seq &&
            handler.player.currentMediaItem?.mediaId == room.currentTrack?.id &&
            readySeq != room.seq && JamSync.ready(handler.player.playbackState)) {
            readySeq = room.seq
            session.reportBufferReady(room.currentTrack?.id.orEmpty(), room.seq)
            session.clearPlaybackFailed()
            if (!room.loading) {
                applying = true
                try { handler.player.seekTo(session.positionAt(room.position, room.isPlaying)) }
                finally { applying = false }
            }
        }
    }

    fun start() {
        if (started) return
        started = true
        scope.launch(Dispatchers.Main) { handler.player.addListener(listener) }
        scope.launch(Dispatchers.Main) {
            session.state.map { Triple(it.roomCode, it.seq, it.connection is ConnectionState.Connected) to it.selfUserId }.distinctUntilChanged().collectLatest { (key, selfId) ->
                val (code, seq, connected) = key
                if (code != null && selfId.isBlank()) return@collectLatest
                JamPlaybackRouter.actions = if (code == null) null else actions
                if (code == null) {
                    withContext(Dispatchers.Main) {
                        handler.player.crossfadeSuppressed = false
                        if (lastRoomCode != null) {
                            applying = true
                            handler.player.pause(); handler.player.clearMediaItems()
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
                    var initialPosition = 0L
                    val current = withContext(Dispatchers.Main) {
                        savedParameters = handler.player.playbackParameters
                        savedSkipSilence = handler.player.skipSilenceEnabled
                        savedShuffle = handler.player.shuffleModeEnabled; savedRepeat = handler.player.repeatMode
                        handler.player.crossfadeSuppressed = true
                        applying = true
                        initialPosition = handler.player.currentPosition
                        val item = handler.player.currentMediaItem
                        handler.player.pause()
                        applying = false
                        item
                    }
                    lastRoomCode = code
                    if (wasEmpty && session.state.value.isHost && current != null && Regex("^[A-Za-z0-9_-]{11}$").matches(current.mediaId)) {
                        session.command("queue_add") { put("track", kotlinx.serialization.json.buildJsonObject { put("videoId", current.mediaId); put("title", current.metadata.title.orEmpty()); put("artist", current.metadata.artist.orEmpty()); put("thumbnail", current.metadata.artworkUri.orEmpty()) }); put("position", initialPosition / 1000.0) }
                    }
                }
                val track = session.state.value.currentTrack ?: run {
                    withContext(Dispatchers.Main) { applying = true; handler.player.pause(); applying = false }
                    return@collectLatest
                }
                withContext(Dispatchers.Main) { applying = true; handler.player.pause(); applying = false }
                try {
                    withContext(Dispatchers.Main) {
                        applying = true
                        try {
                            val sameTrack = handler.player.currentMediaItem?.mediaId == track.id
                            handler.player.playWhenReady = false
                            if (!sameTrack) {
                                handler.player.clearMediaItems()
                                handler.player.setMediaItem(GenericMediaItem(
                                    mediaId = track.id, uri = "https://music.youtube.com/watch?v=${track.id}",
                                    metadata = GenericMediaMetadata(title = track.title, artist = track.artist, artworkUri = track.thumbnail.ifBlank { "https://i.ytimg.com/vi/${track.id}/hqdefault.jpg" }, description = MERGING_DATA_TYPE.SONG),
                                    customCacheKey = track.id,
                                ))
                            }
                            // A failed player can retain its MediaItem in STATE_IDLE after reconnect.
                            if (!sameTrack || handler.player.playbackState == 1) handler.player.prepare()
                            handler.player.shuffleModeEnabled = false; handler.player.repeatMode = 0
                            handler.player.playbackParameters = com.maxrave.domain.data.player.GenericPlaybackParameters(1f, 1f)
                            val state = session.state.value
                            val target = session.positionAt(state.position, state.isPlaying)
                            if (!sameTrack || JamSync.needsSeek(handler.player.currentPosition, target)) handler.player.seekTo(target)
                            loadedSeq = seq; readySeq = -1; endedSeq = -1
                        } finally { applying = false }
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { session.markPlaybackFailed() }
            }
        }
        scope.launch {
            var contributedRoom: String? = null
            var lowSeq = -1L
            var wasLow = false
            session.state.collect { room ->
                if (!room.inRoom) { contributedRoom = null; lowSeq = -1L; wasLow = false; return@collect }
                if (!room.isConnected) return@collect
                // Seed an existing host track before automatic contributions can start the room.
                if (room.isHost && room.currentTrack == null && withContext(Dispatchers.Main) { handler.player.currentMediaItem != null }) return@collect
                val index = room.queue.indexOfFirst { it.id == room.currentTrack?.id }
                val low = room.queue.size - index - 1 < 3
                val shouldContribute = contributedRoom != room.roomCode || (low && (!wasLow || lowSeq != room.seq))
                wasLow = low
                if (!shouldContribute) return@collect
                contributedRoom = room.roomCode
                lowSeq = room.seq
                try {
                    val own = (songs.getLikedSongs().first().shuffled().take(15) + songs.getAllSongs(100).first().shuffled())
                        .distinctBy { it.videoId }.filter { song -> Regex("^[A-Za-z0-9_-]{11}$").matches(song.videoId) && room.queue.none { it.id == song.videoId } }.take(30)
                    var candidates = own.map { TrackInfo(id = it.videoId, title = it.title, artist = it.artistName.orEmpty().joinToString(", "), thumbnail = it.thumbnails.orEmpty()) }
                    if (candidates.isEmpty()) {
                        val id = room.currentTrack?.id ?: withContext(Dispatchers.Main) { handler.player.currentMediaItem?.mediaId }
                        if (id != null) candidates = withTimeoutOrNull(15_000) {
                            songs.getRelatedData(id).first().data?.first.orEmpty().map {
                                TrackInfo(id = it.videoId, title = it.title, artist = it.artists.orEmpty().joinToString(", ") { artist -> artist.name }, thumbnail = it.thumbnails?.lastOrNull()?.url.orEmpty())
                            }
                        }.orEmpty()
                    }
                    if (session.state.value.roomCode == room.roomCode) session.contribute(candidates)
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { session.playbackError("No se pudo renovar el aporte automático de este teléfono") }
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
                reportReady()
                applying = true
                try {
                    val shouldPlay = readySeq == room.seq && !room.loading && room.isPlaying
                    if (!room.loading && !room.isHost && readySeq == room.seq && JamSync.needsSeek(player.currentPosition, target)) player.seekTo(target)
                    // Host follows explicit server transport/gate changes; its real clock remains authoritative.
                    if (!room.loading && room.isHost && room.lastActionServerTime != lastRevisionTime) {
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
