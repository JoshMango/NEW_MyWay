// local database of the app (retrieve/save/store/CRUD) — SharedPreferences-backed, per CLAUDE.md.
package com.usc.myway

import android.app.Application
import android.location.Location
import androidx.appcompat.app.AppCompatDelegate

class App : Application() {

    val myLocations: MutableList<Location> = ArrayList()
    val locationNotes: MutableMap<String, String> = HashMap()
    val collections: MutableList<Collection> = ArrayList()
    private val locationNames: MutableMap<String, String> = HashMap()
    // Google placeId for saved landmarks (POIs). Presence of a key here == "this is a landmark".
    private val locationPlaceIds: MutableMap<String, String> = HashMap()

    override fun onCreate() {
        super.onCreate()
        loadFromPrefs()
        AppCompatDelegate.setDefaultNightMode(
            if (isDarkMode()) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )
    }

    private fun prefs() = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

    // ── Dark mode ──────────────────────────────────────────────────────────────
    fun isDarkMode(): Boolean = prefs().getBoolean(KEY_DARK_MODE, false)
    fun setDarkMode(enabled: Boolean) { prefs().edit().putBoolean(KEY_DARK_MODE, enabled).apply() }

    // ── Locations ──────────────────────────────────────────────────────────────
    fun saveLocation(loc: Location) { myLocations.add(loc); saveLocationsToPrefs() }

    fun removeLocation(loc: Location) {
        val key = locationKey(loc.latitude, loc.longitude) // build key BEFORE removing
        myLocations.remove(loc)
        saveLocationsToPrefs()
        for (c in collections) c.locationKeys.remove(key)
        saveCollectionsToPrefs()
        removeNote(key); removeLocationName(key); removeLocationPlaceId(key)
    }

    // ── Notes ──────────────────────────────────────────────────────────────────
    fun saveNote(key: String, note: String) {
        locationNotes[key] = note
        prefs().edit().putString(KEY_NOTE_PREFIX + key, note).apply()
    }

    fun removeNote(key: String) {
        locationNotes.remove(key)
        prefs().edit().remove(KEY_NOTE_PREFIX + key).apply()
    }

    // ── Names ──────────────────────────────────────────────────────────────────
    fun saveLocationName(key: String, name: String) {
        locationNames[key] = name
        prefs().edit().putString(KEY_NAME_PREFIX + key, name).apply()
    }

    fun getLocationName(key: String): String = locationNames[key] ?: ""

    fun removeLocationName(key: String) {
        locationNames.remove(key)
        prefs().edit().remove(KEY_NAME_PREFIX + key).apply()
    }

    // ── Landmark placeId (marks a saved location as a Google POI) ────────────────
    fun saveLocationPlaceId(key: String, placeId: String) {
        locationPlaceIds[key] = placeId
        prefs().edit().putString(KEY_PLACEID_PREFIX + key, placeId).apply()
    }

    fun getLocationPlaceId(key: String): String = locationPlaceIds[key] ?: ""
    fun isLandmark(key: String): Boolean = locationPlaceIds.containsKey(key)

    fun removeLocationPlaceId(key: String) {
        locationPlaceIds.remove(key)
        prefs().edit().remove(KEY_PLACEID_PREFIX + key).apply()
    }

    // ── Collections ──────────────────────────────────────────────────────────────
    fun saveCollection(c: Collection) { collections.add(c); saveCollectionsToPrefs() }
    fun removeCollection(c: Collection) { collections.remove(c); saveCollectionsToPrefs() }

    fun saveCollectionsToPrefs() {
        val ed = prefs().edit()
        ed.putInt(KEY_COLLECTION_COUNT, collections.size)
        for (i in collections.indices) {
            val c = collections[i]
            ed.putString(KEY_COLLECTION_NAME + i, c.name)
            ed.putString(KEY_COLLECTION_ICON + i, c.icon)
            // "||" delimiter — keys contain commas so a comma delimiter would break them.
            ed.putString(KEY_COLLECTION_KEYS + i, c.locationKeys.joinToString("||"))
        }
        ed.apply()
    }

    // ── Persistence ──────────────────────────────────────────────────────────────
    private fun saveLocationsToPrefs() {
        val editor = prefs().edit()
        editor.putInt(KEY_LOC_COUNT, myLocations.size)
        for (i in myLocations.indices) {
            // Save as String to preserve full double precision — fixes key mismatch bug.
            editor.putString(KEY_LOC_LAT + i, myLocations[i].latitude.toString())
            editor.putString(KEY_LOC_LNG + i, myLocations[i].longitude.toString())
        }
        editor.apply()
    }

    private fun loadFromPrefs() {
        val prefs = prefs()
        val allPrefs = prefs.all
        val count = prefs.getInt(KEY_LOC_COUNT, 0)
        myLocations.clear()
        for (i in 0 until count) {
            val latObj = allPrefs[KEY_LOC_LAT + i]
            val lngObj = allPrefs[KEY_LOC_LNG + i]
            val lat: Double
            val lng: Double
            if (latObj is String) {
                lat = latObj.toDouble(); lng = (lngObj as String).toDouble()
            } else {
                lat = prefs.getFloat(KEY_LOC_LAT + i, 0f).toDouble()
                lng = prefs.getFloat(KEY_LOC_LNG + i, 0f).toDouble()
            }
            myLocations.add(Location("saved").apply { latitude = lat; longitude = lng })
        }

        // Load notes, names AND placeIds in one loop.
        locationNotes.clear(); locationNames.clear(); locationPlaceIds.clear()
        for ((k, v) in allPrefs) {
            when {
                k.startsWith(KEY_NOTE_PREFIX) -> if (v is String) locationNotes[k.substring(KEY_NOTE_PREFIX.length)] = v
                k.startsWith(KEY_PLACEID_PREFIX) -> if (v is String) locationPlaceIds[k.substring(KEY_PLACEID_PREFIX.length)] = v
                k.startsWith(KEY_NAME_PREFIX) -> if (v is String) locationNames[k.substring(KEY_NAME_PREFIX.length)] = v
            }
        }
        loadCollectionsFromPrefs()
        saveLocationsToPrefs() // migration: re-save in new String format
    }

    private fun loadCollectionsFromPrefs() {
        val prefs = prefs()
        val count = prefs.getInt(KEY_COLLECTION_COUNT, 0)
        collections.clear()
        for (i in 0 until count) {
            val name = prefs.getString(KEY_COLLECTION_NAME + i, "Collection") ?: "Collection"
            val icon = prefs.getString(KEY_COLLECTION_ICON + i, "📁") ?: "📁"
            val c = Collection(name, icon)
            val keys = prefs.getString(KEY_COLLECTION_KEYS + i, "") ?: ""
            if (keys.isNotEmpty()) {
                // "||" = new format; fallback regex migrates old comma-saved data.
                val keyArray = if (keys.contains("||")) keys.split("||") else keys.split(Regex(",(?=\\d)"))
                for (k in keyArray) {
                    val trimmed = k.trim()
                    if (trimmed.isNotEmpty()) c.locationKeys.add(trimmed)
                }
            }
            collections.add(c)
        }
    }

    companion object {
        private const val PREFS_NAME = "gps_tracker_prefs"
        private const val KEY_LOC_COUNT = "location_count"
        private const val KEY_LOC_LAT = "location_lat_"
        private const val KEY_LOC_LNG = "location_lng_"
        private const val KEY_NOTE_PREFIX = "note_"
        private const val KEY_NAME_PREFIX = "name_"
        private const val KEY_PLACEID_PREFIX = "placeid_"
        private const val KEY_COLLECTION_COUNT = "collection_count"
        private const val KEY_COLLECTION_NAME = "collection_name_"
        private const val KEY_COLLECTION_ICON = "collection_icon_"
        private const val KEY_COLLECTION_KEYS = "collection_keys_"
        private const val KEY_DARK_MODE = "dark_mode"

        // Key helper — must be consistent everywhere.
        @JvmStatic
        fun locationKey(lat: Double, lng: Double): String = String.format("%.6f,%.6f", lat, lng)
    }
}
