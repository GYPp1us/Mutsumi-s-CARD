package com.mutsumi.card.cards

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.mutsumi.card.MainActivity
import com.mutsumi.card.MutsumiCardApplication
import com.mutsumi.card.data.LibraryAction
import com.mutsumi.card.data.LibrarySelection
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream

class LibraryStudySelectionTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val container get()=ApplicationProvider.getApplicationContext<MutsumiCardApplication>().appContainer
    private val created=mutableSetOf<Long>()
    private var previous:Long?=null

    @After fun cleanup() { runBlocking {
        if(created.isNotEmpty()) container.cardRepository.manage(LibrarySelection(deckIds=created),LibraryAction.Delete)
        previous?.let { container.appPreferences.setCurrentDeckId(it) }
    } }

    @Test fun 管理页切换卡组后学习页跟随当前卡组() {
        compose.waitUntil(12000) { compose.onAllNodesWithTag("app-initialized").fetchSemanticsNodes().isNotEmpty() }
        val second=runBlocking {
            previous=container.appPreferences.currentDeckId.first()
            val first=container.cardRepository.createDeck("验收卡组甲").also(created::add)
            val second=container.cardRepository.createDeck("验收卡组乙").also(created::add)
            val bitmap=Bitmap.createBitmap(1024,1624,Bitmap.Config.ARGB_8888)
            val png=ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }.toByteArray()
            bitmap.recycle()
            container.cardRepository.saveCard(first,"卡组甲的卡片",png)
            container.cardRepository.saveCard(second,"卡组乙的卡片",png)
            container.appPreferences.setCurrentDeckId(first)
            second
        }
        compose.onNodeWithTag("nav-cards").performClick()
        compose.onNodeWithContentDescription("切换卡组").performClick()
        compose.onNodeWithText("验收卡组乙").performScrollTo().performClick()
        compose.waitUntil(5000) { runBlocking { container.appPreferences.currentDeckId.first()==second } }
        compose.onNodeWithTag("nav-study").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithContentDescription("图片 value：卡组乙的卡片").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("图片 value：卡组乙的卡片").assertExists()
        compose.onAllNodesWithContentDescription("图片 value：卡组甲的卡片").assertCountEquals(0)
    }
}
