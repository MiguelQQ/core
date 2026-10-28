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
