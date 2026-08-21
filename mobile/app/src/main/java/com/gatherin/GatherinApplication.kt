package com.gatherin

import android.app.Application
import com.gatherin.data.SessionManager

class GatherinApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Restore persistent session storage before any network/auth work happens.
        SessionManager.init(this)
    }
}
