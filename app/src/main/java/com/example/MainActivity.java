package com.example;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
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

import com.example.model.ConnectionStatus;
import com.example.model.ProxyConfig;
import com.example.network.ProxyRuntimeManager;
import com.example.network.ProxyVpnService;
import com.example.ui.AppProxyFragment;
import com.example.ui.ConnectFragment;
import com.example.ui.IpCheckFragment;
import com.example.ui.LogsFragment;
import com.example.ui.ProxyListFragment;
import com.example.ui.ProxyViewModel;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.navigationrail.NavigationRailView;

/**
 * Main entry Activity written 100% in Java.
 * Hosts adaptive BottomNavigationView / NavigationRailView, manages VPN & Notification
 * permissions, and routes between Connect, Proxy List, IP Check, Apps, and Logs tabs.
 */
public class MainActivity extends AppCompatActivity implements ConnectFragment.HostActions {

    private ProxyViewModel viewModel;
    private BottomNavigationView bottomNav;
    private NavigationRailView navRail;

    private int currentTabId = R.id.nav_connect;
    private long pendingVpnProxyId = -1L;

    private final ActivityResultLauncher<Intent> vpnPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK) {
                    launchVpnAfterPermissions(pendingVpnProxyId);
                } else {
                    Toast.makeText(this, R.string.msg_vpn_permission_denied, Toast.LENGTH_SHORT).show();
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

        viewModel = new ViewModelProvider(this).get(ProxyViewModel.class);

        MaterialToolbar toolbar = findViewById(R.id.top_app_bar);
        bottomNav = findViewById(R.id.bottom_navigation);
        navRail = findViewById(R.id.navigation_rail);

        View rootCoordinator = findViewById(R.id.root_coordinator);
        ViewCompat.setOnApplyWindowInsetsListener(rootCoordinator, (v, insets) -> {
            Insets sysBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            toolbar.setPadding(
                    toolbar.getPaddingLeft(),
                    sysBars.top,
                    toolbar.getPaddingRight(),
                    toolbar.getPaddingBottom()
            );
            if (bottomNav != null) {
                bottomNav.setPadding(0, 0, 0, sysBars.bottom);
            }
            if (navRail != null) {
                navRail.setPadding(sysBars.left, sysBars.top, 0, sysBars.bottom);
            }
            return WindowInsetsCompat.CONSUMED;
        });

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
    }

    @Override
    protected void onSaveInstanceState(@androidx.annotation.NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt("current_tab", currentTabId);
    }

    private void switchTab(int itemId) {
        currentTabId = itemId;
        Fragment target;
        if (itemId == R.id.nav_proxies) {
            target = new ProxyListFragment();
        } else if (itemId == R.id.nav_ip_check) {
            target = new IpCheckFragment();
        } else if (itemId == R.id.nav_apps) {
            target = new AppProxyFragment();
        } else if (itemId == R.id.nav_logs) {
            target = new LogsFragment();
        } else {
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
        if (targetId <= 0 && (viewModel.getProxies().getValue() == null || viewModel.getProxies().getValue().isEmpty())) {
            Toast.makeText(this, R.string.msg_no_proxy_to_connect, Toast.LENGTH_SHORT).show();
            onNavigateToTab(R.id.nav_proxies);
            return;
        }
        startVpnWithPermissions(targetId);
    }

    @Override
    public void onRequestConnectProxy(long proxyId) {
        ConnectionStatus status = ProxyRuntimeManager.getInstance().getCurrentStatus();
        if (status.isRunning()) {
            ProxyVpnService.switchToProxy(this, proxyId);
            onNavigateToTab(R.id.nav_connect);
        } else {
            startVpnWithPermissions(proxyId);
            onNavigateToTab(R.id.nav_connect);
        }
    }

    private void startVpnWithPermissions(long proxyId) {
        pendingVpnProxyId = proxyId;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
                return;
            }
        }
        proceedWithVpnPreparation(proxyId);
    }

    private void proceedWithVpnPreparation(long proxyId) {
        Intent prepareIntent = VpnService.prepare(this);
        if (prepareIntent != null) {
            vpnPermissionLauncher.launch(prepareIntent);
        } else {
            launchVpnAfterPermissions(proxyId);
        }
    }

    private void launchVpnAfterPermissions(long proxyId) {
        ProxyVpnService.startVpn(this, proxyId);
    }
}
