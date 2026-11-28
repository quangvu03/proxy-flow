package com.example.data;

import android.content.Context;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.example.R;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestoreException;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Pure-Java HTTPS REST v1 fallback client for Cloud Firestore.
 * Uses the signed-in user's Firebase ID token so all server-side firestore.rules
 * apply identically even when gRPC/HTTP2 streams fail with UNAVAILABLE.
 */
public final class FirestoreRestClient {

    private static final String BASE_HOST = "https://firestore.googleapis.com/v1";
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 20_000;

    public static final class RestDocument {
        private final String id;
        private final boolean exists;
        private final Map<String, Object> data;

        public RestDocument(@NonNull String id, boolean exists, @NonNull Map<String, Object> data) {
            this.id = id;
            this.exists = exists;
            this.data = data;
        }

        @NonNull
        public String getId() {
            return id;
        }

        public boolean exists() {
            return exists;
        }

        @NonNull
        public Map<String, Object> getData() {
            return data;
        }

        @Nullable
        public String getString(@NonNull String key) {
            Object val = data.get(key);
            return val instanceof String ? (String) val : null;
        }

        @Nullable
        public Boolean getBoolean(@NonNull String key) {
            Object val = data.get(key);
            return val instanceof Boolean ? (Boolean) val : null;
        }

        @Nullable
        public Number getNumber(@NonNull String key) {
            Object val = data.get(key);
            return val instanceof Number ? (Number) val : null;
        }

        public int getInt(@NonNull String key) {
            Number n = getNumber(key);
            return n != null ? Math.max(0, n.intValue()) : 0;
        }

        public long getLong(@NonNull String key, long fallback) {
            Number n = getNumber(key);
            return n != null ? n.longValue() : fallback;
        }

        public long getTimestampMs(@NonNull String key) {
            Object val = data.get(key);
            if (val instanceof Date) {
                return ((Date) val).getTime();
            }
            if (val instanceof Number) {
                return ((Number) val).longValue();
            }
            return 0L;
        }

        @NonNull
        public List<String> getStringList(@NonNull String key) {
            Object val = data.get(key);
            if (!(val instanceof List<?>)) {
                return Collections.emptyList();
            }
            List<String> result = new ArrayList<>();
            for (Object item : (List<?>) val) {
                if (item instanceof String && !((String) item).trim().isEmpty()) {
                    result.add(((String) item).trim());
                }
            }
            return result;
        }
    }

    private static final class FirebaseTarget {
        final String projectId;
        final String databaseId;
        final String apiKey;

        FirebaseTarget(String projectId, String databaseId, String apiKey) {
            this.projectId = projectId;
            this.databaseId = databaseId;
            this.apiKey = apiKey;
        }

        String documentResourceName(@NonNull String relativePath) {
            return "projects/" + projectId + "/databases/" + databaseId + "/documents/" + relativePath;
        }

        String documentsBaseUrl() {
            return BASE_HOST + "/projects/" + encodeSegment(projectId)
                    + "/databases/" + encodeSegment(databaseId) + "/documents";
        }
    }

    private FirestoreRestClient() {
    }

    @NonNull
    private static FirebaseTarget resolveTarget(@NonNull Context context) throws FirebaseFirestoreException {
        Context appCtx = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        String projectId = getStringSafe(appCtx, R.string.project_id);
        String databaseId = getStringSafe(appCtx, R.string.firestore_database_id);
        String apiKey = getStringSafe(appCtx, R.string.google_api_key);
        if (projectId.isEmpty() || databaseId.isEmpty()) {
            throw new FirebaseFirestoreException(
                    "Missing Firebase project or database configuration",
                    FirebaseFirestoreException.Code.FAILED_PRECONDITION
            );
        }
        return new FirebaseTarget(projectId, databaseId, apiKey);
    }

    @NonNull
    private static String getStringSafe(@NonNull Context context, int resId) {
        try {
            String v = context.getString(resId);
            return v != null ? v.trim() : "";
        } catch (Exception e) {
            return "";
        }
    }

    @NonNull
    private static String fetchIdToken(@NonNull FirebaseUser user, boolean forceRefresh)
            throws FirebaseFirestoreException {
        try {
            String token = Tasks.await(user.getIdToken(forceRefresh), 15L, TimeUnit.SECONDS).getToken();
            if (TextUtils.isEmpty(token)) {
                throw new FirebaseFirestoreException(
                        "Empty Firebase auth token",
                        FirebaseFirestoreException.Code.UNAUTHENTICATED
                );
            }
            return token;
        } catch (FirebaseFirestoreException e) {
            throw e;
        } catch (Exception e) {
            throw new FirebaseFirestoreException(
                    "Failed to obtain Firebase auth token: " + safeMsg(e),
                    FirebaseFirestoreException.Code.UNAUTHENTICATED,
                    e
            );
        }
    }

    @NonNull
    public static RestDocument getDocument(
            @NonNull Context context,
            @NonNull FirebaseUser user,
            @NonNull String relativeDocPath) throws Exception {
        FirebaseTarget target = resolveTarget(context);
        String encodedPath = encodePathSegments(relativeDocPath);
        String url = target.documentsBaseUrl() + "/" + encodedPath;
        String docId = extractLastSegment(relativeDocPath);

        HttpResult res = executeAuthenticatedHttp(url, "GET", null, target.apiKey, user);
        if (res.code == 404) {
            if (isDatabaseMissingMessage(res.body)) {
                throw mapHttpError(res.code, res.body);
            }
            return new RestDocument(docId, false, Collections.emptyMap());
        }
        if (res.code < 200 || res.code >= 300) {
            throw mapHttpError(res.code, res.body);
        }
        return parseDocumentJson(new JSONObject(res.body), docId);
    }

    @NonNull
    public static List<RestDocument> listDocuments(
            @NonNull Context context,
            @NonNull FirebaseUser user,
            @NonNull String relativeCollectionPath) throws Exception {
        FirebaseTarget target = resolveTarget(context);
        String encodedPath = encodePathSegments(relativeCollectionPath);
        List<RestDocument> docs = new ArrayList<>();
        String pageToken = null;

        do {
            StringBuilder url = new StringBuilder(target.documentsBaseUrl())
                    .append("/")
                    .append(encodedPath)
                    .append("?pageSize=500");
            if (!TextUtils.isEmpty(pageToken)) {
                url.append("&pageToken=").append(encodeSegment(pageToken));
            }
            HttpResult res = executeAuthenticatedHttp(url.toString(), "GET", null, target.apiKey, user);
            if (res.code < 200 || res.code >= 300) {
                throw mapHttpError(res.code, res.body);
            }
            JSONObject root = TextUtils.isEmpty(res.body) ? new JSONObject() : new JSONObject(res.body);
            JSONArray arr = root.optJSONArray("documents");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject item = arr.optJSONObject(i);
                    if (item != null) {
                        docs.add(parseDocumentJson(item, ""));
                    }
                }
            }
            pageToken = root.optString("nextPageToken", "");
        } while (!TextUtils.isEmpty(pageToken));

        return docs;
    }

    public static void commitDocument(
            @NonNull Context context,
            @NonNull FirebaseUser user,
            @NonNull String relativeDocPath,
            @Nullable Boolean requireExists,
            @NonNull Map<String, Object> fields,
            @NonNull Collection<String> serverTimestampFields) throws Exception {
        FirebaseTarget target = resolveTarget(context);
        String url = target.documentsBaseUrl() + ":commit";
        JSONObject body = buildCommitPayload(
                target.documentResourceName(relativeDocPath),
                requireExists,
                fields,
                serverTimestampFields
        );
        HttpResult res = executeAuthenticatedHttp(url, "POST", body.toString(), target.apiKey, user);
        if (res.code < 200 || res.code >= 300) {
            throw mapHttpError(res.code, res.body);
        }
    }

    @VisibleForTesting
    @NonNull
    public static JSONObject buildCommitPayload(
            @NonNull String documentResourceName,
            @Nullable Boolean requireExists,
            @NonNull Map<String, Object> fields,
            @NonNull Collection<String> serverTimestampFields) throws JSONException {
        JSONObject fieldsJson = new JSONObject();
        List<String> maskPaths = new ArrayList<>();

        for (Map.Entry<String, Object> entry : fields.entrySet()) {
            String key = entry.getKey();
            Object val = entry.getValue();
            if (key == null || val == null || val instanceof FieldValue) {
                continue;
            }
            JSONObject encoded = encodeValue(val);
            if (encoded != null) {
                fieldsJson.put(key, encoded);
                maskPaths.add(key);
            }
        }

        JSONObject updateObj = new JSONObject();
        updateObj.put("name", documentResourceName);
        updateObj.put("fields", fieldsJson);

        JSONObject writeObj = new JSONObject();
        writeObj.put("update", updateObj);

        if (!Boolean.FALSE.equals(requireExists)) {
            JSONObject maskObj = new JSONObject();
            JSONArray pathArr = new JSONArray();
            for (String path : maskPaths) {
                pathArr.put(path);
            }
            maskObj.put("fieldPaths", pathArr);
            writeObj.put("updateMask", maskObj);
        }

        if (!serverTimestampFields.isEmpty()) {
            JSONArray transforms = new JSONArray();
            for (String tsField : serverTimestampFields) {
                if (tsField != null && !tsField.trim().isEmpty()) {
                    JSONObject transform = new JSONObject();
                    transform.put("fieldPath", tsField.trim());
                    transform.put("setToServerValue", "REQUEST_TIME");
                    transforms.put(transform);
                }
            }
            if (transforms.length() > 0) {
                writeObj.put("updateTransforms", transforms);
            }
        }

        if (requireExists != null) {
            JSONObject precondition = new JSONObject();
            precondition.put("exists", requireExists);
            writeObj.put("currentDocument", precondition);
        }

        JSONArray writes = new JSONArray();
        writes.put(writeObj);
        JSONObject payload = new JSONObject();
        payload.put("writes", writes);
        return payload;
    }

    @VisibleForTesting
    @NonNull
    public static RestDocument parseDocumentJson(@NonNull JSONObject docObj, @NonNull String fallbackId) {
        String name = docObj.optString("name", "");
        String docId = !TextUtils.isEmpty(name) ? extractLastSegment(name) : fallbackId;
        Map<String, Object> parsed = new HashMap<>();
        JSONObject fieldsObj = docObj.optJSONObject("fields");
        if (fieldsObj != null) {
            Iterator<String> keys = fieldsObj.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                JSONObject valObj = fieldsObj.optJSONObject(key);
                if (valObj != null) {
                    parsed.put(key, decodeValue(valObj));
                }
            }
        }
        return new RestDocument(docId, true, parsed);
    }

    @Nullable
    private static JSONObject encodeValue(@NonNull Object value) throws JSONException {
        JSONObject out = new JSONObject();
        if (value instanceof String) {
            out.put("stringValue", value);
            return out;
        }
        if (value instanceof Boolean) {
            out.put("booleanValue", value);
            return out;
        }
        if (value instanceof Integer || value instanceof Long) {
            out.put("integerValue", String.valueOf(((Number) value).longValue()));
            return out;
        }
        if (value instanceof Number) {
            out.put("doubleValue", ((Number) value).doubleValue());
            return out;
        }
        if (value instanceof Iterable<?>) {
            JSONArray arr = new JSONArray();
            for (Object item : (Iterable<?>) value) {
                if (item != null) {
                    JSONObject encoded = encodeValue(item);
                    if (encoded != null) {
                        arr.put(encoded);
                    }
                }
            }
            JSONObject arrayVal = new JSONObject();
            arrayVal.put("values", arr);
            out.put("arrayValue", arrayVal);
            return out;
        }
        if (value instanceof Map<?, ?>) {
            JSONObject fields = new JSONObject();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    JSONObject encoded = encodeValue(entry.getValue());
                    if (encoded != null) {
                        fields.put(String.valueOf(entry.getKey()), encoded);
                    }
                }
            }
            JSONObject mapVal = new JSONObject();
            mapVal.put("fields", fields);
            out.put("mapValue", mapVal);
            return out;
        }
        return null;
    }

    @Nullable
    private static Object decodeValue(@NonNull JSONObject valObj) {
        if (valObj.has("stringValue")) {
            return valObj.optString("stringValue", "");
        }
        if (valObj.has("booleanValue")) {
            return valObj.optBoolean("booleanValue", false);
        }
        if (valObj.has("integerValue")) {
            String raw = valObj.optString("integerValue", "0");
            try {
                return Long.parseLong(raw);
            } catch (NumberFormatException e) {
                return 0L;
            }
        }
        if (valObj.has("doubleValue")) {
            return valObj.optDouble("doubleValue", 0.0);
        }
        if (valObj.has("timestampValue")) {
            return parseIsoTimestamp(valObj.optString("timestampValue", ""));
        }
        if (valObj.has("arrayValue")) {
            JSONObject arrWrapper = valObj.optJSONObject("arrayValue");
            JSONArray values = arrWrapper != null ? arrWrapper.optJSONArray("values") : null;
            List<Object> list = new ArrayList<>();
            if (values != null) {
                for (int i = 0; i < values.length(); i++) {
                    JSONObject entry = values.optJSONObject(i);
                    if (entry != null) {
                        list.add(decodeValue(entry));
                    }
                }
            }
            return list;
        }
        if (valObj.has("mapValue")) {
            JSONObject mapWrapper = valObj.optJSONObject("mapValue");
            JSONObject fields = mapWrapper != null ? mapWrapper.optJSONObject("fields") : null;
            Map<String, Object> map = new HashMap<>();
            if (fields != null) {
                Iterator<String> keys = fields.keys();
                while (keys.hasNext()) {
                    String k = keys.next();
                    JSONObject child = fields.optJSONObject(k);
                    if (child != null) {
                        map.put(k, decodeValue(child));
                    }
                }
            }
            return map;
        }
        return null;
    }

    @Nullable
    private static Date parseIsoTimestamp(@Nullable String iso) {
        if (iso == null || iso.trim().isEmpty()) {
            return null;
        }
        String clean = iso.trim();
        // Normalize nanosecond/microsecond precision down to milliseconds for SimpleDateFormat
        int dotIdx = clean.indexOf('.');
        int zIdx = clean.indexOf('Z');
        if (dotIdx > 0 && zIdx > dotIdx) {
            String frac = clean.substring(dotIdx + 1, zIdx);
            if (frac.length() > 3) {
                frac = frac.substring(0, 3);
            } else {
                while (frac.length() < 3) {
                    frac = frac + "0";
                }
            }
            clean = clean.substring(0, dotIdx + 1) + frac + "Z";
            return parseWithPattern(clean, "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
        }
        return parseWithPattern(clean, "yyyy-MM-dd'T'HH:mm:ss'Z'");
    }

    @Nullable
    private static Date parseWithPattern(@NonNull String value, @NonNull String pattern) {
        try {
            SimpleDateFormat fmt = new SimpleDateFormat(pattern, Locale.US);
            fmt.setTimeZone(TimeZone.getTimeZone("UTC"));
            return fmt.parse(value);
        } catch (ParseException e) {
            return null;
        }
    }

    private static final class HttpResult {
        final int code;
        final String body;

        HttpResult(int code, String body) {
            this.code = code;
            this.body = body;
        }
    }

    @NonNull
    private static HttpResult executeAuthenticatedHttp(
            @NonNull String urlStr,
            @NonNull String method,
            @Nullable String jsonBody,
            @NonNull String apiKey,
            @NonNull FirebaseUser user) throws IOException, FirebaseFirestoreException {
        String token = fetchIdToken(user, false);
        HttpResult res = performHttp(urlStr, method, jsonBody, apiKey, token);
        if (res.code == 401) {
            token = fetchIdToken(user, true);
            res = performHttp(urlStr, method, jsonBody, apiKey, token);
        }
        return res;
    }

    @NonNull
    private static HttpResult performHttp(
            @NonNull String urlStr,
            @NonNull String method,
            @Nullable String jsonBody,
            @NonNull String apiKey,
            @NonNull String bearerToken) throws IOException {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod(method);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setUseCaches(false);
            conn.setRequestProperty("Authorization", "Bearer " + bearerToken);
            if (!apiKey.isEmpty()) {
                conn.setRequestProperty("X-Goog-Api-Key", apiKey);
            }
            conn.setRequestProperty("Accept", "application/json");
            if (jsonBody != null) {
                byte[] bytes = jsonBody.getBytes(StandardCharsets.UTF_8);
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                conn.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(bytes);
                }
            }
            int code = conn.getResponseCode();
            InputStream stream = code >= 200 && code < 400 ? conn.getInputStream() : conn.getErrorStream();
            String body = stream != null ? readAll(stream) : "";
            return new HttpResult(code, body);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    @NonNull
    private static String readAll(@NonNull InputStream input) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int read;
        while ((read = input.read(chunk)) != -1) {
            buffer.write(chunk, 0, read);
        }
        return buffer.toString(StandardCharsets.UTF_8.name());
    }

    @NonNull
    private static FirebaseFirestoreException mapHttpError(int httpCode, @Nullable String body) {
        String status = "";
        String message = "";
        if (body != null && !body.trim().isEmpty()) {
            try {
                JSONObject root = new JSONObject(body);
                JSONObject err = root.optJSONObject("error");
                if (err != null) {
                    status = err.optString("status", "");
                    message = err.optString("message", "");
                }
            } catch (JSONException ignored) {
                message = body.trim();
            }
        }
        if (message.isEmpty()) {
            message = "Firestore REST HTTP " + httpCode;
        } else {
            message = "HTTP " + httpCode + ": " + message;
        }
        FirebaseFirestoreException.Code mapped;
        if ("PERMISSION_DENIED".equals(status) || httpCode == 403) {
            mapped = FirebaseFirestoreException.Code.PERMISSION_DENIED;
        } else if ("UNAUTHENTICATED".equals(status) || httpCode == 401) {
            mapped = FirebaseFirestoreException.Code.UNAUTHENTICATED;
        } else if ("NOT_FOUND".equals(status) || httpCode == 404) {
            mapped = FirebaseFirestoreException.Code.NOT_FOUND;
        } else if ("RESOURCE_EXHAUSTED".equals(status) || httpCode == 429) {
            mapped = FirebaseFirestoreException.Code.RESOURCE_EXHAUSTED;
        } else if ("DEADLINE_EXCEEDED".equals(status) || httpCode == 504) {
            mapped = FirebaseFirestoreException.Code.DEADLINE_EXCEEDED;
        } else if ("UNAVAILABLE".equals(status) || httpCode == 503 || httpCode == 502) {
            mapped = FirebaseFirestoreException.Code.UNAVAILABLE;
        } else if ("FAILED_PRECONDITION".equals(status) || httpCode == 400) {
            mapped = FirebaseFirestoreException.Code.FAILED_PRECONDITION;
        } else {
            mapped = FirebaseFirestoreException.Code.UNKNOWN;
        }
        return new FirebaseFirestoreException(message, mapped);
    }

    private static boolean isDatabaseMissingMessage(@Nullable String body) {
        if (body == null) {
            return false;
        }
        String lower = body.toLowerCase(Locale.ROOT);
        return lower.contains("database") && lower.contains("does not exist");
    }

    @NonNull
    private static String encodePathSegments(@NonNull String path) {
        String[] parts = path.split("/");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part == null || part.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('/');
            }
            sb.append(encodeSegment(part));
        }
        return sb.toString();
    }

    @NonNull
    private static String encodeSegment(@NonNull String segment) {
        try {
            return URLEncoder.encode(segment, "UTF-8").replace("+", "%20");
        } catch (Exception e) {
            return segment;
        }
    }

    @NonNull
    private static String extractLastSegment(@NonNull String path) {
        int idx = path.lastIndexOf('/');
        return idx >= 0 && idx + 1 < path.length() ? path.substring(idx + 1) : path;
    }

    @NonNull
    private static String safeMsg(@NonNull Throwable t) {
        return t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
    }
}
