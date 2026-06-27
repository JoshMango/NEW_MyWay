package com.usc.myway;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Context;
import android.content.pm.PackageManager;
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

import androidx.core.app.ActivityCompat;
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

public class MapsActivity extends FragmentActivity implements OnMapReadyCallback {
    private App myApp;
    private GoogleMap mMap;
    List<Location> savedLocs;

    private Map<String, String> locationNotes; 
    private final Map<Marker, String> markerKeys = new HashMap<>(); 
    private final Map<Marker, Marker> labelMarkers = new HashMap<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_maps);

        SupportMapFragment mapFragment = (SupportMapFragment)
                getSupportFragmentManager().findFragmentById(R.id.map);
        if (mapFragment != null) mapFragment.getMapAsync(this);

        myApp = (App) getApplicationContext();
        savedLocs = myApp.getMyLocations();
        locationNotes = myApp.getLocationNotes(); 
    }

    @Override
    public void onMapReady(GoogleMap googleMap) {
        mMap = googleMap;

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            mMap.setMyLocationEnabled(true);
            mMap.getUiSettings().setMyLocationButtonEnabled(true);
        }

        double lat = getIntent().getDoubleExtra("latitude", -34);
        double lng = getIntent().getDoubleExtra("longitude", 151);
        LatLng myLocation = new LatLng(lat, lng);

        mMap.setInfoWindowAdapter(new GoogleMap.InfoWindowAdapter() {
            @Override public View getInfoWindow(Marker m) {
                if (labelMarkers.containsValue(m)) return null;
                return buildInfoWindow(m);
            }
            @Override public View getInfoContents(Marker m) { return null; }
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
        String existingMyNote = locationNotes.getOrDefault(myKey, "");
        if (!existingMyNote.isEmpty()) addLabelMarker(myMarker, existingMyNote);

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
            String existingNote = locationNotes.getOrDefault(key, "");
            if (!existingNote.isEmpty()) addLabelMarker(marker, existingNote);
        }
        mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(myLocation, 15));

        mMap.setOnMarkerClickListener(marker -> {
            if (labelMarkers.containsValue(marker)) return true;
            marker.showInfoWindow();
            return true;
        });
    }

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

    private void addLabelMarker(Marker pinMarker, String note) {
        if (note == null || note.isEmpty()) return;
        LatLng pos = pinMarker.getPosition();
        Marker label = mMap.addMarker(new MarkerOptions()
                .position(new LatLng(pos.latitude + 0.00018, pos.longitude))
                .icon(buildLabelBitmap(this, pinMarker.getTitle(), note))
                .flat(true).anchor(0.5f, 1.0f).zIndex(2f));
        labelMarkers.put(pinMarker, label);
    }

    private void refreshLabel(Marker pinMarker) {
        Marker oldLabel = labelMarkers.remove(pinMarker);
        if (oldLabel != null) oldLabel.remove();
        String key  = markerKeys.getOrDefault(pinMarker, "");
        String note = locationNotes.getOrDefault(key, "");
        if (note != null && !note.isEmpty()) addLabelMarker(pinMarker, note);
    }

    private static BitmapDescriptor buildLabelBitmap(Context ctx, String title, String note) {
        float density   = ctx.getResources().getDisplayMetrics().density;
        float padding   = 10 * density, titleSize = 11 * density, noteSize  = 10 * density;
        float radius    = 8  * density, tailH     = 8  * density;

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

        float contentW = Math.max(titlePaint.measureText(displayTitle), notePaint.measureText(noteText));
        float bmpW = contentW + padding * 2;
        float bmpH = titleSize + noteSize + 4 * density + padding * 2 + tailH;

        Bitmap bmp = Bitmap.createBitmap((int) bmpW, (int) bmpH, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        RectF bubbleRect = new RectF(0, 0, bmpW, bmpH - tailH);
        Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG); bgPaint.setColor(Color.WHITE);
        canvas.drawRoundRect(bubbleRect, radius, radius, bgPaint);

        Path tail = new Path();
        float cx = bmpW / 2f;
        tail.moveTo(cx - 6 * density, bmpH - tailH);
        tail.lineTo(cx + 6 * density, bmpH - tailH);
        tail.lineTo(cx, bmpH);
        tail.close();
        canvas.drawPath(tail, bgPaint);

        Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        borderPaint.setColor(Color.parseColor("#E2E8F0"));
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(1.5f * density);
        canvas.drawRoundRect(bubbleRect, radius, radius, borderPaint);

        canvas.drawText(displayTitle, padding, padding + titleSize, titlePaint);
        canvas.drawText(noteText, padding, padding + titleSize + 4 * density + noteSize, notePaint);

        return BitmapDescriptorFactory.fromBitmap(bmp);
    }

    private void showNoteDialog(Marker marker) {
        String key = markerKeys.getOrDefault(marker, "");
        String existingNote = locationNotes.getOrDefault(key, "");
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
                    refreshLabel(marker);
                    marker.showInfoWindow();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private String getLocationTitle(double lat, double lng) {
        try {
            List<android.location.Address> addresses = new Geocoder(this).getFromLocation(lat, lng, 1);
            if (addresses != null && !addresses.isEmpty()) {
                String name = addresses.get(0).getFeatureName();
                if (name != null && !name.matches("\\d+.*")) return name;
            }
        } catch (Exception ignored) {}
        return String.format("%.5f, %.5f", lat, lng);
    }
}