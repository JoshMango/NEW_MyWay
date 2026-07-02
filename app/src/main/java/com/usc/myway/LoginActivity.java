package com.usc.myway;

import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.util.Patterns;
import android.view.View;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.tasks.Task;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputLayout;
import com.google.firebase.auth.AuthCredential;
import com.google.firebase.auth.EmailAuthProvider;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthUserCollisionException;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.GithubAuthProvider;
import com.google.firebase.auth.GoogleAuthProvider;
import com.google.firebase.auth.OAuthProvider;

import java.util.List;

public class LoginActivity extends AppCompatActivity {

    // ponytail: classic GoogleSignIn API — deprecated but simplest that works.
    // Upgrade path: androidx.credentials CredentialManager when it becomes worth the extra setup.
    private static final int RC_GOOGLE = 9001;

    private FirebaseAuth auth;
    private GoogleSignInClient googleClient;
    private TextInputLayout tilEmail, tilPassword;
    private MaterialButton btnLogin;
    private ProgressBar progress;

    // Set when a sign-in collided with an existing account; linked once the user
    // re-authenticates with their original provider. Null in the normal flow.
    private AuthCredential pendingCredential;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        auth = FirebaseAuth.getInstance();

        // Already signed in AND verified → skip straight to the app.
        FirebaseUser current = auth.getCurrentUser();
        if (current != null && current.isEmailVerified()) {
            goToMain();
            return;
        }

        setContentView(R.layout.activity_login);
        tilEmail = findViewById(R.id.til_email);
        tilPassword = findViewById(R.id.til_password);
        btnLogin = findViewById(R.id.btn_login);
        progress = findViewById(R.id.progress);

        GoogleSignInOptions gso = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestIdToken(getString(R.string.default_web_client_id))
                .requestEmail()
                .build();
        googleClient = GoogleSignIn.getClient(this, gso);

        btnLogin.setOnClickListener(v -> login());
        findViewById(R.id.btn_google).setOnClickListener(v -> googleSignIn());
        findViewById(R.id.btn_github).setOnClickListener(v -> githubSignIn());
        findViewById(R.id.link_register).setOnClickListener(v ->
                startActivity(new Intent(this, RegisterActivity.class)));
    }

    // ── Email / password ──────────────────────────────────────────────────────

    private void login() {
        String email = text(tilEmail);
        String password = text(tilPassword);

        tilEmail.setError(null);
        tilPassword.setError(null);
        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            tilEmail.setError("Enter a valid email");
            return;
        }
        if (TextUtils.isEmpty(password)) {
            tilPassword.setError("Enter your password");
            return;
        }

        setLoading(true);
        auth.signInWithEmailAndPassword(email, password)
                .addOnCompleteListener(this, task -> {
                    setLoading(false);
                    if (!task.isSuccessful()) {
                        toastError(task.getException(), "Sign in failed");
                        return;
                    }
                    FirebaseUser user = auth.getCurrentUser();
                    if (user != null && user.isEmailVerified()) {
                        finishSignIn();
                    } else {
                        promptVerify(user);
                    }
                });
    }

    private void promptVerify(FirebaseUser user) {
        auth.signOut();
        new AlertDialog.Builder(this)
                .setTitle("Verify your email")
                .setMessage("Please verify your email before signing in. Check your inbox for the verification link.")
                .setPositiveButton("Resend", (d, w) -> {
                    if (user != null) {
                        user.sendEmailVerification();
                        Toast.makeText(this, "Verification email sent", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("OK", null)
                .show();
    }

    // ── Google ─────────────────────────────────────────────────────────────────

    private void googleSignIn() {
        startActivityForResult(googleClient.getSignInIntent(), RC_GOOGLE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != RC_GOOGLE) return;
        Task<GoogleSignInAccount> task = GoogleSignIn.getSignedInAccountFromIntent(data);
        try {
            GoogleSignInAccount account = task.getResult(ApiException.class);
            AuthCredential credential = GoogleAuthProvider.getCredential(account.getIdToken(), null);
            setLoading(true);
            auth.signInWithCredential(credential).addOnCompleteListener(this, t -> {
                setLoading(false);
                if (t.isSuccessful()) finishSignIn();
                else handleAuthFailure(t.getException(), credential, GoogleAuthProvider.PROVIDER_ID, "Google sign in failed");
            });
        } catch (ApiException e) {
            toastError(e, "Google sign in failed");
        }
    }

    // ── GitHub (Firebase-hosted OAuth, no extra SDK) ─────────────────────────────

    private void githubSignIn() {
        OAuthProvider.Builder provider = OAuthProvider.newBuilder("github.com");

        Task<com.google.firebase.auth.AuthResult> pending = auth.getPendingAuthResult();
        if (pending != null) {
            pending.addOnSuccessListener(r -> finishSignIn())
                    .addOnFailureListener(e -> handleAuthFailure(e, null, GithubAuthProvider.PROVIDER_ID, "GitHub sign in failed"));
            return;
        }

        setLoading(true);
        auth.startActivityForSignInWithProvider(this, provider.build())
                .addOnSuccessListener(r -> { setLoading(false); finishSignIn(); })
                .addOnFailureListener(e -> {
                    setLoading(false);
                    handleAuthFailure(e, null, GithubAuthProvider.PROVIDER_ID, "GitHub sign in failed");
                });
    }

    // ── Account linking ──────────────────────────────────────────────────────────

    private void handleAuthFailure(Exception e, @Nullable AuthCredential attempted,
                                   String attemptedProviderId, String fallback) {
        if (!(e instanceof FirebaseAuthUserCollisionException)) {
            toastError(e, fallback);
            return;
        }
        FirebaseAuthUserCollisionException col = (FirebaseAuthUserCollisionException) e;
        // Credential to link later: the one we built (Google) or the one Firebase hands back (GitHub OAuth).
        pendingCredential = attempted != null ? attempted : col.getUpdatedCredential();
        String email = col.getEmail();
        if (pendingCredential == null || email == null) {
            toastError(e, fallback);
            return;
        }
        setLoading(true);
        auth.fetchSignInMethodsForEmail(email).addOnCompleteListener(t -> {
            setLoading(false);
            List<String> methods = t.isSuccessful() && t.getResult() != null
                    ? t.getResult().getSignInMethods() : null;
            promptExistingProvider(email, methods, attemptedProviderId);
        });
    }

    private void promptExistingProvider(String email, @Nullable List<String> methods, String attemptedProviderId) {
        // fetchSignInMethodsForEmail can return empty when email-enumeration protection is on,
        // so fall back to offering every provider except the one just attempted.
        final String[] candidates;
        if (methods != null && !methods.isEmpty()) {
            candidates = methods.toArray(new String[0]);
        } else {
            candidates = new String[]{EmailAuthProvider.PROVIDER_ID,
                    GoogleAuthProvider.PROVIDER_ID, GithubAuthProvider.PROVIDER_ID};
        }

        java.util.ArrayList<String> ids = new java.util.ArrayList<>();
        java.util.ArrayList<String> labels = new java.util.ArrayList<>();
        for (String id : candidates) {
            if (id.equals(attemptedProviderId)) continue;
            ids.add(id);
            labels.add(providerLabel(id));
        }
        if (ids.size() == 1) {
            reAuthWith(ids.get(0), email);
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Account already exists")
                .setMessage("The email " + email + " is already registered. Sign in with your original method to link this account.")
                .setItems(labels.toArray(new String[0]),
                        (d, which) -> reAuthWith(ids.get(which), email))
                .setNegativeButton("Cancel", (d, w) -> pendingCredential = null)
                .show();
    }

    private void reAuthWith(String providerId, String email) {
        switch (providerId) {
            case EmailAuthProvider.PROVIDER_ID: promptPasswordThenLink(email); break;
            case GoogleAuthProvider.PROVIDER_ID: googleSignIn(); break;
            case GithubAuthProvider.PROVIDER_ID: githubSignIn(); break;
            default: pendingCredential = null;
        }
    }

    private void promptPasswordThenLink(String email) {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setHint("Password");
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        input.setPadding(pad, pad, pad, pad);

        new AlertDialog.Builder(this)
                .setTitle("Sign in to link account")
                .setMessage("Enter the password for " + email)
                .setView(input)
                .setPositiveButton("Sign in", (d, w) -> {
                    setLoading(true);
                    auth.signInWithEmailAndPassword(email, input.getText().toString())
                            .addOnCompleteListener(t -> {
                                setLoading(false);
                                if (t.isSuccessful()) finishSignIn();
                                else toastError(t.getException(), "Sign in failed");
                            });
                })
                .setNegativeButton("Cancel", (d, w) -> pendingCredential = null)
                .show();
    }

    // ── Finish ─────────────────────────────────────────────────────────────────

    private void finishSignIn() {
        FirebaseUser user = auth.getCurrentUser();
        if (user != null && pendingCredential != null) {
            AuthCredential toLink = pendingCredential;
            pendingCredential = null;
            setLoading(true);
            user.linkWithCredential(toLink).addOnCompleteListener(t -> {
                setLoading(false);
                if (!t.isSuccessful()) {
                    // Linking is best-effort; the user is still signed in, so continue.
                    Toast.makeText(this, "Signed in (couldn't link the other provider)", Toast.LENGTH_SHORT).show();
                }
                goToMain();
            });
            return;
        }
        goToMain();
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private String providerLabel(String providerId) {
        switch (providerId) {
            case EmailAuthProvider.PROVIDER_ID: return "Email & password";
            case GoogleAuthProvider.PROVIDER_ID: return "Google";
            case GithubAuthProvider.PROVIDER_ID: return "GitHub";
            default: return providerId;
        }
    }

    private void goToMain() {
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }

    private void setLoading(boolean loading) {
        progress.setVisibility(loading ? View.VISIBLE : View.GONE);
        btnLogin.setEnabled(!loading);
    }

    private void toastError(Exception e, String fallback) {
        Toast.makeText(this, e != null ? e.getMessage() : fallback, Toast.LENGTH_LONG).show();
    }

    private String text(TextInputLayout til) {
        return til.getEditText() == null ? "" : til.getEditText().getText().toString().trim();
    }
}
