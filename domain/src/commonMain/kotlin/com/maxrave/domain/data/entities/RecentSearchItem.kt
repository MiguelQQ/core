package com.maxrave.domain.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A song, artist, album or playlist the user opened from search.
 * Newest [updatedAt] first, capped at [LIMIT] rows.
 */
@Entity(tableName = "recent_search_item")
data class RecentSearchItem(
    @PrimaryKey val id: String,
    val kind: String,
    val title: String,
    val subtitle: String = "",
    val thumbnail: String? = null,
    val explicit: Boolean = false,
    val videoId: String? = null,
    val browseId: String? = null,
    val updatedAt: Long = 0L,
) {
    companion object {
        const val SONG = "SONG"
        const val ARTIST = "ARTIST"
        const val ALBUM = "ALBUM"
        const val PLAYLIST = "PLAYLIST"
        const val PODCAST = "PODCAST"
        const val LIMIT = 50
    }
}
