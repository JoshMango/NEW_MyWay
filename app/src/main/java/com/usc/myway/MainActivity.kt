// Map home screen — fully Compose (maps-compose). The tuned marker/label/POI logic runs on the
// raw GoogleMap via MapEffect (see MapMarkerManager); sheets/dialogs are Compose.
package com.usc.myway

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Geocoder
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.speech.tts.TextToSpeech
import java.util.Locale
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
import com.google.android.gms.maps.model.LatLngBounds
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
import com.google.maps.android.compose.Polyline
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

    // Directions
    private var routeDest by mutableStateOf<LatLng?>(null)
    private var routeDestName by mutableStateOf("")
    private var routeLoading by mutableStateOf(false)
    private var routes by mutableStateOf<List<RouteResult>>(emptyList())
    private var selectedRouteIndex by mutableIntStateOf(0)
    private var routePoints by mutableStateOf<List<LatLng>>(emptyList())
    private var travelMode by mutableStateOf(TravelMode.DRIVE)
    // Live navigation
    private var navigating by mutableStateOf(false)
    private var currentStepIndex by mutableIntStateOf(0)
    private var navDistanceToNext by mutableIntStateOf(0)
    private var voiceEnabled by mutableStateOf(true)
    private var tts: TextToSpeech? = null
    private var rerouting = false
    private var offRouteCount = 0
    private var lastRerouteTime = 0L
    private var lastNavBearing = 0f

    // Heading-up (compass) mode — rotates the map to the device's facing.
    private var headingMode by mutableStateOf(false)
    private var sensorManager: SensorManager? = null
    private var rotationSensor: Sensor? = null
    private var smoothedAz = Float.NaN
    private var lastAppliedBearing = Float.NaN
    private val orientationListener = object : SensorEventListener {
        private val rot = FloatArray(9)
        private val orient = FloatArray(3)
        override fun onSensorChanged(e: SensorEvent) {
            if (e.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
            SensorManager.getRotationMatrixFromVector(rot, e.values)
            SensorManager.getOrientation(rot, orient)
            var az = Math.toDegrees(orient[0].toDouble()).toFloat()
            if (az < 0f) az += 360f
            handleAzimuth(az)
        }
        override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    }

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
        if (!Places.isInitialized()) Places.initializeWithNewPlacesApiEnabled(applicationContext, BuildConfig.MAPS_API_KEY)
        placesClient = Places.createClient(this)
        tts = TextToSpeech(this) { status -> if (status == TextToSpeech.SUCCESS) tts?.language = Locale.getDefault() }
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
        if (headingMode) sensorManager?.registerListener(orientationListener, rotationSensor, SensorManager.SENSOR_DELAY_UI)
    }

    override fun onPause() {
        super.onPause()
        if (headingMode) sensorManager?.unregisterListener(orientationListener)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleFocusIntent(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        geocodeExecutor.shutdownNow()
        tts?.shutdown()
        sensorManager?.unregisterListener(orientationListener)
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

                if (routePoints.isNotEmpty()) {
                    Polyline(points = routePoints, color = Teal, width = 16f)
                }
            }

            val directionsActive = routeDest != null

            // Top: floating hamburger + search only (hidden while navigating).
            if (!directionsActive) {
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
            }

            // Top: live maneuver banner while navigating.
            if (navigating) {
                Box(Modifier.align(Alignment.TopCenter).fillMaxWidth()) {
                    NavBanner(
                        step = routes.getOrNull(selectedRouteIndex)?.steps?.getOrNull(currentStepIndex),
                        distanceToNext = navDistanceToNext,
                        voiceOn = voiceEnabled,
                        onToggleVoice = { voiceEnabled = !voiceEnabled },
                    )
                }
            }

            // Bottom: nav footer while navigating, planning panel while routing, else stats + controls.
            if (directionsActive) {
                Box(Modifier.align(Alignment.BottomCenter)) {
                    if (navigating) {
                        routes.getOrNull(selectedRouteIndex)?.let { r ->
                            Column(Modifier.fillMaxWidth()) {
                                // Recenter button — snap back to following your position/heading.
                                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 0.dp).padding(bottom = 10.dp),
                                    horizontalArrangement = Arrangement.End) {
                                    CircleButton({ recenterNav() }) { Text("🧭", fontSize = 18.sp) }
                                }
                                NavFooter(r, currentStepIndex) { exitNavigation() }
                            }
                        }
                    } else {
                        DirectionsPanel(
                            destName = routeDestName,
                            mode = travelMode,
                            loading = routeLoading,
                            routes = routes,
                            selectedIndex = selectedRouteIndex,
                            onMode = { changeTravelMode(it) },
                            onSelectRoute = { selectRoute(it) },
                            onStart = { startNavigation() },
                            onClose = { closeDirections() },
                        )
                    }
                }
            } else {
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 12.dp)) {
                    Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.End) {
                        MapControls(
                            bearing = bearing,
                            headingMode = headingMode,
                            showHeading = stats.tracking,
                            onCompass = { scope.launch { cam.animate(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder(cam.position).bearing(0f).tilt(0f).build())) } },
                            onToggleHeading = { applyHeadingMode(!headingMode) },
                            onMyLocation = { if (savedLat != 0.0 || savedLng != 0.0) animateTo(LatLng(savedLat, savedLng), 17f) },
                        )
                    }
                    BottomCard(stats, statsActions)
                }
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
    private fun MapControls(
        bearing: Float,
        headingMode: Boolean,
        showHeading: Boolean,
        onCompass: () -> Unit,
        onToggleHeading: () -> Unit,
        onMyLocation: () -> Unit,
    ) {
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // Reset-north arrow (points to north) — only when rotated and not in heading mode.
            if (bearing != 0f && !headingMode) {
                CircleButton(onCompass) { Text("⬆️", fontSize = 18.sp, modifier = Modifier.rotate(-bearing)) }
            }
            // Heading-up toggle (highlighted when active).
            if (showHeading) {
                CircleButton(onToggleHeading, active = headingMode) { Text("🧭", fontSize = 18.sp) }
            }
            CircleButton(onMyLocation) { Text("📍", fontSize = 18.sp) }
        }
    }

    @Composable
    private fun CircleButton(onClick: () -> Unit, active: Boolean = false, content: @Composable () -> Unit) {
        Box(
            Modifier.size(44.dp).shadow(4.dp, CircleShape).clip(CircleShape)
                .background(if (active) Teal else MaterialTheme.colorScheme.surface).clickable(onClick = onClick),
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
                onDirections = { activeSheet = null; startDirections(s.latLng, s.title) },
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
                    onDirections = { activeSheet = null; startDirections(s.latLng, s.name) },
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
        override fun onProfile() { drawerOpen = false; startActivity(Intent(this@MainActivity, ProfileActivity::class.java)) }
        override fun onFriends() { drawerOpen = false; startActivity(Intent(this@MainActivity, FriendsActivity::class.java)) }
        override fun onToggleTheme() { toggleTheme() }
        override fun onLogout() { logout() }
        override fun onTrackingChanged(enabled: Boolean) { stats.tracking = enabled; if (enabled) startLocationUpdates() else { applyHeadingMode(false); stopLocationUpdates() } }
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

    /* ── Directions ─────────────────────────────────────────────────────── */

    private fun startDirections(dest: LatLng, name: String) {
        if (savedLat == 0.0 && savedLng == 0.0) { toast("Waiting for your location…"); return }
        routeDest = dest; routeDestName = name; travelMode = TravelMode.DRIVE
        fetchRouteAndShow()
    }

    private fun changeTravelMode(mode: TravelMode) {
        if (mode == travelMode) return
        travelMode = mode
        fetchRouteAndShow()
    }

    private fun fetchRouteAndShow() {
        val dest = routeDest ?: return
        routeLoading = true; routes = emptyList(); routePoints = emptyList()
        uiScope?.launch {
            val result = fetchRoute(LatLng(savedLat, savedLng), dest, travelMode, BuildConfig.MAPS_API_KEY)
            if (routeDest != dest) return@launch // user closed/changed while loading
            routeLoading = false
            if (result.isEmpty()) { toast("Couldn't find a route."); return@launch }
            routes = result; selectedRouteIndex = 0; routePoints = result[0].points
            fitToRoute(result[0].points)
        }
    }

    private fun selectRoute(i: Int) {
        if (i !in routes.indices) return
        selectedRouteIndex = i; routePoints = routes[i].points
        if (!navigating) fitToRoute(routes[i].points)
    }

    private fun fitToRoute(points: List<LatLng>) {
        if (points.isEmpty()) return
        val b = LatLngBounds.Builder().apply { points.forEach { include(it) } }.build()
        uiScope?.launch { camState?.animate(CameraUpdateFactory.newLatLngBounds(b, 140)) }
    }

    private fun closeDirections() {
        if (navigating) { navigating = false; tts?.stop(); exitNavLocationUpdates() }
        routeDest = null; routeDestName = ""; routes = emptyList(); routePoints = emptyList()
        routeLoading = false; currentStepIndex = 0; navDistanceToNext = 0
    }

    /* ── Live turn-by-turn navigation ───────────────────────────────────── */

    private fun startNavigation() {
        val route = routes.getOrNull(selectedRouteIndex) ?: return
        applyHeadingMode(false) // nav orients to movement instead
        navigating = true; currentStepIndex = 0
        navDistanceToNext = route.steps.firstOrNull()?.distanceMeters ?: 0
        rerouting = false; offRouteCount = 0; lastRerouteTime = System.currentTimeMillis()
        enterNavLocationUpdates()
        route.steps.firstOrNull()?.let { speak(it.instruction) }
        updateNavigationCamera(LatLng(savedLat, savedLng), camState?.position?.bearing ?: 0f)
    }

    private fun exitNavigation() {
        navigating = false
        tts?.stop()
        exitNavLocationUpdates()
        routes.getOrNull(selectedRouteIndex)?.let { fitToRoute(it.points) }
    }

    /** Called from updateUIValues on each fix while navigating: reroute/re-time, follow, advance steps. */
    private fun updateNavigation(loc: Location) {
        val route = routes.getOrNull(selectedRouteIndex) ?: return
        val here = LatLng(loc.latitude, loc.longitude)
        val now = System.currentTimeMillis()

        // Off-route reroute (>50m off the path for 2 fixes) vs. live traffic re-timing (periodic, DRIVE).
        val offPath = distanceToPathMeters(here, routePoints)
        if (offPath > 50f) {
            offRouteCount++
            if (offRouteCount >= 2 && !rerouting && now - lastRerouteTime > 8000L) reroute(announce = true)
        } else {
            offRouteCount = 0
            if (travelMode == TravelMode.DRIVE && !rerouting && now - lastRerouteTime > 90000L) reroute(announce = false)
        }

        val bearing = if (loc.hasBearing()) loc.bearing else lastNavBearing
        lastNavBearing = bearing
        updateNavigationCamera(here, bearing)

        val steps = route.steps
        if (currentStepIndex >= steps.size) return
        val step = steps[currentStepIndex]
        if (step.endLat == 0.0 && step.endLng == 0.0) return
        val res = FloatArray(1)
        Location.distanceBetween(loc.latitude, loc.longitude, step.endLat, step.endLng, res)
        navDistanceToNext = res[0].toInt()
        if (res[0] < 25f) { // reached this maneuver — advance & announce the next one
            currentStepIndex++
            if (currentStepIndex < steps.size) speak(steps[currentStepIndex].instruction)
            else speak("You have arrived at your destination")
        }
    }

    /** Re-fetch from the current position → destination. Powers both off-route rerouting (voiced)
     *  and live traffic re-timing (silent, periodic). The fresh route starts at the user, so the
     *  first step is the upcoming maneuver and the ETA reflects current traffic. */
    private fun reroute(announce: Boolean) {
        val dest = routeDest ?: return
        if (rerouting) return
        rerouting = true
        lastRerouteTime = System.currentTimeMillis()
        if (announce) speak("Rerouting")
        uiScope?.launch {
            val result = fetchRoute(LatLng(savedLat, savedLng), dest, travelMode, BuildConfig.MAPS_API_KEY)
            rerouting = false
            if (!navigating || result.isEmpty()) return@launch
            routes = result; selectedRouteIndex = 0; routePoints = result[0].points
            currentStepIndex = 0; offRouteCount = 0
            navDistanceToNext = result[0].steps.firstOrNull()?.distanceMeters ?: 0
        }
    }

    /* ── Heading-up (compass) mode ──────────────────────────────────────── */

    private fun applyHeadingMode(on: Boolean) {
        if (on == headingMode) return
        if (on) {
            if (sensorManager == null) sensorManager = getSystemService(SensorManager::class.java)
            if (rotationSensor == null) rotationSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            if (rotationSensor == null) { toast("Compass not available on this device."); return }
            headingMode = true
            smoothedAz = Float.NaN; lastAppliedBearing = Float.NaN
            sensorManager?.registerListener(orientationListener, rotationSensor, SensorManager.SENSOR_DELAY_UI)
        } else {
            headingMode = false
            sensorManager?.unregisterListener(orientationListener)
        }
    }

    /** Low-pass + threshold the compass azimuth, then rotate the map so facing = screen-up. */
    private fun handleAzimuth(raw: Float) {
        smoothedAz = if (smoothedAz.isNaN()) raw else {
            var d = raw - smoothedAz
            if (d > 180f) d -= 360f else if (d < -180f) d += 360f
            var v = smoothedAz + d * 0.2f
            if (v < 0f) v += 360f else if (v >= 360f) v -= 360f
            v
        }
        if (!lastAppliedBearing.isNaN()) {
            var dd = smoothedAz - lastAppliedBearing
            if (dd > 180f) dd -= 360f else if (dd < -180f) dd += 360f
            if (kotlin.math.abs(dd) < 1.5f) return
        }
        lastAppliedBearing = smoothedAz
        val c = camState ?: return
        c.move(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder(c.position).bearing(smoothedAz).build()))
    }

    private fun updateNavigationCamera(target: LatLng, bearing: Float) {
        val pos = CameraPosition.Builder().target(target).zoom(17.5f).tilt(50f).bearing(bearing).build()
        uiScope?.launch { camState?.animate(CameraUpdateFactory.newCameraPosition(pos), 800) }
    }

    /** Snap the camera back to following the user (after they panned the map during nav). */
    private fun recenterNav() {
        if (savedLat != 0.0 || savedLng != 0.0) updateNavigationCamera(LatLng(savedLat, savedLng), lastNavBearing)
    }

    private fun enterNavLocationUpdates() {
        if (!hasLocationPermission()) return
        fusedLocClient.removeLocationUpdates(locCallBack)
        val navReq = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L).setMinUpdateIntervalMillis(500L).build()
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
            fusedLocClient.requestLocationUpdates(navReq, locCallBack, mainLooper)
    }

    private fun exitNavLocationUpdates() {
        fusedLocClient.removeLocationUpdates(locCallBack)
        if (sidebar.tracking) startLocationUpdates()
    }

    private fun speak(text: String) {
        if (!voiceEnabled || text.isEmpty()) return
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "nav")
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
        if (navigating) { updateNavigation(loc); return }
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
