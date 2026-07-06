package com.mutsumi.card

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.mutsumi.card.backup.BackupScreen
import com.mutsumi.card.cards.CardsScreen
import com.mutsumi.card.draw.DrawScreen
import com.mutsumi.card.study.StudyScreen
import com.mutsumi.card.ui.adaptive.AdaptiveScaffold
import com.mutsumi.card.ui.navigation.AppDestination

@Composable
fun MutsumiCardApp() {
    var selected by remember { mutableStateOf(AppDestination.Study) }
    AdaptiveScaffold(
        destinations = AppDestination.entries.toList(),
        selected = selected,
        onSelect = { selected = it },
    ) {
        when (selected) {
            AppDestination.Study -> StudyScreen()
            AppDestination.Cards -> CardsScreen()
            AppDestination.Draw -> DrawScreen()
            AppDestination.Backup -> BackupScreen()
        }
    }
}

