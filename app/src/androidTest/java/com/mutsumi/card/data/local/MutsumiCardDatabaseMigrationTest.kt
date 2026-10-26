package com.mutsumi.card.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MutsumiCardDatabaseMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        MutsumiCardDatabase::class.java.canonicalName,
        FrameworkSQLiteOpenHelperFactory(),
    )

    @After
    fun 清理数据库() {
        InstrumentationRegistry.getInstrumentation().targetContext.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun 从版本2升级到5会保留数据并生成稳定ID() {
        helper.createDatabase(DATABASE_NAME, 2).apply {
            execSQL("INSERT INTO decks (id, name, createdAt, updatedAt) VALUES (7, '旧卡组', 100, 200)")
            execSQL(
                "INSERT INTO cards " +
                    "(id, deckId, keyText, valueImagePath, createdAt, updatedAt, archived, frontImagePath) " +
                    "VALUES (11, 7, '旧卡片', 'back.png', 101, 201, 0, 'front.png')",
            )
            execSQL(
                "INSERT INTO review_states " +
                    "(cardId, weight, seenCount, againCount, unsureCount, knownCount, lastReviewedAt) " +
                    "VALUES (11, 1.75, 9, 2, 3, 4, 300)",
            )
            close()
        }

        helper.runMigrationsAndValidate(
            DATABASE_NAME,
            5,
            true,
            *MutsumiCardDatabase.ALL_MIGRATIONS,
        ).use { database ->
            database.query("SELECT name, syncId FROM decks WHERE id = 7").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("旧卡组", cursor.getString(0))
                assertFalse(cursor.getString(1).isNullOrBlank())
            }
            database.query("SELECT keyText, syncId FROM cards WHERE id = 11").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("旧卡片", cursor.getString(0))
                assertFalse(cursor.getString(1).isNullOrBlank())
            }
            database.query("SELECT weight, seenCount FROM review_states WHERE cardId = 11").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1.75, cursor.getDouble(0), 0.0)
                assertEquals(9, cursor.getInt(1))
            }
            assertNull(columnDefault(database, "decks", "syncId"))
            assertNull(columnDefault(database, "cards", "syncId"))
            assertTrue(indexIsUnique(database, "decks", "index_decks_syncId"))
            assertTrue(indexIsUnique(database, "cards", "index_cards_syncId"))
        }
    }

    @Test
    fun 从版本4升级到5会清理旧部分索引并保留数据() {
        helper.createDatabase(DATABASE_NAME, 4).apply {
            execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_decks_syncId_non_empty ON decks(syncId) WHERE syncId <> ''")
            execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_cards_syncId_non_empty ON cards(syncId) WHERE syncId <> ''")
            execSQL("INSERT INTO decks (id, name, createdAt, updatedAt, syncId) VALUES (3, '现有卡组', 10, 20, 'deck-3')")
            close()
        }

        helper.runMigrationsAndValidate(
            DATABASE_NAME,
            5,
            true,
            *MutsumiCardDatabase.ALL_MIGRATIONS,
        ).use { database ->
            database.query("SELECT name, syncId FROM decks WHERE id = 3").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("现有卡组", cursor.getString(0))
                assertEquals("deck-3", cursor.getString(1))
            }
            assertFalse(indexExists(database, "decks", "index_decks_syncId_non_empty"))
            assertFalse(indexExists(database, "cards", "index_cards_syncId_non_empty"))
        }
    }

    @Test
    fun 从版本5升级到6保留全部内容并追踪每种数据库写入() {
        helper.createDatabase(DATABASE_NAME, 5).apply {
            execSQL("INSERT INTO decks (id,name,createdAt,updatedAt,syncId) VALUES (7,'数学',10,20,'d7')")
            execSQL("INSERT INTO cards (id,deckId,keyText,valueImagePath,createdAt,updatedAt,archived,frontImagePath,syncId) VALUES (11,7,'梯度','images/back.png',12,22,1,'images/front.png','c11')")
            execSQL("INSERT INTO review_states (cardId,weight,seenCount,againCount,unsureCount,knownCount,lastReviewedAt) VALUES (11,1.5,3,1,1,1,30)")
            close()
        }
        helper.runMigrationsAndValidate(DATABASE_NAME, 6, true, *MutsumiCardDatabase.ALL_MIGRATIONS).use { db ->
            db.query("SELECT parentId,archived FROM decks WHERE id=7").use { cursor ->
                assertTrue(cursor.moveToFirst()); assertTrue(cursor.isNull(0)); assertEquals(0, cursor.getInt(1))
            }
            db.query("SELECT keyText,valueImagePath,frontImagePath,archived FROM cards WHERE id=11").use { cursor ->
                assertTrue(cursor.moveToFirst()); assertEquals("梯度", cursor.getString(0)); assertEquals("images/back.png", cursor.getString(1))
                assertEquals("images/front.png", cursor.getString(2)); assertEquals(1, cursor.getInt(3))
            }
            fun revision(): Long = db.query("SELECT revision FROM library_revision WHERE id=1").use { it.moveToFirst(); it.getLong(0) }
            var previous = revision()
            listOf("UPDATE decks SET name='数学二' WHERE id=7", "UPDATE cards SET keyText='梯度二' WHERE id=11",
                "UPDATE review_states SET weight=2 WHERE cardId=11").forEach { sql ->
                db.execSQL(sql); assertTrue(revision() > previous); previous = revision()
            }
        }
    }

    @Test
    fun 从版本1直接升级到6保留旧卡片与复习状态() {
        helper.createDatabase(DATABASE_NAME, 1).apply {
            execSQL("INSERT INTO decks (id,name,createdAt,updatedAt) VALUES (1,'旧卡组',10,20)")
            execSQL("INSERT INTO cards (id,deckId,keyText,valueImagePath,createdAt,updatedAt,archived) VALUES (2,1,'旧卡片','images/old.png',11,21,0)")
            execSQL("INSERT INTO review_states (cardId,weight,seenCount,againCount,unsureCount,knownCount,lastReviewedAt) VALUES (2,1.5,3,1,1,1,30)")
            close()
        }
        helper.runMigrationsAndValidate(DATABASE_NAME, 6, true, *MutsumiCardDatabase.ALL_MIGRATIONS).use { db ->
            db.query("SELECT keyText,syncId FROM cards WHERE id=2").use { cursor ->
                assertTrue(cursor.moveToFirst()); assertEquals("旧卡片", cursor.getString(0)); assertFalse(cursor.getString(1).isBlank())
            }
            db.query("SELECT seenCount,weight FROM review_states WHERE cardId=2").use { cursor ->
                assertTrue(cursor.moveToFirst()); assertEquals(3, cursor.getInt(0)); assertEquals(1.5, cursor.getDouble(1), 0.0)
            }
        }
    }

    private fun columnDefault(
        database: androidx.sqlite.db.SupportSQLiteDatabase,
        table: String,
        column: String,
    ): String? = database.query("PRAGMA table_info(`$table`)").use { cursor ->
        val nameIndex = cursor.getColumnIndexOrThrow("name")
        val defaultIndex = cursor.getColumnIndexOrThrow("dflt_value")
        while (cursor.moveToNext()) {
            if (cursor.getString(nameIndex) == column) {
                return@use if (cursor.isNull(defaultIndex)) null else cursor.getString(defaultIndex)
            }
        }
        error("缺少字段：$table.$column")
    }

    private fun indexExists(
        database: androidx.sqlite.db.SupportSQLiteDatabase,
        table: String,
        index: String,
    ): Boolean = database.query("PRAGMA index_list(`$table`)").use { cursor ->
        val nameIndex = cursor.getColumnIndexOrThrow("name")
        while (cursor.moveToNext()) {
            if (cursor.getString(nameIndex) == index) return@use true
        }
        false
    }

    private fun indexIsUnique(
        database: androidx.sqlite.db.SupportSQLiteDatabase,
        table: String,
        index: String,
    ): Boolean = database.query("PRAGMA index_list(`$table`)").use { cursor ->
        val nameIndex = cursor.getColumnIndexOrThrow("name")
        val uniqueIndex = cursor.getColumnIndexOrThrow("unique")
        while (cursor.moveToNext()) {
            if (cursor.getString(nameIndex) == index) return@use cursor.getInt(uniqueIndex) == 1
        }
        false
    }

    private companion object {
        const val DATABASE_NAME = "migration-test.db"
    }
}
