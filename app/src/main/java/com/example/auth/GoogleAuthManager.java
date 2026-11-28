package com.example.auth;

import android.app.Activity;
import android.content.Context;
import android.content.res.Resources;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.credentials.ClearCredentialStateRequest;
import androidx.credentials.Credential;
import androidx.credentials.CredentialManager;
import androidx.credentials.CredentialManagerCallback;
import androidx.credentials.CustomCredential;
import androidx.credentials.GetCredentialRequest;
import androidx.credentials.GetCredentialResponse;
import androidx.credentials.exceptions.ClearCredentialException;
import androidx.credentials.exceptions.GetCredentialCancellationException;
import androidx.credentials.exceptions.GetCredentialException;

import com.example.R;
import com.google.android.libraries.identity.googleid.GetGoogleIdOption;
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption;
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential;
import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.AuthCredential;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.GoogleAuthProvider;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Pure Java authentication manager for ProxyFlow supporting Google Sign-In exclusively
 * via Jetpack Credential Manager (GetSignInWithGoogleOption / GetGoogleIdOption) + Firebase Auth.
 */
public final class GoogleAuthManager {

    private static final String TAG = "Auth";
    private static final ExecutorService AUTH_EXECUTOR = Executors.newCachedThreadPool();
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    public interface AuthCallback {
        void onSuccess(@NonNull FirebaseUser user);

        void onCancelled();

        void onError(@NonNull String message);
    }

    private GoogleAuthManager() {
    }

    @Nullable
    public static FirebaseAuth getFirebaseAuth(@NonNull Context context) {
        try {
            if (FirebaseApp.getApps(context.getApplicationContext()).isEmpty()) {
                FirebaseApp.initializeApp(context.getApplicationContext());
            }
            if (FirebaseApp.getApps(context.getApplicationContext()).isEmpty()) {
                return null;
            }
            return FirebaseAuth.getInstance();
        } catch (IllegalStateException e) {
            Log.w(TAG, "FirebaseAuth unavailable: " + e.getMessage());
            return null;
        }
    }

    @Nullable
    public static FirebaseUser getCurrentUser(@NonNull Context context) {
        FirebaseAuth auth = getFirebaseAuth(context);
        return auth != null ? auth.getCurrentUser() : null;
    }

    @NonNull
    public static String getDisplayLabel(@Nullable FirebaseUser user, @NonNull String signedOutLabel) {
        if (user == null) {
            return signedOutLabel;
        }
        String name = user.getDisplayName();
        if (!TextUtils.isEmpty(name)) {
            return name.trim();
        }
        String email = user.getEmail();
        if (!TextUtils.isEmpty(email)) {
            int atIdx = email.indexOf('@');
            return atIdx > 0 ? email.substring(0, atIdx) : email;
        }
        return signedOutLabel;
    }

    @Nullable
    private static String resolveWebClientId(@NonNull Context context) {
        try {
            String clientId = context.getString(R.string.default_web_client_id);
            return TextUtils.isEmpty(clientId) ? null : clientId.trim();
        } catch (Resources.NotFoundException e) {
            Log.w(TAG, "default_web_client_id string resource not found", e);
            return null;
        }
    }

    /**
     * Silent startup auto sign-in using GetGoogleIdOption (one credential option per request).
     */
    public static void attemptAutoSignIn(@NonNull Context context, @Nullable AuthCallback callback) {
        FirebaseAuth auth = getFirebaseAuth(context);
        if (auth == null) {
            if (callback != null) {
                MAIN_HANDLER.post(callback::onCancelled);
            }
            return;
        }
        FirebaseUser existing = auth.getCurrentUser();
        if (existing != null) {
            if (callback != null) {
                MAIN_HANDLER.post(() -> callback.onSuccess(existing));
            }
            return;
        }

        String clientId = resolveWebClientId(context);
        if (clientId == null) {
            if (callback != null) {
                MAIN_HANDLER.post(callback::onCancelled);
            }
            return;
        }

        GetGoogleIdOption googleIdOption = new GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(true)
                .setServerClientId(clientId)
                .setAutoSelectEnabled(true)
                .build();

        GetCredentialRequest request = new GetCredentialRequest.Builder()
                .addCredentialOption(googleIdOption)
                .build();

        try {
            CredentialManager credentialManager = CredentialManager.create(context);
            credentialManager.getCredentialAsync(
                    context,
                    request,
                    null,
                    AUTH_EXECUTOR,
                    new CredentialManagerCallback<>() {
                        @Override
                        public void onResult(GetCredentialResponse result) {
                            handleCredentialResponse(context.getApplicationContext(), auth, result, callback);
                        }

                        @Override
                        public void onError(@NonNull GetCredentialException e) {
                            if (e instanceof GetCredentialCancellationException) {
                                Log.w(TAG, "Silent Google Sign-In cancelled: " + e.getMessage());
                            } else {
                                Log.w(TAG, "Silent Google Sign-In failed: " + e.getMessage());
                            }
                            if (callback != null) {
                                MAIN_HANDLER.post(callback::onCancelled);
                            }
                        }
                    }
            );
        } catch (Exception e) {
            Log.w(TAG, "Silent Google Sign-In failed to launch: " + e.getMessage(), e);
            if (callback != null) {
                MAIN_HANDLER.post(callback::onCancelled);
            }
        }
    }

    /**
     * Interactive Google Sign-In using GetSignInWithGoogleOption (one credential option per request).
     */
    public static void signInWithGoogle(@NonNull Activity activity, @NonNull AuthCallback callback) {
        FirebaseAuth auth = getFirebaseAuth(activity);
        String clientId = resolveWebClientId(activity);
        if (auth == null || clientId == null) {
            MAIN_HANDLER.post(() -> callback.onError(activity.getString(R.string.msg_google_config_missing)));
            return;
        }

        GetSignInWithGoogleOption signInOption = new GetSignInWithGoogleOption.Builder(clientId).build();
        GetCredentialRequest request = new GetCredentialRequest.Builder()
                .addCredentialOption(signInOption)
                .build();

        try {
            CredentialManager credentialManager = CredentialManager.create(activity);
            credentialManager.getCredentialAsync(
                    activity,
                    request,
                    null,
                    AUTH_EXECUTOR,
                    new CredentialManagerCallback<>() {
                        @Override
                        public void onResult(GetCredentialResponse result) {
                            handleCredentialResponse(activity.getApplicationContext(), auth, result, callback);
                        }

                        @Override
                        public void onError(@NonNull GetCredentialException e) {
                            if (e instanceof GetCredentialCancellationException) {
                                Log.w(TAG, "Google Sign-In flow cancelled or dismissed: " + e.getMessage(), e);
                                MAIN_HANDLER.post(callback::onCancelled);
                            } else {
                                Log.e(TAG, "Google Sign-In failed", e);
                                String msg = e.getLocalizedMessage();
                                String fallback = msg != null && !msg.isEmpty()
                                        ? msg
                                        : activity.getString(R.string.msg_google_login_cancelled);
                                MAIN_HANDLER.post(() -> callback.onError(fallback));
                            }
                        }
                    }
            );
        } catch (Exception e) {
            Log.e(TAG, "Google Sign-In failed to launch", e);
            String msg = e.getLocalizedMessage();
            String fallback = msg != null && !msg.isEmpty()
                    ? msg
                    : activity.getString(R.string.msg_google_config_missing);
            MAIN_HANDLER.post(() -> callback.onError(fallback));
        }
    }

    private static void handleCredentialResponse(
            @NonNull Context appContext,
            @NonNull FirebaseAuth auth,
            @NonNull GetCredentialResponse response,
            @Nullable AuthCallback callback) {
        Credential credential = response.getCredential();
        if (!(credential instanceof CustomCredential)
                || !GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL.equals(credential.getType())) {
            Log.e(TAG, "Unexpected credential type: " + credential.getType());
            if (callback != null) {
                MAIN_HANDLER.post(() -> callback.onError("Unexpected credential type"));
            }
            return;
        }

        try {
            GoogleIdTokenCredential googleCred = GoogleIdTokenCredential.createFrom(credential.getData());
            String idToken = googleCred.getIdToken();
            AuthCredential firebaseCred = GoogleAuthProvider.getCredential(idToken, null);
            auth.signInWithCredential(firebaseCred)
                    .addOnSuccessListener(authResult -> {
                        FirebaseUser user = authResult.getUser();
                        if (user != null) {
                            if (callback != null) {
                                MAIN_HANDLER.post(() -> callback.onSuccess(user));
                            }
                        } else if (callback != null) {
                            MAIN_HANDLER.post(() -> callback.onError("Firebase user missing"));
                        }
                    })
                    .addOnFailureListener(e -> {
                        Log.e(TAG, "Firebase signInWithCredential failed", e);
                        if (callback != null) {
                            String msg = e.getLocalizedMessage() != null ? e.getLocalizedMessage() : "Authentication failed";
                            MAIN_HANDLER.post(() -> callback.onError(msg));
                        }
                    });
        } catch (Exception e) {
            Log.e(TAG, "Invalid Google ID token", e);
            if (callback != null) {
                String msg = e.getLocalizedMessage() != null ? e.getLocalizedMessage() : "Invalid Google ID token";
                MAIN_HANDLER.post(() -> callback.onError(msg));
            }
        }
    }

    /**
     * Clears Jetpack Credential Manager state before signing out from Firebase Auth.
     */
    public static void signOut(@NonNull Context context, @Nullable Runnable onComplete) {
        Context appContext = context.getApplicationContext();
        FirebaseAuth auth = getFirebaseAuth(appContext);
        try {
            CredentialManager credentialManager = CredentialManager.create(appContext);
            credentialManager.clearCredentialStateAsync(
                    new ClearCredentialStateRequest(),
                    null,
                    AUTH_EXECUTOR,
                    new CredentialManagerCallback<>() {
                        @Override
                        public void onResult(Void result) {
                            finishSignOut(appContext, auth, onComplete);
                        }

                        @Override
                        public void onError(@NonNull ClearCredentialException e) {
                            Log.w(TAG, "Failed to clear credential state: " + e.getMessage());
                            finishSignOut(appContext, auth, onComplete);
                        }
                    }
            );
        } catch (Exception e) {
            Log.w(TAG, "CredentialManager clearCredentialStateAsync threw exception: " + e.getMessage(), e);
            finishSignOut(appContext, auth, onComplete);
        }
    }

    private static void finishSignOut(
            @NonNull Context appContext,
            @Nullable FirebaseAuth auth,
            @Nullable Runnable onComplete) {
        if (auth != null) {
            auth.signOut();
        }
        if (onComplete != null) {
            MAIN_HANDLER.post(onComplete);
        }
    }
}
