package com.example;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;

import androidx.test.core.app.ApplicationProvider;

import com.example.ads.AdMobBannerHelper;
import com.example.ads.AdsConfigManager;
import com.google.android.gms.ads.AdView;
import com.google.android.gms.ads.MobileAds;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;

/**
 * Verifies Google AdMob App ID manifest registration, anchored adaptive Banner AdView
 * attachment/lifecycle handling, and dynamic AdsConfigManager gating inside MainActivity.
 */
@RunWith(RobolectricTestRunner.class)
public class AdMobBannerIntegrationTest {

    @After
    public void tearDown() {
        Context context = ApplicationProvider.getApplicationContext();
        AdsConfigManager.getInstance(context).resetForTesting();
    }

    @Test
    public void manifest_containsExpectedAdMobApplicationId() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        ApplicationInfo appInfo = context.getPackageManager()
                .getApplicationInfo(context.getPackageName(), PackageManager.GET_META_DATA);
        Bundle meta = appInfo.metaData;
        assertNotNull(meta);
        assertEquals(
                "ca-app-pub-4297682558932045~6174032153",
                meta.getString("com.google.android.gms.ads.APPLICATION_ID")
        );
    }

    @Test
    public void mainActivity_attachesBannerAdViewWithExpectedAdUnitIdAndHandlesLifecycle() {
        Context context = ApplicationProvider.getApplicationContext();
        AdsConfigManager.getInstance(context).setAdsEnabledForTesting(true);

        try (ActivityController<MainActivity> controller =
                     Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity = controller.get();
            Shadows.shadowOf(Looper.getMainLooper()).idle();

            assertTrue(
                    "No test device IDs should be configured for production ads",
                    MobileAds.getRequestConfiguration().getTestDeviceIds().isEmpty()
            );

            ViewGroup container = activity.findViewById(R.id.ad_banner_container);
            assertNotNull(container);
            assertTrue(container.getChildCount() > 0);

            AdView adView = null;
            for (int i = 0; i < container.getChildCount(); i++) {
                if (container.getChildAt(i) instanceof AdView) {
                    adView = (AdView) container.getChildAt(i);
                    break;
                }
            }
            assertNotNull(adView);
            assertEquals("ca-app-pub-4297682558932045/2374835054", adView.getAdUnitId());
            assertEquals(AdMobBannerHelper.BANNER_AD_UNIT_ID, adView.getAdUnitId());

            controller.pause();
            controller.resume();
        }
    }

    @Test
    public void mainActivity_hidesBannerWhenAdsDisabledAndReattachesLiveWhenEnabled() {
        Context context = ApplicationProvider.getApplicationContext();
        AdsConfigManager adsConfigManager = AdsConfigManager.getInstance(context);
        adsConfigManager.setAdsEnabledForTesting(false);

        try (ActivityController<MainActivity> controller =
                     Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity = controller.get();
            Shadows.shadowOf(Looper.getMainLooper()).idle();

            View wrapper = activity.findViewById(R.id.ad_banner_wrapper);
            ViewGroup container = activity.findViewById(R.id.ad_banner_container);
            assertNotNull(wrapper);
            assertNotNull(container);

            assertEquals(View.GONE, wrapper.getVisibility());
            assertEquals(0, container.getChildCount());

            // Admin enables ads remotely -> live listener re-attaches banner AdView
            adsConfigManager.setAdsEnabledForTesting(true);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertTrue(container.getChildCount() > 0);

            // Admin disables ads remotely -> live listener destroys & hides banner immediately
            adsConfigManager.setAdsEnabledForTesting(false);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals(View.GONE, wrapper.getVisibility());
            assertEquals(0, container.getChildCount());
        }
    }
}
