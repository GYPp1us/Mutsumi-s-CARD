package com.mutsumi.card.settings

import android.app.DownloadManager
import android.content.Context
import android.os.Build
import android.os.Environment

class AndroidUpdateDownloader(private val context: Context) {
    fun enqueue(update: AvailableUpdate): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            return "请先在系统设置中允许本应用安装更新，再重新下载"
        }
        val request = DownloadManager.Request(android.net.Uri.parse(update.apkUrl))
            .setTitle("记忆卡片 ${update.versionName}")
            .setDescription("下载完成后，请在系统通知中确认安装")
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(
                context,
                Environment.DIRECTORY_DOWNLOADS,
                "mutsumi-card-${update.versionName.removePrefix("v")}-${System.currentTimeMillis()}.apk",
            )
        val manager = context.getSystemService(DownloadManager::class.java)
            ?: error("系统下载服务不可用")
        manager.enqueue(request)
        return "已开始下载 ${update.versionName}，下载完成后请在系统通知中确认安装"
    }
}
