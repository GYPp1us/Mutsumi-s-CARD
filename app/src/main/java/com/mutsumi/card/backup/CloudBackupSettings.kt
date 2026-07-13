package com.mutsumi.card.backup

import android.content.Context

interface CloudBackupSettings {
    fun load(): CloudBackupConfig?
    fun save(config: CloudBackupConfig)
}

class PrivateCloudBackupSettings(context: Context) : CloudBackupSettings {
    private val preferences = context.applicationContext.getSharedPreferences(
        "cloud-backup",
        Context.MODE_PRIVATE,
    )

    override fun load(): CloudBackupConfig? {
        val serverUrl = preferences.getString(SERVER_URL, null) ?: return null
        val username = preferences.getString(USERNAME, null) ?: return null
        val password = preferences.getString(PASSWORD, null) ?: return null
        val remoteDirectory = preferences.getString(REMOTE_DIRECTORY, null) ?: "MutsumiCard"
        return CloudBackupConfig(serverUrl, username, password, remoteDirectory)
    }

    override fun save(config: CloudBackupConfig) {
        preferences.edit()
            .putString(SERVER_URL, config.serverUrl)
            .putString(USERNAME, config.username)
            .putString(PASSWORD, config.password)
            .putString(REMOTE_DIRECTORY, config.remoteDirectory)
            .apply()
    }

    private companion object {
        const val SERVER_URL = "server_url"
        const val USERNAME = "username"
        const val PASSWORD = "password"
        const val REMOTE_DIRECTORY = "remote_directory"
    }
}
