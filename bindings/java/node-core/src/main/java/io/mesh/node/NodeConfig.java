package io.mesh.node;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Immutable node configuration. Built via {@link #builder()}; serialized to
 * the JSON format the Node C ABI accepts.
 */
public final class NodeConfig {

    private final String serverAddress;
    private final String serverName;
    private final String caCertPem;
    private final String nodeId;
    private final String token;
    private final String version;
    private final long heartbeatIntervalSeconds;
    private final long connectTimeoutSeconds;
    private final long reconnectBaseDelayMs;
    private final long reconnectMaxDelayMs;
    private final long stopGraceSeconds;

    private NodeConfig(Builder builder) {
        this.serverAddress = builder.serverAddress;
        this.serverName = builder.serverName;
        this.caCertPem = builder.caCertPem;
        this.nodeId = builder.nodeId;
        this.token = builder.token;
        this.version = builder.version;
        this.heartbeatIntervalSeconds = builder.heartbeatIntervalSeconds;
        this.connectTimeoutSeconds = builder.connectTimeoutSeconds;
        this.reconnectBaseDelayMs = builder.reconnectBaseDelayMs;
        this.reconnectMaxDelayMs = builder.reconnectMaxDelayMs;
        this.stopGraceSeconds = builder.stopGraceSeconds;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Server address ({@code host:port}) — required. */
    public String serverAddress() {
        return serverAddress;
    }

    /** TLS server name override (SNI); defaults to the address host part. */
    public String serverName() {
        return serverName;
    }

    /** PEM-encoded CA bundle for server verification; null uses system roots. */
    public String caCertPem() {
        return caCertPem;
    }

    /** Node identity — required, unique per server. */
    public String nodeId() {
        return nodeId;
    }

    /** Authentication token bound to the node id — required. */
    public String token() {
        return token;
    }

    /** Reported node version (defaults to the binding version). */
    public String version() {
        return version;
    }

    public long heartbeatIntervalSeconds() {
        return heartbeatIntervalSeconds;
    }

    public long connectTimeoutSeconds() {
        return connectTimeoutSeconds;
    }

    public long reconnectBaseDelayMs() {
        return reconnectBaseDelayMs;
    }

    public long reconnectMaxDelayMs() {
        return reconnectMaxDelayMs;
    }

    public long stopGraceSeconds() {
        return stopGraceSeconds;
    }

    /** Serializes into the Node C ABI JSON config format. */
    public String toJson() {
        Map<String, Object> tls = new LinkedHashMap<>();
        if (serverName != null) {
            tls.put("server_name", serverName);
        }
        if (caCertPem != null) {
            tls.put("ca_cert", caCertPem);
        }
        Map<String, Object> server = new LinkedHashMap<>();
        server.put("address", notNull(serverAddress, "serverAddress"));
        if (!tls.isEmpty()) {
            server.put("tls", tls);
        }
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", notNull(nodeId, "nodeId"));
        node.put("token", notNull(token, "token"));
        node.put("version", version);
        Map<String, Object> heartbeat = new LinkedHashMap<>();
        heartbeat.put("interval_secs", heartbeatIntervalSeconds);
        Map<String, Object> connect = new LinkedHashMap<>();
        connect.put("timeout_secs", connectTimeoutSeconds);
        Map<String, Object> reconnect = new LinkedHashMap<>();
        reconnect.put("base_delay_ms", reconnectBaseDelayMs);
        reconnect.put("max_delay_ms", reconnectMaxDelayMs);
        Map<String, Object> shutdown = new LinkedHashMap<>();
        shutdown.put("grace_secs", stopGraceSeconds);

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("server", server);
        root.put("node", node);
        root.put("heartbeat", heartbeat);
        root.put("connect", connect);
        root.put("reconnect", reconnect);
        root.put("shutdown", shutdown);
        return JsonWriter.write(root);
    }

    private static String notNull(String value, String field) {
        if (value == null || value.isEmpty()) {
            throw new NodeConfigException("missing required config field: " + field);
        }
        return value;
    }

    private static final class JsonWriter {
        static String write(Map<String, Object> value) {
            StringBuilder out = new StringBuilder();
            writeMap(value, out);
            return out.toString();
        }

        static void writeMap(Map<String, Object> value, StringBuilder out) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> entry : value.entrySet()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                writeString(entry.getKey(), out);
                out.append(':');
                writeValue(entry.getValue(), out);
            }
            out.append('}');
        }

        static void writeValue(Object value, StringBuilder out) {
            if (value instanceof Map<?, ?> map) {
                writeMap(castMap(map), out);
            } else if (value instanceof String string) {
                writeString(string, out);
            } else if (value instanceof Number || value instanceof Boolean) {
                out.append(value);
            } else {
                writeString(String.valueOf(value), out);
            }
        }

        @SuppressWarnings("unchecked")
        static Map<String, Object> castMap(Map<?, ?> map) {
            return (Map<String, Object>) map;
        }

        static void writeString(String value, StringBuilder out) {
            out.append('"');
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                switch (c) {
                    case '"' -> out.append("\\\"");
                    case '\\' -> out.append("\\\\");
                    case '\n' -> out.append("\\n");
                    case '\r' -> out.append("\\r");
                    case '\t' -> out.append("\\t");
                    default -> {
                        if (c < 0x20) {
                            out.append(String.format("\\u%04x", (int) c));
                        } else {
                            out.append(c);
                        }
                    }
                }
            }
            out.append('"');
        }
    }

    /** Fluent builder with production-safe defaults. */
    public static final class Builder {

        private String serverAddress;
        private String serverName;
        private String caCertPem;
        private String nodeId;
        private String token;
        private String version = "0.1.0-java";
        private long heartbeatIntervalSeconds = 15;
        private long connectTimeoutSeconds = 10;
        private long reconnectBaseDelayMs = 250;
        private long reconnectMaxDelayMs = 30_000;
        private long stopGraceSeconds = 2;

        /** Server address in {@code host:port} form (required). */
        public Builder serverAddress(String serverAddress) {
            this.serverAddress = serverAddress;
            return this;
        }

        /** TLS server name override (SNI). */
        public Builder serverName(String serverName) {
            this.serverName = serverName;
            return this;
        }

        /** PEM CA bundle for server verification. */
        public Builder caCertPem(String caCertPem) {
            this.caCertPem = caCertPem;
            return this;
        }

        /** Node identity (required). */
        public Builder nodeId(String nodeId) {
            this.nodeId = nodeId;
            return this;
        }

        /** Auth token (required). */
        public Builder token(String token) {
            this.token = token;
            return this;
        }

        /** Reported node version. */
        public Builder version(String version) {
            this.version = version;
            return this;
        }

        /** Heartbeat interval in seconds (default 15). */
        public Builder heartbeatIntervalSeconds(long seconds) {
            this.heartbeatIntervalSeconds = seconds;
            return this;
        }

        /** Connect budget in seconds (default 10). */
        public Builder connectTimeoutSeconds(long seconds) {
            this.connectTimeoutSeconds = seconds;
            return this;
        }

        /** Reconnect backoff base in milliseconds (default 250). */
        public Builder reconnectBaseDelayMs(long millis) {
            this.reconnectBaseDelayMs = millis;
            return this;
        }

        /** Reconnect backoff ceiling in milliseconds (default 30000). */
        public Builder reconnectMaxDelayMs(long millis) {
            this.reconnectMaxDelayMs = millis;
            return this;
        }

        /** Force-close grace after shutdown in seconds (default 2). */
        public Builder stopGraceSeconds(long seconds) {
            this.stopGraceSeconds = seconds;
            return this;
        }

        public NodeConfig build() {
            return new NodeConfig(this);
        }
    }
}
