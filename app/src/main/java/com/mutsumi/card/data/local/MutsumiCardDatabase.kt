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
    version = 5,
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
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE `_migration_cards_v5` AS
                    SELECT `id`, `deckId`, `keyText`, `valueImagePath`, `createdAt`, `updatedAt`,
                           `archived`, `frontImagePath`,
                           CASE WHEN `syncId` = '' THEN lower(hex(randomblob(16))) ELSE `syncId` END AS `syncId`
                    FROM `cards`
                    """.trimIndent(),
                )
                database.execSQL(
                    """
                    CREATE TABLE `_migration_reviews_v5` AS
                    SELECT `cardId`, `weight`, `seenCount`, `againCount`, `unsureCount`, `knownCount`, `lastReviewedAt`
                    FROM `review_states`
                    """.trimIndent(),
                )
                database.execSQL(
                    """
                    CREATE TABLE `_migration_decks_v5` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        `syncId` TEXT NOT NULL
                    )
                    """.trimIndent(),
                )
                database.execSQL(
                    """
                    INSERT INTO `_migration_decks_v5` (`id`, `name`, `createdAt`, `updatedAt`, `syncId`)
                    SELECT `id`, `name`, `createdAt`, `updatedAt`,
                           CASE WHEN `syncId` = '' THEN lower(hex(randomblob(16))) ELSE `syncId` END
                    FROM `decks`
                    """.trimIndent(),
                )

                database.execSQL("DROP TABLE `review_states`")
                database.execSQL("DROP TABLE `cards`")
                database.execSQL("DROP TABLE `decks`")
                database.execSQL("ALTER TABLE `_migration_decks_v5` RENAME TO `decks`")

                database.execSQL(
                    """
                    CREATE TABLE `cards` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `deckId` INTEGER NOT NULL,
                        `keyText` TEXT NOT NULL,
                        `valueImagePath` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        `archived` INTEGER NOT NULL,
                        `frontImagePath` TEXT,
                        `syncId` TEXT NOT NULL,
                        FOREIGN KEY(`deckId`) REFERENCES `decks`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                database.execSQL(
                    """
                    INSERT INTO `cards` (`id`, `deckId`, `keyText`, `valueImagePath`, `createdAt`, `updatedAt`,
                                         `archived`, `frontImagePath`, `syncId`)
                    SELECT `id`, `deckId`, `keyText`, `valueImagePath`, `createdAt`, `updatedAt`,
                           `archived`, `frontImagePath`, `syncId`
                    FROM `_migration_cards_v5`
                    """.trimIndent(),
                )
                database.execSQL(
                    """
                    CREATE TABLE `review_states` (
                        `cardId` INTEGER NOT NULL,
                        `weight` REAL NOT NULL,
                        `seenCount` INTEGER NOT NULL,
                        `againCount` INTEGER NOT NULL,
                        `unsureCount` INTEGER NOT NULL,
                        `knownCount` INTEGER NOT NULL,
                        `lastReviewedAt` INTEGER,
                        PRIMARY KEY(`cardId`),
                        FOREIGN KEY(`cardId`) REFERENCES `cards`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                database.execSQL(
                    """
                    INSERT INTO `review_states` (`cardId`, `weight`, `seenCount`, `againCount`, `unsureCount`,
                                                 `knownCount`, `lastReviewedAt`)
                    SELECT `cardId`, `weight`, `seenCount`, `againCount`, `unsureCount`, `knownCount`, `lastReviewedAt`
                    FROM `_migration_reviews_v5`
                    """.trimIndent(),
                )
                database.execSQL("DROP TABLE `_migration_cards_v5`")
                database.execSQL("DROP TABLE `_migration_reviews_v5`")
                database.execSQL("DROP INDEX IF EXISTS `index_decks_syncId_non_empty`")
                database.execSQL("DROP INDEX IF EXISTS `index_cards_syncId_non_empty`")
                database.execSQL("CREATE UNIQUE INDEX `index_decks_syncId` ON `decks` (`syncId`)")
                database.execSQL("CREATE INDEX `index_cards_deckId` ON `cards` (`deckId`)")
                database.execSQL("CREATE UNIQUE INDEX `index_cards_syncId` ON `cards` (`syncId`)")
            }
        }

        internal val ALL_MIGRATIONS = arrayOf(
            MIGRATION_1_2,
            MIGRATION_2_3,
            MIGRATION_3_4,
            MIGRATION_4_5,
        )

        fun build(context: Context): MutsumiCardDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                MutsumiCardDatabase::class.java,
                DATABASE_NAME,
            ).addMigrations(*ALL_MIGRATIONS)
                .build()

        fun inMemory(context: Context): MutsumiCardDatabase =
            Room.inMemoryDatabaseBuilder(
                context.applicationContext,
                MutsumiCardDatabase::class.java,
            ).build()
    }

}
