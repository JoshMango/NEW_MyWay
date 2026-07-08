// User profile settings: edit first/last name, change @tag (with uniqueness check),
// and upload an avatar. The avatar is downscaled to a tiny JPEG and stored base64 inline
// in the user's Firestore doc (users/{uid}.photo) — no Firebase Storage bucket/dependency.
package com.usc.myway

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.firebase.auth.FirebaseAuth
import com.usc.myway.ui.theme.MyWayTheme
import java.io.ByteArrayOutputStream
import kotlin.math.max

private val Teal = Color(0xFF00C99D)

/** Live screen state the activity pushes into the Composables. */
private class ProfileState {
    var loading by mutableStateOf(true)
    var first by mutableStateOf("")
    var last by mutableStateOf("")
    var tag by mutableStateOf("")
    var photo by mutableStateOf("")     // base64 JPEG, "" = none
    var savingName by mutableStateOf(false)
    var savingTag by mutableStateOf(false)
    var savingPhoto by mutableStateOf(false)
    var tagError by mutableStateOf<String?>(null)
    var toast by mutableStateOf<String?>(null)
}

class ProfileActivity : ComponentActivity() {

    private val auth by lazy { FirebaseAuth.getInstance() }
    private val uid get() = auth.currentUser?.uid ?: ""
    private val s = ProfileState()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Profiles.fetchProfile(uid) { p ->
            s.loading = false
            if (p != null) { s.first = p.firstName; s.last = p.lastName; s.tag = p.tag; s.photo = p.photo }
        }
        setContent {
            MyWayTheme {
                val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
                    if (uri != null) uploadPhoto(uri)
                }
                ProfileScreen(
                    s = s,
                    onBack = { finish() },
                    onPickPhoto = { pickImage.launch("image/*") },
                    onSaveName = ::saveName,
                    onSaveTag = ::saveTag,
                )
            }
        }
    }

    private fun saveName() {
        s.savingName = true
        Profiles.updateName(uid, s.first, s.last) { err ->
            s.savingName = false
            s.toast = err ?: "Name saved"
        }
    }

    private fun saveTag(raw: String) {
        val display = raw.trim().removePrefix("@")
        val norm = Profiles.normalize(display)
        Profiles.formatError(norm)?.let { s.tagError = it; return }
        if (norm == Profiles.normalize(s.tag)) { s.tagError = null; s.toast = "That's already your tag"; return }
        s.savingTag = true
        s.tagError = null
        Profiles.claimTag(uid, display) { res ->
            s.savingTag = false
            when (res) {
                is Profiles.ClaimResult.Success -> {
                    s.tag = res.tag
                    (application as App).setUserTag(uid, res.tag)
                    s.toast = "Tag updated to @${res.tag}"
                }
                Profiles.ClaimResult.Taken -> s.tagError = "@$norm is already taken"
                is Profiles.ClaimResult.Error -> s.tagError = res.message
            }
        }
    }

    private fun uploadPhoto(uri: Uri) {
        s.savingPhoto = true
        val b64 = try { encodeAvatar(uri) } catch (_: Exception) { null }
        if (b64 == null) { s.savingPhoto = false; s.toast = "Couldn't read that image"; return }
        Profiles.updatePhoto(uid, b64) { err ->
            s.savingPhoto = false
            if (err == null) { s.photo = b64; s.toast = "Photo updated" } else s.toast = err
        }
    }

    /** Decode, center-downscale to a 256px avatar, JPEG-compress, base64. Keeps the Firestore doc tiny. */
    private fun encodeAvatar(uri: Uri): String {
        val src = contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it) }
            ?: throw IllegalStateException("decode failed")
        val maxDim = max(src.width, src.height).coerceAtLeast(1)
        val scale = (256f / maxDim).coerceAtMost(1f)
        val scaled = Bitmap.createScaledBitmap(src, (src.width * scale).toInt().coerceAtLeast(1), (src.height * scale).toInt().coerceAtLeast(1), true)
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}

/** Decode a base64 avatar for display, or null. */
private fun decodeAvatar(b64: String): androidx.compose.ui.graphics.ImageBitmap? =
    if (b64.isBlank()) null else try {
        val bytes = Base64.decode(b64, Base64.NO_WRAP)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    } catch (_: Exception) { null }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileScreen(
    s: ProfileState,
    onBack: () -> Unit,
    onPickPhoto: () -> Unit,
    onSaveName: () -> Unit,
    onSaveTag: (String) -> Unit,
) {
    var tagField by remember(s.tag) { mutableStateOf(s.tag) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("My Profile", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("←", fontSize = 22.sp, fontWeight = FontWeight.Bold) }
                },
                colors = TopAppBarDefaults.topAppBarColors(),
            )
        },
    ) { pad ->
        if (s.loading) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Teal)
            }
            return@Scaffold
        }
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Avatar
            val img = remember(s.photo) { decodeAvatar(s.photo) }
            Box(
                Modifier.size(120.dp).clip(CircleShape).background(Teal.copy(alpha = 0.15f)).clickable(onClick = onPickPhoto),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    s.savingPhoto -> CircularProgressIndicator(color = Teal)
                    img != null -> Image(img, contentDescription = "Profile photo", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    else -> Text((s.tag.firstOrNull()?.uppercaseChar() ?: '?').toString(), fontSize = 48.sp, fontWeight = FontWeight.Bold, color = Teal)
                }
            }
            TextButton(onClick = onPickPhoto, enabled = !s.savingPhoto) {
                Text(if (s.photo.isBlank()) "Add photo" else "Change photo", color = Teal)
            }

            Spacer(Modifier.height(16.dp))

            // Name
            SectionLabel("NAME")
            OutlinedTextField(
                value = s.first, onValueChange = { s.first = it },
                label = { Text("First name") }, singleLine = true, enabled = !s.savingName,
                shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = s.last, onValueChange = { s.last = it },
                label = { Text("Last name") }, singleLine = true, enabled = !s.savingName,
                shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = onSaveName, enabled = !s.savingName,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.padding(top = 12.dp).fillMaxWidth().height(50.dp),
            ) {
                if (s.savingName) CircularProgressIndicator(Modifier.height(20.dp), strokeWidth = 2.dp, color = Color.White)
                else Text("Save name", fontWeight = FontWeight.Bold)
            }

            Spacer(Modifier.height(24.dp))

            // Tag
            SectionLabel("YOUR @TAG")
            OutlinedTextField(
                value = tagField,
                onValueChange = { v -> tagField = v.filter { !it.isWhitespace() }.removePrefix("@"); if (s.tagError != null) s.tagError = null },
                label = { Text("Tag") }, prefix = { Text("@") }, singleLine = true, enabled = !s.savingTag,
                isError = s.tagError != null, supportingText = s.tagError?.let { { Text(it) } },
                shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { onSaveTag(tagField) },
                enabled = !s.savingTag && tagField.isNotBlank(),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.padding(top = 12.dp).fillMaxWidth().height(50.dp),
            ) {
                if (s.savingTag) CircularProgressIndicator(Modifier.height(20.dp), strokeWidth = 2.dp, color = Color.White)
                else Text("Change tag", fontWeight = FontWeight.Bold)
            }

            s.toast?.let { msg ->
                Row(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.Center) {
                    Text(msg, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text, fontSize = 12.sp, fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
    )
}
