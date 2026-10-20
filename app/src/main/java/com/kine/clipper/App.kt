package com.kine.clipper

import android.app.Application

/** Kine Clipper pro Android - klipy z her na telefonu, s kamerou, nahrané na Kine. */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Log.init(this)
        // Pád kdekoli se zapíše do deníku (Poslat deník), pak ho Android ukončí jako obvykle.
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try { Log.crash(thread.name, error) } catch (_: Throwable) { }
            previous?.uncaughtException(thread, error)
        }
        Notifications.createChannels(this)
    }
}
