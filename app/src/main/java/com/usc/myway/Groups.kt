// Group travel circles + group chat on Firestore.
//   groups/{gid}                { name, owner, members:[uid], admins:[uid], tags:{uid:tag}, createdAt }
//   groups/{gid}/messages/{mid} { from, fromTag, text, ts }
// Members are added by @tag but only from your friends (enforced client-side). owner is always an admin.
// Callback-based (no coroutines-play-services dependency).
package com.usc.myway

import android.util.Log
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions

data class Group(
    val id: String,
    val name: String,
    val owner: String,
    val members: List<String>,
    val admins: List<String>,
    val tags: Map<String, String>,
    val photo: String = "",
    val tripActive: Boolean = false,   // an ongoing trip session (joinable, marked LIVE) — survives everyone leaving
    val tripScheduledAt: Long? = null, // a future trip: start time (millis). Set ⇒ scheduled, not yet live. Server flips it live at the time.
    val reads: Map<String, Long> = emptyMap(),  // uid → ts of the newest message they've seen (read receipts)
    val lastMsg: String = "",          // inbox preview — mirrors DMs so the unified Messages list sorts/shows groups
    val lastTs: Long = 0,              // newest message time; what the unified inbox sorts on
    val pinned: Map<String, Boolean> = emptyMap(),
    val archived: Map<String, Boolean> = emptyMap(),
    val muted: Map<String, Boolean> = emptyMap()
) {
    fun isAdmin(uid: String) = uid == owner || uid in admins
    fun tagOf(uid: String) = tags[uid] ?: "unknown"
    fun isPinned(uid: String) = pinned[uid] == true
    fun isArchived(uid: String) = archived[uid] == true
    fun isMuted(uid: String) = muted[uid] == true
}

/** A chat message: text, an image (base64 JPEG), or a shared location pin (pinLat != null). */
data class GroupMessage(
    val id: String,
    val from: String,
    val fromTag: String,
    val text: String,
    val image: String = "",
    val pinLat: Double? = null,
    val pinLng: Double? = null,
    val pinName: String = "",
    val pinNote: String = "",
    val pinPlaceId: String = "",   // set when the shared pin is a Google landmark → opens its in-app place page
    val system: Boolean = false,   // trip join/leave notices etc — rendered as a centered chip, not a bubble
    val liveFrom: String = "",     // uid of a live-location sharer → rendered as a tappable live card
    val ts: Long = 0,              // client millis; also what read receipts compare against
    val edited: Boolean = false,   // author edited the text after sending → shows an "(edited)" tag (DMs)
    val unsent: Boolean = false,   // author unsent it → tombstone bubble, content cleared (DMs)
)

object Groups {

    private val db get() = FirebaseFirestore.getInstance()

    fun createGroup(owner: String, ownerTag: String, name: String, friends: List<UserHit>, onDone: (String?) -> Unit) {
        val members = (listOf(owner) + friends.map { it.uid }).distinct()
        val tags = (mapOf(owner to ownerTag) + friends.associate { it.uid to it.tag })
        db.collection("groups").document().set(
            mapOf(
                "name" to name.trim(),
                "owner" to owner,
                "members" to members,
                "admins" to listOf(owner),
                "tags" to tags,
                "createdAt" to FieldValue.serverTimestamp(),
            )
        ).addOnSuccessListener { onDone(null) }.addOnFailureListener { onDone(it.message ?: "Could not create group") }
    }

    /** One-shot list of groups I'm in (for the share-to-group picker). */
    fun fetchMyGroups(uid: String, onResult: (List<Group>) -> Unit) {
        db.collection("groups").whereArrayContains("members", uid).get()
            .addOnSuccessListener { snap -> onResult(snap.documents.mapNotNull { mapGroup(it.id, it) }) }
            .addOnFailureListener { onResult(emptyList()) }
    }

    /** Groups I'm in. */
    fun listenMyGroups(uid: String, onChange: (List<Group>) -> Unit): ListenerRegistration =
        db.collection("groups").whereArrayContains("members", uid)
            .addSnapshotListener { snap, _ ->
                if (snap != null) onChange(snap.documents.mapNotNull { mapGroup(it.id, it) })
            }

    fun updateMetadata(gid: String, myUid: String, field: String, value: Any) {
        db.collection("groups").document(gid)
            .update("$field.$myUid", value)
            .addOnFailureListener { Log.e("Groups", "updateMetadata failed", it) }
    }

    /** One-shot group name lookup. */
    fun fetchNamePhoto(gid: String, onResult: (name: String, photo: String) -> Unit) {
        db.collection("groups").document(gid).get()
            .addOnSuccessListener { onResult(it.getString("name") ?: "Group", it.getString("photo") ?: "") }
            .addOnFailureListener { onResult("Group", "") }
    }

    /** Live single group doc — drives the roster/role UI. onChange(null) if it's gone (deleted/kicked). */
    fun listenGroup(gid: String, onChange: (Group?) -> Unit): ListenerRegistration =
        db.collection("groups").document(gid)
            .addSnapshotListener { doc, _ -> onChange(if (doc != null && doc.exists()) mapGroup(gid, doc) else null) }

    @Suppress("UNCHECKED_CAST")
    private fun mapGroup(id: String, d: com.google.firebase.firestore.DocumentSnapshot): Group? {
        val owner = d.getString("owner") ?: return null
        return Group(
            id = id,
            name = d.getString("name") ?: "Group",
            owner = owner,
            members = (d.get("members") as? List<String>) ?: emptyList(),
            admins = (d.get("admins") as? List<String>) ?: emptyList(),
            tags = (d.get("tags") as? Map<String, String>) ?: emptyMap(),
            photo = d.getString("photo") ?: "",
            tripActive = d.getBoolean("tripActive") ?: false,
            tripScheduledAt = d.getTimestamp("tripScheduledAt")?.toDate()?.time,
            reads = (d.get("reads") as? Map<String, Long>) ?: emptyMap(),
            lastMsg = d.getString("lastMsg") ?: "",
            lastTs = d.getLong("lastTs") ?: 0L,
            pinned = (d.get("pinned") as? Map<String, Boolean>) ?: emptyMap(),
            archived = (d.get("archived") as? Map<String, Boolean>) ?: emptyMap(),
            muted = (d.get("muted") as? Map<String, Boolean>) ?: emptyMap()
        )
    }

    /** Admin-only in the UI: set the group avatar (base64 JPEG inline in the group doc). */
    fun updatePhoto(gid: String, base64: String, onDone: (String?) -> Unit) {
        db.collection("groups").document(gid).update("photo", base64)
            .addOnSuccessListener { onDone(null) }.addOnFailureListener { onDone(it.message ?: "Could not set photo") }
    }

    // ── Chat ────────────────────────────────────────────────────────────────────
    fun listenMessages(gid: String, onChange: (List<GroupMessage>) -> Unit): ListenerRegistration =
        db.collection("groups").document(gid).collection("messages")
            .orderBy("ts", Query.Direction.ASCENDING)
            .addSnapshotListener { snap, _ ->
                if (snap != null) onChange(snap.documents.map {
                    GroupMessage(it.id, it.getString("from") ?: "", it.getString("fromTag") ?: "",
                        it.getString("text") ?: "", it.getString("image") ?: "",
                        it.getDouble("pinLat"), it.getDouble("pinLng"),
                        it.getString("pinName") ?: "", it.getString("pinNote") ?: "",
                        it.getString("pinPlaceId") ?: "", it.getBoolean("system") ?: false,
                        it.getString("liveFrom") ?: "", it.getLong("ts") ?: 0L)
                })
            }

    /** Read receipt: remember the newest message [uid] has seen in this group. */
    fun markRead(gid: String, uid: String, ts: Long) {
        db.collection("groups").document(gid).update("reads.$uid", ts)
    }

    /** Announce a live-location share in the chat; the card reads live_shares/{fromUid} when tapped. */
    fun postLiveShare(gid: String, fromUid: String, fromTag: String) {
        post(gid, mapOf("from" to fromUid, "fromTag" to fromTag, "text" to "", "liveFrom" to fromUid))
    }

    /** A centered notice in the chat (e.g. "@x joined the trip"). from="system" so no bubble/avatar. */
    fun postSystem(gid: String, text: String) {
        if (text.isBlank()) return
        post(gid, mapOf("from" to "system", "fromTag" to "", "text" to text, "system" to true))
    }

    fun sendMessage(gid: String, fromUid: String, fromTag: String, text: String) {
        val body = text.trim()
        if (body.isEmpty()) return
        post(gid, mapOf("from" to fromUid, "fromTag" to fromTag, "text" to body))
    }

    fun sendImage(gid: String, fromUid: String, fromTag: String, base64: String) {
        if (base64.isEmpty()) return
        post(gid, mapOf("from" to fromUid, "fromTag" to fromTag, "text" to "", "image" to base64))
    }

    /** Share a personal pin/note into a group's chat as a tappable location card. */
    fun sharePin(gid: String, fromUid: String, fromTag: String, lat: Double, lng: Double, name: String, note: String, placeId: String) {
        post(gid, mapOf(
            "from" to fromUid, "fromTag" to fromTag, "text" to "",
            "pinLat" to lat, "pinLng" to lng, "pinName" to name, "pinNote" to note, "pinPlaceId" to placeId,
        ))
    }

    private fun post(gid: String, fields: Map<String, Any>) {
        // ponytail: client millis for ordering so a just-sent message doesn't jump on a null server timestamp.
        // Ceiling: cross-device clock skew can misorder near-simultaneous messages; swap to serverTimestamp if it matters.
        val ts = System.currentTimeMillis()
        val gref = db.collection("groups").document(gid)
        val batch = db.batch()
        batch.set(gref.collection("messages").document(), fields + ("ts" to ts))
        // Mirror DMs: keep an inbox preview on the group doc so the unified Messages list can sort/show it.
        batch.set(gref, mapOf("lastMsg" to previewOf(fields), "lastTs" to ts), SetOptions.merge())
        batch.commit()
    }

    /** One-time inbox backfill for groups created before lastMsg/lastTs existed: seed from the newest message. */
    fun backfillPreview(gid: String) {
        val gref = db.collection("groups").document(gid)
        gref.collection("messages").orderBy("ts", Query.Direction.DESCENDING).limit(1).get()
            .addOnSuccessListener { snap ->
                val d = snap.documents.firstOrNull() ?: return@addOnSuccessListener
                gref.set(mapOf("lastMsg" to previewOf(d.data ?: emptyMap()), "lastTs" to (d.getLong("ts") ?: 0L)), SetOptions.merge())
            }
    }

    /** Inbox preview for a message (media get an emoji label; text is shown verbatim). */
    private fun previewOf(f: Map<String, Any>): String = when {
        (f["image"] as? String).orEmpty().isNotEmpty() -> "📷 Photo"
        (f["liveFrom"] as? String).orEmpty().isNotEmpty() -> "🔴 Live location"
        f["pinLat"] != null -> "📍 " + (f["pinName"] as? String).orEmpty().ifEmpty { "Location" }
        else -> f["text"] as? String ?: ""
    }

    // ── Membership / roles ────────────────────────────────────────────────────────
    fun addMember(gid: String, friend: UserHit, onDone: (String?) -> Unit) {
        db.collection("groups").document(gid).update(
            "members", FieldValue.arrayUnion(friend.uid),
            "tags.${friend.uid}", friend.tag,
        ).addOnSuccessListener { onDone(null) }.addOnFailureListener { onDone(it.message ?: "Could not add") }
    }

    fun kickMember(gid: String, uid: String, onDone: (String?) -> Unit) {
        db.collection("groups").document(gid).update(
            "members", FieldValue.arrayRemove(uid),
            "admins", FieldValue.arrayRemove(uid),
            "tags.$uid", FieldValue.delete(),
        ).addOnSuccessListener { onDone(null) }.addOnFailureListener { onDone(it.message ?: "Could not remove") }
    }

    /** Owner-only in the UI. */
    fun setAdmin(gid: String, uid: String, makeAdmin: Boolean, onDone: (String?) -> Unit) {
        val op = if (makeAdmin) FieldValue.arrayUnion(uid) else FieldValue.arrayRemove(uid)
        db.collection("groups").document(gid).update("admins", op)
            .addOnSuccessListener { onDone(null) }.addOnFailureListener { onDone(it.message ?: "Failed") }
    }

    fun leaveGroup(gid: String, uid: String, onDone: (String?) -> Unit) = kickMember(gid, uid, onDone)
}
