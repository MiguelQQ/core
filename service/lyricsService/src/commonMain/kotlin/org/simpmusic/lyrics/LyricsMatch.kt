package org.simpmusic.lyrics

import kotlin.math.abs

/** A catalog hit whose length is further than this from the playing track is a different recording. */
const val LYRICS_DURATION_TOLERANCE_SECONDS = 4

private val parenthesisFeat = Regex("\\((?:feat\\.|ft\\.|featuring)[^)]*\\)", RegexOption.IGNORE_CASE)

// Words a YouTube title adds around the song. Stripped so "Palabras Sobran (Video Oficial)"
// still equals the catalog title, without treating a different song as the same one.
private val titleNoise =
    Regex(
        "\\b(official|oficial|video|audio|lyric|lyrics|visualizer|hd|4k|remastered|remaster)\\b",
        RegexOption.IGNORE_CASE,
    )

fun normalizeLyricsField(value: String): String {
    var text = value.lowercase()
    text = parenthesisFeat.replace(text, " ")
    text = text.replace(Regex("[\\[\\](){}]"), " ")
    text = text.replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
    return text.replace(Regex("\\s+"), " ").trim()
}

fun normalizeLyricsTitle(value: String): String {
    val text = titleNoise.replace(normalizeLyricsField(value), " ")
    return text.replace(Regex("\\s+"), " ").trim()
}

fun lyricsTitleMatches(
    expected: String,
    candidate: String?,
): Boolean {
    if (candidate.isNullOrBlank()) return false
    val left = normalizeLyricsTitle(expected)
    val right = normalizeLyricsTitle(candidate)
    if (left.isEmpty() || right.isEmpty()) return false
    if (left == right) return true
    // Only an explicit artist/title separator may add a prefix. Substring matching
    // also accepted "Palabras Sobran Remix" or "Palabras Sobran Parte 2".
    return expected.split(" - ").drop(1).any { normalizeLyricsTitle(it) == right } ||
        candidate.split(" - ").drop(1).any { normalizeLyricsTitle(it) == left }
}

fun lyricsArtistMatches(
    expected: String,
    candidate: String?,
): Boolean {
    if (candidate.isNullOrBlank()) return false
    val left = normalizeLyricsField(expected)
    val right = normalizeLyricsField(candidate)
    if (left.isEmpty() || right.isEmpty()) return false
    if (left == right) return true
    // Compare complete artist credits. One shared token ("Lil", "Los", "Young")
    // does not identify an artist.
    fun credits(value: String): Set<String> =
        value.split(Regex("\\s*(?:,|&|;| feat\\.? | ft\\.? | featuring | x )\\s*", RegexOption.IGNORE_CASE))
            .map(::normalizeLyricsField)
            .filter { it.isNotEmpty() }
            .toSet()
    return credits(expected).intersect(credits(candidate)).isNotEmpty()
}

/**
 * True when [candidateDurationSeconds] is the same recording as [expectedDurationSeconds].
 * A missing length on either side is not a match: the caller has to know both.
 */
fun lyricsDurationMatches(
    expectedDurationSeconds: Int?,
    candidateDurationSeconds: Int?,
): Boolean {
    if (expectedDurationSeconds == null || expectedDurationSeconds <= 0) return false
    if (candidateDurationSeconds == null || candidateDurationSeconds <= 0) return false
    return abs(expectedDurationSeconds - candidateDurationSeconds) <= LYRICS_DURATION_TOLERANCE_SECONDS
}

fun lyricsIdentityMatches(
    expectedTitle: String,
    expectedArtist: String,
    expectedDurationSeconds: Int?,
    candidateTitle: String?,
    candidateArtist: String?,
    candidateDurationSeconds: Int?,
): Boolean =
    lyricsTitleMatches(expectedTitle, candidateTitle) &&
        lyricsArtistMatches(expectedArtist, candidateArtist) &&
        lyricsDurationMatches(expectedDurationSeconds, candidateDurationSeconds)
