// Routes API (New) client — computes routes (with alternatives) between two points and returns
// each route's polyline, distance, ETA, and turn-by-turn steps (with per-step end coords for live
// progress tracking). Plain HttpURLConnection + org.json (no extra deps).
// Requires "Routes API" enabled in the Cloud project and an unrestricted (or web-service) key.
package com.usc.myway

import com.google.android.gms.maps.model.LatLng
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

enum class TravelMode(val api: String, val label: String, val emoji: String) {
    DRIVE("DRIVE", "Drive", "🚗"),
    WALK("WALK", "Walk", "🚶"),
    BICYCLE("BICYCLE", "Bike", "🚲"),
    TRANSIT("TRANSIT", "Transit", "🚌"),
}

data class RouteStep(
    val instruction: String,
    val maneuver: String,
    val distanceMeters: Int,
    val endLat: Double,
    val endLng: Double,
)

data class RouteResult(
    val points: List<LatLng>,
    val distanceMeters: Int,
    val durationSeconds: Int,
    val steps: List<RouteStep>,
)

/** Returns all returned routes (route 0 is the recommended one); empty on any failure. */
suspend fun fetchRoute(origin: LatLng, dest: LatLng, mode: TravelMode, apiKey: String): List<RouteResult> =
    withContext(Dispatchers.IO) {
        try {
            val conn = (URL("https://routes.googleapis.com/directions/v2:computeRoutes").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 15000; readTimeout = 15000
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("X-Goog-Api-Key", apiKey)
                setRequestProperty(
                    "X-Goog-FieldMask",
                    "routes.duration,routes.distanceMeters,routes.polyline.encodedPolyline," +
                        "routes.legs.steps.navigationInstruction,routes.legs.steps.distanceMeters," +
                        "routes.legs.steps.endLocation,routes.legs.steps.transitDetails",
                )
            }
            val body = JSONObject().apply {
                put("origin", pointJson(origin))
                put("destination", pointJson(dest))
                put("travelMode", mode.api)
                if (mode == TravelMode.DRIVE) put("routingPreference", "TRAFFIC_AWARE")
                // Transit doesn't support alternative routes the same way; keep it single.
                if (mode != TravelMode.TRANSIT) put("computeAlternativeRoutes", true)
                put("languageCode", "en-US")
                put("units", "METRIC")
            }.toString()
            conn.outputStream.use { it.write(body.toByteArray()) }
            if (conn.responseCode !in 200..299) return@withContext emptyList()
            parseRoutes(conn.inputStream.bufferedReader().use { it.readText() })
        } catch (_: Exception) { emptyList() }
    }

private fun pointJson(ll: LatLng) = JSONObject().put(
    "location", JSONObject().put("latLng", JSONObject().put("latitude", ll.latitude).put("longitude", ll.longitude)),
)

private fun parseRoutes(json: String): List<RouteResult> {
    val routes = JSONObject(json).optJSONArray("routes") ?: return emptyList()
    val out = ArrayList<RouteResult>()
    for (r in 0 until routes.length()) {
        val route = routes.getJSONObject(r)
        val encoded = route.optJSONObject("polyline")?.optString("encodedPolyline")?.takeIf { it.isNotEmpty() } ?: continue
        val steps = ArrayList<RouteStep>()
        route.optJSONArray("legs")?.let { legs ->
            for (i in 0 until legs.length()) {
                legs.getJSONObject(i).optJSONArray("steps")?.let { s ->
                    for (j in 0 until s.length()) steps += parseStep(s.getJSONObject(j))
                }
            }
        }
        out += RouteResult(
            points = decodePolyline(encoded),
            distanceMeters = route.optInt("distanceMeters"),
            durationSeconds = route.optString("duration").removeSuffix("s").toIntOrNull() ?: 0,
            steps = steps.filter { it.instruction.isNotEmpty() },
        )
    }
    return out
}

private fun parseStep(step: JSONObject): RouteStep {
    val nav = step.optJSONObject("navigationInstruction")
    var instr = nav?.optString("instructions").orEmpty()
    // Transit steps carry no navigationInstruction — synthesize one from the line details.
    if (instr.isEmpty()) {
        step.optJSONObject("transitDetails")?.let { t ->
            val line = t.optJSONObject("transitLine")?.let { it.optString("nameShort").ifEmpty { it.optString("name") } }.orEmpty()
            val headsign = t.optString("headsign")
            if (line.isNotEmpty()) instr = "Take $line" + if (headsign.isNotEmpty()) " toward $headsign" else ""
        }
    }
    val end = step.optJSONObject("endLocation")?.optJSONObject("latLng")
    return RouteStep(
        instruction = instr,
        maneuver = if (step.has("transitDetails")) "TRANSIT" else nav?.optString("maneuver").orEmpty(),
        distanceMeters = step.optInt("distanceMeters"),
        endLat = end?.optDouble("latitude") ?: 0.0,
        endLng = end?.optDouble("longitude") ?: 0.0,
    )
}

/** Shortest distance (metres) from a point to a polyline — used for off-route detection. */
fun distanceToPathMeters(p: LatLng, path: List<LatLng>): Float {
    if (path.isEmpty()) return Float.MAX_VALUE
    if (path.size == 1) return segDist(p, path[0], path[0])
    var min = Float.MAX_VALUE
    for (i in 0 until path.size - 1) {
        val d = segDist(p, path[i], path[i + 1])
        if (d < min) min = d
    }
    return min
}

/** Closest distance (m) from p to segment a–b via a local equirectangular projection. */
private fun segDist(p: LatLng, a: LatLng, b: LatLng): Float {
    val mPerLat = 111_320.0
    val mPerLng = 111_320.0 * Math.cos(Math.toRadians(a.latitude))
    val px = (p.longitude - a.longitude) * mPerLng; val py = (p.latitude - a.latitude) * mPerLat
    val bx = (b.longitude - a.longitude) * mPerLng; val by = (b.latitude - a.latitude) * mPerLat
    val len2 = bx * bx + by * by
    val t = if (len2 == 0.0) 0.0 else ((px * bx + py * by) / len2).coerceIn(0.0, 1.0)
    val dx = px - t * bx; val dy = py - t * by
    return Math.sqrt(dx * dx + dy * dy).toFloat()
}

/** Standard Google encoded-polyline decoder. */
private fun decodePolyline(encoded: String): List<LatLng> {
    val poly = ArrayList<LatLng>()
    var index = 0
    var lat = 0
    var lng = 0
    while (index < encoded.length) {
        var b: Int
        var shift = 0
        var result = 0
        do { b = encoded[index++].code - 63; result = result or ((b and 0x1f) shl shift); shift += 5 } while (b >= 0x20)
        lat += if (result and 1 != 0) (result shr 1).inv() else result shr 1
        shift = 0; result = 0
        do { b = encoded[index++].code - 63; result = result or ((b and 0x1f) shl shift); shift += 5 } while (b >= 0x20)
        lng += if (result and 1 != 0) (result shr 1).inv() else result shr 1
        poly.add(LatLng(lat / 1E5, lng / 1E5))
    }
    return poly
}
