package com.example.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import com.example.model.FirewallRule;
import com.example.model.ProxyConfig;
import com.example.model.ProxyLogEntry;

import java.util.ArrayList;
import java.util.List;

/**
 * dùng sqlite để lưu trữ cấu hình proxy, nhật ký và cài đặt ứng dụng.
 */
public class ProxyDatabaseHelper extends SQLiteOpenHelper {

    private static final String DATABASE_NAME = "proxyflow.db";
    private static final int DATABASE_VERSION = 3;

    private static volatile ProxyDatabaseHelper instance;

    private static final String TABLE_PROXIES = "proxies";
    private static final String COL_ID = "id";
    private static final String COL_NAME = "name";
    private static final String COL_HOST = "host";
    private static final String COL_PORT = "port";
    private static final String COL_USERNAME = "username";
    private static final String COL_PASSWORD = "password";
    private static final String COL_ENABLED = "enabled";
    private static final String COL_LATENCY_MS = "latency_ms";
    private static final String COL_EXIT_IP = "exit_ip";
    private static final String COL_COUNTRY = "country";
    private static final String COL_LAST_CHECKED_AT = "last_checked_at";
    private static final String COL_FAIL_COUNT = "fail_count";
    private static final String COL_SORT_ORDER = "sort_order";

    private static final String TABLE_LOGS = "logs";
    private static final String COL_LOG_ID = "id";
    private static final String COL_LOG_TS = "timestamp";
    private static final String COL_LOG_LEVEL = "level";
    private static final String COL_LOG_MESSAGE = "message";

    private static final String TABLE_SETTINGS = "app_settings";
    private static final String COL_SETTING_KEY = "setting_key";
    private static final String COL_SETTING_VALUE = "setting_value";

    private static final String TABLE_FIREWALL_RULES = "firewall_rules";
    private static final String COL_FW_ID = "id";
    private static final String COL_FW_NAME = "name";
    private static final String COL_FW_ENABLED = "enabled";
    private static final String COL_FW_PACKAGES = "blocked_packages";
    private static final String COL_FW_TARGET = "target_address";
    private static final String COL_FW_CREATED_AT = "created_at";
    private static final String KEY_FIREWALL_SEEDED = "firewall_rules_seeded";

    public static ProxyDatabaseHelper getInstance(Context context) {
        if (instance == null) {
            synchronized (ProxyDatabaseHelper.class) {
                if (instance == null) {
                    instance = new ProxyDatabaseHelper(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    public ProxyDatabaseHelper(Context context) {
        super(context.getApplicationContext(), DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        String createProxies = "CREATE TABLE " + TABLE_PROXIES + " ("
                + COL_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, "
                + COL_NAME + " TEXT NOT NULL DEFAULT '', "
                + COL_HOST + " TEXT NOT NULL, "
                + COL_PORT + " INTEGER NOT NULL, "
                + COL_USERNAME + " TEXT NOT NULL DEFAULT '', "
                + COL_PASSWORD + " TEXT NOT NULL DEFAULT '', "
                + COL_ENABLED + " INTEGER NOT NULL DEFAULT 1, "
                + COL_LATENCY_MS + " INTEGER NOT NULL DEFAULT -1, "
                + COL_EXIT_IP + " TEXT NOT NULL DEFAULT '', "
                + COL_COUNTRY + " TEXT NOT NULL DEFAULT '', "
                + COL_LAST_CHECKED_AT + " INTEGER NOT NULL DEFAULT 0, "
                + COL_FAIL_COUNT + " INTEGER NOT NULL DEFAULT 0, "
                + COL_SORT_ORDER + " INTEGER NOT NULL DEFAULT 0"
                + ")";
        db.execSQL(createProxies);

        String createLogs = "CREATE TABLE " + TABLE_LOGS + " ("
                + COL_LOG_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, "
                + COL_LOG_TS + " INTEGER NOT NULL, "
                + COL_LOG_LEVEL + " TEXT NOT NULL, "
                + COL_LOG_MESSAGE + " TEXT NOT NULL"
                + ")";
        db.execSQL(createLogs);

        createSettingsTable(db);
        createFirewallRulesTableAndSeed(db);
    }

    private void createSettingsTable(SQLiteDatabase db) {
        String createSettings = "CREATE TABLE IF NOT EXISTS " + TABLE_SETTINGS + " ("
                + COL_SETTING_KEY + " TEXT PRIMARY KEY, "
                + COL_SETTING_VALUE + " TEXT NOT NULL"
                + ")";
        db.execSQL(createSettings);
    }

    private void createFirewallRulesTableAndSeed(SQLiteDatabase db) {
        String createFirewall = "CREATE TABLE IF NOT EXISTS " + TABLE_FIREWALL_RULES + " ("
                + COL_FW_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, "
                + COL_FW_NAME + " TEXT NOT NULL, "
                + COL_FW_ENABLED + " INTEGER NOT NULL DEFAULT 1, "
                + COL_FW_PACKAGES + " TEXT NOT NULL DEFAULT '', "
                + COL_FW_TARGET + " TEXT NOT NULL DEFAULT '', "
                + COL_FW_CREATED_AT + " INTEGER NOT NULL DEFAULT 0"
                + ")";
        db.execSQL(createFirewall);

        boolean seeded = false;
        try (Cursor c = db.query(TABLE_SETTINGS, new String[]{COL_SETTING_VALUE},
                COL_SETTING_KEY + " = ?", new String[]{KEY_FIREWALL_SEEDED}, null, null, null)) {
            seeded = c.moveToFirst() && "true".equals(c.getString(0));
        }
        if (!seeded) {
            long count = 0;
            try (Cursor countCursor = db.rawQuery("SELECT COUNT(*) FROM " + TABLE_FIREWALL_RULES, null)) {
                if (countCursor.moveToFirst()) {
                    count = countCursor.getLong(0);
                }
            }
            if (count == 0) {
                ContentValues cv = new ContentValues();
                cv.put(COL_FW_NAME, "Quy tắc 1");
                cv.put(COL_FW_ENABLED, 1);
                cv.put(COL_FW_PACKAGES, "");
                cv.put(COL_FW_TARGET, "");
                cv.put(COL_FW_CREATED_AT, System.currentTimeMillis());
                db.insert(TABLE_FIREWALL_RULES, null, cv);
            }
            ContentValues seedFlag = new ContentValues();
            seedFlag.put(COL_SETTING_KEY, KEY_FIREWALL_SEEDED);
            seedFlag.put(COL_SETTING_VALUE, "true");
            db.insertWithOnConflict(TABLE_SETTINGS, null, seedFlag, SQLiteDatabase.CONFLICT_REPLACE);
        }
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            createSettingsTable(db);
        }
        if (oldVersion < 3) {
            createSettingsTable(db);
            createFirewallRulesTableAndSeed(db);
        }
    }

    public synchronized String getSetting(String key, String defaultValue) {
        if (key == null || key.isEmpty()) return defaultValue;
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor cursor = db.query(TABLE_SETTINGS, new String[]{COL_SETTING_VALUE},
                COL_SETTING_KEY + " = ?", new String[]{key}, null, null, null)) {
            if (cursor.moveToFirst()) {
                return cursor.getString(0);
            }
        }
        return defaultValue;
    }

    public synchronized void putSetting(String key, String value) {
        if (key == null || key.isEmpty()) return;
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put(COL_SETTING_KEY, key);
        cv.put(COL_SETTING_VALUE, value != null ? value : "");
        db.insertWithOnConflict(TABLE_SETTINGS, null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public synchronized boolean hasSetting(String key) {
        if (key == null || key.isEmpty()) return false;
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor cursor = db.query(TABLE_SETTINGS, new String[]{COL_SETTING_KEY},
                COL_SETTING_KEY + " = ?", new String[]{key}, null, null, null)) {
            return cursor.moveToFirst();
        }
    }

    public synchronized void deleteSetting(String key) {
        if (key == null || key.isEmpty()) return;
        SQLiteDatabase db = getWritableDatabase();
        db.delete(TABLE_SETTINGS, COL_SETTING_KEY + " = ?", new String[]{key});
    }

    public synchronized long insertProxy(ProxyConfig proxy) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = toContentValues(proxy);
        long id = db.insert(TABLE_PROXIES, null, cv);
        proxy.setId(id);
        return id;
    }

    public synchronized int updateProxy(ProxyConfig proxy) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = toContentValues(proxy);
        return db.update(TABLE_PROXIES, cv, COL_ID + " = ?", new String[]{String.valueOf(proxy.getId())});
    }

    public synchronized void updateProxyTelemetry(long proxyId, long latencyMs, String exitIp,
                                                  String country, long checkedAt, int failCount) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put(COL_LATENCY_MS, latencyMs);
        cv.put(COL_EXIT_IP, exitIp != null ? exitIp : "");
        cv.put(COL_COUNTRY, country != null ? country : "");
        cv.put(COL_LAST_CHECKED_AT, checkedAt);
        cv.put(COL_FAIL_COUNT, failCount);
        db.update(TABLE_PROXIES, cv, COL_ID + " = ?", new String[]{String.valueOf(proxyId)});
    }

    public synchronized void setProxyEnabled(long proxyId, boolean enabled) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put(COL_ENABLED, enabled ? 1 : 0);
        db.update(TABLE_PROXIES, cv, COL_ID + " = ?", new String[]{String.valueOf(proxyId)});
    }

    public synchronized int deleteProxy(long proxyId) {
        SQLiteDatabase db = getWritableDatabase();
        return db.delete(TABLE_PROXIES, COL_ID + " = ?", new String[]{String.valueOf(proxyId)});
    }

    public synchronized ProxyConfig getProxyById(long proxyId) {
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor cursor = db.query(TABLE_PROXIES, null, COL_ID + " = ?",
                new String[]{String.valueOf(proxyId)}, null, null, null)) {
            if (cursor.moveToFirst()) {
                return fromCursor(cursor);
            }
        }
        return null;
    }

    public synchronized List<ProxyConfig> getAllProxies() {
        List<ProxyConfig> list = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor cursor = db.query(TABLE_PROXIES, null, null, null, null, null,
                COL_SORT_ORDER + " ASC, " + COL_ID + " ASC")) {
            while (cursor.moveToNext()) {
                list.add(fromCursor(cursor));
            }
        }
        return list;
    }

    public synchronized List<ProxyConfig> getEnabledProxies() {
        List<ProxyConfig> list = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor cursor = db.query(TABLE_PROXIES, null, COL_ENABLED + " = 1", null, null, null,
                COL_SORT_ORDER + " ASC, " + COL_ID + " ASC")) {
            while (cursor.moveToNext()) {
                list.add(fromCursor(cursor));
            }
        }
        return list;
    }

    public synchronized void insertLog(ProxyLogEntry.Level level, String message) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put(COL_LOG_TS, System.currentTimeMillis());
        cv.put(COL_LOG_LEVEL, level.name());
        cv.put(COL_LOG_MESSAGE, message != null ? message : "");
        db.insert(TABLE_LOGS, null, cv);
        db.execSQL("DELETE FROM " + TABLE_LOGS + " WHERE " + COL_LOG_ID + " NOT IN (SELECT "
                + COL_LOG_ID + " FROM " + TABLE_LOGS + " ORDER BY " + COL_LOG_ID + " DESC LIMIT 200)");
    }

    public synchronized List<ProxyLogEntry> getRecentLogs() {
        List<ProxyLogEntry> logs = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor cursor = db.query(TABLE_LOGS, null, null, null, null, null,
                COL_LOG_ID + " DESC", "150")) {
            int idxId = cursor.getColumnIndexOrThrow(COL_LOG_ID);
            int idxTs = cursor.getColumnIndexOrThrow(COL_LOG_TS);
            int idxLvl = cursor.getColumnIndexOrThrow(COL_LOG_LEVEL);
            int idxMsg = cursor.getColumnIndexOrThrow(COL_LOG_MESSAGE);
            while (cursor.moveToNext()) {
                ProxyLogEntry.Level level;
                try {
                    level = ProxyLogEntry.Level.valueOf(cursor.getString(idxLvl));
                } catch (IllegalArgumentException e) {
                    level = ProxyLogEntry.Level.INFO;
                }
                logs.add(new ProxyLogEntry(
                        cursor.getLong(idxId),
                        cursor.getLong(idxTs),
                        level,
                        cursor.getString(idxMsg)
                ));
            }
        }
        return logs;
    }

    public synchronized void clearLogs() {
        SQLiteDatabase db = getWritableDatabase();
        db.delete(TABLE_LOGS, null, null);
    }

    public synchronized List<FirewallRule> getAllFirewallRules() {
        List<FirewallRule> list = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor cursor = db.query(TABLE_FIREWALL_RULES, null, null, null, null, null,
                COL_FW_ID + " ASC")) {
            int idxId = cursor.getColumnIndexOrThrow(COL_FW_ID);
            int idxName = cursor.getColumnIndexOrThrow(COL_FW_NAME);
            int idxEnabled = cursor.getColumnIndexOrThrow(COL_FW_ENABLED);
            int idxPkgs = cursor.getColumnIndexOrThrow(COL_FW_PACKAGES);
            int idxTarget = cursor.getColumnIndexOrThrow(COL_FW_TARGET);
            int idxCreated = cursor.getColumnIndexOrThrow(COL_FW_CREATED_AT);
            while (cursor.moveToNext()) {
                list.add(new FirewallRule(
                        cursor.getLong(idxId),
                        cursor.getString(idxName),
                        cursor.getInt(idxEnabled) == 1,
                        FirewallRule.deserializePackages(cursor.getString(idxPkgs)),
                        cursor.getString(idxTarget),
                        cursor.getLong(idxCreated)
                ));
            }
        }
        return list;
    }

    public synchronized long insertFirewallRule(FirewallRule rule) {
        if (rule == null) return -1L;
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = toFirewallContentValues(rule);
        long id = db.insert(TABLE_FIREWALL_RULES, null, cv);
        rule.setId(id);
        return id;
    }

    public synchronized int updateFirewallRule(FirewallRule rule) {
        if (rule == null || rule.getId() <= 0) return 0;
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = toFirewallContentValues(rule);
        return db.update(TABLE_FIREWALL_RULES, cv, COL_FW_ID + " = ?",
                new String[]{String.valueOf(rule.getId())});
    }

    public synchronized void setFirewallRuleEnabled(long ruleId, boolean enabled) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put(COL_FW_ENABLED, enabled ? 1 : 0);
        db.update(TABLE_FIREWALL_RULES, cv, COL_FW_ID + " = ?", new String[]{String.valueOf(ruleId)});
    }

    public synchronized int deleteFirewallRule(long ruleId) {
        SQLiteDatabase db = getWritableDatabase();
        return db.delete(TABLE_FIREWALL_RULES, COL_FW_ID + " = ?", new String[]{String.valueOf(ruleId)});
    }

    private ContentValues toFirewallContentValues(FirewallRule rule) {
        ContentValues cv = new ContentValues();
        cv.put(COL_FW_NAME, rule.getName());
        cv.put(COL_FW_ENABLED, rule.isEnabled() ? 1 : 0);
        cv.put(COL_FW_PACKAGES, FirewallRule.serializePackages(rule.getBlockedPackages()));
        cv.put(COL_FW_TARGET, rule.getTargetAddress());
        cv.put(COL_FW_CREATED_AT, rule.getCreatedAt() > 0 ? rule.getCreatedAt() : System.currentTimeMillis());
        return cv;
    }

    private ContentValues toContentValues(ProxyConfig proxy) {
        ContentValues cv = new ContentValues();
        cv.put(COL_NAME, proxy.getName());
        cv.put(COL_HOST, proxy.getHost());
        cv.put(COL_PORT, proxy.getPort());
        cv.put(COL_USERNAME, proxy.getUsername());
        cv.put(COL_PASSWORD, proxy.getPassword());
        cv.put(COL_ENABLED, proxy.isEnabled() ? 1 : 0);
        cv.put(COL_LATENCY_MS, proxy.getLatencyMs());
        cv.put(COL_EXIT_IP, proxy.getExitIp());
        cv.put(COL_COUNTRY, proxy.getCountry());
        cv.put(COL_LAST_CHECKED_AT, proxy.getLastCheckedAt());
        cv.put(COL_FAIL_COUNT, proxy.getFailCount());
        cv.put(COL_SORT_ORDER, proxy.getSortOrder());
        return cv;
    }

    private ProxyConfig fromCursor(Cursor c) {
        return new ProxyConfig(
                c.getLong(c.getColumnIndexOrThrow(COL_ID)),
                c.getString(c.getColumnIndexOrThrow(COL_NAME)),
                c.getString(c.getColumnIndexOrThrow(COL_HOST)),
                c.getInt(c.getColumnIndexOrThrow(COL_PORT)),
                c.getString(c.getColumnIndexOrThrow(COL_USERNAME)),
                c.getString(c.getColumnIndexOrThrow(COL_PASSWORD)),
                c.getInt(c.getColumnIndexOrThrow(COL_ENABLED)) == 1,
                c.getLong(c.getColumnIndexOrThrow(COL_LATENCY_MS)),
                c.getString(c.getColumnIndexOrThrow(COL_EXIT_IP)),
                c.getString(c.getColumnIndexOrThrow(COL_COUNTRY)),
                c.getLong(c.getColumnIndexOrThrow(COL_LAST_CHECKED_AT)),
                c.getInt(c.getColumnIndexOrThrow(COL_FAIL_COUNT)),
                c.getInt(c.getColumnIndexOrThrow(COL_SORT_ORDER))
        );
    }
}
