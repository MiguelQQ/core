package com.my.kizzy

import com.maxrave.domain.data.entities.SongEntity
import com.my.kizzy.rpc.KizzyRPC
import com.my.kizzy.rpc.RpcImage
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

class DiscordRPC(
    token: String,
) : KizzyRPC(token) {
    @OptIn(ExperimentalTime::class)
    suspend fun updateSong(
        currentPlaybackTimeMillis: Long,
        durationMillis: Long,
        playbackSpeed: Float = 1.0f,
        song: SongEntity,
    ) = runCatching {
        val currentTime = Clock.System.now().toEpochMilliseconds()

        val adjustedPlaybackTime = (currentPlaybackTimeMillis / playbackSpeed).toLong()
        val calculatedStartTime = currentTime - adjustedPlaybackTime

        val remainingDuration = durationMillis - currentPlaybackTimeMillis
        val adjustedRemainingDuration = (remainingDuration / playbackSpeed).toLong()

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
            applicationId = APPLICATION_ID,
        )
    }

    companion object {
        private const val APPLICATION_ID = "1563405206408929401"
        private const val APP_NAME: String = "Killmon Music"

        /**
         * Public app icon. User-token gateway presence cannot resolve developer-portal asset
         * keys (`mp:killmon_icon`); external-assets turns this URL into `mp:external/...`.
         */
        const val APP_ICON_URL =
            "https://raw.githubusercontent.com/MiguelQQ/SimpMusic/killmon/androidApp/src/main/res/drawable/app_icon.png"
    }
}