package com.papercontrol.app

import android.app.Application
import com.papercontrol.app.data.Settings
import com.papercontrol.app.data.Store

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Settings.init(this)
        Store.init(this)
    }
}
