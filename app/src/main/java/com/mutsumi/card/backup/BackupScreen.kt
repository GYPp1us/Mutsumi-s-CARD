package com.mutsumi.card.backup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mutsumi.card.domain.workflow.BackupActions

@Composable
fun BackupScreen(cardCount: Int) {
    var message by remember { mutableStateOf("可手动导出或导入备份包。") }

    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(message)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = { message = BackupActions.exportMessage(cardCount) }, modifier = Modifier.weight(1f)) {
                Text("导出")
            }
            OutlinedButton(onClick = { message = BackupActions.importMessage() }, modifier = Modifier.weight(1f)) {
                Text("导入")
            }
        }
    }
}

