// FCM device-token registry. Each user's push tokens live in fcm_tokens/{uid}.tokens (an array, so
// multiple devices work). The Cloud Function reads these (via Admin SDK) to push to a user's devices.
package com.usc.myway

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.messaging.FirebaseMessaging

object FcmTokens {

    private val db get() = FirebaseFirestore.getInstance()

    /** Fetch this device's token and store it under the signed-in user (idempotent — arrayUnion). */
    fun register(uid: String) {
        if (uid.isEmpty()) return
        FirebaseMessaging.getInstance().token.addOnSuccessListener { token -> save(uid, token) }
    }

    fun save(uid: String, token: String) {
        if (uid.isEmpty() || token.isEmpty()) return
        db.collection("fcm_tokens").document(uid)
            .set(mapOf("tokens" to FieldValue.arrayUnion(token)), SetOptions.merge())
    }

    /** Drop this device's token on logout so a signed-out device stops receiving pushes. */
    fun unregister(uid: String) {
        if (uid.isEmpty()) return
        FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
            db.collection("fcm_tokens").document(uid)
                .set(mapOf("tokens" to FieldValue.arrayRemove(token)), SetOptions.merge())
        }
    }
}
