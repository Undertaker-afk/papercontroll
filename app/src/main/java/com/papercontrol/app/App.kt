package com.papercontrol.app

import android.app.Application
import com.papercontrol.app.data.Settings
import com.papercontrol.app.data.Store
import com.papercontrol.app.work.BackupWorker
import com.papercontrol.app.work.IndexWorker
import com.papercontrol.app.work.ReminderWorker
import com.papercontrol.app.work.SyncWorker

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Settings.init(this)
        Store.init(this)
        // The page finder; falls back to plain code if the native library cannot load.
        com.papercontrol.app.vision.Cv.ready()
        // Daily: deadline notifications and emptying the trash of documents older than 30 days.
        // Background jobs must never stop the app from starting.
        runCatching {
            ReminderWorker.schedule(this)
            BackupWorker.schedule(this)
            SyncWorker.schedule(this)
            if (Settings.current.semanticSearch) IndexWorker.start(this)
        }
    }
}
