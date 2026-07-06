package com.usc.myway;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.app.ActivityCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.BitmapDescriptor;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.MapStyleOptions;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.libraries.places.api.Places;
import com.google.android.libraries.places.api.model.AutocompletePrediction;
import com.google.android.libraries.places.api.model.Place;
import com.google.android.libraries.places.api.net.FetchPlaceRequest;
import com.google.android.libraries.places.api.net.FindAutocompletePredictionsRequest;
import com.google.android.libraries.places.api.net.PlacesClient;
import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.firebase.auth.FirebaseAuth;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends AppCompatActivity implements OnMapReadyCallback {
    private PlacesClient placesClient;
    private static final int PERMISSION_FINE_LOCATION = 99;
    private static final int MAP_PICKER_REQUEST       = 101;
    private static final int WAYPOINT_PICKER_REQUEST  = 102;
    public  static final int DEF_INT_INTERVAL         = 30;
    public  static final int FAST_UPD_INTERVAL        = 5;

    private GoogleMap mainMap;
    private boolean mapReady = false;
    private boolean firstFix = true;
    private final Map<Marker, String> markerKeys  = new HashMap<>();  // red pins -> key (normal pins only)
    private final Map<Marker, String> labelKeys   = new HashMap<>();  // floating note labels -> key

    // Note labels (pins AND landmarks) collapse to a small pencil marker below this zoom, so the
    // map stays clean when zoomed out, and expand back to the full card when zoomed in.
    private static final float LABEL_ZOOM = 18f;
    private final Map<Marker, BitmapDescriptor> noteFullIcons = new HashMap<>(); // marker -> full card icon
    private BitmapDescriptor pencilIcon;
    private Boolean notesCollapsed = null;

    // Session caches so re-tapping a landmark doesn't re-bill fetchPlace / isOpen / fetchPhoto.
    private final Map<String, Place> placeCache   = new HashMap<>();
    private final Map<String, Bitmap> photoCache  = new HashMap<>();
    private final Map<String, Boolean> isOpenCache = new HashMap<>();

    private TextView tv_waypointCounts; // top-header count badge (still XML)
    private final SidebarState sidebarState = new SidebarState();
    private final StatsState statsState = new StatsState();

    private double savedLatitude  = 0;
    private double savedLongitude = 0;
    private String savedAddress   = "";
    // Single background thread for reverse-geocoding; deduped so we don't re-resolve tiny GPS jitter.
    private final java.util.concurrent.ExecutorService geocodeExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    private double lastGeocodedLat = Double.NaN, lastGeocodedLng = Double.NaN;
    private FusedLocationProviderClient fusedLocClient;
    private LocationRequest locReq;
    private LocationCallback locCallBack;

    private Marker tempPickerMarker = null;
    private boolean isPickerModeActive = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        fusedLocClient = LocationServices.getFusedLocationProviderClient(this);

        SupportMapFragment mapFragment = (SupportMapFragment)
                getSupportFragmentManager().findFragmentById(R.id.map_main);
        if (mapFragment != null) mapFragment.getMapAsync(this);

        bindViews();
        setupHamburger();
        setupSidebar();
        setupBottomCard();
        setupLocationRequest();
        setupSearch();

        updateGPS();
        startLocationUpdates();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshMapMarkers();
        handleFocusIntent(getIntent());
    }

    private void bindViews() {
        tv_waypointCounts = findViewById(R.id.tv_countCrumbs);
    }

    private void setupBottomCard() {
        androidx.compose.ui.platform.ComposeView cv = findViewById(R.id.bottom_compose);
        StatsActions actions = new StatsActions() {
            @Override public void onSave()  { startWaypointPicker(); }
            @Override public void onPin()   { togglePickerMode(); }
            @Override public void onShare() { shareLocation(); }
        };
        BottomCardHost.install(cv, statsState, actions, isDarkMode());
    }

    private void setupHamburger() {
        LinearLayout btn_hamburger = findViewById(R.id.btn_hamburger);
        LinearLayout left_sidebar  = findViewById(R.id.left_sidebar);
        btn_hamburger.setOnClickListener(v -> {
            boolean visible = left_sidebar.getVisibility() == View.VISIBLE;
            if (visible) {
                left_sidebar.animate().alpha(0f).translationX(-left_sidebar.getWidth()).setDuration(200)
                        .withEndAction(() -> left_sidebar.setVisibility(View.GONE)).start();
            } else {
                left_sidebar.setVisibility(View.VISIBLE);
                left_sidebar.setAlpha(0f);
                left_sidebar.setTranslationX(-left_sidebar.getWidth());
                left_sidebar.animate().alpha(1f).translationX(0f).setDuration(200).start();
            }
        });
    }

    private void setupSidebar() {
        sidebarState.setDarkMode(isDarkMode());
        sidebarState.setTracking(true);
        sidebarState.setGpsHighAccuracy(false);
        androidx.compose.ui.platform.ComposeView composeView = findViewById(R.id.sidebar_compose);
        SidebarActions actions = new SidebarActions() {
            @Override public void onNewWaypoint()  { startWaypointPicker(); }
            @Override public void onShowWaypoints() { startActivity(new Intent(MainActivity.this, ShowSavedLocations.class)); }
            @Override public void onSetAddress()    { startAddressPicker(); }
            @Override public void onToggleTheme()   {
                AppCompatDelegate.setDefaultNightMode(isDarkMode()
                        ? AppCompatDelegate.MODE_NIGHT_NO : AppCompatDelegate.MODE_NIGHT_YES);
            }
            @Override public void onLogout() {
                FirebaseAuth.getInstance().signOut();
                GoogleSignIn.getClient(MainActivity.this, GoogleSignInOptions.DEFAULT_SIGN_IN).signOut();
                Intent i = new Intent(MainActivity.this, LoginActivity.class);
                i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(i);
                finish();
            }
            @Override public void onTrackingChanged(boolean enabled) {
                if (enabled) startLocationUpdates(); else stopLocationUpdates();
            }
            @Override public void onGpsModeChanged(boolean highAccuracy) {
                locReq = buildLocReq(highAccuracy
                        ? Priority.PRIORITY_HIGH_ACCURACY : Priority.PRIORITY_BALANCED_POWER_ACCURACY);
            }
        };
        SidebarHost.install(composeView, sidebarState, actions);
    }

    private void startWaypointPicker() {
        if (savedLatitude == 0 && savedLongitude == 0) return;
        Intent i = new Intent(this, MapPickerActivity.class);
        i.putExtra("latitude", savedLatitude); i.putExtra("longitude", savedLongitude);
        i.putExtra("mode", "waypoint");
        startActivityForResult(i, WAYPOINT_PICKER_REQUEST);
    }

    private void startAddressPicker() {
        if (savedLatitude == 0 && savedLongitude == 0) return;
        Intent i = new Intent(this, MapPickerActivity.class);
        i.putExtra("latitude", savedLatitude); i.putExtra("longitude", savedLongitude);
        i.putExtra("mode", "address");
        startActivityForResult(i, MAP_PICKER_REQUEST);
    }

    private boolean isDarkMode() {
        int nightMode = AppCompatDelegate.getDefaultNightMode();
        if (nightMode == AppCompatDelegate.MODE_NIGHT_YES) return true;
        if (nightMode == AppCompatDelegate.MODE_NIGHT_NO) return false;
        return (getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    private void setupLocationRequest() {
        locReq = buildLocReq(Priority.PRIORITY_BALANCED_POWER_ACCURACY);
        locCallBack = new LocationCallback() {
            @Override
            public void onLocationResult(@NonNull LocationResult result) {
                super.onLocationResult(result);
                updateUIValues(result.getLastLocation());
            }
        };
    }

    private LocationRequest buildLocReq(int priority) {
        return new LocationRequest.Builder(priority, 1000L * DEF_INT_INTERVAL)
                .setMinUpdateIntervalMillis(1000L * FAST_UPD_INTERVAL).build();
    }


    @Override
    public void onMapReady(GoogleMap googleMap) {
        mainMap = googleMap;
        mapReady = true;
        if (isDarkMode()) mainMap.setMapStyle(MapStyleOptions.loadRawResourceStyle(this, R.raw.map_dark));
        
        enableMyLocationLayer();
        
        mainMap.getUiSettings().setZoomControlsEnabled(false);
        mainMap.getUiSettings().setMyLocationButtonEnabled(true);
        mainMap.getUiSettings().setCompassEnabled(true);
        
        if (hasLocationPermission()) {
            fusedLocClient.getLastLocation().addOnSuccessListener(location -> {
                if (location != null) {
                    savedLatitude = location.getLatitude(); savedLongitude = location.getLongitude();
                    mainMap.moveCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(savedLatitude, savedLongitude), 18f));
                    firstFix = false;
                }
            });
        }
        
        // Tapping a pin (or its floating note label) opens the right sheet directly.
        // The old info-window -> info-window-click hop was unreliable: taps landed on the
        // overlapping label marker or a partly off-screen info window and silently did nothing.
        mainMap.setOnMarkerClickListener(m -> {
            if (markerKeys.containsKey(m)) {           // a normal red pin
                showMarkerActions(m);
            } else if (labelKeys.containsKey(m)) {     // a floating note label
                openForKey(labelKeys.get(m));
            }
            return true;
        });
        // Tapping one of Google's built-in landmark icons -> rich details sheet.
        mainMap.setOnPoiClickListener(this::showPoiDetails);
        // Collapse/expand landmark note cards as the user zooms.
        mainMap.setOnCameraIdleListener(this::applyNoteZoom);
        refreshMapMarkers();
    }

    private void showMarkerActions(Marker marker) {
        centerOn(marker.getPosition());
        App myApp = (App) getApplicationContext();
        String key = markerKeys.getOrDefault(marker, "");
        View sheet = getLayoutInflater().inflate(R.layout.sheet_marker_actions, null);
        ((TextView) sheet.findViewById(R.id.tv_sheet_title)).setText(marker.getTitle());
        String note = myApp.getLocationNotes().getOrDefault(key, "");
        TextView tvNote = sheet.findViewById(R.id.tv_sheet_note);
        if (!note.isEmpty()) { tvNote.setVisibility(View.VISIBLE); tvNote.setText("📝 " + note); }
        else { tvNote.setVisibility(View.GONE); }

        // Address (reverse-geocoded off the main thread).
        geocodeInto(sheet.findViewById(R.id.tv_sheet_address), marker.getPosition());

        com.google.android.material.bottomsheet.BottomSheetDialog dialog =
                new com.google.android.material.bottomsheet.BottomSheetDialog(this);
        dialog.setContentView(sheet);
        sheet.findViewById(R.id.btn_add_note).setOnClickListener(v -> { dialog.dismiss(); showNoteDialog(key); });
        sheet.findViewById(R.id.btn_add_collection).setOnClickListener(v -> { dialog.dismiss(); showAddToCollectionDialog(key); });
        sheet.findViewById(R.id.btn_delete_location).setOnClickListener(v -> { dialog.dismiss(); confirmDelete(key); });
        dialog.show();
    }

    private void geocodeInto(TextView tv, LatLng ll) {
        new Thread(() -> {
            try {
                List<Address> addrs = new Geocoder(this).getFromLocation(ll.latitude, ll.longitude, 1);
                if (addrs != null && !addrs.isEmpty()) {
                    String line = addrs.get(0).getAddressLine(0);
                    runOnUiThread(() -> { tv.setVisibility(View.VISIBLE); tv.setText("📍 " + line); });
                }
            } catch (Exception ignored) { /* offline / no geocoder */ }
        }).start();
    }

    private Marker pinForKey(String key) {
        for (Map.Entry<Marker, String> e : markerKeys.entrySet())
            if (e.getValue().equals(key)) return e.getKey();
        return null;
    }

    /** Route a tap on a note label to the right sheet: landmark details vs. normal-pin actions. */
    private void openForKey(String key) {
        App myApp = (App) getApplicationContext();
        if (myApp.isLandmark(key)) {
            String[] parts = key.split(",");
            try {
                LatLng ll = new LatLng(Double.parseDouble(parts[0].trim()), Double.parseDouble(parts[1].trim()));
                centerOn(ll);
                fetchAndShowPlace(myApp.getLocationPlaceId(key), myApp.getLocationName(key), ll);
            } catch (Exception ignored) { }
        } else {
            Marker pin = pinForKey(key);
            if (pin != null) showMarkerActions(pin);
        }
    }

    private void showAddToCollectionDialog(String key) {
        App myApp = (App) getApplicationContext();
        List<Collection> collections = myApp.getCollections();
        if (collections.isEmpty()) {
            Toast.makeText(this, "No collections yet — create one in Saved Waypoints.", Toast.LENGTH_LONG).show();
            return;
        }
        // A pin belongs to at most one collection (same as the waypoint edit screen).
        String[] options = new String[collections.size() + 1];
        options[0] = "❌ None";
        for (int i = 0; i < collections.size(); i++) {
            Collection c = collections.get(i);
            options[i + 1] = c.icon + " " + c.name + (c.locationKeys.contains(key) ? "  ✓" : "");
        }
        new AlertDialog.Builder(this)
                .setTitle("Add to Collection")
                .setItems(options, (d, which) -> {
                    for (Collection c : collections) c.locationKeys.remove(key);
                    if (which > 0) {
                        Collection chosen = collections.get(which - 1);
                        chosen.locationKeys.add(key);
                        Toast.makeText(this, "Added to " + chosen.name, Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, "Removed from collection", Toast.LENGTH_SHORT).show();
                    }
                    myApp.saveCollectionsToPrefs();
                })
                .show();
    }

    private void confirmDelete(String key) {
        new AlertDialog.Builder(this)
                .setTitle("Delete Waypoint")
                .setMessage("Are you sure you want to delete this location?")
                .setPositiveButton("Delete", (d, w) -> {
                    App myApp = (App) getApplicationContext();
                    for (Location loc : myApp.getMyLocations()) {
                        if (App.locationKey(loc.getLatitude(), loc.getLongitude()).equals(key)) {
                            myApp.removeLocation(loc);
                            break;
                        }
                    }
                    refreshMapMarkers();
                    Toast.makeText(this, "Location deleted.", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showNoteDialog(String key) {
        App myApp = (App) getApplicationContext();
        String existingNote = myApp.getLocationNotes().getOrDefault(key, "");
        EditText input = new EditText(this);
        input.setText(existingNote);
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        container.setPadding(pad, pad, pad, 0);
        container.addView(input);
        new AlertDialog.Builder(this)
                .setTitle("📝 Edit Note")
                .setView(container)
                .setPositiveButton("Save", (d, w) -> {
                    myApp.saveNote(key, input.getText().toString().trim());
                    refreshMapMarkers();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ── Landmark (POI) details ────────────────────────────────────────────────

    private void showPoiDetails(com.google.android.gms.maps.model.PointOfInterest poi) {
        centerOn(poi.latLng);
        fetchAndShowPlace(poi.placeId, poi.name, poi.latLng);
    }

    /** Slide the tapped pin/landmark to the middle of the screen, like the Google Maps app. */
    private void centerOn(LatLng ll) {
        if (mainMap != null && ll != null) mainMap.animateCamera(CameraUpdateFactory.newLatLng(ll));
    }

    private void fetchAndShowPlace(String placeId, String name, LatLng latlng) {
        Place cached = placeCache.get(placeId);
        if (cached != null) { showPlaceSheet(cached, name, latlng); return; }
        List<Place.Field> fields = Arrays.asList(
                Place.Field.ID, Place.Field.NAME, Place.Field.ADDRESS, Place.Field.LAT_LNG,
                Place.Field.RATING, Place.Field.USER_RATINGS_TOTAL, Place.Field.PRICE_LEVEL,
                Place.Field.OPENING_HOURS, Place.Field.CURRENT_OPENING_HOURS, Place.Field.UTC_OFFSET,
                Place.Field.BUSINESS_STATUS, Place.Field.PHONE_NUMBER, Place.Field.WEBSITE_URI,
                Place.Field.REVIEWS, Place.Field.PHOTO_METADATAS);
        placesClient.fetchPlace(FetchPlaceRequest.newInstance(placeId, fields))
                .addOnSuccessListener(resp -> {
                    placeCache.put(placeId, resp.getPlace());
                    showPlaceSheet(resp.getPlace(), name, latlng);
                })
                .addOnFailureListener(e ->
                        Toast.makeText(this, "Couldn't load place details.", Toast.LENGTH_SHORT).show());
    }

    private void showPlaceSheet(Place place, String fallbackName, LatLng fallbackLatLng) {
        LatLng ll = place.getLatLng() != null ? place.getLatLng() : fallbackLatLng;
        if (ll == null) return;
        String name = place.getName() != null ? place.getName() : fallbackName;
        String key = App.locationKey(ll.latitude, ll.longitude);

        View sheet = getLayoutInflater().inflate(R.layout.sheet_place_details, null);
        ((TextView) sheet.findViewById(R.id.tv_place_name)).setText(name);

        loadPhotos(place, sheet);

        // Rating (stars) · price
        TextView tvRating = sheet.findViewById(R.id.tv_place_rating);
        StringBuilder rp = new StringBuilder();
        if (place.getRating() != null) {
            rp.append(String.format("%.1f ", place.getRating())).append(starString(place.getRating()));
            if (place.getUserRatingsTotal() != null)
                rp.append(" (").append(place.getUserRatingsTotal()).append(")");
        }
        if (place.getPriceLevel() != null) {
            if (rp.length() > 0) rp.append("  ·  ");
            for (int i = 0; i < place.getPriceLevel(); i++) rp.append("$");
        }
        if (rp.length() > 0) { tvRating.setVisibility(View.VISIBLE); tvRating.setText(rp.toString()); }

        showOpenStatus(place, sheet.findViewById(R.id.tv_place_status));

        // User's saved note — persists until the location is deleted.
        String userNote = ((App) getApplicationContext()).getLocationNotes().getOrDefault(key, "");
        if (!userNote.isEmpty()) {
            TextView tvNote = sheet.findViewById(R.id.tv_place_note);
            tvNote.setVisibility(View.VISIBLE);
            tvNote.setText("📝 " + userNote);
        }

        // Address
        if (place.getAddress() != null && !place.getAddress().isEmpty()) {
            sheet.findViewById(R.id.row_address).setVisibility(View.VISIBLE);
            ((TextView) sheet.findViewById(R.id.tv_place_address)).setText(place.getAddress());
        }

        // Hours
        if (place.getOpeningHours() != null && place.getOpeningHours().getWeekdayText() != null
                && !place.getOpeningHours().getWeekdayText().isEmpty()) {
            sheet.findViewById(R.id.section_hours).setVisibility(View.VISIBLE);
            ((TextView) sheet.findViewById(R.id.tv_place_hours))
                    .setText(android.text.TextUtils.join("\n", place.getOpeningHours().getWeekdayText()));
        }

        // Phone (tap to dial)
        if (place.getPhoneNumber() != null && !place.getPhoneNumber().isEmpty()) {
            View row = sheet.findViewById(R.id.row_phone);
            row.setVisibility(View.VISIBLE);
            ((TextView) sheet.findViewById(R.id.tv_place_phone)).setText(place.getPhoneNumber());
            row.setOnClickListener(v -> startActivity(
                    new Intent(Intent.ACTION_DIAL, android.net.Uri.parse("tel:" + place.getPhoneNumber()))));
        }

        // Website (tap to open)
        if (place.getWebsiteUri() != null) {
            View row = sheet.findViewById(R.id.row_website);
            row.setVisibility(View.VISIBLE);
            ((TextView) sheet.findViewById(R.id.tv_place_website)).setText(place.getWebsiteUri().toString());
            row.setOnClickListener(v -> startActivity(new Intent(Intent.ACTION_VIEW, place.getWebsiteUri())));
        }

        // Reviews (up to 5)
        List<com.google.android.libraries.places.api.model.Review> reviews = place.getReviews();
        if (reviews != null && !reviews.isEmpty()) {
            sheet.findViewById(R.id.section_reviews).setVisibility(View.VISIBLE);
            LinearLayout reviewsContainer = sheet.findViewById(R.id.ll_place_reviews);
            int margin = (int) (10 * getResources().getDisplayMetrics().density);
            int shown = 0;
            for (com.google.android.libraries.places.api.model.Review r : reviews) {
                if (shown++ >= 5) break;
                String author = r.getAuthorAttribution() != null ? r.getAuthorAttribution().getName() : "Anonymous";
                TextView tv = new TextView(this);
                tv.setTextColor(0xFF475569);
                tv.setTextSize(13);
                tv.setText("⭐ " + r.getRating() + "  " + author + " · "
                        + r.getRelativePublishTimeDescription() + "\n" + r.getText());
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                lp.bottomMargin = margin;
                tv.setLayoutParams(lp);
                reviewsContainer.addView(tv);
            }
        }

        com.google.android.material.bottomsheet.BottomSheetDialog dialog =
                new com.google.android.material.bottomsheet.BottomSheetDialog(this);
        dialog.setContentView(sheet);

        // Actions — note/collection auto-save the landmark as a waypoint first.
        sheet.findViewById(R.id.btn_place_note).setOnClickListener(v -> {
            dialog.dismiss(); ensureSaved(ll, name, place.getId()); showNoteDialog(key);
        });
        sheet.findViewById(R.id.btn_place_collection).setOnClickListener(v -> {
            dialog.dismiss(); ensureSaved(ll, name, place.getId()); showAddToCollectionDialog(key);
        });
        // Delete only exists once the place is "yours" (has a note or is in a collection).
        View delete = sheet.findViewById(R.id.btn_place_delete);
        if (isSaved(key)) {
            delete.setVisibility(View.VISIBLE);
            delete.setOnClickListener(v -> { dialog.dismiss(); confirmDelete(key); });
        }
        dialog.show();
    }

    private String starString(double rating) {
        int full = (int) Math.round(rating);
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < 5; i++) s.append(i < full ? "★" : "☆");
        return s.toString();
    }

    private void loadPhotos(Place place, View sheet) {
        List<com.google.android.libraries.places.api.model.PhotoMetadata> photos = place.getPhotoMetadatas();
        if (photos == null || photos.isEmpty()) return;
        HorizontalScrollView sv = sheet.findViewById(R.id.sv_photos);
        sv.setVisibility(View.VISIBLE);
        LinearLayout container = sheet.findViewById(R.id.ll_photos);
        float d = getResources().getDisplayMetrics().density;
        int w = (int) (240 * d), h = (int) (160 * d), gap = (int) (10 * d);
        int max = Math.min(photos.size(), 6);
        String placeId = place.getId() != null ? place.getId() : String.valueOf(System.identityHashCode(place));
        final android.widget.ImageView[] views = new android.widget.ImageView[max];

        for (int i = 0; i < max; i++) {
            androidx.cardview.widget.CardView card = new androidx.cardview.widget.CardView(this);
            card.setRadius(14 * d);
            card.setCardElevation(0);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(w, h);
            if (i < max - 1) clp.rightMargin = gap;
            card.setLayoutParams(clp);
            android.widget.ImageView iv = new android.widget.ImageView(this);
            iv.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
            iv.setBackgroundColor(0xFFE2E8F0);
            card.addView(iv, new android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
            container.addView(card);
            views[i] = iv;

            String ck = placeId + "#" + i;
            if (photoCache.containsKey(ck)) iv.setImageBitmap(photoCache.get(ck));
            else if (i == 0) fetchPhotoInto(ck, photos.get(i), iv);  // hero loads eagerly
            // remaining photos are deferred until the user scrolls the strip
        }

        // Lazy: fetch the rest only once the user actually scrolls the gallery.
        final boolean[] loadedRest = {false};
        sv.setOnScrollChangeListener((v, x, y, ox, oy) -> {
            if (loadedRest[0]) return;
            loadedRest[0] = true;
            for (int i = 1; i < max; i++) {
                String ck = placeId + "#" + i;
                if (!photoCache.containsKey(ck)) fetchPhotoInto(ck, photos.get(i), views[i]);
            }
        });
    }

    private void fetchPhotoInto(String cacheKey,
                               com.google.android.libraries.places.api.model.PhotoMetadata meta,
                               android.widget.ImageView iv) {
        com.google.android.libraries.places.api.net.FetchPhotoRequest req =
                com.google.android.libraries.places.api.net.FetchPhotoRequest.builder(meta)
                        .setMaxWidth(600).setMaxHeight(400).build();
        placesClient.fetchPhoto(req).addOnSuccessListener(r -> {
            photoCache.put(cacheKey, r.getBitmap());
            iv.setImageBitmap(r.getBitmap());
        });
    }

    private void showOpenStatus(Place place, TextView tv) {
        Place.BusinessStatus status = place.getBusinessStatus();
        if (status == Place.BusinessStatus.CLOSED_PERMANENTLY) { setStatus(tv, "Permanently closed", false); return; }
        if (status == Place.BusinessStatus.CLOSED_TEMPORARILY) { setStatus(tv, "Temporarily closed", false); return; }
        String id = place.getId();
        if (id != null && isOpenCache.containsKey(id)) {
            boolean open = isOpenCache.get(id);
            setStatus(tv, open ? "Open now" : "Closed", open);
            return;
        }
        // "Open now" needs a live check against the place's hours + timezone.
        try {
            com.google.android.libraries.places.api.net.IsOpenRequest req =
                    com.google.android.libraries.places.api.net.IsOpenRequest.newInstance(place);
            placesClient.isOpen(req).addOnSuccessListener(resp -> {
                if (resp.isOpen() != null) {
                    if (id != null) isOpenCache.put(id, resp.isOpen());
                    setStatus(tv, resp.isOpen() ? "Open now" : "Closed", resp.isOpen());
                }
            });
        } catch (Exception ignored) { /* missing fields -> just hide the badge */ }
    }

    private void setStatus(TextView tv, String text, boolean open) {
        tv.setVisibility(View.VISIBLE);
        tv.setText(text);
        tv.setTextColor(open ? 0xFF16A34A : 0xFFEF4444);
    }

    private boolean isSaved(String key) {
        for (Location loc : ((App) getApplicationContext()).getMyLocations())
            if (App.locationKey(loc.getLatitude(), loc.getLongitude()).equals(key)) return true;
        return false;
    }

    /** Persist a tapped landmark as a waypoint (name + placeId) if not already saved. */
    private void ensureSaved(LatLng ll, String name, String placeId) {
        String key = App.locationKey(ll.latitude, ll.longitude);
        if (isSaved(key)) return;
        App myApp = (App) getApplicationContext();
        Location loc = new Location("poi");
        loc.setLatitude(ll.latitude); loc.setLongitude(ll.longitude);
        myApp.saveLocation(loc);
        if (name != null && !name.isEmpty()) myApp.saveLocationName(key, name);
        if (placeId != null && !placeId.isEmpty()) myApp.saveLocationPlaceId(key, placeId);
        refreshMapMarkers();
        Toast.makeText(this, "Saved to waypoints", Toast.LENGTH_SHORT).show();
    }

    private void enableMyLocationLayer() {
        if (mainMap != null && hasLocationPermission()) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
                mainMap.setMyLocationEnabled(true);
        }
    }

    private void startLocationUpdates() {
        if (!hasLocationPermission()) return;
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
            fusedLocClient.requestLocationUpdates(locReq, locCallBack, null);
        enableMyLocationLayer();
        updateGPS();
    }

    private void stopLocationUpdates() {
        statsState.setLat("--"); statsState.setLon("--"); statsState.setSpeed("--");
        statsState.setAddress("Not tracking");
        fusedLocClient.removeLocationUpdates(locCallBack);
    }

    private void updateGPS() {
        if (hasLocationPermission() && ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fusedLocClient.getLastLocation().addOnSuccessListener(this, location -> { if (location != null) updateUIValues(location); });
            enableMyLocationLayer();
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, PERMISSION_FINE_LOCATION);
        }
    }

    private void updateUIValues(Location loc) {
        if (loc == null) return;
        savedLatitude = loc.getLatitude(); savedLongitude = loc.getLongitude();
        statsState.setLat(String.format("%.5f", savedLatitude));
        statsState.setLon(String.format("%.5f", savedLongitude));
        statsState.setAccuracy(String.format("%.1fm", loc.getAccuracy()));
        statsState.setAltitude(loc.hasAltitude() ? String.format("%.1fm", loc.getAltitude()) : "N/A");
        statsState.setSpeed(loc.hasSpeed() ? String.format("%.1fkm/h", loc.getSpeed() * 3.6f) : "0km/h");
        maybeGeocodeAddress(savedLatitude, savedLongitude);
        App myApp = (App) getApplicationContext();
        tv_waypointCounts.setText(String.valueOf(myApp.getMyLocations().size()));
        if (mapReady && mainMap != null && firstFix && savedLatitude != 0) {
            mainMap.moveCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(savedLatitude, savedLongitude), 18f));
            firstFix = false;
        }
    }

    /** Reverse-geocode off the UI thread, skipping if we already resolved a spot within ~15m. */
    private void maybeGeocodeAddress(double lat, double lng) {
        if (!Double.isNaN(lastGeocodedLat)) {
            float[] res = new float[1];
            Location.distanceBetween(lastGeocodedLat, lastGeocodedLng, lat, lng, res);
            if (res[0] < 15f) return;  // GPS jitter — reuse the last address
        }
        lastGeocodedLat = lat; lastGeocodedLng = lng;
        geocodeExecutor.execute(() -> {
            String addr;
            try {
                List<Address> a = new Geocoder(this).getFromLocation(lat, lng, 1);
                addr = (a != null && !a.isEmpty()) ? a.get(0).getAddressLine(0) : null;
            } catch (Exception e) { addr = null; }
            final String result = addr;
            runOnUiThread(() -> {
                if (result != null) { savedAddress = result; statsState.setAddress(result); }
                else statsState.setAddress("Unable to get address");
            });
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        geocodeExecutor.shutdownNow();
    }

    private void shareLocation() {
        String address = savedAddress.isEmpty() ? "Unknown address" : savedAddress;
        String coords = String.format("%.6f, %.6f", savedLatitude, savedLongitude);
        String mapsUrl = "https://maps.google.com/?q=" + savedLatitude + "," + savedLongitude;
        String shareText = "📍 " + address + "\n🌐 Coordinates: " + coords + "\n🗺️ Open in Maps: " + mapsUrl;
        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("text/plain"); shareIntent.putExtra(Intent.EXTRA_TEXT, shareText);
        startActivity(Intent.createChooser(shareIntent, "Share Location via"));
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_FINE_LOCATION && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            enableMyLocationLayer(); startLocationUpdates();
        }
    }

    private boolean hasLocationPermission() { return ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED; }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;
        double lat = data.getDoubleExtra("picked_lat", 0); double lng = data.getDoubleExtra("picked_lng", 0);
        if (requestCode == MAP_PICKER_REQUEST) {
            savedLatitude = lat; savedLongitude = lng; savedAddress = data.getStringExtra("picked_address");
            String shown = savedAddress != null ? savedAddress : "";
            statsState.setAddress(shown); statsState.setSavedAddress(shown);
            if (mainMap != null) mainMap.animateCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(lat, lng), 18f));
        } else if (requestCode == WAYPOINT_PICKER_REQUEST) {
            App myApp = (App) getApplicationContext(); Location pickedLoc = new Location("picked"); pickedLoc.setLatitude(lat); pickedLoc.setLongitude(lng);
            myApp.saveLocation(pickedLoc); String key = App.locationKey(lat, lng);
            String name = data.getStringExtra("picked_name"); String notes = data.getStringExtra("picked_notes");
            if (name != null && !name.isEmpty()) myApp.saveLocationName(key, name);
            if (notes != null && !notes.isEmpty()) myApp.saveNote(key, notes);
            refreshMapMarkers();
        }
    }

    private void refreshMapMarkers() {
        if (mainMap == null) return;
        for (Marker m : markerKeys.keySet()) m.remove();
        for (Marker m : labelKeys.keySet()) m.remove();
        markerKeys.clear(); labelKeys.clear(); noteFullIcons.clear();
        notesCollapsed = null;
        App myApp = (App) getApplicationContext();
        for (Location loc : myApp.getMyLocations()) {
            String key = App.locationKey(loc.getLatitude(), loc.getLongitude());
            String name = myApp.getLocationName(key);
            String note = myApp.getLocationNotes().getOrDefault(key, "");
            LatLng pos = new LatLng(loc.getLatitude(), loc.getLongitude());

            if (myApp.isLandmark(key)) {
                // Landmark: keep Google's native icon (no red pin). Note card sits below it.
                if (!note.isEmpty()) addLandmarkNoteLabel(pos, note, key);
            } else {
                String title = name.isEmpty()
                        ? String.format("%.5f, %.5f", loc.getLatitude(), loc.getLongitude()) : name;
                Marker marker = mainMap.addMarker(new MarkerOptions().position(pos).title(title)
                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED)));
                markerKeys.put(marker, key);
                if (!note.isEmpty()) addPinNoteLabel(pos, title, note, key);
            }
        }
        applyNoteZoom();
    }

    /** Note card floating above a red pin (title + note); collapses to a pencil on zoom-out. */
    private void addPinNoteLabel(LatLng pos, String title, String note, String key) {
        BitmapDescriptor full = buildLabelBitmap(this, title, note, 0f);
        Marker label = mainMap.addMarker(new MarkerOptions()
                .position(new LatLng(pos.latitude + 0.00018, pos.longitude))
                .icon(full).flat(true).anchor(0.5f, 1.0f).zIndex(2f));
        labelKeys.put(label, key);
        noteFullIcons.put(label, full);
    }

    /** Landmark note card anchored just BELOW the icon/label; collapses to a pencil on zoom-out. */
    private void addLandmarkNoteLabel(LatLng pos, String note, String key) {
        float d = getResources().getDisplayMetrics().density;
        // Transparent top-padding gives a constant pixel gap that clears Google's icon + label.
        BitmapDescriptor full = buildLabelBitmap(this, "", note, 22 * d);
        Marker label = mainMap.addMarker(new MarkerOptions()
                .position(pos).icon(full).anchor(0.5f, 0f).zIndex(2f));
        labelKeys.put(label, key);
        noteFullIcons.put(label, full);
    }

    private void applyNoteZoom() {
        if (mainMap == null || noteFullIcons.isEmpty()) return;
        boolean collapse = mainMap.getCameraPosition().zoom < LABEL_ZOOM;
        if (notesCollapsed != null && notesCollapsed == collapse) return;
        notesCollapsed = collapse;
        for (Map.Entry<Marker, BitmapDescriptor> e : noteFullIcons.entrySet()) {
            Marker m = e.getKey();
            m.setIcon(collapse ? getPencilIcon() : e.getValue());  // keep each marker's own anchor
        }
    }

    private BitmapDescriptor getPencilIcon() {
        if (pencilIcon == null) pencilIcon = buildPencilBitmap(this);
        return pencilIcon;
    }

    /** Small white circle with a pencil — the collapsed state of a landmark note. */
    private static BitmapDescriptor buildPencilBitmap(Context ctx) {
        float d = ctx.getResources().getDisplayMetrics().density;
        int size = (int) (32 * d);
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setColor(Color.WHITE);
        bg.setShadowLayer(4 * d, 0, 1 * d, 0x33000000);
        c.drawCircle(size / 2f, size / 2f, size / 2f - 5 * d, bg);
        Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
        tp.setTextSize(15 * d);
        tp.setTextAlign(Paint.Align.CENTER);
        Paint.FontMetrics fm = tp.getFontMetrics();
        c.drawText("✏️", size / 2f, size / 2f - (fm.ascent + fm.descent) / 2f, tp);
        return BitmapDescriptorFactory.fromBitmap(bmp);
    }

    /** Modern rounded note card: white pill, soft shadow, dark title, teal note. topPad reserves
     *  transparent space above the card so an anchor(0.5,0) marker sits below the map point. */
    private static BitmapDescriptor buildLabelBitmap(Context ctx, String title, String note, float topPad) {
        float d = ctx.getResources().getDisplayMetrics().density;
        float padH = 12 * d, padV = 9 * d, lineGap = 4 * d, shadow = 6 * d, radius = 12 * d;
        boolean hasTitle = title != null && !title.isEmpty();
        boolean hasNote  = note != null && !note.isEmpty();
        String noteText = hasNote ? "📝 " + note : "";

        Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
        tp.setTextSize(12 * d); tp.setTypeface(Typeface.DEFAULT_BOLD); tp.setColor(0xFF1E293B);
        Paint np = new Paint(Paint.ANTI_ALIAS_FLAG);
        np.setTextSize(11 * d); np.setColor(0xFF00A77D); // teal, not blue

        float textW = 0;
        if (hasTitle) textW = Math.max(textW, tp.measureText(title));
        if (hasNote)  textW = Math.max(textW, np.measureText(noteText));
        Paint.FontMetrics tm = tp.getFontMetrics(), nm = np.getFontMetrics();
        float titleH = hasTitle ? (tm.descent - tm.ascent) : 0;
        float noteH  = hasNote  ? (nm.descent - nm.ascent) : 0;
        float gap = (hasTitle && hasNote) ? lineGap : 0;

        float cardW = textW + padH * 2;
        float cardH = padV * 2 + titleH + noteH + gap;
        float w = cardW + shadow * 2, h = cardH + shadow * 2 + topPad;

        Bitmap bmp = Bitmap.createBitmap((int) Math.ceil(w), (int) Math.ceil(h), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setColor(Color.WHITE);
        bg.setShadowLayer(shadow, 0, 2 * d, 0x33000000);
        c.drawRoundRect(new RectF(shadow, shadow + topPad, w - shadow, h - shadow), radius, radius, bg);

        float x = shadow + padH, top = shadow + topPad + padV;
        if (hasTitle) {
            c.drawText(title, x, top - tm.ascent, tp);
            top += titleH + gap;
        }
        if (hasNote) c.drawText(noteText, x, top - nm.ascent, np);
        return BitmapDescriptorFactory.fromBitmap(bmp);
    }

    private void setupSearch() {
        if (!Places.isInitialized()) Places.initialize(getApplicationContext(), BuildConfig.MAPS_API_KEY);
        placesClient = Places.createClient(this);
        androidx.compose.ui.platform.ComposeView cv = findViewById(R.id.search_compose);
        SearchHost.install(cv, placesClient, isDarkMode(), ll -> {
            if (mainMap != null) mainMap.animateCamera(CameraUpdateFactory.newLatLngZoom(ll, 16f));
        });
    }

    private void togglePickerMode() {
        isPickerModeActive = !isPickerModeActive; statsState.setPinMode(isPickerModeActive);
        if (isPickerModeActive) {
            mainMap.setOnMapClickListener(ll -> {
                if (tempPickerMarker != null) tempPickerMarker.remove();
                tempPickerMarker = mainMap.addMarker(new MarkerOptions().position(ll).icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN)));
                showSavePickedLocationDialog(ll);
            });
        } else { if (tempPickerMarker != null) tempPickerMarker.remove(); mainMap.setOnMapClickListener(null); }
    }

    private void showSavePickedLocationDialog(LatLng ll) {
        View v = getLayoutInflater().inflate(R.layout.dialog_savepin, null); AlertDialog dialog = new AlertDialog.Builder(this).setView(v).create();
        v.findViewById(R.id.btn_pin_save).setOnClickListener(view -> {
            Location loc = new Location("picked"); loc.setLatitude(ll.latitude); loc.setLongitude(ll.longitude);
            App myApp = (App) getApplicationContext();
            myApp.saveLocation(loc);
            String key = App.locationKey(ll.latitude, ll.longitude);
            String name = ((EditText)v.findViewById(R.id.et_pin_name)).getText().toString().trim();
            if (!name.isEmpty()) myApp.saveLocationName(key, name);
            String notes = ((EditText)v.findViewById(R.id.et_pin_notes)).getText().toString().trim();
            if (!notes.isEmpty()) myApp.saveNote(key, notes);
            refreshMapMarkers(); dialog.dismiss(); togglePickerMode();
        });
        v.findViewById(R.id.btn_pin_cancel).setOnClickListener(view -> { dialog.dismiss(); togglePickerMode(); });
        dialog.show();
    }
    
    private void handleFocusIntent(Intent intent) {
        if (intent == null) return;
        double lat = intent.getDoubleExtra("focus_lat", 0); double lng = intent.getDoubleExtra("focus_lng", 0);
        if (lat != 0 && lng != 0 && mainMap != null) {
            mainMap.animateCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(lat, lng), 18f));
        }
    }
}