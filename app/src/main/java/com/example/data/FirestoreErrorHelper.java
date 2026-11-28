package com.example.data;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.R;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.UserInfo;
import com.google.firebase.firestore.FirebaseFirestoreException;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.concurrent.CancellationException;

/**
 * Structured Firestore exception logger and user-friendly error formatter.
 */
public final class FirestoreErrorHelper {

    private static final String TAG = "FirestoreError";

    public enum OperationType {
        CREATE("create"),
        UPDATE("update"),
        DELETE("delete"),
        LIST("list"),
        GET("get"),
        WRITE("write");

        private final String value;

        OperationType(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }
    }

    private FirestoreErrorHelper() {
    }

    @NonNull
    public static String handleFirestoreError(
            @NonNull Throwable exception,
            @NonNull OperationType operationType,
            @Nullable String path) {
        Throwable rootError = unwrapRootCause(exception);

        FirebaseUser currentUser = null;
        try {
            currentUser = FirebaseAuth.getInstance().getCurrentUser();
        } catch (IllegalStateException ignored) {
        }

        JSONArray providerArray = new JSONArray();
        if (currentUser != null && currentUser.getProviderData() != null) {
            for (UserInfo provider : currentUser.getProviderData()) {
                JSONObject pObj = new JSONObject();
                try {
                    pObj.put("providerId", provider.getProviderId());
                    pObj.put("email", provider.getEmail());
                } catch (JSONException ignored) {
                }
                providerArray.put(pObj);
            }
        }

        JSONObject authInfo = new JSONObject();
        try {
            authInfo.put("userId", currentUser != null ? currentUser.getUid() : JSONObject.NULL);
            authInfo.put("email", currentUser != null ? currentUser.getEmail() : JSONObject.NULL);
            authInfo.put("emailVerified", currentUser != null ? currentUser.isEmailVerified() : JSONObject.NULL);
            authInfo.put("tenantId", currentUser != null ? currentUser.getTenantId() : JSONObject.NULL);
            authInfo.put("providerInfo", providerArray);
        } catch (JSONException ignored) {
        }

        JSONObject errorInfo = new JSONObject();
        try {
            String msg = rootError.getMessage();
            errorInfo.put("error", msg != null ? msg : rootError.toString());
            errorInfo.put("operationType", operationType.getValue());
            errorInfo.put("path", path != null ? path : JSONObject.NULL);
            errorInfo.put("authInfo", authInfo);
        } catch (JSONException ignored) {
        }

        String jsonString = errorInfo.toString();
        Log.e(TAG, "Firestore Error: " + jsonString);
        return jsonString;
    }

    @NonNull
    public static Throwable unwrapRootCause(@NonNull Throwable exception) {
        Throwable cursor = exception;
        Throwable firstNonCancel = null;
        int depth = 0;
        while (cursor != null && depth++ < 12) {
            if (cursor instanceof FirebaseFirestoreException) {
                return cursor;
            }
            if (firstNonCancel == null && !(cursor instanceof CancellationException)) {
                firstNonCancel = cursor;
            }
            cursor = cursor.getCause();
        }
        return firstNonCancel != null ? firstNonCancel : exception;
    }

    @NonNull
    public static String getFriendlyErrorMessage(@NonNull Context context, @NonNull Throwable exception) {
        Throwable root = unwrapRootCause(exception);
        if (root instanceof FirebaseFirestoreException) {
            FirebaseFirestoreException ffe = (FirebaseFirestoreException) root;
            if (ffe.getCode() == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                return context.getString(R.string.msg_firestore_permission_denied);
            }
            if (ffe.getCode() == FirebaseFirestoreException.Code.UNAVAILABLE) {
                return context.getString(R.string.msg_firestore_unavailable);
            }
            if (ffe.getCode() == FirebaseFirestoreException.Code.RESOURCE_EXHAUSTED) {
                return context.getString(R.string.msg_firestore_quota_exceeded);
            }
        }
        String raw = root.getLocalizedMessage();
        return raw != null && !raw.trim().isEmpty()
                ? raw.trim()
                : context.getString(R.string.msg_firestore_generic_error);
    }
}
