package com.ownerlink.agent.pairing

import com.ownerlink.agent.security.SecureStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class PairingPayload(
    val v: Int = 1,
    val server: String,
    val device_id: String,
    val pairing_token: String,
)

object PairingJson {
    private val json = Json { encodeDefaults = true }

    fun fromStore(store: SecureStore): PairingPayload {
        return PairingPayload(
            server = store.serverUrl,
            device_id = store.deviceId,
            pairing_token = store.pairingToken,
        )
    }

    fun encode(store: SecureStore): String = json.encodeToString(fromStore(store))
}
