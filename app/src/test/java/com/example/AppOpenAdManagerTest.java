package com.example;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import com.example.ads.AppOpenAdManager;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;

import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Verifies AdMob App Open Ad configuration and custom open-count + 50% random gating:
 * - Open #1: no ad
 * - Open #2: no ad
 * - Open #3: guaranteed ad
 * - Open #4+: 50% random show / 50% skip
 */
@RunWith(RobolectricTestRunner.class)
public class AppOpenAdManagerTest {

    @Test
    public void appOpenAdUnitId_matchesRequestedUnitId() {
        assertEquals("ca-app-pub-4297682558932045/2179267626", AppOpenAdManager.APP_OPEN_AD_UNIT_ID);
    }

    @Test
    public void openSchedule_firstTwoNeverShow_thirdAlwaysShows_fourthPlusRandom50Percent() {
        Context context = ApplicationProvider.getApplicationContext();
        AppOpenAdManager manager = AppOpenAdManager.getInstance(context);
        manager.resetStateForTesting();

        // Deterministic alternating 50% gate for reproducible schedule verification
        AtomicInteger flips = new AtomicInteger(0);
        manager.setRandomGateForTesting(() -> flips.getAndIncrement() % 2 == 0);

        AppOpenAdManager.OpenDecision d1 = manager.recordAppOpenAndEvaluate();
        assertEquals(1, d1.getOpenCount());
        assertFalse(d1.isShouldShowAd());

        AppOpenAdManager.OpenDecision d2 = manager.recordAppOpenAndEvaluate();
        assertEquals(2, d2.getOpenCount());
        assertFalse(d2.isShouldShowAd());

        AppOpenAdManager.OpenDecision d3 = manager.recordAppOpenAndEvaluate();
        assertEquals(3, d3.getOpenCount());
        assertTrue(d3.isShouldShowAd());

        // From 4th open onward: follows 50% random gate cleanly
        AppOpenAdManager.OpenDecision d4 = manager.recordAppOpenAndEvaluate();
        assertEquals(4, d4.getOpenCount());
        assertTrue(d4.isShouldShowAd());

        AppOpenAdManager.OpenDecision d5 = manager.recordAppOpenAndEvaluate();
        assertEquals(5, d5.getOpenCount());
        assertFalse(d5.isShouldShowAd());

        AppOpenAdManager.OpenDecision d6 = manager.recordAppOpenAndEvaluate();
        assertEquals(6, d6.getOpenCount());
        assertTrue(d6.isShouldShowAd());

        AppOpenAdManager.OpenDecision d7 = manager.recordAppOpenAndEvaluate();
        assertEquals(7, d7.getOpenCount());
        assertFalse(d7.isShouldShowAd());
    }

    @Test
    public void openSchedule_random50PercentDistributionIsBalanced() {
        Random seeded = new Random(20261125L);
        int shown = 0;
        int trials = 1000;
        for (int open = 4; open < 4 + trials; open++) {
            if (AppOpenAdManager.evaluateShowPolicy(open, seeded::nextBoolean)) {
                shown++;
            }
        }
        assertTrue("Expected ~50% shown but got " + shown, shown >= 430 && shown <= 570);
    }

    @Test
    public void lifecycleTransitions_countForegroundOpensAndRespectExternalSuppression() {
        Context context = ApplicationProvider.getApplicationContext();
        AppOpenAdManager manager = AppOpenAdManager.getInstance(context);
        manager.resetStateForTesting();

        // 1st open via initial MainActivity launch -> openCount = 1 (no ad)
        try (ActivityController<MainActivity> controller =
                     Robolectric.buildActivity(MainActivity.class).create().start().resume()) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertEquals(1, manager.getStartedActivityCountForTesting());
            assertEquals(1, manager.getOpenCount());

            // Background -> suppress next return (simulating VPN/permission dialog) -> should stay at 1
            controller.pause().stop();
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertEquals(0, manager.getStartedActivityCountForTesting());
            manager.suppressNextForegroundOpen();
            controller.restart().resume();
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertEquals(1, manager.getOpenCount());

            // Normal background -> foreground transition -> openCount becomes 2, then 3
            controller.pause().stop();
            assertEquals(0, manager.getStartedActivityCountForTesting());
            controller.restart().resume();
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertEquals(2, manager.getOpenCount());

            controller.pause().stop();
            assertEquals(0, manager.getStartedActivityCountForTesting());
            controller.restart().resume();
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertEquals(3, manager.getOpenCount());
        }
    }
}
