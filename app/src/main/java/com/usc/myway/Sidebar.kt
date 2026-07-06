// Compose port of MainActivity's left drawer. Hosted in a ComposeView inside the (still-Java)
// activity via SidebarHost.install(...); the map and everything else stay in MainActivity.
package com.usc.myway

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Teal = Color(0xFF00C99D)

/** Observable state the activity pushes into the drawer (theme + switch positions). */
class SidebarState {
    var darkMode by mutableStateOf(false)
    var tracking by mutableStateOf(true)
    var gpsHighAccuracy by mutableStateOf(false)
}

/** Callbacks the drawer fires back to the activity. Java implements this. */
interface SidebarActions {
    fun onNewWaypoint()
    fun onShowWaypoints()
    fun onSetAddress()
    fun onToggleTheme()
    fun onLogout()
    fun onTrackingChanged(enabled: Boolean)
    fun onGpsModeChanged(highAccuracy: Boolean)
}

@Composable
internal fun Sidebar(state: SidebarState, actions: SidebarActions) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 6.dp,
    ) {
        Column(Modifier.fillMaxWidth().padding(8.dp)) {
            SbButton("➕", "Add Waypoint", actions::onNewWaypoint)
            SbButton("📋", "Saved Waypoints", actions::onShowWaypoints)
            SbButton("🎯", "Set Address", actions::onSetAddress)
            SbDivider()
            SbToggle("📡", "Tracking", state.tracking) { state.tracking = it; actions.onTrackingChanged(it) }
            SbToggle("🛰️", "GPS Mode", state.gpsHighAccuracy) { state.gpsHighAccuracy = it; actions.onGpsModeChanged(it) }
            SbDivider()
            SbButton(
                if (state.darkMode) "☀️" else "🌙",
                if (state.darkMode) "Light Mode" else "Dark Mode",
                actions::onToggleTheme,
            )
            SbButton("🚪", "Log out", actions::onLogout)
        }
    }
}

@Composable
private fun SbButton(emoji: String, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
            .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(emoji, fontSize = 18.sp)
        Text(label, Modifier.padding(start = 10.dp), fontSize = 13.sp,
            fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun SbToggle(emoji: String, label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
            .padding(start = 10.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(emoji, fontSize = 18.sp)
        Text(label, Modifier.weight(1f).padding(start = 10.dp), fontSize = 13.sp,
            fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = Teal),
        )
    }
}

@Composable
private fun SbDivider() {
    HorizontalDivider(Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
}
