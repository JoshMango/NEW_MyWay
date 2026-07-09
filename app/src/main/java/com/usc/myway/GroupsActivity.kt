// My groups: list the circles I'm in and create new ones (adding friends by @tag).
package com.usc.myway

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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

private class GroupsState {
    var groups by mutableStateOf<List<Group>>(emptyList())
    var friends by mutableStateOf<List<UserHit>>(emptyList())
    var creating by mutableStateOf(false)
}

class GroupsActivity : ComponentActivity() {

    private val auth by lazy { FirebaseAuth.getInstance() }
    private val uid get() = auth.currentUser?.uid ?: ""
    private val myTag by lazy { (application as App).getUserTag(uid).ifEmpty { "me" } }
    private val s = GroupsState()
    private val listeners = mutableListOf<ListenerRegistration>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MyWayTheme {
                GroupsScreen(
                    s = s,
                    onBack = { finish() },
                    onOpen = { g ->
                        startActivity(Intent(this, GroupChatActivity::class.java)
                            .putExtra("gid", g.id).putExtra("name", g.name))
                    },
                    onCreate = { name, picked ->
                        s.creating = true
                        Groups.createGroup(uid, myTag, name, picked) { s.creating = false }
                    },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        listeners += Groups.listenMyGroups(uid) { s.groups = it }
        listeners += Friends.listenFriends(uid) { s.friends = it }
    }

    override fun onStop() {
        super.onStop()
        listeners.forEach { it.remove() }; listeners.clear()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupsScreen(
    s: GroupsState,
    onBack: () -> Unit,
    onOpen: (Group) -> Unit,
    onCreate: (String, List<UserHit>) -> Unit,
) {
    var showCreate by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Groups", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Text("←", fontSize = 22.sp, fontWeight = FontWeight.Bold) } },
                colors = TopAppBarDefaults.topAppBarColors(),
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreate = true }, containerColor = Teal) {
                Text("+", fontSize = 28.sp, color = Color.White)
            }
        },
    ) { pad ->
        if (s.groups.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(pad).padding(32.dp), contentAlignment = Alignment.Center) {
                Text("No groups yet. Tap + to start one with your friends.",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), fontSize = 15.sp)
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(pad).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(s.groups, key = { it.id }) { g -> GroupCard(g, g.tripActive, onOpen) }
            }
        }
    }

    if (showCreate) {
        CreateGroupDialog(
            friends = s.friends,
            creating = s.creating,
            onDismiss = { showCreate = false },
            onConfirm = { name, picked -> onCreate(name, picked); showCreate = false },
        )
    }
}

@Composable
private fun GroupCard(g: Group, live: Boolean, onOpen: (Group) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .clickable { onOpen(g) }.padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AvatarCircle(photo = g.photo, fallback = g.name, size = 46.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(g.name, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
                if (live) {
                    Spacer(Modifier.width(8.dp))
                    Row(
                        Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0xFFEF4444))
                            .padding(horizontal = 7.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) { Text("● LIVE", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White) }
                }
            }
            Text("${g.members.size} member${if (g.members.size == 1) "" else "s"}",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        }
        Text("›", fontSize = 24.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
    }
}

@Composable
private fun CreateGroupDialog(
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
                                AvatarCircle(photo = f.photo, fallback = f.tag, size = 32.dp)
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
