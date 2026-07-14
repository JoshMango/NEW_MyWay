package com.usc.myway

import android.os.Bundle
import android.util.Patterns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.UserProfileChangeRequest
import com.usc.myway.ui.theme.MyWayTheme

class RegisterActivity : ComponentActivity() {

    private val auth by lazy { FirebaseAuth.getInstance() }
    private val loading = mutableStateOf(false)
    private val emailError = mutableStateOf<String?>(null)
    private val generalError = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MyWayTheme {
                RegisterScreen(
                    loading = loading.value,
                    emailError = emailError.value,
                    generalError = generalError.value,
                    onEmailChanged = { emailError.value = null },
                    onRegister = { first, last, email, pw -> register(first, last, email, pw) },
                    onBackToLogin = { finish() },
                )
            }
        }
    }

    private fun register(first: String, last: String, email: String, password: String) {
        emailError.value = null
        generalError.value = null
        loading.value = true
        auth.createUserWithEmailAndPassword(email, password).addOnCompleteListener(this) { task ->
            if (!task.isSuccessful) {
                loading.value = false
                if (task.exception is FirebaseAuthUserCollisionException) {
                    emailError.value = "This email is already registered"
                } else {
                    generalError.value = task.exception?.message ?: "Registration failed"
                }
                return@addOnCompleteListener
            }
            val user = auth.currentUser ?: run { loading.value = false; return@addOnCompleteListener }
            val profile = UserProfileChangeRequest.Builder().setDisplayName("$first $last").build()
            user.updateProfile(profile).addOnCompleteListener {
                user.sendEmailVerification().addOnCompleteListener {
                    loading.value = false
                    auth.signOut() // force verify-then-login
                    android.widget.Toast.makeText(
                        this, "Verification email sent. Verify it, then sign in.",
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                    finish() // back to login
                }
            }
        }
    }
}

@Composable
fun RegisterScreen(
    loading: Boolean,
    emailError: String?,
    generalError: String?,
    onEmailChanged: () -> Unit,
    onRegister: (String, String, String, String) -> Unit,
    onBackToLogin: () -> Unit,
) {
    var first by remember { mutableStateOf("") }
    var last by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var firstErr by remember { mutableStateOf<String?>(null) }
    var lastErr by remember { mutableStateOf<String?>(null) }
    var passErr by remember { mutableStateOf<String?>(null) }
    var confirmErr by remember { mutableStateOf<String?>(null) }
    var localEmailErr by remember { mutableStateOf<String?>(null) }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp, vertical = 48.dp),
        ) {
            Text("Create account", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp, bottom = 36.dp)) {
                Text(
                    "Let's get you started ",
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
                Icon(
                    Icons.Outlined.Explore,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }

            Row(Modifier.fillMaxWidth()) {
                AuthTextField(
                    value = first, onValueChange = { first = it; firstErr = null },
                    label = "First name", error = firstErr, enabled = !loading,
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                )
                AuthTextField(
                    value = last, onValueChange = { last = it; lastErr = null },
                    label = "Last name", error = lastErr, enabled = !loading,
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                )
            }
            Spacer(Modifier.height(16.dp))
            AuthTextField(
                value = email, onValueChange = { email = it; localEmailErr = null; onEmailChanged() },
                label = "Email", keyboardType = KeyboardType.Email,
                error = localEmailErr ?: emailError, enabled = !loading,
            )
            Spacer(Modifier.height(16.dp))
            AuthTextField(
                value = password, onValueChange = { password = it; passErr = null },
                label = "Password (min 6 characters)", isPassword = true, error = passErr, enabled = !loading,
            )
            Spacer(Modifier.height(16.dp))
            AuthTextField(
                value = confirm, onValueChange = { confirm = it; confirmErr = null },
                label = "Confirm password", isPassword = true, error = confirmErr, enabled = !loading,
            )

            generalError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, fontSize = 14.sp,
                    modifier = Modifier.padding(top = 12.dp))
            }

            Button(
                onClick = {
                    firstErr = if (first.isBlank()) "Required" else null
                    lastErr = if (last.isBlank()) "Required" else null
                    localEmailErr = if (!Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches()) "Enter a valid email" else null
                    passErr = if (password.length < 6) "At least 6 characters" else null
                    confirmErr = if (password != confirm) "Passwords do not match" else null
                    if (firstErr == null && lastErr == null && localEmailErr == null && passErr == null && confirmErr == null) {
                        onRegister(first.trim(), last.trim(), email.trim(), password)
                    }
                },
                enabled = !loading,
                modifier = Modifier.padding(top = 28.dp).fillMaxWidth().height(56.dp),
            ) {
                if (loading) CircularProgressIndicator(modifier = Modifier.height(22.dp), strokeWidth = 2.dp)
                else Text("Create account", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
            ) {
                Text("Already have an account?", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                TextButton(onClick = onBackToLogin, enabled = !loading) {
                    Text("Sign in", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
