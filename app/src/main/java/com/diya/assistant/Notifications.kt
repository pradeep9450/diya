package com.diya.assistant

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

object Notifications {
    private const val CHANNEL_SERVICE = "diya_service"
    private const val CHANNEL_ACTIONS = "diya_actions"
    const val ID_SERVICE = 1

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SERVICE, "Diya is listening", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while Diya listens for \"Hey Diya\""
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ACTIONS, "Tap to open", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "When Diya can't open an app from the background, it asks you to tap here"
            }
        )
    }

    fun serviceNotification(ctx: Context, text: String): Notification {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            flags
        )
        val talk = PendingIntent.getService(
            ctx, 1, Intent(ctx, DiyaService::class.java).setAction(DiyaService.ACTION_LISTEN), flags
        )
        val stop = PendingIntent.getService(
            ctx, 2, Intent(ctx, DiyaService::class.java).setAction(DiyaService.ACTION_STOP), flags
        )
        return NotificationCompat.Builder(ctx, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_flame)
            .setContentTitle("Diya")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
            .addAction(0, "Talk", talk)
            .addAction(0, "Turn off", stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    @SuppressLint("MissingPermission")
    fun updateService(ctx: Context, text: String) {
        if (!canNotify(ctx)) return
        NotificationManagerCompat.from(ctx).notify(ID_SERVICE, serviceNotification(ctx, text))
    }

    /** Fallback when Android blocks opening an app from the background: one tap opens it. */
    @SuppressLint("MissingPermission")
    fun showTapToOpen(ctx: Context, intent: Intent, label: String) {
        if (!canNotify(ctx)) return
        val code = label.hashCode() and 0xFFFF
        val pi = PendingIntent.getActivity(
            ctx, code, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL_ACTIONS)
            .setSmallIcon(R.drawable.ic_flame)
            .setContentTitle("Tap to $label")
            .setContentText("Diya needs one tap to do this from the background")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        NotificationManagerCompat.from(ctx).notify(1000 + code, n)
    }

    private fun canNotify(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
}
