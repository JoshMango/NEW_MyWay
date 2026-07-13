// First-run onboarding: a short "how to use MyWay" pager, ending on @tag creation.
// Shown once (LoginActivity routes here only when the signed-in user has no tag yet).
package com.usc.myway

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.firebase.auth.FirebaseAuth
import com.usc.myway.ui.theme.MyWayTheme
import kotlinx.coroutines.launch

private val Teal = Color(0xFF00C99D)
private val TealDeep = Color(0xFF00A77D)

private data class OnbPage(val emoji: String, val title: String, val body: String)

private val infoPages = listOf(
    OnbPage("🧭", "Welcome to MyWay", "Your group-travel companion. Share where you are, find your people, and plan the trip together — all on one map."),
    OnbPage("📍", "Map & waypoints", "Drop pins, save landmarks, and add notes. Organize places into collections you can revisit anytime."),
    OnbPage("🚗", "Directions & navigation", "Get turn-by-turn directions to any pin or landmark — drive, walk, bike, or transit — with live voice guidance."),
)

class OnboardingActivity : ComponentActivity() {

    private val auth by lazy { FirebaseAuth.getInstance() }
    private val claiming = mutableStateOf(false)
    private val tagError = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MyWayTheme {
                OnboardingScreen(
                    claiming = claiming.value,
                    tagError = tagError.value,
                    onClearError = { tagError.value = null },
                    onClaim = { raw -> claim(raw) },
                )
            }
        }
    }

    private fun claim(raw: String) {
        val display = raw.trim().removePrefix("@")
        val norm = Profiles.normalize(display)
        Profiles.formatError(norm)?.let { tagError.value = it; return }
        val user = auth.currentUser ?: run { tagError.value = "You're not signed in"; return }
        claiming.value = true
        tagError.value = null
        Profiles.claimTag(user.uid, display) { res ->
            claiming.value = false
            when (res) {
                is Profiles.ClaimResult.Success -> {
                    (application as App).setUserTag(user.uid, res.tag)
                    startActivity(Intent(this, MainActivity::class.java))
                    finish()
                }
                Profiles.ClaimResult.Taken -> tagError.value = "@$norm is already taken"
                is Profiles.ClaimResult.Error -> tagError.value = res.message
            }
        }
    }
}

@Composable
private fun OnboardingScreen(
    claiming: Boolean,
    tagError: String?,
    onClearError: () -> Unit,
    onClaim: (String) -> Unit,
) {
    val tagPageIndex = infoPages.size
    val pageCount = infoPages.size + 1
    val pager = rememberPagerState(pageCount = { pageCount })
    val scope = rememberCoroutineScope()

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 32.dp)) {
            HorizontalPager(state = pager, modifier = Modifier.weight(1f)) { page ->
                if (page < tagPageIndex) InfoPageView(infoPages[page])
                else TagPageView(claiming, tagError, onClearError, onClaim)
            }

            // Dots
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.Center) {
                repeat(pageCount) { i ->
                    val active = pager.currentPage == i
                    val c by animateColorAsState(if (active) Teal else Teal.copy(alpha = 0.25f), label = "dot")
                    Box(Modifier.padding(horizontal = 4.dp).size(if (active) 10.dp else 8.dp).clip(CircleShape).background(c))
                }
            }

            // Nav controls — only on the info pages; the tag page has its own button.
            if (pager.currentPage < tagPageIndex) {
                Row(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { scope.launch { pager.animateScrollToPage(tagPageIndex) } }) {
                        Text("Skip", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    }
                    Button(
                        onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.height(52.dp),
                    ) { Text("Next", fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 12.dp)) }
                }
            } else {
                Spacer(Modifier.height(72.dp))
            }
        }
    }
}

@Composable
private fun InfoPageView(page: OnbPage) {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(page.emoji, fontSize = 88.sp)
        Text(
            page.title, fontSize = 28.sp, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.secondary, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 24.dp),
        )
        Text(
            page.body, fontSize = 16.sp, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

@Composable
private fun TagPageView(
    claiming: Boolean,
    tagError: String?,
    onClearError: () -> Unit,
    onClaim: (String) -> Unit,
) {
    var tag by remember { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("🏷️", fontSize = 72.sp)
        Text(
            "Claim your @tag", fontSize = 28.sp, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(top = 20.dp),
        )
        Text(
            "This is how friends find and add you. Pick something unique.",
            fontSize = 15.sp, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            modifier = Modifier.padding(top = 10.dp, bottom = 24.dp),
        )
        OutlinedTextField(
            value = tag,
            onValueChange = { v -> tag = v.filter { !it.isWhitespace() }; if (tagError != null) onClearError() },
            label = { Text("Your tag") },
            prefix = { Text("@") },
            singleLine = true,
            enabled = !claiming,
            isError = tagError != null,
            supportingText = tagError?.let { { Text(it) } },
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = { onClaim(tag) },
            enabled = !claiming && tag.isNotBlank(),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.padding(top = 24.dp).fillMaxWidth().height(56.dp),
        ) {
            if (claiming) CircularProgressIndicator(Modifier.height(22.dp), strokeWidth = 2.dp, color = Color.White)
            else Text("Get Started", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}
