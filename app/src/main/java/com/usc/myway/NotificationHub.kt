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
    private val knownGroups = HashSet<String>()                     // gids I'm already a member of (invite detection)
    private var groupsSeeded = false

    private var chatsReg: ListenerRegistration? = null
    private val chatMsgRegs = HashMap<String, ListenerRegistration>() // chatId -> latest-message listener
    private val chatLastId = HashMap<String, String>()
    private val chatSeeded = HashSet<String>()

    private var reqReg: ListenerRegistration? = null
    private val seenReqs = HashSet<String>()                        // friend-request ids already notified for
    private var reqSeeded = false

    /** Set by GroupChatActivity while a group chat is open, so we don't notify for the chat you're reading. */
    @Volatile var activeChatGid: String? = null

    /** Set by PrivateChatActivity while a DM is open. */
    @Volatile var activeDmId: String? = null

    private var ctx: Context? = null
    private var uid: String = ""

    fun start(context: Context, myUid: String) {
        if (myUid.isEmpty()) return
        if (uid == myUid && myGroupsReg != null) return   // already running for this user
        stop()
        ctx = context.applicationContext; uid = myUid
        myGroupsReg = Groups.listenMyGroups(myUid) { groups -> sync(groups) }
        chatsReg = PrivateMessages.listenMyChats(myUid) { chats -> syncChats(chats) }
        reqReg = Friends.listenIncoming(myUid) { reqs -> syncRequests(reqs) }
    }

    fun stop() {
        myGroupsReg?.remove(); myGroupsReg = null
        msgRegs.values.forEach { it.remove() }; msgRegs.clear()
        lastMsgId.clear(); seeded.clear(); tripActive.clear(); groupName.clear()
        knownGroups.clear(); groupsSeeded = false
        chatsReg?.remove(); chatsReg = null
        chatMsgRegs.values.forEach { it.remove() }; chatMsgRegs.clear()
        chatLastId.clear(); chatSeeded.clear()
        reqReg?.remove(); reqReg = null
        seenReqs.clear(); reqSeeded = false
        uid = ""
    }

    private fun sync(groups: List<Group>) {
        val ids = groups.map { it.id }.toSet()
        for (g in groups) {
            groupName[g.id] = g.name
            // I was added to a group I didn't create (skip the first snapshot's existing memberships).
            if (groupsSeeded && g.id !in knownGroups && g.owner != uid) ctx?.let { Notifier.groupInvite(it, g.id, g.name) }
            knownGroups += g.id
            // Trip start: false -> true (skip the initial null so an already-running trip isn't announced).
            val prev = tripActive[g.id]
            if (prev == false && g.tripActive && g.id != activeChatGid) ctx?.let { Notifier.trip(it, g.id, g.name) }
            tripActive[g.id] = g.tripActive
            if (g.id !in msgRegs) attachMessages(g.id)
        }
        knownGroups.retainAll(ids)
        groupsSeeded = true
        (msgRegs.keys - ids).forEach { gid ->   // left/removed groups
            msgRegs.remove(gid)?.remove(); lastMsgId.remove(gid); seeded.remove(gid); tripActive.remove(gid)
        }
    }

    private fun syncChats(chats: List<PrivateChat>) {
        val ids = chats.map { it.id }.toSet()
        for (c in chats) if (c.id !in chatMsgRegs) attachChatMessages(c.id)
        (chatMsgRegs.keys - ids).forEach { id ->
            chatMsgRegs.remove(id)?.remove(); chatLastId.remove(id); chatSeeded.remove(id)
        }
    }

    private fun attachChatMessages(chatId: String) {
        chatMsgRegs[chatId] = db.collection("private_chats").document(chatId).collection("messages")
            .orderBy("ts", Query.Direction.DESCENDING).limit(1)
            .addSnapshotListener { snap, _ ->
                val doc = snap?.documents?.firstOrNull() ?: return@addSnapshotListener
                if (chatId !in chatSeeded) { chatLastId[chatId] = doc.id; chatSeeded.add(chatId); return@addSnapshotListener }
                if (chatLastId[chatId] == doc.id) return@addSnapshotListener
                chatLastId[chatId] = doc.id
                val from = doc.getString("from") ?: ""
                if (from == uid || chatId == activeDmId) return@addSnapshotListener
                val c = ctx ?: return@addSnapshotListener
                Notifier.dm(c, chatId, from, doc.getString("fromTag") ?: "", preview(doc))
            }
    }

    private fun syncRequests(reqs: List<FriendRequest>) {
        if (!reqSeeded) { reqs.forEach { seenReqs.add(it.id) }; reqSeeded = true; return } // skip existing on first snapshot
        for (r in reqs) if (seenReqs.add(r.id)) ctx?.let { Notifier.friendRequest(it, r.fromTag) }
        seenReqs.retainAll(reqs.map { it.id }.toSet())
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
