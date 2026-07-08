// RPG-style edge-of-screen arrows pointing at trip members who are currently off-screen.
// Recomputes as the camera moves; an on-screen member (or yourself) gets no arrow.
package com.usc.myway

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.CameraPositionState
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

private val Teal = Color(0xFF00C99D)

@Composable
fun TripMemberArrows(cam: CameraPositionState, members: List<Trip.Member>, myUid: String) {
    if (members.isEmpty()) return
    cam.position                       // read to recompose on every pan/zoom (projection itself isn't observable)
    val proj = cam.projection ?: return
    Canvas(Modifier.fillMaxSize()) {
        val margin = 46.dp.toPx()
        val left = margin; val top = margin
        val right = size.width - margin; val bottom = size.height - margin
        val cx = size.width / 2f; val cy = size.height / 2f
        members.forEach { mbr ->
            if (mbr.uid == myUid) return@forEach                                // never point at yourself
            val lat = mbr.lat; val lng = mbr.lng
            if (lat == null || lng == null) return@forEach                      // no fix yet
            val p = proj.toScreenLocation(LatLng(lat, lng))
            val px = p.x.toFloat(); val py = p.y.toFloat()
            if (px in 0f..size.width && py in 0f..size.height) return@forEach   // visible → no arrow
            val edge = clampToRect(cx, cy, px, py, left, top, right, bottom)
            drawArrow(edge, atan2(py - cy, px - cx))
        }
    }
}

/** Where the ray from centre → pin crosses the inset screen rectangle. */
private fun clampToRect(cx: Float, cy: Float, px: Float, py: Float,
                        left: Float, top: Float, right: Float, bottom: Float): Offset {
    val dx = px - cx; val dy = py - cy
    var t = Float.MAX_VALUE
    if (dx > 0f) t = min(t, (right - cx) / dx) else if (dx < 0f) t = min(t, (left - cx) / dx)
    if (dy > 0f) t = min(t, (bottom - cy) / dy) else if (dy < 0f) t = min(t, (top - cy) / dy)
    if (t == Float.MAX_VALUE) t = 0f
    return Offset(cx + dx * t, cy + dy * t)
}

private fun DrawScope.drawArrow(at: Offset, angle: Float) {
    val r = 13.dp.toPx()
    val path = Path().apply {
        moveTo(at.x + cos(angle) * r, at.y + sin(angle) * r)                       // tip toward the pin
        lineTo(at.x + cos(angle + 2.5f) * r * 0.75f, at.y + sin(angle + 2.5f) * r * 0.75f)
        lineTo(at.x + cos(angle - 2.5f) * r * 0.75f, at.y + sin(angle - 2.5f) * r * 0.75f)
        close()
    }
    drawCircle(Color.White, radius = r * 0.9f, center = at)
    drawPath(path, Teal)
}
