// System notifications for group activity (messages, trip start). Heads-up (top pop-up) while the app
// process is alive. NOTE: waking a fully-killed app needs Firebase Cloud Messaging + a server trigger;
// this covers foreground + short-background only.
package com.usc.myway

import android.Manifest
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

object Notifier {
    private const val CH_MESSAGES = "messages"
    private const val CH_TRIPS = "trips"

    fun ensureChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CH_MESSAGES, "Messages", NotificationManager.IMPORTANCE_HIGH)
            .apply { description = "Group chat messages" })
        nm.createNotificationChannel(NotificationChannel(CH_TRIPS, "Trips", NotificationManager.IMPORTANCE_HIGH)
            .apply { description = "Trip activity" })
    }

    /** POST_NOTIFICATIONS is only enforced on Android 13+; older versions are always allowed. */
    fun canPost(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun message(ctx: Context, gid: String, groupName: String, fromTag: String, preview: String) =
        post(ctx, CH_MESSAGES, gid.hashCode(), gid, groupName, groupName, "@$fromTag: $preview",
            NotificationCompat.CATEGORY_MESSAGE)

    fun trip(ctx: Context, gid: String, groupName: String) =
        post(ctx, CH_TRIPS, ("trip:$gid").hashCode(), gid, groupName, "Trip started",
            "A trip just started in $groupName", NotificationCompat.CATEGORY_EVENT)

    /** Clear a group's message notification (called when its chat is opened). */
    fun clearMessages(ctx: Context, gid: String) =
        NotificationManagerCompat.from(ctx).cancel(gid.hashCode())

    private fun post(ctx: Context, channel: String, notifId: Int, gid: String, groupName: String,
                     title: String, text: String, category: String) {
        if (!canPost(ctx)) return
        val tap = PendingIntent.getActivity(
            ctx, notifId,
            Intent(ctx, GroupChatActivity::class.java).apply {
                putExtra("gid", gid); putExtra("name", groupName)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_launcher_logo)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)   // heads-up (top pop-up) on pre-O
            .setCategory(category)
            .setAutoCancel(true)
            .setContentIntent(tap)
            .build()
        try { NotificationManagerCompat.from(ctx).notify(notifId, n) } catch (_: SecurityException) {}
    }
}
