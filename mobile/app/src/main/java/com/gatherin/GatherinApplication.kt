package com.gatherin

import android.app.Application
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.gatherin.data.SessionManager
import com.gatherin.worker.SyncWorker
import java.util.concurrent.TimeUnit

class GatherinApplication : Application() {
    companion object {
        lateinit var instance: GatherinApplication
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        // Restore persistent session storage before any network/auth work happens.
        SessionManager.init(this)

        // Schedule periodic sync for offline scans
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val syncRequest = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(this).enqueue(syncRequest)
    }
}
