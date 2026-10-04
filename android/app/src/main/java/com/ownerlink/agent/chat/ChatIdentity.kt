package com.ownerlink.agent.chat

import com.ownerlink.agent.security.SecureStore
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.Signature
import java.util.Base64

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
        // The identity is deliberately device-bound and stored with the app data.
        // Avoid synchronous AndroidKeyStore initialization here: affected Motorola
        // firmware can block its binder call and freeze Application.onCreate.
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
            MessageDigest.getInstance("SHA-256").digest(publicBytes).copyOf(16),
        )
        return ChatIdentity(contactId, publicEncoded, privateKey)
    }
}
