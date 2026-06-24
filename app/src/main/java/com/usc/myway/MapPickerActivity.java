// logic for picking locations/add pins on map
package com.usc.myway;

import android.content.Intent;
import android.location.Address;
import android.location.Geocoder;
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

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;

import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
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
import java.util.List;

public class MapPickerActivity extends AppCompatActivity implements OnMapReadyCallback {

    private GoogleMap mMap;
    private GoogleMap mainMap;
    private boolean mapReady = false;
    private Marker currentMarker;
    private LatLng pickedLatLng;
    private String pickedAddress = "";
    private String mode = "address"; // "address" or "waypoint"
    private String selectedCollectionName = null;

    private TextView tv_coords, tv_address, btn_save, tv_selected_collection;
    private EditText et_name, et_notes;
    private LinearLayout layout_waypoint_inputs, btn_add_to_collection;

    private PlacesClient pickerPlacesClient;
    private final List<AutocompletePrediction> pickerPredictions = new ArrayList<>();
    private EditText et_picker_search;
    private ListView lv_picker_autocomplete;
    private TextView btn_picker_clear;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_map_picker);

        mode = getIntent().getStringExtra("mode");
        if (mode == null) mode = "address";

        tv_coords              = findViewById(R.id.tv_pickedCoords);
        tv_address             = findViewById(R.id.tv_pickedAddress);
        btn_save               = findViewById(R.id.btn_setAddress);
        et_name                = findViewById(R.id.et_waypoint_name);
        et_notes               = findViewById(R.id.et_waypoint_notes);
        layout_waypoint_inputs = findViewById(R.id.layout_waypoint_inputs);
        btn_add_to_collection  = findViewById(R.id.btn_add_to_collection);
        tv_selected_collection = findViewById(R.id.tv_selected_collection);

        // Show name/notes inputs only in waypoint mode
        if (mode.equals("waypoint")) {
            layout_waypoint_inputs.setVisibility(View.VISIBLE);
            btn_save.setText("Save Waypoint");
        } else {
            layout_waypoint_inputs.setVisibility(View.GONE);
            btn_save.setText("Set Address");
        }

        double lat = getIntent().getDoubleExtra("latitude", 0);
        double lng = getIntent().getDoubleExtra("longitude", 0);
        pickedLatLng = new LatLng(lat, lng);

        SupportMapFragment mapFragment = (SupportMapFragment)
                getSupportFragmentManager().findFragmentById(R.id.map_picker);
        if (mapFragment != null) mapFragment.getMapAsync(this);

        btn_save.setOnClickListener(v -> returnResult());

        btn_add_to_collection.setOnClickListener(v -> showCollectionPicker());

        setupPickerSearch();
    }
    private void setupPickerSearch() {
        if (!Places.isInitialized()) {
            Places.initialize(getApplicationContext(), getString(R.string.google_maps_key));
        }
        pickerPlacesClient = Places.createClient(this);

        et_picker_search      = findViewById(R.id.et_picker_search);
        lv_picker_autocomplete = findViewById(R.id.lv_picker_autocomplete);
        btn_picker_clear      = findViewById(R.id.btn_picker_search_clear);

        ArrayAdapter<String> adapter = new ArrayAdapter<String>(
                this, R.layout.item_autocomplete, R.id.tv_place_name, new ArrayList<>()) {
            @NonNull
            @Override
            public View getView(int pos, View convertView, @NonNull ViewGroup parent) {
                if (convertView == null)
                    convertView = LayoutInflater.from(getContext())
                            .inflate(R.layout.item_autocomplete, parent, false);
                if (pos < pickerPredictions.size()) {
                    AutocompletePrediction p = pickerPredictions.get(pos);
                    ((TextView) convertView.findViewById(R.id.tv_place_name))
                            .setText(p.getPrimaryText(null).toString());
                    ((TextView) convertView.findViewById(R.id.tv_place_address))
                            .setText(p.getSecondaryText(null).toString());
                }
                return convertView;
            }
            @Override public int getCount() { return pickerPredictions.size(); }
        };
        lv_picker_autocomplete.setAdapter(adapter);

        TextWatcher watcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                String query = s.toString().trim();
                btn_picker_clear.setVisibility(query.isEmpty() ? View.GONE : View.VISIBLE);
                if (query.length() < 2) {
                    lv_picker_autocomplete.setVisibility(View.GONE);
                    pickerPredictions.clear();
                    adapter.notifyDataSetChanged();
                    return;
                }
                pickerPlacesClient.findAutocompletePredictions(
                                FindAutocompletePredictionsRequest.builder().setQuery(query).build())
                        .addOnSuccessListener(response -> {
                            pickerPredictions.clear();
                            pickerPredictions.addAll(response.getAutocompletePredictions());
                            adapter.notifyDataSetChanged();
                            lv_picker_autocomplete.setVisibility(
                                    pickerPredictions.isEmpty() ? View.GONE : View.VISIBLE);
                        })
                        .addOnFailureListener(e ->
                                lv_picker_autocomplete.setVisibility(View.GONE));
            }
        };
        et_picker_search.addTextChangedListener(watcher);

        lv_picker_autocomplete.setOnItemClickListener((parent, view, position, id) -> {
            if (position >= pickerPredictions.size()) return;
            AutocompletePrediction prediction = pickerPredictions.get(position);

            et_picker_search.removeTextChangedListener(watcher);
            et_picker_search.setText(prediction.getPrimaryText(null).toString());
            et_picker_search.addTextChangedListener(watcher);

            lv_picker_autocomplete.setVisibility(View.GONE);
            pickerPredictions.clear();
            adapter.notifyDataSetChanged();
            hidePickerKeyboard();

            pickerPlacesClient.fetchPlace(FetchPlaceRequest.newInstance(
                            prediction.getPlaceId(),
                            Arrays.asList(Place.Field.LAT_LNG, Place.Field.NAME)))
                    .addOnSuccessListener(response -> {
                        Place place = response.getPlace();
                        if (place.getLatLng() != null && mMap != null) {
                            LatLng latLng = new LatLng(
                                    place.getLatLng().latitude,
                                    place.getLatLng().longitude);
                            pickedLatLng = latLng;
                            if (currentMarker != null) currentMarker.setPosition(latLng);
                            mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 16f));
                            reverseGeocode(latLng);
                        }
                    });
        });

        btn_picker_clear.setOnClickListener(v -> {
            et_picker_search.removeTextChangedListener(watcher);
            et_picker_search.setText("");
            et_picker_search.addTextChangedListener(watcher);
            lv_picker_autocomplete.setVisibility(View.GONE);
            pickerPredictions.clear();
            adapter.notifyDataSetChanged();
            btn_picker_clear.setVisibility(View.GONE);
            hidePickerKeyboard();
        });
    }

    private void hidePickerKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null && et_picker_search != null)
            imm.hideSoftInputFromWindow(et_picker_search.getWindowToken(), 0);
    }

    //add to collections
    private void showCollectionPicker() {
        App myApp = (App) getApplicationContext();
        List<Collection> collections = myApp.getCollections();

        if (collections.isEmpty()) {
            android.widget.Toast.makeText(this,
                    "No collections yet. Create one in Saved Waypoints first.",
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }

        // Build display names with icons
        String[] options = new String[collections.size() + 1];
        options[0] = "❌ None";
        for (int i = 0; i < collections.size(); i++) {
            options[i + 1] = collections.get(i).icon + " " + collections.get(i).name;
        }

        new android.app.AlertDialog.Builder(this)
                .setTitle("Add to Collection")
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        // None selected
                        selectedCollectionName = null;
                        tv_selected_collection.setText("Tap to choose a collection...");
                        tv_selected_collection.setTextColor(
                                getResources().getColor(R.color.text_light, getTheme()));
                    } else {
                        Collection chosen = collections.get(which - 1);
                        selectedCollectionName = chosen.name;
                        tv_selected_collection.setText(chosen.icon + " " + chosen.name);
                        tv_selected_collection.setTextColor(
                                getResources().getColor(R.color.text_dark, getTheme()));
                    }
                })
                .show();
    }

    @Override
    public void onMapReady(@NonNull GoogleMap googleMap) {
        mMap = googleMap;
        mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(pickedLatLng, 16f));

        mainMap = googleMap;
        mapReady = true;

        // dark/light map style based on current night mode
        int nightMode = AppCompatDelegate.getDefaultNightMode();
        boolean isNight = nightMode == AppCompatDelegate.MODE_NIGHT_YES ||
                (nightMode == AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM &&
                        (getResources().getConfiguration().uiMode &
                                android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                                android.content.res.Configuration.UI_MODE_NIGHT_YES);
        if (isNight) mainMap.setMapStyle(MapStyleOptions.loadRawResourceStyle(this, R.raw.map_dark));

        currentMarker = mMap.addMarker(new MarkerOptions()
                .position(pickedLatLng)
                .draggable(true)
                .title("Picked Location"));
        reverseGeocode(pickedLatLng);

        // Tap to move pin
        mMap.setOnMapClickListener(latLng -> {
            pickedLatLng = latLng;
            if (currentMarker != null) currentMarker.setPosition(latLng);
            reverseGeocode(latLng);
        });

        // Drag pin
        mMap.setOnMarkerDragListener(new GoogleMap.OnMarkerDragListener() {
            @Override public void onMarkerDragStart(@NonNull Marker marker) {}
            @Override public void onMarkerDrag(@NonNull Marker marker) {}
            @Override
            public void onMarkerDragEnd(@NonNull Marker marker) {
                pickedLatLng = marker.getPosition();
                reverseGeocode(pickedLatLng);
            }
        });
    }

    private void reverseGeocode(LatLng latLng) {
        tv_coords.setText(String.format("%.6f, %.6f", latLng.latitude, latLng.longitude));
        try {
            Geocoder geocoder = new Geocoder(this);
            List<Address> addresses = geocoder.getFromLocation(latLng.latitude, latLng.longitude, 1);
            if (addresses != null && !addresses.isEmpty()) {
                pickedAddress = addresses.get(0).getAddressLine(0);
                tv_address.setText(pickedAddress);
            } else {
                tv_address.setText("Unknown address");
                pickedAddress = "";
            }
        } catch (Exception e) {
            tv_address.setText("Unable to resolve address");
            pickedAddress = "";
        }
    }

    private void returnResult() {
        if (pickedLatLng == null) return;

        String name  = et_name  != null ? et_name.getText().toString().trim()  : "";
        String notes = et_notes != null ? et_notes.getText().toString().trim() : "";

        // Use address as fallback name if empty
        if (name.isEmpty()) name = pickedAddress;

        Intent result = new Intent();
        result.putExtra("picked_lat",     pickedLatLng.latitude);
        result.putExtra("picked_lng",     pickedLatLng.longitude);
        result.putExtra("picked_address", pickedAddress);
        result.putExtra("picked_name",    name);
        result.putExtra("picked_notes",   notes);
        setResult(RESULT_OK, result);
        finish();
    }
}