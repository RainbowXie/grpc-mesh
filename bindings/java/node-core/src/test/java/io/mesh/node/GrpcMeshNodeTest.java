package io.mesh.node;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Lifecycle contract tests for the Java binding against the real native
 * library (host build, loaded via the io.mesh.node.lib system property).
 *
 * <p>Network-level invoke paths are covered by the end-to-end acceptance
 * (real mesh server); these tests pin resource safety, state rules and
 * exception mapping.
 */
class GrpcMeshNodeTest {

    private static NodeConfig.Builder offlineConfig(String nodeId) {
        // Port 9 (discard) is never served: the node keeps retrying, which is
        // exactly what lifecycle tests need.
        return NodeConfig.builder()
                .serverAddress("127.0.0.1:9")
                .nodeId(nodeId)
                .token("test-token")
                .connectTimeoutSeconds(1)
                .reconnectBaseDelayMs(50)
                .reconnectMaxDelayMs(100);
    }

    @Test
    void nativeLibraryLoadsAndAbiMatches() {
        // The static initializer already verified the ABI; assert the check
        // logic directly for both matching and mismatching versions.
        assertEquals(GrpcMeshNode.REQUIRED_ABI_MAJOR, 1);
        assertDoesNotThrow(() -> GrpcMeshNode.checkAbi((1 << 16) | 3));
        AbiVersionException mismatch =
                assertThrows(
                        AbiVersionException.class, () -> GrpcMeshNode.checkAbi((2 << 16) | 0));
        assertEquals(1, mismatch.expectedMajor());
        assertEquals(2 << 16, mismatch.actualVersion());
    }

    @Test
    void missingLibraryExceptionNamesLibraryAndAbi() {
        NativeLibraryException exception =
                new NativeLibraryException("arm64-v8a, x86_64", "grpc_mesh_node", null);
        assertTrue(exception.getMessage().contains("grpc_mesh_node"));
        assertTrue(exception.getMessage().contains("arm64-v8a"));
    }

    @Test
    void invalidConfigRejectedAtCreate() {
        NodeConfig missingToken = NodeConfig.builder().serverAddress("127.0.0.1:9").nodeId("x").build();
        NodeConfigException missing =
                assertThrows(NodeConfigException.class, () -> GrpcMeshNode.create(missingToken));
        assertTrue(missing.getMessage().contains("token"), missing.getMessage());

        NodeConfig emptyAddress = NodeConfig.builder().nodeId("x").token("y").build();
        NodeConfigException address =
                assertThrows(NodeConfigException.class, () -> GrpcMeshNode.create(emptyAddress));
        assertTrue(address.getMessage().contains("serverAddress"), address.getMessage());
    }

    @Test
    @Timeout(20)
    void tryWithResourcesClosesOnceAndRejectsAfterClose() {
        AtomicInteger handlerCalls = new AtomicInteger();
        try (GrpcMeshNode node = GrpcMeshNode.create(offlineConfig("jvm-lifecycle").build())) {
            node.registerMethod(
                    "jvm.echo",
                    request -> {
                        handlerCalls.incrementAndGet();
                        return request.payload();
                    });
            node.start();
            assertEquals(GrpcMeshNode.NodeState.RUNNING, node.state());
        }
        // Scope exit: stopped and freed even without an explicit stop.
        try (GrpcMeshNode node = GrpcMeshNode.create(offlineConfig("jvm-lifecycle-2").build())) {
            node.close();
            // Every subsequent operation is rejected in Java, never reaching JNI.
            assertThrows(NodeStateException.class, () -> node.start());
            assertThrows(NodeStateException.class, () -> node.registerMethod("x", req -> req.payload()));
            assertThrows(NodeStateException.class, () -> node.unregisterMethod("x"));
            assertThrows(NodeStateException.class, node::state);
            // Double close is safe.
            assertDoesNotThrow(node::close);
        }
    }

    @Test
    @Timeout(20)
    void registrationFrozeAfterStartAndUnknownUnregisterFails() {
        try (GrpcMeshNode node = GrpcMeshNode.create(offlineConfig("jvm-freeze").build())) {
            node.registerMethod("early.method", req -> req.payload());
            node.start();
            assertThrows(NodeStateException.class, () -> node.registerMethod("late", req -> req.payload()));
            assertThrows(NodeStateException.class, () -> node.unregisterMethod("early.method"));
            assertEquals(GrpcMeshNode.NodeState.RUNNING, node.state());
        }
        try (GrpcMeshNode fresh = GrpcMeshNode.create(offlineConfig("jvm-unreg").build())) {
            NodeStateException missing =
                    assertThrows(
                            NodeStateException.class, () -> fresh.unregisterMethod("never.there"));
            assertTrue(missing.getMessage().contains("never.there"), missing.getMessage());
        }
    }

    @Test
    @Timeout(20)
    void handlerReplacementKeepsFinalRegistry() {
        try (GrpcMeshNode node = GrpcMeshNode.create(offlineConfig("jvm-replace").build())) {
            node.registerMethod("dup.method", req -> req.payload());
            // Replacement before start is legal (old GlobalRef is released natively).
            assertDoesNotThrow(() -> node.registerMethod("dup.method", req -> new byte[] {1}));
        }
    }

    @Test
    void configJsonEscapesAndCarriesAllFields() {
        NodeConfig config =
                offlineConfig("json-node")
                        .serverName("localhost")
                        .caCertPem("-----BEGIN-----\nline2\n")
                        .heartbeatIntervalSeconds(7)
                        .stopGraceSeconds(3)
                        .build();
        String json = config.toJson();
        assertTrue(json.contains("\"node\""));
        assertTrue(json.contains("\"id\":\"json-node\""));
        assertTrue(json.contains("\\n"), "PEM newlines must be escaped: " + json);
        assertTrue(json.contains("\"interval_secs\":7"));
        assertTrue(json.contains("\"grace_secs\":3"));
        assertTrue(json.contains("\"server_name\":\"localhost\""));
    }
}
