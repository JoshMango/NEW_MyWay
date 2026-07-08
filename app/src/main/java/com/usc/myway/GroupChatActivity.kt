// Group chat + group info. Tap the group name → info sheet: photo (admins can change it),
// recent images, member roster with role controls, delete (owner, with warning) / leave.
// Members can send text and images. Live via Firestore snapshot listeners.
package com.usc.myway

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.usc.myway.ui.theme.MyWayTheme

private val Teal = Color(0xFF00C99D)
private val TealDeep = Color(0xFF00A77D)
private val Danger = Color(0xFFEF4444)

private class ChatState {
    var group by mutableStateOf<Group?>(null)
    var messages by mutableStateOf<List<GroupMessage>>(emptyList())
    var friends by mutableStateOf<List<UserHit>>(emptyList())
}

class GroupChatActivity : ComponentActivity() {

    private val auth by lazy { FirebaseAuth.getInstance() }
    private val uid get() = auth.currentUser?.uid ?: ""
    private val gid by lazy { intent.getStringExtra("gid") ?: "" }
    private val fallbackName by lazy { intent.getStringExtra("name") ?: "Group" }
    private val myTag by lazy { (application as App).getUserTag(uid).ifEmpty { "me" } }
    private val s = ChatState()
    private val listeners = mutableListOf<ListenerRegistration>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MyWayTheme {
                ChatScreen(
                    s = s, myUid = uid, fallbackName = fallbackName,
                    onBack = { finish() },
                    onSend = { text -> Groups.sendMessage(gid, uid, myTag, text) },
                    onSendImage = { uri -> encode(uri, 1024, 60)?.let { Groups.sendImage(gid, uid, myTag, it) } },
                    actions = groupActions,
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        listeners += Groups.listenMessages(gid) { s.messages = it }
        listeners += Friends.listenFriends(uid) { s.friends = it }
        listeners += Groups.listenGroup(gid) { g ->
            // Group deleted, or I'm no longer a member → close the screen.
            if (g == null || uid !in g.members) finish() else s.group = g
        }
    }

    override fun onStop() {
        super.onStop()
        listeners.forEach { it.remove() }; listeners.clear()
    }

    private fun encode(uri: Uri, maxDim: Int, quality: Int): String? =
        try { encodeImage(contentResolver, uri, maxDim, quality) } catch (_: Exception) { null }

    private val groupActions = object : GroupActions {
        override fun onAddMember(f: UserHit) = Groups.addMember(gid, f) {}
        override fun onKick(memberUid: String) = Groups.kickMember(gid, memberUid) {}
        override fun onSetAdmin(memberUid: String, makeAdmin: Boolean) = Groups.setAdmin(gid, memberUid, makeAdmin) {}
        override fun onLeave() = Groups.leaveGroup(gid, uid) { finish() }
        override fun onDelete() {
            FirebaseFirestore.getInstance().collection("groups").document(gid).delete()
                .addOnCompleteListener { finish() }
        }
        override fun onSetGroupPhoto(uri: Uri) { encode(uri, 256, 80)?.let { Groups.updatePhoto(gid, it) {} } }
    }
}

interface GroupActions {
    fun onAddMember(f: UserHit)
    fun onKick(memberUid: String)
    fun onSetAdmin(memberUid: String, makeAdmin: Boolean)
    fun onLeave()
    fun onDelete()
    fun onSetGroupPhoto(uri: Uri)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatScreen(
    s: ChatState,
    myUid: String,
    fallbackName: String,
    onBack: () -> Unit,
    onSend: (String) -> Unit,
    onSendImage: (Uri) -> Unit,
    actions: GroupActions,
) {
    var showInfo by remember { mutableStateOf(false) }
    val g = s.group

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        Modifier.clickable(enabled = g != null) { showInfo = true },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AvatarCircle(photo = g?.photo ?: "", fallback = g?.name ?: fallbackName, size = 34.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(g?.name ?: fallbackName, fontWeight = FontWeight.Bold)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Text("←", fontSize = 22.sp, fontWeight = FontWeight.Bold) } },
                colors = TopAppBarDefaults.topAppBarColors(),
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).imePadding()) {
            MessageList(s.messages, myUid, Modifier.weight(1f))
            MessageInput(onSend, onSendImage)
        }
    }

    if (showInfo && g != null) {
        GroupInfoSheet(
            g = g, myUid = myUid, messages = s.messages, friends = s.friends,
            onDismiss = { showInfo = false }, actions = actions,
        )
    }
}

@Composable
private fun MessageList(messages: List<GroupMessage>, myUid: String, modifier: Modifier) {
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }
    if (messages.isEmpty()) {
        Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Text("No messages yet. Say hi 👋", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        }
        return
    }
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 12.dp), state = listState,
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(messages, key = { it.id }) { m -> MessageBubble(m, mine = m.from == myUid) }
    }
}

@Composable
private fun MessageBubble(m: GroupMessage, mine: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Column(
            Modifier.widthIn(max = 280.dp).clip(RoundedCornerShape(16.dp))
                .background(if (mine) Teal else MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = if (m.image.isNotEmpty()) 4.dp else 12.dp, vertical = if (m.image.isNotEmpty()) 4.dp else 8.dp),
        ) {
            if (!mine) Text("@${m.fromTag}", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TealDeep,
                modifier = Modifier.padding(horizontal = if (m.image.isNotEmpty()) 8.dp else 0.dp))
            if (m.image.isNotEmpty()) {
                val img = remember(m.image) { decodeAvatar(m.image) }
                if (img != null) {
                    Image(img, contentDescription = "Shared image", contentScale = ContentScale.Fit,
                        modifier = Modifier.widthIn(max = 240.dp).heightIn(max = 280.dp).clip(RoundedCornerShape(12.dp)))
                }
            } else {
                Text(m.text, fontSize = 15.sp, color = if (mine) Color.White else MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

@Composable
private fun MessageInput(onSend: (String) -> Unit, onSendImage: (Uri) -> Unit) {
    var text by remember { mutableStateOf("") }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) onSendImage(uri)
    }
    Row(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { pickImage.launch("image/*") }) { Text("📷", fontSize = 22.sp) }
        OutlinedTextField(
            value = text, onValueChange = { text = it },
            placeholder = { Text("Message") }, modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(24.dp), maxLines = 4,
        )
        Spacer(Modifier.width(6.dp))
        Button(
            onClick = { if (text.isNotBlank()) { onSend(text); text = "" } },
            enabled = text.isNotBlank(), shape = RoundedCornerShape(24.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Teal),
        ) { Text("Send", fontWeight = FontWeight.Bold) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupInfoSheet(
    g: Group,
    myUid: String,
    messages: List<GroupMessage>,
    friends: List<UserHit>,
    onDismiss: () -> Unit,
    actions: GroupActions,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showAdd by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val iAmOwner = myUid == g.owner
    val iAmAdmin = g.isAdmin(myUid)
    val recentImages = remember(messages) { messages.filter { it.image.isNotEmpty() }.takeLast(12).reversed() }

    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) actions.onSetGroupPhoto(uri)
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            // Header: group photo + name.
            item {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    AvatarCircle(photo = g.photo, fallback = g.name, size = 96.dp)
                    if (iAmAdmin) {
                        TextButton(onClick = { pickPhoto.launch("image/*") }) {
                            Text(if (g.photo.isBlank()) "Add group photo" else "Change photo", color = TealDeep)
                        }
                    } else Spacer(Modifier.height(8.dp))
                    Text(g.name, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.height(16.dp))
                }
            }

            // Recent images.
            if (recentImages.isNotEmpty()) {
                item {
                    SectionLabel("RECENT IMAGES")
                    LazyRow(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(recentImages, key = { it.id }) { m ->
                            val img = remember(m.image) { decodeAvatar(m.image) }
                            if (img != null) {
                                Image(img, contentDescription = null, contentScale = ContentScale.Crop,
                                    modifier = Modifier.size(84.dp).clip(RoundedCornerShape(10.dp)).aspectRatio(1f))
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }

            // Members.
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    SectionLabel("MEMBERS (${g.members.size})")
                    Spacer(Modifier.weight(1f))
                    if (iAmAdmin) TextButton(onClick = { showAdd = true }) { Text("+ Add", color = TealDeep, fontWeight = FontWeight.Bold) }
                }
            }
            items(g.members, key = { it }) { memberUid ->
                MemberRow(g, memberUid, myUid, iAmOwner, iAmAdmin, actions)
            }

            // Danger zone.
            item {
                Spacer(Modifier.height(16.dp))
                OutlinedButton(
                    onClick = { if (iAmOwner) confirmDelete = true else actions.onLeave() },
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Danger),
                ) { Text(if (iAmOwner) "Delete group" else "Leave group") }
            }
        }
    }

    if (showAdd) {
        val addable = friends.filter { it.uid !in g.members }
        AddMemberDialog(addable, onDismiss = { showAdd = false }, onPick = { f -> actions.onAddMember(f); showAdd = false })
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete group?", fontWeight = FontWeight.Bold) },
            text = { Text("“${g.name}” and its chat will be permanently deleted for everyone. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; actions.onDelete() }) {
                    Text("Delete", color = Danger, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun MemberRow(g: Group, memberUid: String, myUid: String, iAmOwner: Boolean, iAmAdmin: Boolean, actions: GroupActions) {
    val isOwner = memberUid == g.owner
    val isAdmin = g.isAdmin(memberUid)
    val roleLabel = when { isOwner -> "Owner"; isAdmin -> "Admin"; else -> null }
    val canKick = iAmAdmin && !isOwner && memberUid != myUid   // admins kick anyone but the owner and themselves
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        AvatarCircle(photo = "", fallback = g.tagOf(memberUid), size = 36.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("@${g.tagOf(memberUid)}" + if (memberUid == myUid) " (you)" else "",
                fontSize = 14.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
            roleLabel?.let { Text(it, fontSize = 11.sp, color = TealDeep, fontWeight = FontWeight.Bold) }
        }
        if (iAmOwner && !isOwner) {   // owner-only: promote/demote
            TextButton(onClick = { actions.onSetAdmin(memberUid, !isAdmin) }) {
                Text(if (isAdmin) "Demote" else "Make admin", color = TealDeep, fontSize = 13.sp)
            }
        }
        if (canKick) TextButton(onClick = { actions.onKick(memberUid) }) { Text("Kick", color = Danger, fontSize = 13.sp) }
    }
}

@Composable
private fun AddMemberDialog(addable: List<UserHit>, onDismiss: () -> Unit, onPick: (UserHit) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a friend", fontWeight = FontWeight.Bold) },
        text = {
            if (addable.isEmpty()) {
                Text("All your friends are already in this group.", fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            } else {
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(addable, key = { it.uid }) { f ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onPick(f) }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AvatarCircle(photo = f.photo, fallback = f.tag, size = 34.dp)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text("@${f.tag}", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                                if (f.fullName.isNotBlank()) Text(f.fullName, fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                            }
                            Text("Add", color = TealDeep, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, fontSize = 12.sp, fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), modifier = Modifier.padding(top = 4.dp))
}
