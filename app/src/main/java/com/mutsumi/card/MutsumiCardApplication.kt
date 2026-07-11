package com.mutsumi.card

import android.app.Application
import com.mutsumi.card.data.AppContainer

class MutsumiCardApplication : Application() {
    lateinit var appContainer: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        appContainer = AppContainer.create(this)
    }
}
