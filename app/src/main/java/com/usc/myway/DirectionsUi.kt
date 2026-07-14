// Directions UI: the pre-trip planning panel (mode toggle, alternate routes, ETA, steps, Start)
// and the live-navigation chrome (top maneuver banner + bottom progress footer).
package com.usc.myway

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.CallMerge
import androidx.compose.material.icons.automirrored.outlined.Shortcut
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Directions
import androidx.compose.material.icons.outlined.DirectionsBus
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Navigation
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.RadioButtonChecked
import androidx.compose.material.icons.outlined.TurnLeft
import androidx.compose.material.icons.outlined.TurnRight
import androidx.compose.material.icons.outlined.TurnSlightLeft
import androidx.compose.material.icons.outlined.TurnSlightRight
import androidx.compose.material.icons.outlined.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Teal = Color(0xFF00C99D)
private val TealDeep = Color(0xFF00A77D)
private val Red = Color(0xFFEF4444)

/* ── Pre-trip planning panel ───────────────────────────────────────────── */

@Composable
fun DirectionsPanel(
    destName: String,
    mode: TravelMode,
    loading: Boolean,
    routes: List<RouteResult>,
    selectedIndex: Int,
    isTripDirection: Boolean = false,
    isPlanStop: Boolean = false,
    onMode: (TravelMode) -> Unit,
    onSelectRoute: (Int) -> Unit,
    onStart: () -> Unit,
    onClose: () -> Unit,
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val route = routes.getOrNull(selectedIndex)
    Surface(
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 14.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(top = 14.dp).navigationBarsPadding().padding(bottom = 12.dp)) {
            // Header
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(if (isPlanStop) "PLAN · NEXT STOP" else if (isTripDirection) "TRIP DIRECTION" else "DIRECTIONS TO",
                        fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, color = TealDeep)
                    Text(destName.ifEmpty { "Destination" }, fontSize = 19.sp, fontWeight = FontWeight.Bold,
                        color = onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (isTripDirection) Text(if (isPlanStop) "Part of the trip plan" else "Shared with everyone on the trip",
                        fontSize = 12.sp, color = onSurface.copy(alpha = 0.6f))
                }
                CircleIcon(Icons.Outlined.Close, onSurface, onSurface.copy(alpha = 0.06f), onClose)
            }

            // Travel-mode toggle (scrolls — 4 modes)
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                TravelMode.entries.forEach { m -> ModeChip(m, selected = m == mode) { onMode(m) } }
            }

            when {
                loading -> Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(20.dp), color = Teal, strokeWidth = 2.dp)
                    Text("Finding the best route…", fontSize = 14.sp, color = onSurface.copy(alpha = 0.7f),
                        modifier = Modifier.padding(start = 12.dp))
                }

                route == null -> Text(
                    "No route found. Try a different travel mode.",
                    fontSize = 14.sp, color = onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                )

                else -> {
                    // Alternate routes
                    if (routes.size > 1) {
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                                .padding(horizontal = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            routes.forEachIndexed { i, r ->
                                RouteChip(r, i, selected = i == selectedIndex) { onSelectRoute(i) }
                            }
                        }
                        Spacer(Modifier.padding(top = 6.dp))
                    }

                    // Summary + Start
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(formatDuration(route.durationSeconds), fontSize = 26.sp,
                                fontWeight = FontWeight.Bold, color = TealDeep)
                            Text(formatDistance(route.distanceMeters), fontSize = 14.sp,
                                color = onSurface.copy(alpha = 0.6f))
                        }
                        Row(
                            Modifier.clip(RoundedCornerShape(50)).background(Teal)
                                .clickable(onClick = onStart).padding(horizontal = 22.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(Icons.Outlined.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                            Text("Start", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    if (route.steps.isNotEmpty()) {
                        HorizontalDivider(Modifier.padding(20.dp), color = onSurface.copy(alpha = 0.08f))
                        LazyColumn(Modifier.heightIn(max = 240.dp).padding(horizontal = 12.dp)) {
                            itemsIndexed(route.steps) { i, step ->
                                StepRow(step, last = i == route.steps.lastIndex)
                            }
                        }
                    }
                }
            }
        }
    }
}

/* ── Live navigation chrome ────────────────────────────────────────────── */

@Composable
fun NavBanner(step: RouteStep?, distanceToNext: Int, voiceOn: Boolean, onToggleVoice: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = TealDeep,
        shadowElevation = 10.dp,
        modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(48.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center,
            ) { Icon(maneuverIcon(step?.maneuver ?: ""), contentDescription = null, tint = Color.White, modifier = Modifier.size(28.dp)) }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                if (step != null) {
                    Text(
                        if (distanceToNext > 0) "In ${formatDistance(distanceToNext)}" else "Now",
                        fontSize = 12.sp, color = Color.White.copy(alpha = 0.8f), fontWeight = FontWeight.Bold,
                    )
                    Text(step.instruction, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    laneHint(step.maneuver, distanceToNext)?.let { hint ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                            Icon(Icons.Outlined.Directions, contentDescription = null, tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(14.dp))
                            Spacer(Modifier.size(4.dp))
                            Text(hint, fontSize = 12.sp, color = Color.White.copy(alpha = 0.85f))
                        }
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Arrived", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Spacer(Modifier.size(8.dp))
                        Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                }
            }
            CircleIcon(if (voiceOn) Icons.AutoMirrored.Outlined.VolumeUp else Icons.AutoMirrored.Outlined.VolumeOff, Color.White, Color.White.copy(alpha = 0.2f), onToggleVoice)
        }
    }
}

@Composable
fun NavFooter(route: RouteResult, currentStepIndex: Int, onExit: () -> Unit) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val remainingDist = route.steps.drop(currentStepIndex).sumOf { it.distanceMeters }.coerceAtLeast(0)
    val frac = if (route.distanceMeters > 0) remainingDist.toDouble() / route.distanceMeters else 0.0
    val remainingDur = (route.durationSeconds * frac).toInt()
    Surface(
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 14.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(20.dp).navigationBarsPadding(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(formatDuration(remainingDur), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = TealDeep)
                Text("${formatDistance(remainingDist)} left", fontSize = 13.sp, color = onSurface.copy(alpha = 0.6f))
            }
            Row(
                Modifier.clip(RoundedCornerShape(50)).background(Red.copy(alpha = 0.12f))
                    .clickable(onClick = onExit).padding(horizontal = 22.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) { Text("Exit", color = Red, fontSize = 15.sp, fontWeight = FontWeight.Bold) }
        }
    }
}

/* ── Pieces ────────────────────────────────────────────────────────────── */

@Composable
private fun CircleIcon(icon: ImageVector, fg: Color, bg: Color, onClick: () -> Unit) {
    Box(
        Modifier.size(38.dp).clip(CircleShape).background(bg).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(20.dp)) }
}

@Composable
private fun ModeChip(mode: TravelMode, selected: Boolean, onClick: () -> Unit) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    Row(
        Modifier.clip(RoundedCornerShape(50))
            .background(if (selected) Teal else onSurface.copy(alpha = 0.06f))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(mode.icon, contentDescription = null, tint = if (selected) Color.White else onSurface.copy(alpha = 0.7f), modifier = Modifier.size(18.dp))
        Text(mode.label, fontSize = 14.sp, fontWeight = FontWeight.Bold,
            color = if (selected) Color.White else onSurface.copy(alpha = 0.7f))
    }
}

@Composable
private fun RouteChip(route: RouteResult, index: Int, selected: Boolean, onClick: () -> Unit) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    Column(
        Modifier.clip(RoundedCornerShape(14.dp))
            .background(if (selected) Teal.copy(alpha = 0.15f) else onSurface.copy(alpha = 0.05f))
            .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(if (index == 0) "Recommended" else "Alternative", fontSize = 11.sp,
            fontWeight = FontWeight.Bold, color = if (selected) TealDeep else onSurface.copy(alpha = 0.5f))
        Text(formatDuration(route.durationSeconds), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = onSurface)
        Text(formatDistance(route.distanceMeters), fontSize = 12.sp, color = onSurface.copy(alpha = 0.6f))
    }
}

@Composable
private fun StepRow(step: RouteStep, last: Boolean) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp)) {
        Box(
            Modifier.size(34.dp).clip(CircleShape).background(Teal.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) { Icon(maneuverIcon(step.maneuver), contentDescription = null, tint = Teal, modifier = Modifier.size(18.dp)) }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(step.instruction, fontSize = 14.sp, color = onSurface, lineHeight = 19.sp)
            if (step.distanceMeters > 0 && !last) {
                Text(formatDistance(step.distanceMeters), fontSize = 12.sp,
                    color = onSurface.copy(alpha = 0.5f), modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

/** Heuristic lane readiness hint (not true lane-level data — that needs the Navigation SDK).
 *  Only shown as the maneuver approaches. */
private fun laneHint(maneuver: String, distanceToNext: Int): String? {
    if (distanceToNext !in 1..350) return null
    return when {
        maneuver.contains("U") && (maneuver.contains("LEFT") || maneuver.contains("RIGHT")) -> null
        maneuver.contains("LEFT") -> "Keep left for the turn"
        maneuver.contains("RIGHT") -> "Keep right for the turn"
        maneuver.contains("STRAIGHT") -> "Stay in your lane"
        else -> null
    }
}

private fun maneuverIcon(m: String): ImageVector = when {
    m.contains("TRANSIT") -> Icons.Outlined.DirectionsBus
    m.contains("LEFT") && m.contains("U") -> Icons.Outlined.Undo
    m.contains("RIGHT") && m.contains("U") -> Icons.Outlined.Undo 
    m.contains("LEFT") -> if (m.contains("SLIGHT")) Icons.Outlined.TurnSlightLeft else Icons.Outlined.TurnLeft
    m.contains("RIGHT") -> if (m.contains("SLIGHT")) Icons.Outlined.TurnSlightRight else Icons.Outlined.TurnRight
    m.contains("ROUNDABOUT") || m.contains("CIRCLE") -> Icons.Outlined.RadioButtonChecked
    m.contains("MERGE") -> Icons.AutoMirrored.Outlined.CallMerge
    m.contains("FORK") -> Icons.AutoMirrored.Outlined.Shortcut
    m.contains("STRAIGHT") -> Icons.Outlined.Navigation
    m.contains("DEPART") || m.contains("START") -> Icons.Outlined.Flag
    m.contains("DESTINATION") || m.contains("ARRIVE") -> Icons.Outlined.CheckCircle
    else -> Icons.Outlined.Navigation
}

private fun formatDuration(seconds: Int): String {
    if (seconds < 60) return "1 min"
    val mins = seconds / 60
    if (mins < 60) return "$mins min"
    val h = mins / 60
    val m = mins % 60
    return if (m == 0) "$h hr" else "$h hr $m min"
}

private fun formatDistance(meters: Int): String =
    if (meters < 1000) "$meters m" else String.format("%.1f km", meters / 1000.0)

/** Asked when you tap Directions during a trip: route the whole trip, or just yourself. */
@Composable
fun TripDirectionDialog(name: String, onTrip: () -> Unit, onMeOnly: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Directions to ${name.ifEmpty { "destination" }}", fontWeight = FontWeight.Bold) },
        text = { Text("Share this route with everyone on the trip, or keep it to yourself? " +
            "Setting a trip direction replaces the current one and lasts until everyone arrives or ends it.") },
        confirmButton = {
            TextButton(onClick = onTrip) { Text("Set as trip direction", color = TealDeep, fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = { onMeOnly() }) { Text("Include me only") } },
    )
}

/** Shown when another member sets a trip direction — join it or opt out. Never auto-routes you. */
@Composable
fun IncomingTripDirectionDialog(byLabel: String, byPhoto: String, destName: String, onJoin: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AvatarCircle(photo = byPhoto, fallback = byLabel, size = 36.dp)
                Text("$byLabel set a trip direction", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        },
        text = { Text("Head to ${destName.ifEmpty { "the destination" }} with the trip?") },
        confirmButton = { TextButton(onClick = onJoin) { Text("Join", color = TealDeep, fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton(onClick = { onDismiss() }) { Text("Not now") } },
    )
}
