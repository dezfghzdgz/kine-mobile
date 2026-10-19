package com.kine.clipper

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

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

    /** Trvalé oznámení služby: stav + tlačítka Klip a Stop. */
    fun capture(context: Context, text: String, cameraOn: Boolean): Notification {
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), flags())
        val clip = PendingIntent.getService(context, 1, Intent(context, CaptureService::class.java).setAction(CaptureService.ACTION_CLIP), flags())
        val stop = PendingIntent.getService(context, 2, Intent(context, CaptureService::class.java).setAction(CaptureService.ACTION_STOP), flags())
        return Notification.Builder(context, CH_CAPTURE)
            .setSmallIcon(R.drawable.ic_stat_kine)
            .setContentTitle(context.getString(R.string.app_name) + if (cameraOn) " · camera" else "")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "Clip", clip).build())
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    fun clipSaved(context: Context, title: String): Notification {
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), flags())
        return Notification.Builder(context, CH_CLIPS)
            .setSmallIcon(R.drawable.ic_stat_kine)
            .setContentTitle("Clip saved")
            .setContentText(title)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setTimeoutAfter(6000)
            .build()
    }

    fun upload(context: Context, text: String, percent: Int): Notification {
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), flags())
        return Notification.Builder(context, CH_UPLOAD)
            .setSmallIcon(R.drawable.ic_stat_kine)
            .setContentTitle("Uploading to Kine")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, percent.coerceIn(0, 100), percent < 0)
            .build()
    }
}
