package io.mesh.node;

/** Read-only view of one invocation handed to a {@link MethodHandler}. */
public final class MethodRequest {

    private final byte[] payload;
    private final String method;
    private final String correlationId;
    private final long timeoutMs;

    MethodRequest(byte[] payload, String method, String correlationId, long timeoutMs) {
        this.payload = payload;
        this.method = method;
        this.correlationId = correlationId;
        this.timeoutMs = timeoutMs;
    }

    /** Raw request payload; may be empty and may contain NUL bytes. */
    public byte[] payload() {
        return payload;
    }

    /** Fully qualified method name as invoked by the remote caller. */
    public String method() {
        return method;
    }

    /** Correlation id for tracing (may be empty). */
    public String correlationId() {
        return correlationId;
    }

    /** Caller-declared timeout in milliseconds. */
    public long timeoutMs() {
        return timeoutMs;
    }
}
