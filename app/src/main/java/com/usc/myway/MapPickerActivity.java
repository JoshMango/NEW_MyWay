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
import android.widget.Toast;

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
    private Marker currentMarker;
    private LatLng pickedLatLng;
    private String pickedAddress = "";
    private String mode = "address"; 

    private TextView tv_coords, tv_address, btn_save;
    private EditText et_name, et_notes;
    private LinearLayout layout_waypoint_inputs;

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

        if ("waypoint".equals(mode)) {
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
        setupPickerSearch();
    }

    private void setupPickerSearch() {
        if (!Places.isInitialized()) {
            Places.initialize(getApplicationContext(), getString(R.string.MAPS_API_KEY));
        }
        pickerPlacesClient = Places.createClient(this);

        et_picker_search      = findViewById(R.id.et_picker_search);
        lv_picker_autocomplete = findViewById(R.id.lv_picker_autocomplete);
        btn_picker_clear      = findViewById(R.id.btn_picker_search_clear);

        ArrayAdapter<String> adapter = new ArrayAdapter<String>(
                this, R.layout.item_autocomplete, R.id.tv_place_name, new ArrayList<>()) {
            @NonNull
            @Override
            public View getView(int pos, View v, @NonNull ViewGroup p) {
                if (v == null) v = LayoutInflater.from(getContext()).inflate(R.layout.item_autocomplete, p, false);
                if (pos < pickerPredictions.size()) {
                    AutocompletePrediction pred = pickerPredictions.get(pos);
                    ((TextView) v.findViewById(R.id.tv_place_name)).setText(pred.getPrimaryText(null));
                    ((TextView) v.findViewById(R.id.tv_place_address)).setText(pred.getSecondaryText(null));
                }
                return v;
            }
            @Override public int getCount() { return pickerPredictions.size(); }
        };
        lv_picker_autocomplete.setAdapter(adapter);

        et_picker_search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                String q = s.toString().trim();
                btn_picker_clear.setVisibility(q.isEmpty() ? View.GONE : View.VISIBLE);
                if (q.length() < 2) {
                    lv_picker_autocomplete.setVisibility(View.GONE);
                    return;
                }
                pickerPlacesClient.findAutocompletePredictions(FindAutocompletePredictionsRequest.builder().setQuery(q).build())
                        .addOnSuccessListener(resp -> {
                            pickerPredictions.clear();
                            pickerPredictions.addAll(resp.getAutocompletePredictions());
                            adapter.notifyDataSetChanged();
                            lv_picker_autocomplete.setVisibility(pickerPredictions.isEmpty() ? View.GONE : View.VISIBLE);
                        });
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        lv_picker_autocomplete.setOnItemClickListener((p, v, pos, id) -> {
            AutocompletePrediction pred = pickerPredictions.get(pos);
            et_picker_search.setText(pred.getPrimaryText(null));
            lv_picker_autocomplete.setVisibility(View.GONE);
            hidePickerKeyboard();

            pickerPlacesClient.fetchPlace(FetchPlaceRequest.newInstance(pred.getPlaceId(), Arrays.asList(Place.Field.LAT_LNG)))
                    .addOnSuccessListener(resp -> {
                        LatLng ll = resp.getPlace().getLatLng();
                        if (ll != null && mMap != null) {
                            pickedLatLng = ll;
                            if (currentMarker != null) currentMarker.setPosition(ll);
                            mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(ll, 16f));
                            reverseGeocode(ll);
                        }
                    });
        });

        btn_picker_clear.setOnClickListener(v -> {
            et_picker_search.setText("");
            lv_picker_autocomplete.setVisibility(View.GONE);
            hidePickerKeyboard();
        });
    }

    private void hidePickerKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null && et_picker_search != null) imm.hideSoftInputFromWindow(et_picker_search.getWindowToken(), 0);
    }

    @Override
    public void onMapReady(@NonNull GoogleMap googleMap) {
        mMap = googleMap;
        mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(pickedLatLng, 16f));

        if (isDarkMode()) mMap.setMapStyle(MapStyleOptions.loadRawResourceStyle(this, R.raw.map_dark));

        currentMarker = mMap.addMarker(new MarkerOptions().position(pickedLatLng).draggable(true).title("Picked Location"));
        reverseGeocode(pickedLatLng);

        mMap.setOnMapClickListener(latLng -> {
            pickedLatLng = latLng;
            if (currentMarker != null) currentMarker.setPosition(latLng);
            reverseGeocode(latLng);
        });

        mMap.setOnMarkerDragListener(new GoogleMap.OnMarkerDragListener() {
            @Override public void onMarkerDragStart(@NonNull Marker marker) {}
            @Override public void onMarkerDrag(@NonNull Marker marker) {}
            @Override public void onMarkerDragEnd(@NonNull Marker marker) {
                pickedLatLng = marker.getPosition();
                reverseGeocode(pickedLatLng);
            }
        });
    }

    private void reverseGeocode(LatLng ll) {
        tv_coords.setText(String.format("%.6f, %.6f", ll.latitude, ll.longitude));
        try {
            List<Address> addresses = new Geocoder(this).getFromLocation(ll.latitude, ll.longitude, 1);
            if (addresses != null && !addresses.isEmpty()) {
                pickedAddress = addresses.get(0).getAddressLine(0);
                tv_address.setText(pickedAddress);
            }
        } catch (Exception e) {
            tv_address.setText("Unknown address");
        }
    }

    private void returnResult() {
        Intent res = new Intent();
        res.putExtra("picked_lat", pickedLatLng.latitude);
        res.putExtra("picked_lng", pickedLatLng.longitude);
        res.putExtra("picked_address", pickedAddress);
        res.putExtra("picked_name", et_name.getText().toString().trim());
        res.putExtra("picked_notes", et_notes.getText().toString().trim());
        setResult(RESULT_OK, res);
        finish();
    }

    private boolean isDarkMode() {
        int nightMode = AppCompatDelegate.getDefaultNightMode();
        if (nightMode == AppCompatDelegate.MODE_NIGHT_YES) return true;
        if (nightMode == AppCompatDelegate.MODE_NIGHT_NO) return false;
        return (getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK) 
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }
}