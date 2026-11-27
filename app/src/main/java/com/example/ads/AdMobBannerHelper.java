package com.example.ads;

import android.app.Activity;
import android.content.Context;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowMetrics;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.gms.ads.AdListener;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.AdSize;
import com.google.android.gms.ads.AdView;
import com.google.android.gms.ads.LoadAdError;
import com.google.android.gms.ads.MobileAds;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Coordinates Google Mobile Ads SDK initialization and anchored adaptive banner lifecycle
 * in 100% Java with serialized SDK initialization and defensive error handling.
 */
public final class AdMobBannerHelper {

    public static final String ADMOB_APP_ID = "ca-app-pub-4297682558932045~6174032153";
    public static final String BANNER_AD_UNIT_ID = "ca-app-pub-4297682558932045/2374835054";

    private static final AtomicBoolean SDK_INIT_STARTED = new AtomicBoolean(false);
    private static final AtomicBoolean SDK_READY = new AtomicBoolean(false);
    private static final List<Runnable> PENDING_INIT_CALLBACKS = new ArrayList<>();
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    private AdMobBannerHelper() {}

    public static void initializeAsync(@NonNull Context context) {
        runWhenInitialized(context, null);
    }

    public static void runWhenInitialized(@NonNull Context context, @Nullable Runnable onReady) {
        if (SDK_READY.get()) {
            if (onReady != null) {
                MAIN_HANDLER.post(onReady);
            }
            return;
        }
        if (onReady != null) {
            synchronized (PENDING_INIT_CALLBACKS) {
                if (SDK_READY.get()) {
                    MAIN_HANDLER.post(onReady);
                    return;
                }
                PENDING_INIT_CALLBACKS.add(onReady);
            }
        }
        if (!SDK_INIT_STARTED.compareAndSet(false, true)) {
            return;
        }
        final Context appContext = context.getApplicationContext() != null
                ? context.getApplicationContext()
                : context;
        Runnable initTask = () -> {
            try {
                MobileAds.initialize(appContext, initializationStatus -> markSdkReady());
            } catch (Throwable ignored) {
                // Ensure pending callbacks are not stranded in headless/test environments
                markSdkReady();
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            initTask.run();
        } else {
            MAIN_HANDLER.post(initTask);
        }
    }

    private static void markSdkReady() {
        SDK_READY.set(true);
        List<Runnable> callbacks;
        synchronized (PENDING_INIT_CALLBACKS) {
            callbacks = new ArrayList<>(PENDING_INIT_CALLBACKS);
            PENDING_INIT_CALLBACKS.clear();
        }
        for (Runnable r : callbacks) {
            MAIN_HANDLER.post(r);
        }
    }

    public static final class BannerController {
        @Nullable
        private AdView adView;
        private boolean destroyed = false;

        public void attachAndLoad(
                @NonNull Activity activity,
                @Nullable View bannerWrapper,
                @Nullable ViewGroup bannerContainer) {
            if (bannerContainer == null || destroyed) {
                return;
            }

            try {
                AdView view = new AdView(activity);
                view.setAdUnitId(BANNER_AD_UNIT_ID);
                bannerContainer.removeAllViews();
                bannerContainer.addView(view, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                ));
                view.setAdListener(new AdListener() {
                    @Override
                    public void onAdLoaded() {
                        if (bannerWrapper != null && !destroyed) {
                            bannerWrapper.setVisibility(View.VISIBLE);
                        }
                    }

                    @Override
                    public void onAdFailedToLoad(@NonNull LoadAdError loadAdError) {
                        if (bannerWrapper != null && !destroyed) {
                            bannerWrapper.setVisibility(View.GONE);
                        }
                    }
                });
                this.adView = view;

                runWhenInitialized(activity, () -> bannerContainer.post(() -> {
                    if (destroyed || activity.isFinishing() || activity.isDestroyed() || this.adView == null) {
                        return;
                    }
                    try {
                        AdSize adaptiveSize = computeAdaptiveBannerSize(activity, bannerContainer);
                        this.adView.setAdSize(adaptiveSize);
                        AdRequest request = new AdRequest.Builder().build();
                        this.adView.loadAd(request);
                    } catch (Throwable ignored) {
                        if (bannerWrapper != null) {
                            bannerWrapper.setVisibility(View.GONE);
                        }
                    }
                }));
            } catch (Throwable ignored) {
                if (bannerWrapper != null) {
                    bannerWrapper.setVisibility(View.GONE);
                }
            }
        }

        public void onResume() {
            if (adView != null && !destroyed) {
                try {
                    adView.resume();
                } catch (Throwable ignored) {
                }
            }
        }

        public void onPause() {
            if (adView != null && !destroyed) {
                try {
                    adView.pause();
                } catch (Throwable ignored) {
                }
            }
        }

        public void onDestroy() {
            destroyed = true;
            if (adView != null) {
                try {
                    adView.destroy();
                } catch (Throwable ignored) {
                }
                adView = null;
            }
        }

        @Nullable
        public AdView getAdView() {
            return adView;
        }
    }

    @NonNull
    static AdSize computeAdaptiveBannerSize(@NonNull Activity activity, @NonNull View container) {
        DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
        float density = metrics.density > 0f ? metrics.density : 1f;
        int widthPixels = container.getWidth();
        if (widthPixels <= 0) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                WindowMetrics windowMetrics = activity.getWindowManager().getCurrentWindowMetrics();
                Rect bounds = windowMetrics.getBounds();
                widthPixels = bounds.width();
            } else {
                widthPixels = metrics.widthPixels;
            }
        }
        int adWidthDp = Math.max(320, Math.round(widthPixels / density));
        return AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(activity, adWidthDp);
    }
}
