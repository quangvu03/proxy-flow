package com.example.model;

/**
 * Timestamped connection, DNS, packet/request sending, and firewall log entry.
 */
public class ProxyLogEntry {

    public enum Level {
        INFO,
        SUCCESS,
        WARN,
        ERROR
    }

    private final long id;
    private final long timestamp;
    private final Level level;
    private final String message;
    private final String packageName;
    private final String eventTag;

    public ProxyLogEntry(long id, long timestamp, Level level, String message) {
        this(id, timestamp, level, message, "", "");
    }

    public ProxyLogEntry(long id, long timestamp, Level level, String message,
                         String packageName, String eventTag) {
        this.id = id;
        this.timestamp = timestamp;
        this.level = level != null ? level : Level.INFO;
        this.message = message != null ? message : "";
        this.packageName = packageName != null ? packageName : "";
        this.eventTag = eventTag != null ? eventTag : "";
    }

    public long getId() { return id; }
    public long getTimestamp() { return timestamp; }
    public Level getLevel() { return level; }
    public String getMessage() { return message; }
    public String getPackageName() { return packageName; }
    public String getEventTag() { return eventTag; }

    public String getEffectiveTag() {
        return !eventTag.isEmpty() ? eventTag : level.name();
    }
}
