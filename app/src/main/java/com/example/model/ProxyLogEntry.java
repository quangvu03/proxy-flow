package com.example.model;

/**
 * Timestamped connection/switching/testing log entry.
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

    public ProxyLogEntry(long id, long timestamp, Level level, String message) {
        this.id = id;
        this.timestamp = timestamp;
        this.level = level != null ? level : Level.INFO;
        this.message = message != null ? message : "";
    }

    public long getId() { return id; }
    public long getTimestamp() { return timestamp; }
    public Level getLevel() { return level; }
    public String getMessage() { return message; }
}
