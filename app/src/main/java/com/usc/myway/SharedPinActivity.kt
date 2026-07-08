// Preview for a pin/note shared into a group chat: a map preview + address, a link to the location's
// Google Maps page, and an "Add to map" button that saves it to your personal waypoints.
package com.usc.myway

import android.content.Intent
import android.location.Location
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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

private val Teal = Color(0xFF00C99D)
private val TealDeep = Color(0xFF00A77D)

class SharedPinActivity : ComponentActivity() {

    private var lat = 0.0
    private var lng = 0.0
    private var name = ""
    private var note = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lat = intent.getDoubleExtra("lat", 0.0)
        lng = intent.getDoubleExtra("lng", 0.0)
        name = intent.getStringExtra("name") ?: ""
        note = intent.getStringExtra("note") ?: ""
        setContent {
            MyWayTheme {
                SharedPinScreen(
                    lat = lat, lng = lng, name = name, note = note,
                    onBack = { finish() },
                    onOpenGoogle = { openGoogleMaps() },
                    onAddToMap = { addToMap() },
                )
            }
        }
    }

    /** The location's Google Maps page (opens the Maps app or the web page). */
    private fun openGoogleMaps() {
        val uri = Uri.parse("https://www.google.com/maps/search/?api=1&query=$lat,$lng")
        startActivity(Intent(Intent.ACTION_VIEW, uri))
    }

    private fun addToMap() {
        val app = application as App
        val key = App.locationKey(lat, lng)
        if (app.myLocations.any { App.locationKey(it.latitude, it.longitude) == key }) {
            toast("Already on your map"); return
        }
        app.saveLocation(Location("shared").apply { latitude = lat; longitude = lng })
        if (name.isNotEmpty()) app.saveLocationName(key, name)
        if (note.isNotEmpty()) app.saveNote(key, note)
        toast("Added to your map")
    }

    private fun toast(msg: String) = android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SharedPinScreen(
    lat: Double,
    lng: Double,
    name: String,
    note: String,
    onBack: () -> Unit,
    onOpenGoogle: () -> Unit,
    onAddToMap: () -> Unit,
) {
    val ctx = LocalContext.current
    val pos = remember(lat, lng) { LatLng(lat, lng) }
    var added by remember { mutableStateOf(false) }
    val address by produceState("", pos) { value = geocodeLine(ctx, pos) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Shared location", fontWeight = FontWeight.Bold) },
                navigationIcon = { TextButton(onClick = onBack) { Text("←", fontSize = 22.sp, fontWeight = FontWeight.Bold) } },
                colors = TopAppBarDefaults.topAppBarColors(),
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            // Map preview
            val cam = rememberCameraPositionState { position = CameraPosition.fromLatLngZoom(pos, 16f) }
            GoogleMap(
                modifier = Modifier.fillMaxWidth().height(280.dp),
                cameraPositionState = cam,
                uiSettings = MapUiSettings(zoomControlsEnabled = false, mapToolbarEnabled = false),
            ) {
                Marker(state = rememberMarkerState(position = pos), title = name.ifEmpty { "Shared location" })
            }

            Column(Modifier.fillMaxWidth().padding(20.dp)) {
                Text(name.ifEmpty { "Shared location" }, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface)
                if (note.isNotEmpty()) Text("📝 $note", fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f), modifier = Modifier.padding(top = 6.dp))
                Text("📍 ${address.ifEmpty { String.format("%.5f, %.5f", lat, lng) }}", fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f), modifier = Modifier.padding(top = 6.dp))

                Column(Modifier.fillMaxWidth().padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = onOpenGoogle, modifier = Modifier.fillMaxWidth().height(50.dp),
                        shape = RoundedCornerShape(14.dp)) {
                        Text("🌐  Open in Google Maps", color = TealDeep, fontWeight = FontWeight.Bold)
                    }
                    Button(
                        onClick = { onAddToMap(); added = true },
                        enabled = !added,
                        modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Teal),
                    ) { Text(if (added) "✓  Added to your map" else "➕  Add to map", fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
}
