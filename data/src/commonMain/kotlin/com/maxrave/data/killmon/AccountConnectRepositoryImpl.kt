package com.maxrave.data.killmon

import com.maxrave.domain.data.model.connect.ConnectCommand
import com.maxrave.domain.data.model.connect.ConnectDevice
import com.maxrave.domain.data.model.connect.ConnectPlayback
import com.maxrave.domain.data.model.connect.ConnectSession
import com.maxrave.domain.data.model.connect.ConnectState
import com.maxrave.domain.data.model.connect.ConnectTrack
import com.maxrave.domain.data.model.connect.PlaybackHold
import com.maxrave.domain.data.model.connect.holdFor
import com.maxrave.domain.data.model.connect.mergeConnectSession
import com.maxrave.domain.data.model.connect.shouldSendTransport
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val stateMutex = Mutex()
    private var nextSerial = 0
    private var newestApplied = 0
    private var hold: PlaybackHold? = null
    private var lastTransportAction: String? = null
    private var lastTransportAt = 0L

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
        val serial = stateMutex.withLock { ++nextSerial }
        when (outcome) {
            null -> _state.value = ConnectState(deviceId = deviceId)
            is ApiOutcome.Failure -> {
                Logger.w(TAG, "presencia ${outcome.status} ${outcome.message}")
                _state.value = _state.value.copy(deviceId = deviceId, signedIn = true, message = outcome.message)
            }
            is ApiOutcome.Success -> applyWire(outcome.value.toState(deviceId), serial)
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
                is ApiOutcome.Success -> {
                    val serial = stateMutex.withLock { ++nextSerial }
                    applyWire(outcome.value.toState(deviceId), serial)
                }
            }
            kick.trySend(Unit)
        }
    }

    override fun remoteControl(
        action: String,
        positionMs: Long?,
        volume: Float?,
    ) {
        scope.launch {
            if (!ready || accounts.session.value == null) return@launch
            val now = Clock.System.now().toEpochMilliseconds()
            // Debounce, estado optimista y hold van juntos: un presence en vuelo no puede
            // colarse entre el gesto y el hold y reponer el is_playing viejo.
            val serial =
                stateMutex.withLock {
                    if (!shouldSendTransport(lastTransportAction, lastTransportAt, action, now)) return@withLock -1
                    if (action == "play" || action == "pause") {
                        lastTransportAction = action
                        lastTransportAt = now
                    }
                    _state.value.session?.let { current ->
                        val next = current.withControl(action, positionMs, now, volume)
                        if (next != current) {
                            hold = holdFor(action, next, current.revision, now) ?: hold
                            _state.value = _state.value.copy(session = next)
                        }
                    }
                    ++nextSerial
                }
            if (serial < 0) return@launch
            when (
                val outcome =
                    accounts.withAccess { token ->
                        api.control(
                            token,
                            ConnectControlBody(
                                deviceId = deviceId,
                                action = action,
                                positionMs = positionMs,
                                volume = volume,
                            ),
                        )
                    }
            ) {
                null ->
                    stateMutex.withLock {
                        if (hold?.issuedAtMs == now) hold = null
                        _state.value = ConnectState(deviceId = deviceId)
                    }
                is ApiOutcome.Failure ->
                    stateMutex.withLock {
                        if (hold?.issuedAtMs == now) hold = null
                        _state.value =
                            _state.value.copy(
                                signedIn = true,
                                message = outcome.message ?: "No se pudo controlar el otro dispositivo.",
                            )
                    }
                is ApiOutcome.Success -> applyWire(outcome.value.toState(deviceId), serial)
            }
            kick.trySend(Unit)
        }
    }

    private suspend fun applyWire(incoming: ConnectState, serial: Int) {
        stateMutex.withLock {
            val now = Clock.System.now().toEpochMilliseconds()
            val merged = mergeConnectSession(_state.value.session, incoming.session, hold, now)
            hold = merged.hold
            val latest = serial >= newestApplied
            if (latest) newestApplied = serial
            _state.value =
                if (latest) {
                    incoming.copy(session = merged.session)
                } else {
                    _state.value.copy(session = merged.session)
                }
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
        volume = volume.coerceIn(0f, 1f),
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
                volume = wire.volume.coerceIn(0f, 1f),
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
                    volume = it.volume,
                )
            },
    )
}
