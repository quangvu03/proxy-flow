package com.example;

import android.app.Application;

import com.example.ads.AdMobBannerHelper;
import com.example.ads.AppOpenAdManager;
import com.example.data.SettingsPreferences;
import com.example.ui.MapHelper;

/**
 * Application entry point in 100% Java.
 * Applies persisted Light/Dark theme, Vietnamese/English locale, and
 * osmdroid internal storage paths before any Activity or MapView starts.
 */
public class ProxyFlowApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        SettingsPreferences.applySavedThemeAndLocale(this);
        MapHelper.initConfig(this);
        AdMobBannerHelper.initializeAsync(this);
        AppOpenAdManager appOpenAdManager = AppOpenAdManager.getInstance(this);
        appOpenAdManager.register(this);
        appOpenAdManager.loadAdIfNeeded();
    }
}
