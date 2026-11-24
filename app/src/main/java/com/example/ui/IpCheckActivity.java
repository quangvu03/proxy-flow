package com.example.ui;

import android.os.Bundle;
import android.view.View;

import androidx.activity.EdgeToEdge;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.example.R;
import com.google.android.material.appbar.MaterialToolbar;

/**
 * Dedicated Activity hosting IpCheckFragment, opened from the Connect screen
 * when the user taps "Xem IP & Bản đồ".
 */
public class IpCheckActivity extends AppCompatActivity {

    public static final String EXTRA_AUTO_CHECK_CURRENT_IP = "extra_auto_check_current_ip";

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        EdgeToEdge.enable(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ip_check);

        MaterialToolbar toolbar = findViewById(R.id.ip_check_toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        View root = findViewById(R.id.ip_check_root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets sysBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            toolbar.setPadding(
                    toolbar.getPaddingLeft(),
                    sysBars.top,
                    toolbar.getPaddingRight(),
                    toolbar.getPaddingBottom()
            );
            root.setPadding(0, 0, 0, sysBars.bottom);
            return WindowInsetsCompat.CONSUMED;
        });

        boolean autoCheckCurrentIp = getIntent() != null
                && getIntent().getBooleanExtra(EXTRA_AUTO_CHECK_CURRENT_IP, false);

        if (savedInstanceState == null) {
            getSupportFragmentManager()
                    .beginTransaction()
                    .replace(R.id.ip_check_fragment_container,
                            IpCheckFragment.newInstance(autoCheckCurrentIp))
                    .commit();
        }
    }
}
