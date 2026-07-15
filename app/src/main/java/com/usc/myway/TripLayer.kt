// Renders live-trip members (as circular avatar markers) and shared trip pins on the raw GoogleMap.
// Kept separate from MapMarkerManager so personal pins and trip overlays don't clobber each other.
package com.usc.myway

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.util.Base64
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions

class TripLayer(private val context: Context) {
    private val memberMarkers = HashMap<String, Marker>()   // uid -> marker
    private val iconCache = HashMap<String, BitmapDescriptor>() // uid -> avatar icon (photo assumed stable in a session)
    private val pinMarkers = ArrayList<Marker>()
    private val pinIds = HashMap<Marker, String>()          // pin + label markers -> trip pin id (for taps)

    /** The session-pin id a tapped marker belongs to, or null if it isn't a trip pin. */
    fun pinIdFor(marker: Marker): String? = pinIds[marker]

    /** The member uid a tapped avatar marker belongs to, or null if it isn't one of ours. */
    fun memberUidFor(marker: Marker): String? = memberMarkers.entries.firstOrNull { it.value == marker }?.key

    /** Diff members onto the map: move existing markers, add new, drop the ones who left. Skips me. */
    fun renderMembers(map: GoogleMap, members: List<Trip.Member>, myUid: String) {
        val seen = HashSet<String>()
        for (m in members) {
            if (m.uid == myUid) continue                 // my own blue dot already shows me
            val lat = m.lat; val lng = m.lng
            if (lat == null || lng == null) continue      // joined but no fix published yet
            val pos = LatLng(lat, lng)
            seen += m.uid
            val existing = memberMarkers[m.uid]
            if (existing != null) {
                existing.position = pos
            } else {
                val icon = iconCache.getOrPut(m.uid) { buildAvatarMarker(context, m.photo, m.tag) }
                map.addMarker(MarkerOptions().position(pos).icon(icon).anchor(0.5f, 0.5f).zIndex(6f).title("@${m.tag}"))
                    ?.let { memberMarkers[m.uid] = it }
            }
        }
        (memberMarkers.keys - seen).forEach { uid -> memberMarkers.remove(uid)?.remove(); iconCache.remove(uid) }
    }

    /** Session pins render like personal pins (red pin + floating card), but the card always shows the
     *  creator (@tag) so you can see who dropped it, and tapping either marker opens the edit sheet. */
    fun renderPins(map: GoogleMap, pins: List<Trip.TripPin>, dark: Boolean) {
        pinMarkers.forEach { it.remove() }; pinMarkers.clear(); pinIds.clear()
        for (p in pins) {
            val pos = LatLng(p.lat, p.lng)
            val name = p.name.ifEmpty { "Shared pin" }
            map.addMarker(
                MarkerOptions().position(pos).title(name).snippet("shared by @${p.fromTag}")
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED)).zIndex(3f)
            )?.let { pinMarkers.add(it); pinIds[it] = p.id }
            // Always show a card with creator attribution (and the note, if any).
            val cardTitle = "$name  ·  @${p.fromTag}"
            val icon = buildLabelBitmap(context, cardTitle, p.note, 0f, dark)
            map.addMarker(
                MarkerOptions().position(LatLng(pos.latitude + 0.00018, pos.longitude))
                    .icon(icon).anchor(0.5f, 1.0f).zIndex(2f)
            )?.let { pinMarkers.add(it); pinIds[it] = p.id }
        }
    }

    fun clear() {
        memberMarkers.values.forEach { it.remove() }; memberMarkers.clear()
        pinMarkers.forEach { it.remove() }; pinMarkers.clear(); pinIds.clear()
        iconCache.clear()
    }

    companion object {
        private val Teal = 0xFF00C99D.toInt()

        /** A ~48dp circle: the avatar cropped to a circle with a teal ring, or a teal disc + initial. */
        private fun buildAvatarMarker(ctx: Context, photoB64: String, tag: String): BitmapDescriptor {
            val d = ctx.resources.displayMetrics.density
            val size = (48 * d).toInt()
            val ring = 3 * d
            val cx = size / 2f; val cy = size / 2f
            val inner = size / 2f - ring
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)

            // White drop-shadow disc + teal ring.
            val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG)
            ringPaint.color = Teal
            ringPaint.setShadowLayer(4 * d, 0f, 1 * d, 0x55000000)
            c.drawCircle(cx, cy, size / 2f - 2 * d, ringPaint)

            val photo = decode(photoB64)
            if (photo != null) {
                val diameter = (inner * 2).toInt().coerceAtLeast(1)
                val scaled = Bitmap.createScaledBitmap(photo, diameter, diameter, true)
                val shader = BitmapShader(scaled, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                val p = Paint(Paint.ANTI_ALIAS_FLAG); p.shader = shader
                c.save(); c.translate(cx - inner, cy - inner)
                c.drawCircle(inner, inner, inner, p)
                c.restore()
            } else {
                val fill = Paint(Paint.ANTI_ALIAS_FLAG); fill.color = Teal
                c.drawCircle(cx, cy, inner, fill)
                val tp = Paint(Paint.ANTI_ALIAS_FLAG)
                tp.color = Color.WHITE; tp.textAlign = Paint.Align.CENTER
                tp.textSize = inner; tp.typeface = Typeface.DEFAULT_BOLD
                val letter = tag.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
                val fm = tp.fontMetrics
                c.drawText(letter, cx, cy - (fm.ascent + fm.descent) / 2f, tp)
            }
            return BitmapDescriptorFactory.fromBitmap(bmp)
        }

        private fun decode(b64: String): Bitmap? =
            if (b64.isBlank()) null else try {
                val bytes = Base64.decode(b64, Base64.NO_WRAP)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } catch (_: Exception) { null }
    }
}
