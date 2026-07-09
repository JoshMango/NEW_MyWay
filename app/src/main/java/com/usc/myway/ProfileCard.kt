// Discord-style profile card: a wide banner with the avatar overlapping its lower edge, plus name + @tag.
// Reused by friend search results and the group member list (tap a person → their card).
package com.usc.myway

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

private val Teal = Color(0xFF00C99D)
private val TealDeep = Color(0xFF00A77D)

/** Popup card for a user, loaded by uid. [tagHint]/[nameHint] show instantly while the fetch resolves. */
@Composable
fun ProfileCardDialog(uid: String, tagHint: String, nameHint: String = "", onDismiss: () -> Unit) {
    var profile by remember(uid) { mutableStateOf<Profiles.Profile?>(null) }
    var banner by remember(uid) { mutableStateOf("") }
    LaunchedEffect(uid) {
        Profiles.fetchProfile(uid) { profile = it }
        Profiles.fetchBanner(uid) { banner = it }
    }
    val tag = profile?.tag?.ifBlank { tagHint } ?: tagHint
    val name = profile?.let { "${it.firstName} ${it.lastName}".trim() }?.ifBlank { nameHint } ?: nameHint

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxWidth()) {
                ProfileHeader(banner = banner, photo = profile?.photo ?: "", tag = tag)
                Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 44.dp, bottom = 20.dp)) {
                    if (name.isNotBlank()) Text(name, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface)
                    Text("@$tag", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                }
            }
        }
    }
}

/** Wide banner (image or teal gradient) with the circular avatar overlapping its lower-left, ringed like Discord. */
@Composable
fun ProfileHeader(banner: String, photo: String, tag: String, bannerHeight: androidx.compose.ui.unit.Dp = 96.dp, avatar: androidx.compose.ui.unit.Dp = 72.dp) {
    val bannerImg = remember(banner) { decodeAvatar(banner) }
    val ring = MaterialTheme.colorScheme.surface
    Box(Modifier.fillMaxWidth()) {
        // Banner
        Box(Modifier.fillMaxWidth().height(bannerHeight)) {
            if (bannerImg != null) {
                Image(bannerImg, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().height(bannerHeight))
            } else {
                Box(Modifier.fillMaxWidth().height(bannerHeight)
                    .background(Brush.horizontalGradient(listOf(Teal, TealDeep))))
            }
        }
        // Avatar overlapping the banner's bottom edge, with a surface-colored ring.
        Box(
            Modifier.align(Alignment.BottomStart).offset(x = 16.dp, y = avatar / 2)
                .size(avatar + 8.dp).clip(CircleShape).background(ring),
            contentAlignment = Alignment.Center,
        ) { AvatarCircle(photo = photo, fallback = tag, size = avatar) }
    }
}
