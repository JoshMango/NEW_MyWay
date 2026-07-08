// Group Trips: a live, Discord-voice-style session tied to a group. Real-time location sharing +
// shared pins. Single-trip-at-a-time is structural: ONE participant doc per user, keyed by uid, so
// joining another group's trip overwrites it (auto-leaving the previous one).
//   trip_participants/{uid}       { uid, gid, tag, photo, lat, lng, updatedAt }
//   groups/{gid}/trip_pins/{id}   { from, fromTag, lat, lng, name, note, createdAt }
// Callback-based (no coroutines-play-services dependency).
package com.usc.myway

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import java.util.Date

object Trip {

    data class Member(val uid: String, val tag: String, val photo: String, val lat: Double?, val lng: Double?)
    data class TripPin(val id: String, val from: String, val fromTag: String, val lat: Double, val lng: Double, val name: String, val note: String)

    // A member is considered gone if their heartbeat is older than this (crash/no-cleanup guard).
    // The foreground service heartbeats every ~20s, so 60s is a safe 3× margin.
    private const val STALE_MS = 60_000L
    private const val TTL_MS = 90_000L

    private val db get() = FirebaseFirestore.getInstance()
    private fun meRef(uid: String) = db.collection("trip_participants").document(uid)

    private fun expiry() = Timestamp(Date(System.currentTimeMillis() + TTL_MS))

    /** Fresh if we've heard from them recently. A just-joined doc (no server timestamp yet) counts as fresh. */
    private fun fresh(d: DocumentSnapshot): Boolean {
        val u = d.getTimestamp("updatedAt") ?: return true
        return System.currentTimeMillis() - u.toDate().time < STALE_MS
    }

    /** Join [gid]'s trip. Overwrites any existing participation → you leave your previous trip. */
    fun join(uid: String, gid: String, tag: String, photo: String, lat: Double, lng: Double, onDone: (String?) -> Unit) {
        val data = hashMapOf<String, Any>(
            "uid" to uid, "gid" to gid, "tag" to tag, "photo" to photo,
            "updatedAt" to FieldValue.serverTimestamp(), "expireAt" to expiry(),
        )
        if (lat != 0.0 || lng != 0.0) { data["lat"] = lat; data["lng"] = lng }
        meRef(uid).set(data).addOnSuccessListener { onDone(null) }.addOnFailureListener { onDone(it.message ?: "Could not join") }
    }

    fun leave(uid: String, onDone: (String?) -> Unit) {
        meRef(uid).delete().addOnSuccessListener { onDone(null) }.addOnFailureListener { onDone(it.message ?: "Failed") }
    }

    /**
     * Fire-and-forget live-location update + heartbeat. update() (not set) so a left/deleted doc
     * stays gone. Refreshes updatedAt (liveness) and expireAt (Firestore TTL cleanup) every write.
     */
    fun updateLocation(uid: String, lat: Double, lng: Double) {
        meRef(uid).update(
            mapOf("lat" to lat, "lng" to lng, "updatedAt" to FieldValue.serverTimestamp(), "expireAt" to expiry())
        )
    }

    /** My current trip's group id, or null when I'm not in one. */
    fun listenMyTrip(uid: String, onChange: (String?) -> Unit): ListenerRegistration =
        meRef(uid).addSnapshotListener { d, _ -> onChange(if (d != null && d.exists()) d.getString("gid") else null) }

    /** Everyone currently live in [gid]. */
    fun listenMembers(gid: String, onChange: (List<Member>) -> Unit): ListenerRegistration =
        db.collection("trip_participants").whereEqualTo("gid", gid)
            .addSnapshotListener { snap, _ ->
                if (snap != null) onChange(snap.documents.mapNotNull { d ->
                    if (!fresh(d)) return@mapNotNull null   // hide ghosts (crashed without leaving)
                    val uid = d.getString("uid") ?: return@mapNotNull null
                    Member(uid, d.getString("tag") ?: "", d.getString("photo") ?: "",
                        d.getDouble("lat"), d.getDouble("lng"))
                })
            }

    /** Start an ongoing session on the group (marks it LIVE / joinable). Join separately to go live. */
    fun startSession(gid: String, onDone: (String?) -> Unit) {
        db.collection("groups").document(gid).update("tripActive", true)
            .addOnSuccessListener { onDone(null) }.addOnFailureListener { onDone(it.message ?: "Could not start trip") }
    }

    /**
     * End the session (any group member may). Clears the session: removes all participants (everyone
     * goes offline) and all shared pins, then marks the group not-in-trip. This is the ONLY thing that
     * clears session pins — leaving does not.
     */
    fun endSession(gid: String, onDone: (String?) -> Unit) {
        val groupRef = db.collection("groups").document(gid)
        val pinsCol = groupRef.collection("trip_pins")
        val partsQ = db.collection("trip_participants").whereEqualTo("gid", gid)
        pinsCol.get().addOnSuccessListener { pinSnap ->
            partsQ.get().addOnSuccessListener { partSnap ->
                val batch = db.batch()
                pinSnap.documents.forEach { batch.delete(it.reference) }
                partSnap.documents.forEach { batch.delete(it.reference) }
                batch.update(groupRef, "tripActive", false)
                batch.commit().addOnSuccessListener { onDone(null) }.addOnFailureListener { onDone(it.message ?: "Failed") }
            }.addOnFailureListener { onDone(it.message ?: "Failed") }
        }.addOnFailureListener { onDone(it.message ?: "Failed") }
    }

    // ── Shared pins ───────────────────────────────────────────────────────────────
    fun sharePin(gid: String, fromUid: String, fromTag: String, lat: Double, lng: Double, name: String, note: String) {
        db.collection("groups").document(gid).collection("trip_pins").document().set(
            mapOf("from" to fromUid, "fromTag" to fromTag, "lat" to lat, "lng" to lng,
                "name" to name, "note" to note, "createdAt" to FieldValue.serverTimestamp())
        )
    }

    fun listenPins(gid: String, onChange: (List<TripPin>) -> Unit): ListenerRegistration =
        db.collection("groups").document(gid).collection("trip_pins")
            .addSnapshotListener { snap, _ ->
                if (snap != null) onChange(snap.documents.mapNotNull { d ->
                    val lat = d.getDouble("lat") ?: return@mapNotNull null
                    val lng = d.getDouble("lng") ?: return@mapNotNull null
                    TripPin(d.id, d.getString("from") ?: "", d.getString("fromTag") ?: "", lat, lng,
                        d.getString("name") ?: "", d.getString("note") ?: "")
                })
            }

    /** Any member may edit a session pin's name/note (creator attribution is preserved). */
    fun updatePin(gid: String, pinId: String, name: String, note: String, onDone: (String?) -> Unit) {
        db.collection("groups").document(gid).collection("trip_pins").document(pinId)
            .update("name", name, "note", note)
            .addOnSuccessListener { onDone(null) }.addOnFailureListener { onDone(it.message ?: "Could not edit") }
    }

    /** Any member may delete a session pin. */
    fun deletePin(gid: String, pinId: String, onDone: (String?) -> Unit) {
        db.collection("groups").document(gid).collection("trip_pins").document(pinId).delete()
            .addOnSuccessListener { onDone(null) }.addOnFailureListener { onDone(it.message ?: "Could not delete") }
    }
}
