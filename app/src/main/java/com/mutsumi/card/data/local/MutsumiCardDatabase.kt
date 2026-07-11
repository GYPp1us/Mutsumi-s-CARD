package com.mutsumi.card.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [DeckEntity::class, CardEntity::class, ReviewStateEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class MutsumiCardDatabase : RoomDatabase() {
    abstract fun cardDao(): CardDao

    companion object {
        private const val DATABASE_NAME = "mutsumi-card.db"

        fun build(context: Context): MutsumiCardDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                MutsumiCardDatabase::class.java,
                DATABASE_NAME,
            ).build()

        fun inMemory(context: Context): MutsumiCardDatabase =
            Room.inMemoryDatabaseBuilder(
                context.applicationContext,
                MutsumiCardDatabase::class.java,
            ).build()
    }
}
