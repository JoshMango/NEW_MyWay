// Shared avatar rendering: decode a base64 JPEG (users/{uid}.photo) or fall back to a tag initial.
package com.usc.myway

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Teal = Color(0xFF00C99D)
private val TealDeep = Color(0xFF00A77D)

fun decodeAvatar(b64: String): ImageBitmap? =
    if (b64.isBlank()) null else try {
        val bytes = Base64.decode(b64, Base64.NO_WRAP)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    } catch (_: Exception) { null }

/**
 * Decode [uri], downscale so the longest side is [maxDim]px, JPEG-compress at [quality], base64.
 * Keeps the Firestore doc small — 256px for avatars, ~1024px for chat images.
 * ponytail: inline base64 in the doc; ceiling ~1MB/doc and every read pulls the bytes. Move to
 * Firebase Storage if image volume grows.
 */
fun encodeImage(resolver: ContentResolver, uri: Uri, maxDim: Int, quality: Int): String {
    val src = resolver.openInputStream(uri).use { BitmapFactory.decodeStream(it) }
        ?: throw IllegalStateException("decode failed")
    val longest = maxOf(src.width, src.height).coerceAtLeast(1)
    val scale = (maxDim.toFloat() / longest).coerceAtMost(1f)
    val scaled = Bitmap.createScaledBitmap(
        src, (src.width * scale).toInt().coerceAtLeast(1), (src.height * scale).toInt().coerceAtLeast(1), true,
    )
    val out = java.io.ByteArrayOutputStream()
    scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
    return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
}

@Composable
fun AvatarCircle(photo: String, fallback: String, size: Dp = 40.dp) {
    val img = remember(photo) { decodeAvatar(photo) }
    Box(Modifier.size(size).clip(CircleShape).background(Teal.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
        if (img != null) {
            Image(img, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Text(fallback.take(1).uppercase().ifBlank { "?" }, fontWeight = FontWeight.Bold, color = TealDeep, fontSize = (size.value * 0.4f).sp)
        }
    }
}
