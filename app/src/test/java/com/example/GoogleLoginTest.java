package com.example;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;

import com.example.auth.GoogleAuthManager;
import com.example.data.ProxyRepository;
import com.example.ui.GoogleLoginActivity;
import com.example.ui.SettingsActivity;
import com.example.ui.SettingsUiHelper;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;

import com.example.auth.AdminConfig;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;

import java.util.Arrays;
import java.util.Collections;

/**
 * Robolectric tests verifying top-bar Login action (replacing Settings),
 * minimal Google-only login screen rendering, and no auth pollution in proxy logs.
 */
@RunWith(RobolectricTestRunner.class)
public class GoogleLoginTest {

    @After
    public void tearDown() {
        AdminConfig.clearCache();
    }

    @Test
    public void googleServicesWebClientId_isGeneratedFromGoogleServicesJson() {
        Context context = ApplicationProvider.getApplicationContext();
        String clientId = context.getString(R.string.default_web_client_id);
        assertNotNull(clientId);
        assertFalse(clientId.trim().isEmpty());
        assertTrue(clientId.contains(".apps.googleusercontent.com"));
    }

    @Test
    public void mainActivity_topBarShowsLoginInsteadOfSettings_andLaunchesGoogleLoginActivity() {
        MainActivity activity = Robolectric.buildActivity(MainActivity.class)
                .setup()
                .get();

        MaterialToolbar toolbar = activity.findViewById(R.id.top_app_bar);
        assertNotNull(toolbar);

        assertEquals(1, toolbar.getMenu().size());
        MenuItem loginItem = toolbar.getMenu().findItem(R.id.action_google_login);
        assertNotNull(loginItem);
        assertEquals(R.id.action_google_login, toolbar.getMenu().getItem(0).getItemId());
        assertNotNull(loginItem.getActionView());

        View btnTopLogin = loginItem.getActionView().findViewById(R.id.btn_top_login);
        assertNotNull(btnTopLogin);
        btnTopLogin.performClick();

        Intent nextIntent = Shadows.shadowOf(activity).getNextStartedActivity();
        assertNotNull(nextIntent);
        assertNotNull(nextIntent.getComponent());
        assertEquals(GoogleLoginActivity.class.getName(), nextIntent.getComponent().getClassName());
    }

    @Test
    public void googleLoginActivity_showsOnlyIconTitleAndContinueWithGoogleButton() {
        GoogleLoginActivity activity = Robolectric.buildActivity(GoogleLoginActivity.class)
                .setup()
                .get();

        ViewGroup signInCard = activity.findViewById(R.id.card_google_sign_in);
        View accountCard = activity.findViewById(R.id.card_google_account);
        View logo = activity.findViewById(R.id.iv_google_login_logo);
        TextView title = activity.findViewById(R.id.tv_google_login_title);
        MaterialButton btnSignIn = activity.findViewById(R.id.btn_google_sign_in);

        assertNotNull(signInCard);
        assertNotNull(accountCard);
        assertNotNull(logo);
        assertNotNull(title);
        assertNotNull(btnSignIn);

        assertEquals(View.VISIBLE, signInCard.getVisibility());
        assertEquals(View.GONE, accountCard.getVisibility());

        ViewGroup cardContent = (ViewGroup) signInCard.getChildAt(0);
        assertEquals(3, cardContent.getChildCount());
        assertEquals("Continue with Google", btnSignIn.getText().toString());
        assertEquals("Login", GoogleAuthManager.getDisplayLabel(null, "Login"));
    }

    @Test
    public void googleSignOut_doesNotWriteNonProxyLogs() {
        Context context = ApplicationProvider.getApplicationContext();
        ProxyRepository repository = ProxyRepository.getInstance(context);
        repository.clearLogs();
        int initialCount = repository.getLogs().getValue() != null
                ? repository.getLogs().getValue().size()
                : 0;

        GoogleAuthManager.signOut(context, null);

        int afterCount = repository.getLogs().getValue() != null
                ? repository.getLogs().getValue().size()
                : 0;
        assertEquals(initialCount, afterCount);
    }

    @Test
    public void googleLoginActivity_toolbarShowsAccountTitle_andAccountCardShowsCopyableUid() {
        GoogleLoginActivity activity = Robolectric.buildActivity(GoogleLoginActivity.class)
                .setup()
                .get();

        MaterialToolbar toolbar = activity.findViewById(R.id.google_login_toolbar);
        assertNotNull(toolbar);
        assertEquals(activity.getString(R.string.settings_group_account), String.valueOf(toolbar.getTitle()));

        ViewGroup accountCard = activity.findViewById(R.id.card_google_account);
        assertNotNull(accountCard);
        ViewGroup accountContent = (ViewGroup) accountCard.getChildAt(0);
        // Avatar frame, displayName, email, UID pill, verified pill, admin pill, sync button, sync status, admin button, switch button, sign out button
        assertEquals(11, accountContent.getChildCount());
    }

    @Test
    public void adminConfig_requiresDynamicFirestoreRbacRoleWithZeroHardcodedAccounts() {
        AdminConfig.clearCache();
        assertFalse(AdminConfig.getCachedAccess("any_arbitrary_uid").canAccessAdminDashboard());
        assertFalse(AdminConfig.getCachedAccess("").canAccessAdminDashboard());
        assertFalse(AdminConfig.getCachedAccess(null).canAccessAdminDashboard());

        AdminConfig.seedAccessForTesting(
                "uid_rbac_admin",
                AdminConfig.ROLE_ADMIN,
                Collections.singletonList(AdminConfig.PERM_ADMIN_DASHBOARD_VIEW)
        );
        assertTrue(AdminConfig.getCachedAccess("uid_rbac_admin").canAccessAdminDashboard());

        AdminConfig.seedAccessForTesting(
                "uid_rbac_mod",
                AdminConfig.ROLE_MODERATOR,
                Arrays.asList(
                        AdminConfig.PERM_ADMIN_DASHBOARD_VIEW,
                        AdminConfig.PERM_ADMIN_USERS_LIST
                )
        );
        assertTrue(AdminConfig.getCachedAccess("uid_rbac_mod").canAccessAdminDashboard());
        assertFalse(AdminConfig.getCachedAccess("uid_rbac_mod").isAdmin());

        AdminConfig.seedAccessForTesting("uid_rbac_viewer", AdminConfig.ROLE_USER, Collections.emptyList());
        assertFalse(AdminConfig.getCachedAccess("uid_rbac_viewer").canAccessAdminDashboard());

        AdminConfig.seedAccessForTesting(
                "uid_rbac_inactive_admin",
                AdminConfig.ROLE_ADMIN,
                false,
                Collections.singletonList(AdminConfig.PERM_ADMIN_DASHBOARD_VIEW)
        );
        assertFalse(AdminConfig.getCachedAccess("uid_rbac_inactive_admin").isAdmin());
        assertFalse(AdminConfig.getCachedAccess("uid_rbac_inactive_admin").canAccessAdminDashboard());
    }

    @Test
    public void googleLoginActivity_regularUserShowsSyncAndHidesAdmin_rbacGrantedRoleShowsBoth() {
        GoogleLoginActivity activity = Robolectric.buildActivity(GoogleLoginActivity.class)
                .setup()
                .get();

        View btnSync = activity.findViewById(R.id.btn_sync_devices);
        View btnAdmin = activity.findViewById(R.id.btn_open_admin_dashboard);
        View badgeAdmin = activity.findViewById(R.id.badge_admin_account);
        TextView tvUid = activity.findViewById(R.id.tv_google_uid);
        assertNotNull(btnSync);
        assertNotNull(btnAdmin);
        assertNotNull(badgeAdmin);
        assertNotNull(tvUid);

        // Regular user without RBAC admin role
        activity.renderSignedInAccountForUi("regular_uid_001", "Alice", "alice@example.com", true, 0L);
        assertEquals(View.VISIBLE, btnSync.getVisibility());
        assertEquals(View.GONE, btnAdmin.getVisibility());
        assertEquals(View.GONE, badgeAdmin.getVisibility());
        assertTrue(tvUid.getText().toString().contains("regular_uid_001"));

        // Account granted RBAC 'admin' role in Firestore
        AdminConfig.seedAccessForTesting(
                "rbac_uid_admin",
                AdminConfig.ROLE_ADMIN,
                Collections.singletonList(AdminConfig.PERM_ADMIN_DASHBOARD_VIEW)
        );
        activity.renderSignedInAccountForUi("rbac_uid_admin", "Admin One", "admin1@example.com", true, 1700000000000L);
        assertEquals(View.VISIBLE, btnSync.getVisibility());
        assertEquals(View.VISIBLE, btnAdmin.getVisibility());
        assertEquals(View.VISIBLE, badgeAdmin.getVisibility());

        // Account with role == 'admin' but active == false MUST be denied
        AdminConfig.seedAccessForTesting(
                "rbac_uid_inactive_admin",
                AdminConfig.ROLE_ADMIN,
                false,
                Collections.singletonList(AdminConfig.PERM_ADMIN_DASHBOARD_VIEW)
        );
        activity.renderSignedInAccountForUi("rbac_uid_inactive_admin", "Suspended Admin", "suspended@example.com", true, 1700000000000L);
        assertEquals(View.VISIBLE, btnSync.getVisibility());
        assertEquals(View.GONE, btnAdmin.getVisibility());
        assertEquals(View.GONE, badgeAdmin.getVisibility());

        // Account granted RBAC 'moderator' role with admin.dashboard.view permission
        AdminConfig.seedAccessForTesting(
                "rbac_uid_mod",
                AdminConfig.ROLE_MODERATOR,
                Arrays.asList(AdminConfig.PERM_ADMIN_DASHBOARD_VIEW, AdminConfig.PERM_ADMIN_USERS_LIST)
        );
        activity.renderSignedInAccountForUi("rbac_uid_mod", "Mod Two", "mod2@example.com", true, 1700000000000L);
        assertEquals(View.VISIBLE, btnSync.getVisibility());
        assertEquals(View.VISIBLE, btnAdmin.getVisibility());
        assertEquals(View.VISIBLE, badgeAdmin.getVisibility());

        // Revoking RBAC role hides admin controls immediately
        AdminConfig.clearCache();
        activity.renderSignedInAccountForUi("rbac_uid_admin", "Admin One", "admin1@example.com", true, 1700000000000L);
        assertEquals(View.VISIBLE, btnSync.getVisibility());
        assertEquals(View.GONE, btnAdmin.getVisibility());
        assertEquals(View.GONE, badgeAdmin.getVisibility());
    }

    @Test
    public void adminStatsRepository_aggregatesUsersAndSyncEventsAccurately() {
        long now = 1_750_000_000_000L;
        java.util.List<com.example.data.AdminStatsRepository.UserStatsItem> users = java.util.Arrays.asList(
                new com.example.data.AdminStatsRepository.UserStatsItem(
                        "rbac_admin_1", "Admin", "a@b.com", 5, 4, 3, true, true, "Pixel 8", "3.6", now - 3600_000L, true),
                new com.example.data.AdminStatsRepository.UserStatsItem(
                        "user_2", "User 2", "u2@b.com", 2, 1, 1, false, false, "Galaxy S24", "3.6", now - 100_000_000L, false)
        );
        java.util.List<com.example.data.AdminStatsRepository.SyncEventItem> events = java.util.Collections.singletonList(
                new com.example.data.AdminStatsRepository.SyncEventItem("evt_1", "Admin", "Pixel 8", 2, 1, 5, "3.6", now - 1800_000L)
        );

        com.example.data.AdminStatsRepository.AdminDashboardData data =
                com.example.data.AdminStatsRepository.aggregateStats(users, events, now);

        assertEquals(2, data.getTotalUsers());
        assertEquals(1, data.getActiveUsers24h());
        assertEquals(7, data.getTotalSyncedProxies());
        assertEquals(5, data.getTotalWorkingProxies());
        assertEquals(4, data.getTotalSyncRuns());
        assertEquals(1, data.getFirewallEnabledUsers());
        assertEquals(1, data.getPrivateRoutingUsers());
        assertEquals(1, data.getSyncEvents24h());
    }

    @Test
    public void cloudSyncManager_buildsValidDeterministicProxyDocKey() {
        com.example.model.ProxyConfig p = new com.example.model.ProxyConfig();
        p.setProtocol(com.example.model.ProxyConfig.Protocol.SOCKS5);
        p.setHost("proxy.example.com");
        p.setPort(1080);

        String key1 = com.example.data.CloudSyncManager.buildProxyDocKey(p);
        String key2 = com.example.data.CloudSyncManager.buildProxyDocKey(p);

        assertEquals(key1, key2);
        assertTrue(key1.length() <= 128);
        assertTrue(key1.matches("^[a-zA-Z0-9_\\-]+$"));
    }

    @Test
    public void settingsScreen_showsSyncPromptWhenSignedOut_showsAccountWhenSignedIn_andOpensAccountPage() {
        SettingsActivity settingsActivity = Robolectric.buildActivity(SettingsActivity.class)
                .setup()
                .get();

        View rowAccount = settingsActivity.findViewById(R.id.row_setting_account);
        TextView tvTitle = settingsActivity.findViewById(R.id.tv_setting_account_title);
        TextView tvSubtitle = settingsActivity.findViewById(R.id.tv_setting_account_subtitle);
        View ivGoogle = settingsActivity.findViewById(R.id.iv_setting_account_google);
        View cardAvatar = settingsActivity.findViewById(R.id.card_setting_account_avatar);
        TextView tvAvatar = settingsActivity.findViewById(R.id.tv_setting_account_avatar);

        assertNotNull(rowAccount);
        assertNotNull(tvTitle);
        assertNotNull(tvSubtitle);
        assertEquals(View.VISIBLE, ivGoogle.getVisibility());
        assertEquals(View.GONE, cardAvatar.getVisibility());
        assertEquals(
                settingsActivity.getString(R.string.setting_account_sign_in_title),
                tvTitle.getText().toString()
        );
        assertEquals(
                settingsActivity.getString(R.string.setting_account_sign_in_subtitle),
                tvSubtitle.getText().toString()
        );

        rowAccount.performClick();
        Intent signedOutIntent = Shadows.shadowOf(settingsActivity).getNextStartedActivity();
        assertNotNull(signedOutIntent);
        assertNotNull(signedOutIntent.getComponent());
        assertEquals(GoogleLoginActivity.class.getName(), signedOutIntent.getComponent().getClassName());

        View root = settingsActivity.findViewById(R.id.settings_root);
        SettingsUiHelper.renderAccountState(
                settingsActivity,
                root,
                true,
                "Vu Tran",
                "vutran08012k3@gmail.com"
        );
        assertEquals(View.GONE, ivGoogle.getVisibility());
        assertEquals(View.VISIBLE, cardAvatar.getVisibility());
        assertEquals("V", tvAvatar.getText().toString());
        assertEquals("Vu Tran", tvTitle.getText().toString());
        assertEquals("vutran08012k3@gmail.com", tvSubtitle.getText().toString());

        rowAccount.performClick();
        Intent signedInIntent = Shadows.shadowOf(settingsActivity).getNextStartedActivity();
        assertNotNull(signedInIntent);
        assertNotNull(signedInIntent.getComponent());
        assertEquals(GoogleLoginActivity.class.getName(), signedInIntent.getComponent().getClassName());
    }

    @Test
    public void cloudSyncManager_initializesNamedFirestoreInstanceCleanly() {
        Context context = ApplicationProvider.getApplicationContext();
        com.google.firebase.firestore.FirebaseFirestore db =
                com.example.data.CloudSyncManager.getFirestore(context);
        assertNotNull(
                "Named Firestore instance should initialize cleanly: "
                        + com.example.data.CloudSyncManager.getLastFirestoreInitError(),
                db
        );
        assertEquals("", com.example.data.CloudSyncManager.getLastFirestoreInitError());
    }

    @Test
    public void firestoreErrorHelper_classifiesRetryableAndSurfacesUnavailableDiagnostics() {
        Context context = ApplicationProvider.getApplicationContext();

        com.google.firebase.firestore.FirebaseFirestoreException offlineEx =
                new com.google.firebase.firestore.FirebaseFirestoreException(
                        "Failed to get document because the client is offline.",
                        com.google.firebase.firestore.FirebaseFirestoreException.Code.UNAVAILABLE
                );
        assertTrue(com.example.data.FirestoreErrorHelper.isTransientRetryable(
                new java.util.concurrent.ExecutionException(offlineEx)
        ));
        String unavailableMsg = com.example.data.FirestoreErrorHelper.getFriendlyErrorMessage(context, offlineEx);
        assertTrue(unavailableMsg.contains(context.getString(R.string.msg_firestore_unavailable)));
        assertTrue(unavailableMsg.contains("client is offline"));

        assertTrue(com.example.data.FirestoreErrorHelper.isTransientRetryable(
                new java.util.concurrent.TimeoutException("Timed out")
        ));
        assertEquals(
                context.getString(R.string.msg_firestore_timeout),
                com.example.data.FirestoreErrorHelper.getFriendlyErrorMessage(
                        context, new java.util.concurrent.TimeoutException("Timed out")
                )
        );

        com.google.firebase.firestore.FirebaseFirestoreException genericUnavailableEx =
                new com.google.firebase.firestore.FirebaseFirestoreException(
                        "The service is currently unavailable.",
                        com.google.firebase.firestore.FirebaseFirestoreException.Code.UNAVAILABLE
                );
        String genericUnavailableMsg = com.example.data.FirestoreErrorHelper.getFriendlyErrorMessage(
                context, genericUnavailableEx
        );
        assertTrue(genericUnavailableMsg.contains(context.getString(R.string.msg_firestore_unavailable)));
        assertTrue(genericUnavailableMsg.contains("service is currently unavailable"));

        com.google.firebase.firestore.FirebaseFirestoreException unauthEx =
                new com.google.firebase.firestore.FirebaseFirestoreException(
                        "HTTP 401: Invalid auth token",
                        com.google.firebase.firestore.FirebaseFirestoreException.Code.UNAUTHENTICATED
                );
        assertTrue(com.example.data.FirestoreErrorHelper.getFriendlyErrorMessage(context, unauthEx)
                .contains("UNAUTHENTICATED"));

        com.google.firebase.firestore.FirebaseFirestoreException notFoundEx =
                new com.google.firebase.firestore.FirebaseFirestoreException(
                        "Database not found",
                        com.google.firebase.firestore.FirebaseFirestoreException.Code.NOT_FOUND
                );
        assertTrue(com.example.data.FirestoreErrorHelper.getFriendlyErrorMessage(context, notFoundEx)
                .contains("NOT_FOUND"));

        com.google.firebase.firestore.FirebaseFirestoreException permEx =
                new com.google.firebase.firestore.FirebaseFirestoreException(
                        "Missing or insufficient permissions.",
                        com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED
                );
        assertFalse(com.example.data.FirestoreErrorHelper.isTransientRetryable(permEx));
        assertEquals(
                context.getString(R.string.msg_firestore_permission_denied),
                com.example.data.FirestoreErrorHelper.getFriendlyErrorMessage(context, permEx)
        );
    }

    @Test
    public void firestoreRestClient_buildsCommitPayloadsAndDecodesDocumentsCorrectly() throws Exception {
        java.util.Map<String, Object> createFields = new java.util.HashMap<>();
        createFields.put("userId", "uid_123");
        createFields.put("syncCount", 1);
        createFields.put("createdAt", com.google.firebase.firestore.FieldValue.serverTimestamp());

        org.json.JSONObject createPayload = com.example.data.FirestoreRestClient.buildCommitPayload(
                "projects/p/databases/d/documents/users/uid_123",
                Boolean.FALSE,
                createFields,
                Arrays.asList("createdAt", "updatedAt", "lastSyncAt")
        );
        org.json.JSONObject createWrite = createPayload.getJSONArray("writes").getJSONObject(0);
        assertFalse(createWrite.getJSONObject("currentDocument").getBoolean("exists"));
        assertFalse(createWrite.has("updateMask"));
        assertEquals(3, createWrite.getJSONArray("updateTransforms").length());
        assertEquals(
                "REQUEST_TIME",
                createWrite.getJSONArray("updateTransforms").getJSONObject(0).getString("setToServerValue")
        );
        assertFalse(createWrite.getJSONObject("update").getJSONObject("fields").has("createdAt"));

        java.util.Map<String, Object> updateFields = new java.util.HashMap<>();
        updateFields.put("syncCount", 4);
        updateFields.put("firewallEnabled", true);
        updateFields.put("updatedAt", com.google.firebase.firestore.FieldValue.serverTimestamp());

        org.json.JSONObject updatePayload = com.example.data.FirestoreRestClient.buildCommitPayload(
                "projects/p/databases/d/documents/users/uid_123",
                Boolean.TRUE,
                updateFields,
                Arrays.asList("updatedAt", "lastSyncAt")
        );
        org.json.JSONObject updateWrite = updatePayload.getJSONArray("writes").getJSONObject(0);
        assertTrue(updateWrite.getJSONObject("currentDocument").getBoolean("exists"));
        assertTrue(updateWrite.has("updateMask"));
        org.json.JSONArray maskPaths = updateWrite.getJSONObject("updateMask").getJSONArray("fieldPaths");
        assertEquals(2, maskPaths.length());

        java.util.Map<String, Object> mergeFields = new java.util.HashMap<>();
        mergeFields.put("ads_enabled", false);
        mergeFields.put("updatedBy", "uid_admin");
        org.json.JSONObject mergePayload = com.example.data.FirestoreRestClient.buildCommitPayload(
                "projects/p/databases/d/documents/app_config/ads",
                null,
                mergeFields,
                Collections.singletonList("updatedAt")
        );
        org.json.JSONObject mergeWrite = mergePayload.getJSONArray("writes").getJSONObject(0);
        assertFalse(mergeWrite.has("currentDocument"));
        assertTrue(mergeWrite.has("updateMask"));
        assertEquals(1, mergeWrite.getJSONArray("updateTransforms").length());

        String sampleDocJson = "{"
                + "\"name\":\"projects/p/databases/d/documents/roles/admin_uid_1\","
                + "\"fields\":{"
                + "  \"role\":{\"stringValue\":\"admin\"},"
                + "  \"active\":{\"booleanValue\":true},"
                + "  \"syncCount\":{\"integerValue\":\"7\"},"
                + "  \"updatedAt\":{\"timestampValue\":\"2026-11-28T09:15:30.123456Z\"},"
                + "  \"permissions\":{\"arrayValue\":{\"values\":[{\"stringValue\":\"admin.dashboard.view\"}]}}"
                + "}"
                + "}";
        com.example.data.FirestoreRestClient.RestDocument parsedDoc =
                com.example.data.FirestoreRestClient.parseDocumentJson(new org.json.JSONObject(sampleDocJson), "");
        assertTrue(parsedDoc.exists());
        assertEquals("admin_uid_1", parsedDoc.getId());
        assertEquals(7, parsedDoc.getInt("syncCount"));
        assertTrue(parsedDoc.getTimestampMs("updatedAt") > 0L);

        AdminConfig.RbacAccess rbac = AdminConfig.RbacAccess.fromRestDocument("admin_uid_1", parsedDoc);
        assertTrue(rbac.isAdmin());
        assertTrue(rbac.canAccessAdminDashboard());
    }
}
