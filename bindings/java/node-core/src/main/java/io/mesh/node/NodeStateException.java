package io.mesh.node;

/**
 * The requested operation does not match the node's lifecycle state.
 *
 * <p>Extends {@link IllegalStateException} so generic Java cleanup code
 * treats late usage as a programming error.
 */
public class NodeStateException extends IllegalStateException {

    public NodeStateException(String message) {
        super(message);
    }
}
