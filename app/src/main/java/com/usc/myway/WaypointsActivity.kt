package com.usc.myway

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.usc.myway.ui.theme.MyWayTheme

private val Teal = Color(0xFF00C99D)
private val Danger = Color(0xFFEF4444)

class WaypointsActivity : ComponentActivity() {
    private val app get() = application as App

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MyWayTheme { WaypointsScreen() } }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun WaypointsScreen() {
        val locations = remember(app.dataVersion) { app.myLocations.toList() }
        var editTarget by remember { mutableStateOf<String?>(null) }
        var deleteTarget by remember { mutableStateOf<String?>(null) }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Saved Waypoints", fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = { finish() }) {
                            Text("←", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                )
            },
        ) { pad ->
            if (locations.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(pad).padding(32.dp), contentAlignment = Alignment.Center) {
                    Text("No waypoints saved yet.\nLong-press on the map to save a location.",
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                }
            } else {
                LazyColumn(Modifier.fillMaxSize().padding(pad).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { Spacer(Modifier.height(8.dp)) }
                    items(locations) { loc ->
                        val key = App.locationKey(loc.latitude, loc.longitude)
                        WaypointItem(
                            key = key,
                            onEdit = { editTarget = key },
                            onDelete = { deleteTarget = key },
                            onFocus = { focus(loc.latitude, loc.longitude) }
                        )
                    }
                    item { Spacer(Modifier.height(16.dp)) }
                }
            }
        }

        editTarget?.let { key -> EditWaypointDialog(key) { editTarget = null } }
        deleteTarget?.let { key -> DeleteWaypointDialog(key) { deleteTarget = null } }
    }

    @Composable
    private fun WaypointItem(key: String, onEdit: () -> Unit, onDelete: () -> Unit, onFocus: () -> Unit) {
        var menuOpen by remember { mutableStateOf(false) }
        val name = app.getLocationName(key)
        val note = app.locationNotes[key] ?: ""
        val collection = app.collections.firstOrNull { it.locationKeys.contains(key) }

        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth().clickable(onClick = onFocus)
        ) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(Teal.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                    Text("📍", fontSize = 20.sp)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(name.ifEmpty { "Unnamed location" }, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    if (note.isNotEmpty()) Text(note, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f), maxLines = 1)
                    if (collection != null) {
                        Text("${collection.icon} ${collection.name}", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Teal, modifier = Modifier.padding(top = 2.dp))
                    }
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Text("⋮", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text("Edit") }, onClick = { menuOpen = false; onEdit() })
                        DropdownMenuItem(text = { Text("Delete", color = Danger) }, onClick = { menuOpen = false; onDelete() })
                    }
                }
            }
        }
    }

    @Composable
    private fun EditWaypointDialog(key: String, onDismiss: () -> Unit) {
        var name by remember { mutableStateOf(app.getLocationName(key)) }
        var note by remember { mutableStateOf(app.locationNotes[key] ?: "") }
        val collections = app.collections
        val currentColl = collections.firstOrNull { it.locationKeys.contains(key) }
        var selectedCollId by remember { mutableStateOf(currentColl?.id) }
        var collMenuOpen by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Edit Waypoint") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
                    
                    Column {
                        OutlinedTextField(
                            value = note,
                            onValueChange = { note = it },
                            label = { Text("Note") },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            minLines = 2
                        )
                        Box(Modifier.padding(top = 4.dp)) {
                            EmojiPickerButton { emoji -> note += emoji }
                        }
                    }
                    
                    Column {
                        Text("Collection", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        Box(Modifier.padding(top = 4.dp)) {
                            val disp = collections.firstOrNull { it.id == selectedCollId }?.let { "${it.icon} ${it.name}" } ?: "None"
                            Surface(
                                Modifier.fillMaxWidth().clickable { collMenuOpen = true },
                                shape = RoundedCornerShape(12.dp),
                                border = ButtonDefaults.outlinedButtonBorder,
                                color = Color.Transparent
                            ) {
                                Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(disp)
                                    Text("▼", fontSize = 12.sp)
                                }
                            }
                            DropdownMenu(expanded = collMenuOpen, onDismissRequest = { collMenuOpen = false }, modifier = Modifier.fillMaxWidth(0.7f)) {
                                DropdownMenuItem(text = { Text("None") }, onClick = { selectedCollId = null; collMenuOpen = false })
                                collections.forEach { c ->
                                    DropdownMenuItem(text = { Text("${c.icon} ${c.name}") }, onClick = { selectedCollId = c.id; collMenuOpen = false })
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    app.saveLocationName(key, name.trim())
                    app.saveNote(key, note.trim())
                    app.setPinCollection(key, collections.firstOrNull { it.id == selectedCollId })
                    onDismiss()
                }) { Text("Save", color = Teal, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
        )
    }

    @Composable
    private fun DeleteWaypointDialog(key: String, onDismiss: () -> Unit) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Delete Waypoint") },
            text = { Text("Are you sure you want to remove this saved location?") },
            confirmButton = {
                TextButton(onClick = {
                    app.myLocations.firstOrNull { App.locationKey(it.latitude, it.longitude) == key }?.let { app.removeLocation(it) }
                    onDismiss()
                }) { Text("Delete", color = Danger, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
        )
    }

    private fun focus(lat: Double, lng: Double) {
        startActivity(Intent(this, MainActivity::class.java).apply {
            putExtra("focus_lat", lat); putExtra("focus_lng", lng)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        })
        finish()
    }
}
