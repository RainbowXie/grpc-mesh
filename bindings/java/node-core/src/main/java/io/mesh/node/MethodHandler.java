package io.mesh.node;

/**
 * Business handler for one registered method.
 *
 * <p>Handlers may run concurrently on the node's worker threads; each
 * invocation receives its own {@link MethodRequest}. Throwing any exception
 * maps to a structured remote error (class plus message) without affecting
 * later invocations.
 */
@FunctionalInterface
public interface MethodHandler {

    /**
     * Processes one request.
     *
     * @param request the incoming invocation view
     * @return response bytes sent back to the remote caller
     * @throws Exception any failure; rendered as a remote error
     */
    byte[] invoke(MethodRequest request) throws Exception;
}
