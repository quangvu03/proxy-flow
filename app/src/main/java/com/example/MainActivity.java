package com.example;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.example.ads.AdMobBannerHelper;
import com.example.ads.AppOpenAdManager;
import com.example.auth.GoogleAuthManager;
import com.example.data.ProxyRepository;
import com.example.data.SettingsPreferences;
import com.example.model.ConnectionStatus;
import com.example.model.ProxyConfig;
import com.example.network.ProxyRuntimeManager;
import com.example.network.ProxyVpnService;
import com.example.ui.AppProxyFragment;
import com.example.ui.ConnectFragment;
import com.example.ui.GoogleLoginActivity;
import com.example.ui.LogsFragment;
import com.example.ui.ProxyListFragment;
import com.example.ui.ProxyViewModel;
import com.example.ui.SettingsFragment;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.navigationrail.NavigationRailView;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Code viết bằng java, sử dụng Android SDK và các thư viện hỗ trợ của Android để xây dựng một ứng dụng quản lý VPN và proxy.
 * phân quyền, router và tường lửa..
 */
public class MainActivity extends AppCompatActivity implements ConnectFragment.HostActions {

    private static final long SPLASH_DISPLAY_MS = 900L;
    private static boolean hasShownStartupSplash = false;

    @androidx.annotation.VisibleForTesting
    static void resetStartupSplashForTests() {
        hasShownStartupSplash = false;
    }

    private final Handler splashHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService connectCheckExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean isConnectingProxy = new AtomicBoolean(false);
    private View splashOverlay;
    private Runnable hideSplashRunnable;

    private ProxyRepository repository;
    private ProxyViewModel viewModel;
    private MaterialToolbar topAppBar;
    private BottomNavigationView bottomNav;
    private NavigationRailView navRail;
    private View adBannerWrapper;
    private final AdMobBannerHelper.BannerController bannerController = new AdMobBannerHelper.BannerController();

    @Nullable
    private FirebaseAuth firebaseAuth;
    private final FirebaseAuth.AuthStateListener authStateListener =
            auth -> updateTopLoginPill(auth.getCurrentUser());

    private int currentTabId = R.id.nav_connect;
    private long pendingVpnProxyId = -1L;
    @Nullable
    private ProxyConfig pendingVpnProxy;
    private String lastShownConnectionError = "";

    private final ActivityResultLauncher<Intent> vpnPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK) {
                    launchVpnAfterPermissions(pendingVpnProxyId);
                } else {
                    Toast.makeText(this, R.string.msg_vpn_permission_denied, Toast.LENGTH_SHORT).show();
                    ProxyRuntimeManager.getInstance().postDisconnected(pendingVpnProxy, "");
                    pendingVpnProxy = null;
                }
            });

    private final ActivityResultLauncher<String> notificationPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                // Start VPN regardless; notification permission only affects status notification visibility on Android 13+
                proceedWithVpnPreparation(pendingVpnProxyId);
            });

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        EdgeToEdge.enable(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        repository = ProxyRepository.getInstance(this);
        viewModel = new ViewModelProvider(this).get(ProxyViewModel.class);

        topAppBar = findViewById(R.id.top_app_bar);
        setupTopLoginAction(topAppBar);
        firebaseAuth = GoogleAuthManager.getFirebaseAuth(this);
        updateTopLoginPill(GoogleAuthManager.getCurrentUser(this));
        bottomNav = findViewById(R.id.bottom_navigation);
        navRail = findViewById(R.id.navigation_rail);
        adBannerWrapper = findViewById(R.id.ad_banner_wrapper);
        ViewGroup adBannerContainer = findViewById(R.id.ad_banner_container);

        splashOverlay = findViewById(R.id.splash_overlay);
        View splashBrandContainer = findViewById(R.id.splash_brand_container);
        View splashInitCaption = findViewById(R.id.tv_splash_initializing);
        int baseSplashBottomMargin = Math.round(28 * getResources().getDisplayMetrics().density);

        View rootCoordinator = findViewById(R.id.root_coordinator);
        ViewCompat.setOnApplyWindowInsetsListener(rootCoordinator, (v, insets) -> {
            Insets sysBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            topAppBar.setPadding(
                    topAppBar.getPaddingLeft(),
                    sysBars.top,
                    topAppBar.getPaddingRight(),
                    topAppBar.getPaddingBottom()
            );
            if (bottomNav != null) {
                bottomNav.setPadding(0, 0, 0, sysBars.bottom);
            } else if (adBannerWrapper != null) {
                adBannerWrapper.setPadding(0, 0, 0, sysBars.bottom);
            }
            if (navRail != null) {
                navRail.setPadding(sysBars.left, sysBars.top, 0, sysBars.bottom);
            }
            if (splashOverlay != null) {
                splashOverlay.setPadding(sysBars.left, sysBars.top, sysBars.right, 0);
            }
            if (splashInitCaption != null) {
                android.view.ViewGroup.MarginLayoutParams lp =
                        (android.view.ViewGroup.MarginLayoutParams) splashInitCaption.getLayoutParams();
                lp.bottomMargin = baseSplashBottomMargin + sysBars.bottom;
                splashInitCaption.setLayoutParams(lp);
            }
            return WindowInsetsCompat.CONSUMED;
        });

        bannerController.attachAndLoad(this, adBannerWrapper, adBannerContainer);

        setupStartupSplash(savedInstanceState, splashBrandContainer);

        if (savedInstanceState == null) {
            switchTab(R.id.nav_connect);
        } else {
            currentTabId = savedInstanceState.getInt("current_tab", R.id.nav_connect);
            switchTab(currentTabId);
        }

        if (bottomNav != null) {
            bottomNav.setOnItemSelectedListener(item -> {
                switchTab(item.getItemId());
                return true;
            });
        }
        if (navRail != null) {
            navRail.setOnItemSelectedListener(item -> {
                switchTab(item.getItemId());
                return true;
            });
        }

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (currentTabId != R.id.nav_connect) {
                    onNavigateToTab(R.id.nav_connect);
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });
        if (savedInstanceState == null) {
            GoogleAuthManager.attemptAutoSignIn(this, new GoogleAuthManager.AuthCallback() {
                @Override
                public void onSuccess(@androidx.annotation.NonNull FirebaseUser user) {
                    updateTopLoginPill(user);
                }

                @Override
                public void onCancelled() {
                }

                @Override
                public void onError(@androidx.annotation.NonNull String message) {
                }
            });
        }

        viewModel.getConnectionStatus().observe(this, status -> {
            if (status == null) return;
            if (status.getState() == ConnectionStatus.State.ERROR && !status.getLastError().isEmpty()) {
                if (!status.getLastError().equals(lastShownConnectionError)) {
                    lastShownConnectionError = status.getLastError();
                    Toast.makeText(this, status.getLastError(), Toast.LENGTH_LONG).show();
                }
            } else if (status.getState() == ConnectionStatus.State.CONNECTED
                    || status.getState() == ConnectionStatus.State.CONNECTING) {
                lastShownConnectionError = "";
            }
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (firebaseAuth != null) {
            firebaseAuth.addAuthStateListener(authStateListener);
        }
    }

    @Override
    protected void onStop() {
        if (firebaseAuth != null) {
            firebaseAuth.removeAuthStateListener(authStateListener);
        }
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        bannerController.onResume();
        updateTopLoginPill(GoogleAuthManager.getCurrentUser(this));
        getWindow().getDecorView().post(() -> {
            if (!isFinishing() && !isDestroyed()) {
                SettingsPreferences.syncThemeAndLocaleIfNeeded(this);
            }
        });
    }

    @Override
    protected void onPause() {
        bannerController.onPause();
        super.onPause();
    }

    private void setupTopLoginAction(MaterialToolbar toolbar) {
        toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.action_google_login) {
                openGoogleLoginScreen();
                return true;
            }
            return false;
        });
        MenuItem loginItem = toolbar.getMenu().findItem(R.id.action_google_login);
        if (loginItem != null && loginItem.getActionView() != null) {
            View btnTopLogin = loginItem.getActionView().findViewById(R.id.btn_top_login);
            if (btnTopLogin != null) {
                btnTopLogin.setOnClickListener(v -> openGoogleLoginScreen());
            } else {
                loginItem.getActionView().setOnClickListener(v -> openGoogleLoginScreen());
            }
        }
    }

    private void openGoogleLoginScreen() {
        startActivity(new Intent(this, GoogleLoginActivity.class));
    }

    private void updateTopLoginPill(@Nullable FirebaseUser user) {
        if (topAppBar == null) {
            return;
        }
        MenuItem loginItem = topAppBar.getMenu().findItem(R.id.action_google_login);
        if (loginItem == null || loginItem.getActionView() == null) {
            return;
        }
        View actionView = loginItem.getActionView();
        TextView tvLabel = actionView.findViewById(R.id.tv_top_login_label);
        View statusDot = actionView.findViewById(R.id.view_top_login_status_dot);
        String label = GoogleAuthManager.getDisplayLabel(user, getString(R.string.action_login));
        if (tvLabel != null) {
            tvLabel.setText(label);
        }
        if (statusDot != null) {
            statusDot.setVisibility(user != null ? View.VISIBLE : View.GONE);
        }
        loginItem.setTitle(label);
    }

    @Override
    protected void onSaveInstanceState(@androidx.annotation.NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt("current_tab", currentTabId);
    }

    @Override
    protected void onDestroy() {
        bannerController.onDestroy();
        if (hideSplashRunnable != null) {
            splashHandler.removeCallbacks(hideSplashRunnable);
        }
        splashHandler.removeCallbacksAndMessages(null);
        connectCheckExecutor.shutdownNow();
        super.onDestroy();
    }

    private void setupStartupSplash(@Nullable Bundle savedInstanceState, @Nullable View brandContainer) {
        if (splashOverlay == null) {
            return;
        }
        if (savedInstanceState != null || hasShownStartupSplash) {
            splashOverlay.setVisibility(View.GONE);
            return;
        }
        hasShownStartupSplash = true;
        splashOverlay.setVisibility(View.VISIBLE);
        splashOverlay.setAlpha(1f);

        if (brandContainer != null) {
            brandContainer.setAlpha(0f);
            brandContainer.setTranslationY(18f);
            brandContainer.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(280L)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
        }

        hideSplashRunnable = () -> {
            if (isFinishing() || isDestroyed() || splashOverlay == null) {
                return;
            }
            splashOverlay.animate()
                    .alpha(0f)
                    .setDuration(260L)
                    .withEndAction(() -> {
                        if (splashOverlay != null) {
                            splashOverlay.setVisibility(View.GONE);
                        }
                    })
                    .start();
        };
        splashHandler.postDelayed(hideSplashRunnable, SPLASH_DISPLAY_MS);
    }

    private void switchTab(int itemId) {
        currentTabId = itemId;
        Fragment target;
        if (itemId == R.id.nav_proxies) {
            target = new ProxyListFragment();
        } else if (itemId == R.id.nav_logs) {
            target = new LogsFragment();
        } else if (itemId == R.id.nav_apps) {
            target = new AppProxyFragment();
        } else if (itemId == R.id.nav_settings) {
            target = new SettingsFragment();
        } else {
            currentTabId = R.id.nav_connect;
            target = new ConnectFragment();
        }
        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.fragment_container, target)
                .commit();
    }

    @Override
    public void onNavigateToTab(int navItemId) {
        if (bottomNav != null) {
            bottomNav.setSelectedItemId(navItemId);
        } else if (navRail != null) {
            navRail.setSelectedItemId(navItemId);
        } else {
            switchTab(navItemId);
        }
    }

    @Override
    public void onRequestToggleVpn(@Nullable ProxyConfig proxy) {
        ConnectionStatus status = ProxyRuntimeManager.getInstance().getCurrentStatus();
        if (status.isRunning()) {
            ProxyVpnService.stopVpn(this);
            return;
        }
        long targetId = proxy != null ? proxy.getId() : -1L;
        connectToProxy(targetId, false);
    }

    @Override
    public void onRequestConnectProxy(long proxyId) {
        connectToProxy(proxyId, true);
    }

    private void connectToProxy(long requestedProxyId, boolean switchIfRunning) {
        if (!isConnectingProxy.compareAndSet(false, true)) {
            return;
        }
        connectCheckExecutor.execute(() -> {
            try {
                ProxyConfig target = requestedProxyId > 0
                        ? repository.getProxyByIdSync(requestedProxyId)
                        : repository.getActiveProxySync();
                if (target == null) {
                    target = repository.getActiveProxySync();
                }
                if (target == null) {
                    runOnUiThread(() -> {
                        if (!isFinishing() && !isDestroyed()) {
                            Toast.makeText(this, R.string.msg_no_proxy_to_connect, Toast.LENGTH_SHORT).show();
                            onNavigateToTab(R.id.nav_proxies);
                        }
                    });
                    return;
                }

                ConnectionStatus currentStatus = ProxyRuntimeManager.getInstance().getCurrentStatus();
                boolean vpnAlreadyRunning = currentStatus.isRunning();
                repository.markActiveProxySync(target.getId());
                final long targetProxyId = target.getId();
                final ProxyConfig chosenTarget = target;
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    if (vpnAlreadyRunning && switchIfRunning) {
                        ProxyVpnService.switchToProxy(this, targetProxyId);
                        onNavigateToTab(R.id.nav_connect);
                    } else {
                        pendingVpnProxy = chosenTarget;
                        ProxyRuntimeManager.getInstance().postStatus(new ConnectionStatus(
                                ConnectionStatus.State.CONNECTING,
                                chosenTarget,
                                0, 0L, 0L, 0, "", 0L
                        ));
                        startVpnWithPermissions(targetProxyId);
                        if (switchIfRunning) {
                            onNavigateToTab(R.id.nav_connect);
                        }
                    }
                });
            } finally {
                isConnectingProxy.set(false);
            }
        });
    }

    private void startVpnWithPermissions(long proxyId) {
        pendingVpnProxyId = proxyId;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                AppOpenAdManager.getInstance(this).suppressNextForegroundOpen();
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
                return;
            }
        }
        proceedWithVpnPreparation(proxyId);
    }

    private void proceedWithVpnPreparation(long proxyId) {
        Intent prepareIntent = VpnService.prepare(this);
        if (prepareIntent != null) {
            AppOpenAdManager.getInstance(this).suppressNextForegroundOpen();
            vpnPermissionLauncher.launch(prepareIntent);
        } else {
            launchVpnAfterPermissions(proxyId);
        }
    }

    private void launchVpnAfterPermissions(long proxyId) {
        try {
            ProxyVpnService.startVpn(this, proxyId);
        } catch (Exception e) {
            String err = e.getMessage() != null ? e.getMessage() : getString(R.string.status_error);
            ProxyRuntimeManager.getInstance().postDisconnected(pendingVpnProxy, err);
        } finally {
            pendingVpnProxy = null;
        }
    }
}
