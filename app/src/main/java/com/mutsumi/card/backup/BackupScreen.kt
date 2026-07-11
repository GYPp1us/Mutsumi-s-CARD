package com.mutsumi.card.backup

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.io.FileNotFoundException
import java.io.IOException

@Composable
fun BackupScreen(viewModel: BackupViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri: Uri? ->
        if (uri == null) {
            viewModel.onExportDocumentResult(null)
        } else {
            try {
                val output = context.contentResolver.openOutputStream(uri, "w")
                if (output == null) viewModel.onExportAccessFailure(IOException("无法打开导出目标"))
                else viewModel.onExportDocumentResult(output)
            } catch (error: IOException) {
                viewModel.onExportAccessFailure(error)
            } catch (error: SecurityException) {
                viewModel.onExportAccessFailure(error)
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) {
            viewModel.onImportDocumentResult(null)
        } else {
            try {
                val input = context.contentResolver.openInputStream(uri)
                if (input == null) viewModel.onImportAccessFailure(IOException("无法打开导入来源"))
                else viewModel.onImportDocumentResult(input)
            } catch (error: IOException) {
                viewModel.onImportAccessFailure(error)
            } catch (error: SecurityException) {
                viewModel.onImportAccessFailure(error)
            }
        }
    }

    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(state.message)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = { exportLauncher.launch("mutsumi-card-backup.zip") },
                enabled = !state.isBusy,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Outlined.Archive, contentDescription = null)
                Text("导出")
            }
            OutlinedButton(
                onClick = { importLauncher.launch(arrayOf("application/zip", "application/octet-stream")) },
                enabled = !state.isBusy,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Outlined.Unarchive, contentDescription = null)
                Text("导入")
            }
        }
        if (state.isBusy) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
        }
    }
}
