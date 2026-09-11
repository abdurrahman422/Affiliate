package com.ownerlink.agent.chat

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

enum class MessageState { PENDING, SENT, DELIVERED, READ }
data class ChatMessage(val id: String, val contactId: String, val body: String, val outgoing: Boolean, val createdAt: Long, val state: MessageState)

class ChatStore(context: Context) : SQLiteOpenHelper(context, "privatchat.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE contacts(contact_id TEXT PRIMARY KEY, public_key TEXT NOT NULL, name TEXT NOT NULL)")
        db.execSQL("CREATE TABLE messages(id TEXT PRIMARY KEY, contact_id TEXT NOT NULL, body TEXT NOT NULL, outgoing INTEGER NOT NULL, created_at INTEGER NOT NULL, state TEXT NOT NULL)")
        db.execSQL("CREATE INDEX messages_contact_time ON messages(contact_id, created_at)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun saveContact(card: ContactCard) = writableDatabase.insertWithOnConflict("contacts", null, ContentValues().apply {
        put("contact_id", card.contactId); put("public_key", card.publicKey); put("name", card.name)
    }, SQLiteDatabase.CONFLICT_REPLACE)

    fun contacts(): List<ContactCard> = readableDatabase.rawQuery("SELECT contact_id,public_key,name FROM contacts ORDER BY name COLLATE NOCASE", null).use { c ->
        buildList { while (c.moveToNext()) add(ContactCard(c.getString(0), c.getString(1), c.getString(2))) }
    }

    fun contact(contactId: String): ContactCard? = readableDatabase.rawQuery("SELECT contact_id,public_key,name FROM contacts WHERE contact_id=?", arrayOf(contactId)).use { c ->
        if (c.moveToFirst()) ContactCard(c.getString(0), c.getString(1), c.getString(2)) else null
    }

    fun putMessage(message: ChatMessage): Boolean = writableDatabase.insertWithOnConflict("messages", null, ContentValues().apply {
        put("id", message.id); put("contact_id", message.contactId); put("body", message.body); put("outgoing", if (message.outgoing) 1 else 0)
        put("created_at", message.createdAt); put("state", message.state.name)
    }, SQLiteDatabase.CONFLICT_IGNORE) != -1L

    fun updateState(id: String, state: MessageState) = writableDatabase.update("messages", ContentValues().apply { put("state", state.name) }, "id=?", arrayOf(id))

    fun messages(contactId: String): List<ChatMessage> = readableDatabase.rawQuery(
        "SELECT id,contact_id,body,outgoing,created_at,state FROM messages WHERE contact_id=? ORDER BY created_at", arrayOf(contactId)
    ).use { c -> buildList { while (c.moveToNext()) add(ChatMessage(c.getString(0), c.getString(1), c.getString(2), c.getInt(3) == 1, c.getLong(4), MessageState.valueOf(c.getString(5)))) } }

    fun pending(): List<ChatMessage> = readableDatabase.rawQuery(
        "SELECT id,contact_id,body,outgoing,created_at,state FROM messages WHERE outgoing=1 AND state='PENDING' ORDER BY created_at", null
    ).use { c -> buildList { while(c.moveToNext()) add(ChatMessage(c.getString(0), c.getString(1), c.getString(2), true, c.getLong(4), MessageState.PENDING)) } }
}
