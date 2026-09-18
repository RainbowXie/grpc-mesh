package io.mesh.node;

/** The node configuration was rejected at creation time. */
public class NodeConfigException extends MeshNodeException {

    public NodeConfigException(String message) {
        super(1, message);
    }
}
