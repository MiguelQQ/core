package com.maxrave.domain.data.model.connect

import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.data.model.searchResult.songs.Artist
import com.maxrave.domain.data.model.searchResult.songs.Thumbnail

/**
 * Sesión de reproducción de una cuenta Killmon en varios aparatos.
 * El audio suena solo en [activeDeviceId]. El resto sigue la misma cola y posición.
 */
data class ConnectTrack(
    val videoId: String,
    val title: String,
    val artist: String,
    val thumbnail: String,
    val durationMs: Long,
)

data class ConnectPlayback(
    val queue: List<ConnectTrack>,
    val index: Int,
    val positionMs: Long,
    val isPlaying: Boolean,
    /** 0..1 del aparato que suena. El seguidor lo usa como base de las teclas de volumen. */
    val volume: Float = 1f,
)

data class ConnectSession(
    val activeDeviceId: String,
    val revision: Long,
    val isPlaying: Boolean,
    val positionMs: Long,
    val positionAtMs: Long,
    val index: Int,
    val queue: List<ConnectTrack>,
    /** Ganancia del aparato activo, 0..1. No es el volumen del sistema del seguidor. */
    val volume: Float = 1f,
) {
    fun currentTrack(): ConnectTrack? = queue.getOrNull(index)

    /** Posición que hay que pintar. [positionMs] ya venía corregida al recibir la respuesta. */
    fun estimatedPositionMs(nowMs: Long): Long {
        val elapsed = if (isPlaying) (nowMs - positionAtMs).coerceAtLeast(0L) else 0L
        val raw = (positionMs + elapsed).coerceAtLeast(0L)
        val cap = currentTrack()?.durationMs ?: 0L
        return if (cap > 0L) raw.coerceAtMost(cap) else raw
    }

    fun toPlayback(): ConnectPlayback =
        ConnectPlayback(
            queue = queue,
            index = index.coerceIn(0, (queue.size - 1).coerceAtLeast(0)),
            positionMs = positionMs.coerceAtLeast(0L),
            isPlaying = isPlaying,
            volume = volume.coerceIn(0f, 1f),
        )

    /**
     * El mismo contrato que el servidor: play, pause, seek, next y previous.
     * La posición estimada se sella en [nowMs] para que la UI no espere al latido.
     */
    fun withControl(
        action: String,
        requestedPositionMs: Long?,
        nowMs: Long,
        volume: Float? = null,
    ): ConnectSession {
        val estimated = estimatedPositionMs(nowMs)
        var nextIndex = index
        var nextPosition = estimated
        var playing = isPlaying
        when (action) {
            "play" -> playing = true
            "pause" -> playing = false
            "seek" -> {
                val requested = requestedPositionMs ?: return this
                if (requested < 0L) return this
                nextPosition = requested
            }
            "next" -> {
                if (index < queue.lastIndex) {
                    nextIndex = index + 1
                    nextPosition = 0L
                }
            }
            "previous" -> {
                if (estimated > 3_000L) {
                    nextPosition = 0L
                } else if (index > 0) {
                    nextIndex = index - 1
                    nextPosition = 0L
                } else {
                    nextPosition = 0L
                }
            }
            "volume" -> {
                val level = volume ?: return this
                if (level.isNaN() || level < 0f || level > 1f) return this
                return copy(
                    volume = level,
                    positionMs = estimated,
                    positionAtMs = nowMs,
                )
            }
            else -> return this
        }
        val cap = queue.getOrNull(nextIndex)?.durationMs ?: 0L
        if (cap > 0L) nextPosition = nextPosition.coerceAtMost(cap)
        return copy(
            index = nextIndex,
            positionMs = nextPosition,
            positionAtMs = nowMs,
            isPlaying = playing,
        )
    }

    /**
     * Una canción elegida en el controlador. El aparato activo no cambia:
     * la cola nueva suena donde ya estaba el audio.
     */
    fun withLoadedQueue(playback: ConnectPlayback, nowMs: Long): ConnectSession {
        if (playback.queue.isEmpty()) return this
        val target = playback.index.coerceIn(0, playback.queue.lastIndex)
        return copy(
            queue = playback.queue,
            index = target,
            positionMs = playback.positionMs.coerceAtLeast(0L),
            positionAtMs = nowMs,
            isPlaying = true,
        )
    }
}

/** La misma pista, en el modelo que ya consumen la cola y Now Playing. */
fun ConnectTrack.asTrack(): Track {
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

/** Con sesión o con otro aparato en línea el latido es corto: pausa y seek no pueden esperar 8 s. */
fun connectPollIntervalMs(sessionLive: Boolean): Long = if (sessionLive) 1_000L else 8_000L

data class ConnectDevice(
    val deviceId: String,
    val platform: String,
    val displayName: String,
    val isActive: Boolean,
    val isSelf: Boolean,
)

data class ConnectCommand(
    val type: String,
    val revision: Long,
    val action: String? = null,
    val positionMs: Long? = null,
    val index: Int? = null,
    val volume: Float? = null,
)

data class ConnectState(
    val deviceId: String = "",
    val signedIn: Boolean = false,
    val devices: List<ConnectDevice> = emptyList(),
    val session: ConnectSession? = null,
    val command: ConnectCommand? = null,
    val message: String? = null,
) {
    /** Otro aparato de la cuenta tiene el audio. Este no debe sonar encima. */
    fun isFollowingRemote(): Boolean {
        val current = session ?: return false
        return signedIn && current.activeDeviceId != deviceId && current.queue.isNotEmpty()
    }

    /** Este aparato es el que suena, o todavía no hay sesión y puede publicarla. Una sala JAM no. */
    fun publishesLocalAudio(inJam: Boolean): Boolean {
        if (!signedIn || inJam) return false
        val active = session?.activeDeviceId
        return active == null || active == deviceId
    }
}
