package com.example.ui;

import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.example.R;
import com.example.auth.AdminConfig;
import com.example.auth.GoogleAuthManager;
import com.example.data.AdminStatsRepository;
import com.example.data.AdminStatsRepository.AdminDashboardData;
import com.example.data.AdminStatsRepository.SyncEventItem;
import com.example.data.AdminStatsRepository.UserStatsItem;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.firebase.auth.FirebaseUser;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Pure Java Admin Dashboard displaying live user, proxy, and sync statistics
 * retrieved from Firebase Firestore. Restricted to designated Admin UIDs.
 */
public class AdminDashboardActivity extends AppCompatActivity {

    private View progressStats;
    private SwipeRefreshLayout swipeRefresh;
    private View cardError;
    private TextView tvError;

    private TextView tvUpdatedAt;
    private TextView tvTotalUsers;
    private TextView tvTotalProxies;
    private TextView tvTotalSyncs;
    private TextView tvActive24h;
    private TextView tvActive24hSub;
    private TextView tvWorkingProxies;
    private TextView tvWorkingSub;
    private TextView tvFirewallUsers;
    private TextView tvFirewallSub;
    private TextView tvPrivateRoutingUsers;
    private TextView tvPrivateRoutingSub;

    private TextView tvUsersSectionTitle;
    private TextView tvUsersEmpty;
    private TextView tvEventsSectionTitle;
    private TextView tvEventsEmpty;

    private final AdminUsersAdapter usersAdapter = new AdminUsersAdapter();
    private final AdminSyncEventsAdapter eventsAdapter = new AdminSyncEventsAdapter();

    private boolean isLoading = false;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        EdgeToEdge.enable(this);
        super.onCreate(savedInstanceState);

        FirebaseUser currentUser = GoogleAuthManager.getCurrentUser(this);
        if (!AdminConfig.isAdmin(currentUser)) {
            Toast.makeText(this, R.string.msg_admin_access_denied, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        setContentView(R.layout.activity_admin_dashboard);

        MaterialToolbar toolbar = findViewById(R.id.admin_toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        View root = findViewById(R.id.admin_dashboard_root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets sysBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            toolbar.setPadding(toolbar.getPaddingLeft(), sysBars.top, toolbar.getPaddingRight(), toolbar.getPaddingBottom());
            root.setPadding(sysBars.left, 0, sysBars.right, sysBars.bottom);
            return WindowInsetsCompat.CONSUMED;
        });

        progressStats = findViewById(R.id.progress_admin_stats);
        swipeRefresh = findViewById(R.id.swipe_admin_refresh);
        cardError = findViewById(R.id.card_admin_error);
        tvError = findViewById(R.id.tv_admin_error);
        ImageButton btnRefresh = findViewById(R.id.btn_admin_refresh);

        tvUpdatedAt = findViewById(R.id.tv_admin_updated_at);
        tvTotalUsers = findViewById(R.id.tv_admin_total_users);
        tvTotalProxies = findViewById(R.id.tv_admin_total_proxies);
        tvTotalSyncs = findViewById(R.id.tv_admin_total_syncs);
        tvActive24h = findViewById(R.id.tv_admin_active_24h);
        tvActive24hSub = findViewById(R.id.tv_admin_active_24h_sub);
        tvWorkingProxies = findViewById(R.id.tv_admin_working_proxies);
        tvWorkingSub = findViewById(R.id.tv_admin_working_sub);
        tvFirewallUsers = findViewById(R.id.tv_admin_firewall_users);
        tvFirewallSub = findViewById(R.id.tv_admin_firewall_sub);
        tvPrivateRoutingUsers = findViewById(R.id.tv_admin_private_routing_users);
        tvPrivateRoutingSub = findViewById(R.id.tv_admin_private_routing_sub);

        tvUsersSectionTitle = findViewById(R.id.tv_admin_users_section_title);
        tvUsersEmpty = findViewById(R.id.tv_admin_users_empty);
        tvEventsSectionTitle = findViewById(R.id.tv_admin_events_section_title);
        tvEventsEmpty = findViewById(R.id.tv_admin_events_empty);

        RecyclerView rvUsers = findViewById(R.id.rv_admin_users);
        rvUsers.setLayoutManager(new LinearLayoutManager(this));
        rvUsers.setAdapter(usersAdapter);

        RecyclerView rvEvents = findViewById(R.id.rv_admin_sync_events);
        rvEvents.setLayoutManager(new LinearLayoutManager(this));
        rvEvents.setAdapter(eventsAdapter);

        swipeRefresh.setColorSchemeResources(R.color.md_theme_primary, R.color.md_theme_secondary);
        swipeRefresh.setOnRefreshListener(this::loadStats);
        btnRefresh.setOnClickListener(v -> loadStats());

        loadStats();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (!AdminConfig.isAdmin(GoogleAuthManager.getCurrentUser(this))) {
            finish();
        }
    }

    private void loadStats() {
        if (isLoading) {
            return;
        }
        isLoading = true;
        cardError.setVisibility(View.GONE);
        progressStats.setVisibility(View.VISIBLE);

        AdminStatsRepository.loadDashboardStats(this, new AdminStatsRepository.StatsCallback() {
            @Override
            public void onLoaded(@NonNull AdminDashboardData data) {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                isLoading = false;
                progressStats.setVisibility(View.INVISIBLE);
                swipeRefresh.setRefreshing(false);
                bindDashboardData(data);
            }

            @Override
            public void onError(@NonNull String message) {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                isLoading = false;
                progressStats.setVisibility(View.INVISIBLE);
                swipeRefresh.setRefreshing(false);
                tvError.setText(message);
                cardError.setVisibility(View.VISIBLE);
            }
        });
    }

    private void bindDashboardData(@NonNull AdminDashboardData data) {
        String timeStr = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, Locale.getDefault())
                .format(new Date(data.getFetchedAtMs()));
        tvUpdatedAt.setText(getString(R.string.admin_hero_updated_at, timeStr));

        tvTotalUsers.setText(String.valueOf(data.getTotalUsers()));
        tvTotalProxies.setText(String.valueOf(data.getTotalSyncedProxies()));
        tvTotalSyncs.setText(String.valueOf(data.getTotalSyncRuns()));

        tvActive24h.setText(String.valueOf(data.getActiveUsers24h()));
        tvActive24hSub.setText(getString(R.string.admin_kpi_active_24h_sub, data.getSyncEvents24h()));

        tvWorkingProxies.setText(String.valueOf(data.getTotalWorkingProxies()));
        int healthyPct = data.getTotalSyncedProxies() > 0
                ? Math.round((data.getTotalWorkingProxies() * 100f) / data.getTotalSyncedProxies())
                : 0;
        tvWorkingSub.setText(getString(R.string.admin_kpi_working_sub, healthyPct));

        tvFirewallUsers.setText(String.valueOf(data.getFirewallEnabledUsers()));
        tvFirewallSub.setText(getString(R.string.admin_kpi_users_ratio, data.getFirewallEnabledUsers(), data.getTotalUsers()));

        tvPrivateRoutingUsers.setText(String.valueOf(data.getPrivateRoutingUsers()));
        tvPrivateRoutingSub.setText(getString(R.string.admin_kpi_users_ratio, data.getPrivateRoutingUsers(), data.getTotalUsers()));

        List<UserStatsItem> users = data.getUsers();
        tvUsersSectionTitle.setText(getString(R.string.admin_section_users, users.size()));
        tvUsersEmpty.setVisibility(users.isEmpty() ? View.VISIBLE : View.GONE);
        usersAdapter.submitList(users);

        List<SyncEventItem> events = data.getRecentSyncEvents();
        tvEventsSectionTitle.setText(getString(R.string.admin_section_sync_events, events.size()));
        tvEventsEmpty.setVisibility(events.isEmpty() ? View.VISIBLE : View.GONE);
        eventsAdapter.submitList(events);
    }

    private static final class AdminUsersAdapter extends RecyclerView.Adapter<AdminUsersAdapter.UserViewHolder> {

        private final List<UserStatsItem> items = new ArrayList<>();

        void submitList(@NonNull List<UserStatsItem> newItems) {
            items.clear();
            items.addAll(newItems);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public UserViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_admin_user, parent, false);
            return new UserViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull UserViewHolder holder, int position) {
            UserStatsItem item = items.get(position);
            String name = item.getDisplayName();
            String initial = !TextUtils.isEmpty(name)
                    ? name.substring(0, 1).toUpperCase(Locale.getDefault())
                    : "U";
            holder.tvInitial.setText(initial);
            holder.tvName.setText(name);
            holder.tvRolePill.setVisibility(item.isAdmin() ? View.VISIBLE : View.GONE);

            holder.tvEmail.setText(item.getEmail());
            holder.tvEmail.setVisibility(TextUtils.isEmpty(item.getEmail()) ? View.GONE : View.VISIBLE);

            holder.tvProxies.setText(holder.itemView.getContext().getString(
                    R.string.admin_user_proxy_summary,
                    item.getProxyCount(),
                    item.getWorkingProxyCount(),
                    item.getSyncCount()
            ));

            String device = !TextUtils.isEmpty(item.getDeviceModel()) ? item.getDeviceModel() : "Android";
            String version = !TextUtils.isEmpty(item.getAppVersion()) ? item.getAppVersion() : "3.6";
            String meta = holder.itemView.getContext().getString(R.string.admin_user_device_line, device, version);
            if (item.getLastActiveMs() > 0L) {
                String ts = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, Locale.getDefault())
                        .format(new Date(item.getLastActiveMs()));
                meta = meta + " • " + ts;
            }
            holder.tvMeta.setText(meta);
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        static final class UserViewHolder extends RecyclerView.ViewHolder {
            final TextView tvInitial;
            final TextView tvName;
            final TextView tvRolePill;
            final TextView tvEmail;
            final TextView tvProxies;
            final TextView tvMeta;

            UserViewHolder(@NonNull View itemView) {
                super(itemView);
                tvInitial = itemView.findViewById(R.id.tv_admin_user_initial);
                tvName = itemView.findViewById(R.id.tv_admin_user_name);
                tvRolePill = itemView.findViewById(R.id.tv_admin_user_role_pill);
                tvEmail = itemView.findViewById(R.id.tv_admin_user_email);
                tvProxies = itemView.findViewById(R.id.tv_admin_user_proxies);
                tvMeta = itemView.findViewById(R.id.tv_admin_user_meta);
            }
        }
    }

    private static final class AdminSyncEventsAdapter extends RecyclerView.Adapter<AdminSyncEventsAdapter.EventViewHolder> {

        private final List<SyncEventItem> items = new ArrayList<>();

        void submitList(@NonNull List<SyncEventItem> newItems) {
            items.clear();
            items.addAll(newItems);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public EventViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_admin_sync_event, parent, false);
            return new EventViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull EventViewHolder holder, int position) {
            SyncEventItem item = items.get(position);
            holder.tvUser.setText(item.getUserLabel());

            if (item.getCreatedAtMs() > 0L) {
                String timeStr = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, Locale.getDefault())
                        .format(new Date(item.getCreatedAtMs()));
                holder.tvTime.setText(timeStr);
            } else {
                holder.tvTime.setText("");
            }

            holder.tvSummary.setText(holder.itemView.getContext().getString(
                    R.string.admin_event_counts_summary,
                    item.getUploadedProxies(),
                    item.getDownloadedProxies(),
                    item.getTotalProxies()
            ));

            String device = !TextUtils.isEmpty(item.getDeviceModel()) ? item.getDeviceModel() : "Android";
            String version = !TextUtils.isEmpty(item.getAppVersion()) ? item.getAppVersion() : "3.6";
            holder.tvDevice.setText(holder.itemView.getContext().getString(
                    R.string.admin_user_device_line,
                    device,
                    version
            ));
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        static final class EventViewHolder extends RecyclerView.ViewHolder {
            final TextView tvUser;
            final TextView tvTime;
            final TextView tvSummary;
            final TextView tvDevice;

            EventViewHolder(@NonNull View itemView) {
                super(itemView);
                tvUser = itemView.findViewById(R.id.tv_admin_event_user);
                tvTime = itemView.findViewById(R.id.tv_admin_event_time);
                tvSummary = itemView.findViewById(R.id.tv_admin_event_summary);
                tvDevice = itemView.findViewById(R.id.tv_admin_event_device);
            }
        }
    }
}
