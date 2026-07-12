// Lightweight collections viewer: lists your collections, expand one to see its pins,
// tap a pin to focus it back on the map.
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
import androidx.compose.foundation.shape.CircleShape
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
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.ListenerRegistration
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
        val collections = remember(app.dataVersion) { app.collections.toList() }
        var expandedId by remember { mutableStateOf<String?>(null) }
        var tripGid by remember { mutableStateOf<String?>(null) }
        
        var showCreateDialog by remember { mutableStateOf(false) }
        var editCollTarget by remember { mutableStateOf<Collection?>(null) }
        var deleteCollTarget by remember { mutableStateOf<Collection?>(null) }
        
        var addWaypointTarget by remember { mutableStateOf<Collection?>(null) }
        var editWaypointKey by remember { mutableStateOf<String?>(null) }
        var removeWaypointTarget by remember { mutableStateOf<Pair<String, Collection>?>(null) }

        LaunchedEffect(Unit) { Trip.currentTrip(uid) { tripGid = it } }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Collections", fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = { finish() }) {
                            Text("←", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                )
            },
            floatingActionButton = {
                FloatingActionButton(
                    onClick = { showCreateDialog = true },
                    containerColor = Teal,
                    contentColor = Color.White,
                    shape = CircleShape,
                    modifier = Modifier.padding(bottom = 16.dp, end = 8.dp)
                ) {
                    Text("+", fontSize = 28.sp, fontWeight = FontWeight.Light)
                }
            }
        ) { pad ->
            if (collections.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(pad).padding(32.dp), contentAlignment = Alignment.Center) {
                    Text("No collections yet.\nTap the + button to create one.",
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                }
            } else {
                LazyColumn(Modifier.fillMaxSize().padding(pad).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(collections) { c ->
                        CollectionCard(
                            c = c,
                            open = expandedId == c.id,
                            onToggle = { expandedId = if (expandedId == c.id) null else c.id },
                            onOpenPin = { lat, lng -> focus(lat, lng) },
                            onEditColl = { editCollTarget = c },
                            onDeleteColl = { deleteCollTarget = c },
                            onAddWaypoint = { addWaypointTarget = c },
                            onEditWaypoint = { editWaypointKey = it },
                            onRemoveWaypoint = { key -> removeWaypointTarget = key to c },
                            onShareToTrip = tripGid?.let { gid -> { shareToTrip(gid, c) } },
                        )
                    }
                    item { Spacer(Modifier.height(80.dp)) }
                }
            }
        }

        if (showCreateDialog) EditCollectionDialog(null) { showCreateDialog = false }
        editCollTarget?.let { c -> EditCollectionDialog(c) { editCollTarget = null } }
        deleteCollTarget?.let { c -> DeleteCollectionDialog(c) { deleteCollTarget = null } }
        addWaypointTarget?.let { c -> AddWaypointToCollectionDialog(c) { addWaypointTarget = null } }
        editWaypointKey?.let { key -> EditWaypointDialog(key) { editWaypointKey = null } }
        removeWaypointTarget?.let { (key, coll) -> RemoveWaypointDialog(key, coll) { removeWaypointTarget = null } }
    }

    @Composable
    private fun CollectionCard(
        c: Collection, open: Boolean, onToggle: () -> Unit,
        onOpenPin: (Double, Double) -> Unit, onEditColl: () -> Unit,
        onDeleteColl: () -> Unit, onAddWaypoint: () -> Unit,
        onEditWaypoint: (String) -> Unit, onRemoveWaypoint: (String) -> Unit,
        onShareToTrip: (() -> Unit)?
    ) {
        var menuOpen by remember { mutableStateOf(false) }

        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface, // Changed from surfaceVariant to surface (White)
            shadowElevation = 2.dp, // Added shadow to make it pop against the background
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.weight(1f).clickable(onClick = onToggle), verticalAlignment = Alignment.CenterVertically) {
                        Text(c.icon, fontSize = 22.sp, modifier = Modifier.padding(end = 10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(c.name, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                            Text("${c.locationKeys.size} ${if (c.locationKeys.size == 1) "place" else "places"}",
                                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        }
                        Text(if (open) "▲" else "▼", color = TealDeep, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 8.dp))
                    }
                    
                    Box {
                        IconButton(onClick = { menuOpen = true }) { Text("⋮", fontSize = 22.sp, fontWeight = FontWeight.Bold) }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(text = { Text("Edit Collection") }, onClick = { menuOpen = false; onEditColl() })
                            DropdownMenuItem(text = { Text("Delete Collection", color = Danger) }, onClick = { menuOpen = false; onDeleteColl() })
                        }
                    }
                }
                if (open) {
                    Spacer(Modifier.height(8.dp))
                    
                    // Add Waypoint Item Button
                    Row(
                        Modifier.fillMaxWidth().clickable { onAddWaypoint() }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(Modifier.size(24.dp).clip(CircleShape).background(Teal.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                            Text("+", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TealDeep, modifier = Modifier.offset(y = (-1).dp))
                        }
                        Spacer(Modifier.width(10.dp))
                        Text("Add waypoint item", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TealDeep)
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))

                    if (c.locationKeys.isEmpty()) {
                        Text("Empty — add pins from the map or use the button above.", fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            modifier = Modifier.padding(top = 8.dp, start = 4.dp))
                    } else c.locationKeys.forEach { key ->
                        val parts = key.split(",")
                        val lat = parts.getOrNull(0)?.toDoubleOrNull()
                        val lng = parts.getOrNull(1)?.toDoubleOrNull()
                        if (lat == null || lng == null) return@forEach
                        
                        WaypointItemRow(key, lat, lng, onOpenPin, { onEditWaypoint(key) }, { onRemoveWaypoint(key) })
                    }

                    if (onShareToTrip != null) {
                        Spacer(Modifier.height(12.dp))
                        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onShareToTrip)
                            .background(Teal.copy(alpha = 0.12f)).padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                            Text("📤  Share collection to trip", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TealDeep)
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun WaypointItemRow(key: String, lat: Double, lng: Double, onOpenPin: (Double, Double) -> Unit, onEdit: () -> Unit, onRemove: () -> Unit) {
        var menuOpen by remember { mutableStateOf(false) }
        val title = app.getLocationName(key).ifEmpty { app.locationNotes[key]?.ifEmpty { null } ?: key }

        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).clickable { onOpenPin(lat, lng) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("📍", fontSize = 14.sp, modifier = Modifier.padding(end = 8.dp))
                Text(title, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f), maxLines = 1)
            }
            Box {
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(32.dp)) {
                    Text("⋮", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("View on map") }, onClick = { menuOpen = false; onOpenPin(lat, lng) })
                    DropdownMenuItem(text = { Text("Edit waypoint") }, onClick = { menuOpen = false; onEdit() })
                    DropdownMenuItem(text = { Text("Remove from collection", color = Danger) }, onClick = { menuOpen = false; onRemove() })
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
                    OutlinedTextField(note, { note = it }, label = { Text("Note") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), minLines = 2)
                    Column {
                        Text("Collection", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        Box(Modifier.padding(top = 4.dp)) {
                            val disp = collections.firstOrNull { it.id == selectedCollId }?.let { "${it.icon} ${it.name}" } ?: "None"
                            Surface(Modifier.fillMaxWidth().clickable { collMenuOpen = true }, shape = RoundedCornerShape(12.dp), border = ButtonDefaults.outlinedButtonBorder, color = Color.Transparent) {
                                Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(disp); Text("▼", fontSize = 12.sp)
                                }
                            }
                            DropdownMenu(expanded = collMenuOpen, onDismissRequest = { collMenuOpen = false }, modifier = Modifier.fillMaxWidth(0.7f)) {
                                DropdownMenuItem(text = { Text("None") }, onClick = { selectedCollId = null; collMenuOpen = false })
                                collections.forEach { c -> DropdownMenuItem(text = { Text("${c.icon} ${c.name}") }, onClick = { selectedCollId = c.id; collMenuOpen = false }) }
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
    private fun RemoveWaypointDialog(key: String, c: Collection, onDismiss: () -> Unit) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Remove from Collection") },
            text = { Text("Remove this waypoint from “${c.name}”? it will still be in your main Waypoints list.") },
            confirmButton = {
                TextButton(onClick = {
                    app.setPinCollection(key, null)
                    onDismiss()
                }) { Text("Remove", color = Danger, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
        )
    }

    @Composable
    private fun AddWaypointToCollectionDialog(c: Collection, onDismiss: () -> Unit) {
        val waypoints = remember(app.dataVersion) { app.myLocations.toList() }
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Add Waypoint to ${c.name}") },
            text = {
                val available = waypoints.filter { loc -> !c.locationKeys.contains(App.locationKey(loc.latitude, loc.longitude)) }
                if (available.isEmpty()) { Text("No other waypoints available to add.") } else {
                    LazyColumn(Modifier.heightIn(max = 400.dp)) {
                        items(available) { loc ->
                            val key = App.locationKey(loc.latitude, loc.longitude)
                            val otherColl = app.collections.firstOrNull { it.locationKeys.contains(key) }
                            Row(Modifier.fillMaxWidth().clickable { app.setPinCollection(key, c); onDismiss() }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("📍", fontSize = 16.sp); Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(app.getLocationName(key).ifEmpty { "Unnamed location" }, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                    if (otherColl != null) Text("Currently in: ${otherColl.icon} ${otherColl.name}", fontSize = 11.sp, color = TealDeep)
                                    else Text("Not in any collection", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                                }
                                Text("Add", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Teal)
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } }
        )
    }

    @Composable
    private fun EditCollectionDialog(c: Collection?, onDismiss: () -> Unit) {
        var name by remember { mutableStateOf(c?.name ?: "") }
        var icon by remember { mutableStateOf(c?.icon ?: "📁") }
        var customIcon by remember { mutableStateOf("") }
        val icons = listOf("📁", "🗺️", "🏠", "🍕", "🌳", "🏢", "✈️", "🏖️", "🏔️", "❤️")

        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(if (c == null) "New Collection" else "Edit Collection") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(12.dp))
                    Column {
                        Text("Choose Icon", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            icons.forEach { i ->
                                Box(Modifier.size(32.dp).clip(CircleShape).background(if (icon == i) Teal.copy(alpha = 0.2f) else Color.Transparent).clickable { icon = i; customIcon = "" }, contentAlignment = Alignment.Center) { Text(i, fontSize = 18.sp) }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(value = customIcon, onValueChange = { if (it.length <= 2) { customIcon = it; if (it.isNotBlank()) icon = it } }, label = { Text("Custom Icon") }, modifier = Modifier.weight(1f), singleLine = true, shape = RoundedCornerShape(12.dp), placeholder = { Text("Emoji") })
                            Spacer(Modifier.width(12.dp))
                            Box(Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) { Text(icon, fontSize = 24.sp) }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (name.isNotBlank()) {
                        if (c == null) app.saveCollection(Collection(name.trim(), icon))
                        else { c.name = name.trim(); c.icon = icon; app.saveCollection(c) }
                        onDismiss()
                    }
                }) { Text("Save", color = Teal, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
        )
    }

    @Composable
    private fun DeleteCollectionDialog(c: Collection, onDismiss: () -> Unit) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Delete Collection") },
            text = { Text("Are you sure you want to delete “${c.name}”? The saved locations inside won't be deleted.") },
            confirmButton = {
                TextButton(onClick = { app.removeCollection(c); onDismiss() }) { Text("Delete", color = Danger, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
        )
    }
}
