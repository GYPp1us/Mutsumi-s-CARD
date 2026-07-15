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
    version = 2,
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

        fun build(context: Context): MutsumiCardDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                MutsumiCardDatabase::class.java,
                DATABASE_NAME,
            ).addMigrations(MIGRATION_1_2).build()

        fun inMemory(context: Context): MutsumiCardDatabase =
            Room.inMemoryDatabaseBuilder(
                context.applicationContext,
                MutsumiCardDatabase::class.java,
            ).build()
    }

}
