package com.example;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.Looper;

import androidx.test.core.app.ApplicationProvider;

import com.example.ads.AdsConfigManager;
import com.example.ads.AppOpenAdManager;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;

/**
 * Verifies AdMob App Open Ad configuration, open-count gating, and AdsConfigManager toggle gating:
 * - Open #1: no ad
 * - Open #2: no ad
 * - Open #3+: always show ad (when ads_enabled == true)
 */
@RunWith(RobolectricTestRunner.class)
public class AppOpenAdManagerTest {

    @After
    public void tearDown() {
        Context context = ApplicationProvider.getApplicationContext();
        AppOpenAdManager.getInstance(context).resetStateForTesting();
        AdsConfigManager.getInstance(context).resetForTesting();
    }

    @Test
    public void appOpenAdUnitId_matchesRequestedUnitId() {
        assertEquals("ca-app-pub-4297682558932045/2179267626", AppOpenAdManager.APP_OPEN_AD_UNIT_ID);
    }

    @Test
    public void openSchedule_firstTwoNeverShow_thirdAndBeyondAlwaysShow() {
        Context context = ApplicationProvider.getApplicationContext();
        AppOpenAdManager manager = AppOpenAdManager.getInstance(context);
        manager.resetStateForTesting();

        AppOpenAdManager.OpenDecision d1 = manager.recordAppOpenAndEvaluate();
        assertEquals(1, d1.getOpenCount());
        assertFalse(d1.isShouldShowAd());

        AppOpenAdManager.OpenDecision d2 = manager.recordAppOpenAndEvaluate();
        assertEquals(2, d2.getOpenCount());
        assertFalse(d2.isShouldShowAd());

        for (int expectedCount = 3; expectedCount <= 20; expectedCount++) {
            AppOpenAdManager.OpenDecision d = manager.recordAppOpenAndEvaluate();
            assertEquals(expectedCount, d.getOpenCount());
            assertTrue("Open #" + expectedCount + " must always show App Open Ad", d.isShouldShowAd());
        }
    }

    @Test
    public void lifecycleTransitions_countForegroundOpensAndRespectExternalSuppression() {
        Context context = ApplicationProvider.getApplicationContext();
        AdsConfigManager.getInstance(context).setAdsEnabledForTesting(true);
        AppOpenAdManager manager = AppOpenAdManager.getInstance(context);
        manager.resetStateForTesting();

        // 1st open via initial MainActivity launch -> openCount = 1 (no ad)
        try (ActivityController<MainActivity> controller =
                     Robolectric.buildActivity(MainActivity.class).create().start().resume()) {
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals(1, manager.getStartedActivityCountForTesting());
            assertEquals(1, manager.getOpenCount());

            // Background -> suppress next return (simulating VPN/permission dialog) -> stays at 1
            controller.pause().stop();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals(0, manager.getStartedActivityCountForTesting());
            manager.suppressNextForegroundOpen();
            controller.restart().resume();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals(1, manager.getOpenCount());

            // Normal background -> foreground transitions -> 2, 3, 4
            controller.pause().stop();
            assertEquals(0, manager.getStartedActivityCountForTesting());
            controller.restart().resume();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals(2, manager.getOpenCount());

            controller.pause().stop();
            assertEquals(0, manager.getStartedActivityCountForTesting());
            controller.restart().resume();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals(3, manager.getOpenCount());

            controller.pause().stop();
            assertEquals(0, manager.getStartedActivityCountForTesting());
            controller.restart().resume();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals(4, manager.getOpenCount());
        }
    }

    @Test
    public void translucentDialogPauseResume_clearsStaleSuppressionForNextRealOpen() {
        Context context = ApplicationProvider.getApplicationContext();
        AdsConfigManager.getInstance(context).setAdsEnabledForTesting(true);
        AppOpenAdManager manager = AppOpenAdManager.getInstance(context);
        manager.resetStateForTesting();

        try (ActivityController<MainActivity> controller =
                     Robolectric.buildActivity(MainActivity.class).create().start().resume()) {
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals(1, manager.getOpenCount());

            // Simulate translucent system dialog (e.g. VPN consent / permission sheet):
            // suppressNextForegroundOpen() is called, activity pauses & resumes WITHOUT stopping.
            manager.suppressNextForegroundOpen();
            controller.pause();
            controller.resume();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals(1, manager.getOpenCount());

            // Next real background -> foreground open MUST NOT be swallowed.
            controller.pause().stop();
            controller.restart().resume();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals(2, manager.getOpenCount());
        }
    }

    @Test
    public void adsDisabled_skipsForegroundOpenAdAndAvailability() {
        Context context = ApplicationProvider.getApplicationContext();
        AdsConfigManager adsConfig = AdsConfigManager.getInstance(context);
        adsConfig.setAdsEnabledForTesting(false);
        AppOpenAdManager manager = AppOpenAdManager.getInstance(context);
        manager.resetStateForTesting();

        try (ActivityController<MainActivity> controller =
                     Robolectric.buildActivity(MainActivity.class).create().start().resume()) {
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals(0, manager.getOpenCount());
            assertFalse(manager.isAdAvailable());

            controller.pause().stop();
            controller.restart().resume();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals(0, manager.getOpenCount());
            assertFalse(manager.isAdAvailable());
        }
    }
}
