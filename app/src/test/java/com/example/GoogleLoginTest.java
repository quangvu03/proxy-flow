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

import androidx.test.core.app.ApplicationProvider;

import com.example.auth.GoogleAuthManager;
import com.example.ui.GoogleLoginActivity;
import com.google.android.material.appbar.MaterialToolbar;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;

/**
 * Robolectric tests verifying top-bar Login action (replacing Settings),
 * Google-only login screen rendering, and Google Services web client ID generation.
 */
@RunWith(RobolectricTestRunner.class)
public class GoogleLoginTest {

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
    public void googleLoginActivity_showsGoogleOnlySignInWhenUnauthenticated() {
        GoogleLoginActivity activity = Robolectric.buildActivity(GoogleLoginActivity.class)
                .setup()
                .get();

        View signInCard = activity.findViewById(R.id.card_google_sign_in);
        View accountCard = activity.findViewById(R.id.card_google_account);
        View btnSignIn = activity.findViewById(R.id.btn_google_sign_in);

        assertNotNull(signInCard);
        assertNotNull(accountCard);
        assertNotNull(btnSignIn);

        assertEquals(View.VISIBLE, signInCard.getVisibility());
        assertEquals(View.GONE, accountCard.getVisibility());
        assertEquals("Login", GoogleAuthManager.getDisplayLabel(null, "Login"));
    }
}
