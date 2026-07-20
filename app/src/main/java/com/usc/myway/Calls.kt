// Voice/video call signalling on Firestore + LiveKit token fetch. Backend (livekitToken callable,
// calls/group_calls collections, rules, LiveKit Cloud) is shared with iOS — this is the Android client
// half of the contract. Callback-based to match the rest of the app (no coroutines-play-services).
//   calls/{pairId}        { from, fromTag, fromPhoto, to, toTag, status:"ringing"|"active", video, startedAt }
//   group_calls/{gid}     { gid, groupName, participants:[uid], startedAt }
// Room name = pairId(a,b) for 1:1, gid for group. LiveKit identity = uid. Doc deletion = hang up.
package com.usc.myway

import android.util.Log
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.functions.FirebaseFunctions

object Calls {
    private val db get() = FirebaseFirestore.getInstance()
    private val functions get() = FirebaseFunctions.getInstance()

    fun pairId(a: String, b: String) = PrivateMessages.pairId(a, b)

    /** Human call length: "20s", "5m 12s", "1h 3m". */
    fun formatDuration(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val h = total / 3600; val m = (total % 3600) / 60; val s = total % 60
        return when {
            h > 0 -> "${h}h ${m}m"
            m > 0 -> if (s > 0) "${m}m ${s}s" else "${m}m"
            else -> "${s}s"
        }
    }

    /** Fetch a LiveKit access token + server url for [room] from the shared callable. */
    fun fetchToken(room: String, onResult: (token: String?, url: String?) -> Unit) {
        functions.getHttpsCallable("livekitToken").call(hashMapOf("room" to room))
            .addOnSuccessListener { res ->
                @Suppress("UNCHECKED_CAST")
                val data = res.data as? Map<String, Any?>
                onResult(data?.get("token") as? String, data?.get("url") as? String)
            }
            .addOnFailureListener { Log.e("Calls", "livekitToken failed", it); onResult(null, null) }
    }

    // ── 1:1 ───────────────────────────────────────────────────────────────────────
    data class IncomingCall(val callId: String, val from: String, val fromTag: String,
                            val fromPhoto: String, val video: Boolean)

    fun startCall(callId: String, from: String, fromTag: String, fromPhoto: String,
                  to: String, toTag: String, video: Boolean) {
        db.collection("calls").document(callId).set(mapOf(
            "from" to from, "fromTag" to fromTag, "fromPhoto" to fromPhoto,
            "to" to to, "toTag" to toTag,
            "status" to "ringing", "video" to video,
            "startedAt" to FieldValue.serverTimestamp(),
        )).addOnFailureListener { Log.e("Calls", "startCall failed", it) }
    }

    /** Ringing calls addressed to me. Single-field-equality query — no composite index needed. */
    fun listenIncoming(uid: String, onChange: (IncomingCall?) -> Unit): ListenerRegistration =
        db.collection("calls").whereEqualTo("to", uid).whereEqualTo("status", "ringing")
            .addSnapshotListener { snap, _ ->
                val d = snap?.documents?.firstOrNull()
                onChange(d?.let {
                    IncomingCall(it.id, it.getString("from") ?: "", it.getString("fromTag") ?: "",
                        it.getString("fromPhoto") ?: "", it.getBoolean("video") ?: false)
                })
            }

    fun accept(callId: String) {
        db.collection("calls").document(callId).update("status", "active")
            .addOnFailureListener { Log.e("Calls", "accept failed", it) }
    }

    /** Watch a 1:1 call doc. status "ringing"/"active"; status == null ⇒ deleted (the other side hung up). */
    fun listenCall(callId: String, onChange: (status: String?, startedAtMs: Long?) -> Unit): ListenerRegistration =
        db.collection("calls").document(callId).addSnapshotListener { doc, _ ->
            if (doc == null || !doc.exists()) onChange(null, null)
            else onChange(doc.getString("status"), doc.getTimestamp("startedAt")?.toDate()?.time)
        }

    fun end(callId: String) {
        db.collection("calls").document(callId).delete()
            .addOnFailureListener { Log.e("Calls", "end failed", it) }
    }

    // ── Group ─────────────────────────────────────────────────────────────────────
    /** Join (or start) a group call. [onStarted] true ⇒ I created the doc (first in) → "started a call";
     *  false ⇒ I joined an existing one → "joined the call". Transaction so startedAt is set only once. */
    fun joinGroup(gid: String, groupName: String, uid: String, onStarted: (Boolean) -> Unit) {
        val ref = db.collection("group_calls").document(gid)
        db.runTransaction { txn ->
            val snap = txn.get(ref)
            val fresh = !snap.exists()
            if (fresh) txn.set(ref, mapOf(
                "gid" to gid, "groupName" to groupName,
                "participants" to listOf(uid), "startedAt" to FieldValue.serverTimestamp(),
            )) else txn.update(ref, "participants", FieldValue.arrayUnion(uid))
            fresh
        }.addOnSuccessListener { onStarted(it) }
         .addOnFailureListener { Log.e("Calls", "joinGroup failed", it); onStarted(false) }
    }

    /** Leave a group call. [onEmptied] gets the call's startedAt (ms) if I was the last one out (doc
     *  deleted) so the caller can post "Call ended · lasted …"; null otherwise. */
    fun leaveGroup(gid: String, uid: String, onEmptied: (startedAtMs: Long?) -> Unit) {
        val ref = db.collection("group_calls").document(gid)
        db.runTransaction { txn ->
            val snap = txn.get(ref)
            if (!snap.exists()) return@runTransaction null
            @Suppress("UNCHECKED_CAST")
            val remaining = ((snap.get("participants") as? List<String>) ?: emptyList()) - uid
            val startedAt = snap.getTimestamp("startedAt")?.toDate()?.time
            if (remaining.isEmpty()) { txn.delete(ref); startedAt }
            else { txn.update(ref, "participants", remaining); null }
        }.addOnSuccessListener { onEmptied(it) }
         .addOnFailureListener { Log.e("Calls", "leaveGroup failed", it); onEmptied(null) }
    }

    /** Live participant uids for a group call (empty ⇒ no call in progress). */
    fun listenGroupCall(gid: String, onChange: (List<String>) -> Unit): ListenerRegistration =
        db.collection("group_calls").document(gid).addSnapshotListener { doc, _ ->
            @Suppress("UNCHECKED_CAST")
            onChange((doc?.takeIf { it.exists() }?.get("participants") as? List<String>) ?: emptyList())
        }
}
