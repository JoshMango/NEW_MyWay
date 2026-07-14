package com.usc.myway

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.util.Patterns
import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.MarkEmailRead
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.auth.AuthCredential
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GithubAuthProvider
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.OAuthProvider
import com.usc.myway.ui.theme.MyWayTheme

class LoginActivity : ComponentActivity() {

    private val auth by lazy { FirebaseAuth.getInstance() }
    private lateinit var googleClient: GoogleSignInClient
    private lateinit var googleLauncher: ActivityResultLauncher<Intent>

    // Set when a sign-in collided with an existing account; linked once the user
    // re-authenticates with their original provider. Null in the normal flow.
    private var pendingCredential: AuthCredential? = null

    private val loading = mutableStateOf(false)
    private val errorMsg = mutableStateOf<String?>(null)
    // Email a reset link was just requested for — non-null shows the "check your inbox" modal.
    private val resetSentTo = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val current = auth.currentUser
        if (current != null && current.isEmailVerified) { goToMain(); return }

        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(getString(R.string.default_web_client_id))
            .requestEmail()
            .build()
        googleClient = GoogleSignIn.getClient(this, gso)

        googleLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            val task = GoogleSignIn.getSignedInAccountFromIntent(res.data)
            try {
                val account = task.getResult(ApiException::class.java)
                val cred = GoogleAuthProvider.getCredential(account.idToken, null)
                loading.value = true
                auth.signInWithCredential(cred).addOnCompleteListener(this) { t ->
                    loading.value = false
                    if (t.isSuccessful) finishSignIn()
                    else handleAuthFailure(t.exception, cred, GoogleAuthProvider.PROVIDER_ID, "Google sign in failed")
                }
            } catch (e: ApiException) {
                errorMsg.value = e.message ?: "Google sign in failed"
            }
        }

        setContent {
            MyWayTheme {
                LoginScreen(
                    loading = loading.value,
                    error = errorMsg.value,
                    onLogin = { email, pw -> emailLogin(email, pw) },
                    onGoogle = { errorMsg.value = null; googleLauncher.launch(googleClient.signInIntent) },
                    onGithub = { errorMsg.value = null; githubSignIn() },
                    onRegister = { startActivity(Intent(this, RegisterActivity::class.java)) },
                    onForgotPassword = { email -> sendPasswordReset(email) },
                    resetSentTo = resetSentTo.value,
                    onDismissResetSent = { resetSentTo.value = null },
                )
            }
        }
    }

    // ── Email / password ────────────────────────────────────────────────────────

    private fun emailLogin(email: String, password: String) {
        errorMsg.value = null
        loading.value = true
        auth.signInWithEmailAndPassword(email, password).addOnCompleteListener(this) { task ->
            loading.value = false
            if (!task.isSuccessful) {
                errorMsg.value = task.exception?.message ?: "Sign in failed"
                return@addOnCompleteListener
            }
            val user = auth.currentUser
            if (user != null && user.isEmailVerified) finishSignIn() else promptVerify(user)
        }
    }

    private fun promptVerify(user: FirebaseUser?) {
        auth.signOut()
        MaterialAlertDialogBuilder(this)
            .setTitle("Verify your email")
            .setMessage("Please verify your email before signing in. Check your inbox for the verification link.")
            .setPositiveButton("Resend") { _, _ -> user?.sendEmailVerification() }
            .setNegativeButton("OK", null)
            .show()
    }

    // Confirms the same way whether or not the account exists, so a bad actor can't use this
    // to enumerate registered emails.
    private fun sendPasswordReset(email: String) {
        loading.value = true
        auth.sendPasswordResetEmail(email).addOnCompleteListener {
            loading.value = false
            resetSentTo.value = email
        }
    }

    // ── GitHub (Firebase-hosted OAuth, no extra SDK) ──────────────────────────────

    private fun githubSignIn() {
        val provider = OAuthProvider.newBuilder("github.com")
        val pending = auth.pendingAuthResult
        if (pending != null) {
            pending.addOnSuccessListener { finishSignIn() }
                .addOnFailureListener { handleAuthFailure(it, null, GithubAuthProvider.PROVIDER_ID, "GitHub sign in failed") }
            return
        }
        loading.value = true
        auth.startActivityForSignInWithProvider(this, provider.build())
            .addOnSuccessListener { loading.value = false; finishSignIn() }
            .addOnFailureListener {
                loading.value = false
                handleAuthFailure(it, null, GithubAuthProvider.PROVIDER_ID, "GitHub sign in failed")
            }
    }

    // ── Account linking ───────────────────────────────────────────────────────────

    private fun handleAuthFailure(e: Exception?, attempted: AuthCredential?, attemptedProviderId: String, fallback: String) {
        if (e !is FirebaseAuthUserCollisionException) {
            errorMsg.value = e?.message ?: fallback
            return
        }
        pendingCredential = attempted ?: e.updatedCredential
        val email = e.email
        if (pendingCredential == null || email == null) {
            errorMsg.value = e.message ?: fallback
            return
        }
        loading.value = true
        auth.fetchSignInMethodsForEmail(email).addOnCompleteListener { t ->
            loading.value = false
            val methods = if (t.isSuccessful) t.result?.signInMethods else null
            promptExistingProvider(email, methods, attemptedProviderId)
        }
    }

    private fun promptExistingProvider(email: String, methods: List<String>?, attemptedProviderId: String) {
        // fetchSignInMethodsForEmail returns empty when email-enumeration protection is on,
        // so fall back to offering every provider except the one just attempted.
        val candidates = if (!methods.isNullOrEmpty()) methods
        else listOf(EmailAuthProvider.PROVIDER_ID, GoogleAuthProvider.PROVIDER_ID, GithubAuthProvider.PROVIDER_ID)

        val ids = candidates.filter { it != attemptedProviderId }
        if (ids.size == 1) { reAuthWith(ids[0], email); return }

        val labels = ids.map { providerLabel(it) }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("Account already exists")
            .setMessage("The email $email is already registered. Sign in with your original method to link this account.")
            .setItems(labels) { _, which -> reAuthWith(ids[which], email) }
            .setNegativeButton("Cancel") { _, _ -> pendingCredential = null }
            .show()
    }

    private fun reAuthWith(providerId: String, email: String) = when (providerId) {
        EmailAuthProvider.PROVIDER_ID -> promptPasswordThenLink(email)
        GoogleAuthProvider.PROVIDER_ID -> googleLauncher.launch(googleClient.signInIntent)
        GithubAuthProvider.PROVIDER_ID -> githubSignIn()
        else -> { pendingCredential = null }
    }

    private fun promptPasswordThenLink(email: String) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "Password"
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Sign in to link account")
            .setMessage("Enter the password for $email")
            .setView(input)
            .setPositiveButton("Sign in") { _, _ ->
                loading.value = true
                auth.signInWithEmailAndPassword(email, input.text.toString()).addOnCompleteListener { t ->
                    loading.value = false
                    if (t.isSuccessful) finishSignIn() else errorMsg.value = t.exception?.message ?: "Sign in failed"
                }
            }
            .setNegativeButton("Cancel") { _, _ -> pendingCredential = null }
            .show()
    }

    // ── Finish ─────────────────────────────────────────────────────────────────

    private fun finishSignIn() {
        val user = auth.currentUser
        val toLink = pendingCredential
        if (user != null && toLink != null) {
            pendingCredential = null
            loading.value = true
            user.linkWithCredential(toLink).addOnCompleteListener {
                loading.value = false
                goToMain() // linking is best-effort; user is signed in either way
            }
            return
        }
        goToMain()
    }

    private fun providerLabel(id: String) = when (id) {
        EmailAuthProvider.PROVIDER_ID -> "Email & password"
        GoogleAuthProvider.PROVIDER_ID -> "Google"
        GithubAuthProvider.PROVIDER_ID -> "GitHub"
        else -> id
    }

    // Routes to onboarding on first login (no @tag yet), otherwise straight to the map.
    // Local per-uid cache avoids a Firestore read on every launch; a cache miss falls back
    // to Firestore. On network error fetchTag returns null → onboarding, whose claim is
    // idempotent, so an existing user re-claiming their own tag still succeeds.
    private fun goToMain() {
        val user = auth.currentUser ?: run { launch(com.usc.myway.MainActivity::class.java); return }
        val app = application as App
        if (app.getUserTag(user.uid).isNotEmpty()) { launch(com.usc.myway.MainActivity::class.java); return }
        loading.value = true
        Profiles.fetchTag(user.uid) { tag ->
            loading.value = false
            if (tag != null) { app.setUserTag(user.uid, tag); launch(com.usc.myway.MainActivity::class.java) }
            else launch(OnboardingActivity::class.java)
        }
    }

    private fun launch(cls: Class<*>) {
        startActivity(Intent(this, cls))
        finish()
    }
}

@Composable
fun LoginScreen(
    loading: Boolean,
    error: String?,
    onLogin: (String, String) -> Unit,
    onGoogle: () -> Unit,
    onGithub: () -> Unit,
    onRegister: () -> Unit,
    onForgotPassword: (String) -> Unit,
    resetSentTo: String?,
    onDismissResetSent: () -> Unit,
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var emailErr by remember { mutableStateOf<String?>(null) }
    var passErr by remember { mutableStateOf<String?>(null) }
    var showForgot by remember { mutableStateOf(false) }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("MyWay", fontSize = 40.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp, bottom = 40.dp)) {
                Text(
                    "Find your way, together ",
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

            AuthTextField(
                value = email,
                onValueChange = { email = it; emailErr = null },
                label = "Email",
                keyboardType = KeyboardType.Email,
                error = emailErr,
                enabled = !loading,
            )
            Spacer(Modifier.height(16.dp))
            AuthTextField(
                value = password,
                onValueChange = { password = it; passErr = null },
                label = "Password",
                isPassword = true,
                error = passErr,
                enabled = !loading,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { showForgot = true }, enabled = !loading) { Text("Forgot password?") }
            }

            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, fontSize = 14.sp,
                    modifier = Modifier.padding(top = 12.dp))
            }

            Button(
                onClick = {
                    emailErr = if (!Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches()) "Enter a valid email" else null
                    passErr = if (password.isEmpty()) "Enter your password" else null
                    if (emailErr == null && passErr == null) onLogin(email.trim(), password)
                },
                enabled = !loading,
                modifier = Modifier.padding(top = 28.dp).fillMaxWidth().height(56.dp),
            ) {
                if (loading) CircularProgressIndicator(modifier = Modifier.height(22.dp), strokeWidth = 2.dp)
                else Text("Sign in", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }

            Text(
                "or continue with",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(vertical = 20.dp),
            )
            SocialButton("Google", R.drawable.ic_google, onGoogle, enabled = !loading)
            Spacer(Modifier.height(12.dp))
            SocialButton("GitHub", R.drawable.ic_github, onGithub, iconTint = MaterialTheme.colorScheme.onSurface, enabled = !loading)

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.padding(top = 24.dp),
            ) {
                Text("Don't have an account?", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                TextButton(onClick = onRegister, enabled = !loading) {
                    Text("Register", fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    if (showForgot) {
        ForgotPasswordDialog(
            initialEmail = email,
            loading = loading,
            onDismiss = { showForgot = false },
            onSend = { onForgotPassword(it); showForgot = false },
        )
    }

    resetSentTo?.let { ResetSentDialog(it, onDismissResetSent) }
}

@Composable
private fun ForgotPasswordDialog(
    initialEmail: String,
    loading: Boolean,
    onDismiss: () -> Unit,
    onSend: (String) -> Unit,
) {
    var email by remember { mutableStateOf(initialEmail) }
    val valid = Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches()

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        icon = { Icon(Icons.Outlined.VpnKey, contentDescription = null, modifier = Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary) },
        title = { Text("Reset your password", fontWeight = FontWeight.Bold, textAlign = TextAlign.Center) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "Enter the email you signed up with. We'll send you a link to set a new password.",
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
                Spacer(Modifier.height(20.dp))
                AuthTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = "Email",
                    keyboardType = KeyboardType.Email,
                    enabled = !loading,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSend(email.trim()) },
                enabled = valid && !loading,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().height(50.dp),
            ) {
                if (loading) CircularProgressIndicator(Modifier.height(20.dp), strokeWidth = 2.dp)
                else Text("Send reset link", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !loading, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
        },
    )
}

@Composable
private fun ResetSentDialog(email: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        icon = { Icon(Icons.Outlined.MarkEmailRead, contentDescription = null, modifier = Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary) },
        title = { Text("Check your inbox", fontWeight = FontWeight.Bold, textAlign = TextAlign.Center) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "If an account exists for",
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
                Text(email, fontSize = 15.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(vertical = 6.dp))
                Text(
                    "we've sent it a link to reset the password. The link expires in 1 hour — check your spam folder if it doesn't arrive.",
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().height(50.dp),
            ) { Text("Got it", fontWeight = FontWeight.Bold) }
        },
    )
}
