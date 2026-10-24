package com.maxrave.media3.service.callback

import org.junit.Assert.assertEquals
import org.junit.Test

class LargeArtworkTest {
    @Test
    fun upgradesTinyGoogleSizes() {
        assertEquals(
            "https://lh3.googleusercontent.com/a=w544-h544-l90-rw",
            "https://lh3.googleusercontent.com/a=w60-h60-l90-rw".toLargeArtworkUrl(),
        )
        assertEquals(
            "https://lh3.googleusercontent.com/a=w544-h544-l90-rw",
            "https://lh3.googleusercontent.com/a=w120-h120-l90-rw".toLargeArtworkUrl(),
        )
        assertEquals(
            "https://lh3.googleusercontent.com/a=w544-h544-l90-rw",
            "https://lh3.googleusercontent.com/a=w544-h544-l90-rw".toLargeArtworkUrl(),
        )
    }

    @Test
    fun upgradesBareAndAvatarSizes() {
        assertEquals(
            "https://example.com/w544-h544-l90",
            "https://example.com/w60-h60-l90".toLargeArtworkUrl(),
        )
        assertEquals(
            "https://yt3.ggpht.com/a=s544-c",
            "https://yt3.ggpht.com/a=s60-c".toLargeArtworkUrl(),
        )
    }

    @Test
    fun upgradesYoutubeStillPathsAndLeavesResources() {
        assertEquals(
            "https://i.ytimg.com/vi/abc/maxresdefault.jpg",
            "https://i.ytimg.com/vi/abc/sddefault.jpg".toLargeArtworkUrl(),
        )
        assertEquals(
            "https://i.ytimg.com/vi/abc/hqdefault.jpg",
            "https://i.ytimg.com/vi/abc/default.jpg".toLargeArtworkUrl(),
        )
        assertEquals(
            "android.resource://com.killmon.music/drawable/home_android_auto",
            "android.resource://com.killmon.music/drawable/home_android_auto".toLargeArtworkUrl(),
        )
    }
}
