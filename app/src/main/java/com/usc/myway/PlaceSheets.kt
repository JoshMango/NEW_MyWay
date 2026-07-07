// Compose bottom sheets for map markers: the normal-pin actions sheet and the rich landmark
// (POI) details sheet. Async work (geocode, isOpen, photos) runs off the main thread.
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.usc.myway

import android.content.Context
import android.graphics.Bitmap
import android.location.Geocoder
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.tasks.Tasks
import com.google.android.libraries.places.api.model.PhotoMetadata
import com.google.android.libraries.places.api.model.Place
import com.google.android.libraries.places.api.model.Review
import com.google.android.libraries.places.api.net.FetchPhotoRequest
import com.google.android.libraries.places.api.net.IsOpenRequest
import com.google.android.libraries.places.api.net.PlacesClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val Teal = Color(0xFF00C99D)
private val TealDeep = Color(0xFF00A77D)
private val Amber = Color(0xFFF59E0B)
private val Green = Color(0xFF16A34A)
private val Red = Color(0xFFEF4444)

/* ── Normal-pin actions sheet ──────────────────────────────────────────── */

@Composable
fun MarkerActionsSheet(
    title: String,
    note: String,
    latLng: LatLng,
    onDismiss: () -> Unit,
    onNote: () -> Unit,
    onCollection: () -> Unit,
    onDelete: () -> Unit,
) {
    val ctx = LocalContext.current
    val address by produceState("", latLng) { value = geocodeLine(ctx, latLng) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            if (note.isNotEmpty()) Text("📝 $note", fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f), modifier = Modifier.padding(top = 4.dp))
            if (address.isNotEmpty()) Text("📍 $address", fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(16.dp))
            SheetButton("✏️  Add / Edit Note", Teal, Color.White, onNote)
            Spacer(Modifier.height(10.dp))
            SheetButton("📁  Add to Collection", MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f), TealDeep, onCollection)
            Spacer(Modifier.height(10.dp))
            SheetButton("🗑️  Delete Location", Color(0xFFFEE2E2), Red, onDelete)
        }
    }
}

/* ── Landmark details sheet ────────────────────────────────────────────── */

@Composable
fun PlaceDetailsSheet(
    place: Place,
    name: String,
    userNote: String,
    isSaved: Boolean,
    placesClient: PlacesClient,
    photoCache: MutableMap<String, Bitmap>,
    isOpenCache: MutableMap<String, Boolean>,
    onDismiss: () -> Unit,
    onNote: () -> Unit,
    onCollection: () -> Unit,
    onDelete: () -> Unit,
) {
    val ctx = LocalContext.current
    val onSurface = MaterialTheme.colorScheme.onSurface
    val placeId = place.id ?: System.identityHashCode(place).toString()

    var viewerIndex by remember { mutableStateOf<Int?>(null) }
    var openReview by remember { mutableStateOf<Review?>(null) }
    var showAllReviews by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 28.dp)) {
            // Photo gallery
            val photos = place.photoMetadatas
            if (!photos.isNullOrEmpty()) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(bottom = 16.dp),
                ) {
                    itemsIndexed(photos.take(8)) { idx, meta ->
                        PhotoCard(meta, placesClient, "$placeId#$idx", photoCache) { viewerIndex = idx }
                    }
                }
            }

            // Title + chips
            Column(Modifier.padding(horizontal = 20.dp)) {
                Text(name, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, color = onSurface)

                val status by produceState<Pair<String, Boolean>?>(null, place) {
                    value = computeOpenStatus(place, placesClient, isOpenCache)
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    place.rating?.let { r ->
                        Pill(Amber.copy(alpha = 0.15f), onClick = { showAllReviews = true }) {
                            Text("★", color = Amber, fontSize = 13.sp)
                            Text(buildString {
                                append(String.format("%.1f", r))
                                place.userRatingsTotal?.let { append("  ·  ").append(compactCount(it)) }
                            }, color = onSurface, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text("›", color = onSurface.copy(alpha = 0.5f), fontSize = 16.sp)
                        }
                    }
                    place.priceLevel?.takeIf { it > 0 }?.let { pl ->
                        Pill(onSurface.copy(alpha = 0.06f)) {
                            Text("$".repeat(pl), color = onSurface.copy(alpha = 0.8f),
                                fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    status?.let { (text, open) ->
                        val c = if (open) Green else Red
                        Pill(c.copy(alpha = 0.14f)) {
                            Text("●", color = c, fontSize = 9.sp)
                            Text(text, color = c, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                // User note
                if (userNote.isNotEmpty()) {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 14.dp).clip(RoundedCornerShape(12.dp))
                            .background(Teal.copy(alpha = 0.10f)).padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("📝", fontSize = 15.sp, modifier = Modifier.padding(end = 10.dp))
                        Text(userNote, fontSize = 14.sp, color = onSurface, modifier = Modifier.weight(1f))
                    }
                }

                // Actions
                Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.weight(1f)) { SheetButton("✏️  Note", Teal, Color.White, onNote) }
                    Box(Modifier.weight(1f)) { SheetButton("📁  Collection", onSurface.copy(alpha = 0.06f), TealDeep, onCollection) }
                }
            }

            // Details card
            val infoRows = buildList<@Composable () -> Unit> {
                place.address?.takeIf { it.isNotEmpty() }?.let { add { InfoRow("📍", it) } }
                place.openingHours?.weekdayText?.takeIf { it.isNotEmpty() }?.let { add { InfoRow("🕐", it.joinToString("\n")) } }
                place.phoneNumber?.takeIf { it.isNotEmpty() }?.let {
                    add {
                        val i = android.content.Intent(android.content.Intent.ACTION_DIAL, android.net.Uri.parse("tel:$it"))
                        InfoRow("📞", it, Teal) { ctx.startActivity(i) }
                    }
                }
                place.websiteUri?.let {
                    add {
                        val i = android.content.Intent(android.content.Intent.ACTION_VIEW, it)
                        InfoRow("🌐", it.toString(), Teal) { ctx.startActivity(i) }
                    }
                }
            }
            if (infoRows.isNotEmpty()) {
                SectionCard {
                    infoRows.forEachIndexed { idx, row ->
                        row()
                        if (idx < infoRows.lastIndex) HorizontalDivider(color = onSurface.copy(alpha = 0.07f))
                    }
                }
            }

            // Reviews
            place.reviews?.takeIf { it.isNotEmpty() }?.let { reviews ->
                Text("REVIEWS", fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
                    color = onSurface.copy(alpha = 0.5f),
                    modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 6.dp))
                reviews.take(5).forEach { r -> ReviewCard(r) { openReview = r } }
            }

            if (isSaved) {
                Spacer(Modifier.height(10.dp))
                Box(Modifier.padding(horizontal = 20.dp)) {
                    SheetButton("🗑️  Delete Location", Color(0xFFFEE2E2), Red, onDelete)
                }
            }
        }
    }

    // Full-screen zoomable photo viewer (Google-Maps style)
    val photos = place.photoMetadatas
    viewerIndex?.let { start ->
        if (!photos.isNullOrEmpty()) {
            PhotoViewerDialog(photos.take(8), start, placesClient, placeId, photoCache) { viewerIndex = null }
        }
    }
    // Full review reader (single card tap)
    openReview?.let { r -> ReviewDialog(r) { openReview = null } }
    // All reviews (rating-pill tap)
    if (showAllReviews) AllReviewsDialog(place.reviews.orEmpty()) { showAllReviews = false }
}

/* ── Pieces ────────────────────────────────────────────────────────────── */

@Composable
private fun SectionCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 3.dp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
    ) { Column(Modifier.padding(horizontal = 14.dp, vertical = 2.dp), content = content) }
}

@Composable
private fun Pill(bg: Color, onClick: (() -> Unit)? = null, content: @Composable RowScope.() -> Unit) {
    val base = Modifier.clip(RoundedCornerShape(50)).background(bg)
    Row(
        (if (onClick != null) base.clickable(onClick = onClick) else base).padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        content = content,
    )
}

/* ── All-reviews list (rating-pill tap) ────────────────────────────────── */

@Composable
private fun AllReviewsDialog(reviews: List<Review>, onDismiss: () -> Unit) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        ) {
            Column(Modifier.heightIn(max = 640.dp)) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Reviews", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = onSurface,
                        modifier = Modifier.weight(1f))
                    Box(
                        Modifier.size(36.dp).clip(CircleShape)
                            .background(onSurface.copy(alpha = 0.06f)).clickable(onClick = onDismiss),
                        contentAlignment = Alignment.Center,
                    ) { Text("✕", color = onSurface, fontSize = 15.sp) }
                }
                if (reviews.isEmpty()) {
                    Text("No written reviews yet.", fontSize = 14.sp, color = onSurface.copy(alpha = 0.6f),
                        modifier = Modifier.padding(20.dp))
                } else {
                    LazyColumn(Modifier.padding(bottom = 8.dp)) {
                        items(reviews) { r ->
                            ReviewFull(r)
                            HorizontalDivider(color = onSurface.copy(alpha = 0.07f),
                                modifier = Modifier.padding(horizontal = 20.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReviewFull(r: Review) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp)) {
        ReviewHeader(r)
        r.text?.takeIf { it.isNotEmpty() }?.let {
            Text(it, fontSize = 13.sp, color = onSurface.copy(alpha = 0.8f), lineHeight = 20.sp,
                modifier = Modifier.padding(top = 10.dp))
        }
    }
}

@Composable
private fun ReviewCard(r: Review, onClick: () -> Unit) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val text = r.text ?: ""
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 3.dp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp).clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(14.dp)) {
            ReviewHeader(r)
            if (text.isNotEmpty()) {
                Text(text, fontSize = 13.sp, color = onSurface.copy(alpha = 0.8f), lineHeight = 19.sp,
                    maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp))
                if (text.length > 180) {
                    Text("Read more", color = TealDeep, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
    }
}

@Composable
private fun ReviewHeader(r: Review) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val author = r.authorAttribution?.name ?: "Anonymous"
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(38.dp).clip(CircleShape).background(Teal.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(author.firstOrNull()?.uppercase() ?: "?", color = TealDeep,
                fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(author, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                color = onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            r.relativePublishTimeDescription?.let {
                Text(it, fontSize = 11.sp, color = onSurface.copy(alpha = 0.5f))
            }
        }
        Pill(Amber.copy(alpha = 0.15f)) {
            Text("★", color = Amber, fontSize = 12.sp)
            Text(r.rating.toString(), color = onSurface, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/* ── Full review reader ────────────────────────────────────────────────── */

@Composable
private fun ReviewDialog(r: Review, onDismiss: () -> Unit) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()).padding(20.dp)) {
                ReviewHeader(r)
                Text(r.text ?: "", fontSize = 14.sp, color = onSurface.copy(alpha = 0.85f), lineHeight = 21.sp,
                    modifier = Modifier.padding(top = 14.dp))
                Spacer(Modifier.height(16.dp))
                SheetButton("Close", Teal, Color.White, onDismiss)
            }
        }
    }
}

/* ── Full-screen zoomable photo viewer ─────────────────────────────────── */

@Composable
private fun PhotoViewerDialog(
    photos: List<PhotoMetadata>,
    startIndex: Int,
    placesClient: PlacesClient,
    baseKey: String,
    cache: MutableMap<String, Bitmap>,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            val pager = rememberPagerState(initialPage = startIndex) { photos.size }
            HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
                ZoomablePhoto(photos[page], placesClient, "$baseKey#full#$page", cache)
            }
            // X button
            Box(
                Modifier.statusBarsPadding().padding(12.dp).size(40.dp).clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.5f)).clickable(onClick = onDismiss),
                contentAlignment = Alignment.Center,
            ) { Text("✕", color = Color.White, fontSize = 18.sp) }
            // Page indicator
            if (photos.size > 1) {
                Box(
                    Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 20.dp)
                        .clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.5f))
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                ) { Text("${pager.currentPage + 1} / ${photos.size}", color = Color.White, fontSize = 13.sp) }
            }
        }
    }
}

@Composable
private fun ZoomablePhoto(meta: PhotoMetadata, placesClient: PlacesClient, cacheKey: String, cache: MutableMap<String, Bitmap>) {
    val bmp by produceState(cache[cacheKey], cacheKey) {
        if (value != null) return@produceState
        value = try {
            val req = FetchPhotoRequest.builder(meta).setMaxWidth(1600).setMaxHeight(1600).build()
            val r = withContext(Dispatchers.IO) { Tasks.await(placesClient.fetchPhoto(req)) }
            r.bitmap.also { cache[cacheKey] = it }
        } catch (_: Exception) { null }
    }
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val state = rememberTransformableState { zoomChange, panChange, _ ->
        scale = (scale * zoomChange).coerceIn(1f, 5f)
        offset = if (scale > 1f) offset + panChange else Offset.Zero
    }
    Box(
        Modifier.fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = {
                    if (scale > 1f) { scale = 1f; offset = Offset.Zero } else scale = 2.5f
                })
            }
            .transformable(state, canPan = { scale > 1f }),
        contentAlignment = Alignment.Center,
    ) {
        bmp?.let {
            Image(
                it.asImageBitmap(), null,
                Modifier.fillMaxWidth().graphicsLayer(
                    scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y,
                ),
                contentScale = ContentScale.Fit,
            )
        } ?: CircularProgressIndicator(color = Color.White)
    }
}

@Composable
private fun SheetButton(text: String, bg: Color, fg: Color, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(12.dp))
            .background(bg).clickable(onClick = onClick),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) { Text(text, color = fg, fontWeight = FontWeight.Bold, fontSize = 15.sp) }
}

@Composable
private fun InfoRow(emoji: String, text: String, textColor: Color? = null, onClick: (() -> Unit)? = null) {
    val base = Modifier.fillMaxWidth().padding(vertical = 12.dp)
    Row(if (onClick != null) base.clickable(onClick = onClick) else base, verticalAlignment = Alignment.CenterVertically) {
        Text(emoji, fontSize = 16.sp, modifier = Modifier.padding(end = 14.dp))
        Text(text, fontSize = 13.sp, fontWeight = if (textColor != null) FontWeight.Bold else FontWeight.Normal,
            color = textColor ?: MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun PhotoCard(meta: PhotoMetadata, placesClient: PlacesClient, cacheKey: String, cache: MutableMap<String, Bitmap>, onClick: () -> Unit) {
    val bmp by produceState(cache[cacheKey], cacheKey) {
        if (value != null) return@produceState
        value = try {
            val req = FetchPhotoRequest.builder(meta).setMaxWidth(600).setMaxHeight(400).build()
            val r = withContext(Dispatchers.IO) { Tasks.await(placesClient.fetchPhoto(req)) }
            r.bitmap.also { cache[cacheKey] = it }
        } catch (_: Exception) { null }
    }
    Column(
        Modifier.size(width = 240.dp, height = 160.dp).clip(RoundedCornerShape(16.dp))
            .background(Color(0xFFE2E8F0)).clickable(onClick = onClick)
    ) {
        bmp?.let {
            Image(it.asImageBitmap(), null, Modifier.fillMaxWidth().height(160.dp), contentScale = ContentScale.Crop)
        }
    }
}

/** "1234" → "1.2k" for compact rating counts. */
private fun compactCount(n: Int): String =
    if (n < 1000) "$n reviews" else String.format("%.1fk reviews", n / 1000.0)

private suspend fun geocodeLine(ctx: Context, ll: LatLng): String = withContext(Dispatchers.IO) {
    try {
        @Suppress("DEPRECATION")
        Geocoder(ctx).getFromLocation(ll.latitude, ll.longitude, 1)?.firstOrNull()?.getAddressLine(0) ?: ""
    } catch (_: Exception) { "" }
}

private suspend fun computeOpenStatus(
    place: Place, placesClient: PlacesClient, cache: MutableMap<String, Boolean>,
): Pair<String, Boolean>? {
    when (place.businessStatus) {
        Place.BusinessStatus.CLOSED_PERMANENTLY -> return "Permanently closed" to false
        Place.BusinessStatus.CLOSED_TEMPORARILY -> return "Temporarily closed" to false
        else -> {}
    }
    val id = place.id
    if (id != null && cache.containsKey(id)) { val o = cache[id]!!; return (if (o) "Open now" else "Closed") to o }
    return try {
        val resp = withContext(Dispatchers.IO) { Tasks.await(placesClient.isOpen(IsOpenRequest.newInstance(place))) }
        resp.isOpen?.let { open ->
            if (id != null) cache[id] = open
            (if (open) "Open now" else "Closed") to open
        }
    } catch (_: Exception) { null }
}
