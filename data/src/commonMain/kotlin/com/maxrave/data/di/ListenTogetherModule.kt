package com.maxrave.data.di

import com.maxrave.common.Config
import com.maxrave.data.listentogether.ListenTogetherPlaybackBridge
import com.maxrave.data.listentogether.ListenTogetherRepositoryImpl
import com.maxrave.domain.repository.ListenTogetherRepository
import kotlinx.coroutines.CoroutineScope
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.simpmusic.listentogether.ListenTogetherSession

/**
 * Listen Together lives for as long as the app does, not as long as its screen.
 *
 * The session was originally created inside the ViewModel, which meant leaving the screen tore the
 * socket down and left the room — while playback, the thing the room is about, kept running in the
 * background. Singletons here, and the screen merely observes them.
 */
val listenTogetherModule =
    module {
        single {
            val accounts = get<com.maxrave.domain.repository.KillmonAccountRepository>()
            ListenTogetherSession(accessToken = { accounts.jamAccessToken() })
        }

        // The boundary: everything above this line speaks domain types only.
        single<ListenTogetherRepository> {
            ListenTogetherRepositoryImpl(
                session = get(),
                scope = get<CoroutineScope>(named(Config.SERVICE_SCOPE)),
            )
        }

        // createdAtStart: the bridge has to be listening before the user joins anything, otherwise
        // the first track change of a room is missed and the guest sits in silence.
        single(createdAtStart = true) {
            ListenTogetherPlaybackBridge(
                repository = get(),
                session = get(),
                handler = get(),
                songs = get(),
                scope = get<CoroutineScope>(named(Config.SERVICE_SCOPE)),
            ).also { it.start() }
        }
    }
