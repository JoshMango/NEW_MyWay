// Voice/video call screen (LiveKit). One activity, three modes via intent: outgoing 1:1, incoming 1:1
// (ring → accept/decline), and group. Connection + signalling use the shared backend (Calls.kt); the
// media grid + controls use the LiveKit Compose components. Foreground-only, per the iOS handoff.
//
// Call-log chat messages are written from here (single-writer to avoid duplicates):
//   1:1  → the CALLER writes "Missed a call from @X · rang …" (never answered) or "Call ended · …".
//   group→ each client writes its own "started/joined/left"; whoever empties the room writes "Call ended · lasted …".
package com.usc.myway

import android.Manifest
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.FlipCameraAndroid
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.filled.VolumeDown
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.firebase.auth.FirebaseAuth
import com.usc.myway.ui.theme.MyWayTheme
import io.livekit.android.compose.local.RoomScope
import io.livekit.android.compose.state.rememberTracks
import io.livekit.android.compose.types.TrackReference
import io.livekit.android.compose.ui.VideoTrackView
import io.livekit.android.room.Room
import io.livekit.android.room.track.LocalVideoTrack
import io.livekit.android.room.track.Track
import kotlinx.coroutines.launch

private val Teal = Color(0xFF00C99D)
private val Danger = Color(0xFFEF4444)
private val CallBg = Color(0xFF111318)

class CallActivity : ComponentActivity() {
    companion object {
        fun dmOutgoing(ctx: Context, otherUid: String, otherTag: String, otherPhoto: String, video: Boolean): Intent =
            Intent(ctx, CallActivity::class.java).putExtra("mode", "dm_out")
                .putExtra("otherUid", otherUid).putExtra("otherTag", otherTag).putExtra("otherPhoto", otherPhoto)
                .putExtra("video", video)

        fun dmIncoming(ctx: Context, call: Calls.IncomingCall): Intent =
            Intent(ctx, CallActivity::class.java).putExtra("mode", "dm_in")
                .putExtra("otherUid", call.from).putExtra("otherTag", call.fromTag).putExtra("otherPhoto", call.fromPhoto)
                .putExtra("video", call.video).putExtra("room", call.callId)

        fun group(ctx: Context, gid: String, groupName: String, video: Boolean): Intent =
            Intent(ctx, CallActivity::class.java).putExtra("mode", "group")
                .putExtra("gid", gid).putExtra("groupName", groupName).putExtra("room", gid).putExtra("video", video)
    }

    private val auth by lazy { FirebaseAuth.getInstance() }
    private val myUid get() = auth.currentUser?.uid ?: ""
    private val app get() = application as App
    private val myTag by lazy { app.getUserTag(myUid).ifEmpty { "me" } }
    private val myPhoto by lazy { app.getUserPhoto(myUid) }

    private val mode by lazy { intent.getStringExtra("mode") ?: "dm_out" }
    private val initialVideo by lazy { intent.getBooleanExtra("video", false) }
    private val otherUid by lazy { intent.getStringExtra("otherUid") ?: "" }
    private val otherTag by lazy { intent.getStringExtra("otherTag") ?: "" }
    private val otherPhoto by lazy { intent.getStringExtra("otherPhoto") ?: "" }
    private val gid by lazy { intent.getStringExtra("gid") ?: "" }
    private val groupName by lazy { intent.getStringExtra("groupName") ?: "" }
    private val room by lazy {
        intent.getStringExtra("room")?.takeIf { it.isNotEmpty() } ?: Calls.pairId(myUid, otherUid)
    }

    // Local start fallback if the server timestamp hasn't landed yet; and once-only guards.
    private var localStart = System.currentTimeMillis()
    private var everActive = false
    private var startedAtMs: Long? = null
    private var logged = false
    private var sawCallDoc = false   // have we seen the call doc exist yet? (guards the startup race)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        volumeControlStream = AudioManager.STREAM_VOICE_CALL
        if (mode.startsWith("dm")) NotificationHub.activeCallId = room   // suppress re-ringing for this call
        setContent { MyWayTheme { CallScreen() } }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (NotificationHub.activeCallId == room) NotificationHub.activeCallId = null
    }

    /** 1:1 caller writes the call log exactly once (missed vs answered). */
    private fun logDmEnd() {
        if (mode != "dm_out" || logged) return
        logged = true
        val start = startedAtMs ?: localStart
        val dur = Calls.formatDuration(System.currentTimeMillis() - start)
        val text = if (everActive) "Call ended · $dur" else "Missed a call from @$myTag · rang $dur"
        PrivateMessages.postSystem(room, myUid, myTag, otherUid, otherTag, text)
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    @Composable
    private fun CallScreen() {
        var accepted by remember { mutableStateOf(mode != "dm_in") }   // incoming waits for the user to accept
        var granted by remember { mutableStateOf(false) }
        var token by remember { mutableStateOf<String?>(null) }
        var url by remember { mutableStateOf<String?>(null) }
        var status by remember { mutableStateOf(if (mode == "dm_out") "ringing" else "active") }

        val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
            // Mic is mandatory; camera only matters for a video call.
            if (res[Manifest.permission.RECORD_AUDIO] == true) granted = true
            else { toast("Microphone permission is needed to call"); finish() }
        }
        LaunchedEffect(accepted) {
            if (accepted) {
                val perms = buildList {
                    add(Manifest.permission.RECORD_AUDIO)
                    if (initialVideo) add(Manifest.permission.CAMERA)
                }
                permLauncher.launch(perms.toTypedArray())
            }
        }
        // Fetch a LiveKit token once the user has accepted and granted the mic.
        LaunchedEffect(accepted, granted) {
            if (accepted && granted && token == null) {
                Calls.fetchToken(room) { t, u ->
                    if (t != null && u != null) { token = t; url = u }
                    else { toast("Couldn't start the call"); finish() }
                }
            }
        }
        // 1:1 signalling: post the ringing doc (outgoing) and watch the call doc for accept / hang-up.
        LaunchedEffect(Unit) {
            if (mode == "dm_out") Calls.startCall(room, myUid, myTag, myPhoto, otherUid, otherTag, initialVideo)
        }
        DisposableEffect(Unit) {
            val reg = if (mode.startsWith("dm")) Calls.listenCall(room) { s, at ->
                if (s != null) {
                    sawCallDoc = true
                    if (s == "active") everActive = true
                    if (at != null) startedAtMs = at
                    status = s
                } else if (sawCallDoc) {
                    // We saw the call live and now it's deleted → the other side hung up / declined.
                    logDmEnd(); finish()
                }
                // Initial null — the doc isn't visible yet (local cache miss, or our own startCall write
                // hasn't landed). Don't tear down; wait for the real snapshot. (dm_in has a ring timeout.)
            } else null
            onDispose { reg?.remove() }
        }
        // Incoming ring has a hard cap: if it's never answered (and no live doc ever shows), stop ringing.
        if (mode == "dm_in") LaunchedEffect(Unit) {
            kotlinx.coroutines.delay(45_000)
            if (!accepted) finish()
        }

        when {
            mode == "dm_in" && !accepted ->
                IncomingRing(
                    onAccept = { Calls.accept(room); accepted = true },
                    onDecline = { Calls.end(room); finish() },
                )
            token != null && url != null ->
                RoomScope(url = url, token = token, audio = true, video = initialVideo, connect = true) { room ->
                    // Group join/leave + logs live with the connected room lifecycle.
                    if (mode == "group") DisposableEffect(Unit) {
                        Calls.joinGroup(gid, groupName, myUid) { started ->
                            Groups.postSystem(gid, "@$myTag ${if (started) "started a call" else "joined the call"}")
                        }
                        onDispose {
                            Calls.leaveGroup(gid, myUid) { startedAt ->
                                Groups.postSystem(gid, "@$myTag left the call")
                                if (startedAt != null)
                                    Groups.postSystem(gid, "Call ended · lasted ${Calls.formatDuration(System.currentTimeMillis() - startedAt)}")
                            }
                        }
                    }
                    InCall(room, ringing = mode == "dm_out" && status != "active")
                }
            else -> Connecting(if (mode == "dm_out") "Ringing…" else "Connecting…")
        }
    }

    // ── UI pieces ─────────────────────────────────────────────────────────────────

    @Composable
    private fun IncomingRing(onAccept: () -> Unit, onDecline: () -> Unit) {
        Column(Modifier.fillMaxSize().background(CallBg).padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            AvatarCircle(photo = otherPhoto, fallback = otherTag, size = 120.dp)
            Spacer(Modifier.height(20.dp))
            Text("@$otherTag", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text(if (initialVideo) "Incoming video call" else "Incoming call",
                color = Color.White.copy(alpha = 0.7f), fontSize = 15.sp)
            Spacer(Modifier.height(48.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(56.dp)) {
                RoundButton(Icons.Filled.CallEnd, Danger, "Decline", onDecline)
                RoundButton(Icons.Filled.Mic, Teal, "Accept", onAccept)
            }
        }
    }

    @Composable
    private fun Connecting(label: String) {
        Column(Modifier.fillMaxSize().background(CallBg), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center) {
            if (mode.startsWith("dm")) {
                AvatarCircle(photo = otherPhoto, fallback = otherTag, size = 110.dp)
                Spacer(Modifier.height(16.dp))
                Text("@$otherTag", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            } else {
                Text(groupName, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(10.dp))
            Text(label, color = Color.White.copy(alpha = 0.7f), fontSize = 15.sp)
        }
    }

    @Composable
    private fun InCall(room: Room, ringing: Boolean) {
        // One camera track (or placeholder) per participant, including me.
        val tracks by rememberTracks(
            sources = listOf(Track.Source.CAMERA),
            usePlaceholders = setOf(Track.Source.CAMERA),
        )
        Box(Modifier.fillMaxSize().background(CallBg)) {
            if (tracks.isEmpty()) {
                Connecting("Connecting…")
            } else {
                Column(Modifier.fillMaxSize()) {
                    tracks.chunked(2).forEach { rowTracks ->
                        Row(Modifier.weight(1f).fillMaxWidth()) {
                            rowTracks.forEach { ref ->
                                ParticipantTile(ref, Modifier.weight(1f).fillMaxHeight().padding(2.dp))
                            }
                        }
                    }
                }
            }
            if (ringing) {
                Box(Modifier.fillMaxSize().background(CallBg.copy(alpha = 0.85f)), contentAlignment = Alignment.Center) {
                    Connecting("Ringing…")
                }
            }
            Controls(room, Modifier.align(Alignment.BottomCenter).padding(bottom = 36.dp))
        }
    }

    @Composable
    private fun ParticipantTile(ref: TrackReference, modifier: Modifier) {
        Box(modifier.clip(RoundedCornerShape(12.dp)).background(Color.Black), contentAlignment = Alignment.Center) {
            if (ref.publication?.track != null) {
                VideoTrackView(trackReference = ref, modifier = Modifier.fillMaxSize())
            } else {
                // Camera off → live avatar for that uid (identity == uid, per the token contract).
                val id = ref.participant.identity?.value ?: ""
                LiveAvatar(id, id.ifBlank { "?" }, 72.dp)
            }
        }
    }

    @Composable
    private fun Controls(room: Room, modifier: Modifier) {
        val scope = rememberCoroutineScope()
        var muted by remember { mutableStateOf(false) }
        var camOn by remember { mutableStateOf(initialVideo) }
        var speaker by remember { mutableStateOf(initialVideo) }   // video calls default to loudspeaker

        LaunchedEffect(speaker) {
            @Suppress("DEPRECATION")
            (getSystemService(Context.AUDIO_SERVICE) as AudioManager).isSpeakerphoneOn = speaker
        }

        Row(modifier, horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundButton(if (muted) Icons.Filled.MicOff else Icons.Filled.Mic,
                if (muted) Color.DarkGray else Color(0xFF2A2E37), "Mute") {
                val next = !muted; muted = next
                scope.launch { room.localParticipant.setMicrophoneEnabled(!next) }
            }
            RoundButton(if (camOn) Icons.Filled.Videocam else Icons.Filled.VideocamOff,
                if (camOn) Teal else Color(0xFF2A2E37), "Camera") {
                val next = !camOn; camOn = next
                scope.launch { room.localParticipant.setCameraEnabled(next) }
            }
            RoundButton(Icons.Filled.FlipCameraAndroid, Color(0xFF2A2E37), "Flip") {
                scope.launch {
                    (room.localParticipant.getTrackPublication(Track.Source.CAMERA)?.track as? LocalVideoTrack)?.switchCamera()
                }
            }
            RoundButton(if (speaker) Icons.Filled.VolumeUp else Icons.Filled.VolumeDown,
                if (speaker) Teal else Color(0xFF2A2E37), "Speaker") { speaker = !speaker }
            RoundButton(Icons.Filled.CallEnd, Danger, "Hang up") { hangUp() }
        }
    }

    /** Leaving the composition triggers the group leave / 1:1 log via the effects above; just close. */
    private fun hangUp() {
        if (mode.startsWith("dm")) { logDmEnd(); Calls.end(room) }
        finish()
    }

    @Composable
    private fun RoundButton(icon: ImageVector, bg: Color, desc: String, onClick: () -> Unit) {
        Box(
            Modifier.size(60.dp).clip(CircleShape).background(bg).clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = desc, tint = Color.White, modifier = Modifier.size(28.dp))
        }
    }
}
