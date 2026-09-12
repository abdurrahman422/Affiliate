package com.ownerlink.agent

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ownerlink.agent.chat.ChatDatabase
import com.ownerlink.agent.chat.ContactEntity
import com.ownerlink.agent.chat.MessageEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatDatabaseTest {
    private lateinit var db: ChatDatabase
    @Before fun open() { db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), ChatDatabase::class.java).allowMainThreadQueries().build() }
    @After fun close() = db.close()

    @Test fun contactMessagePendingAndDedupPersist() {
        val dao = db.chatDao()
        dao.saveContact(ContactEntity("alice-123", "A".repeat(64), "Alice"))
        assertEquals("Alice", dao.contact("alice-123")?.name)
        val message = MessageEntity("m1", "alice-123", "hello", true, 1, "PENDING", "text", null, null, null, null, 0, null)
        assertTrue(dao.insertMessage(message) > 0)
        assertEquals(-1L, dao.insertMessage(message))
        assertEquals(listOf("m1"), dao.pending().map { it.id })
        dao.updateState("m1", "DELIVERED")
        assertEquals("DELIVERED", dao.messageState("m1"))
    }
}
