package com.ownerlink.agent.chat

import com.ownerlink.agent.security.SecureStore
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.Signature
import java.util.Base64
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties

data class ChatIdentity(val contactId: String, val publicKey: String, val privateKey: PrivateKey)

fun ChatIdentity.signChallenge(challenge: String, deviceId: String): String {
    val signer = Signature.getInstance("SHA256withECDSA")
    signer.initSign(privateKey)
    signer.update("$challenge:$deviceId".toByteArray(Charsets.UTF_8))
    return Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign())
}

object ChatIdentityProvider {
    fun loadOrCreate(store: SecureStore): ChatIdentity {
        var privateEncoded = store.chatPrivateKey
        var publicEncoded = store.chatPublicKey
        // Preserve existing identities so updates never invalidate contact
        // codes. New Android 12+ installs use a non-exportable Keystore key.
        if (privateEncoded.isNullOrBlank() && Build.VERSION.SDK_INT >= 31) {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (!keyStore.containsAlias(KEYSTORE_ALIAS)) {
                KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
                    initialize(
                        KeyGenParameterSpec.Builder(
                            KEYSTORE_ALIAS,
                            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_AGREE_KEY,
                        ).setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                            .setDigests(KeyProperties.DIGEST_SHA256)
                            .setUserAuthenticationRequired(false)
                            .build(),
                    )
                }.generateKeyPair()
            }
            val certificate = requireNotNull(keyStore.getCertificate(KEYSTORE_ALIAS))
            val privateKey = requireNotNull(keyStore.getKey(KEYSTORE_ALIAS, null) as? PrivateKey)
            publicEncoded = Base64.getUrlEncoder().withoutPadding().encodeToString(certificate.publicKey.encoded)
            store.chatPublicKey = publicEncoded
            return identity(publicEncoded, privateKey)
        }
        if (privateEncoded.isNullOrBlank() || publicEncoded.isNullOrBlank()) {
            val generator = KeyPairGenerator.getInstance("EC")
            generator.initialize(ECGenParameterSpec("secp256r1"))
            val pair = generator.generateKeyPair()
            privateEncoded = Base64.getUrlEncoder().withoutPadding().encodeToString(pair.private.encoded)
            publicEncoded = Base64.getUrlEncoder().withoutPadding().encodeToString(pair.public.encoded)
            store.chatPrivateKey = privateEncoded
            store.chatPublicKey = publicEncoded
        }
        val privateKey = KeyFactory.getInstance("EC").generatePrivate(
            PKCS8EncodedKeySpec(Base64.getUrlDecoder().decode(privateEncoded)),
        )
        return identity(publicEncoded, privateKey)
    }

    private fun identity(publicEncoded: String, privateKey: PrivateKey): ChatIdentity {
        val publicBytes = Base64.getUrlDecoder().decode(publicEncoded)
        val contactId = Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(publicBytes).copyOf(18),
        )
        return ChatIdentity(contactId, publicEncoded, privateKey)
    }

    private const val KEYSTORE_ALIAS = "privatchat-contact-identity-v1"
}
