// Private 1-on-1 messaging layer on Firestore.
//   private_chats/{chatId}                { users: [a,b], tags: {uid:tag}, lastMsg: string, lastTs: Long, reads: {uid:ts} }
//   private_chats/{chatId}/messages/{mid}   { from, fromTag, text, image, pinLat... ts }
// chatId is pairId(uidA, uidB) — alphabetical sort of UIDs joined by underscore.
package com.usc.myway

import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions

data class PrivateChat(
    val id: String,
    val users: List<String>,
    val tags: Map<String, String>,
    val lastMsg: String = "",
    val lastTs: Long = 0,
    val pinned: Map<String, Boolean> = emptyMap(),
    val archived: Map<String, Boolean> = emptyMap(),
    val muted: Map<String, Boolean> = emptyMap(),
    val reads: Map<String, Long> = emptyMap()
) {
    fun otherUid(myUid: String) = users.firstOrNull { it != myUid } ?: ""
    fun otherTag(myUid: String) = tags[otherUid(myUid)] ?: "User"
    fun isPinned(uid: String) = pinned[uid] == true
    fun isArchived(uid: String) = archived[uid] == true
    fun isMuted(uid: String) = muted[uid] == true
    fun isUnread(uid: String) = lastTs > (reads[uid] ?: 0L)
}

object PrivateMessages {

    private val db get() = FirebaseFirestore.getInstance()

    fun pairId(a: String, b: String) = listOf(a, b).sorted().joinToString("_")

    /** List of all my active private chats, ordered by lastTs descending. */
    fun listenMyChats(myUid: String, onChange: (List<PrivateChat>) -> Unit): ListenerRegistration =
        // No orderBy: `whereArrayContains` + `orderBy` needs a composite index, and without it the
        // server listener silently fails (cache still serves your own writes, but remote messages never
        // arrive). Consumers sort client-side anyway, so ordering here is redundant.
        db.collection("private_chats").whereArrayContains("users", myUid)
            .addSnapshotListener { snap, err ->
                if (err != null) {
                    Log.e("PrivateMessages", "listenMyChats failed. Ensure index is created: ${err.message}")
                    return@addSnapshotListener
                }
                if (snap == null) return@addSnapshotListener
                onChange(snap.documents.mapNotNull { d ->
                    val users = d.get("users") as? List<String> ?: return@mapNotNull null
                    @Suppress("UNCHECKED_CAST")
                    val tags = d.get("tags") as? Map<String, String> ?: emptyMap()
                    @Suppress("UNCHECKED_CAST")
                    val pinned = d.get("pinned") as? Map<String, Boolean> ?: emptyMap()
                    @Suppress("UNCHECKED_CAST")
                    val archived = d.get("archived") as? Map<String, Boolean> ?: emptyMap()
                    @Suppress("UNCHECKED_CAST")
                    val muted = d.get("muted") as? Map<String, Boolean> ?: emptyMap()
                    @Suppress("UNCHECKED_CAST")
                    val reads = d.get("reads") as? Map<String, Long> ?: emptyMap()
                    
                    PrivateChat(
                        id = d.id, 
                        users = users, 
                        tags = tags, 
                        lastMsg = d.getString("lastMsg") ?: "", 
                        lastTs = d.getLong("lastTs") ?: 0L,
                        pinned = pinned,
                        archived = archived,
                        muted = muted,
                        reads = reads
                    )
                })
            }

    fun updateMetadata(chatId: String, myUid: String, field: String, value: Any) {
        db.collection("private_chats").document(chatId)
            .update("$field.$myUid", value)
            .addOnFailureListener { Log.e("PrivateMessages", "updateMetadata failed", it) }
    }

    /** Read receipt: remember the newest message [uid] has seen in this chat. */
    fun markRead(chatId: String, uid: String, ts: Long) {
        db.collection("private_chats").document(chatId).update("reads.$uid", ts)
    }

    fun deleteChat(chatId: String, myUid: String) {
        db.collection("private_chats").document(chatId)
            .update("users", com.google.firebase.firestore.FieldValue.arrayRemove(myUid))
            .addOnFailureListener { Log.e("PrivateMessages", "deleteChat failed", it) }
    }

    fun listenMessages(chatId: String, onChange: (List<GroupMessage>) -> Unit): ListenerRegistration =
        db.collection("private_chats").document(chatId).collection("messages")
            .orderBy("ts", Query.Direction.ASCENDING)
            .addSnapshotListener { snap, err ->
                if (err != null) Log.e("PrivateMessages", "listenMessages failed", err)
                if (snap != null) onChange(snap.documents.map {
                    GroupMessage(it.id, it.getString("from") ?: "", it.getString("fromTag") ?: "",
                        it.getString("text") ?: "", it.getString("image") ?: "",
                        it.getDouble("pinLat"), it.getDouble("pinLng"),
                        it.getString("pinName") ?: "", it.getString("pinNote") ?: "",
                        it.getString("pinPlaceId") ?: "", it.getBoolean("system") ?: false,
                        it.getString("liveFrom") ?: "", it.getLong("ts") ?: 0L,
                        it.getBoolean("edited") ?: false, it.getBoolean("unsent") ?: false)
                })
            }

    /** Edit a text message (author only). Refresh the inbox preview when it was the newest message. */
    fun editMessage(chatId: String, mid: String, text: String, isLast: Boolean) {
        val body = text.trim()
        if (body.isEmpty()) return
        val ref = db.collection("private_chats").document(chatId)
        ref.collection("messages").document(mid).update("text", body, "edited", true)
            .addOnFailureListener { Log.e("PrivateMessages", "editMessage failed", it) }
        if (isLast) ref.update("lastMsg", body)
    }

    /** Unsend a message (author only). Soft-delete: keep the doc as a tombstone with content cleared. */
    fun unsendMessage(chatId: String, mid: String, isLast: Boolean) {
        val ref = db.collection("private_chats").document(chatId)
        ref.collection("messages").document(mid).update(
            mapOf("unsent" to true, "text" to "", "image" to "", "liveFrom" to "", "edited" to false)
        ).addOnFailureListener { Log.e("PrivateMessages", "unsendMessage failed", it) }
        if (isLast) ref.update("lastMsg", "Unsent a message")
    }

    fun sendMessage(chatId: String, fromUid: String, fromTag: String, otherUid: String, otherTag: String, text: String) {
        val body = text.trim()
        if (body.isEmpty()) return
        val ts = System.currentTimeMillis()
        val msg = mapOf("from" to fromUid, "fromTag" to fromTag, "text" to body, "ts" to ts)
        
        val batch = db.batch()
        val chatRef = db.collection("private_chats").document(chatId)
        val msgRef = chatRef.collection("messages").document()
        
        batch.set(chatRef, mapOf(
            "users" to listOf(fromUid, otherUid).sorted(),
            "tags" to mapOf(fromUid to fromTag, otherUid to otherTag),
            "lastMsg" to body,
            "lastTs" to ts
        ), SetOptions.merge())
        batch.set(msgRef, msg)
        batch.commit().addOnFailureListener { Log.e("PrivateMessages", "sendMessage failed", it) }
    }

    /** Drop a live-location card into a DM so the recipient can tap to follow the sharer's map. */
    fun postLiveShare(chatId: String, fromUid: String, fromTag: String, otherUid: String, otherTag: String) {
        val ts = System.currentTimeMillis()
        val batch = db.batch()
        val chatRef = db.collection("private_chats").document(chatId)
        batch.set(chatRef, mapOf(
            "users" to listOf(fromUid, otherUid).sorted(),
            "tags" to mapOf(fromUid to fromTag, otherUid to otherTag),
            "lastMsg" to "🔴 Live location", "lastTs" to ts,
        ), SetOptions.merge())
        batch.set(chatRef.collection("messages").document(),
            mapOf("from" to fromUid, "fromTag" to fromTag, "text" to "", "liveFrom" to fromUid, "ts" to ts))
        batch.commit().addOnFailureListener { Log.e("PrivateMessages", "postLiveShare failed", it) }
    }

    fun sendImage(chatId: String, fromUid: String, fromTag: String, otherUid: String, otherTag: String, base64: String) {
        if (base64.isEmpty()) return
        val ts = System.currentTimeMillis()
        val msg = mapOf("from" to fromUid, "fromTag" to fromTag, "text" to "", "image" to base64, "ts" to ts)

        val batch = db.batch()
        val chatRef = db.collection("private_chats").document(chatId)
        val msgRef = chatRef.collection("messages").document()

        batch.set(chatRef, mapOf(
            "users" to listOf(fromUid, otherUid).sorted(),
            "tags" to mapOf(fromUid to fromTag, otherUid to otherTag),
            "lastMsg" to "📷 Image",
            "lastTs" to ts
        ), SetOptions.merge())
        batch.set(msgRef, msg)
        batch.commit().addOnFailureListener { Log.e("PrivateMessages", "sendImage failed", it) }
    }
}
