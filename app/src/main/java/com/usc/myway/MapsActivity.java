// show map layout
package com.usc.myway;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.location.Geocoder;
import android.location.Location;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.fragment.app.FragmentActivity;

import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.BitmapDescriptor;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import ph.edu.gps_tracker.databinding.ActivityMapsBinding;

public class MapsActivity extends FragmentActivity implements OnMapReadyCallback {
    private App myApp;
    private GoogleMap mMap;
    private ActivityMapsBinding binding;
    List<Location> savedLocs;

    private Map<String, String> locationNotes; // reference to App's persistent map
    private final Map<Marker, String> markerKeys = new HashMap<>(); // marker → "lat,lng" key
    private final Map<Marker, Marker> labelMarkers = new HashMap<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMapsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        SupportMapFragment mapFragment = (SupportMapFragment)
                getSupportFragmentManager().findFragmentById(R.id.map);
        mapFragment.getMapAsync(this);

        myApp = (App) getApplicationContext();
        savedLocs = myApp.getMyLocations();
        locationNotes = myApp.getLocationNotes(); // load persisted notes
    }

    @Override
    public void onMapReady(GoogleMap googleMap) {
        mMap = googleMap;

        double lat = getIntent().getDoubleExtra("latitude", -34);
        double lng = getIntent().getDoubleExtra("longitude", 151);
        LatLng myLocation = new LatLng(lat, lng);

        final boolean[] deleteTapped = {false};

        mMap.setInfoWindowAdapter(new GoogleMap.InfoWindowAdapter() {
            @Override public View getInfoWindow(Marker m) {
                if (labelMarkers.containsValue(m)) return null;
                return buildInfoWindow(m);
            }
            @Override public View getInfoContents(Marker m) { return null; }
        });

        mMap.setOnMarkerClickListener(m -> {
            if (labelMarkers.containsValue(m)) return true;
            deleteTapped[0] = false;
            m.showInfoWindow();

            // Attach touch listener to the map to detect which button area was tapped
            mMap.setOnMapClickListener(null); // clear temporarily
            return true;
        });
        mMap.setOnInfoWindowClickListener(marker -> {
            if (labelMarkers.containsValue(marker)) return;
            new AlertDialog.Builder(MapsActivity.this)
                    .setTitle(marker.getTitle())
                    .setItems(new String[]{"✏️ Add / Edit Note", "🗑️ Delete Location"}, (d, which) -> {
                        if (which == 0) {
                            showNoteDialog(marker);
                        } else {
                            new AlertDialog.Builder(MapsActivity.this)
                                    .setTitle("Delete Waypoint")
                                    .setMessage("Are you sure you want to delete this location?")
                                    .setPositiveButton("Delete", (d2, w2) -> {
                                        String key = markerKeys.getOrDefault(marker, "");
                                        for (Location loc : myApp.getMyLocations()) {
                                            if (App.locationKey(loc.getLatitude(),
                                                    loc.getLongitude()).equals(key)) {
                                                myApp.removeLocation(loc);
                                                break;
                                            }
                                        }
                                        marker.remove();
                                        Marker label = labelMarkers.remove(marker);
                                        if (label != null) label.remove();
                                        markerKeys.remove(marker);
                                        Toast.makeText(MapsActivity.this,
                                                "Location deleted.", Toast.LENGTH_SHORT).show();
                                    })
                                    .setNegativeButton("Cancel", null)
                                    .show();
                        }
                    })
                    .show();
        });

        // Current location pin
        String myTitle  = getLocationTitle(lat, lng);
        Marker myMarker = mMap.addMarker(new MarkerOptions()
                .position(myLocation)
                .title(myTitle)
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE))
                .zIndex(1f));
        String myKey = App.locationKey(lat, lng);
        markerKeys.put(myMarker, myKey);
        // Restore floating bubble if note already exists
        String existingMyNote = locationNotes.getOrDefault(myKey, "");
        if (!existingMyNote.isEmpty()) addLabelMarker(myMarker, existingMyNote);

        // Saved waypoint markers
        for (Location loc : savedLocs) {
            LatLng latLng = new LatLng(loc.getLatitude(), loc.getLongitude());
            String title  = getLocationTitle(loc.getLatitude(), loc.getLongitude());
            Marker marker = mMap.addMarker(new MarkerOptions()
                    .position(latLng)
                    .title(title)
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED))
                    .zIndex(1f));
            String key = App.locationKey(loc.getLatitude(), loc.getLongitude());
            markerKeys.put(marker, key);
            // Restore floating bubble if note already exists
            String existingNote = locationNotes.getOrDefault(key, "");
            if (!existingNote.isEmpty()) addLabelMarker(marker, existingNote);
        }
        mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(myLocation, 15));

        // Tap pin → show custom info window
        mMap.setOnMarkerClickListener(marker -> {
            if (labelMarkers.containsValue(marker)) return true; // ignore label taps
            marker.showInfoWindow();
            return true;
        });
    }

    // Builds the tappable info window shown when a pin is pressed
    private View buildInfoWindow(Marker marker) {
        View view = getLayoutInflater().inflate(R.layout.custom_infowindow, null);

        TextView tv_title    = view.findViewById(R.id.tv_info_title);
        TextView tv_snippet  = view.findViewById(R.id.tv_info_snippet);
        TextView tv_btnLabel = view.findViewById(R.id.tv_note_btn_label);

        tv_title.setText(marker.getTitle());

        String key  = markerKeys.getOrDefault(marker, "");
        String note = locationNotes.getOrDefault(key, "");
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

    // Adds a floating bitmap bubble above a pin (only when note exists)
    private void addLabelMarker(Marker pinMarker, String note) {
        if (note == null || note.isEmpty()) return;

        LatLng pos      = pinMarker.getPosition();
        LatLng labelPos = new LatLng(pos.latitude + 0.00018, pos.longitude);

        Marker label = mMap.addMarker(new MarkerOptions()
                .position(labelPos)
                .icon(buildLabelBitmap(this, pinMarker.getTitle(), note))
                .flat(true)
                .anchor(0.5f, 1.0f)
                .zIndex(2f));

        labelMarkers.put(pinMarker, label);
    }

    private void refreshLabel(Marker pinMarker) {
        Marker oldLabel = labelMarkers.remove(pinMarker);
        if (oldLabel != null) oldLabel.remove();

        String key  = markerKeys.getOrDefault(pinMarker, "");
        String note = locationNotes.getOrDefault(key, "");
        if (note != null && !note.isEmpty()) {
            addLabelMarker(pinMarker, note);
        }
    }

    private static BitmapDescriptor buildLabelBitmap(Context ctx, String title, String note) {
        float density   = ctx.getResources().getDisplayMetrics().density;
        float padding   = 10 * density;
        float titleSize = 11 * density;
        float noteSize  = 10 * density;
        float radius    = 8  * density;
        float tailH     = 8  * density;

        Paint titlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        titlePaint.setTextSize(titleSize);
        titlePaint.setTypeface(Typeface.DEFAULT_BOLD);
        titlePaint.setColor(Color.parseColor("#1E293B"));

        Paint notePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        notePaint.setTextSize(noteSize);
        notePaint.setColor(Color.parseColor("#3B82F6"));

        String displayTitle = title.length() > 28 ? title.substring(0, 25) + "..." : title;
        String displayNote  = note.length()  > 32 ? note.substring(0,  29) + "..." : note;
        String noteText     = "📝 " + displayNote;

        float titleWidth = titlePaint.measureText(displayTitle);
        float noteWidth  = notePaint.measureText(noteText);
        float contentW   = Math.max(titleWidth, noteWidth);
        float contentH   = titleSize + noteSize + 4 * density;

        float bmpW = contentW + padding * 2;
        float bmpH = contentH + padding * 2 + tailH;

        Bitmap bmp    = Bitmap.createBitmap((int) bmpW, (int) bmpH, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);

        // White bubble
        Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bgPaint.setColor(Color.WHITE);
        RectF bubbleRect = new RectF(0, 0, bmpW, bmpH - tailH);
        canvas.drawRoundRect(bubbleRect, radius, radius, bgPaint);

        // Tail
        Paint tailPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        tailPaint.setColor(Color.WHITE);
        float cx = bmpW / 2f;
        Path tail = new Path();
        tail.moveTo(cx - 6 * density, bmpH - tailH);
        tail.lineTo(cx + 6 * density, bmpH - tailH);
        tail.lineTo(cx, bmpH);
        tail.close();
        canvas.drawPath(tail, tailPaint);

        // Border
        Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        borderPaint.setColor(Color.parseColor("#E2E8F0"));
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(1.5f * density);
        canvas.drawRoundRect(bubbleRect, radius, radius, borderPaint);

        // Title
        canvas.drawText(displayTitle, padding, padding + titleSize, titlePaint);
        // Note
        canvas.drawText(noteText, padding, padding + titleSize + 4 * density + noteSize, notePaint);

        return BitmapDescriptorFactory.fromBitmap(bmp);
    }

    private void showNoteDialog(Marker marker) {
        String key          = markerKeys.getOrDefault(marker, "");
        String existingNote = locationNotes.getOrDefault(key, "");

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("📝 " + (existingNote.isEmpty() ? "Add Note" : "Edit Note"));
        builder.setMessage("Enter a label or description for this location:");

        EditText input = new EditText(this);
        input.setHint("e.g. Coffee shop, Meeting point...");
        input.setText(existingNote);
        input.setSingleLine(false);
        input.setMaxLines(3);

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        container.setPadding(pad, pad, pad, 0);
        container.addView(input);
        builder.setView(container);

        builder.setPositiveButton("Save Note", (dialog, which) -> {
            String note = input.getText().toString().trim();
            myApp.saveNote(key, note);   // save to App's persistent map
            refreshLabel(marker);
            marker.showInfoWindow();
            Toast.makeText(this, "Note saved!", Toast.LENGTH_SHORT).show();
        });

        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.dismiss());

        builder.setNeutralButton("Clear Note", (dialog, which) -> {
            myApp.removeNote(key);      // remove from App's persistent map
            refreshLabel(marker);
            marker.showInfoWindow();
        });
        builder.show();
    }

    private String getLocationTitle(double lat, double lng) {
        Geocoder geocoder = new Geocoder(this);
        try {
            List<android.location.Address> addresses = geocoder.getFromLocation(lat, lng, 1);
            if (addresses != null && !addresses.isEmpty()) {
                android.location.Address address = addresses.get(0);
                String name   = address.getFeatureName();
                String street = address.getThoroughfare();
                String suburb = address.getSubLocality();
                String city   = address.getLocality();

                if (name != null && !name.matches("\\d+.*"))  return name;
                else if (street != null) return name != null ? name + ", " + street : street;
                else if (suburb != null) return suburb;
                else if (city   != null) return city;
            }
        } catch (Exception e) { /* fall through */ }
        return String.format("%.5f, %.5f", lat, lng);
    }
}