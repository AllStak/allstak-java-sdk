package dev.allstak.model;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class DatabaseQueryItem {
    private String normalizedQuery;
    private String queryHash;
    private String queryType;
    private long durationMs;
    private long timestampMillis;
    private String status;
    private String errorMessage;
    private String databaseName;
    private String databaseType;
    private String service;
    private String environment;
    private String traceId;
    private String requestId;
    private String spanId;
    private int rowsAffected;
    private String release;

    // Builder pattern
    public static Builder builder() { return new Builder(); }

    // All getters
    public String getNormalizedQuery() { return normalizedQuery; }
    public String getQueryHash() { return queryHash; }
    public String getQueryType() { return queryType; }
    public long getDurationMs() { return durationMs; }
    public long getTimestampMillis() { return timestampMillis; }
    public String getStatus() { return status; }
    public String getErrorMessage() { return errorMessage; }
    public String getDatabaseName() { return databaseName; }
    public String getDatabaseType() { return databaseType; }
    public String getService() { return service; }
    public String getEnvironment() { return environment; }
    public String getTraceId() { return traceId; }
    public String getRequestId() { return requestId; }
    public String getSpanId() { return spanId; }
    public int getRowsAffected() { return rowsAffected; }
    public String getRelease() { return release; }

    public static class Builder {
        private final DatabaseQueryItem item = new DatabaseQueryItem();

        public Builder normalizedQuery(String q) { item.normalizedQuery = q; return this; }
        public Builder queryHash(String h) { item.queryHash = h; return this; }
        public Builder queryType(String t) { item.queryType = t; return this; }
        public Builder durationMs(long d) { item.durationMs = d; return this; }
        public Builder timestampMillis(long t) { item.timestampMillis = t; return this; }
        public Builder status(String s) { item.status = s; return this; }
        public Builder errorMessage(String e) { item.errorMessage = e; return this; }
        public Builder databaseName(String n) { item.databaseName = n; return this; }
        public Builder databaseType(String t) { item.databaseType = t; return this; }
        public Builder service(String s) { item.service = s; return this; }
        public Builder environment(String e) { item.environment = e; return this; }
        public Builder traceId(String t) { item.traceId = t; return this; }
        public Builder requestId(String r) { item.requestId = r; return this; }
        public Builder spanId(String s) { item.spanId = s; return this; }
        public Builder rowsAffected(int r) { item.rowsAffected = r; return this; }
        public Builder release(String r) { item.release = r; return this; }

        public DatabaseQueryItem build() {
            if (item.timestampMillis == 0) item.timestampMillis = System.currentTimeMillis();
            if (item.status == null) item.status = "success";
            if (item.queryType == null) item.queryType = "OTHER";
            if (item.rowsAffected == 0 && item.status.equals("success")) item.rowsAffected = -1;
            return item;
        }
    }
}
