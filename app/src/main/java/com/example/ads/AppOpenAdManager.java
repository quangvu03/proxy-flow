package com.example.ads;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.google.android.gms.ads.AdActivity;
import com.google.android.gms.ads.AdError;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.FullScreenContentCallback;
import com.google.android.gms.ads.LoadAdError;
import com.google.android.gms.ads.appopen.AppOpenAd;

import java.security.SecureRandom;
import java.util.concurrent.TimeUnit;

/**
 * Manages AdMob App Open Ads with custom open-count gating:
 * - 1st app open: never show
 * - 2nd app open: never show
 * - 3rd app open: always show
 * - 4th+ app opens: random 50% show / 50% skip
 */
public final class AppOpenAdManager implements Application.ActivityLifecycleCallbacks {

    public static final String APP_OPEN_AD_UNIT_ID = "ca-app-pub-4297682558932045/2179267626";

    private static final String PREFS_NAME = "proxyflow_app_open_ads";
    private static final String KEY_OPEN_COUNT = "open_count";
    private static final long AD_EXPIRY_HOURS = 4L;
    private static final long PENDING_SHOW_WINDOW_MS = 3500L;

    public interface RandomGate {
        boolean nextBoolean();
    }

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
    private RandomGate randomGate = new SecureRandom()::nextBoolean;

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

    private boolean pendingShowForCurrentOpen = false;
    private long pendingShowDeadlineMs = 0L;

    private AppOpenAdManager(@NonNull Context appContext) {
        this.appContext = appContext;
        this.prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
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
    public void setRandomGateForTesting(@NonNull RandomGate gate) {
        this.randomGate = gate;
    }

    @VisibleForTesting
    public void resetStateForTesting() {
        prefs.edit().clear().apply();
        startedActivityCount = 0;
        changingConfigurations = false;
        suppressNextOpen = false;
        pendingShowForCurrentOpen = false;
        pendingShowDeadlineMs = 0L;
        appOpenAd = null;
        isLoadingAd = false;
        isShowingAd = false;
        loadTimeMs = 0L;
    }

    public static boolean evaluateShowPolicy(int openCount, @NonNull RandomGate randomGate) {
        if (openCount <= 2) {
            return false;
        }
        if (openCount == 3) {
            return true;
        }
        return randomGate.nextBoolean();
    }

    public synchronized OpenDecision recordAppOpenAndEvaluate() {
        int nextOpenCount = prefs.getInt(KEY_OPEN_COUNT, 0) + 1;
        prefs.edit().putInt(KEY_OPEN_COUNT, nextOpenCount).apply();
        boolean shouldShow = evaluateShowPolicy(nextOpenCount, randomGate);
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
        if (isLoadingAd || isAdAvailable()) {
            return;
        }
        isLoadingAd = true;
        try {
            AdMobBannerHelper.initializeAsync(appContext);
            AdRequest request = new AdRequest.Builder().build();
            AppOpenAd.load(
                    appContext,
                    APP_OPEN_AD_UNIT_ID,
                    request,
                    new AppOpenAd.AppOpenAdLoadCallback() {
                        @Override
                        public void onAdLoaded(@NonNull AppOpenAd ad) {
                            appOpenAd = ad;
                            loadTimeMs = System.currentTimeMillis();
                            isLoadingAd = false;
                            Activity candidate = resumedActivity != null ? resumedActivity : currentActivity;
                            if (candidate != null && candidate == resumedActivity) {
                                tryShowPendingAd(candidate);
                            }
                        }

                        @Override
                        public void onAdFailedToLoad(@NonNull LoadAdError loadAdError) {
                            isLoadingAd = false;
                        }
                    }
            );
        } catch (Throwable ignored) {
            isLoadingAd = false;
        }
    }

    private void onAppEnteredForeground(@NonNull Activity activity) {
        if (isShowingAd || isAdActivity(activity)) {
            return;
        }
        OpenDecision decision = recordAppOpenAndEvaluate();
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
