// Local settings: customize map marker appearance (pin colour, note-card icon, collapsed pencil icon)
// and wipe locally-saved map data. All settings are device-local (SharedPreferences via App).
package com.usc.myway

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.usc.myway.ui.theme.MyWayTheme

private val Teal = Color(0xFF00C99D)
private val TealDeep = Color(0xFF00A77D)
private val Danger = Color(0xFFEF4444)

// BitmapDescriptorFactory hues → their approximate swatch colours.
private val PIN_HUES = listOf(0f, 30f, 60f, 120f, 210f, 240f, 270f, 330f)
private val PIN_ICONS = listOf("📝", "📍", "⭐", "❤️", "🔖", "🚩")
private val PENCIL_ICONS = listOf("✏️", "🖊️", "📌", "⭐", "❗", "🎯")

class SettingsActivity : ComponentActivity() {
    private val app get() = application as App

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MyWayTheme { SettingsScreen() } }
    }

    private fun toast(msg: String) = android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun SettingsScreen() {
        var hue by remember { mutableStateOf(app.getPinHue()) }
        var pinIcon by remember { mutableStateOf(app.getPinIcon()) }
        var pencilIcon by remember { mutableStateOf(app.getPencilIcon()) }
        var confirmWipe by remember { mutableStateOf(false) }

        Scaffold(
            topBar = {
                TopAppBar(title = { Text("Settings", fontWeight = FontWeight.Bold) },
                    navigationIcon = { TextButton(onClick = { finish() }) { Text("←", fontSize = 22.sp, fontWeight = FontWeight.Bold) } })
            },
        ) { pad ->
            Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(20.dp)) {
                SectionLabel("PIN COLOUR")
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PIN_HUES.forEach { h ->
                        val c = Color.hsv(h, 0.85f, 0.9f)
                        Box(
                            Modifier.size(38.dp).clip(CircleShape).background(c)
                                .border(if (h == hue) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                .clickable { hue = h; app.setPinHue(h) },
                        )
                    }
                }

                Spacer(Modifier.height(24.dp))
                SectionLabel("PIN NOTE ICON")
                IconPicker(PIN_ICONS, pinIcon) { pinIcon = it; app.setPinIcon(it) }

                Spacer(Modifier.height(24.dp))
                SectionLabel("PENCIL ICON (zoomed-out note)")
                IconPicker(PENCIL_ICONS, pencilIcon) { pencilIcon = it; app.setPencilIcon(it) }

                Spacer(Modifier.height(36.dp))
                SectionLabel("DATA")
                OutlinedButton(
                    onClick = { confirmWipe = true },
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Danger),
                ) { Text("🗑️  Delete my saved places") }
                Text("Removes every saved pin, note and collection from your account, on all devices. Your account isn't affected.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    modifier = Modifier.padding(top = 8.dp))
            }
        }

        if (confirmWipe) {
            AlertDialog(
                onDismissRequest = { confirmWipe = false },
                title = { Text("Delete my saved places?", fontWeight = FontWeight.Bold) },
                text = { Text("This permanently removes all saved pins, notes and collections from your account, on every device. This can't be undone.") },
                confirmButton = {
                    TextButton(onClick = { confirmWipe = false; app.clearMyPlaces(); toast("Saved places deleted") }) {
                        Text("Delete", color = Danger, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = { TextButton(onClick = { confirmWipe = false }) { Text("Cancel") } },
            )
        }
    }

    @Composable
    private fun IconPicker(icons: List<String>, selected: String, onPick: (String) -> Unit) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            icons.forEach { icon ->
                Box(
                    Modifier.size(46.dp).clip(RoundedCornerShape(12.dp))
                        .background(if (icon == selected) Teal.copy(alpha = 0.20f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .border(if (icon == selected) 2.dp else 0.dp, TealDeep, RoundedCornerShape(12.dp))
                        .clickable { onPick(icon) },
                    contentAlignment = Alignment.Center,
                ) { Text(icon, fontSize = 22.sp) }
            }
        }
    }

    @Composable
    private fun SectionLabel(text: String) {
        Text(text, fontSize = 12.sp, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f), modifier = Modifier.padding(bottom = 10.dp))
    }
}
