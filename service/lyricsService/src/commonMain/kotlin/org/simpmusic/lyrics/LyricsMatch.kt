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
    // "BLESSD - Palabras Sobran (Official Video)" against "Palabras Sobran". A single short
    // word is not enough: "Palabras" must not accept "Palabras Sobran".
    val shorter = if (left.length <= right.length) left else right
    val longer = if (left.length <= right.length) right else left
    val shortTokens = shorter.split(" ")
    if (shortTokens.size < 2 && shorter.length < 10) return false
    val longTokens = longer.split(" ")
    if (shortTokens.size > longTokens.size) return false
    return (0..longTokens.size - shortTokens.size).any { start ->
        longTokens.subList(start, start + shortTokens.size) == shortTokens
    }
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
    val leftTokens = left.split(" ").filter { it.length >= 3 }.toSet()
    val rightTokens = right.split(" ").filter { it.length >= 3 }.toSet()
    if (leftTokens.isEmpty() || rightTokens.isEmpty()) return false
    return leftTokens.any { it in rightTokens }
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
