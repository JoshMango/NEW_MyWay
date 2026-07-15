// Viewer for a member's live location share (opened from the live card in a group chat).
// Follows live_shares/{uid} in real time; shows the moving marker + remaining time, or an ended state.
package com.usc.myway

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberMarkerState
import com.usc.myway.ui.theme.MyWayTheme

class LiveLocationActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uid = intent.getStringExtra("uid") ?: ""
        val who = intent.getStringExtra("name") ?: "Member"
        setContent { MyWayTheme { LiveScreen(uid, who) { finish() } } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LiveScreen(uid: String, who: String, onBack: () -> Unit) {
    var state by remember { mutableStateOf<LiveShare.State?>(null) }
    var loading by remember { mutableStateOf(true) }
    DisposableEffect(uid) {
        val reg = LiveShare.listen(uid) { state = it; loading = false }
        onDispose { reg.remove() }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("$who · live", fontWeight = FontWeight.Bold) },
                navigationIcon = { TextButton(onClick = onBack) { Text("←", fontSize = 22.sp, fontWeight = FontWeight.Bold) } })
        },
    ) { pad ->
        val s = state
        val pos = s?.takeIf { it.active && it.lat != null && it.lng != null }?.let { LatLng(it.lat!!, it.lng!!) }
        Column(Modifier.fillMaxSize().padding(pad)) {
            when {
                loading -> Center("Loading…")
                pos == null -> Center("$who is no longer sharing their location.")
                else -> {
                    val cam = rememberCameraPositionState { position = CameraPosition.fromLatLngZoom(pos, 16f) }
                    val marker = rememberMarkerState(position = pos)
                    // Follow the live position as it updates.
                    marker.position = pos
                    val ctx = LocalContext.current
                    val dark = isSystemInDarkTheme()
                    // Their profile photo IS the marker (matches iOS), not a generic pin.
                    val icon = remember(s.photo, dark) { buildAvatarMarker(ctx, s.photo, s.tag, dark) }
                    GoogleMap(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        cameraPositionState = cam,
                        uiSettings = MapUiSettings(zoomControlsEnabled = false, mapToolbarEnabled = false),
                    ) { Marker(state = marker, title = who, icon = icon) }
                    Text("🔴  Live · ${minutesLeft(s.expiresAt)} min left", fontSize = 14.sp, fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(16.dp))
                }
            }
        }
    }
}

@Composable
private fun Center(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
    }
}

private fun minutesLeft(expiresAt: Long): Int =
    ((expiresAt - System.currentTimeMillis()) / 60000L).toInt().coerceAtLeast(0)
