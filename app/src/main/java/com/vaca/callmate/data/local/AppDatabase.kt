package com.vaca.callmate.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        CallLogEntity::class,
        TranscriptLineEntity::class,
        CallFeedbackEntity::class,
        OutboundPromptTemplateEntity::class,
        OutboundContactBookEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun callLogDao(): CallLogDao
    abstract fun transcriptLineDao(): TranscriptLineDao
    abstract fun callFeedbackDao(): CallFeedbackDao
    abstract fun outboundPromptTemplateDao(): OutboundPromptTemplateDao
    abstract fun outboundContactBookDao(): OutboundContactBookDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `outbound_prompt_template` (" +
                        "`id` TEXT NOT NULL, `name` TEXT NOT NULL, `content` TEXT NOT NULL, " +
                        "`createdAtMillis` INTEGER NOT NULL, `updatedAtMillis` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `outbound_contact_book_entry` (" +
                        "`id` TEXT NOT NULL, `name` TEXT NOT NULL, `phone` TEXT NOT NULL, " +
                        "`createdAtMillis` INTEGER NOT NULL, `updatedAtMillis` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                )
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `call_log` ADD COLUMN `outboundOutcome` TEXT")
            }
        }

        @Volatile
        private var INSTANCE: AppDatabase? = null
        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "callmate_db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build().also { INSTANCE = it }
            }
        }
    }
}
