// Messenger-style live location sharing to group chats. One doc per user keyed by uid; it names the
// groups the share is posted to and carries the live position. Auto-expires after 1 hour (client checks
// expiresAt; Firestore TTL on expireAt is the backstop) or when the user stops it.
//   live_shares/{uid}  { uid, tag, photo, groups:[gid], lat, lng, updatedAt, expireAt }
package com.usc.myway

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import java.util.Date

object LiveShare {

    const val DURATION_MS = 60 * 60 * 1000L   // 1 hour

    data class State(
        val uid: String, val tag: String, val photo: String, val groups: List<String>,
        val lat: Double?, val lng: Double?, val expiresAt: Long,
    ) {
        val active: Boolean get() = expiresAt > System.currentTimeMillis()
    }

    private val db get() = FirebaseFirestore.getInstance()
    private fun ref(uid: String) = db.collection("live_shares").document(uid)
    // Client-set (not serverTimestamp) so expiresAt is readable immediately for countdown/expiry checks.
    private fun expiry() = Timestamp(Date(System.currentTimeMillis() + DURATION_MS))

    /** Start/replace the share: set the target groups and reset the 1-hour window. */
    fun start(uid: String, tag: String, photo: String, groups: List<String>, lat: Double, lng: Double, onDone: (String?) -> Unit) {
        val data = hashMapOf<String, Any>(
            "uid" to uid, "tag" to tag, "photo" to photo, "groups" to groups,
            "updatedAt" to FieldValue.serverTimestamp(), "expireAt" to expiry(),
        )
        if (lat != 0.0 || lng != 0.0) { data["lat"] = lat; data["lng"] = lng }
        ref(uid).set(data).addOnSuccessListener { onDone(null) }.addOnFailureListener { onDone(it.message ?: "Couldn't share location") }
    }

    /** Fire-and-forget position refresh. update() so a stopped/expired (deleted) doc stays gone. */
    fun updateLocation(uid: String, lat: Double, lng: Double) {
        ref(uid).update(mapOf("lat" to lat, "lng" to lng, "updatedAt" to FieldValue.serverTimestamp()))
    }

    fun stop(uid: String, onDone: (String?) -> Unit = {}) {
        ref(uid).delete().addOnSuccessListener { onDone(null) }.addOnFailureListener { onDone(it.message ?: "Failed") }
    }

    /** Watch a share (mine, for the banner; or someone else's, for the live viewer). */
    fun listen(uid: String, onChange: (State?) -> Unit): ListenerRegistration =
        ref(uid).addSnapshotListener { d, _ -> onChange(parse(d)) }

    private fun parse(d: DocumentSnapshot?): State? {
        if (d == null || !d.exists()) return null
        val uid = d.getString("uid") ?: return null
        @Suppress("UNCHECKED_CAST")
        val groups = (d.get("groups") as? List<String>) ?: emptyList()
        return State(uid, d.getString("tag") ?: "", d.getString("photo") ?: "", groups,
            d.getDouble("lat"), d.getDouble("lng"), d.getTimestamp("expireAt")?.toDate()?.time ?: 0L)
    }
}
