/*
 *
 *  ******************************************************************
 *  *  * Copyright (C) 2022
 *  *  * RpcImage.kt is part of Kizzy
 *  *  *  and can not be copied and/or distributed without the express
 *  *  * permission of yzziK(Vaibhav)
 *  *  *****************************************************************
 *
 *
 */

package com.my.kizzy.rpc

/**
 * Modified by Zion Huang
 */
sealed class RpcImage {
    abstract suspend fun resolveImage(resolveExternalImage: suspend (String) -> String?): String?

    class DiscordImage(val image: String) : RpcImage() {
        override suspend fun resolveImage(resolveExternalImage: suspend (String) -> String?): String {
            return if (image.startsWith("http")) image else "mp:${image}"
        }
    }

    class ExternalImage(
        val image: String,
        private val fallbackDiscordAsset: String? = null,
    ) : RpcImage() {
        override suspend fun resolveImage(resolveExternalImage: suspend (String) -> String?): String? {
            resolveOne(image, resolveExternalImage)?.let { return it }
            val fallback = fallbackDiscordAsset ?: return null
            if (fallback == image) return null
            // An http fallback is an external URL, same as the primary image: "mp:" is only valid
            // for a media-proxy path returned by external-assets, so it has to be resolved too.
            return resolveOne(fallback, resolveExternalImage)
        }

        private suspend fun resolveOne(
            url: String,
            resolveExternalImage: suspend (String) -> String?,
        ): String? {
            if (!url.startsWith("http")) {
                return if (url.startsWith("mp:")) url else "mp:$url"
            }
            val asset = ArtworkCache.getOrFetch(url) { resolveExternalImage(url) } ?: return null
            return if (asset.startsWith("http") || asset.startsWith("mp:")) asset else "mp:$asset"
        }
    }
}
