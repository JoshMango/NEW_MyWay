// Lists all 1-on-1 private conversations. Tap a chat to open it.
package com.usc.myway

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.ListenerRegistration
import com.usc.myway.ui.theme.MyWayTheme

private val TealDeep = Color(0xFF00A77D)

class MessagesActivity : ComponentActivity() {
    private val auth by lazy { FirebaseAuth.getInstance() }
    private val uid get() = auth.currentUser?.uid ?: ""
    private var chats by mutableStateOf<List<PrivateChat>>(emptyList())
    private var listener: ListenerRegistration? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MyWayTheme { MessagesScreen() } }
    }

    override fun onStart() {
        super.onStart()
        listener = PrivateMessages.listenMyChats(uid) { chats = it }
    }

    override fun onStop() {
        super.onStop()
        listener?.remove()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun MessagesScreen() {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Messages", fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = { finish() }) {
                            Text("←", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                )
            }
        ) { pad ->
            if (chats.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(pad).padding(32.dp), contentAlignment = Alignment.Center) {
                    Text("No messages yet.\nStart a chat from your Friends list.",
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        modifier = Modifier.align(Alignment.Center))
                }
            } else {
                LazyColumn(Modifier.fillMaxSize().padding(pad).padding(horizontal = 12.dp)) {
                    items(chats) { chat ->
                        ChatRow(chat)
                    }
                }
            }
        }
    }

    @Composable
    private fun ChatRow(chat: PrivateChat) {
        val otherTag = chat.otherTag(uid)
        val otherUid = chat.otherUid(uid)
        var otherPhoto by remember { mutableStateOf("") }
        
        LaunchedEffect(otherUid) {
            Profiles.fetchProfile(otherUid) { otherPhoto = it?.photo ?: "" }
        }

        Surface(
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().clickable { openChat(chat) }.padding(vertical = 4.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                AvatarCircle(photo = otherPhoto, fallback = otherTag, size = 48.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("@$otherTag", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    if (chat.lastMsg.isNotEmpty()) {
                        Text(chat.lastMsg, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f), maxLines = 1)
                    }
                }
            }
        }
    }

    private fun openChat(chat: PrivateChat) {
        val otherUid = chat.otherUid(uid)
        val otherTag = chat.otherTag(uid)
        startActivity(Intent(this, PrivateChatActivity::class.java).apply {
            putExtra("chatId", chat.id)
            putExtra("otherUid", otherUid)
            putExtra("otherTag", otherTag)
        })
    }
}
