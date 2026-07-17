// Unified inbox (Messenger-style): group chats + 1-on-1 DMs in one list, newest first.
// Enhanced with tabs (All/Groups), long-press context menu (Pin/Archive/Mute/Block/Delete),
// and a persistent archiving system.
package com.usc.myway

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.ListenerRegistration
import com.usc.myway.ui.theme.MyWayTheme

private val Teal = Color(0xFF00C99D)
private val TealDeep = Color(0xFF00A77D)
private val Danger = Color(0xFFEF4444)

// One inbox row — a DM or a group, normalised so they sort together by pinning and recency.
private sealed interface Inbox {
    val id: String
    val ts: Long
    val pinned: Boolean
    val archived: Boolean
    val muted: Boolean

    data class Dm(val chat: PrivateChat, val myUid: String) : Inbox {
        override val id get() = chat.id
        override val ts get() = chat.lastTs
        override val pinned get() = chat.isPinned(myUid)
        override val archived get() = chat.isArchived(myUid)
        override val muted get() = chat.isMuted(myUid)
        val otherUid = chat.otherUid(myUid)
        val otherTag = chat.otherTag(myUid)
    }
    data class Grp(val g: Group, val myUid: String) : Inbox {
        override val id get() = g.id
        override val ts get() = g.lastTs
        override val pinned get() = g.isPinned(myUid)
        override val archived get() = g.isArchived(myUid)
        override val muted get() = g.isMuted(myUid)
    }
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
    private val backfilled = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MyWayTheme { MessagesScreen() } }
    }

    override fun onStart() {
        super.onStart()
        listeners += PrivateMessages.listenMyChats(uid) { chats = it }
        listeners += Groups.listenMyGroups(uid) { list ->
            groups = list
            list.forEach { if (it.lastTs == 0L && backfilled.add(it.id)) Groups.backfillPreview(it.id) }
        }
        listeners += Friends.listenFriends(uid) { friends = it }
    }

    override fun onStop() {
        super.onStop()
        listeners.forEach { it.remove() }; listeners.clear()
    }

    private fun inbox(archived: Boolean, groupsOnly: Boolean): List<Inbox> {
        val all = (chats.map { Inbox.Dm(it, uid) } + groups.map { Inbox.Grp(it, uid) })
        return all.filter { it.archived == archived && (!groupsOnly || it is Inbox.Grp) }
            .sortedWith(compareByDescending<Inbox> { it.pinned }.thenByDescending { it.ts })
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun MessagesScreen() {
        var selectedTabIndex by rememberSaveable { mutableIntStateOf(0) }
        var showArchived by rememberSaveable { mutableStateOf(false) }
        var showMenu by remember { mutableStateOf(false) }
        var showNewDM by remember { mutableStateOf(false) }
        var showCreate by remember { mutableStateOf(false) }
        var selectedItem by remember { mutableStateOf<Inbox?>(null) }
        val sheetState = rememberModalBottomSheetState()
        
        val items = remember(chats, groups, selectedTabIndex, showArchived) {
            inbox(archived = showArchived, groupsOnly = selectedTabIndex == 1)
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(if (showArchived) "Archived" else "Messages", fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = { if (showArchived) showArchived = false else finish() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        if (!showArchived) {
                            IconButton(onClick = { showArchived = true }) {
                                Icon(Icons.Outlined.Archive, contentDescription = "View Archived")
                            }
                        }
                    }
                )
            },
            floatingActionButton = {
                if (!showArchived) {
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
            }
        ) { pad ->
            Column(Modifier.fillMaxSize().padding(pad)) {
                if (!showArchived) {
                    PrimaryTabRow(selectedTabIndex = selectedTabIndex, containerColor = MaterialTheme.colorScheme.surface) {
                        Tab(selected = selectedTabIndex == 0, onClick = { selectedTabIndex = 0 }) {
                            Text("All", modifier = Modifier.padding(12.dp), fontWeight = FontWeight.Medium)
                        }
                        Tab(selected = selectedTabIndex == 1, onClick = { selectedTabIndex = 1 }) {
                            Text("Groups", modifier = Modifier.padding(12.dp), fontWeight = FontWeight.Medium)
                        }
                    }
                }

                if (items.isEmpty()) {
                    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text(if (showArchived) "No archived conversations." else "No conversations yet.\nTap the pencil to message a friend or start a group.",
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    }
                } else {
                    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                        items(items, key = { it.id }) { row ->
                            when (row) {
                                is Inbox.Dm -> DmRow(row) { selectedItem = row }
                                is Inbox.Grp -> GroupRow(row) { selectedItem = row }
                            }
                        }
                    }
                }
            }
        }

        selectedItem?.let { item ->
            ModalBottomSheet(
                onDismissRequest = { selectedItem = null },
                sheetState = sheetState,
                containerColor = MaterialTheme.colorScheme.surface,
            ) {
                ContextActions(item, onDismiss = { selectedItem = null })
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

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun DmRow(dm: Inbox.Dm, onLongClick: () -> Unit) {
        var tag by remember(dm.otherUid) { mutableStateOf(dm.otherTag) }
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
                .combinedClickable(
                    onClick = {
                        startActivity(Intent(this@MessagesActivity, PrivateChatActivity::class.java)
                            .putExtra("chatId", dm.chat.id).putExtra("otherUid", dm.otherUid).putExtra("otherTag", tag))
                    },
                    onLongClick = onLongClick
                )
                .padding(12.dp), verticalAlignment = Alignment.CenterVertically
        ) {
            AvatarCircle(photo = photo, fallback = tag, size = 48.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("@$tag", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    if (dm.pinned) {
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.Default.PushPin, null, Modifier.size(12.dp), TealDeep)
                    }
                    if (dm.muted) {
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.Outlined.NotificationsOff, null, Modifier.size(12.dp), MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
                    }
                }
                Preview(dm.chat.lastMsg)
            }
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun GroupRow(row: Inbox.Grp, onLongClick: () -> Unit) {
        val g = row.g
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                .combinedClickable(
                    onClick = {
                        startActivity(Intent(this@MessagesActivity, GroupChatActivity::class.java)
                            .putExtra("gid", g.id).putExtra("name", g.name))
                    },
                    onLongClick = onLongClick
                )
                .padding(12.dp), verticalAlignment = Alignment.CenterVertically
        ) {
            AvatarCircle(photo = g.photo, fallback = g.name, size = 48.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Group, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(5.dp))
                    Text(g.name, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    if (row.pinned) {
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.Default.PushPin, null, Modifier.size(12.dp), TealDeep)
                    }
                    if (row.muted) {
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.Outlined.NotificationsOff, null, Modifier.size(12.dp), MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
                    }
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

    @Composable
    private fun ContextActions(item: Inbox, onDismiss: () -> Unit) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp)) {
            val title = if (item is Inbox.Dm) "@${item.otherTag}" else (item as Inbox.Grp).g.name
            Text(title, Modifier.padding(16.dp), fontWeight = FontWeight.Bold, fontSize = 18.sp)
            
            ActionItem(if (item.pinned) "Unpin" else "Pin", if (item.pinned) Icons.Outlined.PushPin else Icons.Outlined.PushPin) {
                if (item is Inbox.Dm) PrivateMessages.updateMetadata(item.id, uid, "pinned", !item.pinned)
                else Groups.updateMetadata(item.id, uid, "pinned", !item.pinned)
                onDismiss()
            }
            ActionItem(if (item.archived) "Unarchive" else "Archive", if (item.archived) Icons.Outlined.Unarchive else Icons.Outlined.Archive) {
                if (item is Inbox.Dm) PrivateMessages.updateMetadata(item.id, uid, "archived", !item.archived)
                else Groups.updateMetadata(item.id, uid, "archived", !item.archived)
                onDismiss()
            }
            ActionItem(if (item.muted) "Unmute" else "Mute", if (item.muted) Icons.Outlined.Notifications else Icons.Outlined.NotificationsOff) {
                if (item is Inbox.Dm) PrivateMessages.updateMetadata(item.id, uid, "muted", !item.muted)
                else Groups.updateMetadata(item.id, uid, "muted", !item.muted)
                onDismiss()
            }
            if (item is Inbox.Dm) {
                ActionItem("Block User", Icons.Outlined.Block, color = Danger) {
                    Profiles.blockUser(uid, item.otherUid)
                    onDismiss()
                }
            }
            ActionItem("Delete Chat", Icons.Outlined.Delete, color = Danger) {
                if (item is Inbox.Dm) PrivateMessages.deleteChat(item.id, uid)
                else Groups.leaveGroup(item.id, uid) { }
                onDismiss()
            }
        }
    }

    @Composable
    private fun ActionItem(label: String, icon: ImageVector, color: Color = MaterialTheme.colorScheme.onSurface, onClick: () -> Unit) {
        ListItem(
            headlineContent = { Text(label, color = color) },
            leadingContent = { Icon(icon, null, tint = color) },
            modifier = Modifier.clickable(onClick = onClick)
        )
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
