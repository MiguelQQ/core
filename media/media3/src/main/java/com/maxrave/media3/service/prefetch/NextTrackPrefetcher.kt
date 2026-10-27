package com.maxrave.media3.service.prefetch

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.SimpleCache
import com.maxrave.common.MERGING_DATA_TYPE
import com.maxrave.domain.data.player.LiveStreamRegistry
import com.maxrave.domain.extension.now
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.repository.StreamRepository
import com.maxrave.logger.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Writes the audio of the next [PrefetchPolicy.UPCOMING_TRACKS] tracks into the player cache, so
 * that a connection that drops mid-queue does not stop the music.
 *
 * The prepared ExoPlayers the adapter already keeps for those tracks hold their audio in memory
 * and lose it when the player is released or errors. This writes it to the same [playerCache] the
 * playback path reads, under the same key (the media id), so once a track is whole on disk the
 * resolver in `Media3ServiceModule` serves it without ever asking for a URL.
 *
 * - The URL is resolved ahead of time through [StreamRepository] (which also stores the format row,
 *   with its expiry), reusing a still-valid stored one.
 * - Only on a network [PrefetchPolicy] allows, with a per-track byte cap; a track over the cap is
 *   cut off and left partial (what was written stays useful to the player).
 * - [update] replaces the target set: a track that is no longer one of the next two is cancelled.
 * - A failure is retried twice with a short delay, then left to the normal playback path.
 */
@UnstableApi
internal class NextTrackPrefetcher(
    context: Context,
    private val scope: CoroutineScope,
    // SimpleCache, not Cache: Koin registers the caches under that class, and looks types up exactly.
    private val playerCache: SimpleCache,
    private val downloadCache: SimpleCache,
    private val upstreamFactory: DataSource.Factory,
    private val streamRepository: StreamRepository,
    private val dataStoreManager: DataStoreManager,
) {
    private val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val jobs = ConcurrentHashMap<String, Job>()

    /** Makes [videoIds] (in play order) the set being prefetched; anything else is cancelled. */
    fun update(videoIds: List<String>) {
        val wanted = videoIds.filter { !LiveStreamRegistry.isLive(it) }.distinct().take(PrefetchPolicy.UPCOMING_TRACKS)
        jobs.keys.filter { it !in wanted }.forEach { id -> jobs.remove(id)?.cancel() }
        wanted.forEach { id ->
            if (jobs[id]?.isActive == true) return@forEach
            jobs[id] = scope.launch(Dispatchers.IO) { prefetch(id) }
        }
    }

    /** Stops everything in flight, e.g. when the queue is replaced or the player is released. */
    fun cancelAll() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
    }

    private suspend fun prefetch(videoId: String) {
        var attempt = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            if (isWhole(videoId)) {
                Logger.d(TAG, "$videoId already whole in cache")
                return
            }
            val network = currentNetwork()
            if (!PrefetchPolicy.allows(network, null)) {
                Logger.d(TAG, "$videoId skipped: network=$network")
                return
            }
            attempt++
            val outcome =
                try {
                    write(videoId, network)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Logger.w(TAG, "$videoId attempt $attempt failed: ${e.javaClass.simpleName}: ${e.message}")
                    Outcome.RETRY
                }
            if (outcome != Outcome.RETRY || attempt >= PrefetchPolicy.MAX_ATTEMPTS) return
            delay(PrefetchPolicy.retryDelayMs(attempt))
        }
    }

    private enum class Outcome { DONE, STOP, RETRY }

    private suspend fun write(
        videoId: String,
        network: PrefetchNetwork,
    ): Outcome {
        val format = streamRepository.getNewFormat(videoId).firstOrNull()
        if (!PrefetchPolicy.allows(network, format?.contentLength)) {
            Logger.d(TAG, "$videoId skipped: ${format?.contentLength} bytes over budget on $network")
            return Outcome.STOP
        }
        val url = resolveUrl(videoId, format?.audioUrl, format?.expiredTime?.let { it > now() } == true) ?: return Outcome.RETRY
        // Resolving can discover a broadcast; its playlist is not a file.
        if (LiveStreamRegistry.isLive(videoId)) return Outcome.STOP
        currentCoroutineContext().ensureActive()

        val budget = PrefetchPolicy.byteBudget(network)
        // Same two-level cache the player reads through, but writing into the player cache only.
        val source =
            CacheDataSource
                .Factory()
                .setCache(playerCache)
                .setUpstreamDataSourceFactory(upstreamFactory)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
                .createDataSource()
        // key == the media id: the player's resolver and CacheDataSource look the track up by it.
        val spec = DataSpec.Builder().setUri(url.toUri()).setKey(videoId).build()
        var overBudget = false
        lateinit var writer: CacheWriter
        writer =
            CacheWriter(source, spec, null) { requestLength, bytesCached, _ ->
                val total = if (requestLength > 0) requestLength else bytesCached
                if (total > budget || bytesCached > budget) {
                    overBudget = true
                    writer.cancel()
                }
            }
        val cancelOnScopeEnd = currentCoroutineContext().job.invokeOnCompletion { writer.cancel() }
        try {
            withContext(Dispatchers.IO) { writer.cache() }
        } catch (e: IOException) {
            // CacheWriter.cancel() surfaces as InterruptedIOException; the budget case is not a failure.
            if (overBudget) {
                Logger.d(TAG, "$videoId stopped at the ${budget / 1024 / 1024} MiB cap")
                return Outcome.STOP
            }
            currentCoroutineContext().ensureActive()
            throw e
        } finally {
            cancelOnScopeEnd.dispose()
        }
        Logger.d(TAG, "$videoId written to the player cache")
        return Outcome.DONE
    }

    /** A stored, unexpired URL if it still answers, otherwise a fresh extraction. */
    private suspend fun resolveUrl(
        videoId: String,
        storedUrl: String?,
        storedValid: Boolean,
    ): String? {
        if (storedUrl != null && storedValid) {
            val rejected = streamRepository.is403Url(storedUrl).firstOrNull() != false
            if (!rejected) return storedUrl
        }
        return streamRepository
            .getStream(dataStoreManager, videoId, isDownloading = false, isVideo = false)
            .lastOrNull()
    }

    private fun isWhole(videoId: String): Boolean {
        val key = videoId.removePrefix(MERGING_DATA_TYPE.VIDEO)
        return isFullyCached(downloadCache, key) || isFullyCached(playerCache, key)
    }

    private fun isFullyCached(
        cache: Cache,
        key: String,
    ): Boolean {
        val total =
            androidx.media3.datasource.cache.ContentMetadata
                .getContentLength(cache.getContentMetadata(key))
        return total > 0L && cache.isCached(key, 0L, total)
    }

    private fun currentNetwork(): PrefetchNetwork {
        val cm = connectivity ?: return PrefetchNetwork.METERED
        return try {
            val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return PrefetchNetwork.NONE
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return PrefetchNetwork.NONE
            when {
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) -> PrefetchNetwork.UNMETERED
                !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING) ||
                    cm.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED ->
                    PrefetchNetwork.RESTRICTED
                else -> PrefetchNetwork.METERED
            }
        } catch (e: SecurityException) {
            // b/163342798: some OEMs throw without ACCESS_NETWORK_STATE races; be conservative.
            PrefetchNetwork.METERED
        }
    }

    private companion object {
        const val TAG = "NextTrackPrefetcher"
    }
}
