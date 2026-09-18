package io.mesh.node;

/**
 * Base exception for failures surfaced by the gRPC-Mesh node binding.
 *
 * <p>Carries the Node C ABI status code when one applies (negative when the
 * failure originated on the Java side).
 */
public class MeshNodeException extends RuntimeException {

    private final int nativeCode;

    public MeshNodeException(String message) {
        this(-1, message);
    }

    public MeshNodeException(int nativeCode, String message) {
        super(message);
        this.nativeCode = nativeCode;
    }

    /** The Node C ABI status code, or -1 when the failure is Java-side. */
    public int nativeCode() {
        return nativeCode;
    }
}
