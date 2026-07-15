// Unified inbox (Messenger-style): group chats + 1-on-1 DMs in one list, newest first. The + button
// starts either a DM (pick a friend) or a new group. DM rows carry a live profile listener so a
// friend's new photo/@tag shows up immediately; groups already stream via the groups listener.
package com.usc.myway

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.outlined.RadioButtonChecked
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.ListenerRegistration
import com.usc.myway.ui.theme.MyWayTheme

private val Teal = Color(0xFF00C99D)
private val TealDeep = Color(0xFF00A77D)

// One inbox row — a DM or a group, normalised so they sort together by recency.
private sealed interface Inbox {
    val ts: Long
    data class Dm(val chat: PrivateChat, val otherUid: String, val fallbackTag: String) : Inbox {
        override val ts get() = chat.lastTs
    }
    data class Grp(val g: Group) : Inbox { override val ts get() = g.lastTs }
}

class MessagesActivity : ComponentActivity() {
    private val auth by lazy { FirebaseAuth.getInstance() }
    private val uid get() = auth.currentUser?.uid ?: ""
    private val myTag by lazy { (application as App).getUserTag(uid).ifEmpty { "me" } }

    private var chats by mutableStateOf<List<PrivateChat>>(emptyList())
    private var groups by mutableStateOf<List<Group>>(emptyList())
    private var friends by mutableStateOf<List<UserHit>>(emptyList())
    private var creating by mutableStateOf(false)
    private val listeners = mutableListOf<ListenerRegistration>()
    private val backfilled = mutableSetOf<String>()   // groups we've already seeded a preview for this session

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MyWayTheme { MessagesScreen() } }
    }

    override fun onStart() {
        super.onStart()
        listeners += PrivateMessages.listenMyChats(uid) { chats = it }
        listeners += Groups.listenMyGroups(uid) { list ->
            groups = list
            // Legacy groups predate lastMsg/lastTs — seed them once so they sort/preview in the inbox.
            list.forEach { if (it.lastTs == 0L && backfilled.add(it.id)) Groups.backfillPreview(it.id) }
        }
        listeners += Friends.listenFriends(uid) { friends = it }
    }

    override fun onStop() {
        super.onStop()
        listeners.forEach { it.remove() }; listeners.clear()
    }

    private fun inbox(): List<Inbox> =
        (chats.map { Inbox.Dm(it, it.otherUid(uid), it.otherTag(uid)) } + groups.map { Inbox.Grp(it) })
            .sortedByDescending { it.ts }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun MessagesScreen() {
        var showMenu by remember { mutableStateOf(false) }
        var showNewDM by remember { mutableStateOf(false) }
        var showCreate by remember { mutableStateOf(false) }
        val items = inbox()

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Messages", fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = { finish() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                )
            },
            floatingActionButton = {
                Box {
                    FloatingActionButton(onClick = { showMenu = true }, containerColor = Teal) {
                        Icon(Icons.Default.Edit, contentDescription = "New conversation", tint = Color.White)
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(text = { Text("Message a friend") }, onClick = { showMenu = false; showNewDM = true })
                        DropdownMenuItem(text = { Text("Create a group") }, onClick = { showMenu = false; showCreate = true })
                    }
                }
            }
        ) { pad ->
            if (items.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(pad).padding(32.dp), contentAlignment = Alignment.Center) {
                    Text("No conversations yet.\nTap the pencil to message a friend or start a group.",
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                }
            } else {
                LazyColumn(Modifier.fillMaxSize().padding(pad).padding(horizontal = 12.dp)) {
                    items(items, key = { if (it is Inbox.Grp) "g:${it.g.id}" else "p:${(it as Inbox.Dm).chat.id}" }) { row ->
                        when (row) {
                            is Inbox.Dm -> DmRow(row)
                            is Inbox.Grp -> GroupRow(row.g)
                        }
                    }
                }
            }
        }

        if (showNewDM) NewMessageDialog(friends, onDismiss = { showNewDM = false }) { f ->
            showNewDM = false
            startActivity(Intent(this, PrivateChatActivity::class.java)
                .putExtra("chatId", PrivateMessages.pairId(uid, f.uid))
                .putExtra("otherUid", f.uid).putExtra("otherTag", f.tag))
        }
        if (showCreate) CreateGroupDialog(
            friends = friends, creating = creating,
            onDismiss = { showCreate = false },
            onConfirm = { name, picked -> creating = true; Groups.createGroup(uid, myTag, name, picked) { creating = false }; showCreate = false },
        )
    }

    @Composable
    private fun DmRow(dm: Inbox.Dm) {
        var tag by remember(dm.otherUid) { mutableStateOf(dm.fallbackTag) }
        var photo by remember(dm.otherUid) { mutableStateOf("") }
        DisposableEffect(dm.otherUid) {
            val reg = Profiles.listenProfile(dm.otherUid) { p ->
                if (p.tag.isNotEmpty()) tag = p.tag
                photo = p.photo
            }
            onDispose { reg.remove() }
        }
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                .clickable {
                    startActivity(Intent(this@MessagesActivity, PrivateChatActivity::class.java)
                        .putExtra("chatId", dm.chat.id).putExtra("otherUid", dm.otherUid).putExtra("otherTag", tag))
                }
                .padding(12.dp), verticalAlignment = Alignment.CenterVertically
        ) {
            AvatarCircle(photo = photo, fallback = tag, size = 48.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("@$tag", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Preview(dm.chat.lastMsg)
            }
        }
    }

    @Composable
    private fun GroupRow(g: Group) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                .clickable {
                    startActivity(Intent(this@MessagesActivity, GroupChatActivity::class.java)
                        .putExtra("gid", g.id).putExtra("name", g.name))
                }
                .padding(12.dp), verticalAlignment = Alignment.CenterVertically
        ) {
            AvatarCircle(photo = g.photo, fallback = g.name, size = 48.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Group, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(5.dp))
                    Text(g.name, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    if (g.tripActive) {
                        Spacer(Modifier.width(8.dp))
                        Row(
                            Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0xFFEF4444)).padding(horizontal = 7.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Outlined.RadioButtonChecked, contentDescription = null, tint = Color.White, modifier = Modifier.size(10.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("LIVE", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    }
                }
                Preview(g.lastMsg.ifEmpty { "${g.members.size} member${if (g.members.size == 1) "" else "s"}" })
            }
        }
    }

    @Composable
    private fun Preview(text: String) {
        if (text.isNotEmpty()) Text(text, fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f), maxLines = 1)
    }
}

// Pick a friend to open (or start) a 1-on-1 chat with.
@Composable
private fun NewMessageDialog(friends: List<UserHit>, onDismiss: () -> Unit, onPick: (UserHit) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New message", fontWeight = FontWeight.Bold) },
        text = {
            if (friends.isEmpty()) {
                Text("Add friends first — you can only message your friends.")
            } else {
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(friends, key = { it.uid }) { f ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onPick(f) }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            LiveAvatar(f.uid, f.tag, 36.dp)
                            Spacer(Modifier.width(12.dp))
                            Text("@${f.tag}", fontSize = 15.sp)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// Name a new group and pick which friends to add (friends-only). Shared with the group-creation flow.
@Composable
fun CreateGroupDialog(
    friends: List<UserHit>,
    creating: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String, List<UserHit>) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf<Set<String>>(emptySet()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New group", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Group name") }, singleLine = true,
                    shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Text("ADD FRIENDS", fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                if (friends.isEmpty()) {
                    Text("Add friends first — you can only add friends to a group.",
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        modifier = Modifier.padding(top = 8.dp))
                } else {
                    LazyColumn(Modifier.heightIn(max = 240.dp)) {
                        items(friends, key = { it.uid }) { f ->
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    picked = if (f.uid in picked) picked - f.uid else picked + f.uid
                                }.padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = f.uid in picked,
                                    onCheckedChange = { picked = if (it) picked + f.uid else picked - f.uid },
                                    colors = CheckboxDefaults.colors(checkedColor = Teal),
                                )
                                Spacer(Modifier.width(4.dp))
                                LiveAvatar(f.uid, f.tag, 32.dp)
                                Spacer(Modifier.width(10.dp))
                                Text("@${f.tag}", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name, friends.filter { it.uid in picked }) },
                enabled = !creating && name.isNotBlank(),
            ) { Text("Create", color = TealDeep, fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
