package io.mesh.node;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A gRPC-Mesh node embedded in this JVM: dials the control plane over TLS,
 * reports registered methods and serves remote invocations through Java
 * {@link MethodHandler}s.
 *
 * <p>Lifecycle: {@link #create(NodeConfig)} → {@link #registerMethod} (any
 * number, before start) → {@link #start()} → serving → {@link #close()}.
 * The node is single-use: after a stop there is no restart; create a new
 * instance instead. Implements {@link AutoCloseable} for
 * try-with-resources.
 *
 * <p>Thread safety: lifecycle transitions are guarded; handlers may run
 * concurrently. {@link #close()} performs a bounded stop (default 5 s). On
 * {@link ShutdownTimeoutException} the node is retained and close may be
 * retried with a longer deadline via {@link #stop(long)} first.
 */
public final class GrpcMeshNode implements AutoCloseable {

    /** Node C ABI major version this binding requires. */
    public static final int REQUIRED_ABI_MAJOR = 1;

    /** Library name loaded via System.loadLibrary. */
    static final String LIBRARY_NAME = "grpc_mesh_node";

    private static final long DEFAULT_STOP_TIMEOUT_MS = 5_000;

    /** Native handle; 0 means closed/freed. Swapped atomically on close. */
    private final AtomicLong handle = new AtomicLong();

    /** Guards the one-shot free (stop is retryable, free is not). */
    private final AtomicBoolean freed = new AtomicBoolean();

    private final AtomicBoolean started = new AtomicBoolean();

    private volatile boolean closed;

    static {
        loadLibrary();
    }

    private static void loadLibrary() {
        try {
            String explicit = System.getProperty("io.mesh.node.lib");
            if (explicit != null && !explicit.isEmpty()) {
                System.load(explicit);
            } else {
                System.loadLibrary(LIBRARY_NAME);
            }
        } catch (UnsatisfiedLinkError error) {
            String abis;
            try {
                // Build.SUPPORTED_ABIS on Android; fallback elsewhere.
                Class<?> build = Class.forName("android.os.Build");
                Object supported = build.getField("SUPPORTED_ABIS").get(null);
                abis = String.join(",", (String[]) supported);
            } catch (ReflectiveOperationException | ClassCastException runtimeOnly) {
                abis = System.getProperty("os.arch", "unknown");
            }
            throw new NativeLibraryException(abis, LIBRARY_NAME, error);
        }
        checkAbi(nativeAbiVersion());
    }

    /** Throws {@link AbiVersionException} unless the major version matches. */
    static void checkAbi(int runtimeVersion) {
        if ((runtimeVersion >>> 16) != REQUIRED_ABI_MAJOR) {
            throw new AbiVersionException(REQUIRED_ABI_MAJOR, runtimeVersion);
        }
    }

    /**
     * Creates (but does not start) a node.
     *
     * @throws NodeConfigException when the configuration is rejected
     * @throws NativeLibraryException when the native library cannot load
     * @throws AbiVersionException on an ABI major mismatch
     */
    public static GrpcMeshNode create(NodeConfig config) {
        String json = config.toJson();
        long handle = nativeCreate(json);
        return new GrpcMeshNode(handle);
    }

    private GrpcMeshNode(long handle) {
        this.handle.set(handle);
    }

    /** Node lifecycle states mirrored from the C ABI. */
    public enum NodeState {
        CREATED,
        STARTING,
        RUNNING,
        STOPPING,
        STOPPED;

        static NodeState fromNative(int code) {
            return switch (code) {
                case 1 -> CREATED;
                case 2 -> STARTING;
                case 3 -> RUNNING;
                case 4 -> STOPPING;
                case 5 -> STOPPED;
                default -> throw new NodeStateException("unknown native node state " + code);
            };
        }
    }

    /**
     * Registers (or replaces) the handler for a method. Only legal before
     * {@link #start()}; the method snapshot is frozen and reported at start.
     */
    public void registerMethod(String method, MethodHandler handler) {
        requireOpen("registerMethod");
        if (method == null || method.isEmpty()) {
            throw new IllegalArgumentException("method must not be empty");
        }
        if (handler == null) {
            throw new IllegalArgumentException("handler must not be null");
        }
        nativeRegisterMethod(handle(), method, new NativeBridge(handler));
    }

    /** Removes the handler for a method; only legal before {@link #start()}. */
    public void unregisterMethod(String method) {
        requireOpen("unregisterMethod");
        nativeUnregisterMethod(handle(), method);
    }

    /**
     * Starts the node: dials the control plane (retrying transient
     * failures) and serves registered methods.
     */
    public void start() {
        requireOpen("start");
        if (!started.compareAndSet(false, true)) {
            throw new NodeStateException("start is only valid once");
        }
        nativeStart(handle());
    }

    /**
     * Requests a bounded stop. Rejects new callbacks immediately and waits
     * up to {@code timeoutMs} for in-flight work to drain.
     *
     * @throws ShutdownTimeoutException when the deadline passes; retryable
     */
    public void stop(long timeoutMs) {
        requireOpen("stop");
        nativeStop(handle(), timeoutMs);
    }

    /** Current lifecycle state. */
    public NodeState state() {
        requireOpen("state");
        return NodeState.fromNative(nativeState(handle()));
    }

    /**
     * Stops (bounded) and frees the node. Safe to call more than once. On a
     * stop timeout the node is retained and the exception propagates; free
     * the node later by stopping successfully and closing again.
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        long current = handle();
        // A stop timeout propagates and leaves the node open for a retry;
        // the node is only marked closed after a successful stop.
        nativeStop(current, DEFAULT_STOP_TIMEOUT_MS);
        if (handle.compareAndSet(current, 0)) {
            closed = true;
            if (freed.compareAndSet(false, true)) {
                // Only reached after a successful stop: releasing handler
                // GlobalRefs before this point would be unsafe.
                nativeFree(current);
            }
        }
    }

    private long handle() {
        long current = handle.get();
        if (current == 0) {
            throw new NodeStateException("node is closed");
        }
        return current;
    }

    private void requireOpen(String operation) {
        if (closed || handle.get() == 0) {
            throw new NodeStateException(operation + " on a closed node");
        }
    }

    // ------------------------------------------------------------------
    // Native entry points (implemented in the JNI shim inside
    // libgrpc_mesh_node.so; they adapt the Node C ABI only).
    // ------------------------------------------------------------------

    private static native int nativeAbiVersion();

    private static native long nativeCreate(String configJson);

    private native void nativeRegisterMethod(long handle, String method, Object bridge);

    private native void nativeUnregisterMethod(long handle, String method);

    private native void nativeStart(long handle);

    private native void nativeStop(long handle, long timeoutMs);

    private static native void nativeFree(long handle);

    private static native int nativeState(long handle);

    @SuppressWarnings("unused")
    private static native String nativeLastError();
}
