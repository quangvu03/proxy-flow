package com.example.auth;

import androidx.annotation.Nullable;

import com.google.firebase.auth.FirebaseUser;

import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Central authorization allowlist for TopProxy Admin Dashboard access.
 */
public final class AdminConfig {

    private static final Set<String> ADMIN_UIDS;
    private static final Set<String> ADMIN_EMAILS;

    static {
        Set<String> uids = new HashSet<>();
        uids.add("xuonHbFXXrPQEJVZPjyJy9itC7x1");
        uids.add("jACOpeQtRie82vw5S20AUWfZxD53");
        ADMIN_UIDS = Collections.unmodifiableSet(uids);

        Set<String> emails = new HashSet<>();
        emails.add("resetall1st@gmail.com");
        ADMIN_EMAILS = Collections.unmodifiableSet(emails);
    }

    private AdminConfig() {
    }

    public static boolean isAdmin(@Nullable FirebaseUser user) {
        if (user == null) {
            return false;
        }
        return isAdmin(user.getUid(), user.getEmail(), user.isEmailVerified());
    }

    public static boolean isAdmin(@Nullable String uid, @Nullable String email, boolean emailVerified) {
        return isAdminUid(uid) || isAdminEmail(email, emailVerified);
    }

    public static boolean isAdminUid(@Nullable String uid) {
        return uid != null && ADMIN_UIDS.contains(uid.trim());
    }

    public static boolean isAdminEmail(@Nullable String email, boolean emailVerified) {
        if (!emailVerified || email == null) {
            return false;
        }
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        return !normalized.isEmpty() && ADMIN_EMAILS.contains(normalized);
    }

    public static Set<String> getAdminUids() {
        return ADMIN_UIDS;
    }

    public static Set<String> getAdminEmails() {
        return ADMIN_EMAILS;
    }
}
