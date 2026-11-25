package com.example.ui;

import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.example.R;
import com.example.ads.AppOpenAdManager;
import com.example.auth.GoogleAuthManager;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

import java.util.Locale;

/**
 * Pure Java dedicated screen allowing sign-in exclusively via Google Account
 * (Jetpack Credential Manager + Firebase Auth), as well as switching Google accounts or signing out.
 */
public class GoogleLoginActivity extends AppCompatActivity {

    private View cardError;
    private TextView tvError;
    private View cardSignIn;
    private View cardAccount;
    private MaterialButton btnGoogleSignIn;
    private MaterialButton btnSwitchAccount;
    private MaterialButton btnSignOut;
    private View progressLogin;

    private TextView tvAvatarInitial;
    private TextView tvDisplayName;
    private TextView tvEmail;
    private TextView tvUid;

    @Nullable
    private FirebaseAuth firebaseAuth;
    private final FirebaseAuth.AuthStateListener authStateListener = auth -> renderUser(auth.getCurrentUser());

    private boolean isLoading = false;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        EdgeToEdge.enable(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_google_login);

        MaterialToolbar toolbar = findViewById(R.id.google_login_toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        View root = findViewById(R.id.google_login_root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets sysBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            toolbar.setPadding(toolbar.getPaddingLeft(), sysBars.top, toolbar.getPaddingRight(), toolbar.getPaddingBottom());
            root.setPadding(sysBars.left, 0, sysBars.right, sysBars.bottom);
            return WindowInsetsCompat.CONSUMED;
        });

        cardError = findViewById(R.id.card_google_login_error);
        tvError = findViewById(R.id.tv_google_login_error);
        cardSignIn = findViewById(R.id.card_google_sign_in);
        cardAccount = findViewById(R.id.card_google_account);
        btnGoogleSignIn = findViewById(R.id.btn_google_sign_in);
        btnSwitchAccount = findViewById(R.id.btn_switch_google_account);
        btnSignOut = findViewById(R.id.btn_google_sign_out);
        progressLogin = findViewById(R.id.progress_google_login);

        tvAvatarInitial = findViewById(R.id.tv_google_avatar_initial);
        tvDisplayName = findViewById(R.id.tv_google_display_name);
        tvEmail = findViewById(R.id.tv_google_email);
        tvUid = findViewById(R.id.tv_google_uid);

        firebaseAuth = GoogleAuthManager.getFirebaseAuth(this);

        btnGoogleSignIn.setOnClickListener(v -> triggerGoogleSignIn());
        btnSwitchAccount.setOnClickListener(v -> triggerGoogleSignIn());
        btnSignOut.setOnClickListener(v -> performSignOut());

        renderUser(GoogleAuthManager.getCurrentUser(this));
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (firebaseAuth != null) {
            firebaseAuth.addAuthStateListener(authStateListener);
        }
    }

    @Override
    protected void onStop() {
        if (firebaseAuth != null) {
            firebaseAuth.removeAuthStateListener(authStateListener);
        }
        super.onStop();
    }

    private void triggerGoogleSignIn() {
        if (isLoading) {
            return;
        }
        hideError();
        setLoading(true);
        AppOpenAdManager.getInstance(this).suppressNextForegroundOpen();
        GoogleAuthManager.signInWithGoogle(this, new GoogleAuthManager.AuthCallback() {
            @Override
            public void onSuccess(@NonNull FirebaseUser user) {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                setLoading(false);
                renderUser(user);
                String label = !TextUtils.isEmpty(user.getEmail())
                        ? user.getEmail()
                        : GoogleAuthManager.getDisplayLabel(user, getString(R.string.title_google_login));
                Toast.makeText(
                        GoogleLoginActivity.this,
                        getString(R.string.msg_google_login_success, label),
                        Toast.LENGTH_SHORT
                ).show();
            }

            @Override
            public void onCancelled() {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                setLoading(false);
                Toast.makeText(
                        GoogleLoginActivity.this,
                        R.string.msg_google_login_cancelled,
                        Toast.LENGTH_SHORT
                ).show();
            }

            @Override
            public void onError(@NonNull String message) {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                setLoading(false);
                showError(getString(R.string.msg_google_login_failed, message));
            }
        });
    }

    private void performSignOut() {
        if (isLoading) {
            return;
        }
        hideError();
        setLoading(true);
        GoogleAuthManager.signOut(this, () -> {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            setLoading(false);
            renderUser(null);
            Toast.makeText(GoogleLoginActivity.this, R.string.msg_signed_out, Toast.LENGTH_SHORT).show();
        });
    }

    private void renderUser(@Nullable FirebaseUser user) {
        if (user == null) {
            cardSignIn.setVisibility(View.VISIBLE);
            cardAccount.setVisibility(View.GONE);
            return;
        }
        cardSignIn.setVisibility(View.GONE);
        cardAccount.setVisibility(View.VISIBLE);

        String displayName = !TextUtils.isEmpty(user.getDisplayName())
                ? user.getDisplayName()
                : GoogleAuthManager.getDisplayLabel(user, getString(R.string.title_google_login));
        String email = !TextUtils.isEmpty(user.getEmail()) ? user.getEmail() : "";

        String initialSource = !TextUtils.isEmpty(displayName) ? displayName : email;
        String initial = !TextUtils.isEmpty(initialSource)
                ? initialSource.substring(0, 1).toUpperCase(Locale.getDefault())
                : "G";

        tvAvatarInitial.setText(initial);
        tvDisplayName.setText(displayName);
        tvEmail.setText(email);
        tvEmail.setVisibility(TextUtils.isEmpty(email) ? View.GONE : View.VISIBLE);
        tvUid.setText(getString(R.string.label_google_uid, user.getUid()));
    }

    private void setLoading(boolean loading) {
        isLoading = loading;
        progressLogin.setVisibility(loading ? View.VISIBLE : View.INVISIBLE);
        btnGoogleSignIn.setEnabled(!loading);
        btnSwitchAccount.setEnabled(!loading);
        btnSignOut.setEnabled(!loading);
    }

    private void showError(@NonNull String message) {
        tvError.setText(message);
        cardError.setVisibility(View.VISIBLE);
    }

    private void hideError() {
        cardError.setVisibility(View.GONE);
    }
}
