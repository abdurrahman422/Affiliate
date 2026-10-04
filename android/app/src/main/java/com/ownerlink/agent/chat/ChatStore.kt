package com.ownerlink.agent.chat

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase

enum class MessageState { PENDING, SENDING, SENT, DELIVERED, READ, FAILED }

data class ChatMessage(
    val id: String,
    val contactId: String,
    val body: String,
    val outgoing: Boolean,
    val createdAt: Long,
    val state: MessageState,
    val kind: String = "text",
    val attachmentUri: String? = null,
    val attachmentName: String? = null,
    val attachmentMime: String? = null,
    val attachmentSize: Long? = null,
    val progress: Int = 0,
    val error: String? = null,
)

@Entity(tableName = "contacts")
data class ContactEntity(
    @PrimaryKey val contactId: String,
    val publicKey: String,
    val name: String,
    val avatarUri: String? = null,
    val addedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "messages", indices = [Index(value = ["contactId", "createdAt"]), Index(value = ["state"])])
data class MessageEntity(
    @PrimaryKey val id: String,
    val contactId: String,
    val body: String,
    val outgoing: Boolean,
    val createdAt: Long,
    val state: String,
    val kind: String,
    val attachmentUri: String?,
    val attachmentName: String?,
    val attachmentMime: String?,
    val attachmentSize: Long?,
    val progress: Int,
    val error: String?,
)

@Entity(tableName = "pending_transfers", indices = [Index("contactId")])
data class PendingTransferEntity(
    @PrimaryKey val transferId: String,
    val messageId: String,
    val contactId: String,
    val sourceUri: String?,
    val destinationPath: String?,
    val safeName: String,
    val mime: String,
    val size: Long,
    val sha256: String,
    val direction: String,
    val state: String,
    val bytesComplete: Long = 0,
)

@Dao
interface ChatDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun saveContact(value: ContactEntity)
    @Query("SELECT * FROM contacts ORDER BY name COLLATE NOCASE") fun contacts(): List<ContactEntity>
    @Query("SELECT * FROM contacts WHERE contactId=:id LIMIT 1") fun contact(id: String): ContactEntity?
    @Query("DELETE FROM contacts WHERE contactId=:id") fun removeContact(id: String)
    @Insert(onConflict = OnConflictStrategy.IGNORE) fun insertMessage(value: MessageEntity): Long
    @Query("UPDATE messages SET state=:state,error=:error WHERE id=:id") fun updateState(id: String, state: String, error: String? = null)
    @Query("SELECT state FROM messages WHERE id=:id LIMIT 1") fun messageState(id: String): String?
    @Query("UPDATE messages SET progress=:progress,state=:state WHERE id=:id") fun updateProgress(id: String, progress: Int, state: String)
    @Query("SELECT * FROM messages WHERE contactId=:id ORDER BY createdAt,id") fun messages(id: String): List<MessageEntity>
    @Query("SELECT * FROM messages WHERE id=:id LIMIT 1") fun message(id: String): MessageEntity?
    @Query("SELECT * FROM messages WHERE outgoing=1 AND state IN ('PENDING','FAILED') ORDER BY createdAt") fun pending(): List<MessageEntity>
    @Query("SELECT id FROM messages WHERE contactId=:id AND outgoing=0 AND state='DELIVERED'") fun unreadIds(id: String): List<String>
    @Query("UPDATE messages SET state='READ' WHERE contactId=:id AND outgoing=0 AND state='DELIVERED'") fun markRead(id: String)
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun saveTransfer(value: PendingTransferEntity)
    @Query("SELECT * FROM pending_transfers WHERE transferId=:id LIMIT 1") fun transfer(id: String): PendingTransferEntity?
    @Query("SELECT * FROM pending_transfers") fun transfers(): List<PendingTransferEntity>
    @Query("DELETE FROM pending_transfers WHERE transferId=:id") fun deleteTransfer(id: String)
    @Query("DELETE FROM pending_transfers WHERE messageId=:messageId") fun deleteTransferForMessage(messageId: String)
}

@Database(entities = [ContactEntity::class, MessageEntity::class, PendingTransferEntity::class], version = 1, exportSchema = true)
abstract class ChatDatabase : RoomDatabase() { abstract fun chatDao(): ChatDao }

class ChatStore(context: Context) {
    private val dao = Room.databaseBuilder(context, ChatDatabase::class.java, "privatchat-room.db")
        .allowMainThreadQueries()
        .build()
        .chatDao()

    fun saveContact(card: ContactCard) = dao.saveContact(ContactEntity(card.contactId, card.publicKey, card.name))
    fun contacts(): List<ContactCard> = dao.contacts().map { ContactCard(it.contactId, it.publicKey, it.name) }
    fun contact(contactId: String): ContactCard? = dao.contact(contactId)?.let { ContactCard(it.contactId, it.publicKey, it.name) }
    fun removeContact(contactId: String) = dao.removeContact(contactId)
    fun putMessage(message: ChatMessage): Boolean = dao.insertMessage(message.entity()) != -1L
    fun updateState(id: String, state: MessageState, error: String? = null) {
        val current = dao.messageState(id)?.let { runCatching { MessageState.valueOf(it) }.getOrNull() }
        if (current == null || MessageStateRules.canTransition(current, state)) dao.updateState(id, state.name, error)
    }
    fun updateProgress(id: String, progress: Int, state: MessageState) = dao.updateProgress(id, progress.coerceIn(0, 100), state.name)
    fun messages(contactId: String): List<ChatMessage> = dao.messages(contactId).map(MessageEntity::model)
    fun message(id: String): ChatMessage? = dao.message(id)?.model()
    fun pending(): List<ChatMessage> = dao.pending().map(MessageEntity::model)
    fun markRead(contactId: String): List<String> = dao.unreadIds(contactId).also { dao.markRead(contactId) }
    fun saveTransfer(value: PendingTransferEntity) = dao.saveTransfer(value)
    fun transfer(id: String) = dao.transfer(id)
    fun transfers() = dao.transfers()
    fun deleteTransfer(id: String) = dao.deleteTransfer(id)
    fun deleteTransferForMessage(messageId: String) = dao.deleteTransferForMessage(messageId)
}

private fun ChatMessage.entity() = MessageEntity(id, contactId, body, outgoing, createdAt, state.name, kind, attachmentUri, attachmentName, attachmentMime, attachmentSize, progress, error)
private fun MessageEntity.model() = ChatMessage(id, contactId, body, outgoing, createdAt, MessageState.valueOf(state), kind, attachmentUri, attachmentName, attachmentMime, attachmentSize, progress, error)
