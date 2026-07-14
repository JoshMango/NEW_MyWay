// App-wide listener that turns group activity into notifications while the process is alive.
// Watches my groups; posts a notification for a new message (not mine, not the chat I'm viewing) and
// for a trip that just started. Seeds "last seen" on first snapshot so it never notifies for backlog.
package com.usc.myway

import android.content.Context
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query

object NotificationHub {

    private val db get() = FirebaseFirestore.getInstance()
    private var myGroupsReg: ListenerRegistration? = null
    private val msgRegs = HashMap<String, ListenerRegistration>()   // gid -> latest-message listener
    private val lastMsgId = HashMap<String, String>()               // gid -> last seen message id
    private val seeded = HashSet<String>()                          // gids whose backlog we've skipped
    private val tripActive = HashMap<String, Boolean>()             // gid -> last known tripActive
    private val groupName = HashMap<String, String>()

    /** Set by GroupChatActivity while a chat is open, so we don't notify for the chat you're reading. */
    @Volatile var activeChatGid: String? = null

    private var ctx: Context? = null
    private var uid: String = ""

    fun start(context: Context, myUid: String) {
        if (myUid.isEmpty()) return
        if (uid == myUid && myGroupsReg != null) return   // already running for this user
        stop()
        ctx = context.applicationContext; uid = myUid
        myGroupsReg = Groups.listenMyGroups(myUid) { groups -> sync(groups) }
    }

    fun stop() {
        myGroupsReg?.remove(); myGroupsReg = null
        msgRegs.values.forEach { it.remove() }; msgRegs.clear()
        lastMsgId.clear(); seeded.clear(); tripActive.clear(); groupName.clear()
        uid = ""
    }

    private fun sync(groups: List<Group>) {
        val ids = groups.map { it.id }.toSet()
        for (g in groups) {
            groupName[g.id] = g.name
            // Trip start: false -> true (skip the initial null so an already-running trip isn't announced).
            val prev = tripActive[g.id]
            if (prev == false && g.tripActive && g.id != activeChatGid) ctx?.let { Notifier.trip(it, g.id, g.name) }
            tripActive[g.id] = g.tripActive
            if (g.id !in msgRegs) attachMessages(g.id)
        }
        (msgRegs.keys - ids).forEach { gid ->   // left/removed groups
            msgRegs.remove(gid)?.remove(); lastMsgId.remove(gid); seeded.remove(gid); tripActive.remove(gid)
        }
    }

    private fun attachMessages(gid: String) {
        msgRegs[gid] = db.collection("groups").document(gid).collection("messages")
            .orderBy("ts", Query.Direction.DESCENDING).limit(1)
            .addSnapshotListener { snap, _ ->
                val doc = snap?.documents?.firstOrNull() ?: return@addSnapshotListener
                if (gid !in seeded) { lastMsgId[gid] = doc.id; seeded.add(gid); return@addSnapshotListener } // skip backlog
                if (lastMsgId[gid] == doc.id) return@addSnapshotListener
                lastMsgId[gid] = doc.id
                val from = doc.getString("from") ?: ""
                if (from == uid || from == "system") return@addSnapshotListener   // not my own; skip system notices
                if (gid == activeChatGid) return@addSnapshotListener
                val c = ctx ?: return@addSnapshotListener
                Notifier.message(c, gid, groupName[gid] ?: "Group", doc.getString("fromTag") ?: "", preview(doc))
            }
    }

    private fun preview(doc: com.google.firebase.firestore.DocumentSnapshot): String = when {
        (doc.getString("image") ?: "").isNotEmpty() -> "Photo"
        (doc.getString("liveFrom") ?: "").isNotEmpty() -> "Live location"
        doc.getDouble("pinLat") != null -> (doc.getString("pinName")?.ifEmpty { "Location" } ?: "Location")
        else -> doc.getString("text") ?: ""
    }
}
