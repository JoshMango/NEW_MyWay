// Compose port of MainActivity's navigation drawer — a modern full-height slide-in panel.
package com.usc.myway

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Teal = Color(0xFF00C99D)
private val Danger = Color(0xFFEF4444)

/** Observable state the activity pushes into the drawer (theme + switch positions). */
class SidebarState {
    var darkMode by mutableStateOf(false)
    var tracking by mutableStateOf(true)
    var gpsHighAccuracy by mutableStateOf(false)
}

/** Callbacks the drawer fires back to the activity. */
interface SidebarActions {
    fun onNewWaypoint()
    fun onShowWaypoints()
    fun onSetAddress()
    fun onProfile()
    fun onFriends()
    fun onToggleTheme()
    fun onLogout()
    fun onTrackingChanged(enabled: Boolean)
    fun onGpsModeChanged(highAccuracy: Boolean)
}

@Composable
internal fun Sidebar(state: SidebarState, actions: SidebarActions) {
    Surface(
        modifier = Modifier.fillMaxHeight().width(288.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp,
        shape = RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp),
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(16.dp)) {
            // Brand header
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                Image(
                    painterResource(R.drawable.ic_launcher_logo), contentDescription = null,
                    modifier = Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)),
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("MyWay", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    Text("Group travel companion", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                }
            }

            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                SectionLabel("NAVIGATE")
                SbItem("➕", "Add Waypoint", actions::onNewWaypoint)
                SbItem("📋", "Saved Waypoints", actions::onShowWaypoints)
                SbItem("🎯", "Set Address", actions::onSetAddress)

                Spacer(Modifier.height(12.dp))
                SectionLabel("SOCIAL")
                SbItem("😀", "My Profile", actions::onProfile)
                SbItem("👥", "Friends", actions::onFriends)

                Spacer(Modifier.height(12.dp))
                SectionLabel("SETTINGS")
                SbToggle("📡", "Tracking", state.tracking) { state.tracking = it; actions.onTrackingChanged(it) }
                SbToggle("🛰️", "GPS Mode", state.gpsHighAccuracy) { state.gpsHighAccuracy = it; actions.onGpsModeChanged(it) }
                SbItem(
                    if (state.darkMode) "☀️" else "🌙",
                    if (state.darkMode) "Light Mode" else "Dark Mode",
                    actions::onToggleTheme,
                )
            }

            SbItem("🚪", "Log out", actions::onLogout, danger = true)
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text, fontSize = 11.sp, fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
        modifier = Modifier.padding(start = 8.dp, top = 4.dp, bottom = 6.dp),
    )
}

@Composable
private fun SbItem(emoji: String, label: String, onClick: () -> Unit, danger: Boolean = false) {
    val accent = if (danger) Danger else Teal
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(38.dp).clip(CircleShape).background(accent.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
            Text(emoji, fontSize = 18.sp)
        }
        Spacer(Modifier.width(14.dp))
        Text(label, fontSize = 15.sp, fontWeight = FontWeight.Medium,
            color = if (danger) Danger else MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun SbToggle(emoji: String, label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(38.dp).clip(CircleShape).background(Teal.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
            Text(emoji, fontSize = 18.sp)
        }
        Spacer(Modifier.width(14.dp))
        Text(label, Modifier.weight(1f), fontSize = 15.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
        Switch(checked = checked, onCheckedChange = onChange, colors = SwitchDefaults.colors(checkedTrackColor = Teal))
    }
}
