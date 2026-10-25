package com.diya.assistant

import android.app.Application

class DiyaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
    }
}
