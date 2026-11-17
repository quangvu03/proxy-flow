package com.example.model;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Per-app proxy routing rule stored in preferences.
 * BLOCK_SELECTED: all apps use Proxy except the checked packages.
 * ALLOW_SELECTED: only the checked packages use Proxy.
 */
public class AppProxyRule {

    public enum Mode {
        BLOCK_SELECTED,
        ALLOW_SELECTED
    }

    private final Mode mode;
    private final Set<String> selectedPackages;

    public AppProxyRule(Mode mode, Set<String> selectedPackages) {
        this.mode = mode != null ? mode : Mode.BLOCK_SELECTED;
        this.selectedPackages = selectedPackages != null
                ? Collections.unmodifiableSet(new HashSet<>(selectedPackages))
                : Collections.emptySet();
    }

    public Mode getMode() {
        return mode;
    }

    public Set<String> getSelectedPackages() {
        return selectedPackages;
    }

    public boolean isPackageChecked(String packageName) {
        return packageName != null && selectedPackages.contains(packageName);
    }

    public boolean doesAppUseProxy(String packageName) {
        boolean checked = isPackageChecked(packageName);
        return mode == Mode.BLOCK_SELECTED ? !checked : checked;
    }
}
