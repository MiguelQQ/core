package com.maxrave.data.killmon

import com.maxrave.domain.data.model.connect.ConnectCommand
import com.maxrave.domain.data.model.connect.ConnectDevice
import com.maxrave.domain.data.model.connect.ConnectPlayback
import com.maxrave.domain.data.model.connect.ConnectSession
import com.maxrave.domain.data.model.connect.ConnectState
import com.maxrave.domain.data.model.connect.ConnectTrack
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.repository.AccountConnectRepository
import com.maxrave.logger.Logger
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal data class ConnectReport(
    val playback: ConnectPlayback? = null,
    val takeover: Boolean = false,
    val ackTransfer: Long? = null,
    val ackControl: Long? = null,
)

@OptIn(ExperimentalTime::class)
internal class AccountConnectRepositoryImpl(
    private val accounts: KillmonAccountRepositoryImpl,
    private val api: KillmonApi,
    private val dataStore: DataStoreManager,
) : AccountConnectRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val kick = Channel<Unit>(Channel.CONFLATED)
    private val _state = MutableStateFlow(ConnectState())
    override val state: StateFlow<ConnectState> = _state.asStateFlow()

    private var ready = false
    private var deviceId: String = ""
    private var label = KillmonDeviceLabel("desktop", "Killmon Desktop")
    var localPlayback: ConnectPlayback? = null

    suspend fun ensureReady(): Boolean {
        if (ready) return true
        deviceId = loadDeviceId()
        label = killmonDeviceLabel()
        accounts.beforeClear = { bye() }
        ready = true
        _state.value = _state.value.copy(deviceId = deviceId, signedIn = accounts.session.value != null)
        return true
    }

    suspend fun awaitKick(timeoutMs: Long) {
        withTimeoutOrNull(timeoutMs) { kick.receive() }
    }

    override fun refresh() {
        kick.trySend(Unit)
    }

    suspend fun exchange(report: ConnectReport) {
        if (!ready) return
        if (accounts.session.value == null) {
            _state.value = ConnectState(deviceId = deviceId)
            return
        }
        val outcome =
            accounts.withAccess { token ->
                api.presence(
                    token,
                    ConnectPresenceBody(
                        deviceId = deviceId,
                        platform = label.platform,
                        displayName = label.displayName,
                        playback = report.playback?.toBody(),
                        takeover = report.takeover,
                        ackTransfer = report.ackTransfer,
                        ackControl = report.ackControl,
                    ),
                )
            }
        when (outcome) {
            null -> _state.value = ConnectState(deviceId = deviceId)
            is ApiOutcome.Failure -> {
                Logger.w(TAG, "presencia ${outcome.status} ${outcome.message}")
                _state.value = _state.value.copy(deviceId = deviceId, signedIn = true, message = outcome.message)
            }
            is ApiOutcome.Success -> _state.value = outcome.value.toState(deviceId)
        }
    }

    override fun transferTo(targetDeviceId: String) {
        scope.launch {
            if (!ready || accounts.session.value == null) return@launch
            if (targetDeviceId == deviceId && _state.value.session?.activeDeviceId == deviceId) return@launch
            val playback = playbackForTransfer() ?: run {
                _state.value = _state.value.copy(message = "No hay una canción para llevar al otro dispositivo.")
                return@launch
            }
            when (
                val outcome =
                    accounts.withAccess { token ->
                        api.transfer(
                            token,
                            ConnectTransferBody(deviceId = deviceId, targetDeviceId = targetDeviceId, playback = playback.toBody()),
                        )
                    }
            ) {
                null -> _state.value = ConnectState(deviceId = deviceId)
                is ApiOutcome.Failure ->
                    _state.value = _state.value.copy(signedIn = true, message = outcome.message ?: "No se pudo transferir.")
                is ApiOutcome.Success -> _state.value = outcome.value.toState(deviceId)
            }
            kick.trySend(Unit)
        }
    }

    override fun remoteControl(
        action: String,
        positionMs: Long?,
    ) {
        scope.launch {
            if (!ready || accounts.session.value == null) return@launch
            val now = Clock.System.now().toEpochMilliseconds()
            _state.value.session?.let { current ->
                val next = current.withControl(action, positionMs, now)
                if (next != current) _state.value = _state.value.copy(session = next)
            }
            when (
                val outcome =
                    accounts.withAccess { token ->
                        api.control(token, ConnectControlBody(deviceId = deviceId, action = action, positionMs = positionMs))
                    }
            ) {
                null -> _state.value = ConnectState(deviceId = deviceId)
                is ApiOutcome.Failure ->
                    _state.value = _state.value.copy(signedIn = true, message = outcome.message ?: "No se pudo controlar el otro dispositivo.")
                is ApiOutcome.Success -> _state.value = outcome.value.toState(deviceId)
            }
            kick.trySend(Unit)
        }
    }

    private fun playbackForTransfer(): ConnectPlayback? {
        val state = _state.value
        val session = state.session
        if (session != null && session.activeDeviceId != deviceId && session.queue.isNotEmpty()) {
            val now = Clock.System.now().toEpochMilliseconds()
            return session.toPlayback().copy(positionMs = session.estimatedPositionMs(now))
        }
        return localPlayback
    }

    private suspend fun bye() {
        if (!ready || deviceId.isBlank()) return
        accounts.withAccess { api.bye(it, ConnectByeBody(deviceId)) }
    }

    private suspend fun loadDeviceId(): String {
        val current = dataStore.getString(KEY_DEVICE).first()?.trim().orEmpty()
        if (DEVICE_ID.matches(current)) return current
        val created = newDeviceId()
        dataStore.putString(KEY_DEVICE, created)
        return created
    }

    private companion object {
        const val TAG = "KillmonConnect"
        const val KEY_DEVICE = "killmon_connect_device_id"
        val DEVICE_ID = Regex("^[A-Za-z0-9_-]{8,80}$")

        fun newDeviceId(): String {
            val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
            return buildString(24) {
                repeat(24) { append(alphabet[kotlin.random.Random.nextInt(alphabet.length)]) }
            }
        }
    }
}

private fun ConnectPlayback.toBody() =
    ConnectPlaybackBody(
        queue =
            queue.map {
                ConnectTrackBody(
                    videoId = it.videoId,
                    title = it.title,
                    artist = it.artist,
                    thumbnail = it.thumbnail,
                    durationMs = it.durationMs,
                )
            },
        index = index,
        positionMs = positionMs,
        isPlaying = isPlaying,
    )

@OptIn(ExperimentalTime::class)
private fun ConnectWire.toState(selfId: String): ConnectState {
    val now = Clock.System.now().toEpochMilliseconds()
    val session =
        session?.let { wire ->
            ConnectSession(
                activeDeviceId = wire.activeDeviceId,
                revision = wire.revision,
                isPlaying = wire.isPlaying,
                positionMs = wire.positionMs.coerceAtLeast(0),
                positionAtMs = now,
                index = wire.index,
                queue =
                    wire.queue.map {
                        ConnectTrack(
                            videoId = it.videoId,
                            title = it.title,
                            artist = it.artist,
                            thumbnail = it.thumbnail,
                            durationMs = it.durationMs,
                        )
                    },
            )
        }
    return ConnectState(
        deviceId = selfId,
        signedIn = true,
        devices =
            devices.map {
                ConnectDevice(
                    deviceId = it.deviceId,
                    platform = it.platform,
                    displayName = it.displayName.ifBlank { it.platform },
                    isActive = it.isActive,
                    isSelf = it.isSelf || it.deviceId == selfId,
                )
            },
        session = session,
        command =
            command?.takeIf { it.type.isNotBlank() }?.let {
                ConnectCommand(
                    type = it.type,
                    revision = it.revision,
                    action = it.action,
                    positionMs = it.positionMs,
                    index = it.index,
                )
            },
    )
}
