package com.mutsumi.card.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.ui.graphics.vector.ImageVector

enum class AppDestination(
    val label: String,
    val icon: ImageVector,
) {
    Study("学习", Icons.Outlined.Layers),
    Cards("卡片", Icons.AutoMirrored.Outlined.ViewList),
    Draw("录入", Icons.Outlined.Edit),
    AiBatch("AI录入", Icons.Outlined.AutoAwesome),
    Backup("备份", Icons.Outlined.Archive),
    Settings("设置", Icons.Outlined.Settings),
}
