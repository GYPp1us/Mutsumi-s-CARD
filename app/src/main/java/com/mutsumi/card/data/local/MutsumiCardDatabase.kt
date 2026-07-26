package com.mutsumi.card.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        DeckEntity::class,
        CardEntity::class,
        ReviewStateEntity::class,
        PendingImageDeletionEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
abstract class MutsumiCardDatabase : RoomDatabase() {
    abstract fun cardDao(): CardDao

    companion object {
        private const val DATABASE_NAME = "mutsumi-card.db"
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE cards ADD COLUMN frontImagePath TEXT DEFAULT NULL")
            }
        }
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE decks ADD COLUMN syncId TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE cards ADD COLUMN syncId TEXT NOT NULL DEFAULT ''")
                database.execSQL("UPDATE decks SET syncId = lower(hex(randomblob(16))) WHERE syncId = ''")
                database.execSQL("UPDATE cards SET syncId = lower(hex(randomblob(16))) WHERE syncId = ''")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_decks_syncId ON decks(syncId)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_cards_syncId ON cards(syncId)")
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_decks_syncId_non_empty ON decks(syncId) WHERE syncId <> ''")
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_cards_syncId_non_empty ON cards(syncId) WHERE syncId <> ''")
            }
        }
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("DROP INDEX IF EXISTS index_decks_syncId")
                database.execSQL("DROP INDEX IF EXISTS index_cards_syncId")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_decks_syncId ON decks(syncId)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_cards_syncId ON cards(syncId)")
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_decks_syncId_non_empty ON decks(syncId) WHERE syncId <> ''")
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_cards_syncId_non_empty ON cards(syncId) WHERE syncId <> ''")
            }
        }

        private val SYNC_ID_INDEX_CALLBACK = object : RoomDatabase.Callback() {
            override fun onCreate(database: SupportSQLiteDatabase) {
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_decks_syncId_non_empty ON decks(syncId) WHERE syncId <> ''")
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_cards_syncId_non_empty ON cards(syncId) WHERE syncId <> ''")
            }
        }

        fun build(context: Context): MutsumiCardDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                MutsumiCardDatabase::class.java,
                DATABASE_NAME,
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .addCallback(SYNC_ID_INDEX_CALLBACK)
                .build()

        fun inMemory(context: Context): MutsumiCardDatabase =
            Room.inMemoryDatabaseBuilder(
                context.applicationContext,
                MutsumiCardDatabase::class.java,
            ).addCallback(SYNC_ID_INDEX_CALLBACK).build()
    }

}
