// Private 1-on-1 messaging layer on Firestore.
//   private_chats/{chatId}                { users: [a,b], tags: {uid:tag}, lastMsg: string, lastTs: Long }
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
) {
    fun otherUid(myUid: String) = users.firstOrNull { it != myUid } ?: ""
    fun otherTag(myUid: String) = tags[otherUid(myUid)] ?: "User"
}

object PrivateMessages {

    private val db get() = FirebaseFirestore.getInstance()

    fun pairId(a: String, b: String) = listOf(a, b).sorted().joinToString("_")

    /** List of all my active private chats. */
    fun listenMyChats(myUid: String, onChange: (List<PrivateChat>) -> Unit): ListenerRegistration =
        db.collection("private_chats").whereArrayContains("users", myUid)
            .orderBy("lastTs", Query.Direction.DESCENDING)
            .addSnapshotListener { snap, _ ->
                if (snap == null) return@addSnapshotListener
                onChange(snap.documents.mapNotNull { d ->
                    val users = d.get("users") as? List<String> ?: return@mapNotNull null
                    @Suppress("UNCHECKED_CAST")
                    val tags = d.get("tags") as? Map<String, String> ?: emptyMap()
                    PrivateChat(d.id, users, tags, d.getString("lastMsg") ?: "", d.getLong("lastTs") ?: 0L)
                })
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
                        it.getString("liveFrom") ?: "", it.getLong("ts") ?: 0L)
                })
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
