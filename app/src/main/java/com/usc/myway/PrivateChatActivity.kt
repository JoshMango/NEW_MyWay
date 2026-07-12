// Private 1-on-1 chat screen. Similar to GroupChat but uses the private_chats collection.
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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.ListenerRegistration
import com.usc.myway.ui.theme.MyWayTheme
import java.text.SimpleDateFormat
import java.util.*

private val Teal = Color(0xFF00C99D)
private val TealDeep = Color(0xFF00A77D)
private const val BURST_GAP_MS = 60 * 60 * 1000L

class PrivateChatActivity : ComponentActivity() {
    private val auth by lazy { FirebaseAuth.getInstance() }
    private val uid get() = auth.currentUser?.uid ?: ""
    private val myTag by lazy { (application as App).getUserTag(uid).ifEmpty { "me" } }
    
    private val chatId by lazy { intent.getStringExtra("chatId") ?: "" }
    private val otherUid by lazy { intent.getStringExtra("otherUid") ?: "" }
    private val otherTag by lazy { intent.getStringExtra("otherTag") ?: "User" }
    
    private var messages by mutableStateOf<List<GroupMessage>>(emptyList())
    private var otherPhoto by mutableStateOf("")
    private var listener: ListenerRegistration? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Profiles.fetchProfile(otherUid) { otherPhoto = it?.photo ?: "" }
        
        setContent {
            MyWayTheme {
                ChatScreen()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        listener = PrivateMessages.listenMessages(chatId) { messages = it }
    }

    override fun onStop() {
        super.onStop()
        listener?.remove()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun ChatScreen() {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AvatarCircle(photo = otherPhoto, fallback = otherTag, size = 34.dp)
                            Spacer(Modifier.width(10.dp))
                            Text("@$otherTag", fontWeight = FontWeight.Bold)
                        }
                    },
                    navigationIcon = { IconButton(onClick = { finish() }) { Text("←", fontSize = 22.sp, fontWeight = FontWeight.Bold) } }
                )
            }
        ) { pad ->
            Column(Modifier.fillMaxSize().padding(pad).imePadding()) {
                MessageList(Modifier.weight(1f))
                MessageInput()
            }
        }
    }

    @Composable
    private fun MessageList(modifier: Modifier) {
        val listState = rememberLazyListState()
        val photos = remember(otherPhoto) { mapOf(uid to (application as App).getUserPhoto(uid), otherUid to otherPhoto) }
        
        LaunchedEffect(messages.size) {
            if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
        }

        if (messages.isEmpty()) {
            Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Start the conversation 👋", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
        } else {
            LazyColumn(modifier.fillMaxSize().padding(horizontal = 12.dp), state = listState, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(messages, key = { _, m -> m.id }) { i, m ->
                    val prev = messages.getOrNull(i - 1)
                    val next = messages.getOrNull(i + 1)
                    Column {
                        if (prev == null || m.ts - prev.ts > BURST_GAP_MS) {
                            TimeSeparator(m.ts)
                        }
                        MessageBubble(
                            m = m,
                            mine = m.from == uid,
                            photo = photos[m.from] ?: "",
                            showAvatar = next == null || next.from != m.from
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun MessageBubble(m: GroupMessage, mine: Boolean, photo: String, showAvatar: Boolean) {
        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
            if (!mine) {
                Box(Modifier.size(28.dp)) { if (showAvatar) AvatarCircle(photo, m.fromTag, 28.dp) }
                Spacer(Modifier.width(6.dp))
            }
            Column(
                Modifier.widthIn(max = 280.dp).clip(RoundedCornerShape(16.dp))
                    .background(if (mine) Teal else MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = if (m.image.isNotEmpty()) 4.dp else 12.dp, vertical = if (m.image.isNotEmpty()) 4.dp else 8.dp)
            ) {
                if (m.image.isNotEmpty()) {
                    val img = remember(m.image) { decodeAvatar(m.image) }
                    if (img != null) {
                        Image(img, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.widthIn(max = 240.dp).heightIn(max = 280.dp).clip(RoundedCornerShape(12.dp)))
                    }
                } else {
                    Text(m.text, fontSize = 15.sp, color = if (mine) Color.White else MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }

    @Composable
    private fun MessageInput() {
        var text by remember { mutableStateOf("") }
        val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) {
                encodeImage(contentResolver, uri, 1024, 60)?.let {
                    PrivateMessages.sendImage(chatId, uid, myTag, otherUid, otherTag, it)
                }
            }
        }
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { pickImage.launch("image/*") }) { Text("📷", fontSize = 22.sp) }
            OutlinedTextField(
                value = text, onValueChange = { text = it },
                placeholder = { Text("Message") }, modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(24.dp), maxLines = 4
            )
            Spacer(Modifier.width(6.dp))
            Button(
                onClick = { if (text.isNotBlank()) { PrivateMessages.sendMessage(chatId, uid, myTag, otherUid, otherTag, text); text = "" } },
                enabled = text.isNotBlank(), shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Teal)
            ) { Text("Send", fontWeight = FontWeight.Bold) }
        }
    }

    @Composable
    private fun TimeSeparator(ts: Long) {
        val stamp = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(ts))
        Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
            Text(stamp, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f))
        }
    }
}
