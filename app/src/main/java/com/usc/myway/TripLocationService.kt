// Foreground service that publishes my live location + a heartbeat to my trip participant doc,
// so location sharing keeps working when the app is backgrounded or the map isn't on screen.
// It owns the trip's location publishing (MainActivity no longer publishes). It watches my own
// participant doc and stops itself the moment I leave / am kicked / the group is deleted.
package com.usc.myway

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.firebase.firestore.ListenerRegistration

class TripLocationService : Service() {

    private lateinit var fused: FusedLocationProviderClient
    private lateinit var callback: LocationCallback
    private var uid = ""
    private var currentGid: String? = null
    private var lastWrite = 0L
    private var myTripReg: ListenerRegistration? = null

    private val handler = Handler(Looper.getMainLooper())
    // Heartbeat: re-write updatedAt/expireAt even when stationary, so we don't look stale.
    private val heartbeat = object : Runnable {
        override fun run() {
            val gid = currentGid
            val a = application as App
            if (gid != null && uid.isNotEmpty()) Trip.updateLocation(uid, a.lastLat, a.lastLng)
            handler.postDelayed(this, HEARTBEAT_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        fused = LocationServices.getFusedLocationProviderClient(this)
        callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                onFix(loc)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        uid = intent?.getStringExtra(EXTRA_UID) ?: uid
        val groupName = intent?.getStringExtra(EXTRA_NAME) ?: "your group"
        startForegroundCompat(groupName)
        if (uid.isEmpty()) { stopSelf(); return START_NOT_STICKY }

        // Single source of truth: stop as soon as my participation ends (from anywhere).
        myTripReg?.remove()
        myTripReg = Trip.listenMyTrip(uid) { gid ->
            currentGid = gid
            if (gid == null) stopSelf()
        }

        requestUpdates()
        handler.removeCallbacks(heartbeat)
        handler.postDelayed(heartbeat, HEARTBEAT_MS)
        // Redeliver the last intent (with uid) if the system restarts us, so publishing resumes.
        return START_REDELIVER_INTENT
    }

    private fun requestUpdates() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            stopSelf(); return
        }
        fused.removeLocationUpdates(callback) // idempotent re-start
        val req = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 10_000L)
            .setMinUpdateIntervalMillis(5_000L).build()
        fused.requestLocationUpdates(req, callback, Looper.getMainLooper())
    }

    private fun onFix(loc: Location) {
        val a = application as App
        a.lastLat = loc.latitude; a.lastLng = loc.longitude
        val gid = currentGid ?: return
        if (gid.isEmpty()) return
        val now = System.currentTimeMillis()
        if (now - lastWrite < 8_000L) return
        lastWrite = now
        Trip.updateLocation(uid, loc.latitude, loc.longitude)
    }

    private fun startForegroundCompat(groupName: String) {
        createChannel()
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notif: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Sharing your live location")
            .setContentText("With $groupName · tap to open MyWay")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = getSystemService(NotificationManager::class.java)
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Live trip", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "Shown while you're sharing your location on a trip." }
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(heartbeat)
        fused.removeLocationUpdates(callback)
        myTripReg?.remove()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "trip_live"
        private const val NOTIF_ID = 42
        private const val HEARTBEAT_MS = 20_000L
        private const val EXTRA_UID = "uid"
        private const val EXTRA_NAME = "name"

        fun start(ctx: Context, uid: String, groupName: String) {
            val i = Intent(ctx, TripLocationService::class.java)
                .putExtra(EXTRA_UID, uid).putExtra(EXTRA_NAME, groupName)
            androidx.core.content.ContextCompat.startForegroundService(ctx, i)
        }
    }
}
