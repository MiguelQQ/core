package org.simpmusic.listentogether

import kotlin.math.abs

/** Pure timing policy shared by the Android bridge and unit tests. Media3 STATE_READY = 3. */
object JamSync {
    fun needsSeek(localMs: Long, hostMs: Long): Boolean = abs(localMs - hostMs) > 500L
    fun ready(state: Int, bufferedMs: Long, positionMs: Long, durationMs: Long): Boolean {
        val required = if (durationMs > 0) minOf(3000L, (durationMs - positionMs).coerceAtLeast(0)) else 3000L
        return state == 3 && bufferedMs - positionMs >= required
    }
}
