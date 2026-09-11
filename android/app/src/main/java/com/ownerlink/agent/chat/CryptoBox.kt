package com.ownerlink.agent.chat

import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class SealedText(val ciphertext: String, val nonce: String)

object CryptoBox {
    fun seal(text: String, ownPrivate: PrivateKey, peerPublic: String): SealedText {
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, sharedKey(ownPrivate, peerPublic), GCMParameterSpec(128, nonce))
        return SealedText(b64(cipher.doFinal(text.toByteArray(Charsets.UTF_8))), b64(nonce))
    }
    fun open(sealed: SealedText, ownPrivate: PrivateKey, peerPublic: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, sharedKey(ownPrivate, peerPublic), GCMParameterSpec(128, decode(sealed.nonce)))
        return cipher.doFinal(decode(sealed.ciphertext)).toString(Charsets.UTF_8)
    }
    private fun sharedKey(privateKey: PrivateKey, peerPublic: String): SecretKeySpec {
        val publicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(decode(peerPublic)))
        val agreement = KeyAgreement.getInstance("ECDH").apply { init(privateKey); doPhase(publicKey, true) }
        return SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(agreement.generateSecret()), "AES")
    }
    private fun b64(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    private fun decode(value: String) = Base64.getUrlDecoder().decode(value)
}
