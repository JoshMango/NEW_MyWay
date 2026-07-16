// Group chat + group info. Tap the group name → info sheet: photo (admins can change it),
// recent images, member roster with role controls, delete (owner, with warning) / leave.
// Members can send text and images. Live via Firestore snapshot listeners.
package com.usc.myway

import android.content.Intent
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.AddAPhoto
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.RadioButtonChecked
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.WifiTethering
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import com.canhub.cropper.CropImageContract
import com.google.android.libraries.places.api.Places
import com.google.android.libraries.places.api.net.PlacesClient
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.usc.myway.ui.theme.MyWayTheme
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

private val Teal = Color(0xFF00C99D)
private val TealDeep = Color(0xFF00A77D)
private val Danger = Color(0xFFEF4444)

/** Messages closer together than this share one timestamp. */
private const val BURST_GAP_MS = 60 * 60 * 1000L

/** "3:42 PM" for today, "Mar 4, 3:42 PM" otherwise. */
private fun stamp(ts: Long): String {
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = ts }
    val sameDay = now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
        now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
    return SimpleDateFormat(if (sameDay) "h:mm a" else "MMM d, h:mm a", Locale.getDefault()).format(ts)
}

private class ChatState {
    var group by mutableStateOf<Group?>(null)
    var messages by mutableStateOf<List<GroupMessage>>(emptyList())
    var friends by mutableStateOf<List<UserHit>>(emptyList())
    var tripMembers by mutableStateOf<List<Trip.Member>>(emptyList())
    var plan by mutableStateOf<Trip.TripPlan?>(null)   // shared objective queue (built while scheduling)
}

class GroupChatActivity : ComponentActivity() {

    private val auth by lazy { FirebaseAuth.getInstance() }
    private val uid get() = auth.currentUser?.uid ?: ""
    private val gid by lazy { intent.getStringExtra("gid") ?: "" }
    private val fallbackName by lazy { intent.getStringExtra("name") ?: "Group" }
    private val myTag by lazy { (application as App).getUserTag(uid).ifEmpty { "me" } }
    private val s = ChatState()
    private val listeners = mutableListOf<ListenerRegistration>()
    private val placesClient by lazy {
        if (!Places.isInitialized()) Places.initializeWithNewPlacesApiEnabled(applicationContext, BuildConfig.MAPS_API_KEY)
        Places.createClient(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MyWayTheme {
                ChatScreen(
                    s = s, myUid = uid, fallbackName = fallbackName, placesClient = placesClient,
                    onBack = { finish() },
                    onSend = { text -> Groups.sendMessage(gid, uid, myTag, text) },
                    onSendImage = { uri -> encode(uri, 1024, 60)?.let { Groups.sendImage(gid, uid, myTag, it) } },
                    onJoinTrip = { joinTrip() },
                    onScheduleTrip = { startAt, name ->
                        Trip.scheduleSession(gid, startAt) { err ->
                            if (err != null) toast("Couldn't schedule: $err")
                            else Trip.createPlan(gid, name, uid, myTag) { e -> if (e != null) toast("Couldn't create plan: $e") }
                        }
                    },
                    onCancelSchedule = { Trip.endSession(gid) { err -> if (err != null) toast("Couldn't cancel: $err") } },
                    onCreatePlan = { name -> Trip.createPlan(gid, name, uid, myTag) { e -> if (e != null) toast("Couldn't create plan: $e") } },
                    onAddPlanItem = { name, lat, lng -> Trip.addPlanItem(gid, name, lat, lng, uid, myTag) { e -> if (e != null) toast("Couldn't add: $e") } },
                    onTogglePlanItem = { id, done -> Trip.setItemFinished(gid, id, done, uid, myTag) {} },
                    onPausePlan = { paused -> Trip.setPlanPaused(gid, paused, uid, myTag) {} },
                    onLeaveTrip = { Trip.leave(uid) { err -> if (err != null) toast("Couldn't leave: $err") else Groups.postSystem(gid, "Joined the trip") } },
                    onEndTrip = { Trip.endSession(gid) { err -> if (err != null) toast("Couldn't end trip: $err") } },
                    onOpenPin = { m -> openSharedPin(m) },
                    onOpenLive = { m -> startActivity(Intent(this, LiveLocationActivity::class.java).apply {
                        putExtra("uid", m.liveFrom); putExtra("name", "@${m.fromTag}")
                    }) },
                    actions = groupActions,
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        NotificationHub.activeChatGid = gid          // don't notify for the chat I'm reading
        Notifier.clearMessages(this, gid)            // clear any pending notification for it
        listeners += Groups.listenMessages(gid) { s.messages = it; markRead() }
        listeners += Friends.listenFriends(uid) { s.friends = it }
        listeners += Trip.listenMembers(gid) { s.tripMembers = it }
        listeners += Trip.listenPlan(gid) { s.plan = it }
        listeners += Groups.listenGroup(gid) { g ->
            // Group deleted, or I'm no longer a member → close the screen.
            if (g == null || uid !in g.members) finish() else s.group = g
        }
    }

    /** I'm looking at the chat → my receipt sits on the newest message. */
    private fun markRead() {
        val ts = s.messages.lastOrNull()?.ts ?: return
        if (s.group?.reads?.get(uid) == ts) return
        Groups.markRead(gid, uid, ts)
    }

    override fun onStop() {
        super.onStop()
        if (NotificationHub.activeChatGid == gid) NotificationHub.activeChatGid = null
        listeners.forEach { it.remove() }; listeners.clear()
    }

    private fun encode(uri: Uri, maxDim: Int, quality: Int): String? =
        try { encodeImage(contentResolver, uri, maxDim, quality) } catch (_: Exception) { null }

    private fun openSharedPin(m: GroupMessage) {
        val lat = m.pinLat ?: return
        val lng = m.pinLng ?: return
        if (m.pinPlaceId.isNotEmpty()) {
            // A shared landmark → open its rich in-app place page on the map.
            startActivity(Intent(this, MainActivity::class.java).apply {
                putExtra("shared_placeId", m.pinPlaceId)
                putExtra("shared_lat", lat); putExtra("shared_lng", lng); putExtra("shared_name", m.pinName)
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            })
        } else {
            startActivity(Intent(this, SharedPinActivity::class.java).apply {
                putExtra("lat", lat); putExtra("lng", lng)
                putExtra("name", m.pinName); putExtra("note", m.pinNote)
            })
        }
    }

    private fun joinTrip() {
        val appRef = application as App
        // Join is the critical write; surface any error so a denied write isn't silent.
        Trip.join(uid, gid, myTag, appRef.getUserPhoto(uid), appRef.lastLat, appRef.lastLng) { err ->
            if (err != null) toast("Couldn't join: $err")
            else {
                TripLocationService.start(this, uid, s.group?.name ?: fallbackName)
                Groups.postSystem(gid, "@$myTag joined the trip")
                // The trip lives on the map — go there instead of leaving the user in the chat.
                startActivity(Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                })
            }
        }
        // Mark the session ongoing (best-effort; not needed if it's already active).
        if (s.group?.tripActive != true) Trip.startSession(gid) { err -> if (err != null) toast("Couldn't mark live: $err") }
    }

    private fun toast(msg: String) = android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show()

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
    placesClient: PlacesClient,
    onBack: () -> Unit,
    onSend: (String) -> Unit,
    onSendImage: (Uri) -> Unit,
    onJoinTrip: () -> Unit,
    onScheduleTrip: (startAtMillis: Long, name: String) -> Unit,
    onCancelSchedule: () -> Unit,
    onCreatePlan: (String) -> Unit,
    onAddPlanItem: (name: String, lat: Double, lng: Double) -> Unit,
    onTogglePlanItem: (itemId: String, finished: Boolean) -> Unit,
    onPausePlan: (Boolean) -> Unit,
    onLeaveTrip: () -> Unit,
    onEndTrip: () -> Unit,
    onOpenPin: (GroupMessage) -> Unit,
    onOpenLive: (GroupMessage) -> Unit,
    actions: GroupActions,
) {
    var showInfo by remember { mutableStateOf(false) }
    var fullImage by remember { mutableStateOf<String?>(null) }
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
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(),
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).imePadding()) {
            // iPhone-call-style banner — only while a trip is ongoing. Starting a trip lives in the info menu.
            if (g?.tripActive == true) TripBar(s.tripMembers, myUid, onJoinTrip, onLeaveTrip, onEndTrip)
            else g?.tripScheduledAt?.let { ScheduledTripBar(it) { showInfo = true } }
            MessageList(s.messages, myUid, g?.reads ?: emptyMap(), g?.tags ?: emptyMap(),
                onOpenPin, onOpenLive, { fullImage = it }, Modifier.weight(1f))
            MessageInput(onSend, onSendImage)
        }
    }

    if (showInfo && g != null) {
        GroupInfoSheet(
            g = g, myUid = myUid, messages = s.messages, friends = s.friends,
            plan = s.plan, placesClient = placesClient,
            onDismiss = { showInfo = false }, onStartTrip = onJoinTrip,
            onScheduleTrip = onScheduleTrip, onCancelSchedule = onCancelSchedule,
            onCreatePlan = onCreatePlan, onAddPlanItem = onAddPlanItem,
            onTogglePlanItem = onTogglePlanItem, onPausePlan = onPausePlan,
            actions = actions,
        )
    }
    fullImage?.let { FullscreenImageDialog(it) { fullImage = null } }
}

/** Slim banner shown in the chat while a trip is scheduled (not yet live). Tap → info sheet to manage it. */
@Composable
private fun ScheduledTripBar(startAtMillis: Long, onTap: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Teal.copy(alpha = 0.12f)).clickable(onClick = onTap)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Schedule, contentDescription = null, tint = TealDeep, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("Trip scheduled for ${stamp(startAtMillis)}", fontSize = 13.sp, fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface)
    }
}

/** Ongoing-trip banner (rendered only while a trip is active) — Join/Leave + End, like an iPhone call bar. */
@Composable
private fun TripBar(
    members: List<Trip.Member>,
    myUid: String,
    onJoin: () -> Unit,
    onLeave: () -> Unit,
    onEnd: () -> Unit,
) {
    val iAmLive = members.any { it.uid == myUid }
    var confirmEnd by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().background(Teal.copy(alpha = 0.10f)).padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.RadioButtonChecked, contentDescription = null, tint = Danger, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            if (members.isEmpty()) "Trip ongoing · nobody sharing yet" else "Trip ongoing · ${members.size} sharing location",
            modifier = Modifier.weight(1f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (iAmLive) {
            Button(onClick = onLeave, shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Danger)) { Text("Leave", fontWeight = FontWeight.Bold) }
        } else {
            Button(onClick = onJoin, shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Teal)) { Text("Join", fontWeight = FontWeight.Bold) }
        }
        TextButton(onClick = { confirmEnd = true }) { Text("End", color = Danger, fontWeight = FontWeight.Bold) }
    }

    if (confirmEnd) {
        AlertDialog(
            onDismissRequest = { confirmEnd = false },
            title = { Text("End trip?", fontWeight = FontWeight.Bold) },
            text = { Text("This ends the trip for everyone and deletes all shared pins and notes from the session. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = { confirmEnd = false; onEnd() }) { Text("End trip", color = Danger, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { confirmEnd = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun MessageList(messages: List<GroupMessage>, myUid: String,
                        reads: Map<String, Long>, tags: Map<String, String>,
                        onOpenPin: (GroupMessage) -> Unit, onOpenLive: (GroupMessage) -> Unit,
                        onOpenImage: (String) -> Unit, modifier: Modifier) {
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }
    if (messages.isEmpty()) {
        Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Text("No messages yet.", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        }
        return
    }
    // Everyone else's receipt hangs on the newest message they've seen — Messenger style.
    val receipts = remember(messages, reads) {
        val byMessage = HashMap<String, MutableList<String>>()
        for ((uid, ts) in reads) {
            if (uid == myUid) continue
            val seen = messages.lastOrNull { !it.system && it.ts <= ts } ?: continue
            byMessage.getOrPut(seen.id) { mutableListOf() }.add(uid)
        }
        byMessage
    }
    var selectedId by remember { mutableStateOf<String?>(null) }
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 12.dp), state = listState,
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        itemsIndexed(messages, key = { _, m -> m.id }) { i, m ->
            val prev = messages.getOrNull(i - 1)
            val next = messages.getOrNull(i + 1)
            Column {
                // Only stamp the start of a conversation burst, not every message.
                if (prev == null || m.ts - prev.ts > BURST_GAP_MS) TimeSeparator(m.ts)
                MessageBubble(
                    m, mine = m.from == myUid,
                    // Avatar only on the last bubble of a run, like Messenger.
                    showAvatar = next == null || next.from != m.from,
                    onClick = { selectedId = if (selectedId == m.id) null else m.id },
                    onOpenPin = onOpenPin, onOpenLive = onOpenLive, onOpenImage = onOpenImage,
                )
                if (selectedId == m.id && !m.system) MessageDetails(m, myUid, reads, tags, mine = m.from == myUid)
                receipts[m.id]?.let { seenBy -> ReadReceipts(seenBy, tags) }
            }
        }
    }
}

/** Centered "3:42 PM" chip above the first message of a burst. */
@Composable
private fun TimeSeparator(ts: Long) {
    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        Text(stamp(ts), fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f))
    }
}

/** Tapping a bubble reveals its exact time and who has read it. */
@Composable
private fun MessageDetails(m: GroupMessage, myUid: String, reads: Map<String, Long>,
                           tags: Map<String, String>, mine: Boolean) {
    val seenBy = reads.filter { (uid, ts) -> uid != m.from && uid != myUid && ts >= m.ts }.keys
    val seen = if (seenBy.isEmpty()) "Not seen yet"
    else "Seen by " + seenBy.joinToString { "@${tags[it] ?: "someone"}" }
    Box(Modifier.fillMaxWidth().padding(start = 34.dp, end = 2.dp, top = 2.dp),
        contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Text("${stamp(m.ts)} · $seen", fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
    }
}

/** Tiny avatars under the newest message each member has seen. */
@Composable
private fun ReadReceipts(uids: List<String>, tags: Map<String, String>) {
    Row(Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.End) {
        uids.forEach { uid ->
            Box(Modifier.padding(start = 2.dp)) {
                LiveAvatar(uid, tags[uid] ?: "?", 14.dp)
            }
        }
    }
}

@Composable
private fun MessageBubble(m: GroupMessage, mine: Boolean, showAvatar: Boolean,
                          onClick: () -> Unit,
                          onOpenPin: (GroupMessage) -> Unit, onOpenLive: (GroupMessage) -> Unit,
                          onOpenImage: (String) -> Unit) {
    if (m.system) {
        Box(Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
            Text(m.text, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
        }
        return
    }
    val isLive = m.liveFrom.isNotEmpty()
    val isPin = m.pinLat != null && m.pinLng != null
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        if (!mine) {
            // Reserve the gutter even on stacked bubbles so they stay aligned.
            Box(Modifier.size(28.dp)) { if (showAvatar) LiveAvatar(m.from, m.fromTag, 28.dp) }
            Spacer(Modifier.width(6.dp))
        }
        val isImage = m.image.isNotEmpty()
        Column(
            Modifier.widthIn(max = 280.dp).clip(RoundedCornerShape(16.dp))
                // Photos render on their own with no coloured bubble behind them (that teal box was the bug).
                .background(if (isImage) Color.Transparent else if (mine) Teal else MaterialTheme.colorScheme.surfaceVariant)
                // Pin/live cards navigate, images open full-screen, everything else toggles its time + seen-by line.
                .clickable {
                    when {
                        isPin -> onOpenPin(m); isLive -> onOpenLive(m)
                        isImage -> onOpenImage(m.image); else -> onClick()
                    }
                }
                .padding(horizontal = if (isImage) 0.dp else 12.dp, vertical = if (isImage) 0.dp else 8.dp),
        ) {
            if (!mine) Text("@${m.fromTag}", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TealDeep,
                modifier = Modifier.padding(horizontal = if (m.image.isNotEmpty()) 8.dp else 0.dp))
            when {
                isLive -> {
                    val onCard = if (mine) Color.White else MaterialTheme.colorScheme.onSurface
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.RadioButtonChecked, contentDescription = null, tint = if (mine) Color.White else Danger, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Live location", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = onCard)
                    }
                    Text("Tap to follow on map", fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                        color = if (mine) Color.White.copy(alpha = 0.85f) else TealDeep, modifier = Modifier.padding(top = 6.dp))
                }
                isPin -> {
                    val onCard = if (mine) Color.White else MaterialTheme.colorScheme.onSurface
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Place, contentDescription = null, tint = if (mine) Color.White else TealDeep, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(m.pinName.ifEmpty { "Shared location" }, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = onCard)
                    }
                    if (m.pinNote.isNotEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                            Icon(Icons.Outlined.Description, contentDescription = null, tint = onCard.copy(alpha = 0.85f), modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(m.pinNote, fontSize = 13.sp, color = onCard.copy(alpha = 0.85f))
                        }
                    }
                    Text("Tap to view on map", fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                        color = if (mine) Color.White.copy(alpha = 0.85f) else TealDeep, modifier = Modifier.padding(top = 6.dp))
                }
                m.image.isNotEmpty() -> {
                    val img = remember(m.image) { decodeAvatar(m.image) }
                    if (img != null) {
                        Image(img, contentDescription = "Shared image", contentScale = ContentScale.Fit,
                            modifier = Modifier.widthIn(max = 240.dp).heightIn(max = 280.dp).clip(RoundedCornerShape(12.dp)))
                    }
                }
                else -> Text(m.text, fontSize = 15.sp, color = if (mine) Color.White else MaterialTheme.colorScheme.onSurface)
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
        IconButton(onClick = { pickImage.launch("image/*") }) { 
            Icon(Icons.Outlined.AddAPhoto, contentDescription = "Send Photo", tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        }
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
    plan: Trip.TripPlan?,
    placesClient: PlacesClient,
    onDismiss: () -> Unit,
    onStartTrip: () -> Unit,
    onScheduleTrip: (startAtMillis: Long, name: String) -> Unit,
    onCancelSchedule: () -> Unit,
    onCreatePlan: (String) -> Unit,
    onAddPlanItem: (name: String, lat: Double, lng: Double) -> Unit,
    onTogglePlanItem: (itemId: String, finished: Boolean) -> Unit,
    onPausePlan: (Boolean) -> Unit,
    actions: GroupActions,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showAdd by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var showSchedule by remember { mutableStateOf(false) }   // scheduling dialog
    var showQueue by remember { mutableStateOf(false) }      // plan/queue sheet (activities)
    val iAmOwner = myUid == g.owner
    val iAmAdmin = g.isAdmin(myUid)
    val recentImages = remember(messages) { messages.filter { it.image.isNotEmpty() }.takeLast(12).reversed() }

    val pickPhoto = rememberLauncherForActivityResult(CropImageContract()) { r ->
        r.uriContent?.takeIf { r.isSuccessful }?.let { actions.onSetGroupPhoto(it) }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            // Header: group photo + name.
            item {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    AvatarCircle(photo = g.photo, fallback = g.name, size = 96.dp)
                    if (iAmAdmin) {
                        TextButton(onClick = { pickPhoto.launch(avatarCropOptions()) }) {
                            Text(if (g.photo.isBlank()) "Add group photo" else "Change photo", color = TealDeep)
                        }
                    } else Spacer(Modifier.height(8.dp))
                    Text(g.name, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.height(16.dp))
                }
            }

            // Trip: start it now or schedule it here; once ongoing, Join/Leave/End live in the chat banner.
            item {
                when {
                    g.tripActive -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Icon(Icons.Outlined.RadioButtonChecked, contentDescription = null, tint = Danger, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Trip in progress — join or end it from the chat.",
                            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                    }
                    g.tripScheduledAt != null -> Column(Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Schedule, contentDescription = null, tint = TealDeep, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Trip scheduled for ${stamp(g.tripScheduledAt)}", fontSize = 14.sp,
                                fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                        }
                        Text("The group is notified a day and 15 min before.", fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), modifier = Modifier.padding(top = 2.dp))
                        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { showQueue = true }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) {
                                Text("Activities" + (plan?.items?.size?.takeIf { it > 0 }?.let { " ($it)" } ?: ""))
                            }
                            OutlinedButton(onClick = { onCancelSchedule(); onDismiss() }, modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp)) { Text("Cancel", color = Danger) }
                        }
                    }
                    else -> Button(
                        onClick = { showSchedule = true },
                        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Teal),
                    ) {
                        Icon(Icons.Outlined.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Start Trip", fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.height(16.dp))
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

    if (showSchedule) {
        ScheduleTripDialog(
            onDismiss = { showSchedule = false },
            onStartNow = { showSchedule = false; onStartTrip(); onDismiss() },
            onSchedule = { startAt, name -> showSchedule = false; onScheduleTrip(startAt, name); showQueue = true },
        )
    }

    // Activities (the shared queue) — used to build the plan after scheduling, or edit it later.
    if (showQueue) {
        PlanSheet(
            plan = plan, placesClient = placesClient,
            onCreate = onCreatePlan, onAddItem = onAddPlanItem,
            onToggle = onTogglePlanItem, onPause = onPausePlan,
            onDismiss = { showQueue = false },
        )
    }
}

/**
 * Schedule (or immediately start) a trip. Time defaults to now; picking a future date/time schedules it,
 * a time in the past is rejected. Uses the platform date/time pickers (no extra deps).
 */
@Composable
private fun ScheduleTripDialog(
    onDismiss: () -> Unit,
    onStartNow: () -> Unit,
    onSchedule: (startAtMillis: Long, name: String) -> Unit,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var name by remember { mutableStateOf("") }
    var startMs by remember { mutableStateOf(System.currentTimeMillis()) }   // chosen start; defaults to now
    val isFuture = startMs > System.currentTimeMillis() + 60_000L            // >1 min out ⇒ scheduled, else "now"

    fun pickDate() {
        val c = Calendar.getInstance().apply { timeInMillis = startMs }
        android.app.DatePickerDialog(ctx, { _, y, mo, d ->
            c.set(y, mo, d)
            android.app.TimePickerDialog(ctx, { _, h, mi ->
                c.set(Calendar.HOUR_OF_DAY, h); c.set(Calendar.MINUTE, mi); c.set(Calendar.SECOND, 0)
                startMs = c.timeInMillis
            }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), false).show()
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH))
            .apply { datePicker.minDate = System.currentTimeMillis() }.show()
    }

    val inPast = startMs < System.currentTimeMillis() - 60_000L
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Start a trip", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Trip name") },
                    singleLine = true, shape = RoundedCornerShape(12.dp))
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = { pickDate() }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                    Icon(Icons.Outlined.Schedule, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (isFuture) stamp(startMs) else "Now — tap to schedule for later")
                }
                if (inPast) Text("That time is in the past.", color = Danger, fontSize = 12.sp,
                    modifier = Modifier.padding(top = 6.dp))
            }
        },
        confirmButton = {
            TextButton(
                enabled = !inPast,
                onClick = { if (isFuture) onSchedule(startMs, name.trim().ifEmpty { "Trip plan" }) else onStartNow() },
            ) { Text(if (isFuture) "Schedule" else "Start now", fontWeight = FontWeight.Bold, color = Teal) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun MemberRow(g: Group, memberUid: String, myUid: String, iAmOwner: Boolean, iAmAdmin: Boolean, actions: GroupActions) {
    val isOwner = memberUid == g.owner
    val isAdmin = g.isAdmin(memberUid)
    val roleLabel = when { isOwner -> "Owner"; isAdmin -> "Admin"; else -> null }
    val canKick = iAmAdmin && !isOwner && memberUid != myUid   // admins kick anyone but the owner and themselves
    var showCard by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        // Tap the person → their profile card.
        Row(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).clickable { showCard = true },
            verticalAlignment = Alignment.CenterVertically) {
            LiveAvatar(memberUid, g.tagOf(memberUid), 36.dp)
            Spacer(Modifier.width(10.dp))
            Column {
                Text("@${g.tagOf(memberUid)}" + if (memberUid == myUid) " (you)" else "",
                    fontSize = 14.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                roleLabel?.let { Text(it, fontSize = 11.sp, color = TealDeep, fontWeight = FontWeight.Bold) }
            }
        }
        if (iAmOwner && !isOwner) {   // owner-only: promote/demote
            TextButton(onClick = { actions.onSetAdmin(memberUid, !isAdmin) }) {
                Text(if (isAdmin) "Demote" else "Make admin", color = TealDeep, fontSize = 13.sp)
            }
        }
        if (canKick) TextButton(onClick = { actions.onKick(memberUid) }) { Text("Kick", color = Danger, fontSize = 13.sp) }
    }
    if (showCard) ProfileCardDialog(memberUid, g.tagOf(memberUid)) { showCard = false }
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
                            LiveAvatar(f.uid, f.tag, 34.dp)
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
