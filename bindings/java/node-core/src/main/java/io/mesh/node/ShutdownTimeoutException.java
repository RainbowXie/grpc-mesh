package io.mesh.node;

/**
 * The node did not drain within the requested stop deadline. The stop may be
 * retried with a longer deadline; resources (and Java handler references)
 * are retained until a stop succeeds.
 */
public class ShutdownTimeoutException extends MeshNodeException {

    public ShutdownTimeoutException(String message) {
        super(4, message);
    }
}
