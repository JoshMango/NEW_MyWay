// Social hub: find people by @tag, send/accept friend requests, manage friends.
package com.usc.myway

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.ListenerRegistration
import com.usc.myway.ui.theme.MyWayTheme
import kotlinx.coroutines.delay

private val Teal = Color(0xFF00C99D)
private val TealDeep = Color(0xFF00A77D)
private val Danger = Color(0xFFEF4444)

/** All social state the activity owns; the UI reads it reactively. */
class FriendsState {
    var query by mutableStateOf("")
    var searching by mutableStateOf(false)
    var results by mutableStateOf<List<UserHit>>(emptyList())
    var incoming by mutableStateOf<List<FriendRequest>>(emptyList())
    var outgoing by mutableStateOf<List<FriendRequest>>(emptyList())
    var friends by mutableStateOf<List<UserHit>>(emptyList())
    var busy by mutableStateOf<Set<String>>(emptySet()) // uids/request-ids with an in-flight action
}

class FriendsActivity : ComponentActivity() {

    private val auth by lazy { FirebaseAuth.getInstance() }
    private val uid get() = auth.currentUser?.uid ?: ""
    private val myTag by lazy { (application as App).getUserTag(uid).ifEmpty { "me" } }
    private val s = FriendsState()

    private val listeners = mutableListOf<ListenerRegistration>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MyWayTheme { FriendsScreen(s, actions, onBack = { finish() }) } }
    }

    // Live listeners while the screen is visible; detach when it isn't.
    override fun onStart() {
        super.onStart()
        listeners += Friends.listenIncoming(uid) { s.incoming = it }
        listeners += Friends.listenOutgoing(uid) { s.outgoing = it }
        listeners += Friends.listenFriends(uid) { s.friends = it }
    }

    override fun onStop() {
        super.onStop()
        listeners.forEach { it.remove() }
        listeners.clear()
    }

    private fun busy(key: String, on: Boolean) {
        s.busy = if (on) s.busy + key else s.busy - key
    }

    private val actions = object : FriendsActions {
        override fun onSearch(q: String) {
            if (q.isBlank()) { s.results = emptyList(); s.searching = false; return }
            s.searching = true
            Friends.search(q, uid) { s.results = it; s.searching = false }
        }

        override fun onSend(hit: UserHit) {
            busy(hit.uid, true)
            Friends.sendRequest(uid, myTag, hit) { busy(hit.uid, false) }
        }

        override fun onAccept(req: FriendRequest) {
            busy(req.id, true)
            Friends.accept(req) { busy(req.id, false) }
        }

        override fun onDecline(req: FriendRequest) {
            busy(req.id, true)
            Friends.deleteRequest(req) { busy(req.id, false) }
        }

        override fun onCancel(req: FriendRequest) = onDecline(req)

        override fun onRemove(friend: UserHit) {
            busy(friend.uid, true)
            Friends.removeFriend(uid, friend.uid) { busy(friend.uid, false) }
        }
    }
}

interface FriendsActions {
    fun onSearch(q: String)
    fun onSend(hit: UserHit)
    fun onAccept(req: FriendRequest)
    fun onDecline(req: FriendRequest)
    fun onCancel(req: FriendRequest)
    fun onRemove(friend: UserHit)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FriendsScreen(s: FriendsState, actions: FriendsActions, onBack: () -> Unit) {
    var tab by remember { mutableStateOf(0) }
    val tabs = listOf("Find", "Requests", "Friends")

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Friends", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Text("←", fontSize = 22.sp, fontWeight = FontWeight.Bold) }
                },
                colors = TopAppBarDefaults.topAppBarColors(),
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            TabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.surface, contentColor = TealDeep) {
                tabs.forEachIndexed { i, t ->
                    val badge = if (i == 1 && s.incoming.isNotEmpty()) " (${s.incoming.size})" else ""
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t + badge, fontWeight = FontWeight.Medium) })
                }
            }
            when (tab) {
                0 -> FindTab(s, actions)
                1 -> RequestsTab(s, actions)
                else -> FriendsTab(s, actions)
            }
        }
    }
}

// ── Find tab ──────────────────────────────────────────────────────────────────
@Composable
private fun FindTab(s: FriendsState, actions: FriendsActions) {
    // Debounce the query so we don't fire a read on every keystroke.
    LaunchedEffect(s.query) {
        delay(350)
        actions.onSearch(s.query)
    }
    val friendUids = s.friends.map { it.uid }.toSet()
    val requestedUids = s.outgoing.map { it.toUid }.toSet()
    val incomingByFrom = s.incoming.associateBy { it.fromUid }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        OutlinedTextField(
            value = s.query,
            onValueChange = { s.query = it },
            label = { Text("Find by @tag") },
            prefix = { Text("@") },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        when {
            s.searching -> CenterHint("Searching…")
            s.query.isBlank() -> CenterHint("Type a friend's @tag to find them.")
            s.results.isEmpty() -> CenterHint("No one found for “${s.query}”.")
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(s.results, key = { it.uid }) { hit ->
                    val incoming = incomingByFrom[hit.uid]
                    UserRow(
                        hit = hit,
                        trailing = {
                            when {
                                hit.uid in friendUids -> Label("Friends", Teal)
                                incoming != null -> ActionButton("Accept", s.busy.contains(incoming.id)) { actions.onAccept(incoming) }
                                hit.uid in requestedUids -> Label("Requested", MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                                else -> ActionButton("Add", s.busy.contains(hit.uid)) { actions.onSend(hit) }
                            }
                        },
                    )
                }
            }
        }
    }
}

// ── Requests tab ────────────────────────────────────────────────────────────────
@Composable
private fun RequestsTab(s: FriendsState, actions: FriendsActions) {
    if (s.incoming.isEmpty() && s.outgoing.isEmpty()) { CenterHint("No pending requests."); return }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (s.incoming.isNotEmpty()) {
            item { SectionLabel("INCOMING") }
            items(s.incoming, key = { it.id }) { req ->
                UserRow(
                    hit = UserHit(req.fromUid, req.fromTag),
                    trailing = {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            ActionButton("Accept", s.busy.contains(req.id)) { actions.onAccept(req) }
                            OutlinedButton(onClick = { actions.onDecline(req) }, shape = RoundedCornerShape(12.dp)) { Text("Decline") }
                        }
                    },
                )
            }
        }
        if (s.outgoing.isNotEmpty()) {
            item { SectionLabel("SENT") }
            items(s.outgoing, key = { it.id }) { req ->
                UserRow(
                    hit = UserHit(req.toUid, req.toTag),
                    trailing = { OutlinedButton(onClick = { actions.onCancel(req) }, shape = RoundedCornerShape(12.dp)) { Text("Cancel") } },
                )
            }
        }
    }
}

// ── Friends tab ─────────────────────────────────────────────────────────────────
@Composable
private fun FriendsTab(s: FriendsState, actions: FriendsActions) {
    if (s.friends.isEmpty()) { CenterHint("No friends yet. Find people in the Find tab."); return }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(s.friends, key = { it.uid }) { f ->
            UserRow(
                hit = f,
                trailing = {
                    OutlinedButton(
                        onClick = { actions.onRemove(f) },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Danger),
                    ) { Text("Remove") }
                },
            )
        }
    }
}

// ── Shared bits ─────────────────────────────────────────────────────────────────
@Composable
private fun UserRow(hit: UserHit, trailing: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AvatarCircle(photo = hit.photo, fallback = hit.tag)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("@${hit.tag}", fontWeight = FontWeight.Medium, fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onSurface)
            if (hit.fullName.isNotBlank()) {
                Text(hit.fullName, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
        }
        trailing()
    }
}

@Composable
private fun ActionButton(label: String, busy: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = !busy, shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Teal)) {
        if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
        else Text(label, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Label(text: String, color: Color) {
    Text(text, color = color, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 8.dp))
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, fontSize = 11.sp, fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))
}

@Composable
private fun CenterHint(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), fontSize = 15.sp)
    }
}
