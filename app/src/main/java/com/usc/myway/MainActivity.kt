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
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import java.util.Locale
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresPermission
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.LaunchedEffect
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
import com.google.firebase.firestore.ListenerRegistration
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
    data class PlaceDetails(val place: Place, val name: String, val latLng: LatLng, val fromShare: Boolean = false) : ActiveSheet
    data class TripPinActions(val pin: Trip.TripPin) : ActiveSheet
}

// A session pin being created (id == null) or edited (id != null).
private data class TripPinDraft(val id: String?, val lat: Double, val lng: Double, val name: String, val note: String)

// A personal pin the user is sharing into a group's chat. placeId set for landmarks.
private data class ShareTarget(val lat: Double, val lng: Double, val name: String, val note: String, val placeId: String)

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
    private val tripLayer = TripLayer(this)
    private var googleMap: GoogleMap? = null
    private var camState: CameraPositionState? = null
    private var uiScope: CoroutineScope? = null
    private var firstFix = true
    private var tempMarker: Marker? = null

    private val placeCache = HashMap<String, Place>()
    private val photoCache = HashMap<String, Bitmap>()
    private val iconCache = HashMap<String, Bitmap>() // for landmarks
    private val isOpenCache = HashMap<String, Boolean>()

    private var savedLat = 0.0
    private var savedLng = 0.0

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
    private var tripPinDraft by mutableStateOf<TripPinDraft?>(null)
    private var shareTarget by mutableStateOf<ShareTarget?>(null)

    // Directions
    private var routeDest by mutableStateOf<LatLng?>(null)
    private var routeDestName by mutableStateOf("")
    private var routeLoading by mutableStateOf(false)
    private var routes by mutableStateOf<List<RouteResult>>(emptyList())
    private var selectedRouteIndex by mutableIntStateOf(0)
    private var routePoints by mutableStateOf<List<LatLng>>(emptyList())
    private var travelMode by mutableStateOf(TravelMode.DRIVE)
    private var routeIsTrip by mutableStateOf(false)                 // current route is the shared trip direction
    private var directionChoice by mutableStateOf<Pair<LatLng, String>?>(null) // pending "trip vs me only" prompt
    private var incomingTripDest by mutableStateOf<Trip.TripDest?>(null)        // someone else set a trip direction → offer to join
    private var showTripRoster by mutableStateOf(false)                         // tapping the live-trip bar → who's here
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

    // Group Trip (live location sharing). Source of truth is trip_participants/{myUid} in Firestore.
    private val myUid get() = FirebaseAuth.getInstance().currentUser?.uid ?: ""
    private val myTag get() = app.getUserTag(myUid)
    private var myTripListener: ListenerRegistration? = null
    private var tripMembersListener: ListenerRegistration? = null
    private var tripPinsListener: ListenerRegistration? = null
    private var tripDestListener: ListenerRegistration? = null
    private var tripOffersListener: ListenerRegistration? = null
    private var pendingOffer by mutableStateOf<Trip.TripOffer?>(null)   // collection-share modal
    private val handledOffers = mutableSetOf<String>()
    private var lastOffers: List<Trip.TripOffer> = emptyList()
    private var tripPlanListener: ListenerRegistration? = null
    private var tripPlan by mutableStateOf<Trip.TripPlan?>(null)        // shared objective queue
    private var showPlanSheet by mutableStateOf(false)
    private var routeIsPlan by mutableStateOf(false)                    // current trip direction is plan-driven (auto)
    private var currentTripGid by mutableStateOf<String?>(null)
    // Live location share (Messenger-style) — one live_shares/{myUid} doc, independent of trips.
    private var liveShareListener: ListenerRegistration? = null
    private var liveShare by mutableStateOf<LiveShare.State?>(null)
    private var showLiveShareDialog by mutableStateOf(false)
    private var tripGroupName by mutableStateOf("")
    private var tripGroupPhoto by mutableStateOf("")
    private var tripMembers by mutableStateOf<List<Trip.Member>>(emptyList())
    private var tripPins by mutableStateOf<List<Trip.TripPin>>(emptyList())

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
    // POST_NOTIFICATIONS (Android 13+); result ignored — notifications just stay off if declined.
    private val notifPermLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        fusedLocClient = LocationServices.getFusedLocationProviderClient(this)
        if (!Places.isInitialized()) Places.initializeWithNewPlacesApiEnabled(applicationContext, BuildConfig.MAPS_API_KEY)
        placesClient = Places.createClient(this)
        tts = TextToSpeech(this) { status -> if (status == TextToSpeech.SUCCESS) tts?.language = Locale.getDefault() }
        setupLocationRequest()

        sidebar.darkMode = app.isDarkMode(); sidebar.tracking = true
        // Drawer profile header — tag/photo/banner from cache instantly; name from a one-shot profile fetch.
        loadSidebarProfile()
        if (myUid.isNotEmpty()) Profiles.fetchProfile(myUid) { p ->
            if (p != null) {
                sidebar.userName = "${p.firstName} ${p.lastName}".trim()
                sidebar.userTag = p.tag.ifBlank { sidebar.userTag }
                sidebar.userPhoto = p.photo
                app.setUserPhoto(myUid, p.photo)
            }
        }
        hasLocationPerm = hasLocationPermission()
        if (!hasLocationPerm) permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        else startLocationUpdates()

        // Group notifications: ask for POST_NOTIFICATIONS (13+) and start the app-wide watcher.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (myUid.isNotEmpty()) { app.bindUser(myUid); NotificationHub.start(this, myUid); FcmTokens.register(myUid) }

        setContent { MyWayTheme { MainScreen() } }
    }

    override fun onStart() {
        super.onStart()
        if (myUid.isNotEmpty()) {
            myTripListener = Trip.listenMyTrip(myUid) { gid -> onMyTripChanged(gid) }
            liveShareListener = LiveShare.listen(myUid) { onLiveShareChanged(it) }
        }
    }

    override fun onStop() {
        super.onStop()
        myTripListener?.remove(); tripMembersListener?.remove(); tripPinsListener?.remove(); tripDestListener?.remove(); tripOffersListener?.remove(); tripPlanListener?.remove()
        myTripListener = null; tripMembersListener = null; tripPinsListener = null; tripDestListener = null; tripOffersListener = null; tripPlanListener = null
        liveShareListener?.remove(); liveShareListener = null
    }

    private fun onLiveShareChanged(s: LiveShare.State?) {
        // A stale doc past its hour → tear it down (also stops any viewers). Otherwise reflect state.
        if (s != null && !s.active) { LiveShare.stop(myUid); liveShare = null; stats.sharingLive = false; return }
        liveShare = s
        stats.sharingLive = s != null
    }

    /** Cheap prefs read — picks up a photo/banner changed in ProfileActivity the moment we come back. */
    private fun loadSidebarProfile() {
        sidebar.userTag = app.getUserTag(myUid)
        sidebar.userPhoto = app.getUserPhoto(myUid)
        sidebar.userBanner = app.getUserBanner(myUid)
    }

    override fun onResume() {
        super.onResume()
        loadSidebarProfile()
        refresh()
        handleFocusIntent(intent)
        handleSharedPlaceIntent(intent)
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
        handleSharedPlaceIntent(intent)
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
        val dark = app.isDarkMode()
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
                    refreshMap(map, dark)
                }
                MapEffect(refreshKey, dark, app.dataVersion) { map -> refreshMap(map, dark) }

                if (routePoints.isNotEmpty()) {
                    Polyline(points = routePoints, color = Teal, width = 16f)
                }
            }

            // RPG-style arrows to off-screen trip members (cleared automatically when they're visible / not on a trip).
            if (currentTripGid != null && !navigating) TripMemberArrows(cam, tripMembers, myUid)

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
                        SearchBar(placesClient) { placeId, name, ll -> animateTo(ll, 16f); fetchAndShowPlace(placeId, name, ll) }
                    }
                }
            }

            // Live-trip bar — sits under the search row; tap Leave to go offline. Plan pill below it.
            if (currentTripGid != null && !navigating) {
                Column(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 66.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    TripLiveBar(tripGroupName, tripMembers.size, onClick = { showTripRoster = true }, onLeave = { leaveTrip() })
                    Spacer(Modifier.height(8.dp))
                    val planLabel = tripPlan?.let { p ->
                        if (p.archived) "📋 Plan complete" else "📋 Plan · ${p.items.count { it.finished }}/${p.items.size}"
                    } ?: "📋 New plan"
                    Box(
                        Modifier.shadow(4.dp, RoundedCornerShape(20.dp)).clip(RoundedCornerShape(20.dp))
                            .background(MaterialTheme.colorScheme.surface).clickable { showPlanSheet = true }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    ) { Text(planLabel, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface) }
                }
            }

            // Messenger-style chat bubble → the trip's group chat. Only while on a trip.
            if (currentTripGid != null && !navigating) {
                Box(
                    Modifier.align(Alignment.CenterEnd).padding(end = 12.dp)
                        .shadow(6.dp, CircleShape).clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface)
                        .clickable { openTripChat() }
                        .padding(3.dp),
                ) { AvatarCircle(tripGroupPhoto, tripGroupName, size = 50.dp) }
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
                            isTripDirection = routeIsTrip,
                            isPlanStop = routeIsPlan,
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

            // Offline banner — Google Maps style: thin bar pinned to the very top, above everything else.
            AnimatedVisibility(
                visible = !app.isOnline,
                modifier = Modifier.align(Alignment.TopCenter),
                enter = fadeIn(), exit = fadeOut(),
            ) { ConnectivityBanner() }

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
    private fun TripLiveBar(groupName: String, memberCount: Int, onClick: () -> Unit, onLeave: () -> Unit) {
        Row(
            Modifier.shadow(6.dp, RoundedCornerShape(22.dp)).clip(RoundedCornerShape(22.dp))
                .background(MaterialTheme.colorScheme.surface).padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(Modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("🔴", fontSize = 12.sp)
                Spacer(Modifier.width(8.dp))
                Text(
                    buildString {
                        append("On trip")
                        if (groupName.isNotEmpty()) append(" · ").append(groupName)
                        append("  ·  ").append(memberCount).append(" here")
                    },
                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.width(10.dp))
            Box(
                Modifier.clip(RoundedCornerShape(16.dp)).background(Color(0xFFEF4444)).clickable(onClick = onLeave)
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            ) { Text("Leave", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White) }
        }
    }

    @Composable
    private fun ConnectivityBanner() {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().background(Color(0xFF3C4043)).padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(Modifier.size(14.dp), color = Color.White, strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text("Reconnecting…", fontSize = 13.sp, color = Color.White)
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
                onShareToGroup = {
                    activeSheet = null
                    shareTarget = ShareTarget(s.latLng.latitude, s.latLng.longitude, s.title,
                        app.locationNotes[s.key] ?: "", app.getLocationPlaceId(s.key))
                },
                onDelete = { activeSheet = null; deleteKey = s.key },
            )
            is ActiveSheet.PlaceDetails -> {
                val key = App.locationKey(s.latLng.latitude, s.latLng.longitude)
                val inTrip = currentTripGid != null
                PlaceDetailsSheet(
                    place = s.place, name = s.name,
                    userNote = if (inTrip) "" else app.locationNotes[key] ?: "",
                    isSaved = if (inTrip) false else isSaved(key),
                    placesClient = placesClient, photoCache = photoCache, isOpenCache = isOpenCache,
                    onDismiss = { activeSheet = null },
                    onDirections = { activeSheet = null; startDirections(s.latLng, s.name) },
                    // On a trip → a note on this place becomes a shared session pin, never personal.
                    onNote = {
                        activeSheet = null
                        if (inTrip) tripPinDraft = TripPinDraft(null, s.latLng.latitude, s.latLng.longitude, s.name, "")
                        else { ensureSaved(s.latLng, s.name, s.place.id); noteKey = key }
                    },
                    onCollection = {
                        activeSheet = null
                        if (inTrip) toast("Collections aren't available during a trip")
                        else { ensureSaved(s.latLng, s.name, s.place.id); openCollection(key) }
                    },
                    onShareToGroup = {
                        activeSheet = null
                        shareTarget = ShareTarget(s.latLng.latitude, s.latLng.longitude, s.name,
                            app.locationNotes[key] ?: "", s.place.id ?: "")
                    },
                    canAddToMap = s.fromShare,
                    onAddToMap = { activeSheet = null; ensureSaved(s.latLng, s.name, s.place.id) },
                    onDelete = { activeSheet = null; deleteKey = key },
                )
            }
            is ActiveSheet.TripPinActions -> TripPinActionsDialog(
                pin = s.pin,
                onDismiss = { activeSheet = null },
                onDirections = { activeSheet = null; startDirections(LatLng(s.pin.lat, s.pin.lng), s.pin.name.ifEmpty { "Shared pin" }) },
                onEdit = { activeSheet = null; tripPinDraft = TripPinDraft(s.pin.id, s.pin.lat, s.pin.lng, s.pin.name, s.pin.note) },
                onDelete = { activeSheet = null; currentTripGid?.let { Trip.deletePin(it, s.pin.id) {} } },
            )
            null -> {}
        }
    }

    @Composable
    private fun Dialogs() {
        noteKey?.let { key ->
            var text by remember { mutableStateOf(app.locationNotes[key] ?: "") }
            AlertDialog(
                onDismissRequest = { noteKey = null },
                title = { Text("✎ Edit Note") },
                text = {
                    Column {
                        OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
                        Box(Modifier.padding(top = 4.dp)) {
                            EmojiPickerButton { emoji -> text += emoji }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { app.saveNote(key, text.trim()); refresh(); noteKey = null }) { Text("Save") } },
                dismissButton = { TextButton(onClick = { noteKey = null }) { Text("Cancel") } },
            )
        }
        collectionKey?.let { key ->
            val collections = app.collections
            var creating by remember { mutableStateOf(false) }
            var newName by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { collectionKey = null },
                title = { Text(if (creating) "New Collection" else "Add to Collection") },
                text = {
                    if (creating) {
                        OutlinedTextField(newName, { newName = it }, Modifier.fillMaxWidth(),
                            label = { Text("Collection name") }, singleLine = true, shape = RoundedCornerShape(12.dp))
                    } else androidx.compose.foundation.layout.Column {
                        CollectionRow("❌ None") { app.setPinCollection(key, null); refresh(); collectionKey = null }
                        collections.forEach { c ->
                            val mark = if (c.locationKeys.contains(key)) "  ✓" else ""
                            CollectionRow("${c.icon} ${c.name}$mark") {
                                app.setPinCollection(key, c); refresh(); collectionKey = null
                            }
                        }
                        CollectionRow("➕ New collection…") { creating = true }
                    }
                },
                confirmButton = {
                    if (creating) TextButton(onClick = {
                        val n = newName.trim()
                        if (n.isNotEmpty()) {
                            app.setPinCollection(key, null)                            // one collection per pin
                            app.saveCollection(Collection(n, "📁").apply { locationKeys.add(key) })
                            refresh(); collectionKey = null
                        }
                    }) { Text("Create") }
                },
                dismissButton = { TextButton(onClick = { if (creating) creating = false else collectionKey = null }) { Text(if (creating) "Back" else "Close") } },
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
        tripPinDraft?.let { d -> TripPinDialog(d) }
        shareTarget?.let { t -> ShareToGroupDialog(t) }
        if (showLiveShareDialog) LiveShareDialog()
        if (showPlanSheet && currentTripGid != null) {
            PlanSheet(
                plan = tripPlan,
                placesClient = placesClient,
                onCreate = { name -> currentTripGid?.let { Trip.createPlan(it, name, myUid, myTag) { e -> if (e != null) toast("Couldn't create plan: $e") } } },
                onAddItem = { n, lat, lng -> currentTripGid?.let { Trip.addPlanItem(it, n, lat, lng, myUid, myTag) { e -> if (e != null) toast("Couldn't add: $e") } } },
                onToggle = { id, fin -> currentTripGid?.let { Trip.setItemFinished(it, id, fin, myUid, myTag) { e -> if (e != null) toast(e) } } },
                onPause = { p -> currentTripGid?.let { Trip.setPlanPaused(it, p, myUid, myTag) { e -> if (e != null) toast(e) } } },
                onDismiss = { showPlanSheet = false },
            )
        }
        pendingOffer?.let { offer ->
            AlertDialog(
                onDismissRequest = { dismissOffer(offer) },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
                        AvatarCircle(photo = offer.fromPhoto, fallback = "@${offer.fromTag}", size = 36.dp)
                        Text("@${offer.fromTag} shared a collection", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                },
                text = { Text("Add “${offer.name}” — ${offer.pins.size} place${if (offer.pins.size == 1) "" else "s"} — to this trip? Everyone on the trip will see them.") },
                confirmButton = { TextButton(onClick = { acceptOffer(offer) }) { Text("Add", color = Teal, fontWeight = FontWeight.Bold) } },
                dismissButton = { TextButton(onClick = { dismissOffer(offer) }) { Text("Dismiss") } },
            )
        }
        directionChoice?.let { (dest, name) ->
            TripDirectionDialog(
                name = name,
                onTrip = {
                    directionChoice = null
                    val planActive = tripPlan?.archived == false
                    currentTripGid?.let { gid ->
                        if (planActive) Trip.prependPlanItem(gid, name, dest.latitude, dest.longitude, myUid, myTag) { err ->
                            if (err != null) toast("Couldn't add to plan: $err")
                        } else Trip.setTripDest(gid, dest.latitude, dest.longitude, name, myUid, myTag) { err ->
                            if (err != null) toast("Couldn't set trip direction: $err")
                        }
                    }
                    beginDirections(dest, name, isTrip = true, isPlan = planActive)
                },
                onMeOnly = { directionChoice = null; beginDirections(dest, name, isTrip = false) },
                onDismiss = { directionChoice = null },
            )
        }
        if (showTripRoster) {
            AlertDialog(
                onDismissRequest = { showTripRoster = false },
                title = { Text("On the trip · ${tripMembers.size} here", fontWeight = FontWeight.Bold) },
                text = {
                    if (tripMembers.isEmpty()) {
                        Text("No one is sharing their location right now.")
                    } else {
                        androidx.compose.foundation.layout.Column {
                            tripMembers.forEach { mbr ->
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp),
                                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
                                    AvatarCircle(photo = mbr.photo, fallback = "@${mbr.tag}", size = 32.dp)
                                    Text(if (mbr.uid == myUid) "@${mbr.tag} (you)" else "@${mbr.tag}",
                                        fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
                                }
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { showTripRoster = false }) { Text("Close") } },
            )
        }
        incomingTripDest?.let { dest ->
            IncomingTripDirectionDialog(
                byLabel = "@${dest.byTag.ifEmpty { "someone" }}",
                byPhoto = tripMembers.firstOrNull { it.uid == dest.by }?.photo ?: "",
                destName = dest.name,
                onJoin = {
                    incomingTripDest = null
                    beginDirections(LatLng(dest.lat, dest.lng), dest.name.ifEmpty { "Trip destination" }, isTrip = true)
                },
                onDismiss = {
                    incomingTripDest = null
                    currentTripGid?.let { Trip.endTripDestForMe(it, myUid) } // opting out counts as done so it can clear
                },
            )
        }
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
                    Column {
                        OutlinedTextField(notes, { notes = it }, Modifier.fillMaxWidth(), label = { Text("Notes") },
                            minLines = 2, maxLines = 4, shape = RoundedCornerShape(12.dp))
                        Box(Modifier.padding(top = 4.dp)) {
                            EmojiPickerButton { emoji -> notes += emoji }
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End) {
                        TextButton(onClick = { cancelPin() }) { Text("Cancel") }
                        TextButton(onClick = { savePin(ll, name.trim(), notes.trim()) }) { Text("Save", color = Teal, fontWeight = FontWeight.Bold) }
                    }
                }
            }
        }
    }

    /** Action panel for a shared session pin — any member can edit or delete it. */
    @Composable
    private fun TripPinActionsDialog(
        pin: Trip.TripPin,
        onDismiss: () -> Unit,
        onDirections: () -> Unit,
        onEdit: () -> Unit,
        onDelete: () -> Unit,
    ) {
        Dialog(onDismissRequest = onDismiss) {
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.padding(20.dp)) {
                    Text(pin.name.ifEmpty { "Shared pin" }, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(top = 4.dp)) {
                        AvatarCircle(photo = pin.fromPhoto, fallback = "@${pin.fromTag}", size = 22.dp)
                        Text("Shared by @${pin.fromTag}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    }
                    if (pin.note.isNotEmpty()) Text("✎ ${pin.note}", fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(top = 10.dp))
                    Spacer(Modifier.padding(top = 8.dp))
                    TripActionRow("🧭  Directions", Teal, onDirections)
                    TripActionRow("✎  Edit note", Teal, onEdit)
                    TripActionRow("🗑  Delete", Color(0xFFEF4444), onDelete)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End) {
                        TextButton(onClick = onDismiss) { Text("Close") }
                    }
                }
            }
        }
    }

    @Composable
    private fun TripActionRow(label: String, color: Color, onClick: () -> Unit) {
        Text(label, Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
            color = color, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }

    /** Create (id == null) or edit a shared session pin. */
    @Composable
    private fun TripPinDialog(draft: TripPinDraft) {
        var name by remember(draft) { mutableStateOf(draft.name) }
        var note by remember { mutableStateOf(draft.note) }
        Dialog(onDismissRequest = { tripPinDraft = null }) {
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.padding(20.dp)) {
                    Text(if (draft.id == null) "📌 New Trip Pin" else "✏️ Edit Trip Pin",
                        fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    Text("Shared with everyone on the trip", fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), modifier = Modifier.padding(bottom = 12.dp))
                    OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Name") },
                        singleLine = true, shape = RoundedCornerShape(12.dp))
                    Spacer(Modifier.padding(top = 8.dp))
                    Column {
                        OutlinedTextField(note, { note = it }, Modifier.fillMaxWidth(), label = { Text("Note") },
                            minLines = 2, maxLines = 4, shape = RoundedCornerShape(12.dp))
                        Box(Modifier.padding(top = 4.dp)) {
                            EmojiPickerButton { emoji -> note += emoji }
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End) {
                        TextButton(onClick = { tripPinDraft = null }) { Text("Cancel") }
                        TextButton(onClick = { saveTripPin(draft, name.trim(), note.trim()) }) { Text("Save", color = Teal, fontWeight = FontWeight.Bold) }
                    }
                }
            }
        }
    }

    /** Facebook-style "share": pick one of my groups and post this personal pin into its chat. */
    @Composable
    private fun ShareToGroupDialog(target: ShareTarget) {
        var groups by remember { mutableStateOf<List<Group>?>(null) }
        LaunchedEffect(Unit) { Groups.fetchMyGroups(myUid) { groups = it } }
        AlertDialog(
            onDismissRequest = { shareTarget = null },
            title = { Text("Share to group") },
            text = {
                val list = groups
                when {
                    list == null -> Text("Loading your groups…", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    list.isEmpty() -> Text("You're not in any groups yet.", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    else -> Column {
                        list.forEach { g ->
                            Row(Modifier.fillMaxWidth().clickable {
                                Groups.sharePin(g.id, myUid, myTag, target.lat, target.lng, target.name, target.note, target.placeId)
                                toast("Shared to ${g.name}")
                                shareTarget = null
                            }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                AvatarCircle(photo = g.photo, fallback = g.name, size = 34.dp)
                                Spacer(Modifier.width(10.dp))
                                Text("${g.name}  ·  ${g.members.size} member${if (g.members.size == 1) "" else "s"}",
                                    fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { shareTarget = null }) { Text("Cancel") } },
        )
    }

    /** Pick targeting for your live location share: groups, all friends, close friends, or individuals. */
    @Composable
    private fun LiveShareDialog() {
        var groups by remember { mutableStateOf<List<Group>?>(null) }
        var friends by remember { mutableStateOf<List<UserHit>?>(null) }

        // Selection state
        val selGroups = remember { androidx.compose.runtime.mutableStateListOf<String>().apply { liveShare?.groups?.let { addAll(it) } } }
        val selUids = remember { androidx.compose.runtime.mutableStateListOf<String>().apply { liveShare?.uids?.let { addAll(it) } } }
        var allFriends by remember { mutableStateOf(liveShare?.allFriends ?: false) }
        var closeFriends by remember { mutableStateOf(liveShare?.closeFriends ?: false) }

        LaunchedEffect(Unit) {
            Groups.fetchMyGroups(myUid) { groups = it }
            // One-shot fetch for friends list to populate the picker
            val reg = Friends.listenFriends(myUid) { friends = it }
            // Since we only need it once for the dialog, we'll keep it active while open
        }

        val active = liveShare?.active == true
        val onSurface = MaterialTheme.colorScheme.onSurface

        AlertDialog(
            onDismissRequest = { showLiveShareDialog = false },
            title = { Text(if (active) "Live location" else "Share live location") },
            text = {
                Column {
                    Text("Choose who can see your live location for 1 hour:",
                        fontSize = 13.sp, color = onSurface.copy(alpha = 0.7f), modifier = Modifier.padding(bottom = 10.dp))

                    LazyColumn(Modifier.heightIn(max = 400.dp)) {
                        // Special Toggles
                        item {
                            ToggleRow("All Friends", allFriends) { allFriends = it }
                            ToggleRow("Close Friends Only", closeFriends, isClose = true) { closeFriends = it }
                            HorizontalDivider(Modifier.padding(vertical = 8.dp), color = onSurface.copy(alpha = 0.08f))
                        }

                        // Groups Section
                        item { SectionLabel("GROUPS") }
                        val gList = groups
                        if (gList == null) item { CenterHint("Loading groups...") }
                        else if (gList.isEmpty()) item { CenterHint("No groups yet.") }
                        else items(gList) { g ->
                            val checked = g.id in selGroups
                            Row(Modifier.fillMaxWidth().clickable { if (checked) selGroups.remove(g.id) else selGroups.add(g.id) }
                                .padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(if (checked) "☑" else "☐", fontSize = 18.sp, color = if (checked) Teal else onSurface.copy(alpha = 0.5f))
                                Spacer(Modifier.width(10.dp))
                                AvatarCircle(photo = g.photo, fallback = g.name, size = 34.dp)
                                Spacer(Modifier.width(10.dp))
                                Text(g.name, Modifier.weight(1f), fontSize = 15.sp, color = onSurface)
                            }
                        }

                        // Friends Section
                        item { Spacer(Modifier.height(12.dp)); SectionLabel("SPECIFIC FRIENDS") }
                        val fList = friends
                        if (fList == null) item { CenterHint("Loading friends...") }
                        else if (fList.isEmpty()) item { CenterHint("No friends yet.") }
                        else items(fList) { f ->
                            val checked = f.uid in selUids
                            Row(Modifier.fillMaxWidth().clickable { if (checked) selUids.remove(f.uid) else selUids.add(f.uid) }
                                .padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(if (checked) "☑" else "☐", fontSize = 18.sp, color = if (checked) Teal else onSurface.copy(alpha = 0.5f))
                                Spacer(Modifier.width(10.dp))
                                AvatarCircle(photo = f.photo, fallback = f.tag, size = 34.dp)
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("@${f.tag}", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = onSurface)
                                        if (f.isClose) {
                                            Spacer(Modifier.width(6.dp))
                                            Icon(Icons.Default.Star, null, tint = Color(0xFFEAB308), modifier = Modifier.size(12.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    applyLiveShare(selGroups.toList(), allFriends, closeFriends, selUids.toList())
                }) {
                    Text(if (active) "Update" else "Share", color = Teal, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                if (active) TextButton(onClick = { showLiveShareDialog = false; LiveShare.stop(myUid) }) {
                    Text("Stop sharing", color = Color(0xFFEF4444), fontWeight = FontWeight.Bold)
                } else TextButton(onClick = { showLiveShareDialog = false }) { Text("Cancel") }
            },
        )
    }

    @Composable
    private fun ToggleRow(label: String, checked: Boolean, isClose: Boolean = false, onToggle: (Boolean) -> Unit) {
        Row(Modifier.fillMaxWidth().clickable { onToggle(!checked) }.padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(if (checked) "☑" else "☐", fontSize = 18.sp, color = if (checked) Teal else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
            Spacer(Modifier.width(10.dp))
            if (isClose) Icon(Icons.Default.Star, null, tint = Color(0xFFEAB308), modifier = Modifier.size(20.dp).padding(end = 8.dp))
            Text(label, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
        }
    }

    private fun saveTripPin(draft: TripPinDraft, name: String, note: String) {
        val gid = currentTripGid
        if (gid != null) {
            if (draft.id == null) Trip.sharePin(gid, myUid, myTag, app.getUserPhoto(myUid), draft.lat, draft.lng, name, note)
            else Trip.updatePin(gid, draft.id, name, note) {}
        }
        tripPinDraft = null
    }

    private val sidebarActions = object : SidebarActions {
        override fun onCollections() { drawerOpen = false; startActivity(Intent(this@MainActivity, CollectionsActivity::class.java)) }
        override fun onWaypoints() { drawerOpen = false; startActivity(Intent(this@MainActivity, WaypointsActivity::class.java)) }
        override fun onProfile() { drawerOpen = false; startActivity(Intent(this@MainActivity, ProfileActivity::class.java)) }
        override fun onFriends() { drawerOpen = false; startActivity(Intent(this@MainActivity, FriendsActivity::class.java)) }
        override fun onGroups() { drawerOpen = false; startActivity(Intent(this@MainActivity, GroupsActivity::class.java)) }
        override fun onMessages() { drawerOpen = false; startActivity(Intent(this@MainActivity, MessagesActivity::class.java)) }
        override fun onSettings() { drawerOpen = false; startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
        override fun onToggleTheme() { toggleTheme() }
        override fun onLogout() { logout() }
        override fun onTrackingChanged(enabled: Boolean) { stats.tracking = enabled; if (enabled) startLocationUpdates() else { applyHeadingMode(false); stopLocationUpdates() } }
    }

    private val statsActions = object : StatsActions {
        override fun onPin() { togglePinMode() }
        override fun onShare() { showLiveShareDialog = true }
    }

    /* ── Map interaction ────────────────────────────────────────────────── */

    private fun handleMarkerClick(m: Marker) {
        tripLayer.pinIdFor(m)?.let { id ->
            tripPins.firstOrNull { it.id == id }?.let { centerOn(m.position); activeSheet = ActiveSheet.TripPinActions(it) }
            return
        }
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

    private fun fetchAndShowPlace(placeId: String, name: String, latlng: LatLng, fromShare: Boolean = false) {
        placeCache[placeId]?.let { showPlaceSheet(it, name, latlng, fromShare); return }
        val fields = listOf(
            Place.Field.ID, Place.Field.NAME, Place.Field.ADDRESS, Place.Field.LAT_LNG,
            Place.Field.RATING, Place.Field.USER_RATINGS_TOTAL, Place.Field.PRICE_LEVEL,
            Place.Field.OPENING_HOURS, Place.Field.CURRENT_OPENING_HOURS, Place.Field.UTC_OFFSET,
            Place.Field.BUSINESS_STATUS, Place.Field.PHONE_NUMBER, Place.Field.WEBSITE_URI,
            Place.Field.REVIEWS, Place.Field.PHOTO_METADATAS,
        )
        placesClient.fetchPlace(FetchPlaceRequest.newInstance(placeId, fields))
            .addOnSuccessListener { resp -> placeCache[placeId] = resp.place; showPlaceSheet(resp.place, name, latlng, fromShare) }
            .addOnFailureListener { toast("Couldn't load place details.") }
    }

    // Anchor the sheet on the place's canonical LatLng so the note key matches what's stored
    // (a POI tap gives the click point, which differs slightly from the establishment coords).
    private fun showPlaceSheet(place: Place, name: String, fallback: LatLng, fromShare: Boolean = false) {
        activeSheet = ActiveSheet.PlaceDetails(place, name, place.latLng ?: fallback, fromShare)
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
        val gid = currentTripGid
        if (gid != null) {
            // On a trip → this pin belongs to the shared session, not your personal storage.
            Trip.sharePin(gid, myUid, myTag, app.getUserPhoto(myUid), ll.latitude, ll.longitude, name, notes)
        } else {
            val loc = Location("picked").apply { latitude = ll.latitude; longitude = ll.longitude }
            app.saveLocation(loc)
            val key = App.locationKey(ll.latitude, ll.longitude)
            if (name.isNotEmpty()) app.saveLocationName(key, name)
            if (notes.isNotEmpty()) app.saveNote(key, notes)
        }
        refresh(); savePinLatLng = null; togglePinMode()
    }

    private fun openCollection(key: String) {
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

    /** A shared landmark tapped in a group chat → open its in-app place page here. */
    private fun handleSharedPlaceIntent(intent: Intent?) {
        val placeId = intent?.getStringExtra("shared_placeId")
        if (placeId.isNullOrEmpty()) return
        intent.removeExtra("shared_placeId") // consume so it doesn't refire on the next resume
        val ll = LatLng(intent.getDoubleExtra("shared_lat", 0.0), intent.getDoubleExtra("shared_lng", 0.0))
        animateTo(ll, 17f)
        fetchAndShowPlace(placeId, intent.getStringExtra("shared_name") ?: "", ll, fromShare = true)
    }

    /* ── Group Trip (live location + shared pins) ───────────────────────── */

    /** Render the map's pin layer: personal pins when free, session pins + members while on a trip. */
    private fun refreshMap(map: GoogleMap, dark: Boolean) {
        if (currentTripGid == null) markers.refresh(map, app, dark) else markers.clear()
        renderTrip()
    }

    private fun openTripChat() {
        val gid = currentTripGid ?: return
        startActivity(Intent(this, GroupChatActivity::class.java).apply {
            putExtra("gid", gid); putExtra("name", tripGroupName)
        })
    }

    /** My participant doc changed (joined/left/switched groups) → (re)wire the live listeners. */
    private fun onMyTripChanged(gid: String?) {
        currentTripGid = gid
        tripMembersListener?.remove(); tripPinsListener?.remove(); tripDestListener?.remove(); tripOffersListener?.remove(); tripPlanListener?.remove()
        tripMembersListener = null; tripMembersListener = null; tripPinsListener = null; tripDestListener = null; tripOffersListener = null; tripPlanListener = null
        refresh() // swap personal ↔ session pins on the map
        if (gid == null) {
            tripMembers = emptyList(); tripPins = emptyList(); tripGroupName = ""; tripGroupPhoto = ""
            pendingOffer = null; lastOffers = emptyList(); tripPlan = null; showPlanSheet = false
            if (routeIsTrip) { routeIsTrip = false; clearDirections() } // left the trip → drop its shared direction
            googleMap?.let { tripLayer.clear() }
            return
        }
        // Ensure the foreground publisher is running (e.g. app relaunched while already in a trip).
        Groups.fetchNamePhoto(gid) { name, photo ->
            tripGroupName = name; tripGroupPhoto = photo
            TripLocationService.start(this, myUid, name)
        }
        tripMembersListener = Trip.listenMembers(gid) { tripMembers = it; renderTrip() }
        tripPinsListener = Trip.listenPins(gid) { tripPins = it; renderTrip() }
        tripDestListener = Trip.listenTripDest(gid) { onTripDestChanged(it) }
        tripOffersListener = Trip.listenOffers(gid) { onTripOffers(it) }
        tripPlanListener = Trip.listenPlan(gid) { tripPlan = it }
    }

    /* ── Shared collection offers (trip-only) ───────────────────────────── */
    private fun onTripOffers(offers: List<Trip.TripOffer>) { lastOffers = offers; showNextOffer() }

    private fun showNextOffer() {
        if (pendingOffer != null) return
        pendingOffer = lastOffers.firstOrNull { it.from != myUid && it.id !in handledOffers }
    }

    private fun acceptOffer(offer: Trip.TripOffer) {
        val gid = currentTripGid
        handledOffers.add(offer.id)
        pendingOffer = null
        if (gid != null) {
            val existing = tripPins.map { App.locationKey(it.lat, it.lng) }.toSet() // skip pins already on the trip
            val newPins = offer.pins.filter { App.locationKey(it.lat, it.lng) !in existing }
            if (newPins.isEmpty()) toast("Already on the trip")
            else Trip.addPins(gid, offer.from, offer.fromTag, offer.fromPhoto, newPins) { err ->
                toast(err ?: "Added ${newPins.size} place${if (newPins.size == 1) "" else "s"}")
            }
        }
        showNextOffer()
    }

    private fun dismissOffer(offer: Trip.TripOffer) {
        handledOffers.add(offer.id); pendingOffer = null; showNextOffer()
    }

    /** A shared trip destination appeared, changed, or ended → offer to join (or clear it). Never forced. */
    private fun onTripDestChanged(dest: Trip.TripDest?) {
        if (dest == null) {                       // everyone finished / not set → drop my copy of it
            incomingTripDest = null
            if (routeIsTrip) { routeIsTrip = false; clearDirections() }
            return
        }
        val ll = LatLng(dest.lat, dest.lng)
        if (routeIsTrip && routeDest == ll) { incomingTripDest = null; return } // already following this exact one
        if (routeIsTrip) { routeIsTrip = false; clearDirections() }             // a replaced dest → drop the old shared route
        // Plan-driven direction → auto-navigate everyone (no opt-in modal; advances on "finished", not arrival).
        if (dest.planItemId.isNotEmpty()) { beginDirections(ll, dest.name.ifEmpty { "Plan stop" }, isTrip = true, isPlan = true); return }
        if (myUid in dest.done) { incomingTripDest = null; return }             // I already ended/dismissed this one
        if (dest.by == myUid) { beginDirections(ll, dest.name.ifEmpty { "Trip destination" }, isTrip = true); return } // I set it → follow my own
        incomingTripDest = dest                    // someone else set it → prompt (Join / Dismiss)
    }

    private fun renderTrip() {
        val map = googleMap ?: return
        if (currentTripGid == null) { tripLayer.clear(); return }
        tripLayer.renderMembers(map, tripMembers, myUid)
        tripLayer.renderPins(map, tripPins, app.isDarkMode())
    }

    private fun leaveTrip() {
        val uid = myUid
        val gid = currentTripGid
        if (uid.isNotEmpty()) Trip.leave(uid) { err ->
            if (err != null) toast("Couldn't leave: $err")
            else gid?.let { Groups.postSystem(it, "👋 @$myTag left the trip") }
        }
    }

    /* ── Directions ─────────────────────────────────────────────────────── */

    private fun startDirections(dest: LatLng, name: String) {
        if (currentTripGid != null) { directionChoice = dest to name; return } // ask: share with trip, or just me?
        beginDirections(dest, name, isTrip = false)
    }

    private fun beginDirections(dest: LatLng, name: String, isTrip: Boolean, isPlan: Boolean = false) {
        if (savedLat == 0.0 && savedLng == 0.0) { toast("Waiting for your location…"); return }
        routeIsTrip = isTrip; routeIsPlan = isPlan
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
        if (routeIsPlan) { routeIsTrip = false; clearDirections(); return } // plan route: just stop my nav (plan runs on)
        if (routeIsTrip) { endTripDirection(); return }                     // manual trip route: closing = ending it for me
        clearDirections()
    }

    private fun clearDirections() {
        if (navigating) { navigating = false; tts?.stop(); exitNavLocationUpdates() }
        routeDest = null; routeDestName = ""; routes = emptyList(); routePoints = emptyList()
        routeLoading = false; currentStepIndex = 0; navDistanceToNext = 0; routeIsPlan = false
    }

    /** End the shared trip direction for me only (leaves it running for everyone else). */
    private fun endTripDirection() {
        val gid = currentTripGid
        routeIsTrip = false
        clearDirections()
        if (gid != null) Trip.endTripDestForMe(gid, myUid)
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
        if (routeIsPlan) { routeIsTrip = false; clearDirections() }  // plan route: stop my nav; plan continues for others
        else if (routeIsTrip) endTripDirection()                     // manual trip route ends it for me
        else routes.getOrNull(selectedRouteIndex)?.let { fitToRoute(it.points) }
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
            else {
                speak("You have arrived at your destination")
                // Plan stops advance only when explicitly marked finished — arrival does nothing.
                if (routeIsTrip && !routeIsPlan) endTripDirection() // manual dir: arrival counts as done
            }
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

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    private fun updateGPS() {
        if (hasLocationPermission()) {
            fusedLocClient.lastLocation.addOnSuccessListener(this) { it?.let(::updateUIValues) }
        }
    }

    private fun updateUIValues(loc: Location?) {
        if (loc == null) return
        savedLat = loc.latitude; savedLng = loc.longitude
        app.lastLat = savedLat; app.lastLng = savedLng // trip publishing is owned by TripLocationService
        // Live-share publishing (foreground): push each fix while a share is active; expiry handled in onLiveShareChanged.
        liveShare?.let { if (it.active) LiveShare.updateLocation(myUid, savedLat, savedLng) }
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
                stats.address = addr ?: "Unable to get address"
            }
        }
    }

    private fun hasLocationPermission() =
        ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /* ── Pickers & actions ──────────────────────────────────────────────── */

    /** Apply the live-share dialog's expanded targeting options. */
    private fun applyLiveShare(groups: List<String>, allFriends: Boolean, closeFriends: Boolean, uids: List<String>) {
        showLiveShareDialog = false
        if (groups.isEmpty() && !allFriends && !closeFriends && uids.isEmpty()) {
            LiveShare.stop(myUid) { err -> if (err != null) toast("Couldn't stop: $err") }
            return
        }
        if (savedLat == 0.0 && savedLng == 0.0) { toast("Waiting for your location…"); return }

        LiveShare.start(
            uid = myUid,
            tag = myTag,
            photo = app.getUserPhoto(myUid),
            groups = groups,
            allFriends = allFriends,
            closeFriends = closeFriends,
            uids = uids,
            lat = savedLat,
            lng = savedLng
        ) { err ->
            if (err != null) { toast("Couldn't share: $err"); return@start }
            // Announce in group chats if shared to new groups
            val was = liveShare?.groups?.toSet() ?: emptySet()
            (groups.toSet() - was).forEach { gid -> Groups.postLiveShare(gid, myUid, myTag) }
        }
    }

    private fun toggleTheme() {
        val newMode = !app.isDarkMode()
        app.setDarkMode(newMode)
        AppCompatDelegate.setDefaultNightMode(
            if (newMode) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )
        recreate()
    }

    private fun logout() {
        FcmTokens.unregister(myUid)
        NotificationHub.stop()
        app.unbindUser()
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

@Composable
private fun CenterHint(text: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), fontSize = 13.sp)
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, fontSize = 11.sp, fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
}
