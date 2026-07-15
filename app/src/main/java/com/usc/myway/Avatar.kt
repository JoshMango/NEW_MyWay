// Shared avatar rendering: decode a base64 JPEG (users/{uid}.photo) or fall back to a tag initial.
package com.usc.myway

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
    val decoded = resolver.openInputStream(uri).use { BitmapFactory.decodeStream(it) }
        ?: throw IllegalStateException("decode failed")
    // Camera photos carry an EXIF orientation flag instead of rotating the pixels; apply it here or
    // portrait shots come out sideways (landscape) once we re-encode and drop the EXIF.
    val src = applyExifRotation(resolver, uri, decoded)
    val longest = maxOf(src.width, src.height).coerceAtLeast(1)
    val scale = (maxDim.toFloat() / longest).coerceAtMost(1f)
    val scaled = Bitmap.createScaledBitmap(
        src, (src.width * scale).toInt().coerceAtLeast(1), (src.height * scale).toInt().coerceAtLeast(1), true,
    )
    val out = java.io.ByteArrayOutputStream()
    scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
    return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
}

private fun applyExifRotation(resolver: ContentResolver, uri: Uri, bmp: Bitmap): Bitmap {
    val degrees = try {
        resolver.openInputStream(uri)?.use {
            when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } ?: 0f
    } catch (_: Exception) { 0f }
    if (degrees == 0f) return bmp
    val m = Matrix().apply { postRotate(degrees) }
    return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
}

/**
 * Avatar for another user, resolved live from users/{uid}. Use this in friend/member lists and chat
 * rows so photos actually appear (list docs don't carry photos) and auto-update when the person
 * changes their picture. [fallback] (their @tag) shows an initial until the photo loads.
 */
@Composable
fun LiveAvatar(uid: String, fallback: String, size: Dp = 40.dp) {
    var photo by remember(uid) { mutableStateOf("") }
    androidx.compose.runtime.DisposableEffect(uid) {
        val reg = Profiles.listenProfile(uid) { photo = it.photo }
        onDispose { reg.remove() }
    }
    AvatarCircle(photo = photo, fallback = fallback, size = size)
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
