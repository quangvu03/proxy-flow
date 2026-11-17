package com.example;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import com.example.data.SettingsPreferences;
import com.example.model.AppProxyRule;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.Arrays;
import java.util.HashSet;

/**
 * Pure Java Robolectric tests for per-app proxy allow/block rules and persistence.
 */
@RunWith(RobolectricTestRunner.class)
public class AppProxyRuleTest {

    @Test
    public void blockSelectedMode_bypassesOnlyCheckedApps() {
        AppProxyRule rule = new AppProxyRule(
                AppProxyRule.Mode.BLOCK_SELECTED,
                new HashSet<>(Arrays.asList("com.bank.app", "com.local.tool"))
        );

        assertFalse(rule.doesAppUseProxy("com.bank.app"));
        assertFalse(rule.doesAppUseProxy("com.local.tool"));
        assertTrue(rule.doesAppUseProxy("com.android.chrome"));
    }

    @Test
    public void allowSelectedMode_routesOnlyCheckedApps() {
        AppProxyRule rule = new AppProxyRule(
                AppProxyRule.Mode.ALLOW_SELECTED,
                new HashSet<>(Arrays.asList("com.android.chrome"))
        );

        assertTrue(rule.doesAppUseProxy("com.android.chrome"));
        assertFalse(rule.doesAppUseProxy("com.bank.app"));
    }

    @Test
    public void settingsPreferences_persistsAppProxyRule() {
        Context context = ApplicationProvider.getApplicationContext();
        SettingsPreferences prefs = new SettingsPreferences(context);

        AppProxyRule saved = new AppProxyRule(
                AppProxyRule.Mode.ALLOW_SELECTED,
                new HashSet<>(Arrays.asList("com.example.browser", "com.example.chat"))
        );
        prefs.saveAppProxyRule(saved);

        AppProxyRule loaded = prefs.loadAppProxyRule();
        assertEquals(AppProxyRule.Mode.ALLOW_SELECTED, loaded.getMode());
        assertEquals(2, loaded.getSelectedPackages().size());
        assertTrue(loaded.isPackageChecked("com.example.browser"));
        assertTrue(loaded.isPackageChecked("com.example.chat"));
    }
}
