// local database of the app (retrieve/save/store/CRUD) — SharedPreferences-backed, per CLAUDE.md.
package com.usc.myway

import android.app.Activity
import android.app.Application
import android.location.Location
import android.os.Bundle
import androidx.appcompat.app.AppCompatDelegate
import com.google.firebase.auth.FirebaseAuth

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
        Notifier.ensureChannels(this)
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private var started = 0
            override fun onActivityStarted(activity: Activity) { started++; inForeground = true }
            override fun onActivityStopped(activity: Activity) { started--; if (started <= 0) inForeground = false }
            override fun onActivityCreated(activity: Activity, s: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, out: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
        // Relaunch while already signed in → start watching for group notifications + register push token.
        FirebaseAuth.getInstance().currentUser?.uid?.let { NotificationHub.start(this, it); FcmTokens.register(it) }
    }

    private fun prefs() = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

    // ── Dark mode ──────────────────────────────────────────────────────────────
    fun isDarkMode(): Boolean = prefs().getBoolean(KEY_DARK_MODE, false)
    fun setDarkMode(enabled: Boolean) { prefs().edit().putBoolean(KEY_DARK_MODE, enabled).apply() }

    // ── Local marker-appearance settings (Settings screen) ───────────────────────
    // pinHue = BitmapDescriptorFactory hue (0=red…330=rose); pinIcon = note-card emoji; pencilIcon = collapsed-note glyph.
    fun getPinHue(): Float = prefs().getFloat(KEY_PIN_HUE, 0f)
    fun setPinHue(hue: Float) { prefs().edit().putFloat(KEY_PIN_HUE, hue).apply() }
    fun getPinIcon(): String = prefs().getString(KEY_PIN_ICON, "📝") ?: "📝"
    fun setPinIcon(icon: String) { prefs().edit().putString(KEY_PIN_ICON, icon).apply() }
    fun getPencilIcon(): String = prefs().getString(KEY_PENCIL_ICON, "✏️") ?: "✏️"
    fun setPencilIcon(icon: String) { prefs().edit().putString(KEY_PENCIL_ICON, icon).apply() }

    /** Wipe locally-saved map data: pins, notes, names, landmark placeIds, collections. Keeps settings & sign-in. */
    fun clearLocalData() {
        myLocations.clear(); locationNotes.clear(); locationNames.clear(); locationPlaceIds.clear(); collections.clear()
        val ed = prefs().edit()
        for (k in prefs().all.keys.toList()) {
            if (k.startsWith(KEY_NOTE_PREFIX) || k.startsWith(KEY_NAME_PREFIX) || k.startsWith(KEY_PLACEID_PREFIX) ||
                k.startsWith(KEY_LOC_LAT) || k.startsWith(KEY_LOC_LNG) ||
                k.startsWith(KEY_COLLECTION_NAME) || k.startsWith(KEY_COLLECTION_ICON) || k.startsWith(KEY_COLLECTION_KEYS)
            ) ed.remove(k)
        }
        ed.remove(KEY_LOC_COUNT); ed.remove(KEY_COLLECTION_COUNT); ed.apply()
    }

    // ── User @tag cache (keyed by uid) — lets sign-in skip onboarding + a Firestore read. ──
    fun getUserTag(uid: String): String = prefs().getString(KEY_USER_TAG + uid, "") ?: ""
    fun setUserTag(uid: String, tag: String) { prefs().edit().putString(KEY_USER_TAG + uid, tag).apply() }

    // ── User avatar cache (base64) — used as the live-trip map icon without a Firestore read. ──
    fun getUserPhoto(uid: String): String = prefs().getString(KEY_USER_PHOTO + uid, "") ?: ""
    fun setUserPhoto(uid: String, photo: String) { prefs().edit().putString(KEY_USER_PHOTO + uid, photo).apply() }

    // ── Last known location (in-memory) — MainActivity keeps this fresh so other screens (e.g. joining
    //    a trip from the group chat) can seed a location without their own GPS fix. ──
    @Volatile var lastLat: Double = 0.0
    @Volatile var lastLng: Double = 0.0

    // Is any activity currently in the foreground? FCM uses this to skip pushing when NotificationHub
    // (the live foreground listener) already handles the alert.
    @Volatile var inForeground = false

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
        private const val KEY_PIN_HUE = "pin_hue"
        private const val KEY_PIN_ICON = "pin_icon"
        private const val KEY_PENCIL_ICON = "pencil_icon"
        private const val KEY_USER_TAG = "usertag_"
        private const val KEY_USER_PHOTO = "userphoto_"

        // Key helper — must be consistent everywhere.
        @JvmStatic
        fun locationKey(lat: Double, lng: Double): String = String.format("%.6f,%.6f", lat, lng)
    }
}
