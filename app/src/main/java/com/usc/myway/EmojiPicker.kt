package com.usc.myway

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SentimentSatisfiedAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

private val TRAVEL_EMOJIS = listOf(
    "📍", "📌", "🗺️", "🏠", "🍕", "🥐", "☕", "🍺", "🍦",
    "🚗", "✈️", "🚆", "🛳️", "🚲", "🎒", "🏖️", "🏔️", "🌳", "🏢",
    "📸", "✨", "❤️", "🙌", "👋", "🔥", "🌈", "☀️", "🌙", "☁️"
)

@Composable
fun EmojiPickerButton(onEmojiSelected: (String) -> Unit) {
    var showPicker by remember { mutableStateOf(false) }

    IconButton(
        onClick = { showPicker = true },
        modifier = Modifier.size(36.dp)
    ) {
        Icon(
            imageVector = Icons.Outlined.SentimentSatisfiedAlt,
            contentDescription = "Choose emoji",
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            modifier = Modifier.size(24.dp)
        )
    }

    if (showPicker) {
        EmojiPickerDialog(
            onDismiss = { showPicker = false },
            onSelect = {
                onEmojiSelected(it)
                showPicker = false
            }
        )
    }
}

@Composable
fun EmojiPickerDialog(onDismiss: () -> Unit, onSelect: (String) -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 4.dp
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("Choose Emoji", fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.padding(bottom = 12.dp))
                LazyVerticalGrid(
                    columns = GridCells.Fixed(5),
                    modifier = Modifier.height(240.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(TRAVEL_EMOJIS) { emoji ->
                        Box(
                            Modifier.size(44.dp).clip(CircleShape).clickable { onSelect(emoji) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(emoji, fontSize = 24.sp)
                        }
                    }
                }
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
            }
        }
    }
}
