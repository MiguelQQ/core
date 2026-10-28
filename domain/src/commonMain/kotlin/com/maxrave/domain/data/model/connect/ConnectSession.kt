package com.maxrave.domain.data.model.connect

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
)

data class ConnectSession(
    val activeDeviceId: String,
    val revision: Long,
    val isPlaying: Boolean,
    val positionMs: Long,
    val positionAtMs: Long,
    val index: Int,
    val queue: List<ConnectTrack>,
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
        )

    /**
     * El mismo contrato que el servidor: play, pause, seek, next y previous.
     * La posición estimada se sella en [nowMs] para que la UI no espere al latido.
     */
    fun withControl(
        action: String,
        requestedPositionMs: Long?,
        nowMs: Long,
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
