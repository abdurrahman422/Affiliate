package com.ownerlink.agent.chat

import com.ownerlink.agent.security.SecureStore
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

data class ChatIdentity(val contactId: String, val publicKey: String, val privateKey: PrivateKey)

object ChatIdentityProvider {
    fun loadOrCreate(store: SecureStore): ChatIdentity {
        var privateEncoded = store.chatPrivateKey
        var publicEncoded = store.chatPublicKey
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
        val publicBytes = Base64.getUrlDecoder().decode(publicEncoded)
        val contactId = Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(publicBytes).copyOf(18),
        )
        return ChatIdentity(contactId, publicEncoded, privateKey)
    }
}
