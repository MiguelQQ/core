package com.maxrave.domain.data.player

import kotlinx.coroutines.flow.MutableStateFlow

/** App-wide routing for player entry points. StateFlow publishes safely to the Media3 loader. */
object JamPlaybackRouter {
    interface Actions {
        fun add(item: GenericMediaItem)
        fun control(type: String, positionMs: Long? = null)
    }
    private val current = MutableStateFlow<Actions?>(null)
    var actions: Actions?
        get() = current.value
        set(value) { current.value = value }
}
