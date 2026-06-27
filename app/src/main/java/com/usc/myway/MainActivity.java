package com.usc.myway;

import android.view.LayoutInflater;
import android.view.ViewGroup;

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
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.widget.SwitchCompat;
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

import android.text.Editable;
import android.text.TextWatcher;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.ListView;

import com.google.android.libraries.places.api.Places;
import com.google.android.libraries.places.api.model.AutocompletePrediction;
import com.google.android.libraries.places.api.model.Place;
import com.google.android.libraries.places.api.net.FetchPlaceRequest;
import com.google.android.libraries.places.api.net.FindAutocompletePredictionsRequest;
import com.google.android.libraries.places.api.net.PlacesClient;

import java.util.Arrays;
import java.util.ArrayList;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends AppCompatActivity implements OnMapReadyCallback {
    private PlacesClient placesClient;
    private EditText et_search;
    private ListView lv_autocomplete;
    private TextView btn_search_clear;
    private final List<AutocompletePrediction> predictions = new ArrayList<>();
    private static final int PERMISSION_FINE_LOCATION = 99;
    private static final int MAP_PICKER_REQUEST       = 101;
    private static final int WAYPOINT_PICKER_REQUEST  = 102;
    public  static final int DEF_INT_INTERVAL         = 30;
    public  static final int FAST_UPD_INTERVAL        = 5;

    // Map
    private GoogleMap mainMap;
    private boolean mapReady = false;
    private boolean firstFix = true;
    private final Map<Marker, String> markerKeys  = new HashMap<>();
    private final Map<Marker, Marker> labelMarkers = new HashMap<>();

    // UI
    private TextView tv_lat, tv_lon, tv_alt, tv_accuracy, tv_speed,
            tv_address, tv_savedAdd, tv_waypointCounts, tv_toggletheme, tv_pin_label;
    private LinearLayout btn_newWaypoint, btn_showWaypointList,
            btn_toggle_theme, btn_setLocation, btn_save_location, btn_share_location, btn_pin_on_map;
    private SwitchCompat sw_locUpdates, sw_gps;

    // Location
    private double savedLatitude  = 0;
    private double savedLongitude = 0;
    private String savedAddress   = "";
    private FusedLocationProviderClient fusedLocClient;
    private LocationRequest locReq;
    private LocationCallback locCallBack;

    private Marker tempPickerMarker = null;
    private boolean isPickerModeActive = false;

    // ── Lifecycle ─────────────────────────────────────────────────────────
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
        setupThemeToggle();
        setupLocationRequest();
        setupButtonListeners();
        setupSearch();

        updateGPS();
        sw_locUpdates.setChecked(true);
        startLocationUpdates();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshMapMarkers();
        handleFocusIntent(getIntent());
    }

    // ── View binding ──────────────────────────────────────────────────────
    private void bindViews() {
        tv_lat            = findViewById(R.id.tv_lat);
        tv_lon            = findViewById(R.id.tv_lon);
        tv_alt            = findViewById(R.id.tv_altitude);
        tv_accuracy       = findViewById(R.id.tv_accuracy);
        tv_speed          = findViewById(R.id.tv_speed);
        tv_address        = findViewById(R.id.tv_address);
        tv_savedAdd       = findViewById(R.id.tv_savedAddress);
        tv_waypointCounts = findViewById(R.id.tv_countCrumbs);
        sw_gps            = findViewById(R.id.sw_gps);
        sw_locUpdates     = findViewById(R.id.sw_locationsupdates);
        btn_newWaypoint      = findViewById(R.id.btn_newWaypoint);
        btn_showWaypointList = findViewById(R.id.btn_showWaypoint);
        btn_setLocation      = findViewById(R.id.btn_setLocation);
        btn_toggle_theme     = findViewById(R.id.btn_toggle_theme);
        tv_toggletheme       = findViewById(R.id.txt_toggletheme);
        btn_save_location  = findViewById(R.id.btn_save_location);
        btn_share_location = findViewById(R.id.btn_share_location);
        btn_pin_on_map    = findViewById(R.id.btn_pin_on_map);
        tv_pin_label      = findViewById(R.id.tv_pin_label);
    }

    private void setupHamburger() {
        LinearLayout btn_hamburger = findViewById(R.id.btn_hamburger);
        LinearLayout left_sidebar  = findViewById(R.id.left_sidebar);
        btn_hamburger.setOnClickListener(v -> {
            boolean visible = left_sidebar.getVisibility() == View.VISIBLE;
            if (visible) {
                left_sidebar.animate()
                        .alpha(0f).translationX(-left_sidebar.getWidth())
                        .setDuration(200)
                        .withEndAction(() -> left_sidebar.setVisibility(View.GONE))
                        .start();
            } else {
                left_sidebar.setVisibility(View.VISIBLE);
                left_sidebar.setAlpha(0f);
                left_sidebar.setTranslationX(-left_sidebar.getWidth());
                left_sidebar.animate().alpha(1f).translationX(0f).setDuration(200).start();
            }
        });
    }

    private void setupThemeToggle() {
        TextView tv_icon = (TextView) btn_toggle_theme.getChildAt(0);
        tv_icon.setText(isDarkMode() ? "☀️" : "🌙");
        tv_toggletheme.setText(isDarkMode() ? "Light Mode" : "Dark Mode");

        btn_toggle_theme.setOnClickListener(v -> {
            if (isDarkMode()) {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
                tv_icon.setText("🌙");
                if (mainMap != null) mainMap.setMapStyle(null);
            } else {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
                tv_icon.setText("☀️");
                if (mainMap != null) mainMap.setMapStyle(
                        MapStyleOptions.loadRawResourceStyle(this, R.raw.map_dark));
            }
        });
    }

    private boolean isDarkMode() {
        return AppCompatDelegate.getDefaultNightMode() == AppCompatDelegate.MODE_NIGHT_YES ||
                (AppCompatDelegate.getDefaultNightMode() == AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM &&
                        (getResources().getConfiguration().uiMode &
                                android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                                android.content.res.Configuration.UI_MODE_NIGHT_YES);
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
                .setMinUpdateIntervalMillis(1000L * FAST_UPD_INTERVAL)
                .build();
    }

    //------ALL BUTTON LISTERNERS-------
    private void setupButtonListeners() {
        btn_newWaypoint.setOnClickListener(v -> {
            if (savedLatitude == 0 && savedLongitude == 0) {
                Toast.makeText(this, "No location yet.", Toast.LENGTH_SHORT).show();
                return;
            }
            Intent i = new Intent(this, MapPickerActivity.class);
            i.putExtra("latitude", savedLatitude);
            i.putExtra("longitude", savedLongitude);
            i.putExtra("mode", "waypoint");
            startActivityForResult(i, WAYPOINT_PICKER_REQUEST);
        });

        btn_pin_on_map.setOnClickListener(v -> togglePickerMode());

        btn_showWaypointList.setOnClickListener(v ->
                startActivity(new Intent(this, ShowSavedLocations.class)));

        btn_setLocation.setOnClickListener(v -> {
            if (savedLatitude == 0 && savedLongitude == 0) {
                Toast.makeText(this, "No location available yet.", Toast.LENGTH_SHORT).show();
                return;
            }
            Intent i = new Intent(this, MapPickerActivity.class);
            i.putExtra("latitude", savedLatitude);
            i.putExtra("longitude", savedLongitude);
            i.putExtra("mode", "address");
            startActivityForResult(i, MAP_PICKER_REQUEST);
        });

        sw_gps.setOnClickListener(v -> locReq = buildLocReq(
                sw_gps.isChecked()
                        ? Priority.PRIORITY_HIGH_ACCURACY
                        : Priority.PRIORITY_BALANCED_POWER_ACCURACY));

        sw_locUpdates.setOnClickListener(v -> {
            if (sw_locUpdates.isChecked()) startLocationUpdates();
            else stopLocationUpdates();
        });

        btn_save_location.setOnClickListener(v -> {
            if (savedLatitude == 0 && savedLongitude == 0) {
                Toast.makeText(this, "No location yet.", Toast.LENGTH_SHORT).show();
                return;
            }
            Intent i = new Intent(this, MapPickerActivity.class);
            i.putExtra("latitude", savedLatitude);
            i.putExtra("longitude", savedLongitude);
            i.putExtra("mode", "waypoint");
            startActivityForResult(i, WAYPOINT_PICKER_REQUEST);
        });

        btn_share_location.setOnClickListener(v -> {
            if (savedLatitude == 0 && savedLongitude == 0) {
                Toast.makeText(this, "No location yet.", Toast.LENGTH_SHORT).show();
                return;
            }
            shareLocation();
        });
    }


    // ── Map ───────────────────────────────────────────────────────────────
    @Override
    public void onMapReady(GoogleMap googleMap) {
        mainMap = googleMap;
        mapReady = true;

        if (isDarkMode())
            mainMap.setMapStyle(MapStyleOptions.loadRawResourceStyle(this, R.raw.map_dark));

        if (hasLocationPermission()) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED)
                return;

            mainMap.setMyLocationEnabled(true);
            fusedLocClient.getLastLocation().addOnSuccessListener(location -> {
                if (location != null) {
                    savedLatitude  = location.getLatitude();
                    savedLongitude = location.getLongitude();
                    mainMap.moveCamera(CameraUpdateFactory.newLatLngZoom(
                            new LatLng(savedLatitude, savedLongitude), 18f));
                    firstFix = false;
                }
            });
        }
        mainMap.getUiSettings().setZoomControlsEnabled(false);
        mainMap.getUiSettings().setMyLocationButtonEnabled(false);
        mainMap.getUiSettings().setCompassEnabled(true);

        // Track which button was tapped by touch position
        final boolean[] deleteTapped = {false};

        mainMap.setInfoWindowAdapter(new GoogleMap.InfoWindowAdapter() {
            @Override public View getInfoWindow(Marker m) {
                if (labelMarkers.containsValue(m)) return null;
                return buildInfoWindow(m);
            }
            @Override public View getInfoContents(Marker m) { return null; }
        });

        mainMap.setOnMarkerClickListener(m -> {
            if (labelMarkers.containsValue(m)) return true;
            deleteTapped[0] = false;
            m.showInfoWindow();

            // Attach touch listener to the map to detect which button area was tapped
            mainMap.setOnMapClickListener(null); // clear temporarily
            return true;
        });

        mainMap.setOnInfoWindowClickListener(m -> {
            if (labelMarkers.containsValue(m)) return;
            // We can't reliably detect child taps — show a choice dialog instead
            new AlertDialog.Builder(this)
                    .setTitle(m.getTitle())
                    .setItems(new String[]{"✏️ Add / Edit Note", "🗑️ Delete Location"}, (d, which) -> {
                        if (which == 0) {
                            showNoteDialog(m);
                        } else {
                            new AlertDialog.Builder(this)
                                    .setTitle("Delete Waypoint")
                                    .setMessage("Are you sure you want to delete this location?")
                                    .setPositiveButton("Delete", (d2, w2) -> {
                                        App myApp = (App) getApplicationContext();
                                        String key = markerKeys.getOrDefault(m, "");
                                        for (Location loc : myApp.getMyLocations()) {
                                            if (App.locationKey(loc.getLatitude(),
                                                    loc.getLongitude()).equals(key)) {
                                                myApp.removeLocation(loc);
                                                break;
                                            }
                                        }
                                        tv_waypointCounts.setText(
                                                String.valueOf(myApp.getMyLocations().size()));
                                        m.hideInfoWindow();
                                        refreshMapMarkers();
                                        Toast.makeText(this, "Location deleted.",
                                                Toast.LENGTH_SHORT).show();
                                    })
                                    .setNegativeButton("Cancel", null)
                                    .show();
                        }
                    })
                    .show();
        });
        mainMap.setOnMarkerClickListener(m -> {
            if (labelMarkers.containsValue(m)) return true;
            m.showInfoWindow();
            return true;
        });
        refreshMapMarkers();
    }

    // ── Location ──────────────────────────────────────────────────────────
    private void startLocationUpdates() {
        if (!hasLocationPermission()) return;
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            return;

        fusedLocClient.requestLocationUpdates(locReq, locCallBack, null);
        updateGPS();
    }

    private void stopLocationUpdates() {
        tv_lat.setText("--");
        tv_lon.setText("--");
        tv_speed.setText("--");
        tv_address.setText("Not tracking");
        tv_accuracy.setText("--");
        tv_alt.setText("--");
        fusedLocClient.removeLocationUpdates(locCallBack);
    }

    private void updateGPS() {
        if (hasLocationPermission()) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED)
                return;

            fusedLocClient.getLastLocation().addOnSuccessListener(this, location -> {
                if (location != null) updateUIValues(location);
                else Toast.makeText(this, "Location not detected yet", Toast.LENGTH_SHORT).show();
            });
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    PERMISSION_FINE_LOCATION);
        }
    }

    private void updateUIValues(Location loc) {
        savedLatitude  = loc.getLatitude();
        savedLongitude = loc.getLongitude();

        tv_lat.setText(String.format("%.5f", savedLatitude));
        tv_lon.setText(String.format("%.5f", savedLongitude));
        tv_accuracy.setText(String.format("%.1fm", loc.getAccuracy()));
        tv_alt.setText(loc.hasAltitude() ? String.format("%.1fm", loc.getAltitude()) : "N/A");
        tv_speed.setText(loc.hasSpeed()
                ? String.format("%.1fkm/h", loc.getSpeed() * 3.6f)
                : "0km/h");

        try {
            List<Address> addresses = new Geocoder(this)
                    .getFromLocation(savedLatitude, savedLongitude, 1);
            if (addresses != null && !addresses.isEmpty()) {
                savedAddress = addresses.get(0).getAddressLine(0);
                tv_address.setText(savedAddress);
            }
        } catch (Exception e) {
            tv_address.setText("Unable to get address");
            savedAddress = "";
        }

        App myApp = (App) getApplicationContext();
        tv_waypointCounts.setText(String.valueOf(myApp.getMyLocations().size()));

        if (mapReady && mainMap != null) {
            LatLng current = new LatLng(savedLatitude, savedLongitude);
            if (firstFix) {
                mainMap.moveCamera(CameraUpdateFactory.newLatLngZoom(current, 18f));
                firstFix = false;
            } else {
                mainMap.moveCamera(CameraUpdateFactory.newLatLng(current));
            }
        }
    }
    //share location
    private void shareLocation() {
        String address = savedAddress.isEmpty() ? "Unknown address" : savedAddress;
        String coords  = String.format("%.6f, %.6f", savedLatitude, savedLongitude);
        String mapsUrl = "https://maps.google.com/?q=" + savedLatitude + "," + savedLongitude;

        String shareText = "📍 " + address + "\n" +
                "🌐 Coordinates: " + coords + "\n" +
                "🗺️ Open in Maps: " + mapsUrl;

        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("text/plain");
        shareIntent.putExtra(Intent.EXTRA_TEXT, shareText);
        startActivity(Intent.createChooser(shareIntent, "Share Location via"));
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_FINE_LOCATION) {
            if (grantResults[0] == PackageManager.PERMISSION_GRANTED) updateGPS();
            else { Toast.makeText(this, "Location permission required.", Toast.LENGTH_SHORT).show(); finish(); }
        }
    }

    private boolean hasLocationPermission() {
        return ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    // ── Activity results ──────────────────────────────────────────────────
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;

        double pickedLat = data.getDoubleExtra("picked_lat", 0);
        double pickedLng = data.getDoubleExtra("picked_lng", 0);

        if (requestCode == MAP_PICKER_REQUEST) {
            savedLatitude  = pickedLat;
            savedLongitude = pickedLng;
            savedAddress   = data.getStringExtra("picked_address");
            tv_address.setText(savedAddress);
            tv_savedAdd.setVisibility(View.VISIBLE);
            tv_savedAdd.setText("📍 " + savedAddress);
            Toast.makeText(this, "Address updated!", Toast.LENGTH_SHORT).show();

        } else if (requestCode == WAYPOINT_PICKER_REQUEST) {
            App myApp = (App) getApplicationContext();
            Location pickedLoc = new Location("picked");
            pickedLoc.setLatitude(pickedLat);
            pickedLoc.setLongitude(pickedLng);

            for (Location loc : myApp.getMyLocations()) {
                if (loc.distanceTo(pickedLoc) < 5.0f) {
                    Toast.makeText(this, "Location already saved!", Toast.LENGTH_SHORT).show();
                    return;
                }
            }

            myApp.saveLocation(pickedLoc);
            String key = App.locationKey(pickedLat, pickedLng);
            String pickedName  = data.getStringExtra("picked_name");
            String pickedNotes = data.getStringExtra("picked_notes");
            String pickedCollection = data.getStringExtra("picked_collection");
            if (pickedName  != null && !pickedName.isEmpty())  myApp.saveLocationName(key, pickedName);
            if (pickedNotes != null && !pickedNotes.isEmpty()) myApp.saveNote(key, pickedNotes);

            if (pickedCollection != null) {
                for (Collection c : myApp.getCollections()) {
                    if (c.name.equals(pickedCollection)) {
                        if (!c.locationKeys.contains(key)) {
                            c.locationKeys.add(key);
                            myApp.saveCollectionsToPrefs();
                        }
                        break;
                    }
                } }
            tv_waypointCounts.setText(String.valueOf(myApp.getMyLocations().size()));
            Toast.makeText(this, "Waypoint saved!", Toast.LENGTH_SHORT).show();
            refreshMapMarkers();
        }
    }

    // ── Markers ───────────────────────────────────────────────────────────
    private void refreshMapMarkers() {
        if (mainMap == null) return;
        for (Marker m : markerKeys.keySet()) m.remove();
        for (Marker m : labelMarkers.values()) m.remove();
        markerKeys.clear();
        labelMarkers.clear();

        App myApp = (App) getApplicationContext();
        Map<String, String> notes = myApp.getLocationNotes();

        for (Location loc : myApp.getMyLocations()) {
            String key   = App.locationKey(loc.getLatitude(), loc.getLongitude());
            String name  = myApp.getLocationName(key);
            String title = name.isEmpty() ? getLocationTitle(loc.getLatitude(), loc.getLongitude()) : name;
            Marker marker = mainMap.addMarker(new MarkerOptions()
                    .position(new LatLng(loc.getLatitude(), loc.getLongitude()))
                    .title(title)
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED))
                    .zIndex(1f));
            markerKeys.put(marker, key);
            String note = notes.getOrDefault(key, "");
            if (!note.isEmpty()) addLabelMarker(marker, note);
        }
    }

    private View buildInfoWindow(Marker marker) {
        View view = getLayoutInflater().inflate(R.layout.custom_infowindow, null);
        ((TextView) view.findViewById(R.id.tv_info_title)).setText(marker.getTitle());

        App myApp = (App) getApplicationContext();
        String note = myApp.getLocationNotes().getOrDefault(markerKeys.getOrDefault(marker, ""), "");
        TextView tv_snippet  = view.findViewById(R.id.tv_info_snippet);
        TextView tv_btnLabel = view.findViewById(R.id.tv_note_btn_label);

        if (note != null && !note.isEmpty()) {
            tv_snippet.setVisibility(View.VISIBLE);
            tv_snippet.setText("📝 " + note);
            tv_btnLabel.setText("✏️ Edit Note");
        } else {
            tv_snippet.setVisibility(View.GONE);
            tv_btnLabel.setText("✏️ Add Note");
        }
        return view;
    }

    private void addLabelMarker(Marker pinMarker, String note) {
        if (note == null || note.isEmpty()) return;
        LatLng pos = pinMarker.getPosition();
        Marker label = mainMap.addMarker(new MarkerOptions()
                .position(new LatLng(pos.latitude + 0.00018, pos.longitude))
                .icon(buildLabelBitmap(this, pinMarker.getTitle(), note))
                .flat(true).anchor(0.5f, 1.0f).zIndex(2f));
        labelMarkers.put(pinMarker, label);
    }

    private void refreshLabel(Marker pinMarker) {
        Marker old = labelMarkers.remove(pinMarker);
        if (old != null) old.remove();
        App myApp = (App) getApplicationContext();
        String note = myApp.getLocationNotes()
                .getOrDefault(markerKeys.getOrDefault(pinMarker, ""), "");
        if (note != null && !note.isEmpty()) addLabelMarker(pinMarker, note);
    }

    private void showNoteDialog(Marker marker) {
        App myApp = (App) getApplicationContext();
        String key  = markerKeys.getOrDefault(marker, "");
        String existing = myApp.getLocationNotes().getOrDefault(key, "");

        EditText input = new EditText(this);
        input.setHint("e.g. Coffee shop, Meeting point...");
        input.setText(existing);
        input.setSingleLine(false);
        input.setMaxLines(3);

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        container.setPadding(pad, pad, pad, 0);
        container.addView(input);

        new AlertDialog.Builder(this)
                .setTitle("📝 " + (existing.isEmpty() ? "Add Note" : "Edit Note"))
                .setMessage("Enter a label or description for this location:")
                .setView(container)
                .setPositiveButton("Save", (d, w) -> {
                    String note = input.getText().toString().trim();
                    myApp.saveNote(key, note);
                    refreshLabel(marker);
                    marker.showInfoWindow();
                    Toast.makeText(this, "Note saved!", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .setNeutralButton("Clear", (d, w) -> {
                    myApp.removeNote(key);
                    refreshLabel(marker);
                    marker.showInfoWindow();
                })
                .show();
    }

    private static BitmapDescriptor buildLabelBitmap(Context ctx, String title, String note) {
        float dp      = ctx.getResources().getDisplayMetrics().density;
        float pad     = 10 * dp, tsz = 11 * dp, nsz = 10 * dp, r = 8 * dp, tail = 8 * dp;

        Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
        tp.setTextSize(tsz); tp.setTypeface(Typeface.DEFAULT_BOLD);
        tp.setColor(Color.parseColor("#1E293B"));

        Paint np = new Paint(Paint.ANTI_ALIAS_FLAG);
        np.setTextSize(nsz); np.setColor(Color.parseColor("#3B82F6"));

        String t  = title.length() > 28 ? title.substring(0, 25) + "..." : title;
        String nt = "📝 " + (note.length() > 32 ? note.substring(0, 29) + "..." : note);

        float w = Math.max(tp.measureText(t), np.measureText(nt)) + pad * 2;
        float h = tsz + nsz + 4 * dp + pad * 2 + tail;

        Bitmap bmp = Bitmap.createBitmap((int) w, (int) h, Bitmap.Config.ARGB_8888);
        Canvas c   = new Canvas(bmp);
        RectF bubble = new RectF(0, 0, w, h - tail);

        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG); bg.setColor(Color.WHITE);
        c.drawRoundRect(bubble, r, r, bg);

        float cx = w / 2f;
        Paint tailPaint = new Paint(Paint.ANTI_ALIAS_FLAG); tailPaint.setColor(Color.WHITE);
        Path tailPath = new Path();
        tailPath.moveTo(cx - 6*dp, h - tail);
        tailPath.lineTo(cx + 6*dp, h - tail);
        tailPath.lineTo(cx, h);
        tailPath.close();
        c.drawPath(tailPath, tailPaint);

        Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
        border.setColor(Color.parseColor("#E2E8F0"));
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(1.5f * dp);
        c.drawRoundRect(bubble, r, r, border);

        c.drawText(t,  pad, pad + tsz, tp);
        c.drawText(nt, pad, pad + tsz + 4*dp + nsz, np);

        return BitmapDescriptorFactory.fromBitmap(bmp);
    }

    private String getLocationTitle(double lat, double lng) {
        try {
            List<android.location.Address> list =
                    new Geocoder(this).getFromLocation(lat, lng, 1);
            if (list != null && !list.isEmpty()) {
                android.location.Address a = list.get(0);
                String name = a.getFeatureName(), street = a.getThoroughfare(),
                        suburb = a.getSubLocality(), city = a.getLocality();
                if (name   != null && !name.matches("\\d+.*")) return name;
                if (street != null) return name != null ? name + ", " + street : street;
                if (suburb != null) return suburb;
                if (city   != null) return city;
            }
        } catch (Exception ignored) {}
        return String.format("%.5f, %.5f", lat, lng);
    }

    /*
    The reason we call "handleFocusIntent" in onResume too is as a fallback — there's a timing issue where sometimes
    onNewIntent fires but the map isn't ready yet (mapReady = false), so handleFocusIntent does nothing.
    Then when onResume fires shortly after, the map might be ready and it can pan correctly.
    But there's actually a subtle bug here — onResume fires every time you come back to MainActivity
    (e.g. opening and closing settings), and if the intent still has focus_lat/focus_lng in it,
    it will keep re-panning the map every time. That's why we have this line inside handleFocusIntent:
        intent.removeExtra("focus_lat");
        intent.removeExtra("focus_lng");
     */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleFocusIntent(intent); //pans the map to the location
    }
    private void handleFocusIntent(Intent intent) {
        if (intent == null) return;
        double focusLat = intent.getDoubleExtra("focus_lat", 0);
        double focusLng = intent.getDoubleExtra("focus_lng", 0);
        if (focusLat == 0 && focusLng == 0) return;

        if (mapReady && mainMap != null) {
            LatLng target = new LatLng(focusLat, focusLng);
            mainMap.animateCamera(CameraUpdateFactory.newLatLngZoom(target, 18f));

            // find and show the info window for this marker
            for (Map.Entry<Marker, String> entry : markerKeys.entrySet()) {
                String key = App.locationKey(focusLat, focusLng);
                if (entry.getValue().equals(key)) {
                    entry.getKey().showInfoWindow();
                    break;
                }
            }
            // clear extras so onResume doesn't re-trigger it
            intent.removeExtra("focus_lat");
            intent.removeExtra("focus_lng");
        }
    }

    private void setupSearch() {
        // initialize Places SDK first
        if (!Places.isInitialized()) {
            Places.initialize(getApplicationContext(), getString(R.string.google_maps_key));
        }
        placesClient = Places.createClient(this);

        // find views
        et_search        = findViewById(R.id.et_search);
        lv_autocomplete  = findViewById(R.id.lv_autocomplete);
        btn_search_clear = findViewById(R.id.btn_search_clear);

        // set up adapter
        ArrayAdapter<String> autocompleteAdapter = new ArrayAdapter<String>(
                this, R.layout.item_autocomplete, R.id.tv_place_name, new ArrayList<>()) {
            @NonNull
            @Override
            public View getView(int position, View convertView, @NonNull ViewGroup parent) {
                if (convertView == null)
                    convertView = LayoutInflater.from(getContext())
                            .inflate(R.layout.item_autocomplete, parent, false);
                if (position < predictions.size()) {
                    AutocompletePrediction p = predictions.get(position);
                    ((TextView) convertView.findViewById(R.id.tv_place_name))
                            .setText(p.getPrimaryText(null).toString());
                    ((TextView) convertView.findViewById(R.id.tv_place_address))
                            .setText(p.getSecondaryText(null).toString());
                }
                return convertView;
            }
            @Override public int getCount() { return predictions.size(); }
        };
        lv_autocomplete.setAdapter(autocompleteAdapter);

        // declare watcher AFTER adapter and views are ready
        TextWatcher searchWatcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                String query = s.toString().trim();
                btn_search_clear.setVisibility(query.isEmpty() ? View.GONE : View.VISIBLE);

                if (query.length() < 2) {
                    lv_autocomplete.setVisibility(View.GONE);
                    predictions.clear();
                    autocompleteAdapter.notifyDataSetChanged();
                    return;
                }

                placesClient.findAutocompletePredictions(
                                FindAutocompletePredictionsRequest.builder().setQuery(query).build())
                        .addOnSuccessListener(response -> {
                            predictions.clear();
                            predictions.addAll(response.getAutocompletePredictions());
                            autocompleteAdapter.notifyDataSetChanged();
                            lv_autocomplete.setVisibility(
                                    predictions.isEmpty() ? View.GONE : View.VISIBLE);
                        })
                        .addOnFailureListener(e -> lv_autocomplete.setVisibility(View.GONE));
            }
        };
        // attach watcher — only once
        et_search.addTextChangedListener(searchWatcher);

        // tap item
        lv_autocomplete.setOnItemClickListener((parent, view, position, id) -> {
            if (position >= predictions.size()) return;
            AutocompletePrediction prediction = predictions.get(position);

            et_search.removeTextChangedListener(searchWatcher);
            et_search.setText(prediction.getPrimaryText(null).toString());
            et_search.addTextChangedListener(searchWatcher);

            lv_autocomplete.setVisibility(View.GONE);
            predictions.clear();
            autocompleteAdapter.notifyDataSetChanged();
            hideKeyboard();

            placesClient.fetchPlace(FetchPlaceRequest.newInstance(
                            prediction.getPlaceId(),
                            Arrays.asList(Place.Field.LAT_LNG, Place.Field.NAME)))
                    .addOnSuccessListener(response -> {
                        Place place = response.getPlace();
                        if (place.getLatLng() != null && mainMap != null) {
                            mainMap.animateCamera(CameraUpdateFactory.newLatLngZoom(
                                    new LatLng(place.getLatLng().latitude,
                                            place.getLatLng().longitude), 16f));
                        }
                    });
        });

        // keyboard search key
        et_search.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH && !predictions.isEmpty()) {
                lv_autocomplete.performItemClick(
                        lv_autocomplete.getAdapter().getView(0, null, lv_autocomplete),
                        0, lv_autocomplete.getAdapter().getItemId(0));
            }
            return true;
        });

        // clear button
        btn_search_clear.setOnClickListener(v -> {
            et_search.removeTextChangedListener(searchWatcher);
            et_search.setText("");
            et_search.addTextChangedListener(searchWatcher);
            lv_autocomplete.setVisibility(View.GONE);
            predictions.clear();
            autocompleteAdapter.notifyDataSetChanged();
            hideKeyboard();
        });
    }
    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null && et_search != null)
            imm.hideSoftInputFromWindow(et_search.getWindowToken(), 0);
    }

    private void togglePickerMode() {
        isPickerModeActive = !isPickerModeActive;
        if (isPickerModeActive) {
            tv_pin_label.setText("Cancel");
            Toast.makeText(this, "Tap anywhere on map to place a pin", Toast.LENGTH_SHORT).show();
            mainMap.setOnMapClickListener(latLng -> {
                // Remove old temp marker
                if (tempPickerMarker != null) tempPickerMarker.remove();
                // Place new temp marker
                tempPickerMarker = mainMap.addMarker(new MarkerOptions()
                        .position(latLng)
                        .draggable(true)
                        .title("New Waypoint")
                        .icon(BitmapDescriptorFactory.defaultMarker(
                                BitmapDescriptorFactory.HUE_GREEN)));
                // Show save dialog
                showSavePickedLocationDialog(latLng);
            });
        } else {
            cancelPickerMode();
        }
    }

    private void cancelPickerMode() {
        isPickerModeActive = false;
        tv_pin_label.setText("Pin");
        if (tempPickerMarker != null) {
            tempPickerMarker.remove();
            tempPickerMarker = null;
        }
        // Restore default map click (none)
        mainMap.setOnMapClickListener(null);
    }

    private void showSavePickedLocationDialog(LatLng latLng) {
        App myApp = (App) getApplicationContext();

        // Check duplicate
        Location pickedLoc = new Location("picked");
        pickedLoc.setLatitude(latLng.latitude);
        pickedLoc.setLongitude(latLng.longitude);
        for (Location loc : myApp.getMyLocations()) {
            if (loc.distanceTo(pickedLoc) < 5.0f) {
                Toast.makeText(this, "Location already saved!", Toast.LENGTH_SHORT).show();
                cancelPickerMode();
                return;
            }
        }
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_savepin, null);
        EditText et_name = dialogView.findViewById(R.id.et_pin_name);
        EditText et_notes = dialogView.findViewById(R.id.et_pin_notes);
        TextView tv_coords = dialogView.findViewById(R.id.tv_pin_coords);
        TextView tv_addr = dialogView.findViewById(R.id.tv_pin_address);
        TextView btn_cancel = dialogView.findViewById(R.id.btn_pin_cancel);
        TextView btn_save = dialogView.findViewById(R.id.btn_pin_save);
        LinearLayout btn_collection = dialogView.findViewById(R.id.btn_pin_collection);
        TextView tv_collection = dialogView.findViewById(R.id.tv_pin_collection);

        final String[] selectedCollection = {null};

        tv_coords.setText(String.format("%.6f, %.6f", latLng.latitude, latLng.longitude));
        tv_addr.setText("Resolving address...");

        // Geocode in background
        new Thread(() -> {
            try {
                List<Address> addresses = new Geocoder(this)
                        .getFromLocation(latLng.latitude, latLng.longitude, 1);
                if (addresses != null && !addresses.isEmpty()) {
                    String addr = addresses.get(0).getAddressLine(0);
                    runOnUiThread(() -> tv_addr.setText(addr));
                }
            } catch (Exception ignored) {
            }
        }).start();

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(dialogView)
                .setCancelable(false)
                .create();
        if (dialog.getWindow() != null)
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);

        // Collection picker
        btn_collection.setOnClickListener(v -> {
            List<Collection> collections = myApp.getCollections();
            if (collections.isEmpty()) {
                Toast.makeText(this, "No collections yet. Create one first.",
                        Toast.LENGTH_SHORT).show();
                return;
            }

            String[] options = new String[collections.size() + 1];
            options[0] = "❌ None";
            for (int i = 0; i < collections.size(); i++)
                options[i + 1] = collections.get(i).icon + " " + collections.get(i).name;

            new AlertDialog.Builder(this)
                    .setTitle("Add to Collection")
                    .setItems(options, (d, which) -> {
                        if (which == 0) {
                            selectedCollection[0] = null;
                            tv_collection.setText("Tap to choose a collection...");
                            tv_collection.setTextColor(
                                    getResources().getColor(R.color.text_light, getTheme()));
                        } else {
                            Collection chosen = collections.get(which - 1);
                            selectedCollection[0] = chosen.name;
                            tv_collection.setText(chosen.icon + " " + chosen.name);
                            tv_collection.setTextColor(
                                    getResources().getColor(R.color.text_dark, getTheme()));
                        }
                    })
                    .show();
        });

        //cancel
        btn_cancel.setOnClickListener(v -> {
            cancelPickerMode();
            dialog.dismiss();
        });

        //save
        btn_save.setOnClickListener(v -> {
            String name = et_name.getText().toString().trim();
            String notes = et_notes.getText().toString().trim();
            String addr = tv_addr.getText().toString();
            if (name.isEmpty()) name = addr.equals("Resolving address...") ?
                    String.format("%.5f, %.5f", latLng.latitude, latLng.longitude) : addr;

            myApp.saveLocation(pickedLoc);
            String key = App.locationKey(latLng.latitude, latLng.longitude);
            if (!name.isEmpty()) myApp.saveLocationName(key, name);
            if (!notes.isEmpty()) myApp.saveNote(key, notes);

            // Save to selected collection
            if (selectedCollection[0] != null) {
                for (Collection c : myApp.getCollections()) {
                    if (c.name.equals(selectedCollection[0])) {
                        if (!c.locationKeys.contains(key)) {
                            c.locationKeys.add(key);
                            myApp.saveCollectionsToPrefs();
                        }
                        break;
                    }
                }
            }
            tv_waypointCounts.setText(String.valueOf(myApp.getMyLocations().size()));
            Toast.makeText(this, "Waypoint saved!", Toast.LENGTH_SHORT).show();
            cancelPickerMode();
            refreshMapMarkers();
            dialog.dismiss();
        });
        dialog.show();
    }
}