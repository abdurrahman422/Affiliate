package com.ownerlink.agent.affiliate

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
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Entity(tableName = "affiliate_tasks", indices = [Index("status"), Index("categoryId"), Index("priority")])
data class AffiliateTaskEntity(
    @PrimaryKey val taskId: String,
    val categoryId: String,
    val title: String,
    val description: String,
    val instructions: String,
    val reward: String,
    val status: String,
    val priority: Int,
    val deadline: String?,
    val schemaJson: String,
    val revision: Int,
    val taskType: String = "FORM",
    val actionUrl: String? = null,
    val actionDurationSeconds: Int = 0,
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "affiliate_submissions", indices = [Index("taskId"), Index("status"), Index(value = ["clientSubmissionId"], unique = true)])
data class AffiliateSubmissionEntity(
    @PrimaryKey val submissionId: String,
    val clientSubmissionId: String,
    val taskId: String,
    val taskTitle: String,
    val reward: String,
    val status: String,
    val fieldsJson: String,
    val reviewReason: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(tableName = "affiliate_ledger", indices = [Index("createdAt")])
data class AffiliateLedgerEntity(
    @PrimaryKey val ledgerId: String,
    val taskId: String?,
    val amount: String,
    val reason: String,
    val balanceAfter: String,
    val createdAt: String,
)

@Entity(tableName = "affiliate_sync")
data class AffiliateSyncEntity(@PrimaryKey val key: String = "cursor", val value: String)

@Dao
interface AffiliateDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun saveTasks(values: List<AffiliateTaskEntity>)
    @Query("SELECT * FROM affiliate_tasks ORDER BY priority DESC, updatedAt DESC") fun tasks(): List<AffiliateTaskEntity>
    @Query("SELECT * FROM affiliate_tasks WHERE taskId=:id LIMIT 1") fun task(id: String): AffiliateTaskEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun saveSubmission(value: AffiliateSubmissionEntity)
    @Query("SELECT * FROM affiliate_submissions ORDER BY updatedAt DESC") fun submissions(): List<AffiliateSubmissionEntity>
    @Query("SELECT * FROM affiliate_submissions WHERE status IN ('QUEUED','SENDING') ORDER BY createdAt") fun pending(): List<AffiliateSubmissionEntity>
    @Query("SELECT * FROM affiliate_submissions WHERE clientSubmissionId=:id LIMIT 1") fun byClientId(id: String): AffiliateSubmissionEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun saveLedger(values: List<AffiliateLedgerEntity>)
    @Query("SELECT * FROM affiliate_ledger ORDER BY createdAt DESC") fun ledger(): List<AffiliateLedgerEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun saveSync(value: AffiliateSyncEntity)
    @Query("SELECT value FROM affiliate_sync WHERE key='cursor' LIMIT 1") fun cursor(): String?
    @Query("DELETE FROM affiliate_tasks") fun clearTasks()
    @Query("DELETE FROM affiliate_ledger") fun clearLedger()
}

@Database(entities = [AffiliateTaskEntity::class, AffiliateSubmissionEntity::class, AffiliateLedgerEntity::class, AffiliateSyncEntity::class], version = 2, exportSchema = true)
abstract class AffiliateDatabase : RoomDatabase() { abstract fun dao(): AffiliateDao }

class AffiliateStore(context: Context) {
    val dao: AffiliateDao = Room.databaseBuilder(context.applicationContext, AffiliateDatabase::class.java, "affiliate-room.db")
        .addMigrations(MIGRATION_1_2).build().dao()

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE affiliate_tasks ADD COLUMN taskType TEXT NOT NULL DEFAULT 'FORM'")
                db.execSQL("ALTER TABLE affiliate_tasks ADD COLUMN actionUrl TEXT")
                db.execSQL("ALTER TABLE affiliate_tasks ADD COLUMN actionDurationSeconds INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}
