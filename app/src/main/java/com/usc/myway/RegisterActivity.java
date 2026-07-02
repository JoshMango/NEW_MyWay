package com.usc.myway;

import android.os.Bundle;
import android.text.TextUtils;
import android.util.Patterns;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputLayout;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthUserCollisionException;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.UserProfileChangeRequest;

public class RegisterActivity extends AppCompatActivity {

    private FirebaseAuth auth;
    private TextInputLayout tilFirst, tilLast, tilEmail, tilPassword, tilConfirm;
    private MaterialButton btnRegister;
    private ProgressBar progress;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_register);
        auth = FirebaseAuth.getInstance();

        tilFirst = findViewById(R.id.til_first);
        tilLast = findViewById(R.id.til_last);
        tilEmail = findViewById(R.id.til_email);
        tilPassword = findViewById(R.id.til_password);
        tilConfirm = findViewById(R.id.til_confirm);
        btnRegister = findViewById(R.id.btn_register);
        progress = findViewById(R.id.progress);

        btnRegister.setOnClickListener(v -> register());
        findViewById(R.id.link_login).setOnClickListener(v -> finish());
    }

    private void register() {
        String first = text(tilFirst);
        String last = text(tilLast);
        String email = text(tilEmail);
        String password = text(tilPassword);
        String confirm = text(tilConfirm);

        tilFirst.setError(null); tilLast.setError(null);
        tilEmail.setError(null); tilPassword.setError(null); tilConfirm.setError(null);

        if (TextUtils.isEmpty(first)) { tilFirst.setError("Required"); return; }
        if (TextUtils.isEmpty(last)) { tilLast.setError("Required"); return; }
        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            tilEmail.setError("Enter a valid email"); return;
        }
        if (password.length() < 6) {
            tilPassword.setError("At least 6 characters"); return;
        }
        if (!password.equals(confirm)) {
            tilConfirm.setError("Passwords do not match"); return;
        }

        setLoading(true);
        auth.createUserWithEmailAndPassword(email, password)
                .addOnCompleteListener(this, task -> {
                    if (!task.isSuccessful()) {
                        setLoading(false);
                        Exception ex = task.getException();
                        if (ex instanceof FirebaseAuthUserCollisionException) {
                            tilEmail.setError("This email is already registered");
                        } else {
                            Toast.makeText(this,
                                    ex != null ? ex.getMessage() : "Registration failed",
                                    Toast.LENGTH_LONG).show();
                        }
                        return;
                    }
                    FirebaseUser user = auth.getCurrentUser();
                    if (user == null) { setLoading(false); return; }

                    UserProfileChangeRequest profile = new UserProfileChangeRequest.Builder()
                            .setDisplayName(first + " " + last)
                            .build();
                    user.updateProfile(profile)
                            .addOnCompleteListener(p -> user.sendEmailVerification()
                                    .addOnCompleteListener(v -> {
                                        setLoading(false);
                                        auth.signOut(); // force verify-then-login
                                        Toast.makeText(this,
                                                "Verification email sent. Verify it, then sign in.",
                                                Toast.LENGTH_LONG).show();
                                        finish(); // back to login
                                    }));
                });
    }

    private void setLoading(boolean loading) {
        progress.setVisibility(loading ? View.VISIBLE : View.GONE);
        btnRegister.setEnabled(!loading);
    }

    private String text(TextInputLayout til) {
        return til.getEditText() == null ? "" : til.getEditText().getText().toString().trim();
    }
}
