// Pick a location on a map (address mode) or a full waypoint with name/notes (waypoint mode).
// Compose + maps-compose port of the old XML/Java screen. Returns via the same Intent extras.
package com.usc.myway

import android.content.Intent
import android.content.Context
import android.location.Geocoder
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.android.libraries.places.api.Places
import com.google.android.libraries.places.api.model.AutocompletePrediction
import com.google.android.libraries.places.api.model.AutocompleteSessionToken
import com.google.android.libraries.places.api.model.Place
import com.google.android.libraries.places.api.net.FetchPlaceRequest
import com.google.android.libraries.places.api.net.FindAutocompletePredictionsRequest
import com.google.android.libraries.places.api.net.PlacesClient
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberMarkerState
import com.usc.myway.ui.theme.MyWayTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Teal = Color(0xFF00C99D)

class MapPickerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = applicationContext as App
        val mode = intent.getStringExtra("mode") ?: "address"
        val initial = LatLng(intent.getDoubleExtra("latitude", 0.0), intent.getDoubleExtra("longitude", 0.0))
        if (!Places.isInitialized()) Places.initialize(applicationContext, BuildConfig.MAPS_API_KEY)
        val placesClient = Places.createClient(this)

        setContent {
            MyWayTheme(darkTheme = app.isDarkMode()) {
                MapPickerScreen(
                    mode = mode,
                    initial = initial,
                    darkMap = app.isDarkMode(),
                    placesClient = placesClient,
                    onSave = { lat, lng, address, name, notes ->
                        setResult(RESULT_OK, Intent().apply {
                            putExtra("picked_lat", lat)
                            putExtra("picked_lng", lng)
                            putExtra("picked_address", address)
                            putExtra("picked_name", name)
                            putExtra("picked_notes", notes)
                        })
                        finish()
                    },
                )
            }
        }
    }
}

@Composable
private fun MapPickerScreen(
    mode: String,
    initial: LatLng,
    darkMap: Boolean,
    placesClient: PlacesClient,
    onSave: (Double, Double, String, String, String) -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val isWaypoint = mode == "waypoint"

    val markerState = rememberMarkerState(position = initial)
    val cameraState = rememberCameraPositionState { position = CameraPosition.fromLatLngZoom(initial, 16f) }
    val mapProps = remember(darkMap) {
        MapProperties(mapStyleOptions = if (darkMap) MapStyleOptions.loadRawResourceStyle(ctx, R.raw.map_dark) else null)
    }

    var address by remember { mutableStateOf("Resolving address…") }
    var name by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var predictions by remember { mutableStateOf<List<AutocompletePrediction>>(emptyList()) }
    var token by remember { mutableStateOf(AutocompleteSessionToken.newInstance()) }
    var justPicked by remember { mutableStateOf(false) }

    // Reverse-geocode off the main thread whenever the pin moves (tap or drag).
    androidx.compose.runtime.LaunchedEffect(markerState.position) {
        address = "Resolving address…"
        address = geocodeAddress(ctx, markerState.position)
    }

    // Debounced, session-tokened autocomplete — one request after typing stops, billed as one session.
    androidx.compose.runtime.LaunchedEffect(query) {
        if (justPicked) { justPicked = false; return@LaunchedEffect }
        val q = query.trim()
        if (q.length < 2) { predictions = emptyList(); return@LaunchedEffect }
        kotlinx.coroutines.delay(300)
        predictions = try {
            withContext(Dispatchers.IO) {
                com.google.android.gms.tasks.Tasks.await(
                    placesClient.findAutocompletePredictions(
                        FindAutocompletePredictionsRequest.builder().setSessionToken(token).setQuery(q).build()
                    )
                )
            }.autocompletePredictions
        } catch (e: Exception) { emptyList() }
    }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            GoogleMap(
                modifier = Modifier.fillMaxSize(),
                cameraPositionState = cameraState,
                properties = mapProps,
                onMapClick = { markerState.position = it },
            ) {
                Marker(state = markerState, draggable = true, title = "Picked Location")
            }

            // Search overlay
            Column(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(12.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search for a place…") },
                    leadingIcon = { Text("🔍", fontSize = 16.sp) },
                    trailingIcon = {
                        if (query.isNotEmpty()) Text("✕", modifier = Modifier
                            .clickable { query = ""; predictions = emptyList() }.padding(8.dp))
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    colors = fieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (predictions.isNotEmpty()) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surface,
                        shadowElevation = 6.dp,
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    ) {
                        LazyColumn(Modifier.heightIn(max = 240.dp)) {
                            items(predictions) { pred ->
                                Column(Modifier.fillMaxWidth().clickable {
                                    justPicked = true
                                    query = pred.getPrimaryText(null).toString()
                                    predictions = emptyList()
                                    placesClient.fetchPlace(
                                        FetchPlaceRequest.builder(pred.placeId, listOf(Place.Field.LAT_LNG)).setSessionToken(token).build()
                                    ).addOnSuccessListener { resp ->
                                        resp.place.latLng?.let { ll ->
                                            markerState.position = ll
                                            scope.launch { cameraState.animate(CameraUpdateFactory.newLatLngZoom(ll, 16f)) }
                                        }
                                    }
                                    token = AutocompleteSessionToken.newInstance()
                                }.padding(horizontal = 14.dp, vertical = 10.dp)) {
                                    Text(pred.getPrimaryText(null).toString(),
                                        fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                                        color = MaterialTheme.colorScheme.onSurface)
                                    Text(pred.getSecondaryText(null).toString(),
                                        fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                                }
                                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                            }
                        }
                    }
                }
            }
        }

        // Bottom panel
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 8.dp,
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        ) {
            Column(
                Modifier.fillMaxWidth().padding(16.dp).navigationBarsPadding().imePadding()
                    .verticalScroll(rememberScrollState())
            ) {
                Text(String.format("%.6f, %.6f", markerState.position.latitude, markerState.position.longitude),
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                Text(address, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))

                if (isWaypoint) {
                    OutlinedTextField(
                        value = name, onValueChange = { name = it },
                        label = { Text("Waypoint name") },
                        placeholder = { Text("e.g. Home, Office, Park…") },
                        singleLine = true, shape = RoundedCornerShape(14.dp), colors = fieldColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = notes, onValueChange = { notes = it },
                        label = { Text("Notes / label") },
                        minLines = 2, maxLines = 4, shape = RoundedCornerShape(14.dp), colors = fieldColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(14.dp))
                }

                Button(
                    onClick = {
                        val p = markerState.position
                        onSave(p.latitude, p.longitude, address, name.trim(), notes.trim())
                    },
                    shape = RoundedCornerShape(15.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Teal, contentColor = Color.White),
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                ) {
                    Text(if (isWaypoint) "Save Waypoint" else "Set Address", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun fieldColors() = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Teal,
    focusedLabelColor = Teal,
)

private suspend fun geocodeAddress(ctx: Context, ll: LatLng): String = withContext(Dispatchers.IO) {
    try {
        @Suppress("DEPRECATION")
        Geocoder(ctx).getFromLocation(ll.latitude, ll.longitude, 1)?.firstOrNull()?.getAddressLine(0)
            ?: "Unknown address"
    } catch (_: Exception) { "Unknown address" }
}
