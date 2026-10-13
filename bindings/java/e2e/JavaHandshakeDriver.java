package io.mesh.node.e2e;

import io.mesh.node.GrpcMeshNode;
import io.mesh.node.NodeConfig;
import io.mesh.node.NodeConfigException;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Host-side driver for the Java → JNI → Rust full-protocol harness.
 *
 * <p>It runs in a separate JVM spawned by the Rust end-to-end test, which owns
 * the real TLS/yamux control server. The driver exercises the public binding
 * only: reserved-key rejection, handshake feature/metadata serialization,
 * register/replace/unregister registry truth and a normal close. It blocks on
 * stdin after start so the harness keeps the node serving until the server has
 * asserted the captured handshake, then closes when it reads a line.
 *
 * <p>Args: {@code <address> <nodeId> <token>}. System properties:
 * {@code e2e.dir} (scratch dir for the reserved-key result),
 * {@code e2e.caFile} (PEM trust anchor), {@code io.mesh.node.lib} (cdylib).
 */
public final class JavaHandshakeDriver {

    private JavaHandshakeDriver() {}

    public static void main(String[] args) throws Exception {
        String address = args[0];
        String nodeId = args[1];
        String token = args[2];
        Path dir = Path.of(System.getProperty("e2e.dir"));
        String caPem =
                Files.readString(Path.of(System.getProperty("e2e.caFile")), StandardCharsets.UTF_8);

        // The reserved key must be rejected by the Builder before any JNI
        // call, so this config never attempts a connection.
        boolean reservedRejected = false;
        try {
            NodeConfig.builder()
                    .serverAddress(address)
                    .nodeId(nodeId)
                    .token(token)
                    .addMetadata("mesh.methods", "fake.Service/NotRegistered");
        } catch (NodeConfigException expected) {
            reservedRejected = true;
        }
        Files.writeString(
                dir.resolve("reserved.txt"),
                reservedRejected ? "REJECTED" : "ACCEPTED",
                StandardCharsets.UTF_8);

        NodeConfig config =
                NodeConfig.builder()
                        .serverAddress(address)
                        .serverName("localhost")
                        .caCertPem(caPem)
                        .nodeId(nodeId)
                        .token(token)
                        .addFeature(" DG ")
                        .addFeature("Report")
                        .addMetadata("capability", "dg")
                        .addMetadata("padded", "  text  ")
                        .addMetadata("empty", "")
                        .addMetadata("unicode", "a\u00A0b\u2007c")
                        .build();

        try (GrpcMeshNode node = GrpcMeshNode.create(config)) {
            node.registerMethod("calculator.v1.Calculator/Health", request -> request.payload());
            node.registerMethod("calculator.v1.Calculator/Add", request -> request.payload());
            node.unregisterMethod("calculator.v1.Calculator/Health");
            node.start();
            System.out.println("STARTED");
            System.out.flush();
            new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)).readLine();
        }
        System.out.println("STOPPED");
        System.out.flush();
    }
}
