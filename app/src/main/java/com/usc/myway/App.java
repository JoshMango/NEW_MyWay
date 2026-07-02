// local database of the app (retrieve/save/store/CRUD)
package com.usc.myway;

import android.app.Application;
import android.content.SharedPreferences;
import android.location.Location;

import androidx.appcompat.app.AppCompatDelegate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class App extends Application {
    private static final String PREFS_NAME          = "gps_tracker_prefs";
    private static final String KEY_LOC_COUNT       = "location_count";
    private static final String KEY_LOC_LAT         = "location_lat_";
    private static final String KEY_LOC_LNG         = "location_lng_";
    private static final String KEY_NOTE_PREFIX     = "note_";
    private static final String KEY_COLLECTION_COUNT = "collection_count";
    private static final String KEY_COLLECTION_NAME  = "collection_name_";
    private static final String KEY_COLLECTION_ICON  = "collection_icon_";
    private static final String KEY_COLLECTION_KEYS  = "collection_keys_";
    private Map<String, String> locationNames = new HashMap<>();
    private static final String KEY_NAME_PREFIX = "name_";

    private List<Location> myLocations          = new ArrayList<>();
    private Map<String, String> locationNotes   = new HashMap<>();
    private List<Collection> collections        = new ArrayList<>();

    @Override
    public void onCreate() {
        super.onCreate();
        loadFromPrefs();
        // dark/light mode
        if (isDarkMode()) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
        } else {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
        }
    }


    private static final String KEY_DARK_MODE = "dark_mode";

    public boolean isDarkMode() {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getBoolean(KEY_DARK_MODE, false);
    }
    public void setDarkMode(boolean enabled) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_DARK_MODE, enabled)
                .apply();
    }
    // ── Locations ────────────────────────────────────────────────────────────

    public List<Location> getMyLocations() { return myLocations; }

    public void saveLocation(Location loc) {
        myLocations.add(loc);
        saveLocationsToPrefs();
    }

    public void removeLocation(Location loc) {
        // Build key BEFORE removing, using full double precision
        String key = locationKey(loc.getLatitude(), loc.getLongitude());

        myLocations.remove(loc);
        saveLocationsToPrefs();

        // Remove from all collections
        for (Collection c : collections) {
            c.locationKeys.remove(key);
        }
        saveCollectionsToPrefs();

        // Remove note — key must match exactly
        removeNote(key);
        removeLocationName(key);
    }

    // ── Notes ────────────────────────────────────────────────────────────────

    public Map<String, String> getLocationNotes() { return locationNotes; }

    public void saveNote(String key, String note) {
        locationNotes.put(key, note);
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putString(KEY_NOTE_PREFIX + key, note)
                .apply();
    }

    public void removeNote(String key) {
        locationNotes.remove(key);
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .remove(KEY_NOTE_PREFIX + key)
                .apply();
    }

    // ── Key helper — must be consistent everywhere ────────────────────────────
    public static String locationKey(double lat, double lng) {
        return String.format("%.6f,%.6f", lat, lng);
    }

    // ── Persistence ──────────────────────────────────────────────────────────

    private void saveLocationsToPrefs() {
        SharedPreferences.Editor editor = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit();
        editor.putInt(KEY_LOC_COUNT, myLocations.size());
        for (int i = 0; i < myLocations.size(); i++) {
            // Save as String to preserve full double precision — fixes key mismatch bug
            editor.putString(KEY_LOC_LAT + i, String.valueOf(myLocations.get(i).getLatitude()));
            editor.putString(KEY_LOC_LNG + i, String.valueOf(myLocations.get(i).getLongitude()));
        }
        editor.apply();
    }

    private void loadFromPrefs() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        Map<String, ?> allPrefs = prefs.getAll();

        int count = prefs.getInt(KEY_LOC_COUNT, 0);
        myLocations.clear();
        for (int i = 0; i < count; i++) {
            double lat, lng;
            Object latObj = allPrefs.get(KEY_LOC_LAT + i);
            Object lngObj = allPrefs.get(KEY_LOC_LNG + i);

            if (latObj instanceof String) {
                lat = Double.parseDouble((String) latObj);
                lng = Double.parseDouble((String) lngObj);
            } else {
                lat = prefs.getFloat(KEY_LOC_LAT + i, 0f);
                lng = prefs.getFloat(KEY_LOC_LNG + i, 0f);
            }
            Location loc = new Location("saved");
            loc.setLatitude(lat);
            loc.setLongitude(lng);
            myLocations.add(loc);
        }

        // Load notes AND names in one loop
        locationNotes.clear();
        locationNames.clear();
        for (Map.Entry<String, ?> entry : allPrefs.entrySet()) {
            if (entry.getKey().startsWith(KEY_NOTE_PREFIX)) {
                String coordKey = entry.getKey().substring(KEY_NOTE_PREFIX.length());
                if (entry.getValue() instanceof String)
                    locationNotes.put(coordKey, (String) entry.getValue());
            } else if (entry.getKey().startsWith(KEY_NAME_PREFIX)) {
                String coordKey = entry.getKey().substring(KEY_NAME_PREFIX.length());
                if (entry.getValue() instanceof String)
                    locationNames.put(coordKey, (String) entry.getValue());
            }
        }
        loadCollectionsFromPrefs();
        // migration: re-save in new String format
        saveLocationsToPrefs();
    }

    // ── Collections ──────────────────────────────────────────────────────────

    public List<Collection> getCollections() { return collections; }

    public void saveCollection(Collection c) {
        collections.add(c);
        saveCollectionsToPrefs();
    }

    public void removeCollection(Collection c) {
        collections.remove(c);
        saveCollectionsToPrefs();
    }

    public void saveCollectionsToPrefs() {
        SharedPreferences.Editor ed = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit();
        ed.putInt(KEY_COLLECTION_COUNT, collections.size());
        for (int i = 0; i < collections.size(); i++) {
            Collection c = collections.get(i);
            ed.putString(KEY_COLLECTION_NAME + i, c.name);
            ed.putString(KEY_COLLECTION_ICON + i, c.icon);
            // Use "||" as delimiter — keys contain commas so comma delimiter breaks them
            ed.putString(KEY_COLLECTION_KEYS + i,
                    android.text.TextUtils.join("||", c.locationKeys));
        }
        ed.apply();
    }

    private void loadCollectionsFromPrefs() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        int count = prefs.getInt(KEY_COLLECTION_COUNT, 0);
        collections.clear();
        for (int i = 0; i < count; i++) {
            String name = prefs.getString(KEY_COLLECTION_NAME + i, "Collection");
            String icon = prefs.getString(KEY_COLLECTION_ICON + i, "📁");
            Collection c = new Collection(name, icon);
            String keys = prefs.getString(KEY_COLLECTION_KEYS + i, "");
            if (!keys.isEmpty()) {
                // "||" = new format, fallback regex for old comma-saved data migration
                String[] keyArray = keys.contains("||")
                        ? keys.split("\\|\\|")
                        : keys.split(",(?=\\d)");
                for (String k : keyArray) {
                    String trimmed = k.trim();
                    if (!trimmed.isEmpty()) c.locationKeys.add(trimmed);
                }
            }
            collections.add(c);
        }
    }
    public void saveLocationName(String key, String name) {
        locationNames.put(key, name);
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putString(KEY_NAME_PREFIX + key, name)
                .apply();
    }
    public String getLocationName(String key) {
        return locationNames.getOrDefault(key, "");
    }
    public void removeLocationName(String key) {
        locationNames.remove(key);
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .remove(KEY_NAME_PREFIX + key)
                .apply();
    }
}