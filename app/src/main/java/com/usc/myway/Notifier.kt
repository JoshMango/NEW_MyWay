// System notifications for app activity (group + DM messages, trip start, friend requests, group
// invites). Heads-up (top pop-up) while the app process is alive. NOTE: waking a fully-killed app
// needs Firebase Cloud Messaging + a server trigger; this covers foreground + short-background only.
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
    private const val CH_SOCIAL = "social"

    fun ensureChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CH_MESSAGES, "Messages", NotificationManager.IMPORTANCE_HIGH)
            .apply { description = "Group and direct messages" })
        nm.createNotificationChannel(NotificationChannel(CH_TRIPS, "Trips", NotificationManager.IMPORTANCE_HIGH)
            .apply { description = "Trip activity" })
        nm.createNotificationChannel(NotificationChannel(CH_SOCIAL, "Social", NotificationManager.IMPORTANCE_HIGH)
            .apply { description = "Friend requests and group invites" })
    }

    /** POST_NOTIFICATIONS is only enforced on Android 13+; older versions are always allowed. */
    fun canPost(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun message(ctx: Context, gid: String, groupName: String, fromTag: String, preview: String) =
        post(ctx, CH_MESSAGES, gid.hashCode(), groupName, "@$fromTag: $preview",
            NotificationCompat.CATEGORY_MESSAGE, groupChatIntent(ctx, gid, groupName))

    fun dm(ctx: Context, chatId: String, otherUid: String, otherTag: String, preview: String) =
        post(ctx, CH_MESSAGES, chatId.hashCode(), "@$otherTag", preview,
            NotificationCompat.CATEGORY_MESSAGE, dmIntent(ctx, chatId, otherUid, otherTag))

    fun trip(ctx: Context, gid: String, groupName: String) =
        post(ctx, CH_TRIPS, ("trip:$gid").hashCode(), "Trip started",
            "A trip just started in $groupName", NotificationCompat.CATEGORY_EVENT, groupChatIntent(ctx, gid, groupName))

    /** Scheduled-trip reminder (day-before / few-mins-before); [body] copy comes from the server. */
    fun tripScheduled(ctx: Context, gid: String, groupName: String, body: String) =
        post(ctx, CH_TRIPS, ("tripsched:$gid").hashCode(), groupName, body,
            NotificationCompat.CATEGORY_EVENT, groupChatIntent(ctx, gid, groupName))

    fun groupInvite(ctx: Context, gid: String, groupName: String) =
        post(ctx, CH_SOCIAL, ("invite:$gid").hashCode(), "Added to a group",
            "You were added to $groupName", NotificationCompat.CATEGORY_SOCIAL, groupChatIntent(ctx, gid, groupName))

    fun friendRequest(ctx: Context, fromTag: String) =
        post(ctx, CH_SOCIAL, ("req:$fromTag").hashCode(), "Friend request",
            "@$fromTag wants to be friends", NotificationCompat.CATEGORY_SOCIAL,
            Intent(ctx, FriendsActivity::class.java).apply { flags = TAP_FLAGS })

    /** Clear a chat's message notification (called when its chat is opened). Works for both group and DM. */
    fun clearMessages(ctx: Context, id: String) =
        NotificationManagerCompat.from(ctx).cancel(id.hashCode())

    private const val TAP_FLAGS = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP

    private fun groupChatIntent(ctx: Context, gid: String, groupName: String) =
        Intent(ctx, GroupChatActivity::class.java).apply {
            putExtra("gid", gid); putExtra("name", groupName); flags = TAP_FLAGS
        }

    private fun dmIntent(ctx: Context, chatId: String, otherUid: String, otherTag: String) =
        Intent(ctx, PrivateChatActivity::class.java).apply {
            putExtra("chatId", chatId); putExtra("otherUid", otherUid); putExtra("otherTag", otherTag); flags = TAP_FLAGS
        }

    private fun post(ctx: Context, channel: String, notifId: Int,
                     title: String, text: String, category: String, tap: Intent) {
        if (!canPost(ctx)) return
        val pending = PendingIntent.getActivity(
            ctx, notifId, tap,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_launcher_logo)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)   // heads-up (top pop-up) on pre-O
            .setCategory(category)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        try { NotificationManagerCompat.from(ctx).notify(notifId, n) } catch (_: SecurityException) {}
    }
}
