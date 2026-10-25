package com.mutsumi.card.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.mutsumi.card.data.LibraryAction
import com.mutsumi.card.data.LibraryImpact
import com.mutsumi.card.data.LibrarySelection
import com.mutsumi.card.domain.model.*
import com.mutsumi.card.ui.adaptive.AppLayoutMode
import com.mutsumi.card.ui.theme.MutsumiCardTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class LibraryManagementUiTest {
    @get:Rule val compose = createComposeRule()
    private val decks = listOf(Deck(1,"数学",1),Deck(2,"微积分",0,1),Deck(3,"导数",0,2),Deck(4,"408",0))
    private val card = MemoryCard(11,1,"梯度","images/test.png",false,ReviewState(1.0,0,0,0,0,null))

    @Test fun 多选卡片移动先选择目标卡组并保持文件夹面包屑() {
        var state by mutableStateOf(CardsUiState(decks=decks,currentDeck=decks[0],cards=listOf(card),isLoading=false))
        var action: LibraryAction?=null; var target: Long?=null
        compose.setContent { MutsumiCardTheme { CardsScreen(state, AppLayoutMode.LandscapeThreePane,
            { _,modifier -> Box(modifier.background(Color.LightGray)) }, callbacks(
                onCard={ state=state.copy(selection=LibrarySelection(cardIds=setOf(it))) },
                onManage={ a,t -> action=a; target=t },
            )) } }
        compose.onNodeWithText("全部卡组").assertIsDisplayed()
        compose.onNodeWithText("微积分").assertIsDisplayed()
        compose.onNodeWithTag("card-select-11").performClick()
        compose.onNodeWithText("已选 1").assertIsDisplayed()
        compose.onNodeWithText("移动").performScrollTo().performClick()
        assertNull(action)
        compose.onNodeWithText("移动到卡组").assertIsDisplayed()
        compose.onNodeWithTag("library-destination-list").performScrollToNode(hasTestTag("destination-4"))
        compose.onNodeWithTag("destination-4").performClick()
        compose.onNodeWithText("确认移动").performClick()
        compose.runOnIdle { assertEquals(LibraryAction.Move,action); assertEquals(4L,target) }
    }

    @Test fun 批量删除先显示包含子级的影响确认再提交() {
        var state by mutableStateOf(CardsUiState(decks=decks,currentDeck=null,atRoot=true,isLoading=false))
        var deletes=0
        compose.setContent { MutsumiCardTheme { CardsScreen(state,AppLayoutMode.Portrait,
            { _,modifier -> Box(modifier.background(Color.LightGray)) }, callbacks(
                onDeck={ state=state.copy(selection=LibrarySelection(deckIds=setOf(it))) },
                onPreview={ state=state.copy(pendingDeleteImpact=LibraryImpact(1,3,0,1)) },
                onManage={ a,_ -> if(a==LibraryAction.Delete)deletes++ },
            )) } }
        compose.onNodeWithTag("deck-select-1").performClick()
        compose.onNodeWithText("删除",substring=false).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(0,deletes) }
        compose.onNodeWithText("将删除 3 个卡组",substring=true).assertIsDisplayed()
        compose.onNodeWithText("共 1 张卡片",substring=true).assertIsDisplayed()
        compose.onNodeWithText("确认删除").performClick()
        compose.runOnIdle { assertEquals(1,deletes) }
    }

    private fun callbacks(onCard:(Long)->Unit={},onDeck:(Long)->Unit={},onPreview:()->Unit={},onManage:(LibraryAction,Long?)->Unit={_,_->})=CardsCallbacks(
        onQueryChange={},onClearQuery={},onSelectCard={},onSwitchDeck={},onCreateDeck={},onRenameDeck={},onNewCard={},onSaveKey={},
        onRedraw={},onArchive={},onDelete={},onToggleCardSelection=onCard,onToggleDeckSelection=onDeck,onPreviewDelete=onPreview,onManage=onManage)
}
