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
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;

/**
 * Robolectric tests verifying top-bar Login action (replacing Settings),
 * minimal Google-only login screen rendering, and no auth pollution in proxy logs.
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
}
