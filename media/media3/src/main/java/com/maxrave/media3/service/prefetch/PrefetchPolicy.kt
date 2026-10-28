package com.maxrave.media3.service.prefetch

/** What the active network allows us to spend on tracks nobody has asked to hear yet. */
internal enum class PrefetchNetwork {
    /** No usable connection: there is nothing to download over. */
    NONE,

    /** Wi-Fi, Ethernet or any other connection the carrier does not bill by the byte. */
    UNMETERED,

    /** Mobile data, with no Data Saver and not roaming. */
    METERED,

    /** Roaming, or Data Saver on for this app: the user asked us to keep the bytes down. */
    RESTRICTED,
}

/**
 * The rules of the next-track prefetch, kept free of Android types so they can be unit tested.
 *
 * The prefetch is a cushion, not a download feature: it writes the audio of the next
 * [UPCOMING_TRACKS] tracks into the player cache so a connection that drops for a while does not
 * stop the music. Everything here exists to keep it cheap and to make it stand down.
 */
internal object PrefetchPolicy {
    /** How many tracks ahead of the playing one are written to disk. */
    const val UPCOMING_TRACKS = 2

    private const val MIB = 1024L * 1024L

    /** Per track on Wi-Fi: a 10-minute 256 kbps song is ~19 MiB, so this covers long mixes too. */
    const val MAX_BYTES_UNMETERED = 48 * MIB

    /** Per track on mobile data: a normal 3-5 minute song, not a podcast or an hour-long mix. */
    const val MAX_BYTES_METERED = 16 * MIB

    /** Failed attempts per track before it is left to the normal playback path. */
    const val MAX_ATTEMPTS = 3

    /** Bytes one track may cost on [network]; 0 means "do not prefetch at all". */
    fun byteBudget(network: PrefetchNetwork): Long =
        when (network) {
            PrefetchNetwork.UNMETERED -> MAX_BYTES_UNMETERED
            PrefetchNetwork.METERED -> MAX_BYTES_METERED
            PrefetchNetwork.NONE, PrefetchNetwork.RESTRICTED -> 0L
        }

    /**
     * Whether a track of [contentLength] bytes (null when the format row has none yet) is worth
     * writing. An unknown length is allowed; the writer is cut off at the budget regardless.
     */
    fun allows(
        network: PrefetchNetwork,
        contentLength: Long?,
    ): Boolean {
        val budget = byteBudget(network)
        if (budget <= 0L) return false
        return contentLength == null || contentLength <= 0L || contentLength <= budget
    }

    /** Wait before retry number [attempt] (1-based): 4 s, then 12 s. */
    fun retryDelayMs(attempt: Int): Long = if (attempt <= 1) 4_000L else 12_000L

    /**
     * The playlist indices that will play after [current], in play order and at most [count] of
     * them. With shuffle on that is the shuffled order, not the playlist order: [shuffleOrder] lists
     * playlist indices in play order and [shuffleIndices] is its inverse.
     *
     * Stops at the end of the queue unless [repeatAll], never returns [current] itself, and never
     * repeats an index.
     */
    fun upcomingIndices(
        size: Int,
        current: Int,
        count: Int,
        repeatAll: Boolean,
        shuffleOrder: List<Int>?,
        shuffleIndices: List<Int>?,
    ): List<Int> {
        if (size <= 0 || current !in 0 until size || count <= 0) return emptyList()
        val shuffled =
            shuffleOrder != null &&
                shuffleIndices != null &&
                shuffleOrder.size == size &&
                shuffleIndices.size == size
        val position = if (shuffled) shuffleIndices!![current] else current
        val out = ArrayList<Int>(count)
        for (step in 1..count) {
            var next = position + step
            if (next >= size) {
                if (!repeatAll) break
                next %= size
            }
            val index = if (shuffled) shuffleOrder!![next] else next
            if (index == current || index in out) break
            out.add(index)
        }
        return out
    }
}
