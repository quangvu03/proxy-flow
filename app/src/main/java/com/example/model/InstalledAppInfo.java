package com.example.model;

import android.graphics.drawable.Drawable;

/**
 * Immutable snapshot of an installed application discovered on the device,
 * including whether it declares INTERNET permission and whether it is a system app.
 */
public class InstalledAppInfo {

    private final String packageName;
    private final String appName;
    private final Drawable icon;
    private final boolean systemApp;
    private final boolean hasInternetPermission;

    public InstalledAppInfo(String packageName, String appName, Drawable icon,
                            boolean systemApp, boolean hasInternetPermission) {
        this.packageName = packageName != null ? packageName : "";
        this.appName = appName != null ? appName : this.packageName;
        this.icon = icon;
        this.systemApp = systemApp;
        this.hasInternetPermission = hasInternetPermission;
    }

    public String getPackageName() { return packageName; }
    public String getAppName() { return appName; }
    public Drawable getIcon() { return icon; }
    public boolean isSystemApp() { return systemApp; }
    public boolean hasInternetPermission() { return hasInternetPermission; }
}
