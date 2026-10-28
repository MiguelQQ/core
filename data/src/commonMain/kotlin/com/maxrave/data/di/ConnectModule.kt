package com.maxrave.data.di

import com.maxrave.common.Config
import com.maxrave.data.killmon.AccountConnectRepositoryImpl
import com.maxrave.data.killmon.ConnectPlaybackBridge
import com.maxrave.data.killmon.KillmonAccountRepositoryImpl
import com.maxrave.data.killmon.KillmonApi
import com.maxrave.domain.repository.AccountConnectRepository
import com.maxrave.domain.repository.KillmonAccountRepository
import kotlinx.coroutines.CoroutineScope
import org.koin.core.qualifier.named
import org.koin.dsl.module

val connectModule =
    module {
        single<AccountConnectRepository> {
            val accounts = get<KillmonAccountRepository>() as KillmonAccountRepositoryImpl
            val dataStore = get<com.maxrave.domain.manager.DataStoreManager>()
            val repo = AccountConnectRepositoryImpl(accounts, KillmonApi(), dataStore)
            ConnectPlaybackBridge(
                connect = repo,
                handler = get(),
                jam = get(),
                dataStore = dataStore,
                scope = get<CoroutineScope>(named(Config.SERVICE_SCOPE)),
            ).start()
            repo
        }
    }
