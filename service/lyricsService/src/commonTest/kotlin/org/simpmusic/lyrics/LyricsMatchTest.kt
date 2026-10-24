package org.simpmusic.lyrics

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LyricsMatchTest {
    @Test
    fun title_ignores_video_noise_and_rejects_a_different_song() {
        assertTrue(lyricsTitleMatches("Palabras Sobran (Video Oficial)", "Palabras Sobran"))
        assertTrue(lyricsTitleMatches("Palabras Sobran", "BLESSD - Palabras Sobran (Official Video)"))
        assertFalse(lyricsTitleMatches("Palabras Sobran", "Medallo"))
        assertFalse(lyricsTitleMatches("Palabras", "Palabras Sobran"))
    }

    @Test
    fun artist_matches_a_credit_that_includes_the_playing_artist() {
        assertTrue(lyricsArtistMatches("Blessd", "Blessd, Ryan Castro"))
        assertFalse(lyricsArtistMatches("Blessd", "Feid"))
    }

    @Test
    fun shared_artist_words_and_song_title_substrings_are_not_identity() {
        assertFalse(lyricsArtistMatches("Lil Wayne", "Lil Nas X"))
        assertFalse(lyricsArtistMatches("Los Tigres del Norte", "Los Angeles Azules"))
        assertTrue(lyricsArtistMatches("Blessd feat. Ryan Castro", "Ryan Castro"))
        assertFalse(lyricsTitleMatches("Palabras Sobran", "Palabras Sobran Remix"))
        assertFalse(lyricsTitleMatches("Palabras Sobran", "Palabras Sobran Parte 2"))
        assertFalse(lyricsTitleMatches("Palabras Sobran", null))
        assertFalse(lyricsArtistMatches("Blessd", null))
    }

    @Test
    fun duration_rejects_anything_further_than_four_seconds() {
        assertTrue(lyricsDurationMatches(216, 220))
        assertFalse(lyricsDurationMatches(216, 221))
        assertFalse(lyricsDurationMatches(216, null))
        assertFalse(lyricsDurationMatches(0, 216))
    }

    @Test
    fun identity_needs_title_artist_and_duration_together() {
        assertTrue(
            lyricsIdentityMatches(
                expectedTitle = "Palabras Sobran",
                expectedArtist = "Blessd",
                expectedDurationSeconds = 216,
                candidateTitle = "Palabras Sobran",
                candidateArtist = "Blessd",
                candidateDurationSeconds = 218,
            ),
        )
        assertFalse(
            lyricsIdentityMatches(
                expectedTitle = "Palabras Sobran",
                expectedArtist = "Blessd",
                expectedDurationSeconds = 216,
                candidateTitle = "Palabras Sobran",
                candidateArtist = "Blessd",
                candidateDurationSeconds = 180,
            ),
        )
    }
}
