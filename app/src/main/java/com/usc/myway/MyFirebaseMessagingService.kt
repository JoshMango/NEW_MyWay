// Receives FCM pushes from the Cloud Function. We send DATA-only messages (not "notification" payloads)
// so this runs in every app state and we control the notification. Foreground is handled by
// NotificationHub's live Firestore listener, so here we only post when the app is backgrounded/killed.
package com.usc.myway

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class MyFirebaseMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        FirebaseAuth.getInstance().currentUser?.uid?.let { FcmTokens.save(it, token) }
    }

    override fun onMessageReceived(msg: RemoteMessage) {
        // Foreground → NotificationHub already posts (avoids a double buzz). Only handle push when away.
        if ((application as? App)?.inForeground == true) return
        val data = msg.data
        val gid = data["gid"] ?: return
        val name = data["groupName"] ?: "Group"
        when (data["type"]) {
            "message" -> Notifier.message(this, gid, name, data["fromTag"] ?: "", data["preview"] ?: "")
            "trip" -> Notifier.trip(this, gid, name)
        }
    }
}
