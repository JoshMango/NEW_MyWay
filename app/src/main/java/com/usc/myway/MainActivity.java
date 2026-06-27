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
import android.widget.LinearLayout;
import android.widget.ListView;
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
import com.google.android.libraries.places.api.Places;
import com.google.android.libraries.places.api.model.AutocompletePrediction;
import com.google.android.libraries.places.api.model.Place;
import com.google.android.libraries.places.api.net.FetchPlaceRequest;
import com.google.android.libraries.places.api.net.FindAutocompletePredictionsRequest;
import com.google.android.libraries.places.api.net.PlacesClient;

import java.util.ArrayList;
import java.util.Arrays;
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

    private GoogleMap mainMap;
    private boolean mapReady = false;
    private boolean firstFix = true;
    private final Map<Marker, String> markerKeys  = new HashMap<>();
    private final Map<Marker, Marker> labelMarkers = new HashMap<>();

    private TextView tv_lat, tv_lon, tv_alt, tv_accuracy, tv_speed,
            tv_address, tv_savedAdd, tv_waypointCounts, tv_toggletheme, tv_pin_label;
    private LinearLayout btn_newWaypoint, btn_showWaypointList,
            btn_toggle_theme, btn_setLocation, btn_save_location, btn_share_location, btn_pin_on_map;
    private SwitchCompat sw_locUpdates, sw_gps;

    private double savedLatitude  = 0;
    private double savedLongitude = 0;
    private String savedAddress   = "";
    private FusedLocationProviderClient fusedLocClient;
    private LocationRequest locReq;
    private LocationCallback locCallBack;

    private Marker tempPickerMarker = null;
    private boolean isPickerModeActive = false;
    private TextWatcher searchWatcher;

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

    private void setupThemeToggle() {
        TextView tv_icon = (TextView) btn_toggle_theme.getChildAt(0);
        tv_icon.setText(isDarkMode() ? "☀️" : "🌙");
        tv_toggletheme.setText(isDarkMode() ? "Light Mode" : "Dark Mode");

        btn_toggle_theme.setOnClickListener(v -> {
            if (isDarkMode()) {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
            } else {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
            }
        });
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

    private void setupButtonListeners() {
        btn_newWaypoint.setOnClickListener(v -> {
            if (savedLatitude == 0 && savedLongitude == 0) return;
            Intent i = new Intent(this, MapPickerActivity.class);
            i.putExtra("latitude", savedLatitude); i.putExtra("longitude", savedLongitude);
            i.putExtra("mode", "waypoint");
            startActivityForResult(i, WAYPOINT_PICKER_REQUEST);
        });
        btn_pin_on_map.setOnClickListener(v -> togglePickerMode());
        btn_showWaypointList.setOnClickListener(v -> startActivity(new Intent(this, ShowSavedLocations.class)));
        btn_setLocation.setOnClickListener(v -> {
            if (savedLatitude == 0 && savedLongitude == 0) return;
            Intent i = new Intent(this, MapPickerActivity.class);
            i.putExtra("latitude", savedLatitude); i.putExtra("longitude", savedLongitude);
            i.putExtra("mode", "address");
            startActivityForResult(i, MAP_PICKER_REQUEST);
        });
        sw_gps.setOnClickListener(v -> locReq = buildLocReq(sw_gps.isChecked() ? Priority.PRIORITY_HIGH_ACCURACY : Priority.PRIORITY_BALANCED_POWER_ACCURACY));
        sw_locUpdates.setOnClickListener(v -> { if (sw_locUpdates.isChecked()) startLocationUpdates(); else stopLocationUpdates(); });
        btn_save_location.setOnClickListener(v -> btn_newWaypoint.performClick());
        btn_share_location.setOnClickListener(v -> shareLocation());
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
        
        mainMap.setInfoWindowAdapter(new GoogleMap.InfoWindowAdapter() {
            @Override public View getInfoWindow(Marker m) { return labelMarkers.containsValue(m) ? null : buildInfoWindow(m); }
            @Override public View getInfoContents(Marker m) { return null; }
        });
        mainMap.setOnMarkerClickListener(m -> { if (!labelMarkers.containsValue(m)) m.showInfoWindow(); return true; });
        refreshMapMarkers();
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
        tv_lat.setText("--"); tv_lon.setText("--"); tv_speed.setText("--"); tv_address.setText("Not tracking");
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
        tv_lat.setText(String.format("%.5f", savedLatitude)); tv_lon.setText(String.format("%.5f", savedLongitude));
        tv_accuracy.setText(String.format("%.1fm", loc.getAccuracy()));
        tv_alt.setText(loc.hasAltitude() ? String.format("%.1fm", loc.getAltitude()) : "N/A");
        tv_speed.setText(loc.hasSpeed() ? String.format("%.1fkm/h", loc.getSpeed() * 3.6f) : "0km/h");
        try {
            List<Address> addresses = new Geocoder(this).getFromLocation(savedLatitude, savedLongitude, 1);
            if (addresses != null && !addresses.isEmpty()) { savedAddress = addresses.get(0).getAddressLine(0); tv_address.setText(savedAddress); }
        } catch (Exception e) { tv_address.setText("Unable to get address"); }
        App myApp = (App) getApplicationContext();
        tv_waypointCounts.setText(String.valueOf(myApp.getMyLocations().size()));
        if (mapReady && mainMap != null && firstFix && savedLatitude != 0) {
            mainMap.moveCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(savedLatitude, savedLongitude), 18f));
            firstFix = false;
        }
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
            tv_address.setText(savedAddress); tv_savedAdd.setVisibility(View.VISIBLE); tv_savedAdd.setText("📍 " + savedAddress);
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
        for (Marker m : markerKeys.keySet()) m.remove(); for (Marker m : labelMarkers.values()) m.remove();
        markerKeys.clear(); labelMarkers.clear();
        App myApp = (App) getApplicationContext();
        for (Location loc : myApp.getMyLocations()) {
            String key = App.locationKey(loc.getLatitude(), loc.getLongitude());
            String name = myApp.getLocationName(key);
            String title = name.isEmpty() ? String.format("%.5f, %.5f", loc.getLatitude(), loc.getLongitude()) : name;
            Marker marker = mainMap.addMarker(new MarkerOptions().position(new LatLng(loc.getLatitude(), loc.getLongitude())).title(title).icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED)));
            markerKeys.put(marker, key);
            String note = myApp.getLocationNotes().getOrDefault(key, "");
            if (!note.isEmpty()) addLabelMarker(marker, note);
        }
    }

    private View buildInfoWindow(Marker marker) {
        View view = getLayoutInflater().inflate(R.layout.custom_infowindow, null);
        ((TextView) view.findViewById(R.id.tv_info_title)).setText(marker.getTitle());
        String note = ((App)getApplicationContext()).getLocationNotes().getOrDefault(markerKeys.getOrDefault(marker, ""), "");
        TextView tv_snippet = view.findViewById(R.id.tv_info_snippet);
        if (!note.isEmpty()) { tv_snippet.setVisibility(View.VISIBLE); tv_snippet.setText("📝 " + note); }
        else { tv_snippet.setVisibility(View.GONE); }
        return view;
    }

    private void addLabelMarker(Marker pinMarker, String note) {
        LatLng pos = pinMarker.getPosition();
        Marker label = mainMap.addMarker(new MarkerOptions().position(new LatLng(pos.latitude + 0.00018, pos.longitude)).icon(buildLabelBitmap(this, pinMarker.getTitle(), note)).flat(true).anchor(0.5f, 1.0f).zIndex(2f));
        labelMarkers.put(pinMarker, label);
    }

    private static BitmapDescriptor buildLabelBitmap(Context ctx, String title, String note) {
        float dp = ctx.getResources().getDisplayMetrics().density; float pad = 10 * dp;
        Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG); tp.setTextSize(11*dp); tp.setTypeface(Typeface.DEFAULT_BOLD);
        Paint np = new Paint(Paint.ANTI_ALIAS_FLAG); np.setTextSize(10*dp); np.setColor(Color.BLUE);
        float w = Math.max(tp.measureText(title), np.measureText(note)) + pad * 2; float h = 40 * dp;
        Bitmap bmp = Bitmap.createBitmap((int) w, (int) h, Bitmap.Config.ARGB_8888); Canvas c = new Canvas(bmp);
        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG); bg.setColor(Color.WHITE); c.drawRoundRect(new RectF(0, 0, w, h-8*dp), 8*dp, 8*dp, bg);
        c.drawText(title, pad, 15*dp, tp); c.drawText(note, pad, 28*dp, np);
        return BitmapDescriptorFactory.fromBitmap(bmp);
    }

    private void setupSearch() {
        if (!Places.isInitialized()) Places.initialize(getApplicationContext(), getString(R.string.google_maps_key));
        placesClient = Places.createClient(this);
        et_search = findViewById(R.id.et_search); 
        lv_autocomplete = findViewById(R.id.lv_autocomplete); 
        btn_search_clear = findViewById(R.id.btn_search_clear);
        
        ArrayAdapter<AutocompletePrediction> adapter = new ArrayAdapter<AutocompletePrediction>(this, R.layout.item_autocomplete, R.id.tv_place_name, predictions) {
            @NonNull @Override public View getView(int position, View convertView, @NonNull ViewGroup parent) {
                if (convertView == null) convertView = LayoutInflater.from(getContext()).inflate(R.layout.item_autocomplete, parent, false);
                AutocompletePrediction pred = getItem(position);
                if (pred != null) {
                    ((TextView) convertView.findViewById(R.id.tv_place_name)).setText(pred.getPrimaryText(null));
                    ((TextView) convertView.findViewById(R.id.tv_place_address)).setText(pred.getSecondaryText(null));
                }
                return convertView;
            }
        };
        lv_autocomplete.setAdapter(adapter);

        searchWatcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                String q = s.toString().trim(); 
                btn_search_clear.setVisibility(q.isEmpty() ? View.GONE : View.VISIBLE);
                if (q.length() < 2) { 
                    lv_autocomplete.setVisibility(View.GONE); 
                    predictions.clear();
                    adapter.notifyDataSetChanged();
                    return; 
                }
                placesClient.findAutocompletePredictions(FindAutocompletePredictionsRequest.builder().setQuery(q).build())
                    .addOnSuccessListener(resp -> {
                        predictions.clear(); 
                        predictions.addAll(resp.getAutocompletePredictions()); 
                        adapter.notifyDataSetChanged();
                        lv_autocomplete.setVisibility(predictions.isEmpty() ? View.GONE : View.VISIBLE); 
                        lv_autocomplete.bringToFront();
                    })
                    .addOnFailureListener(e -> {
                        lv_autocomplete.setVisibility(View.GONE);
                    });
            }
            @Override public void afterTextChanged(Editable s) {}
        };
        et_search.addTextChangedListener(searchWatcher);

        lv_autocomplete.setOnItemClickListener((parent, view, position, id) -> {
            AutocompletePrediction pred = predictions.get(position); 
            et_search.removeTextChangedListener(searchWatcher);
            et_search.setText(pred.getPrimaryText(null).toString()); 
            et_search.addTextChangedListener(searchWatcher);
            lv_autocomplete.setVisibility(View.GONE); 
            hideKeyboard();
            placesClient.fetchPlace(FetchPlaceRequest.newInstance(pred.getPlaceId(), Arrays.asList(Place.Field.LAT_LNG)))
                .addOnSuccessListener(resp -> {
                    LatLng ll = resp.getPlace().getLatLng(); 
                    if (ll != null && mainMap != null) mainMap.animateCamera(CameraUpdateFactory.newLatLngZoom(ll, 16f));
                });
        });

        btn_search_clear.setOnClickListener(v -> { 
            et_search.setText(""); 
            lv_autocomplete.setVisibility(View.GONE); 
            predictions.clear();
            adapter.notifyDataSetChanged();
            hideKeyboard(); 
        });
    }

    private void hideKeyboard() { 
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE); 
        if (imm != null && et_search != null) imm.hideSoftInputFromWindow(et_search.getWindowToken(), 0); 
    }

    private void togglePickerMode() {
        isPickerModeActive = !isPickerModeActive; tv_pin_label.setText(isPickerModeActive ? "Cancel" : "Pin");
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
            ((App)getApplicationContext()).saveLocation(loc); String name = ((EditText)v.findViewById(R.id.et_pin_name)).getText().toString().trim();
            if (!name.isEmpty()) ((App)getApplicationContext()).saveLocationName(App.locationKey(ll.latitude, ll.longitude), name);
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