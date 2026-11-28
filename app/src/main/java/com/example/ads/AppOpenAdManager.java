package com.example.ads;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.google.android.gms.ads.AdActivity;
import com.google.android.gms.ads.AdError;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.FullScreenContentCallback;
import com.google.android.gms.ads.LoadAdError;
import com.google.android.gms.ads.appopen.AppOpenAd;

import java.util.concurrent.TimeUnit;

/**
 * Manages AdMob App Open Ads with custom open-count gating:
 * - 1st app open: never show
 * - 2nd app open: never show
 * - 3rd+ app opens: always show
 */
public final class AppOpenAdManager implements Application.ActivityLifecycleCallbacks {

    public static final String APP_OPEN_AD_UNIT_ID = "ca-app-pub-4297682558932045/2179267626";

    private static final String TAG = "AppOpenAdManager";
    private static final String PREFS_NAME = "proxyflow_app_open_ads";
    private static final String KEY_OPEN_COUNT = "open_count";
    private static final long AD_EXPIRY_HOURS = 4L;
    private static final long PENDING_SHOW_WINDOW_MS = 15000L;
    private static final int MAX_LOAD_RETRIES = 2;
    private static final long RETRY_BASE_DELAY_MS = 2500L;

    public static final class OpenDecision {
        private final int openCount;
        private final boolean shouldShowAd;

        public OpenDecision(int openCount, boolean shouldShowAd) {
            this.openCount = openCount;
            this.shouldShowAd = shouldShowAd;
        }

        public int getOpenCount() {
            return openCount;
        }

        public boolean isShouldShowAd() {
            return shouldShowAd;
        }
    }

    private static volatile AppOpenAdManager instance;

    private final Context appContext;
    private final SharedPreferences prefs;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private boolean registered = false;
    private int startedActivityCount = 0;
    private boolean changingConfigurations = false;
    private boolean suppressNextOpen = false;

    @Nullable
    private Activity currentActivity;
    @Nullable
    private Activity resumedActivity;
    @Nullable
    private AppOpenAd appOpenAd;
    private boolean isLoadingAd = false;
    private boolean isShowingAd = false;
    private long loadTimeMs = 0L;
    private int consecutiveLoadFailures = 0;

    private boolean pendingShowForCurrentOpen = false;
    private long pendingShowDeadlineMs = 0L;
    @Nullable
    private Runnable pendingRetryRunnable;

    private AppOpenAdManager(@NonNull Context appContext) {
        this.appContext = appContext;
        this.prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        AdsConfigManager.getInstance(appContext).addListener(enabled -> {
            if (!enabled) {
                clearPendingAndCachedAd();
            } else if (startedActivityCount > 0) {
                loadAdIfNeeded();
            }
        });
    }

    private void clearPendingAndCachedAd() {
        if (pendingRetryRunnable != null) {
            mainHandler.removeCallbacks(pendingRetryRunnable);
            pendingRetryRunnable = null;
        }
        pendingShowForCurrentOpen = false;
        pendingShowDeadlineMs = 0L;
        appOpenAd = null;
        isLoadingAd = false;
    }

    @NonNull
    public static AppOpenAdManager getInstance(@NonNull Context context) {
        Context appCtx = context.getApplicationContext();
        if (appCtx == null) {
            appCtx = context;
        }
        AppOpenAdManager current = instance;
        if (current == null || current.appContext != appCtx) {
            synchronized (AppOpenAdManager.class) {
                current = instance;
                if (current == null || current.appContext != appCtx) {
                    instance = new AppOpenAdManager(appCtx);
                }
            }
        }
        return instance;
    }

    public synchronized void register(@NonNull Application application) {
        if (registered) {
            return;
        }
        registered = true;
        application.registerActivityLifecycleCallbacks(this);
    }

    public void suppressNextForegroundOpen() {
        suppressNextOpen = true;
    }

    public int getOpenCount() {
        return prefs.getInt(KEY_OPEN_COUNT, 0);
    }

    @VisibleForTesting
    public int getStartedActivityCountForTesting() {
        return startedActivityCount;
    }

    @VisibleForTesting
    public void resetStateForTesting() {
        prefs.edit().clear().commit();
        if (pendingRetryRunnable != null) {
            mainHandler.removeCallbacks(pendingRetryRunnable);
            pendingRetryRunnable = null;
        }
        startedActivityCount = 0;
        changingConfigurations = false;
        suppressNextOpen = false;
        pendingShowForCurrentOpen = false;
        pendingShowDeadlineMs = 0L;
        appOpenAd = null;
        isLoadingAd = false;
        isShowingAd = false;
        loadTimeMs = 0L;
        consecutiveLoadFailures = 0;
    }

    public static boolean evaluateShowPolicy(int openCount) {
        return openCount >= 3;
    }

    public synchronized OpenDecision recordAppOpenAndEvaluate() {
        int nextOpenCount = prefs.getInt(KEY_OPEN_COUNT, 0) + 1;
        prefs.edit().putInt(KEY_OPEN_COUNT, nextOpenCount).commit();
        boolean shouldShow = evaluateShowPolicy(nextOpenCount);
        return new OpenDecision(nextOpenCount, shouldShow);
    }

    public boolean isAdAvailable() {
        return appOpenAd != null && wasLoadTimeLessThanNHoursAgo(AD_EXPIRY_HOURS);
    }

    private boolean wasLoadTimeLessThanNHoursAgo(long numHours) {
        long diff = System.currentTimeMillis() - loadTimeMs;
        return diff < TimeUnit.HOURS.toMillis(numHours);
    }

    public void loadAdIfNeeded() {
        if (!AdsConfigManager.getInstance(appContext).isAdsEnabled()) {
            clearPendingAndCachedAd();
            return;
        }
        if (isLoadingAd || isAdAvailable()) {
            return;
        }
        if (pendingRetryRunnable != null) {
            mainHandler.removeCallbacks(pendingRetryRunnable);
            pendingRetryRunnable = null;
        }
        isLoadingAd = true;
        AdMobBannerHelper.runWhenInitialized(appContext, () -> {
            if (!AdsConfigManager.getInstance(appContext).isAdsEnabled() || isAdAvailable()) {
                isLoadingAd = false;
                return;
            }
            try {
                AdRequest request = new AdRequest.Builder().build();
                AppOpenAd.load(
                        appContext,
                        APP_OPEN_AD_UNIT_ID,
                        request,
                        new AppOpenAd.AppOpenAdLoadCallback() {
                            @Override
                            public void onAdLoaded(@NonNull AppOpenAd ad) {
                                Log.d(TAG, "AppOpenAd loaded successfully");
                                appOpenAd = ad;
                                loadTimeMs = System.currentTimeMillis();
                                isLoadingAd = false;
                                consecutiveLoadFailures = 0;
                                Activity candidate = resumedActivity != null ? resumedActivity : currentActivity;
                                if (candidate != null && candidate == resumedActivity) {
                                    tryShowPendingAd(candidate);
                                }
                            }

                            @Override
                            public void onAdFailedToLoad(@NonNull LoadAdError loadAdError) {
                                isLoadingAd = false;
                                consecutiveLoadFailures++;
                                Log.w(TAG, "AppOpenAd failed to load (code="
                                        + loadAdError.getCode() + "): " + loadAdError.getMessage());
                                if (consecutiveLoadFailures <= MAX_LOAD_RETRIES && startedActivityCount > 0) {
                                    long delayMs = RETRY_BASE_DELAY_MS * consecutiveLoadFailures;
                                    pendingRetryRunnable = () -> {
                                        pendingRetryRunnable = null;
                                        if (startedActivityCount > 0 && !isAdAvailable()) {
                                            loadAdIfNeeded();
                                        }
                                    };
                                    mainHandler.postDelayed(pendingRetryRunnable, delayMs);
                                }
                            }
                        }
                );
            } catch (Throwable t) {
                isLoadingAd = false;
                Log.w(TAG, "AppOpenAd.load threw exception: " + t.getMessage());
            }
        });
    }

    private void onAppEnteredForeground(@NonNull Activity activity) {
        if (isShowingAd || isAdActivity(activity)) {
            return;
        }
        if (!AdsConfigManager.getInstance(appContext).isAdsEnabled()) {
            clearPendingAndCachedAd();
            return;
        }
        consecutiveLoadFailures = 0;
        OpenDecision decision = recordAppOpenAndEvaluate();
        Log.d(TAG, "App foreground open #" + decision.getOpenCount()
                + ", shouldShow=" + decision.isShouldShowAd());
        if (decision.isShouldShowAd()) {
            pendingShowForCurrentOpen = true;
            pendingShowDeadlineMs = SystemClock.elapsedRealtime() + PENDING_SHOW_WINDOW_MS;
        } else {
            pendingShowForCurrentOpen = false;
            pendingShowDeadlineMs = 0L;
        }
        loadAdIfNeeded();
    }

    private void tryShowPendingAd(@NonNull Activity activity) {
        if (!AdsConfigManager.getInstance(appContext).isAdsEnabled()) {
            pendingShowForCurrentOpen = false;
            pendingShowDeadlineMs = 0L;
            return;
        }
        if (!pendingShowForCurrentOpen || isShowingAd || isAdActivity(activity)) {
            return;
        }
        if (SystemClock.elapsedRealtime() > pendingShowDeadlineMs) {
            pendingShowForCurrentOpen = false;
            return;
        }
        if (activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        if (!isAdAvailable()) {
            loadAdIfNeeded();
            return;
        }

        AppOpenAd adToShow = appOpenAd;
        if (adToShow == null) {
            return;
        }
        pendingShowForCurrentOpen = false;
        isShowingAd = true;

        try {
            adToShow.setFullScreenContentCallback(new FullScreenContentCallback() {
                @Override
                public void onAdDismissedFullScreenContent() {
                    appOpenAd = null;
                    isShowingAd = false;
                    loadAdIfNeeded();
                }

                @Override
                public void onAdFailedToShowFullScreenContent(@NonNull AdError adError) {
                    appOpenAd = null;
                    isShowingAd = false;
                    loadAdIfNeeded();
                }

                @Override
                public void onAdShowedFullScreenContent() {
                    isShowingAd = true;
                }
            });
            adToShow.show(activity);
        } catch (Throwable ignored) {
            appOpenAd = null;
            isShowingAd = false;
            loadAdIfNeeded();
        }
    }

    private static boolean isAdActivity(@Nullable Activity activity) {
        return activity instanceof AdActivity;
    }

    @Override
    public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle savedInstanceState) {}

    @Override
    public void onActivityStarted(@NonNull Activity activity) {
        boolean isAd = isAdActivity(activity);
        if (!isAd) {
            currentActivity = activity;
        }
        boolean wasInBackground = startedActivityCount == 0;
        startedActivityCount++;
        if (wasInBackground && !changingConfigurations && !isAd) {
            if (suppressNextOpen) {
                suppressNextOpen = false;
            } else {
                onAppEnteredForeground(activity);
            }
        }
        changingConfigurations = false;
    }

    @Override
    public void onActivityResumed(@NonNull Activity activity) {
        if (!isAdActivity(activity)) {
            // Clear stale suppression if an external dialog (e.g. VPN/permission sheet)
            // paused without stopping the Activity.
            suppressNextOpen = false;
            currentActivity = activity;
            resumedActivity = activity;
            tryShowPendingAd(activity);
        }
    }

    @Override
    public void onActivityPaused(@NonNull Activity activity) {
        if (resumedActivity == activity) {
            resumedActivity = null;
        }
    }

    @Override
    public void onActivityStopped(@NonNull Activity activity) {
        changingConfigurations = activity.isChangingConfigurations();
        startedActivityCount = Math.max(0, startedActivityCount - 1);
        if (startedActivityCount == 0 && !changingConfigurations) {
            pendingShowForCurrentOpen = false;
            pendingShowDeadlineMs = 0L;
        }
    }

    @Override
    public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) {}

    @Override
    public void onActivityDestroyed(@NonNull Activity activity) {
        if (currentActivity == activity) {
            currentActivity = null;
        }
        if (resumedActivity == activity) {
            resumedActivity = null;
        }
    }
}
