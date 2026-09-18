package io.mesh.node.smoke;

import io.mesh.node.GrpcMeshNode;
import io.mesh.node.NodeConfig;

/**
 * Minimal runnable example / device smoke test for the gRPC-Mesh Java
 * binding. Also usable on a desktop JVM with the host native library.
 *
 * <p>Usage: {@code MeshSmokeMain <server host:port> <ca.pem path> <node id>
 * <token>} — registers two methods, starts the node, keeps it alive for 25 s
 * (the control plane invokes during this window), then closes cleanly.
 */
public final class MeshSmokeMain {

    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            System.err.println("usage: MeshSmokeMain <host:port> <ca.pem> <node id> <token>");
            System.exit(2);
        }
        String serverAddress = args[0];
        String caPem = readFile(args[1]);
        String nodeId = args[2];
        String token = args[3];

        NodeConfig config =
                NodeConfig.builder()
                        .serverAddress(serverAddress)
                        .caCertPem(caPem)
                        .nodeId(nodeId)
                        .token(token)
                        .connectTimeoutSeconds(5)
                        .heartbeatIntervalSeconds(2)
                        .reconnectBaseDelayMs(250)
                        .reconnectMaxDelayMs(2000)
                        .build();

        GrpcMeshNode node = GrpcMeshNode.create(config);
        try {
            // Binary-safe echo: payload may contain NUL bytes.
            node.registerMethod("java.echo", request -> request.payload());

            // Any thrown exception maps to a structured remote error.
            node.registerMethod(
                    "java.boom",
                    request -> {
                        throw new IllegalStateException("kaboom from java");
                    });

            // Request metadata (method / correlation id / timeout) reaches
            // the handler verbatim.
            node.registerMethod(
                    "java.json",
                    request ->
                            ("{\"method\":\"" + request.method() + "\",\"corr\":\"" + request.correlationId()
                                    + "\",\"timeout\":" + request.timeoutMs() + "}")
                                    .getBytes());

            node.start();
            System.out.println("SMOKE:running state=" + node.state());
            System.out.flush();

            // Serve for a window; the control plane invokes meanwhile.
            Thread.sleep(25_000);
        } finally {
            node.close();
        }

        // After close, a state query must fail in Java without touching JNI.
        try {
            node.state();
            throw new AssertionError("state() after close must throw");
        } catch (io.mesh.node.NodeStateException expected) {
            System.out.println("SMOKE:state-after-close=" + expected.getClass().getSimpleName());
        }
        System.out.println("SMOKE:closed");
        System.out.flush();
    }

    /** Stream-based read so the example runs on every Android API level. */
    private static String readFile(String path) throws java.io.IOException {
        try (java.io.InputStream in = new java.io.FileInputStream(path)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            for (int n; (n = in.read(buffer)) > 0; ) {
                out.write(buffer, 0, n);
            }
            return out.toString("UTF-8");
        }
    }

    private MeshSmokeMain() {}
}
