package org.simpmusic.listentogether

import kotlin.math.abs

/** Pure timing policy shared by the Android bridge and unit tests. Media3 STATE_READY = 3. */
object JamSync {
    fun needsSeek(localMs: Long, hostMs: Long): Boolean = abs(localMs - hostMs) > 500L
    // ExoPlayer owns its buffering threshold, including short/unknown-duration YouTube streams.
    fun ready(state: Int): Boolean = state == 3
}
