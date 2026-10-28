package com.maxrave.domain.data.model.connect

import com.maxrave.common.AudioCandidate
import com.maxrave.common.ITAG
import com.maxrave.common.QUALITY
import kotlin.test.Test
import kotlin.test.assertEquals

class AudioQualitySelectionTest {
    @Test
    fun highOpusBeatsMediumEvenWhenTheHighBitrateIsMissing() {
        val chosen =
            ITAG.bestAudio(
                listOf(
                    AudioCandidate(ITAG.AUDIO_OPUS_MEDIUM, bitrate = 158_000, audioQuality = "AUDIO_QUALITY_MEDIUM"),
                    AudioCandidate(ITAG.AUDIO_OPUS_LOW, bitrate = 66_000, audioQuality = "AUDIO_QUALITY_LOW"),
                    AudioCandidate(ITAG.AUDIO_OPUS_HIGH, bitrate = 0, audioQuality = "AUDIO_QUALITY_HIGH"),
                ),
            )
        assertEquals(ITAG.AUDIO_OPUS_HIGH, chosen?.itag)
    }

    @Test
    fun aacHighBeatsMediumWhenOpusHighIsAbsent() {
        val chosen =
            ITAG.bestAudio(
                listOf(
                    AudioCandidate(ITAG.AUDIO_OPUS_MEDIUM, bitrate = 148_000),
                    AudioCandidate(ITAG.AUDIO_AAC_HIGH, bitrate = 256_000, audioQuality = "AUDIO_QUALITY_HIGH"),
                ),
            )
        assertEquals(ITAG.AUDIO_AAC_HIGH, chosen?.itag)
    }

    @Test
    fun withoutAHighItagTheHighestBitrateRemains() {
        val chosen =
            ITAG.bestAudio(
                listOf(
                    AudioCandidate(ITAG.AUDIO_OPUS_LOW, bitrate = 70_000),
                    AudioCandidate(ITAG.AUDIO_OPUS_MEDIUM, bitrate = 160_000),
                ),
            )
        assertEquals(ITAG.AUDIO_OPUS_MEDIUM, chosen?.itag)
    }

    @Test
    fun storedLowAndMediumMigrateToHighOpus() {
        assertEquals(QUALITY.defaultLabel, QUALITY.normalize("Medium - 129kps"))
        assertEquals(QUALITY.defaultLabel, QUALITY.normalize("Low - 66kps"))
        assertEquals(QUALITY.defaultLabel, QUALITY.normalize(null))
        assertEquals(QUALITY.defaultLabel, QUALITY.normalize("High - 256kps (YT Premium)"))
        assertEquals("High AAC - 256kps (YT Premium)", QUALITY.normalize("High AAC - 256kps (YT Premium)"))
        assertEquals(ITAG.AUDIO_OPUS_HIGH, QUALITY.itagOf("Medium - 129kps"))
    }
}
