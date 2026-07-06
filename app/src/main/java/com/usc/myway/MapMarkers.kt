// Marker/label rendering for the map. Pure Kotlin operating on a raw GoogleMap (obtained via
// MapEffect in Compose) — the tuned bitmap/label/zoom-collapse algorithms are ported verbatim.
package com.usc.myway

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions

class MapMarkerManager(private val context: Context) {
    val markerKeys = HashMap<Marker, String>()  // red pins -> key (normal pins only)
    val labelKeys = HashMap<Marker, String>()    // floating note labels -> key
    private val noteFullIcons = HashMap<Marker, BitmapDescriptor>() // marker -> full card icon
    private var pencilIcon: BitmapDescriptor? = null
    private var notesCollapsed: Boolean? = null
    private var dark = false

    fun refresh(map: GoogleMap, app: App, dark: Boolean) {
        if (dark != this.dark) { pencilIcon = null; this.dark = dark }
        markerKeys.keys.forEach { it.remove() }
        labelKeys.keys.forEach { it.remove() }
        markerKeys.clear(); labelKeys.clear(); noteFullIcons.clear()
        notesCollapsed = null
        for (loc in app.myLocations) {
            val key = App.locationKey(loc.latitude, loc.longitude)
            val name = app.getLocationName(key)
            val note = app.locationNotes[key] ?: ""
            val pos = LatLng(loc.latitude, loc.longitude)
            if (app.isLandmark(key)) {
                // Landmark keeps Google's native icon (no red pin); note card sits below it.
                if (note.isNotEmpty()) addLandmarkNoteLabel(map, pos, note, key)
            } else {
                val title = if (name.isEmpty())
                    String.format("%.5f, %.5f", loc.latitude, loc.longitude) else name
                val marker = map.addMarker(
                    MarkerOptions().position(pos).title(title)
                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED))
                )
                if (marker != null) {
                    markerKeys[marker] = key
                    if (note.isNotEmpty()) addPinNoteLabel(map, pos, title, note, key)
                }
            }
        }
        applyZoom(map)
    }

    fun pinForKey(key: String): Marker? = markerKeys.entries.firstOrNull { it.value == key }?.key

    private fun addPinNoteLabel(map: GoogleMap, pos: LatLng, title: String, note: String, key: String) {
        val full = buildLabelBitmap(context, title, note, 0f, dark)
        // Billboard (not flat): stays upright/readable as the map rotates, like Google's labels.
        val label = map.addMarker(
            MarkerOptions().position(LatLng(pos.latitude + 0.00018, pos.longitude))
                .icon(full).anchor(0.5f, 1.0f).zIndex(2f)
        ) ?: return
        labelKeys[label] = key
        noteFullIcons[label] = full
    }

    private fun addLandmarkNoteLabel(map: GoogleMap, pos: LatLng, note: String, key: String) {
        // Note floats slightly NORTH of the store icon; billboard so it stays upright on rotation.
        val full = buildLabelBitmap(context, "", note, 0f, dark)
        val label = map.addMarker(
            MarkerOptions().position(LatLng(pos.latitude + 0.00018, pos.longitude))
                .icon(full).anchor(0.5f, 1.0f).zIndex(2f)
        ) ?: return
        labelKeys[label] = key
        noteFullIcons[label] = full
    }

    /** Note labels collapse to a pencil below LABEL_ZOOM so the map stays clean when zoomed out. */
    fun applyZoom(map: GoogleMap) {
        if (noteFullIcons.isEmpty()) return
        val collapse = map.cameraPosition.zoom < LABEL_ZOOM
        if (notesCollapsed != null && notesCollapsed == collapse) return
        notesCollapsed = collapse
        for ((m, full) in noteFullIcons) {
            m.setIcon(if (collapse) pencil() else full) // keep each marker's own anchor
        }
    }

    private fun pencil(): BitmapDescriptor =
        pencilIcon ?: buildPencilBitmap(context, dark).also { pencilIcon = it }

    companion object {
        private const val LABEL_ZOOM = 18f

        /** Small circle with a pencil — the collapsed state of a note. */
        private fun buildPencilBitmap(ctx: Context, dark: Boolean): BitmapDescriptor {
            val d = ctx.resources.displayMetrics.density
            val size = (32 * d).toInt()
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val bg = Paint(Paint.ANTI_ALIAS_FLAG)
            bg.color = if (dark) 0xFF243244.toInt() else Color.WHITE
            bg.setShadowLayer(4 * d, 0f, 1 * d, 0x33000000.toInt())
            c.drawCircle(size / 2f, size / 2f, size / 2f - 5 * d, bg)
            val tp = Paint(Paint.ANTI_ALIAS_FLAG)
            tp.textSize = 15 * d
            tp.textAlign = Paint.Align.CENTER
            val fm = tp.fontMetrics
            c.drawText("✏️", size / 2f, size / 2f - (fm.ascent + fm.descent) / 2f, tp)
            return BitmapDescriptorFactory.fromBitmap(bmp)
        }

        /** Modern rounded note card: white pill, soft shadow, dark title, teal note. topPad reserves
         *  transparent space above the card so an anchor(0.5,0) marker sits below the map point. */
        private fun buildLabelBitmap(ctx: Context, title: String, note: String, topPad: Float, dark: Boolean): BitmapDescriptor {
            val d = ctx.resources.displayMetrics.density
            val padH = 12 * d; val padV = 9 * d; val lineGap = 4 * d; val shadow = 6 * d; val radius = 12 * d
            val hasTitle = title.isNotEmpty()
            val hasNote = note.isNotEmpty()
            val noteText = if (hasNote) "📝 $note" else ""

            val cardColor = if (dark) 0xFF243244.toInt() else Color.WHITE
            val tp = Paint(Paint.ANTI_ALIAS_FLAG)
            tp.textSize = 12 * d; tp.typeface = Typeface.DEFAULT_BOLD
            tp.color = if (dark) 0xFFF1F5F9.toInt() else 0xFF1E293B.toInt()
            val np = Paint(Paint.ANTI_ALIAS_FLAG)
            np.textSize = 11 * d; np.color = if (dark) 0xFF2DD4BF.toInt() else 0xFF00A77D.toInt() // teal

            var textW = 0f
            if (hasTitle) textW = maxOf(textW, tp.measureText(title))
            if (hasNote) textW = maxOf(textW, np.measureText(noteText))
            val tm = tp.fontMetrics; val nm = np.fontMetrics
            val titleH = if (hasTitle) tm.descent - tm.ascent else 0f
            val noteH = if (hasNote) nm.descent - nm.ascent else 0f
            val gap = if (hasTitle && hasNote) lineGap else 0f

            val cardW = textW + padH * 2
            val cardH = padV * 2 + titleH + noteH + gap
            val w = cardW + shadow * 2; val h = cardH + shadow * 2 + topPad

            val bmp = Bitmap.createBitmap(Math.ceil(w.toDouble()).toInt(), Math.ceil(h.toDouble()).toInt(), Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val bg = Paint(Paint.ANTI_ALIAS_FLAG)
            bg.color = cardColor
            bg.setShadowLayer(shadow, 0f, 2 * d, 0x40000000.toInt())
            c.drawRoundRect(RectF(shadow, shadow + topPad, w - shadow, h - shadow), radius, radius, bg)

            val x = shadow + padH
            var top = shadow + topPad + padV
            if (hasTitle) {
                c.drawText(title, x, top - tm.ascent, tp)
                top += titleH + gap
            }
            if (hasNote) c.drawText(noteText, x, top - nm.ascent, np)
            return BitmapDescriptorFactory.fromBitmap(bmp)
        }
    }
}
