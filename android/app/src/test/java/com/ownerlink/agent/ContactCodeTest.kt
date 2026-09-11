package com.ownerlink.agent

import com.ownerlink.agent.chat.ContactCard
import com.ownerlink.agent.chat.ContactCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactCodeTest {
    @Test fun roundTripContactCode() {
        val card = ContactCard("contact-123", "A".repeat(64), "Alice")
        assertEquals(card, ContactCode.parse(ContactCode.encode(card)))
    }

    @Test fun dashboardCodeCannotBecomeContact() {
        val error = runCatching { ContactCode.parse("""{"v":1,"type":"dashboard_pair","contact_id":"contact-123","public_key":"${"A".repeat(64)}","name":"Alice"}""") }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }
}
