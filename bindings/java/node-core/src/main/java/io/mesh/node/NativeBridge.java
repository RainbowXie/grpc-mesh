package io.mesh.node;

/**
 * Internal adapter the JNI shim calls. Keeping the JNI signature primitive
 * (byte[], String, String, long) avoids constructing intermediate objects
 * in native code; the user-facing {@link MethodHandler} sees a
 * {@link MethodRequest}.
 */
final class NativeBridge {

    private final MethodHandler handler;

    NativeBridge(MethodHandler handler) {
        this.handler = handler;
    }

    /** Called from Rust worker threads via JNI. */
    @SuppressWarnings("unused")
    byte[] nativeInvoke(byte[] payload, String method, String correlationId, long timeoutMs)
            throws Exception {
        return handler.invoke(new MethodRequest(payload, method, correlationId, timeoutMs));
    }
}
