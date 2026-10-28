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
}

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
}
