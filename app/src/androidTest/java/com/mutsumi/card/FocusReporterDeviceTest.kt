package com.mutsumi.card

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import com.mutsumi.card.focus.AndroidFocusSettingsStorage
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class FocusReporterDeviceTest {
    @Test fun 密钥仅在不可备份目录以密文保存() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val key = "1." + "a".repeat(64)
        val store = AndroidFocusSettingsStorage(context)
        val previousKey = store.loadKey()
        try {
            store.saveKey(key)
            assertEquals(key, store.loadKey())
            val file = File(context.noBackupFilesDir, "focus-reporter.credential")
            assertTrue(file.exists())
            assertFalse(file.readBytes().decodeToString().contains(key))
        } finally {
            if (previousKey == null) store.clearKey() else store.saveKey(previousKey)
        }
    }
}
