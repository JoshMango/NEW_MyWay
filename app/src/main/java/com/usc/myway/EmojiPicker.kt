package com.usc.myway

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.SentimentSatisfiedAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

private val TealDeep = Color(0xFF00A77D)

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
    var keyboardEmoji by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(Modifier.padding(20.dp)) {
                Text("Choose Emoji", fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.padding(bottom = 16.dp))

                // Keyboard Input Section
                OutlinedTextField(
                    value = keyboardEmoji,
                    onValueChange = { keyboardEmoji = it },
                    placeholder = { Text("Type any emoji...", fontSize = 14.sp) },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    trailingIcon = {
                        if (keyboardEmoji.isNotBlank()) {
                            IconButton(onClick = { onSelect(keyboardEmoji) }) {
                                Icon(Icons.Default.Check, contentDescription = "Confirm", tint = TealDeep)
                            }
                        }
                    }
                )

                Text("Quick Select", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), modifier = Modifier.padding(bottom = 8.dp))

                LazyVerticalGrid(
                    columns = GridCells.Fixed(5),
                    modifier = Modifier.height(200.dp),
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
                Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.CenterEnd) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                }
            }
        }
    }
}
