# grpc-mesh-node Java/Android binding (Alpha)

Embed a gRPC-Mesh node in a JVM or Android app: the node dials the control
plane over TLS, reports its method list and serves remote invocations
through Java handlers.

* Package `io.mesh.node`, artifacts `io.grpc-mesh:node-core` (pure Java) and
  `io.grpc-mesh:node-android` (AAR, `0.1.0-alpha1`).
* Java 17, Android `minSdk 24`.
* **The Alpha AAR ships exactly one native ABI: `arm64-v8a`.** Running on
  another ABI fails at load with a `NativeLibraryException` naming the
  device's supported ABIs — there is no fallback and no silent degradation.
  Adding an ABI requires its own build plus device smoke test first.

## Quick start

```java
try (GrpcMeshNode node = GrpcMeshNode.create(
        NodeConfig.builder()
                .serverAddress("mesh.example.com:8443")
                .caCertPem(caPem)          // trust anchor of the control plane
                .nodeId("my-node-1")
                .token("node-token")       // issued per node by the server
                .build())) {

    node.registerMethod("my.service/Echo", request -> request.payload());
    node.registerMethod("my.service/Fail", request -> {
        throw new IllegalArgumentException("bad input");  // -> remote error
    });

    node.start();
    // ... serve until scope exit; close() stops (bounded) and frees
}
```

A complete runnable example (also used as the device smoke test) lives at
[`examples/mesh-smoke/src/MeshSmokeMain.java`](examples/mesh-smoke/src/MeshSmokeMain.java).

## Lifecycle and closing semantics

* `create` validates nothing beyond config shape; TLS material is checked at
  `start`, and a configuration failure there is terminal — create a new node.
* Registration is frozen at `start`; the sorted snapshot is reported to the
  control plane (`mesh.methods`).
* `close()` performs a bounded stop (default 5 s, configurable via
  `stop(long)` first). On timeout it throws `ShutdownTimeoutException` and
  the node stays alive — retry `close()` with a longer budget. The native
  node is freed exactly once, only after a successful stop; handler
  references are released at that point, never while callbacks are running.
* After `close`, every operation throws `NodeStateException` in Java without
  touching JNI.

## Handlers

* `MethodHandler.invoke(MethodRequest)` returns response bytes; any thrown
  exception is converted into a structured remote error
  (`INTERNAL`, message `java handler threw: <class>: <message>`) and the
  node keeps serving later requests.
* Handlers run concurrently on the node's worker threads; each invocation
  gets its own `MethodRequest`. Payloads are binary-safe (NUL bytes intact).
* Method names are the fully qualified names the control plane invokes —
  for typed services use the proto path, e.g. `calculator.v1.Calculator/Add`.

## Errors

| Exception | Meaning |
| --- | --- |
| `NativeLibraryException` | library missing for this device/ABI (message lists supported ABIs) |
| `AbiVersionException` | native ABI major ≠ required (both versions in the message) |
| `NodeConfigException` | configuration rejected at creation |
| `NodeStateException` (extends `IllegalStateException`) | wrong lifecycle state / closed node |
| `ShutdownTimeoutException` | bounded stop timed out; retryable |
| `MeshNodeException` | other native failures (carries the ABI status code) |

## Thread attachment (JNI details)

Rust worker threads calling into Java attach to the JVM for the duration of
the callback and detach afterwards; threads already attached by the JVM are
reused and never detached. Each callback runs in a local reference frame
(capacity 32). The JNI shim talks only to the stable Node C ABI — see
`grpc-mesh-node/crates/grpc-mesh-node-ffi` (README covers the ABI contract).

## Building and testing

```sh
./gradlew :node-core:test            # host JVM tests (uses the host-built .so)
./gradlew :node-android:assembleRelease   # AAR: classes + jni/arm64-v8a/*.so
```

The host tests locate the native library via the `io.mesh.node.lib` system
property (or `GRPC_MESH_NODE_LIB` for the gradle task); on Android the
normal `System.loadLibrary("grpc_mesh_node")` path applies.

## Alpha distribution

Artifacts are published as GitHub release attachments (AAR, standalone
`.so`, C header, manifest, SHA-256) — nothing binary is committed to Git.
Consumer projects install the AAR from the release download; see the
repository release notes for the manifest and checksums.
