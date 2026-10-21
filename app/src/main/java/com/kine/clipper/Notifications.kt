package com.kine.clipper

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build

/** Kanály oznámení a stavba oznámení služby (nahrává se / klip uložen / nahrávání na Kine). */
object Notifications {
    const val CH_CAPTURE = "capture"
    const val CH_UPLOAD = "upload"
    const val CH_CLIPS = "clips"
    const val ID_CAPTURE = 1
    const val ID_UPLOAD = 2
    const val ID_CLIP = 3

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CH_CAPTURE, context.getString(R.string.channel_capture), NotificationManager.IMPORTANCE_LOW).apply {
                description = context.getString(R.string.channel_capture_desc)
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_UPLOAD, context.getString(R.string.channel_upload), NotificationManager.IMPORTANCE_LOW).apply {
                description = context.getString(R.string.channel_upload_desc)
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_CLIPS, context.getString(R.string.channel_clips), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = context.getString(R.string.channel_clips_desc)
            }
        )
    }

    private fun flags(): Int = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    private fun openApp(context: Context): PendingIntent =
        PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), flags())

    /** Trvalé oznámení služby: stav + tlačítka Klip (když se nahrává) a Stop. */
    fun capture(context: Context, title: String, text: String, canClip: Boolean): Notification {
        val clip = PendingIntent.getService(context, 1, Intent(context, CaptureService::class.java).setAction(CaptureService.ACTION_CLIP), flags())
        val stop = PendingIntent.getService(context, 2, Intent(context, CaptureService::class.java).setAction(CaptureService.ACTION_STOP), flags())
        val b = Notification.Builder(context, CH_CAPTURE)
            .setSmallIcon(R.drawable.ic_stat_kine)
            .setColor(Palette.BRAND)
            .setContentTitle(title)
            .setContentText(text)
            // Delší text (upozornění na horko) celý po rozbalení, ne useknutý na jeden řádek.
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(openApp(context))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
        if (canClip) b.addAction(Notification.Action.Builder(Icon.createWithResource(context, R.drawable.ic_clip), context.getString(R.string.clip_now), clip).build())
        b.addAction(Notification.Action.Builder(Icon.createWithResource(context, R.drawable.ic_stop), context.getString(R.string.stop), stop).build())
        // Android 12+ jinak oznámení služby až 10 s zdržuje - tlačítko Klip má být hned.
        if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        return b.build()
    }

    fun clipSaved(context: Context, text: String): Notification =
        Notification.Builder(context, CH_CLIPS)
            .setSmallIcon(R.drawable.ic_stat_kine)
            .setColor(Palette.BRAND)
            .setContentTitle(context.getString(R.string.clip_saved_title))
            .setContentText(text)
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .setTimeoutAfter(6000)
            .build()

    fun upload(context: Context, text: String, percent: Int): Notification =
        Notification.Builder(context, CH_UPLOAD)
            .setSmallIcon(R.drawable.ic_stat_kine)
            .setColor(Palette.BRAND)
            .setContentTitle(context.getString(R.string.uploading_title))
            .setContentText(text)
            .setContentIntent(openApp(context))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, percent.coerceIn(0, 100), percent < 0)
            .build()
}
