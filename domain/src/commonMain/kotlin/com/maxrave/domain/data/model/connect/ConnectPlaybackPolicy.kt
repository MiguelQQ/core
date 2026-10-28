package com.maxrave.domain.data.model.connect

/**
 * Decisiones puras de Connect y del primer frame de audio.
 * Los reproductores las consultan para que un test pueda fijar el orden sin MPV ni ExoPlayer.
 */

data class ConnectDisplayPlayback(
    val videoId: String,
    val title: String,
    val artist: String,
    val artwork: String,
    val isPlaying: Boolean,
    val positionMs: Long,
    val durationMs: Long,
)

/** Lo que pintan el mini-player y Now Playing mientras se sigue a otro aparato. */
fun ConnectSession.toDisplayPlayback(nowMs: Long): ConnectDisplayPlayback? {
    val track = currentTrack() ?: return null
    val art = track.thumbnail.ifBlank { "https://i.ytimg.com/vi/${track.videoId}/hqdefault.jpg" }
    return ConnectDisplayPlayback(
        videoId = track.videoId,
        title = track.title,
        artist = track.artist,
        artwork = art,
        isPlaying = isPlaying,
        positionMs = estimatedPositionMs(nowMs),
        durationMs = track.durationMs,
    )
}

fun ConnectState.listeningOnDeviceName(): String? {
    if (!isFollowingRemote()) return null
    val id = session?.activeDeviceId ?: return null
    return devices.firstOrNull { it.deviceId == id }?.displayName?.takeIf { it.isNotBlank() }
}

data class ConnectPickerRow(
    val deviceId: String,
    val platform: String,
    val displayName: String,
    val status: String,
    val selected: Boolean,
)

/**
 * Todas las filas de la sección Killmon en Salida de audio.
 * El check es el aparato activo. El resto sigue en la lista para poder transferir.
 */
fun connectPickerRows(
    devices: List<ConnectDevice>,
    session: ConnectSession?,
): List<ConnectPickerRow> {
    val title = session?.currentTrack()?.title?.takeIf { it.isNotBlank() }
    val playing = session?.isPlaying == true
    val activeId = session?.activeDeviceId
    val rows =
        devices.map { device ->
            val active = device.isActive || (activeId != null && device.deviceId == activeId)
            ConnectPickerRow(
                deviceId = device.deviceId,
                platform = device.platform,
                displayName = device.displayName.ifBlank { device.platform.ifBlank { "Killmon" } },
                status = connectDeviceStatus(active, playing, title, device.isSelf),
                selected = active,
            )
        }
    if (activeId.isNullOrBlank() || rows.any { it.deviceId == activeId }) return rows
    return rows +
        ConnectPickerRow(
            deviceId = activeId,
            platform = "",
            displayName = "Dispositivo activo",
            status = connectDeviceStatus(active = true, playing = playing, title = title, self = false),
            selected = true,
        )
}

fun connectDeviceStatus(
    active: Boolean,
    playing: Boolean,
    title: String?,
    self: Boolean,
): String =
    when {
        active && !title.isNullOrBlank() && playing -> "Reproduciendo · $title"
        active && !title.isNullOrBlank() -> "En pausa · $title"
        active && playing -> "Reproduciendo"
        active -> "En pausa"
        self -> "Este dispositivo"
        else -> "En línea"
    }

/** Un play con el enum ya en PLAYING tiene que despertar el backend, no solo el flag. */
data class PlayWake(
    val playWhenReady: Boolean,
    val callBackend: Boolean,
)

fun playWhileMarkedPlaying(): PlayWake = PlayWake(playWhenReady = true, callBackend = true)

/**
 * `wasPlaying` sale del intent aplicado. Un snapshot con `session.isPlaying == false`
 * no puede dejar el flanco en falso después de un play.
 */
fun wasPlayingAfterControl(
    action: String?,
    @Suppress("UNUSED_PARAMETER") sessionIsPlaying: Boolean,
    playerPlayWhenReady: Boolean,
): Boolean =
    when (action) {
        "play" -> true
        "pause" -> false
        else -> playerPlayWhenReady
    }

/** El presence que confirma un control no lleva playback. El latido siguiente sí. */
fun controlAckIncludesPlayback(): Boolean = false

enum class HandleStep {
    APPLY_LEVELS,
    MUTE,
    FADE_ZERO,
    FADE_FULL,
    UNMUTE,
    LOAD_FILE,
}

/** Precache y crossfade: niveles, silencio y después loadFile. Nunca al revés. */
fun incomingHandleOrder(): List<HandleStep> =
    listOf(HandleStep.APPLY_LEVELS, HandleStep.MUTE, HandleStep.FADE_ZERO, HandleStep.LOAD_FILE)

/** La pista que va a sonar: el nivel del usuario antes de loadFile. */
fun audibleHandleOrder(): List<HandleStep> =
    listOf(HandleStep.APPLY_LEVELS, HandleStep.FADE_FULL, HandleStep.UNMUTE, HandleStep.LOAD_FILE)

data class MpvGainWrite(
    val aoVolume: Double,
    val softwareVolume: Double,
)

/**
 * Con el AO todavía sin crear, el volumen de software queda en 0.
 * Si no, el mixer nace a 100 y el primer buffer sale a tope.
 * Con el AO listo, `ao-volume` es el master del usuario y el software es solo el fade.
 */
fun mpvGainWrite(
    masterPercent: Int,
    fadePercent: Int,
    sleepPercent: Int,
    outputReady: Boolean,
): MpvGainWrite {
    val master = masterPercent.coerceIn(0, 100)
    val fade = fadePercent.coerceIn(0, 100)
    val sleep = sleepPercent.coerceIn(0, 100)
    val ao = master * sleep / 100.0
    return if (outputReady) {
        MpvGainWrite(aoVolume = ao, softwareVolume = fade.toDouble())
    } else {
        MpvGainWrite(aoVolume = ao, softwareVolume = 0.0)
    }
}

/** Normalización apagada: no hay escritura. El 1f del player no sustituye al usuario. */
fun volumeWriteWhenNormalizeOff(): Float? = null

/** Handle nuevo de ExoPlayer: el volumen del usuario, antes de prepare. */
fun newHandleVolume(userVolume: Float): Float = userVolume.coerceIn(0f, 1f)

/** Paso de las teclas de volumen cuando el audio está en otro aparato. Absoluto, no un delta suelto. */
const val REMOTE_VOLUME_STEP = 0.05f

fun nextRemoteVolume(current: Float, up: Boolean): Float {
    val step = if (up) REMOTE_VOLUME_STEP else -REMOTE_VOLUME_STEP
    return (current + step).coerceIn(0f, 1f)
}

/**
 * Un segundo play o un segundo pause, dentro de la ventana, es el eco del mismo gesto.
 * El gesto contrario sí sale: si no, un pause y un play seguidos se perderían.
 */
fun shouldSendTransport(
    lastAction: String?,
    lastAtMs: Long,
    action: String,
    nowMs: Long,
    debounceMs: Long = 350L,
): Boolean {
    if (action != "play" && action != "pause") return true
    if (lastAction == null) return true
    return !(lastAction == action && nowMs - lastAtMs < debounceMs)
}

enum class HoldKind {
    TRANSPORT,
    VOLUME,
    SEEK,
}

fun holdFor(
    action: String,
    session: ConnectSession,
    baseRevision: Long,
    nowMs: Long,
): PlaybackHold? {
    val kind =
        when (action) {
            "play", "pause", "next", "previous" -> HoldKind.TRANSPORT
            "volume" -> HoldKind.VOLUME
            "seek" -> HoldKind.SEEK
            else -> return null
        }
    return PlaybackHold(
        kind = kind,
        isPlaying = session.isPlaying,
        index = session.index,
        positionMs = session.positionMs,
        volume = session.volume,
        videoId = session.currentTrack()?.videoId.orEmpty(),
        baseRevision = baseRevision,
        issuedAtMs = nowMs,
    )
}

data class PlaybackHold(
    val kind: HoldKind,
    val isPlaying: Boolean,
    val index: Int,
    val positionMs: Long,
    val volume: Float,
    val videoId: String,
    val baseRevision: Long,
    val issuedAtMs: Long,
)

data class MergedSession(
    val session: ConnectSession?,
    val hold: PlaybackHold?,
)

fun PlaybackHold.matches(session: ConnectSession): Boolean {
    if (session.currentTrack()?.videoId != videoId && videoId.isNotBlank()) return false
    return when (kind) {
        HoldKind.TRANSPORT -> session.isPlaying == isPlaying && session.index == index
        HoldKind.VOLUME -> session.isPlaying == isPlaying && kotlin.math.abs(session.volume - volume) <= 0.02f
        HoldKind.SEEK -> session.index == index && kotlin.math.abs(session.positionMs - positionMs) <= 2_000L
    }
}

/**
 * Un snapshot con revisión menor, o con la misma revisión mientras el control optimista sigue vivo,
 * no pisa play/pause/volumen. Si el servidor ya avanzó y el snapshot coincide con el hold, entra.
 * Pasados [holdForMs] manda el servidor.
 */
fun mergeConnectSession(
    current: ConnectSession?,
    incoming: ConnectSession?,
    hold: PlaybackHold?,
    nowMs: Long,
    holdForMs: Long = 2_500L,
): MergedSession {
    if (incoming == null) return MergedSession(session = null, hold = null)
    if (current == null) return MergedSession(session = incoming, hold = null)
    val held = hold?.takeIf { nowMs - it.issuedAtMs < holdForMs }
    if (held != null && incoming.revision <= held.baseRevision) {
        return MergedSession(session = current, hold = held)
    }
    if (incoming.revision < current.revision) {
        return MergedSession(session = current, hold = held)
    }
    if (held != null && incoming.revision > held.baseRevision && !held.matches(incoming)) {
        return MergedSession(session = current, hold = held)
    }
    return MergedSession(session = incoming, hold = null)
}

/** El presence que confirma un control no publica playback hasta que el backend asentó el gesto. */
fun shouldPublishPlayback(nowMs: Long, suppressUntilMs: Long): Boolean = nowMs >= suppressUntilMs

/** El presence que confirma una transferencia no lleva playback. El índice viejo cambiaría la canción. */
fun transferAckIncludesPlayback(): Boolean = false

fun reportedTrackMatches(reportedVideoId: String?, expectedVideoId: String?): Boolean {
    if (expectedVideoId.isNullOrBlank()) return true
    return reportedVideoId == expectedVideoId
}

data class TransferLoad(
    val videoId: String,
    val index: Int,
    val positionMs: Long,
    val play: Boolean,
)

/** La pista que hay que abrir al recibir el mando: la del índice de la sesión, no la primera de la cola. */
fun ConnectSession.toTransferLoad(nowMs: Long): TransferLoad? {
    if (queue.isEmpty()) return null
    val target = index.coerceIn(0, queue.lastIndex)
    val track = queue[target]
    return TransferLoad(
        videoId = track.videoId,
        index = target,
        positionMs = estimatedPositionMs(nowMs),
        play = isPlaying,
    )
}

/** El seguidor vuelve a callar si alguien le dejó ítems o un play a medias. */
fun followerNeedsSilence(
    itemCount: Int,
    playWhenReady: Boolean,
    isPlaying: Boolean,
): Boolean = itemCount > 0 || playWhenReady || isPlaying

enum class QueueLoadOp {
    REPLACE_ALL,
    LOAD_INDEX,
}

/** Un turno: reemplazar la lista y cargar el índice. Sin un clear encolado delante de los add. */
fun connectQueueLoadOps(): List<QueueLoadOp> = listOf(QueueLoadOp.REPLACE_ALL, QueueLoadOp.LOAD_INDEX)
