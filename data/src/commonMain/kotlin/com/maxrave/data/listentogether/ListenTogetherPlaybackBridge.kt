package com.maxrave.data.listentogether

import com.maxrave.common.MERGING_DATA_TYPE
import com.maxrave.domain.data.player.JamPlaybackRouter
import com.maxrave.domain.data.player.PlayerError
import com.maxrave.domain.data.player.GenericMediaItem
import com.maxrave.domain.data.player.GenericMediaMetadata
import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.data.model.searchResult.songs.Artist
import com.maxrave.domain.data.model.searchResult.songs.Thumbnail
import com.maxrave.domain.mediaservice.handler.MediaPlayerHandler
import com.maxrave.domain.mediaservice.handler.PlaylistType
import com.maxrave.domain.mediaservice.handler.QueueData
import com.maxrave.domain.mediaservice.player.MediaPlayerListener
import com.maxrave.domain.repository.ListenTogetherRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.put
import kotlin.time.TimeSource
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
    private val epoch = TimeSource.Monotonic.markNow()
    private fun mono() = epoch.elapsedNow().inWholeMilliseconds
    /** When WE last moved the playhead. A seek rebuffers, and that rebuffer must not open a room-wide gate. */
    private var lastSeekAt = Long.MIN_VALUE / 2
    /** The recording we asked the adapter to load, until the adapter (which applies it later) shows it. */
    private var pendingLoad: Pair<String, Long>? = null

    // This listener only OBSERVES. Transport leaves the phone through JamPlaybackRouter, which every
    // user path already goes through (scrubber, play/pause, next/prev, notification, headset).
    // It used to also turn onIsPlayingChanged / onSeeked into room commands, guarded by an `applying`
    // flag set around our own calls. The adapter applies those calls later, in its own coroutine, so
    // the player's echo of OUR pause/seek/play arrived after the flag was down and was sent to the
    // server as if a person had done it: a "pause" during the load gate left the room paused, a "play"
    // overwrote the room clock with this phone's stale second, a "seek" opened another gate. That echo
    // loop is the pause / resume-from-the-start / "loading" cycle seen on two phones.
    private val listener = object : MediaPlayerListener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            val room = session.state.value
            // Only a phone that was already in step with this generation can stall the room; one still
            // catching up is not a reason to stop everybody else again.
            if (room.inRoom && !room.loading && loadedSeq == room.seq && readySeq == room.seq && bufferingSeq != room.seq &&
                JamSync.isRealStall(playbackState, mono(), lastSeekAt)) {
                bufferingSeq = room.seq
                session.command("buffering") { put("seq", room.seq); put("position", handler.player.currentPosition / 1000.0) }
            }
            reportReady()
        }
        override fun onPlayerError(error: PlayerError) {
            val room = session.state.value
            if (room.inRoom && handler.player.currentMediaItem?.mediaId?.removePrefix(MERGING_DATA_TYPE.VIDEO) == room.currentTrack?.id) {
                readySeq = -1L
                session.markPlaybackFailed()
            }
        }
    }

    private class Outgoing(val item: GenericMediaItem, val play: Boolean)
    private val outbox = Channel<Outgoing>(Channel.UNLIMITED)
    private var chainAnchor: String? = null
    private var chainUntil = 0L

    /** Same title, artist and length the normal player would use: whatever the item lacks, the library has. */
    private suspend fun outgoing(o: Outgoing) {
        val item = o.item
        val id = item.mediaId.removePrefix(MERGING_DATA_TYPE.VIDEO)
        var title = item.metadata.title.orEmpty()
        var artist = item.metadata.artist.orEmpty().takeUnless { it.isBlank() || it == "null" }.orEmpty()
        var duration = 0L
        val known = withTimeoutOrNull(2_000) { songs.getSongById(id).firstOrNull() }
        if (known != null) {
            if (title.isBlank() || title == "null") title = known.title
            if (artist.isBlank()) artist = known.artistName.orEmpty().joinToString(", ")
            duration = (known.durationSeconds ?: 0) * 1000L
        }
        val info = TrackInfo(id = id, title = title, artist = artist, duration = duration, thumbnail = item.metadata.artworkUri.orEmpty())
        val isVideo = item.metadata.description == MERGING_DATA_TYPE.VIDEO
        val now = mono()
        // A playlist is a play followed by its other tracks: those go right behind the first one,
        // not behind whatever the room's automatic mix had queued.
        val after = if (!o.play && now < chainUntil) chainAnchor else null
        if (o.play || after != null) { chainAnchor = id; chainUntil = now + 1_500 }
        session.suggestTrack(info, play = o.play, isVideo = isVideo, after = after)
    }

    private val actions = object : JamPlaybackRouter.Actions {
        override fun add(item: GenericMediaItem) { outbox.trySend(Outgoing(item, false)) }
        override fun play(item: GenericMediaItem) { outbox.trySend(Outgoing(item, true)) }
        override fun queue(type: String, index: Int, to: Int?) {
            val room = session.state.value
            val qid = room.queueIds.getOrNull(index) ?: return
            session.command(type) { put("qid", qid); to?.let { put("to", it) } }
        }
        override fun control(type: String, positionMs: Long?) {
            // Asking for play is the user answering the pause another app caused.
            if (type == "play") handler.player.audioFocusLost = false
            session.command(type) { positionMs?.let { put("position", it.coerceAtLeast(0L) / 1000.0) } }
        }
    }

    private fun mediaItemFor(track: TrackInfo, isVideo: Boolean) = GenericMediaItem(
        mediaId = track.id, uri = "https://music.youtube.com/watch?v=${track.id}",
        metadata = GenericMediaMetadata(
            title = track.title, artist = track.artist,
            artworkUri = track.thumbnail.ifBlank { "https://i.ytimg.com/vi/${track.id}/hqdefault.jpg" },
            // Video rooms tracks keep their type, so MergingMediaSourceFactory resolves the video
            // stream exactly as it does outside a room (and still honours the watch-video setting).
            description = if (isVideo) MERGING_DATA_TYPE.VIDEO else MERGING_DATA_TYPE.SONG,
        ),
        customCacheKey = track.id,
    )

    private fun mirrorOf(room: ListenTogetherState): QueueData.Data = QueueData.Data(
        listTracks = room.queue.mapIndexed { i, info ->
            val video = room.queueIsVideo.getOrNull(i) == true
            val url = info.thumbnail.ifBlank { "https://i.ytimg.com/vi/${info.id}/hqdefault.jpg" }
            Track(
                album = null,
                artists = info.artist.split(", ").filter { it.isNotBlank() }.map { Artist(id = null, name = it) },
                duration = null,
                durationSeconds = (info.duration / 1000).toInt().takeIf { it > 0 },
                isAvailable = true, isExplicit = false, likeStatus = null,
                thumbnails = listOf(if (video) Thumbnail(720, url, 1280) else Thumbnail(544, url, 544)),
                title = info.title, videoId = info.id, videoType = null, category = null, feedbackTokens = null, resultType = null,
            )
        },
        playlistId = null, playlistName = "JAM ${room.roomCode.orEmpty()}", playlistType = PlaylistType.PLAYLIST,
    )

    private fun seekLocal(positionMs: Long) {
        lastSeekAt = mono()
        handler.player.seekTo(positionMs)
    }

    // Called on Main, both by the player event and the polling fallback.
    private fun reportReady() {
        val room = session.state.value
        if (room.connection is ConnectionState.Connected && loadedSeq == room.seq &&
            JamSync.sameTrack(handler.player.currentMediaItem?.mediaId, room.currentTrack?.id.orEmpty()) &&
            readySeq != room.seq && JamSync.ready(handler.player.playbackState)) {
            readySeq = room.seq
            session.reportBufferReady(room.currentTrack?.id.orEmpty(), room.seq)
            session.clearPlaybackFailed()
            // Ready while the room is already running (a late joiner): land on its second once.
            if (!room.loading) {
                val target = session.positionAt(room.position, room.isPlaying)
                if (JamSync.needsSeek(handler.player.currentPosition, target, JamSync.GATE_TOLERANCE_MS)) seekLocal(target)
            }
        }
    }

    fun start() {
        if (started) return
        started = true
        scope.launch(Dispatchers.Main) { handler.player.addListener(listener) }
        scope.launch { for (o in outbox) { try { outgoing(o) } catch (e: CancellationException) { throw e } catch (_: Exception) { session.playbackError("No se pudo añadir la canción a la JAM") } } }
        // The room's queue is shown by the normal queue screen: it reads the handler's queue, and the
        // handler already routes tap / drag / remove back to the room through JamPlaybackRouter.
        scope.launch(Dispatchers.Main) {
            var mirrored = false
            session.state.map { Triple(it.roomCode, it.queueIds, it.queue.map { t -> t.id + t.title + t.artist + t.thumbnail }) to it.queueIsVideo }
                .distinctUntilChanged().collect {
                    val room = session.state.value
                    if (room.inRoom) { mirrored = true; handler.setQueueData(mirrorOf(room)) }
                    else if (mirrored) { mirrored = false; handler.setQueueData(QueueData.Data()) }
                }
        }
        scope.launch(Dispatchers.Main) {
            session.state.map { Triple(it.roomCode, it.seq, it.connection is ConnectionState.Connected) to it.selfUserId }.distinctUntilChanged().collectLatest { (key, selfId) ->
                val (code, seq, connected) = key
                if (code != null && selfId.isBlank()) return@collectLatest
                JamPlaybackRouter.actions = if (code == null) null else actions
                if (code == null) {
                    handler.player.crossfadeSuppressed = false
                    if (lastRoomCode != null) {
                        handler.player.pause(); handler.player.clearMediaItems()
                        handler.player.playbackParameters = savedParameters
                        handler.player.skipSilenceEnabled = savedSkipSilence
                        handler.player.shuffleModeEnabled = savedShuffle; handler.player.repeatMode = savedRepeat
                    }
                    lastRoomCode = null; loadedSeq = -1; readySeq = -1; pendingLoad = null; chainAnchor = null
                    return@collectLatest
                }
                if (!connected) {
                    loadedSeq = -1; readySeq = -1
                    handler.player.pause()
                    return@collectLatest
                }
                if (lastRoomCode != code) {
                    val wasEmpty = session.state.value.currentTrack == null
                    savedParameters = handler.player.playbackParameters
                    savedSkipSilence = handler.player.skipSilenceEnabled
                    savedShuffle = handler.player.shuffleModeEnabled; savedRepeat = handler.player.repeatMode
                    handler.player.crossfadeSuppressed = true
                    val initialPosition = handler.player.currentPosition
                    val current = handler.player.currentMediaItem
                    handler.player.pause()
                    lastRoomCode = code
                    val bare = current?.mediaId?.removePrefix(MERGING_DATA_TYPE.VIDEO).orEmpty()
                    if (wasEmpty && session.state.value.isHost && current != null && Regex("^[A-Za-z0-9_-]{11}$").matches(bare)) {
                        session.command("queue_add") { put("track", kotlinx.serialization.json.buildJsonObject { put("videoId", bare); put("title", current.metadata.title.orEmpty()); put("artist", current.metadata.artist.orEmpty()); put("thumbnail", current.metadata.artworkUri.orEmpty()); if (current.metadata.description == MERGING_DATA_TYPE.VIDEO) put("isVideo", true) }); put("position", initialPosition / 1000.0) }
                    }
                }
                val room = session.state.value
                val track = room.currentTrack ?: run { handler.player.pause(); return@collectLatest }
                try {
                    // The listener holds its second and pauses; nothing below reloads a recording the
                    // player already has. The whole generation change is: pause, then at most one load
                    // (other recording) or one seek (same recording, more than a second off).
                    handler.player.playWhenReady = false
                    val target = session.positionAt(room.position, room.isPlaying)
                    val isVideo = room.queueIsVideo.getOrNull(room.queue.indexOfFirst { it.id == track.id }) == true
                    val player = handler.player
                    val pending = pendingLoad
                    val step = when {
                        pending != null && pending.first == track.id && mono() - pending.second < 2_500 -> JamLoadStep.Keep
                        // A failed player keeps its MediaItem in STATE_IDLE: that one needs a real reload.
                        JamSync.sameTrack(player.currentMediaItem?.mediaId, track.id) && player.playbackState == 1 -> JamLoadStep.Load(target.coerceAtLeast(0L))
                        else -> JamSync.planStep(player.currentMediaItem?.mediaId, track.id, player.currentPosition, target)
                    }
                    when (step) {
                        is JamLoadStep.Load -> {
                            pendingLoad = track.id to mono()
                            lastSeekAt = mono()
                            player.setMediaItem(mediaItemFor(track, isVideo), step.startMs)
                        }
                        is JamLoadStep.Seek -> seekLocal(step.toMs)
                        JamLoadStep.Keep -> Unit
                    }
                    player.shuffleModeEnabled = false; player.repeatMode = 0
                    if (player.playbackParameters.speed != 1f || player.playbackParameters.pitch != 1f) player.playbackParameters = com.maxrave.domain.data.player.GenericPlaybackParameters(1f, 1f)
                    loadedSeq = seq; readySeq = -1; endedSeq = -1
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
                if (!JamSync.sameTrack(player.currentMediaItem?.mediaId, room.currentTrack?.id.orEmpty())) { if (pendingLoad?.first != room.currentTrack?.id) player.pause(); continue }
                player.skipSilenceEnabled = false
                if (player.playbackParameters.speed != 1f) player.playbackParameters = com.maxrave.domain.data.player.GenericPlaybackParameters()
                val target = session.positionAt(room.position, room.isPlaying)
                reportReady()
                val state = player.playbackState
                val inStep = readySeq == room.seq && !room.loading
                // ENDED is never played again: play() on an ended player rewinds it to 0, which is the
                // song starting over while the room waits for the host's `ended` to move on.
                // A phone that lost audio focus (a YouTube video, a call) stays paused: that pause is
                // this phone's, it is not sent to the room, and forcing play here would take the focus
                // straight back from the app that asked for it.
                val shouldPlay = inStep && room.isPlaying && state != JamSync.STATE_ENDED && !player.audioFocusLost
                val now = mono()
                if (inStep && JamSync.needsSeek(player.currentPosition, target) && JamSync.mayCorrectDrift(state, now, lastSeekAt) &&
                    (!room.isHost || room.lastActionServerTime != lastRevisionTime)) seekLocal(target)
                if (room.isHost) lastRevisionTime = room.lastActionServerTime
                if (player.playWhenReady != shouldPlay && !(player.audioFocusLost && !shouldPlay)) player.playWhenReady = shouldPlay
                if (room.isHost && inStep && room.isPlaying) {
                    val serverNow = session.serverNow() ?: 0L
                    if (state == JamSync.STATE_ENDED && endedSeq != room.seq) { endedSeq = room.seq; session.command("ended") { put("seq", room.seq) } }
                    else if (shouldPlay && serverNow - lastClock >= 1000) {
                        lastClock = serverNow
                        session.command("pos") { put("seq", room.seq); put("position", player.currentPosition / 1000.0); put("t", serverNow) }
                    }
                }
            }
        }
    }
}
