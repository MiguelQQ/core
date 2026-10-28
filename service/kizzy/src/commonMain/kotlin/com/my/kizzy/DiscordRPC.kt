package com.my.kizzy

import com.maxrave.common.DiscordDesktop
import com.maxrave.common.DiscordIpcActivity
import com.maxrave.domain.data.entities.SongEntity
import com.my.kizzy.rpc.KizzyRPC
import com.my.kizzy.rpc.RpcImage
import com.my.kizzy.rpc.localDiscordIpcOrNull
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

class DiscordRPC(
    private val rpcToken: String,
) : KizzyRPC(rpcToken) {
    private val localIpc = localDiscordIpcOrNull(rpcToken)

    override fun closeRPC() {
        if (localIpc != null) {
            localIpc.close()
            return
        }
        if (DiscordDesktop.isWindowsIpcToken(rpcToken)) return
        super.closeRPC()
    }

    override fun isRpcRunning(): Boolean =
        if (localIpc != null) {
            // True for the whole IPC session, not only while the pipe is up, so pause and logout
            // always call closeRPC and cannot leave a pipe open.
            true
        } else {
            super.isRpcRunning()
        }

    @OptIn(ExperimentalTime::class)
    suspend fun updateSong(
        currentPlaybackTimeMillis: Long,
        durationMillis: Long,
        playbackSpeed: Float = 1.0f,
        song: SongEntity,
        stillPlaying: () -> Boolean = { true },
    ) = runCatching {
        val currentTime = Clock.System.now().toEpochMilliseconds()

        val speed = playbackSpeed.takeIf { it > 0f } ?: 1f
        val adjustedPlaybackTime = (currentPlaybackTimeMillis / speed).toLong()
        val calculatedStartTime = currentTime - adjustedPlaybackTime

        val remainingDuration = durationMillis - currentPlaybackTimeMillis
        val adjustedRemainingDuration = (remainingDuration / speed).toLong()

        val ipc = localIpc
        if (ipc != null) {
            val artwork = song.thumbnails?.takeIf { it.startsWith("http") }
            val updated =
                ipc.update(
                    activity =
                    DiscordIpcActivity(
                        name = APP_NAME,
                        details = song.title,
                        state = song.artistName?.joinToString(", "),
                        largeImage = artwork ?: APP_ICON_URL,
                        smallImage = APP_ICON_URL,
                        largeText = song.albumName,
                        smallText = song.artistName?.firstOrNull(),
                        startMillis = calculatedStartTime,
                        endMillis = currentTime + adjustedRemainingDuration,
                        type = Type.LISTENING.value,
                    ),
                    stillCurrent = stillPlaying,
                )
            if (!updated) error("Discord IPC did not accept the activity")
            return@runCatching
        }
        // Sentinel saved on Windows and later opened on a platform with no pipe. Do not call the gateway.
        if (DiscordDesktop.isWindowsIpcToken(rpcToken)) return@runCatching

        setActivity(
            name = APP_NAME,
            details = song.title,
            state = song.artistName?.joinToString(", "),
            largeImage =
                song.thumbnails?.let {
                    RpcImage.ExternalImage(it, fallbackDiscordAsset = APP_ICON_URL)
                } ?: RpcImage.ExternalImage(APP_ICON_URL),
            smallImage = RpcImage.ExternalImage(APP_ICON_URL),
            largeText = song.albumName,
            smallText = song.artistName?.firstOrNull(),
            type = Type.LISTENING,
            since = currentTime,
            startTime = calculatedStartTime,
            endTime = currentTime + adjustedRemainingDuration,
            applicationId = DiscordDesktop.APPLICATION_ID,
        )
    }

    companion object {
        private const val APP_NAME: String = "Killmon Music"

        /**
         * Public app icon. User-token gateway presence cannot resolve developer-portal asset
         * keys (`mp:killmon_icon`); external-assets turns this URL into `mp:external/...`.
         */
        const val APP_ICON_URL =
            "https://raw.githubusercontent.com/MiguelQQ/SimpMusic/killmon/androidApp/src/main/res/drawable/app_icon.png"
    }
}