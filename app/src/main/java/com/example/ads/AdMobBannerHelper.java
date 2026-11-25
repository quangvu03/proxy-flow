package com.example.ads;

import android.app.Activity;
import android.content.Context;
import android.graphics.Rect;
import android.os.Build;
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

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Coordinates Google Mobile Ads SDK initialization and anchored adaptive banner lifecycle
 * in 100% Java with defensive error handling.
 */
public final class AdMobBannerHelper {

    public static final String ADMOB_APP_ID = "ca-app-pub-4297682558932045~6174032153";
    public static final String BANNER_AD_UNIT_ID = "ca-app-pub-4297682558932045/2374835054";

    private static final AtomicBoolean SDK_INITIALIZED = new AtomicBoolean(false);

    private AdMobBannerHelper() {}

    public static void initializeAsync(@NonNull Context context) {
        if (!SDK_INITIALIZED.compareAndSet(false, true)) {
            return;
        }
        final Context appContext = context.getApplicationContext() != null
                ? context.getApplicationContext()
                : context;
        Thread initThread = new Thread(() -> {
            try {
                MobileAds.initialize(appContext, initializationStatus -> {});
            } catch (Throwable ignored) {
                // Keep app operational even if Google Play Services / WebView is unavailable in tests
            }
        }, "ProxyFlow-AdMobInit");
        initThread.setDaemon(true);
        initThread.start();
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
            initializeAsync(activity);

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

                bannerContainer.post(() -> {
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
                });
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
