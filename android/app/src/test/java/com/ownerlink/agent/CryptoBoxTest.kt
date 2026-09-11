package com.ownerlink.agent

import com.ownerlink.agent.chat.CryptoBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64

class CryptoBoxTest {
    @Test fun ecdhRoundTripDoesNotExposePlaintext() {
        val generator = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
        val alice = generator.generateKeyPair(); val bob = generator.generateKeyPair()
        val bobPublic = Base64.getUrlEncoder().withoutPadding().encodeToString(bob.public.encoded)
        val alicePublic = Base64.getUrlEncoder().withoutPadding().encodeToString(alice.public.encoded)
        val sealed = CryptoBox.seal("secret hello", alice.private, bobPublic)
        assertNotEquals("secret hello", sealed.ciphertext)
        assertEquals("secret hello", CryptoBox.open(sealed, bob.private, alicePublic))
    }
}
