// Lightweight collections viewer: lists your collections, expand one to see its pins,
// tap a pin to focus it back on the map. (Replaces the collections tab of the old saved-locations screen.)
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.usc.myway.ui.theme.MyWayTheme

private val Teal = Color(0xFF00C99D)
private val TealDeep = Color(0xFF00A77D)
private val Danger = Color(0xFFEF4444)

class CollectionsActivity : ComponentActivity() {
    private val app get() = application as App
    private val uid get() = FirebaseAuth.getInstance().currentUser?.uid ?: ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MyWayTheme { CollectionsScreen() } }
    }

    /** Broadcast a collection's pins/notes to the current trip; members get a modal to add them. */
    private fun shareToTrip(gid: String, c: Collection) {
        val pins = c.locationKeys.mapNotNull { key ->
            val parts = key.split(",")
            val lat = parts.getOrNull(0)?.toDoubleOrNull() ?: return@mapNotNull null
            val lng = parts.getOrNull(1)?.toDoubleOrNull() ?: return@mapNotNull null
            Trip.OfferPin(lat, lng, app.getLocationName(key), app.locationNotes[key] ?: "")
        }
        if (pins.isEmpty()) { toast("That collection is empty"); return }
        Trip.shareCollection(gid, uid, app.getUserTag(uid), app.getUserPhoto(uid), c.name, pins) { err ->
            toast(err ?: "Shared “${c.name}” to the trip")
        }
    }

    private fun toast(msg: String) = android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()

    private fun focus(lat: Double, lng: Double) {
        startActivity(Intent(this, MainActivity::class.java).apply {
            putExtra("focus_lat", lat); putExtra("focus_lng", lng)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        })
        finish()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun CollectionsScreen() {
        var version by remember { mutableIntStateOf(0) }
        val collections = remember(version) { app.collections.toList() }
        var expanded by remember { mutableStateOf<Collection?>(null) }
        var tripGid by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(Unit) { Trip.currentTrip(uid) { tripGid = it } }

        Scaffold(
            topBar = {
                TopAppBar(title = { Text("Collections", fontWeight = FontWeight.Bold) },
                    navigationIcon = { TextButton(onClick = { finish() }) { Text("←", fontSize = 22.sp, fontWeight = FontWeight.Bold) } })
            },
        ) { pad ->
            if (collections.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(pad).padding(32.dp), contentAlignment = Alignment.Center) {
                    Text("No collections yet.\nAdd a pin to a collection from the map.",
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                }
                return@Scaffold
            }
            LazyColumn(Modifier.fillMaxSize().padding(pad).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(collections) { c ->
                    CollectionCard(
                        c = c,
                        open = expanded == c,
                        onToggle = { expanded = if (expanded == c) null else c },
                        onOpenPin = { lat, lng -> focus(lat, lng) },
                        onDelete = { app.removeCollection(c); expanded = null; version++ },
                        onShareToTrip = tripGid?.let { gid -> { shareToTrip(gid, c) } },
                    )
                }
            }
        }
    }

    @Composable
    private fun CollectionCard(c: Collection, open: Boolean, onToggle: () -> Unit,
                               onOpenPin: (Double, Double) -> Unit, onDelete: () -> Unit,
                               onShareToTrip: (() -> Unit)?) {
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle)) {
                    Text(c.icon, fontSize = 22.sp, modifier = Modifier.padding(end = 10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(c.name, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                        Text("${c.locationKeys.size} ${if (c.locationKeys.size == 1) "place" else "places"}",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    }
                    Text(if (open) "▲" else "▼", color = TealDeep, fontSize = 13.sp)
                }
                if (open) {
                    if (c.locationKeys.isEmpty()) {
                        Text("Empty — add pins from the map.", fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            modifier = Modifier.padding(top = 10.dp, start = 4.dp))
                    } else c.locationKeys.forEach { key ->
                        val parts = key.split(",")
                        val lat = parts.getOrNull(0)?.toDoubleOrNull()
                        val lng = parts.getOrNull(1)?.toDoubleOrNull()
                        if (lat == null || lng == null) return@forEach
                        val title = app.getLocationName(key).ifEmpty { app.locationNotes[key]?.ifEmpty { null } ?: key }
                        Row(Modifier.fillMaxWidth().clickable { onOpenPin(lat, lng) }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text("📍", fontSize = 14.sp, modifier = Modifier.padding(end = 8.dp))
                            Text(title, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                            Text("View", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TealDeep)
                        }
                    }
                    if (onShareToTrip != null) {
                        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onShareToTrip)
                            .background(Teal.copy(alpha = 0.12f)).padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                            Text("📤  Share to trip", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TealDeep)
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onDelete)
                        .padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                        Text("🗑️  Delete collection", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Danger)
                    }
                }
            }
        }
    }
}
