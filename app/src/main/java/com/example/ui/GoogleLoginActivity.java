package com.example.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.example.R;
import com.example.ads.AppOpenAdManager;
import com.example.auth.AdminConfig;
import com.example.auth.GoogleAuthManager;
import com.example.data.CloudSyncManager;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Pure Java dedicated screen allowing sign-in exclusively via Google Account
 * (Jetpack Credential Manager + Firebase Auth), cross-device cloud sync,
 * and Admin Dashboard access authorized via Firestore RBAC (/roles/{uid}).
 */
public class GoogleLoginActivity extends AppCompatActivity {

    private View cardError;
    private TextView tvError;
    private View cardSignIn;
    private View cardAccount;
    private MaterialButton btnGoogleSignIn;
    private MaterialButton btnSyncDevices;
    private MaterialButton btnOpenAdmin;
    private MaterialButton btnSwitchAccount;
    private MaterialButton btnSignOut;
    private View badgeAdminAccount;
    private TextView tvSyncStatus;
    private View progressLogin;

    private TextView tvAvatarInitial;
    private TextView tvDisplayName;
    private TextView tvEmail;
    private View layoutAccountUid;
    private TextView tvUid;
    @Nullable
    private String currentUid;

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
        btnSyncDevices = findViewById(R.id.btn_sync_devices);
        btnOpenAdmin = findViewById(R.id.btn_open_admin_dashboard);
        btnSwitchAccount = findViewById(R.id.btn_switch_google_account);
        btnSignOut = findViewById(R.id.btn_google_sign_out);
        badgeAdminAccount = findViewById(R.id.badge_admin_account);
        tvSyncStatus = findViewById(R.id.tv_sync_status);
        progressLogin = findViewById(R.id.progress_google_login);

        tvAvatarInitial = findViewById(R.id.tv_google_avatar_initial);
        tvDisplayName = findViewById(R.id.tv_google_display_name);
        tvEmail = findViewById(R.id.tv_google_email);
        layoutAccountUid = findViewById(R.id.layout_account_uid);
        tvUid = findViewById(R.id.tv_google_uid);

        firebaseAuth = GoogleAuthManager.getFirebaseAuth(this);

        btnGoogleSignIn.setOnClickListener(v -> triggerGoogleSignIn());
        btnSyncDevices.setOnClickListener(v -> triggerCrossDeviceSync());
        btnOpenAdmin.setOnClickListener(v -> openAdminDashboard());
        btnSwitchAccount.setOnClickListener(v -> triggerGoogleSignIn());
        btnSignOut.setOnClickListener(v -> performSignOut());
        layoutAccountUid.setOnClickListener(v -> copyCurrentUid());

        FirebaseUser currentUser = GoogleAuthManager.getCurrentUser(this);
        renderUser(currentUser);
        if (currentUser != null) {
            CloudSyncManager.recordUserPresenceAsync(this);
        }
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
                CloudSyncManager.recordUserPresenceAsync(GoogleLoginActivity.this);
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

    private void triggerCrossDeviceSync() {
        if (isLoading) {
            return;
        }
        hideError();
        setLoading(true);
        btnSyncDevices.setText(R.string.action_syncing_devices);

        CloudSyncManager.syncAcrossDevices(this, new CloudSyncManager.SyncCallback() {
            @Override
            public void onSuccess(@NonNull CloudSyncManager.SyncResult result) {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                setLoading(false);
                btnSyncDevices.setText(R.string.action_sync_across_devices);
                updateSyncStatusText(result.getSyncedAtMs());
                Toast.makeText(
                        GoogleLoginActivity.this,
                        getString(
                                R.string.msg_sync_success,
                                result.getUploadedCount(),
                                result.getDownloadedCount(),
                                result.getTotalProxies()
                        ),
                        Toast.LENGTH_LONG
                ).show();
            }

            @Override
            public void onError(@NonNull String message) {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                setLoading(false);
                btnSyncDevices.setText(R.string.action_sync_across_devices);
                showError(getString(R.string.msg_sync_failed, message));
            }
        });
    }

    private void openAdminDashboard() {
        FirebaseUser current = GoogleAuthManager.getCurrentUser(this);
        if (current == null || TextUtils.isEmpty(current.getUid())) {
            Toast.makeText(this, R.string.msg_admin_access_denied, Toast.LENGTH_SHORT).show();
            return;
        }
        if (isLoading) {
            return;
        }
        String uid = current.getUid().trim();
        setLoading(true);
        AdminConfig.fetchAccessForUser(this, uid, new AdminConfig.RbacCallback() {
            @Override
            public void onResolved(@NonNull AdminConfig.RbacAccess access) {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                setLoading(false);
                if (!uid.equals(currentUid)) {
                    return;
                }
                applyRbacUi(access.canAccessAdminDashboard());
                if (!access.canAccessAdminDashboard()) {
                    Toast.makeText(GoogleLoginActivity.this, R.string.msg_admin_access_denied, Toast.LENGTH_SHORT).show();
                    return;
                }
                startActivity(new Intent(GoogleLoginActivity.this, AdminDashboardActivity.class));
            }

            @Override
            public void onError(@NonNull String message) {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                setLoading(false);
                showError(message);
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
            currentUid = null;
            AdminConfig.clearCache();
            applyRbacUi(false);
            cardSignIn.setVisibility(View.VISIBLE);
            cardAccount.setVisibility(View.GONE);
            return;
        }
        String displayName = !TextUtils.isEmpty(user.getDisplayName())
                ? user.getDisplayName()
                : GoogleAuthManager.getDisplayLabel(user, getString(R.string.title_google_login));
        String email = !TextUtils.isEmpty(user.getEmail()) ? user.getEmail() : "";
        long lastSyncMs = CloudSyncManager.getLastSyncTimestampMs(this, user.getUid());

        renderSignedInAccountForUi(user.getUid(), displayName, email, user.isEmailVerified(), lastSyncMs);
        refreshRbacAccessAsync(user.getUid());
    }

    @VisibleForTesting
    public void renderSignedInAccountForUi(
            @Nullable String uid,
            @Nullable String displayName,
            @Nullable String email,
            long lastSyncMs) {
        renderSignedInAccountForUi(uid, displayName, email, false, lastSyncMs);
    }

    @VisibleForTesting
    public void renderSignedInAccountForUi(
            @Nullable String uid,
            @Nullable String displayName,
            @Nullable String email,
            boolean emailVerified,
            long lastSyncMs) {
        cardSignIn.setVisibility(View.GONE);
        cardAccount.setVisibility(View.VISIBLE);

        String safeUid = uid != null ? uid.trim() : "";
        currentUid = safeUid;
        String safeName = !TextUtils.isEmpty(displayName)
                ? displayName.trim()
                : getString(R.string.title_google_login);
        String safeEmail = email != null ? email.trim() : "";

        String initialSource = !TextUtils.isEmpty(safeName) ? safeName : safeEmail;
        String initial = !TextUtils.isEmpty(initialSource)
                ? initialSource.substring(0, 1).toUpperCase(Locale.getDefault())
                : "G";

        tvAvatarInitial.setText(initial);
        tvDisplayName.setText(safeName);
        tvEmail.setText(safeEmail);
        tvEmail.setVisibility(TextUtils.isEmpty(safeEmail) ? View.GONE : View.VISIBLE);
        layoutAccountUid.setVisibility(TextUtils.isEmpty(safeUid) ? View.GONE : View.VISIBLE);
        if (!TextUtils.isEmpty(safeUid)) {
            tvUid.setText(getString(R.string.label_account_uid, safeUid));
        }

        boolean hasAdminAccess = !TextUtils.isEmpty(safeUid)
                && AdminConfig.getCachedAccess(safeUid).canAccessAdminDashboard();
        applyRbacUi(hasAdminAccess);
        btnSyncDevices.setVisibility(View.VISIBLE);
        updateSyncStatusText(lastSyncMs);
    }

    private void refreshRbacAccessAsync(@Nullable String uid) {
        if (TextUtils.isEmpty(uid)) {
            applyRbacUi(false);
            return;
        }
        final String targetUid = uid.trim();
        AdminConfig.fetchAccessForUser(this, targetUid, new AdminConfig.RbacCallback() {
            @Override
            public void onResolved(@NonNull AdminConfig.RbacAccess access) {
                if (isFinishing() || isDestroyed() || !targetUid.equals(currentUid)) {
                    return;
                }
                applyRbacUi(access.canAccessAdminDashboard());
            }

            @Override
            public void onError(@NonNull String message) {
                // Keep cached UI state quietly on transient offline errors
            }
        });
    }

    private void applyRbacUi(boolean hasAdminAccess) {
        badgeAdminAccount.setVisibility(hasAdminAccess ? View.VISIBLE : View.GONE);
        btnOpenAdmin.setVisibility(hasAdminAccess ? View.VISIBLE : View.GONE);
    }

    private void copyCurrentUid() {
        if (TextUtils.isEmpty(currentUid)) {
            return;
        }
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("Firebase UID", currentUid));
            Toast.makeText(this, getString(R.string.msg_uid_copied, currentUid), Toast.LENGTH_SHORT).show();
        }
    }

    private void updateSyncStatusText(long syncedAtMs) {
        if (syncedAtMs <= 0L) {
            tvSyncStatus.setText(R.string.label_never_synced);
            return;
        }
        String formatted = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, Locale.getDefault())
                .format(new Date(syncedAtMs));
        tvSyncStatus.setText(getString(R.string.label_last_sync_at, formatted));
    }

    private void setLoading(boolean loading) {
        isLoading = loading;
        progressLogin.setVisibility(loading ? View.VISIBLE : View.INVISIBLE);
        btnGoogleSignIn.setEnabled(!loading);
        btnSyncDevices.setEnabled(!loading);
        btnOpenAdmin.setEnabled(!loading);
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
