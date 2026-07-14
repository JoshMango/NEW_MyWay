// Personal map data lives in Firestore (see Places.kt); this holds the in-memory mirror the map and
// list screens read, kept current by snapshot listeners. SharedPreferences now only stores device
// settings (dark mode, marker appearance) and small caches (@tag, avatar).
package com.usc.myway

import android.app.Activity
import android.app.Application
import android.location.Location
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.ListenerRegistration

class App : Application() {

    val myLocations: MutableList<Location> = ArrayList()
    val locationNotes: MutableMap<String, String> = HashMap()
    val collections: MutableList<Collection> = ArrayList()
    private val locationNames: MutableMap<String, String> = HashMap()
    // Google placeId for saved landmarks (POIs). Presence of a key here == "this is a landmark".
    private val locationPlaceIds: MutableMap<String, String> = HashMap()

    /** Bumped on every snapshot. Compose screens read it to repaint when the data changes. */
    var dataVersion by mutableIntStateOf(0)
        private set

    private var uid = ""
    private var placesReg: ListenerRegistration? = null
    private var collsReg: ListenerRegistration? = null

    override fun onCreate() {
        super.onCreate()
        loadFromPrefs() // legacy data, if any — uploaded and cleared on the next bindUser()
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
        // Relaunch while already signed in → load my places + watch for group notifications + register push token.
        FirebaseAuth.getInstance().currentUser?.uid?.let { bindUser(it); NotificationHub.start(this, it); FcmTokens.register(it) }

        getSystemService(ConnectivityManager::class.java).registerNetworkCallback(
            NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
            object : ConnectivityManager.NetworkCallback() {
                // Track a count, not a flag — WiFi + cellular can both be up, and one dropping shouldn't flip us offline.
                override fun onAvailable(network: Network) { onlineNetworkCount++; isOnline = true }
                override fun onLost(network: Network) { onlineNetworkCount--; isOnline = onlineNetworkCount > 0 }
            },
        )
    }

    private fun prefs() = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

    // ── Dark mode ──────────────────────────────────────────────────────────────
    fun isDarkMode(): Boolean = prefs().getBoolean(KEY_DARK_MODE, false)
    fun setDarkMode(enabled: Boolean) { prefs().edit().putBoolean(KEY_DARK_MODE, enabled).apply() }

    // ── Local marker-appearance settings (Settings screen) ───────────────────────
    // pinHue = BitmapDescriptorFactory hue (0=red…330=rose); pinIcon = note-card emoji; pencilIcon = collapsed-note glyph.
    fun getPinHue(): Float = prefs().getFloat(KEY_PIN_HUE, 0f)
    fun setPinHue(hue: Float) { prefs().edit().putFloat(KEY_PIN_HUE, hue).apply() }
    fun getPinIcon(): String = prefs().getString(KEY_PIN_ICON, "✎:") ?: "✎:"
    fun setPinIcon(icon: String) { prefs().edit().putString(KEY_PIN_ICON, icon).apply() }
    fun getPencilIcon(): String = prefs().getString(KEY_PENCIL_ICON, "✏️") ?: "✏️"
    fun setPencilIcon(icon: String) { prefs().edit().putString(KEY_PENCIL_ICON, icon).apply() }

    // ── User @tag cache (keyed by uid) — lets sign-in skip onboarding + a Firestore read. ──
    fun getUserTag(uid: String): String = prefs().getString(KEY_USER_TAG + uid, "") ?: ""
    fun setUserTag(uid: String, tag: String) { prefs().edit().putString(KEY_USER_TAG + uid, tag).apply() }

    // ── User avatar + banner cache (base64) — lets the map icon and drawer header paint without a
    //    Firestore read, and makes an upload show up everywhere the moment it succeeds. ──
    fun getUserPhoto(uid: String): String = prefs().getString(KEY_USER_PHOTO + uid, "") ?: ""
    fun setUserPhoto(uid: String, photo: String) { prefs().edit().putString(KEY_USER_PHOTO + uid, photo).apply() }
    fun getUserBanner(uid: String): String = prefs().getString(KEY_USER_BANNER + uid, "") ?: ""
    fun setUserBanner(uid: String, banner: String) { prefs().edit().putString(KEY_USER_BANNER + uid, banner).apply() }

    // ── Last known location (in-memory) — MainActivity keeps this fresh so other screens (e.g. joining
    //    a trip from the group chat) can seed a location without their own GPS fix. ──
    @Volatile var lastLat: Double = 0.0
    @Volatile var lastLng: Double = 0.0

    // Is any activity currently in the foreground? FCM uses this to skip pushing when NotificationHub
    // (the live foreground listener) already handles the alert.
    @Volatile var inForeground = false

    // True while the device has internet connectivity. Screens read this to show a "reconnecting" banner.
    var isOnline by mutableStateOf(true)
        private set
    private var onlineNetworkCount = 0

    /* ── Firestore binding ─────────────────────────────────────────────────── */

    /** Attach the live listeners for [uid]'s places + collections. Idempotent. */
    fun bindUser(uid: String) {
        if (uid.isEmpty() || uid == this.uid) return
        unbindUser()
        this.uid = uid
        migrateLegacyPrefs(uid)
        placesReg = Places.listenPlaces(uid) { docs ->
            myLocations.clear(); locationNotes.clear(); locationNames.clear(); locationPlaceIds.clear()
            for (d in docs) {
                myLocations.add(Location("saved").apply { latitude = d.lat; longitude = d.lng })
                if (d.name.isNotEmpty()) locationNames[d.key] = d.name
                if (d.note.isNotEmpty()) locationNotes[d.key] = d.note
                if (d.placeId.isNotEmpty()) locationPlaceIds[d.key] = d.placeId
            }
            dataVersion++
        }
        collsReg = Places.listenCollections(uid) { list ->
            collections.clear()
            for (c in list) collections.add(Collection(c.name, c.icon, c.id).apply { locationKeys.addAll(c.keys) })
            dataVersion++
        }
    }

    /** Sign-out → drop the listeners and the mirror. */
    fun unbindUser() {
        placesReg?.remove(); collsReg?.remove()
        placesReg = null; collsReg = null; uid = ""
        clearMirror()
    }

    private fun clearMirror() {
        myLocations.clear(); locationNotes.clear(); locationNames.clear(); locationPlaceIds.clear(); collections.clear()
        dataVersion++
    }

    /** Wipe my saved places, notes and collections — locally and in Firestore. Keeps settings & sign-in. */
    fun clearMyPlaces() {
        if (uid.isNotEmpty()) Places.deleteAll(uid)
        clearMirror()
    }

    // ── Locations ──────────────────────────────────────────────────────────────
    fun saveLocation(loc: Location) {
        myLocations.add(loc)
        Places.savePlace(uid, locationKey(loc.latitude, loc.longitude), loc.latitude, loc.longitude)
    }

    fun removeLocation(loc: Location) {
        val key = locationKey(loc.latitude, loc.longitude) // build key BEFORE removing
        myLocations.remove(loc)
        locationNotes.remove(key); locationNames.remove(key); locationPlaceIds.remove(key)
        Places.deletePlace(uid, key)
        for (c in collections) if (c.locationKeys.remove(key)) Places.saveCollection(uid, c)
    }

    // ── Per-place attributes (side tables keyed by locationKey) ──────────────────
    fun saveNote(key: String, note: String) = setAttr(locationNotes, key, "note", note)
    fun saveLocationName(key: String, name: String) = setAttr(locationNames, key, "name", name)
    fun saveLocationPlaceId(key: String, placeId: String) = setAttr(locationPlaceIds, key, "placeId", placeId)

    private fun setAttr(mirror: MutableMap<String, String>, key: String, field: String, value: String) {
        if (value.isEmpty()) mirror.remove(key) else mirror[key] = value
        Places.setPlaceField(uid, key, field, value)
    }

    fun getLocationName(key: String): String = locationNames[key] ?: ""
    fun getLocationPlaceId(key: String): String = locationPlaceIds[key] ?: ""
    fun isLandmark(key: String): Boolean = locationPlaceIds.containsKey(key)

    // ── Collections ──────────────────────────────────────────────────────────────
    fun saveCollection(c: Collection) { collections.add(c); Places.saveCollection(uid, c) }
    fun removeCollection(c: Collection) { collections.remove(c); Places.deleteCollection(uid, c.id) }

    /** One collection per pin: drop [pinKey] from every collection, then add it to [target] (null = none). */
    fun setPinCollection(pinKey: String, target: Collection?) {
        for (c in collections) if (c !== target && c.locationKeys.remove(pinKey)) Places.saveCollection(uid, c)
        if (target != null && !target.locationKeys.contains(pinKey)) {
            target.locationKeys.add(pinKey)
            Places.saveCollection(uid, target)
        }
    }

    /* ── Legacy SharedPreferences data → Firestore (one-time, then the keys are dropped) ───── */

    private fun migrateLegacyPrefs(uid: String) {
        if (myLocations.isEmpty() && collections.isEmpty()) return
        val docs = myLocations.map { l ->
            val k = locationKey(l.latitude, l.longitude)
            Places.Doc(k, l.latitude, l.longitude, locationNames[k] ?: "", locationNotes[k] ?: "", locationPlaceIds[k] ?: "")
        }
        Places.uploadAll(uid, docs, collections.toList())
        removeLegacyPrefKeys() // the snapshot listener re-populates the mirror from Firestore
    }

    private fun removeLegacyPrefKeys() {
        val ed = prefs().edit()
        for (k in prefs().all.keys.toList()) {
            if (k.startsWith(KEY_NOTE_PREFIX) || k.startsWith(KEY_NAME_PREFIX) || k.startsWith(KEY_PLACEID_PREFIX) ||
                k.startsWith(KEY_LOC_LAT) || k.startsWith(KEY_LOC_LNG) ||
                k.startsWith(KEY_COLLECTION_NAME) || k.startsWith(KEY_COLLECTION_ICON) || k.startsWith(KEY_COLLECTION_KEYS)
            ) ed.remove(k)
        }
        ed.remove(KEY_LOC_COUNT); ed.remove(KEY_COLLECTION_COUNT); ed.apply()
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
        private const val KEY_USER_BANNER = "userbanner_"

        // Key helper — must be consistent everywhere. Also the Firestore doc id for a place.
        @JvmStatic
        fun locationKey(lat: Double, lng: Double): String = String.format("%.6f,%.6f", lat, lng)
    }
}
