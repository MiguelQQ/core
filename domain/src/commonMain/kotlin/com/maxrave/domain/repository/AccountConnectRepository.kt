package com.maxrave.domain.repository

import com.maxrave.domain.data.model.connect.ConnectState
import kotlinx.coroutines.flow.StateFlow

/**
 * Dispositivos de la misma cuenta Killmon. No es la sala JAM (varios usuarios):
 * aquí el audio vive en un solo aparato y se puede llevar a otro.
 */
interface AccountConnectRepository {
    val state: StateFlow<ConnectState>

    /** Pide ya la lista, sin esperar al siguiente latido. */
    fun refresh()

    /** Lleva la sesión (cola y posición) a [targetDeviceId]. El audio pasa a sonar allí. */
    fun transferTo(targetDeviceId: String)

    /** Play, pause, seek, next, previous o volume sobre el aparato que tiene el audio. */
    fun remoteControl(
        action: String,
        positionMs: Long? = null,
        volume: Float? = null,
    )
}
