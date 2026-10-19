package com.kine.clipper

import android.app.Application

/** Kine Clipper pro Android - klipy z her na telefonu, s kamerou, nahrané na Kine. */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
        Log.init(this)
    }
}
