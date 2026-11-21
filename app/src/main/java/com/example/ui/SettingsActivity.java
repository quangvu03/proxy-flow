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
 * Pure Java Settings Activity sharing the exact grouped Settings UI and dialogs
 * with SettingsFragment.
 */
public class SettingsActivity extends AppCompatActivity {

    private View settingsRoot;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        EdgeToEdge.enable(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        MaterialToolbar toolbar = findViewById(R.id.settings_toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        settingsRoot = findViewById(R.id.settings_root);
        ViewCompat.setOnApplyWindowInsetsListener(settingsRoot, (v, insets) -> {
            Insets sysBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            toolbar.setPadding(toolbar.getPaddingLeft(), sysBars.top, toolbar.getPaddingRight(), toolbar.getPaddingBottom());
            settingsRoot.setPadding(0, 0, 0, sysBars.bottom);
            return WindowInsetsCompat.CONSUMED;
        });

        SettingsUiHelper.bind(this, settingsRoot);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (settingsRoot != null) {
            SettingsUiHelper.refreshSummaries(this, settingsRoot);
        }
    }
}
