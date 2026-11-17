package com.example.data;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.net.Uri;

import com.example.model.InstalledAppInfo;

import java.text.Collator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Pure Java scanner inspecting installed apps on the device via Play-compliant <queries>
 * intents (Launcher + HTTPS handlers), checking system status and INTERNET permission.
 */
public final class InstalledAppScanner {

    private InstalledAppScanner() {}

    public static List<InstalledAppInfo> scanInstalledApps(Context context) {
        PackageManager pm = context.getPackageManager();
        String selfPackage = context.getPackageName();

        Map<String, ApplicationInfo> discovered = new HashMap<>();

        Intent launcherIntent = new Intent(Intent.ACTION_MAIN);
        launcherIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> launcherMatches = pm.queryIntentActivities(launcherIntent, 0);
        for (ResolveInfo ri : launcherMatches) {
            if (ri.activityInfo != null && ri.activityInfo.applicationInfo != null) {
                String pkg = ri.activityInfo.packageName;
                if (pkg != null && !pkg.equals(selfPackage)) {
                    discovered.put(pkg, ri.activityInfo.applicationInfo);
                }
            }
        }

        Intent httpsIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"));
        List<ResolveInfo> httpsMatches = pm.queryIntentActivities(httpsIntent, 0);
        for (ResolveInfo ri : httpsMatches) {
            if (ri.activityInfo != null && ri.activityInfo.applicationInfo != null) {
                String pkg = ri.activityInfo.packageName;
                if (pkg != null && !pkg.equals(selfPackage) && !discovered.containsKey(pkg)) {
                    discovered.put(pkg, ri.activityInfo.applicationInfo);
                }
            }
        }

        List<InstalledAppInfo> results = new ArrayList<>(discovered.size());
        for (Map.Entry<String, ApplicationInfo> entry : discovered.entrySet()) {
            String pkg = entry.getKey();
            ApplicationInfo appInfo = entry.getValue();

            CharSequence rawLabel = appInfo.loadLabel(pm);
            String label = rawLabel != null ? rawLabel.toString().trim() : pkg;
            if (label.isEmpty()) label = pkg;

            Drawable icon = null;
            try {
                icon = appInfo.loadIcon(pm);
            } catch (Exception ignored) {
            }

            boolean isSystem = (appInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                    && (appInfo.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0;
            boolean hasInternet = checkInternetPermission(pm, pkg);

            results.add(new InstalledAppInfo(pkg, label, icon, isSystem, hasInternet));
        }

        Collator collator = Collator.getInstance(new Locale("vi", "VN"));
        results.sort((a, b) -> {
            if (a.isSystemApp() != b.isSystemApp()) {
                return a.isSystemApp() ? 1 : -1;
            }
            return collator.compare(a.getAppName(), b.getAppName());
        });

        return results;
    }

    public static Set<String> scanInstalledPackageNames(Context context) {
        PackageManager pm = context.getPackageManager();
        String selfPackage = context.getPackageName();
        Set<String> pkgs = new HashSet<>();

        Intent launcherIntent = new Intent(Intent.ACTION_MAIN);
        launcherIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        for (ResolveInfo ri : pm.queryIntentActivities(launcherIntent, 0)) {
            if (ri.activityInfo != null && ri.activityInfo.packageName != null
                    && !ri.activityInfo.packageName.equals(selfPackage)) {
                pkgs.add(ri.activityInfo.packageName);
            }
        }
        return pkgs;
    }

    private static boolean checkInternetPermission(PackageManager pm, String packageName) {
        try {
            PackageInfo pkgInfo = pm.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS);
            if (pkgInfo.requestedPermissions != null) {
                for (String perm : pkgInfo.requestedPermissions) {
                    if (Manifest.permission.INTERNET.equals(perm)) {
                        return true;
                    }
                }
            }
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        return false;
    }
}
