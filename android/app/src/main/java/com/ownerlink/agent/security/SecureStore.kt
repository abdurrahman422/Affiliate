package com.ownerlink.agent.security

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

class SecureStore(private val context: Context) {
    private val fallback: SharedPreferences =
        context.getSharedPreferences("ownerlink_fallback", Context.MODE_PRIVATE)
    private var encrypted: SharedPreferences? = null

    init {
        encrypted = createEncrypted()
        ensureIdentity()
    }

    private fun createEncrypted(): SharedPreferences? {
        return try {
            val alias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            EncryptedSharedPreferences.create(
                "ownerlink_secure",
                alias,
                context,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun prefs(): SharedPreferences {
        if (encrypted == null) encrypted = createEncrypted()
        return encrypted ?: fallback
    }

    private fun ensureIdentity() {
        if (deviceId.isBlank()) deviceId = UUID.randomUUID().toString()
        if (pairingToken.isBlank()) pairingToken = newPairingToken()
        if (serverUrl.isBlank()) serverUrl = PRODUCTION_SERVER
    }

    var serverUrl: String
        get() = prefs().getString(KEY_SERVER, "") ?: ""
        set(value) { prefs().edit().putString(KEY_SERVER, value.trim().trimEnd('/')).apply() }

    var deviceId: String
        get() = prefs().getString(KEY_DEVICE_ID, "") ?: ""
        set(value) { prefs().edit().putString(KEY_DEVICE_ID, value).apply() }

    var pairingToken: String
        get() = prefs().getString(KEY_PAIRING, "") ?: ""
        set(value) { prefs().edit().putString(KEY_PAIRING, value).apply() }

    var sessionToken: String?
        get() = prefs().getString(KEY_SESSION, null)
        set(value) {
            prefs().edit().apply {
                if (value == null) remove(KEY_SESSION) else putString(KEY_SESSION, value)
            }.apply()
        }

    var friendlyName: String
        get() = prefs().getString(KEY_NAME, "") ?: ""
        set(value) { prefs().edit().putString(KEY_NAME, value).apply() }

    var paired: Boolean
        get() = prefs().getBoolean(KEY_PAIRED, false)
        set(value) { prefs().edit().putBoolean(KEY_PAIRED, value).apply() }

    var serviceWanted: Boolean
        get() = prefs().getBoolean(KEY_SERVICE, false)
        set(value) { prefs().edit().putBoolean(KEY_SERVICE, value).apply() }

    var permissionsSeen: Boolean
        get() = prefs().getBoolean(KEY_PERMS, false)
        set(value) { prefs().edit().putBoolean(KEY_PERMS, value).apply() }

    var onboardingComplete: Boolean
        get() = prefs().getBoolean(KEY_ONBOARDING, false)
        set(value) { prefs().edit().putBoolean(KEY_ONBOARDING, value).apply() }

    var profileUri: String?
        get() = prefs().getString(KEY_PROFILE_URI, null)
        set(value) { prefs().edit().putString(KEY_PROFILE_URI, value).apply() }

    var chatPrivateKey: String?
        get() = prefs().getString(KEY_CHAT_PRIVATE, null)
        set(value) { prefs().edit().putString(KEY_CHAT_PRIVATE, value).apply() }

    var chatPublicKey: String?
        get() = prefs().getString(KEY_CHAT_PUBLIC, null)
        set(value) { prefs().edit().putString(KEY_CHAT_PUBLIC, value).apply() }

    var safTrees: Set<String>
        get() = prefs().getStringSet(KEY_SAF, emptySet()) ?: emptySet()
        set(value) { prefs().edit().putStringSet(KEY_SAF, value).apply() }

    fun addSafTree(uri: String) {
        safTrees = safTrees + uri
    }

    fun markPaired(session: String) {
        sessionToken = session
        paired = true
        pairingToken = ""
    }

    fun markRevoked() {
        sessionToken = null
        paired = false
        pairingToken = newPairingToken()
    }

    fun regeneratePairingToken() {
        pairingToken = newPairingToken()
        sessionToken = null
        paired = false
    }

    companion object {
        private const val KEY_SERVER = "server_url"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_PAIRING = "pairing_token"
        private const val KEY_SESSION = "session_token"
        private const val KEY_NAME = "friendly_name"
        private const val KEY_PAIRED = "paired"
        private const val KEY_SERVICE = "service_wanted"
        private const val KEY_PERMS = "permissions_seen"
        private const val KEY_SAF = "saf_trees"
        private const val KEY_ONBOARDING = "privatchat_onboarding"
        private const val KEY_PROFILE_URI = "privatchat_profile_uri"
        private const val KEY_CHAT_PRIVATE = "privatchat_identity_private"
        private const val KEY_CHAT_PUBLIC = "privatchat_identity_public"
        const val PRODUCTION_SERVER = "https://ownerlink-curr.onrender.com"

        fun newPairingToken(): String {
            val bytes = ByteArray(32)
            SecureRandom().nextBytes(bytes)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }
    }
}
