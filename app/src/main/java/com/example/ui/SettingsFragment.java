package com.example.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.example.R;

/**
 * Pure Java Settings Fragment for the bottom navigation Settings tab,
 * organized into Appearance (Theme, Language) and Proxy (Auto Stop, VPN Timer).
 */
public class SettingsFragment extends Fragment {

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_settings, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        SettingsUiHelper.bind(requireContext(), view);
    }

    @Override
    public void onResume() {
        super.onResume();
        View view = getView();
        if (view != null) {
            SettingsUiHelper.refreshSummaries(requireContext(), view);
        }
    }
}
