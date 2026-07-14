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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.ExitToApp
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.WifiTethering
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Teal = Color(0xFF00C99D)
private val TealDeep = Color(0xFF00A77D)
private val Danger = Color(0xFFEF4444)

/** Observable state the activity pushes into the drawer (theme + switch positions). */
class SidebarState {
    var darkMode by mutableStateOf(false)
    var tracking by mutableStateOf(true)
    var userName by mutableStateOf("")   // for the Discord-style profile header
    var userTag by mutableStateOf("")
    var userPhoto by mutableStateOf("")
    var userBanner by mutableStateOf("") // "" = teal gradient fallback
}

/** Callbacks the drawer fires back to the activity. */
interface SidebarActions {
    fun onCollections()
    fun onWaypoints()
    fun onProfile()
    fun onFriends()
    fun onGroups()
    fun onMessages()
    fun onSettings()
    fun onToggleTheme()
    fun onLogout()
    fun onTrackingChanged(enabled: Boolean)
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
                // Discord-style profile header: banner strip above, avatar + name below. Tap to open settings.
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                        .background(Teal.copy(alpha = 0.10f)).clickable(onClick = actions::onProfile),
                ) {
                    val banner = remember(state.userBanner) { decodeAvatar(state.userBanner) }
                    Box(Modifier.fillMaxWidth().height(56.dp)) {
                        if (banner != null) Image(banner, contentDescription = null, contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize())
                        else Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Teal, TealDeep))))
                    }
                    Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        AvatarCircle(photo = state.userPhoto, fallback = state.userTag.ifBlank { "?" }, size = 44.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(state.userName.ifBlank { "Set up your profile" }, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
                            if (state.userTag.isNotBlank()) Text("@${state.userTag}", fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), maxLines = 1)
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))
                SectionLabel("PLACES")
                SbItem(Icons.Outlined.Folder, "Collections", actions::onCollections)
                SbItem(Icons.Outlined.Place, "Waypoints", actions::onWaypoints)

                Spacer(Modifier.height(12.dp))
                SectionLabel("SOCIAL")
                SbItem(Icons.Outlined.Chat, "Messages", actions::onMessages)
                SbItem(Icons.Outlined.People, "Friends", actions::onFriends)
                SbItem(Icons.Outlined.Explore, "Groups", actions::onGroups)

                Spacer(Modifier.height(12.dp))
                SectionLabel("SETTINGS")
                SbItem(Icons.Outlined.Settings, "Settings", actions::onSettings)
                SbToggle(Icons.Outlined.WifiTethering, "Tracking", state.tracking) { state.tracking = it; actions.onTrackingChanged(it) }
                SbItem(
                    if (state.darkMode) Icons.Outlined.LightMode else Icons.Outlined.DarkMode,
                    if (state.darkMode) "Light Mode" else "Dark Mode",
                    actions::onToggleTheme,
                )
            }

            SbItem(Icons.Outlined.ExitToApp, "Log out", actions::onLogout, danger = true)
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
private fun SbItem(icon: ImageVector, label: String, onClick: () -> Unit, danger: Boolean = false) {
    val accent = if (danger) Danger else Teal
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(38.dp).clip(CircleShape).background(accent.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(14.dp))
        Text(label, fontSize = 15.sp, fontWeight = FontWeight.Medium,
            color = if (danger) Danger else MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun SbToggle(icon: ImageVector, label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(38.dp).clip(CircleShape).background(Teal.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = Teal, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(14.dp))
        Text(label, Modifier.weight(1f), fontSize = 15.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
        Switch(checked = checked, onCheckedChange = onChange, colors = SwitchDefaults.colors(checkedTrackColor = Teal))
    }
}
