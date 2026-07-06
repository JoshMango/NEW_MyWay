// Map home screen — fully Compose (maps-compose). The tuned marker/label/POI logic runs on the
// raw GoogleMap via MapEffect (see MapMarkerManager); sheets/dialogs are Compose.
package com.usc.myway

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.location.Geocoder
import android.location.Location
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.app.ActivityCompat
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.libraries.places.api.Places
import com.google.android.libraries.places.api.model.Place
import com.google.android.libraries.places.api.net.FetchPlaceRequest
import com.google.android.libraries.places.api.net.PlacesClient
import com.google.firebase.auth.FirebaseAuth
import com.google.maps.android.compose.CameraPositionState
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapEffect
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.rememberCameraPositionState
import com.usc.myway.ui.theme.MyWayTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

private val Teal = Color(0xFF00C99D)

private sealed interface ActiveSheet {
    data class PinActions(val key: String, val title: String, val latLng: LatLng) : ActiveSheet
    data class PlaceDetails(val place: Place, val name: String, val latLng: LatLng) : ActiveSheet
}

class MainActivity : ComponentActivity() {

    private val app get() = applicationContext as App
    private lateinit var fusedLocClient: FusedLocationProviderClient
    private lateinit var placesClient: PlacesClient
    private lateinit var locReq: LocationRequest
    private lateinit var locCallBack: LocationCallback
    private val geocodeExecutor = Executors.newSingleThreadExecutor()
    private var lastGeoLat = Double.NaN
    private var lastGeoLng = Double.NaN

    private val markers = MapMarkerManager(this)
    private var googleMap: GoogleMap? = null
    private var camState: CameraPositionState? = null
    private var uiScope: CoroutineScope? = null
    private var firstFix = true
    private var tempMarker: Marker? = null

    private val placeCache = HashMap<String, Place>()
    private val photoCache = HashMap<String, Bitmap>()
    private val isOpenCache = HashMap<String, Boolean>()

    private var savedLat = 0.0
    private var savedLng = 0.0
    private var savedAddress = ""

    // Compose state
    private val stats = StatsState()
    private val sidebar = SidebarState()
    private var hasLocationPerm by mutableStateOf(false)
    private var drawerOpen by mutableStateOf(false)
    private var refreshKey by mutableIntStateOf(0)
    private var activeSheet by mutableStateOf<ActiveSheet?>(null)
    private var noteKey by mutableStateOf<String?>(null)
    private var collectionKey by mutableStateOf<String?>(null)
    private var deleteKey by mutableStateOf<String?>(null)
    private var savePinLatLng by mutableStateOf<LatLng?>(null)

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasLocationPerm = granted
        if (granted) startLocationUpdates()
    }
    private val waypointPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == RESULT_OK) res.data?.let { handleWaypointResult(it) }
    }
    private val addressPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == RESULT_OK) res.data?.let { handleAddressResult(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        fusedLocClient = LocationServices.getFusedLocationProviderClient(this)
        if (!Places.isInitialized()) Places.initialize(applicationContext, BuildConfig.MAPS_API_KEY)
        placesClient = Places.createClient(this)
        setupLocationRequest()

        sidebar.darkMode = isDarkMode(); sidebar.tracking = true; sidebar.gpsHighAccuracy = false
        hasLocationPerm = hasLocationPermission()
        if (!hasLocationPerm) permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        else startLocationUpdates()

        setContent { MyWayTheme(darkTheme = isDarkMode()) { MainScreen() } }
    }

    override fun onResume() {
        super.onResume()
        refresh()
        handleFocusIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleFocusIntent(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        geocodeExecutor.shutdownNow()
    }

    /* ── Compose UI ─────────────────────────────────────────────────────── */

    @Composable
    private fun MainScreen() {
        val dark = isDarkMode()
        val cam = rememberCameraPositionState { position = CameraPosition.fromLatLngZoom(LatLng(0.0, 0.0), 2f) }
        val scope = rememberCoroutineScope()
        camState = cam
        uiScope = scope
        val mapProps = remember(dark, hasLocationPerm) {
            MapProperties(
                isMyLocationEnabled = hasLocationPerm,
                mapStyleOptions = if (dark) MapStyleOptions.loadRawResourceStyle(this, R.raw.map_dark) else null,
            )
        }
        // Native compass + my-location button off — we render our own above the bottom card.
        val uiSettings = remember { MapUiSettings(zoomControlsEnabled = false, myLocationButtonEnabled = false, compassEnabled = false, mapToolbarEnabled = false) }
        // derivedStateOf so overlays recompose only when the bearing actually changes (not on pan/zoom).
        val bearing by remember { derivedStateOf { cam.position.bearing } }

        Box(Modifier.fillMaxSize()) {
            GoogleMap(
                modifier = Modifier.fillMaxSize(),
                cameraPositionState = cam,
                properties = mapProps,
                uiSettings = uiSettings,
                contentPadding = PaddingValues(bottom = 150.dp), // keep Google logo above the bottom card
                onPOIClick = { poi -> centerOn(poi.latLng); fetchAndShowPlace(poi.placeId, poi.name, poi.latLng) },
                onMapClick = { ll -> if (stats.pinMode) onPinMapClick(ll) },
            ) {
                MapEffect(Unit) { map ->
                    googleMap = map
                    map.setOnMarkerClickListener { m -> handleMarkerClick(m); true }
                    map.setOnCameraIdleListener { markers.applyZoom(map) }
                    if (firstFix && savedLat != 0.0) {
                        map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(savedLat, savedLng), 18f)); firstFix = false
                    }
                    markers.refresh(map, app, dark)
                }
                MapEffect(refreshKey, dark) { map -> markers.refresh(map, app, dark) }
            }

            // Top: floating hamburger + search only.
            Row(
                Modifier.align(Alignment.TopStart).fillMaxWidth().statusBarsPadding()
                    .padding(start = 10.dp, end = 12.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(48.dp).shadow(4.dp, RoundedCornerShape(12.dp))
                        .clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surface)
                        .clickable { drawerOpen = true },
                    contentAlignment = Alignment.Center,
                ) { Text("☰", fontSize = 22.sp, color = MaterialTheme.colorScheme.onSurface) }
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f)) {
                    SearchBar(placesClient, PlacePickedListener { ll -> animateTo(ll, 16f) })
                }
            }

            // Bottom: map controls (compass + my-location) sit at the top-right, above the card.
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 12.dp)) {
                Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.End) {
                    MapControls(
                        bearing = bearing,
                        onCompass = { scope.launch { cam.animate(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder(cam.position).bearing(0f).tilt(0f).build())) } },
                        onMyLocation = { if (savedLat != 0.0 || savedLng != 0.0) animateTo(LatLng(savedLat, savedLng), 17f) },
                    )
                }
                BottomCard(stats, statsActions)
            }

            // Drawer: full-screen scrim + slide-in panel.
            AnimatedVisibility(drawerOpen, enter = fadeIn(), exit = fadeOut()) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { drawerOpen = false })
            }
            AnimatedVisibility(
                visible = drawerOpen,
                enter = slideInHorizontally { -it },
                exit = slideOutHorizontally { -it },
                modifier = Modifier.align(Alignment.TopStart),
            ) { Sidebar(sidebar, sidebarActions) }

            Sheets()
            Dialogs()
        }
    }

    @Composable
    private fun MapControls(bearing: Float, onCompass: () -> Unit, onMyLocation: () -> Unit) {
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (bearing != 0f) {
                CircleButton(onCompass) { Text("🧭", fontSize = 20.sp, modifier = Modifier.rotate(-bearing)) }
            }
            CircleButton(onMyLocation) { Text("📍", fontSize = 18.sp) }
        }
    }

    @Composable
    private fun CircleButton(onClick: () -> Unit, content: @Composable () -> Unit) {
        Box(
            Modifier.size(44.dp).shadow(4.dp, CircleShape).clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface).clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { content() }
    }

    @Composable
    private fun Sheets() {
        when (val s = activeSheet) {
            is ActiveSheet.PinActions -> MarkerActionsSheet(
                title = s.title,
                note = app.locationNotes[s.key] ?: "",
                latLng = s.latLng,
                onDismiss = { activeSheet = null },
                onNote = { activeSheet = null; noteKey = s.key },
                onCollection = { activeSheet = null; openCollection(s.key) },
                onDelete = { activeSheet = null; deleteKey = s.key },
            )
            is ActiveSheet.PlaceDetails -> {
                val key = App.locationKey(s.latLng.latitude, s.latLng.longitude)
                PlaceDetailsSheet(
                    place = s.place, name = s.name,
                    userNote = app.locationNotes[key] ?: "",
                    isSaved = isSaved(key),
                    placesClient = placesClient, photoCache = photoCache, isOpenCache = isOpenCache,
                    onDismiss = { activeSheet = null },
                    onNote = { activeSheet = null; ensureSaved(s.latLng, s.name, s.place.id); noteKey = key },
                    onCollection = { activeSheet = null; ensureSaved(s.latLng, s.name, s.place.id); openCollection(key) },
                    onDelete = { activeSheet = null; deleteKey = key },
                )
            }
            null -> {}
        }
    }

    @Composable
    private fun Dialogs() {
        noteKey?.let { key ->
            var text by remember { mutableStateOf(app.locationNotes[key] ?: "") }
            AlertDialog(
                onDismissRequest = { noteKey = null },
                title = { Text("📝 Edit Note") },
                text = { OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) },
                confirmButton = { TextButton(onClick = { app.saveNote(key, text.trim()); refresh(); noteKey = null }) { Text("Save") } },
                dismissButton = { TextButton(onClick = { noteKey = null }) { Text("Cancel") } },
            )
        }
        collectionKey?.let { key ->
            val collections = app.collections
            AlertDialog(
                onDismissRequest = { collectionKey = null },
                title = { Text("Add to Collection") },
                text = {
                    androidx.compose.foundation.layout.Column {
                        CollectionRow("❌ None") { collections.forEach { it.locationKeys.remove(key) }; app.saveCollectionsToPrefs(); refresh(); collectionKey = null }
                        collections.forEach { c ->
                            val mark = if (c.locationKeys.contains(key)) "  ✓" else ""
                            CollectionRow("${c.icon} ${c.name}$mark") {
                                collections.forEach { it.locationKeys.remove(key) }
                                c.locationKeys.add(key); app.saveCollectionsToPrefs(); refresh(); collectionKey = null
                            }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = { TextButton(onClick = { collectionKey = null }) { Text("Close") } },
            )
        }
        deleteKey?.let { key ->
            AlertDialog(
                onDismissRequest = { deleteKey = null },
                title = { Text("Delete Waypoint") },
                text = { Text("Are you sure you want to delete this location?") },
                confirmButton = {
                    TextButton(onClick = {
                        app.myLocations.firstOrNull { App.locationKey(it.latitude, it.longitude) == key }?.let { app.removeLocation(it) }
                        refresh(); deleteKey = null
                    }) { Text("Delete") }
                },
                dismissButton = { TextButton(onClick = { deleteKey = null }) { Text("Cancel") } },
            )
        }
        savePinLatLng?.let { ll -> SavePinDialog(ll) }
    }

    @Composable
    private fun CollectionRow(label: String, onClick: () -> Unit) {
        Text(label, Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
            color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp)
    }

    @Composable
    private fun SavePinDialog(ll: LatLng) {
        var name by remember { mutableStateOf("") }
        var notes by remember { mutableStateOf("") }
        Dialog(onDismissRequest = { cancelPin() }) {
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
                androidx.compose.foundation.layout.Column(Modifier.padding(20.dp)) {
                    Text("📌 Save Waypoint", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    Text(String.format("%.6f, %.6f", ll.latitude, ll.longitude), fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), modifier = Modifier.padding(bottom = 12.dp))
                    OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Name") },
                        singleLine = true, shape = RoundedCornerShape(12.dp))
                    androidx.compose.foundation.layout.Spacer(Modifier.padding(top = 8.dp))
                    OutlinedTextField(notes, { notes = it }, Modifier.fillMaxWidth(), label = { Text("Notes") },
                        minLines = 2, maxLines = 4, shape = RoundedCornerShape(12.dp))
                    Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End) {
                        TextButton(onClick = { cancelPin() }) { Text("Cancel") }
                        TextButton(onClick = { savePin(ll, name.trim(), notes.trim()) }) { Text("Save", color = Teal, fontWeight = FontWeight.Bold) }
                    }
                }
            }
        }
    }

    private val sidebarActions = object : SidebarActions {
        override fun onNewWaypoint() { drawerOpen = false; startWaypointPicker() }
        override fun onShowWaypoints() { drawerOpen = false; startActivity(Intent(this@MainActivity, ShowSavedLocations::class.java)) }
        override fun onSetAddress() { drawerOpen = false; startAddressPicker() }
        override fun onToggleTheme() { toggleTheme() }
        override fun onLogout() { logout() }
        override fun onTrackingChanged(enabled: Boolean) { if (enabled) startLocationUpdates() else stopLocationUpdates() }
        override fun onGpsModeChanged(highAccuracy: Boolean) {
            locReq = buildLocReq(if (highAccuracy) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY)
        }
    }

    private val statsActions = object : StatsActions {
        override fun onSave() { startWaypointPicker() }
        override fun onPin() { togglePinMode() }
        override fun onShare() { shareLocation() }
    }

    /* ── Map interaction ────────────────────────────────────────────────── */

    private fun handleMarkerClick(m: Marker) {
        markers.markerKeys[m]?.let { key -> centerOn(m.position); activeSheet = ActiveSheet.PinActions(key, m.title ?: "", m.position); return }
        markers.labelKeys[m]?.let { key -> openForKey(key) }
    }

    private fun openForKey(key: String) {
        if (app.isLandmark(key)) {
            val parts = key.split(",")
            val lat = parts.getOrNull(0)?.trim()?.toDoubleOrNull() ?: return
            val lng = parts.getOrNull(1)?.trim()?.toDoubleOrNull() ?: return
            val ll = LatLng(lat, lng)
            centerOn(ll)
            fetchAndShowPlace(app.getLocationPlaceId(key), app.getLocationName(key), ll)
        } else {
            markers.pinForKey(key)?.let { pin -> centerOn(pin.position); activeSheet = ActiveSheet.PinActions(key, pin.title ?: "", pin.position) }
        }
    }

    private fun fetchAndShowPlace(placeId: String, name: String, latlng: LatLng) {
        placeCache[placeId]?.let { showPlaceSheet(it, name, latlng); return }
        val fields = listOf(
            Place.Field.ID, Place.Field.NAME, Place.Field.ADDRESS, Place.Field.LAT_LNG,
            Place.Field.RATING, Place.Field.USER_RATINGS_TOTAL, Place.Field.PRICE_LEVEL,
            Place.Field.OPENING_HOURS, Place.Field.CURRENT_OPENING_HOURS, Place.Field.UTC_OFFSET,
            Place.Field.BUSINESS_STATUS, Place.Field.PHONE_NUMBER, Place.Field.WEBSITE_URI,
            Place.Field.REVIEWS, Place.Field.PHOTO_METADATAS,
        )
        placesClient.fetchPlace(FetchPlaceRequest.newInstance(placeId, fields))
            .addOnSuccessListener { resp -> placeCache[placeId] = resp.place; showPlaceSheet(resp.place, name, latlng) }
            .addOnFailureListener { toast("Couldn't load place details.") }
    }

    // Anchor the sheet on the place's canonical LatLng so the note key matches what's stored
    // (a POI tap gives the click point, which differs slightly from the establishment coords).
    private fun showPlaceSheet(place: Place, name: String, fallback: LatLng) {
        activeSheet = ActiveSheet.PlaceDetails(place, name, place.latLng ?: fallback)
    }

    private fun onPinMapClick(ll: LatLng) {
        tempMarker?.remove()
        tempMarker = googleMap?.addMarker(MarkerOptions().position(ll).icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN)))
        savePinLatLng = ll
    }

    private fun togglePinMode() {
        stats.pinMode = !stats.pinMode
        if (!stats.pinMode) { tempMarker?.remove(); tempMarker = null }
    }

    private fun cancelPin() { savePinLatLng = null; togglePinMode() }

    private fun savePin(ll: LatLng, name: String, notes: String) {
        val loc = Location("picked").apply { latitude = ll.latitude; longitude = ll.longitude }
        app.saveLocation(loc)
        val key = App.locationKey(ll.latitude, ll.longitude)
        if (name.isNotEmpty()) app.saveLocationName(key, name)
        if (notes.isNotEmpty()) app.saveNote(key, notes)
        refresh(); savePinLatLng = null; togglePinMode()
    }

    private fun openCollection(key: String) {
        if (app.collections.isEmpty()) { toast("No collections yet — create one in Saved Waypoints."); return }
        collectionKey = key
    }

    private fun isSaved(key: String) = app.myLocations.any { App.locationKey(it.latitude, it.longitude) == key }

    private fun ensureSaved(ll: LatLng, name: String, placeId: String?) {
        val key = App.locationKey(ll.latitude, ll.longitude)
        if (isSaved(key)) return
        app.saveLocation(Location("poi").apply { latitude = ll.latitude; longitude = ll.longitude })
        if (name.isNotEmpty()) app.saveLocationName(key, name)
        if (!placeId.isNullOrEmpty()) app.saveLocationPlaceId(key, placeId)
        refresh(); toast("Saved to waypoints")
    }

    /* ── Camera ─────────────────────────────────────────────────────────── */

    private fun centerOn(ll: LatLng) = animateTo(ll, null)

    private fun animateTo(ll: LatLng, zoom: Float?) {
        val c = camState ?: return
        val update = if (zoom != null) CameraUpdateFactory.newLatLngZoom(ll, zoom) else CameraUpdateFactory.newLatLng(ll)
        uiScope?.launch { c.animate(update) }
    }

    private fun handleFocusIntent(intent: Intent?) {
        if (intent == null) return
        val lat = intent.getDoubleExtra("focus_lat", 0.0)
        val lng = intent.getDoubleExtra("focus_lng", 0.0)
        if (lat != 0.0 && lng != 0.0) animateTo(LatLng(lat, lng), 18f)
    }

    /* ── Location ───────────────────────────────────────────────────────── */

    private fun setupLocationRequest() {
        locReq = buildLocReq(Priority.PRIORITY_BALANCED_POWER_ACCURACY)
        locCallBack = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) { updateUIValues(result.lastLocation) }
        }
    }

    private fun buildLocReq(priority: Int): LocationRequest =
        LocationRequest.Builder(priority, 1000L * 30).setMinUpdateIntervalMillis(1000L * 5).build()

    private fun startLocationUpdates() {
        if (!hasLocationPermission()) return
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
            fusedLocClient.requestLocationUpdates(locReq, locCallBack, null)
        updateGPS()
    }

    private fun stopLocationUpdates() {
        stats.lat = "--"; stats.lon = "--"; stats.speed = "--"; stats.address = "Not tracking"
        fusedLocClient.removeLocationUpdates(locCallBack)
    }

    private fun updateGPS() {
        if (hasLocationPermission()) {
            fusedLocClient.lastLocation.addOnSuccessListener(this) { it?.let(::updateUIValues) }
        }
    }

    private fun updateUIValues(loc: Location?) {
        if (loc == null) return
        savedLat = loc.latitude; savedLng = loc.longitude
        stats.lat = String.format("%.5f", savedLat)
        stats.lon = String.format("%.5f", savedLng)
        stats.accuracy = String.format("%.1fm", loc.accuracy)
        stats.altitude = if (loc.hasAltitude()) String.format("%.1fm", loc.altitude) else "N/A"
        stats.speed = if (loc.hasSpeed()) String.format("%.1fkm/h", loc.speed * 3.6f) else "0km/h"
        maybeGeocodeAddress(savedLat, savedLng)
        if (firstFix && savedLat != 0.0) { camState?.move(CameraUpdateFactory.newLatLngZoom(LatLng(savedLat, savedLng), 18f)); firstFix = false }
    }

    /** Reverse-geocode off the UI thread, skipping if we already resolved a spot within ~15m. */
    private fun maybeGeocodeAddress(lat: Double, lng: Double) {
        if (!lastGeoLat.isNaN()) {
            val res = FloatArray(1)
            Location.distanceBetween(lastGeoLat, lastGeoLng, lat, lng, res)
            if (res[0] < 15f) return
        }
        lastGeoLat = lat; lastGeoLng = lng
        geocodeExecutor.execute {
            val addr = try {
                @Suppress("DEPRECATION")
                Geocoder(this).getFromLocation(lat, lng, 1)?.firstOrNull()?.getAddressLine(0)
            } catch (e: Exception) { null }
            runOnUiThread {
                if (addr != null) { savedAddress = addr; stats.address = addr } else stats.address = "Unable to get address"
            }
        }
    }

    private fun hasLocationPermission() =
        ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /* ── Pickers & actions ──────────────────────────────────────────────── */

    private fun startWaypointPicker() {
        if (savedLat == 0.0 && savedLng == 0.0) return
        waypointPicker.launch(Intent(this, MapPickerActivity::class.java).apply {
            putExtra("latitude", savedLat); putExtra("longitude", savedLng); putExtra("mode", "waypoint")
        })
    }

    private fun startAddressPicker() {
        if (savedLat == 0.0 && savedLng == 0.0) return
        addressPicker.launch(Intent(this, MapPickerActivity::class.java).apply {
            putExtra("latitude", savedLat); putExtra("longitude", savedLng); putExtra("mode", "address")
        })
    }

    private fun handleWaypointResult(data: Intent) {
        val lat = data.getDoubleExtra("picked_lat", 0.0); val lng = data.getDoubleExtra("picked_lng", 0.0)
        app.saveLocation(Location("picked").apply { latitude = lat; longitude = lng })
        val key = App.locationKey(lat, lng)
        data.getStringExtra("picked_name")?.takeIf { it.isNotEmpty() }?.let { app.saveLocationName(key, it) }
        data.getStringExtra("picked_notes")?.takeIf { it.isNotEmpty() }?.let { app.saveNote(key, it) }
        refresh()
    }

    private fun handleAddressResult(data: Intent) {
        val lat = data.getDoubleExtra("picked_lat", 0.0); val lng = data.getDoubleExtra("picked_lng", 0.0)
        savedLat = lat; savedLng = lng; savedAddress = data.getStringExtra("picked_address") ?: ""
        stats.address = savedAddress; stats.savedAddress = savedAddress
        animateTo(LatLng(lat, lng), 18f)
    }

    private fun shareLocation() {
        val address = savedAddress.ifEmpty { "Unknown address" }
        val coords = String.format("%.6f, %.6f", savedLat, savedLng)
        val text = "📍 $address\n🌐 Coordinates: $coords\n🗺️ Open in Maps: https://maps.google.com/?q=$savedLat,$savedLng"
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share Location via"))
    }

    private fun toggleTheme() {
        AppCompatDelegate.setDefaultNightMode(if (isDarkMode()) AppCompatDelegate.MODE_NIGHT_NO else AppCompatDelegate.MODE_NIGHT_YES)
        recreate()
    }

    private fun logout() {
        FirebaseAuth.getInstance().signOut()
        GoogleSignIn.getClient(this, GoogleSignInOptions.DEFAULT_SIGN_IN).signOut()
        startActivity(Intent(this, LoginActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK })
        finish()
    }

    private fun refresh() { refreshKey++ }

    private fun toast(msg: String) = android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()

    private fun isDarkMode(): Boolean = when (AppCompatDelegate.getDefaultNightMode()) {
        AppCompatDelegate.MODE_NIGHT_YES -> true
        AppCompatDelegate.MODE_NIGHT_NO -> false
        else -> (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
    }
}
