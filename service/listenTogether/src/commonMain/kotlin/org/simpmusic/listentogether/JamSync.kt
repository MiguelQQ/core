package org.simpmusic.listentogether

import kotlin.math.abs

/** What the bridge does to its player for one room snapshot. Never more than one of these. */
sealed interface JamLoadStep {
    /** Same recording, close enough to the room: touch nothing, the listener keeps its second. */
    data object Keep : JamLoadStep

    /** Same recording, too far from the room: move the playhead, keep the MediaItem. */
    data class Seek(val toMs: Long) : JamLoadStep

    /** Another recording: ONE load, already positioned, instead of set + prepare + seek. */
    data class Load(val startMs: Long) : JamLoadStep
}

/** Pure timing policy shared by the Android bridge and unit tests. Media3 STATE_READY = 3, STATE_ENDED = 4. */
object JamSync {
    const val STATE_BUFFERING = 2
    const val STATE_READY = 3
    const val STATE_ENDED = 4

    /** A listener within this of the room's second keeps playing where it is when a gate opens. */
    const val GATE_TOLERANCE_MS = 1000L

    /** Two corrective seeks closer than this feed each other: a seek rebuffers, a rebuffer opens a gate. */
    const val MIN_SEEK_INTERVAL_MS = 2500L

    /** A rebuffer this soon after our own seek is the seek, not a network stall. */
    const val SEEK_BUFFERING_GRACE_MS = 4000L

    fun needsSeek(localMs: Long, hostMs: Long, toleranceMs: Long = 500L): Boolean = abs(localMs - hostMs) > toleranceMs

    // ExoPlayer owns its buffering threshold, including short/unknown-duration YouTube streams.
    fun ready(state: Int): Boolean = state == STATE_READY

    /**
     * True when the player already holds [trackId]. The MediaItem id is bare for songs and carries the
     * "Video" prefix when the item is a video; both are the same recording and must never be re-set.
     */
    fun sameTrack(currentMediaId: String?, trackId: String, videoPrefix: String = "Video"): Boolean =
        currentMediaId != null && (currentMediaId == trackId || currentMediaId == videoPrefix + trackId)

    /**
     * Decides what a new snapshot (new generation, gate, queue change) does to the local player.
     * The playing phone pauses where it is; it only moves when it is more than [GATE_TOLERANCE_MS]
     * from the room, and it never reloads a recording it already holds.
     */
    fun planStep(
        currentMediaId: String?,
        trackId: String,
        localMs: Long,
        targetMs: Long,
        videoPrefix: String = "Video",
    ): JamLoadStep =
        when {
            !sameTrack(currentMediaId, trackId, videoPrefix) -> JamLoadStep.Load(targetMs.coerceAtLeast(0L))
            needsSeek(localMs, targetMs, GATE_TOLERANCE_MS) -> JamLoadStep.Seek(targetMs.coerceAtLeast(0L))
            else -> JamLoadStep.Keep
        }

    /** The 150 ms follow loop: seek a drifting guest, but not an ended player and not in a seek storm. */
    fun mayCorrectDrift(playerState: Int, nowMs: Long, lastSeekAtMs: Long): Boolean =
        playerState != STATE_ENDED && nowMs - lastSeekAtMs >= MIN_SEEK_INTERVAL_MS

    /** Whether a player in BUFFERING is a real stall worth opening a gate for the whole room. */
    fun isRealStall(playerState: Int, nowMs: Long, lastSeekAtMs: Long): Boolean =
        playerState == STATE_BUFFERING && nowMs - lastSeekAtMs > SEEK_BUFFERING_GRACE_MS

    /** What mini-player and full player show: the player's own spinner OR the room waiting for someone. */
    fun showLoading(playerLoading: Boolean, room: ListenTogetherState): Boolean =
        playerLoading || (room.inRoom && room.loading)
}
