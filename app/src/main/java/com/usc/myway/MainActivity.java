package ph.edu.gps_tracker;

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