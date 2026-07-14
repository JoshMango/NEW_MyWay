// Compose port of MainActivity's bottom info card (live GPS stats + Save/Pin/Share).
// Hosted in a ComposeView; the activity pushes live values into StatsState.
package com.usc.myway

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.WifiTethering
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Teal = Color(0xFF00C99D)
private val TealDeep = Color(0xFF00A77D)

/** Live GPS/location values the activity writes to; the card reads them reactively. */
class StatsState {
    var lat by mutableStateOf("--")
    var lon by mutableStateOf("--")
    var altitude by mutableStateOf("N/A")
    var accuracy by mutableStateOf("--")
    var speed by mutableStateOf("0km/h")
    var address by mutableStateOf("Waiting for location…")
    var pinMode by mutableStateOf(false)   // Pin vs Cancel label
    var tracking by mutableStateOf(true)   // false hides the live GPS stat tiles
    var sharingLive by mutableStateOf(false) // Share button reflects an active live-location share
}

interface StatsActions {
    fun onPin()
    fun onShare()
}

@Composable
internal fun BottomCard(state: StatsState, actions: StatsActions) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 12.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            // Address
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    Icons.Outlined.Place,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp).padding(end = 4.dp),
                    tint = TealDeep
                )
                Column(Modifier.weight(1f)) {
                    Text("CURRENT LOCATION", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = TealDeep)
                    Text(state.address, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 2,
                        overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface)
                }
            }
            // Actions
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val tonal = ButtonDefaults.filledTonalButtonColors(containerColor = Teal.copy(alpha = 0.12f), contentColor = TealDeep)
                FilledTonalButton(
                    onClick = actions::onPin, shape = RoundedCornerShape(12.dp), colors = tonal,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        if (state.pinMode) Icons.Outlined.Cancel else Icons.Outlined.PushPin,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp).padding(end = 4.dp)
                    )
                    Text(if (state.pinMode) "Cancel" else "Pin", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
                val shareColors = if (state.sharingLive)
                    ButtonDefaults.filledTonalButtonColors(containerColor = Color(0xFFEF4444).copy(alpha = 0.14f), contentColor = Color(0xFFEF4444))
                else tonal
                FilledTonalButton(
                    onClick = actions::onShare, shape = RoundedCornerShape(12.dp), colors = shareColors,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        if (state.sharingLive) Icons.Outlined.WifiTethering else Icons.Outlined.Share,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp).padding(end = 4.dp)
                    )
                    Text(if (state.sharingLive) "Live" else "Share", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }

            // Stats — live GPS values, hidden when tracking is off.
            if (state.tracking) {
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatTile("LAT", state.lat, Modifier.weight(1f))
                    StatTile("LNG", state.lon, Modifier.weight(1f))
                    StatTile("ALT", state.altitude, Modifier.weight(1f))
                    StatTile("ACC", state.accuracy, Modifier.weight(1f))
                    StatTile("SPD", state.speed, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(10.dp))
            .background(Teal.copy(alpha = 0.10f)).padding(vertical = 8.dp, horizontal = 6.dp)
    ) {
        Text(label, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = TealDeep)
        Text(value, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurface)
    }
}
