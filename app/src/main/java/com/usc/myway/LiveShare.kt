// Messenger-style live location sharing to group chats and friends. One doc per user keyed by uid;
// it names the targets and carries the live position. discovery is enabled by "visibleTo" array.
//   live_shares/{uid}  { uid, tag, photo, groups:[gid], allFriends:bool, closeFriends:bool, visibleTo:[uid], lat, lng, updatedAt, expireAt }
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
        val uid: String, val tag: String, val photo: String,
        val groups: List<String> = emptyList(),
        val allFriends: Boolean = false,
        val closeFriends: Boolean = false,
        val uids: List<String> = emptyList(),
        val visibleTo: List<String> = emptyList(),
        val lat: Double?, val lng: Double?, val expiresAt: Long,
    ) {
        val active: Boolean get() = expiresAt > System.currentTimeMillis()
    }

    private val db get() = FirebaseFirestore.getInstance()
    private fun ref(uid: String) = db.collection("live_shares").document(uid)
    private fun expiry() = Timestamp(Date(System.currentTimeMillis() + DURATION_MS))

    /** Start/replace the share with expanded targeting options. */
    fun start(
        uid: String, tag: String, photo: String,
        groups: List<String> = emptyList(),
        allFriends: Boolean = false,
        closeFriends: Boolean = false,
        uids: List<String> = emptyList(),
        visibleTo: List<String> = emptyList(),
        lat: Double, lng: Double,
        onDone: (String?) -> Unit
    ) {
        val data = hashMapOf<String, Any>(
            "uid" to uid, "tag" to tag, "photo" to photo,
            "groups" to groups, "allFriends" to allFriends, "closeFriends" to closeFriends, 
            "uids" to uids, "visibleTo" to visibleTo,
            "updatedAt" to FieldValue.serverTimestamp(), "expireAt" to expiry(),
        )
        if (lat != 0.0 || lng != 0.0) { data["lat"] = lat; data["lng"] = lng }
        ref(uid).set(data).addOnSuccessListener { onDone(null) }.addOnFailureListener { onDone(it.message ?: "Couldn't share location") }
    }

    fun updateLocation(uid: String, lat: Double, lng: Double) {
        ref(uid).update(mapOf("lat" to lat, "lng" to lng, "updatedAt" to FieldValue.serverTimestamp()))
    }

    fun stop(uid: String, onDone: (String?) -> Unit = {}) {
        ref(uid).delete().addOnSuccessListener { onDone(null) }.addOnFailureListener { onDone(it.message ?: "Failed") }
    }

    fun listen(uid: String, onChange: (State?) -> Unit): ListenerRegistration =
        ref(uid).addSnapshotListener { d, _ -> onChange(parse(d)) }

    /** Find all active live shares that are visible to [myUid]. */
    fun listenVisible(myUid: String, onChange: (List<State>) -> Unit): ListenerRegistration =
        db.collection("live_shares")
            .whereArrayContains("visibleTo", myUid)
            .addSnapshotListener { snap, _ ->
                if (snap == null) return@addSnapshotListener
                val now = System.currentTimeMillis()
                onChange(snap.documents.mapNotNull { parse(it) }.filter { it.active })
            }

    private fun parse(d: DocumentSnapshot?): State? {
        if (d == null || !d.exists()) return null
        val uid = d.getString("uid") ?: return null
        @Suppress("UNCHECKED_CAST")
        val groups = (d.get("groups") as? List<String>) ?: emptyList()
        @Suppress("UNCHECKED_CAST")
        val uids = (d.get("uids") as? List<String>) ?: emptyList()
        @Suppress("UNCHECKED_CAST")
        val visibleTo = (d.get("visibleTo") as? List<String>) ?: emptyList()
        return State(
            uid = uid, tag = d.getString("tag") ?: "", photo = d.getString("photo") ?: "",
            groups = groups,
            allFriends = d.getBoolean("allFriends") ?: false,
            closeFriends = d.getBoolean("closeFriends") ?: false,
            uids = uids, visibleTo = visibleTo,
            lat = d.getDouble("lat"), lng = d.getDouble("lng"),
            expiresAt = d.getTimestamp("expireAt")?.toDate()?.time ?: 0L
        )
    }
}
