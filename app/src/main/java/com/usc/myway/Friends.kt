// Social layer on Firestore: find users by @tag, send/accept friend requests, list friends.
// Model (no cross-user writes, so it fits the "write only your own" rules):
//   friendRequests/{from_to}  { from, fromTag, to, toTag, createdAt }
//   friendships/{sortedPair}  { users: [a,b], tagByUid: {a:tagA, b:tagB}, closeByUid: {a:bool, b:bool} }
// Callback-based (no coroutines-play-services dependency).
package com.usc.myway

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.QuerySnapshot
import com.google.firebase.firestore.SetOptions

data class UserHit(
    val uid: String,
    val tag: String,
    val firstName: String = "",
    val lastName: String = "",
    val photo: String = "",
    val isClose: Boolean = false,
) {
    val fullName: String get() = "$firstName $lastName".trim()
}

data class FriendRequest(
    val id: String,
    val fromUid: String,
    val fromTag: String,
    val toUid: String,
    val toTag: String,
)

object Friends {

    private val db get() = FirebaseFirestore.getInstance()

    private fun pairId(a: String, b: String) = listOf(a, b).sorted().joinToString("_")

    /** Prefix search on tagLower (case-insensitive), excluding yourself. */
    fun search(rawQuery: String, myUid: String, onResult: (List<UserHit>) -> Unit) {
        val q = Profiles.normalize(rawQuery)
        if (q.isEmpty()) { onResult(emptyList()); return }
        db.collection("users").orderBy("tagLower").startAt(q).endAt(q + "").limit(25).get()
            .addOnSuccessListener { snap ->
                onResult(snap.documents.mapNotNull { d ->
                    val tag = d.getString("tag") ?: return@mapNotNull null
                    if (d.id == myUid) null else UserHit(
                        uid = d.id, tag = tag,
                        firstName = d.getString("firstName") ?: "",
                        lastName = d.getString("lastName") ?: "",
                        photo = d.getString("photo") ?: "",
                    )
                })
            }
            .addOnFailureListener { onResult(emptyList()) }
    }

    fun sendRequest(myUid: String, myTag: String, target: UserHit, onDone: (String?) -> Unit) {
        db.collection("friendRequests").document("${myUid}_${target.uid}").set(
            mapOf(
                "from" to myUid, "fromTag" to myTag,
                "to" to target.uid, "toTag" to target.tag,
                "createdAt" to FieldValue.serverTimestamp(),
            )
        ).addOnSuccessListener { onDone(null) }.addOnFailureListener { onDone(it.message ?: "Could not send request") }
    }

    /** Live incoming requests (to me). Returns a registration the caller must remove(). */
    fun listenIncoming(myUid: String, onChange: (List<FriendRequest>) -> Unit): ListenerRegistration =
        db.collection("friendRequests").whereEqualTo("to", myUid)
            .addSnapshotListener { snap, _ -> if (snap != null) onChange(mapRequests(snap)) }

    /** Live outgoing requests (sent by me). */
    fun listenOutgoing(myUid: String, onChange: (List<FriendRequest>) -> Unit): ListenerRegistration =
        db.collection("friendRequests").whereEqualTo("from", myUid)
            .addSnapshotListener { snap, _ -> if (snap != null) onChange(mapRequests(snap)) }

    private fun mapRequests(snap: QuerySnapshot): List<FriendRequest> = snap.documents.mapNotNull { d ->
        FriendRequest(
            id = d.id,
            fromUid = d.getString("from") ?: return@mapNotNull null,
            fromTag = d.getString("fromTag") ?: "",
            toUid = d.getString("to") ?: return@mapNotNull null,
            toTag = d.getString("toTag") ?: "",
        )
    }

    /** Recipient accepts: create the friendship, then remove the request. */
    fun accept(req: FriendRequest, onDone: (String?) -> Unit) {
        db.collection("friendships").document(pairId(req.fromUid, req.toUid)).set(
            mapOf(
                "users" to listOf(req.fromUid, req.toUid),
                "tagByUid" to mapOf(req.fromUid to req.fromTag, req.toUid to req.toTag),
                "closeByUid" to mapOf(req.fromUid to false, req.toUid to false),
            )
        ).addOnSuccessListener {
            db.collection("friendRequests").document(req.id).delete()
                .addOnSuccessListener { onDone(null) }.addOnFailureListener { onDone(null) } // friendship made; stale request is harmless
        }.addOnFailureListener { onDone(it.message ?: "Could not accept") }
    }

    /** Decline (recipient) or cancel (sender) — both just delete the request. */
    fun deleteRequest(req: FriendRequest, onDone: (String?) -> Unit) {
        db.collection("friendRequests").document(req.id).delete()
            .addOnSuccessListener { onDone(null) }.addOnFailureListener { onDone(it.message ?: "Failed") }
    }

    /** Live friends list. */
    fun listenFriends(myUid: String, onChange: (List<UserHit>) -> Unit): ListenerRegistration =
        db.collection("friendships").whereArrayContains("users", myUid)
            .addSnapshotListener { snap, _ ->
                if (snap == null) return@addSnapshotListener
                onChange(snap.documents.mapNotNull { d ->
                    val users = d.get("users") as? List<*> ?: return@mapNotNull null
                    val other = users.firstOrNull { it != myUid } as? String ?: return@mapNotNull null
                    @Suppress("UNCHECKED_CAST")
                    val tags = d.get("tagByUid") as? Map<String, String>
                    @Suppress("UNCHECKED_CAST")
                    val close = d.get("closeByUid") as? Map<String, Boolean>
                    UserHit(other, tags?.get(other) ?: "friend", isClose = close?.get(myUid) ?: false)
                })
            }

    fun removeFriend(myUid: String, otherUid: String, onDone: (String?) -> Unit) {
        db.collection("friendships").document(pairId(myUid, otherUid)).delete()
            .addOnSuccessListener { onDone(null) }.addOnFailureListener { onDone(it.message ?: "Failed") }
    }

    /** Mark/unmark a friend as a "Close Friend" (private to you). */
    fun setCloseFriend(myUid: String, otherUid: String, isClose: Boolean, onDone: (String?) -> Unit) {
        db.collection("friendships").document(pairId(myUid, otherUid))
            .set(mapOf("closeByUid" to mapOf(myUid to isClose)), SetOptions.merge())
            .addOnSuccessListener { onDone(null) }
            .addOnFailureListener { onDone(it.message ?: "Failed to update") }
    }

    /** One-shot fetch of all friend UIDs. */
    fun fetchFriendUids(myUid: String, onResult: (List<String>) -> Unit) {
        db.collection("friendships").whereArrayContains("users", myUid).get()
            .addOnSuccessListener { snap ->
                onResult(snap.documents.mapNotNull { d ->
                    (d.get("users") as? List<*>)?.firstOrNull { it != myUid } as? String
                })
            }
            .addOnFailureListener { onResult(emptyList()) }
    }

    /** One-shot fetch of all Close Friend UIDs. */
    fun fetchCloseFriendUids(myUid: String, onResult: (List<String>) -> Unit) {
        db.collection("friendships").whereArrayContains("users", myUid).get()
            .addOnSuccessListener { snap ->
                onResult(snap.documents.mapNotNull { d ->
                    val users = d.get("users") as? List<*> ?: return@mapNotNull null
                    val other = users.firstOrNull { it != myUid } as? String ?: return@mapNotNull null
                    @Suppress("UNCHECKED_CAST")
                    val close = d.get("closeByUid") as? Map<String, Boolean>
                    if (close?.get(myUid) == true) other else null
                })
            }
            .addOnFailureListener { onResult(emptyList()) }
    }
}
