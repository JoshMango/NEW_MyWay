// Saved waypoints + collections screen — Compose port of the old XML/Java stack
// (ShowSavedLocations activity, AllWaypoints/CollectionsList fragments, and the
// Waypoint/Collection/CollectionItem adapters). App.java is still the data source.
package com.usc.myway

import android.content.Context
import android.content.Intent
import android.location.Geocoder
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.usc.myway.ui.theme.MyWayTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val Teal = Color(0xFF00C99D)
private val TealDeep = Color(0xFF00A77D)

private val QUICK_EMOJIS = listOf(
    "📁", "⭐", "🏠", "🍔", "🏋️", "🏥", "🏫", "🛍️", "🌿", "🚗",
    "📍", "💼", "🎯", "🌟", "❤️", "🎵", "📸", "🌍", "🏖️", "🎓"
)

class ShowSavedLocations : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = applicationContext as App
        setContent {
            MyWayTheme(darkTheme = app.isDarkMode()) {
                SavedLocationsScreen(app)
            }
        }
    }
}

/* ── Screen ────────────────────────────────────────────────────────────── */

@Composable
private fun SavedLocationsScreen(app: App) {
    // App mutates its lists in place; bumping this key re-snapshots them.
    var refreshKey by remember { mutableIntStateOf(0) }
    val refresh: () -> Unit = { refreshKey++ }
    RefreshOnResume(refresh) // parity with the old onResume() count refresh

    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val count = remember(refreshKey) { app.myLocations.size }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            Header(count, selectedTab) { selectedTab = it }
            Box(Modifier.weight(1f)) {
                if (selectedTab == 0) AllWaypointsTab(app, refreshKey, refresh)
                else CollectionsTab(app, refreshKey, refresh)
            }
        }
    }
}

@Composable
private fun Header(count: Int, selectedTab: Int, onTab: (Int) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Teal, TealDeep)))
            .statusBarsPadding()
            .padding(start = 20.dp, end = 20.dp, top = 16.dp)
    ) {
        Text("Saved Waypoints", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text(
            "$count ${if (count == 1) "location" else "locations"} saved",
            color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        val tabs = listOf("All Locations", "Collections")
        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = Color.Transparent,
            contentColor = Color.White,
            indicator = { pos ->
                TabRowDefaults.SecondaryIndicator(
                    Modifier.tabIndicatorOffset(pos[selectedTab]), color = Color.White
                )
            }
        ) {
            tabs.forEachIndexed { i, title ->
                Tab(
                    selected = selectedTab == i,
                    onClick = { onTab(i) },
                    selectedContentColor = Color.White,
                    unselectedContentColor = Color.White.copy(alpha = 0.6f),
                    text = { Text(title, fontWeight = FontWeight.SemiBold) }
                )
            }
        }
    }
}

/* ── Tab 1: all waypoints ──────────────────────────────────────────────── */

@Composable
private fun AllWaypointsTab(app: App, refreshKey: Int, refresh: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val all = remember(refreshKey) { ArrayList(app.myLocations) }
    val filtered = remember(all, query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) all
        else all.filter { loc ->
            val key = App.locationKey(loc.latitude, loc.longitude)
            app.getLocationName(key).lowercase().contains(q) ||
                (app.locationNotes[key] ?: "").lowercase().contains(q)
        }
    }
    var editing by remember { mutableStateOf<android.location.Location?>(null) }

    Column(Modifier.fillMaxSize()) {
        if (all.isNotEmpty()) {
            SearchField(query, { query = it }, "Search saved waypoints...")
        }
        when {
            all.isEmpty() -> EmptyState("📍", "No waypoints saved yet", "Go back and tap 'Add New Waypoint'")
            filtered.isEmpty() -> EmptyState("🔍", "No matching waypoints", "No results for \"$query\"")
            else -> LazyColumn(
                contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
                modifier = Modifier.navigationBarsPadding()
            ) {
                itemsIndexed(filtered) { i, loc ->
                    WaypointCard(app, loc, i, refreshKey, onEdit = { editing = loc }, onDeleted = refresh)
                }
            }
        }
    }

    editing?.let { loc ->
        EditPinDialog(app, loc, onDismiss = { editing = null }, onSaved = { refresh(); editing = null })
    }
}

@Composable
private fun WaypointCard(
    app: App,
    loc: android.location.Location,
    index: Int,
    refreshKey: Int,
    onEdit: () -> Unit,
    onDeleted: () -> Unit,
) {
    val ctx = LocalContext.current
    val key = App.locationKey(loc.latitude, loc.longitude)
    val address = rememberAddress(app, key, loc.latitude, loc.longitude)
    val note = remember(refreshKey) { app.locationNotes[key] ?: "" }
    val collectionLabel = remember(refreshKey) {
        app.collections.firstOrNull { it.locationKeys.contains(key) }?.let { "${it.icon} ${it.name}" }
    }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 5.dp)
            .clickable { focusOnMap(ctx, loc.latitude, loc.longitude) },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(Teal),
                contentAlignment = Alignment.Center
            ) {
                Text("${index + 1}", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(
                    address, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    String.format("%.5f, %.5f", loc.latitude, loc.longitude),
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(top = 4.dp)
                )
                Row(
                    Modifier.padding(top = 4.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (note.isNotEmpty()) {
                        Text(
                            "📝 $note", fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                            modifier = Modifier.weight(1f)
                        )
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                    collectionLabel?.let {
                        Box(
                            Modifier.padding(start = 8.dp).clip(RoundedCornerShape(50))
                                .background(Teal).padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(it, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            Box {
                Text(
                    "⋮", fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { menuOpen = true }
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                )
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("✏️ Edit") }, onClick = { menuOpen = false; onEdit() })
                    DropdownMenuItem(text = { Text("🗑️ Delete") }, onClick = { menuOpen = false; confirmDelete = true })
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete Waypoint") },
            text = { Text("Are you sure you want to delete this waypoint?") },
            confirmButton = {
                TextButton(onClick = {
                    app.removeLocation(loc)
                    confirmDelete = false
                    Toast.makeText(ctx, "Waypoint deleted.", Toast.LENGTH_SHORT).show()
                    onDeleted()
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
        )
    }
}

/* ── Tab 2: collections ────────────────────────────────────────────────── */

@Composable
private fun CollectionsTab(app: App, refreshKey: Int, refresh: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val all = remember(refreshKey) { ArrayList(app.collections) }
    val filtered = remember(all, query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) all else all.filter { it.name.lowercase().contains(q) }
    }
    val expanded = remember { mutableStateMapOf<Collection, Boolean>() }
    var showCreate by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Collection?>(null) }
    var addingTo by remember { mutableStateOf<Collection?>(null) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            SearchField(query, { query = it }, "Search created collections...")
            when {
                all.isEmpty() -> EmptyState("📝", "No collections yet", "Tap + to create your first collection")
                filtered.isEmpty() -> EmptyState("🔍", "No matching collections", "No results for \"$query\"")
                else -> LazyColumn(
                    contentPadding = PaddingValues(top = 4.dp, bottom = 96.dp),
                    modifier = Modifier.navigationBarsPadding()
                ) {
                    items(filtered, key = { it.name + it.icon }) { c ->
                        CollectionCard(
                            app, c, refreshKey,
                            expanded = expanded[c] ?: false,
                            onToggle = { expanded[c] = !(expanded[c] ?: false) },
                            onEdit = { editing = c },
                            onAdd = { addingTo = c },
                            onChanged = refresh,
                        )
                    }
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { showCreate = true },
            containerColor = Teal,
            contentColor = Color.White,
            text = { Text("New") },
            icon = { Text("＋", fontSize = 20.sp) },
            modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(16.dp),
        )
    }

    if (showCreate) {
        CollectionEditDialog(app, existing = null, onDismiss = { showCreate = false }, onSaved = { refresh(); showCreate = false })
    }
    editing?.let { c ->
        CollectionEditDialog(app, existing = c, onDismiss = { editing = null }, onSaved = { refresh(); editing = null })
    }
    addingTo?.let { c ->
        AddLocationDialog(app, c, onDismiss = { addingTo = null }, onChanged = refresh)
    }
}

@Composable
private fun CollectionCard(
    app: App,
    c: Collection,
    refreshKey: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onAdd: () -> Unit,
    onChanged: () -> Unit,
) {
    val ctx = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val validCount = remember(refreshKey) {
        c.locationKeys.count { key -> app.myLocations.any { App.locationKey(it.latitude, it.longitude) == key } }
    }

    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth().clickable { onToggle() },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.size(44.dp).clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)),
                    contentAlignment = Alignment.Center
                ) { Text(c.icon, fontSize = 22.sp) }
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(c.name, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        "$validCount ${if (validCount == 1) "location" else "locations"}",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
                Text(
                    if (expanded) "▼" else "▶", fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    modifier = Modifier.padding(end = 8.dp)
                )
                Box {
                    Text(
                        "⋮", fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { menuOpen = true }
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text("✏️ Edit") }, onClick = { menuOpen = false; onEdit() })
                        DropdownMenuItem(text = { Text("🗑️ Delete") }, onClick = { menuOpen = false; confirmDelete = true })
                    }
                }
            }

            if (expanded) {
                HorizontalDivider(Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                        .background(Teal.copy(alpha = 0.12f)).clickable { onAdd() }.padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("＋  Add Location to Collection", color = TealDeep, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
                Spacer(Modifier.padding(top = 4.dp))
                c.locationKeys.forEach { key ->
                    CollectionItemRow(app, c, key, refreshKey, onChanged)
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete Collection") },
            text = { Text("Delete \"${c.name}\"? Locations won't be deleted.") },
            confirmButton = {
                TextButton(onClick = {
                    app.removeCollection(c)
                    confirmDelete = false
                    onChanged()
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun CollectionItemRow(app: App, c: Collection, key: String, refreshKey: Int, onChanged: () -> Unit) {
    val ctx = LocalContext.current
    val parts = key.split(",")
    val lat = parts.getOrNull(0)?.trim()?.toDoubleOrNull()
    val lng = parts.getOrNull(1)?.trim()?.toDoubleOrNull()
    val address = if (lat != null && lng != null) rememberAddress(app, key, lat, lng) else key
    val note = remember(refreshKey) { app.locationNotes[key] ?: "" }

    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f))
            .clickable { if (lat != null && lng != null) focusOnMap(ctx, lat, lng) }
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(address, color = Teal, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (note.isNotEmpty()) {
                Text("📝 $note", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), fontSize = 12.sp)
            }
        }
        Text(
            "✕", color = Color(0xFFEF4444), fontSize = 14.sp,
            modifier = Modifier.clip(CircleShape).clickable {
                c.locationKeys.remove(key)
                app.saveCollectionsToPrefs()
                onChanged()
            }.padding(8.dp)
        )
    }
}

/* ── Dialogs ───────────────────────────────────────────────────────────── */

@Composable
private fun EditPinDialog(
    app: App,
    loc: android.location.Location,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    val ctx = LocalContext.current
    val key = App.locationKey(loc.latitude, loc.longitude)
    var name by remember { mutableStateOf(app.getLocationName(key)) }
    var notes by remember { mutableStateOf(app.locationNotes[key] ?: "") }
    var selected by remember { mutableStateOf(app.collections.firstOrNull { it.locationKeys.contains(key) }) }
    val address = rememberAddress(app, key, loc.latitude, loc.longitude)

    DialogSurface(onDismiss) {
        Text("📌 Edit Waypoint", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurface)
        Text(address, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), modifier = Modifier.padding(top = 4.dp))
        Text(
            String.format("%.6f, %.6f", loc.latitude, loc.longitude),
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
            modifier = Modifier.padding(bottom = 12.dp)
        )
        OutlinedTextField(
            value = name, onValueChange = { name = it }, label = { Text("Name") },
            singleLine = true, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.padding(top = 8.dp))
        OutlinedTextField(
            value = notes, onValueChange = { notes = it }, label = { Text("Notes") },
            shape = RoundedCornerShape(14.dp), minLines = 2, maxLines = 4, modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.padding(top = 8.dp))
        CollectionPicker(app, selected) { selected = it }
        Spacer(Modifier.padding(top = 12.dp))
        DialogButtons(
            confirmText = "Save Changes",
            onCancel = onDismiss,
            onConfirm = {
                if (name.trim().isNotEmpty()) app.saveLocationName(key, name.trim()) else app.removeLocationName(key)
                if (notes.trim().isNotEmpty()) app.saveNote(key, notes.trim()) else app.removeNote(key)
                app.collections.forEach { it.locationKeys.remove(key) }
                selected?.let { if (!it.locationKeys.contains(key)) it.locationKeys.add(key) }
                app.saveCollectionsToPrefs()
                Toast.makeText(ctx, "Waypoint updated!", Toast.LENGTH_SHORT).show()
                onSaved()
            }
        )
    }
}

@Composable
private fun CollectionPicker(app: App, selected: Collection?, onSelect: (Collection?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Text("COLLECTION", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
    Box {
        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp).clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
                .clickable { open = true }.padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                selected?.let { "${it.icon} ${it.name}" } ?: "Tap to choose a collection...",
                modifier = Modifier.weight(1f), fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (selected == null) 0.5f else 1f)
            )
            Text("▶", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("❌ None") }, onClick = { onSelect(null); open = false })
            app.collections.forEach { col ->
                DropdownMenuItem(text = { Text("${col.icon} ${col.name}") }, onClick = { onSelect(col); open = false })
            }
        }
    }
}

@Composable
private fun CollectionEditDialog(app: App, existing: Collection?, onDismiss: () -> Unit, onSaved: () -> Unit) {
    var icon by remember { mutableStateOf(existing?.icon ?: "") }
    var name by remember { mutableStateOf(existing?.name ?: "") }

    DialogSurface(onDismiss) {
        Text(if (existing == null) "New Collection" else "Edit Collection", fontWeight = FontWeight.Bold, fontSize = 26.sp, color = MaterialTheme.colorScheme.onSurface)
        Text("Choose an icon and give it a name", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), modifier = Modifier.padding(bottom = 16.dp))
        OutlinedTextField(
            value = icon, onValueChange = { if (it.length <= 4) icon = it }, label = { Text("Icon") },
            singleLine = true, shape = RoundedCornerShape(14.dp), modifier = Modifier.width(120.dp)
        )
        Text("Quick pick", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
        LazyRow {
            items(QUICK_EMOJIS) { e ->
                Text(e, fontSize = 24.sp, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { icon = e }.padding(8.dp))
            }
        }
        Spacer(Modifier.padding(top = 8.dp))
        OutlinedTextField(
            value = name, onValueChange = { name = it }, label = { Text("Name") },
            singleLine = true, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.padding(top = 12.dp))
        DialogButtons(
            confirmText = if (existing == null) "Create" else "Save",
            onCancel = onDismiss,
            onConfirm = {
                val finalIcon = icon.trim().ifEmpty { "📁" }
                val finalName = name.trim().ifEmpty { "New Collection" }
                if (existing == null) {
                    app.saveCollection(Collection(finalName, finalIcon))
                } else {
                    existing.icon = finalIcon
                    existing.name = finalName
                    app.saveCollectionsToPrefs()
                }
                onSaved()
            }
        )
    }
}

@Composable
private fun AddLocationDialog(app: App, c: Collection, onDismiss: () -> Unit, onChanged: () -> Unit) {
    val ctx = LocalContext.current
    // Locations not already in this collection. Rebuilt as they're added.
    val available = remember {
        app.myLocations
            .map { App.locationKey(it.latitude, it.longitude) }
            .filter { !c.locationKeys.contains(it) }
            .toMutableStateList()
    }

    DialogSurface(onDismiss) {
        Text("Add to \"${c.name}\"", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurface)
        Text(
            "${available.size} ${if (available.size == 1) "location" else "locations"} available",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            modifier = Modifier.padding(bottom = 12.dp)
        )
        if (available.isEmpty()) {
            Text("No more locations to add.", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), modifier = Modifier.padding(vertical = 8.dp))
        } else {
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                items(available, key = { it }) { key ->
                    val parts = key.split(",")
                    val lat = parts.getOrNull(0)?.trim()?.toDoubleOrNull()
                    val lng = parts.getOrNull(1)?.trim()?.toDoubleOrNull()
                    val address = if (lat != null && lng != null) rememberAddress(app, key, lat, lng) else key
                    val note = app.locationNotes[key] ?: ""
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f)).padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(address, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface)
                            Text(
                                if (note.isEmpty()) "📍 No note" else "📝 $note",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (note.isEmpty()) 0.4f else 0.7f)
                            )
                        }
                        TextButton(onClick = {
                            c.locationKeys.add(key)
                            app.saveCollectionsToPrefs()
                            available.remove(key)
                            onChanged()
                            Toast.makeText(ctx, "Added!", Toast.LENGTH_SHORT).show()
                            if (available.isEmpty()) onDismiss()
                        }) { Text("Add") }
                    }
                }
            }
        }
        Spacer(Modifier.padding(top = 8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    }
}

/* ── Shared building blocks ────────────────────────────────────────────── */

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit, hint: String) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        placeholder = { Text(hint) },
        singleLine = true,
        leadingIcon = { Text("🔍", fontSize = 16.sp) },
        trailingIcon = {
            if (value.isNotEmpty()) {
                Text("✕", fontSize = 14.sp, modifier = Modifier.clip(CircleShape).clickable { onChange("") }.padding(8.dp))
            }
        },
        keyboardOptions = KeyboardOptions.Default,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)
    )
}

@Composable
private fun EmptyState(emoji: String, title: String, subtitle: String) {
    Column(
        Modifier.fillMaxSize().padding(40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(emoji, fontSize = 48.sp)
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(top = 12.dp))
        Text(subtitle, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), modifier = Modifier.padding(top = 8.dp))
    }
}

/** Bottom-anchored modal card, used by all the form dialogs. */
@Composable
private fun DialogSurface(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(20.dp).imePadding()) { content() }
        }
    }
}

@Composable
private fun DialogButtons(confirmText: String, onCancel: () -> Unit, onConfirm: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = onCancel) { Text("Cancel") }
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = onConfirm) { Text(confirmText, color = Teal, fontWeight = FontWeight.Bold) }
    }
}

/* ── Helpers ───────────────────────────────────────────────────────────── */

/** Saved name if present, else reverse-geocoded off the main thread. */
@Composable
private fun rememberAddress(app: App, key: String, lat: Double, lng: Double): String {
    val ctx = LocalContext.current
    val saved = app.getLocationName(key)
    return produceState(initialValue = saved.ifEmpty { "Loading…" }, key, saved) {
        if (saved.isNotEmpty()) { value = saved; return@produceState }
        value = withContext(Dispatchers.IO) { geocode(ctx, lat, lng) } ?: "Unknown Location"
    }.value
}

private fun geocode(ctx: Context, lat: Double, lng: Double): String? = try {
    @Suppress("DEPRECATION")
    Geocoder(ctx).getFromLocation(lat, lng, 1)?.firstOrNull()?.getAddressLine(0)
} catch (_: Exception) { null }

private fun focusOnMap(ctx: Context, lat: Double, lng: Double) {
    val intent = Intent(ctx, MainActivity::class.java).apply {
        putExtra("focus_lat", lat)
        putExtra("focus_lng", lng)
        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
    }
    ctx.startActivity(intent)
}

@Composable
private fun RefreshOnResume(onResume: () -> Unit) {
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) onResume() }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
}
