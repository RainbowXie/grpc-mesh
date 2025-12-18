# Quick API Reference

This is a condensed reference for the most commonly used APIs in gRPC-Mesh. For complete documentation, see the individual package docs.

---

## Table of Contents

- [Go Server API](#go-server-api)
  - [Server Creation](#server-creation)
  - [Session Management](#session-management)
  - [Reverse RPC](#reverse-rpc)
- [Rust Node API](#rust-node-api)
  - [Method Registry](#method-registry)
  - [Tunnel Connection](#tunnel-connection)
  - [Service Setup](#service-setup)

---

## Go Server API

### Server Creation

```go
import (
    "github.com/grpc-mesh/grpc-mesh-server/pkg/config"
    "github.com/grpc-mesh/grpc-mesh-server/pkg/server"
)

// Load configuration
cfg, err := config.Load("config.yaml")
if err != nil {
    log.Fatal(err)
}

// Create server
srv, err := server.New(cfg)
if err != nil {
    log.Fatal(err)
}

// Start server
if err := srv.Start(); err != nil {
    log.Fatal(err)
}
defer srv.Stop()
```

### Session Management

```go
// Get session registry
registry := srv.Registry()

// List all connected nodes
sessions := registry.List()
for _, session := range sessions {
    fmt.Printf("Node: %s, Connected: %v\n", 
        session.PeerID, session.Connected)
}

// Get specific session
session, ok := registry.Get(registry.PeerID("node-123"))
if !ok {
    return fmt.Errorf("node not found")
}

// Open stream to node
stream, err := registry.OpenStream(ctx, registry.PeerID("node-123"))
if err != nil {
    return err
}
defer stream.Close()

// Get session statistics
stats := registry.Stats()
fmt.Printf("Active: %d, Total: %d\n", 
    stats.ActiveSessions, stats.TotalSessions)
```

### Reverse RPC

```go
import "github.com/grpc-mesh/grpc-mesh-server/pkg/rpc"

// Get reverse gateway
gateway := srv.ReverseGateway()

// Invoke unary RPC
resp, err := gateway.Invoke(ctx, registry.PeerID("node-123"), &rpc.InvokeRequest{
    PeerId:  "node-123",
    Method:  "calculator.Add",
    Payload: []byte(`{"a":5,"b":3}`),
    TimeoutMs: 5000,
})
if err != nil {
    return err
}

if !resp.Success {
    fmt.Printf("RPC failed: %s\n", resp.Error.Message)
}

// Invoke streaming RPC
stream, cleanup, err := gateway.InvokeStream(ctx, registry.PeerID("node-123"))
if err != nil {
    return err
}
defer cleanup()

// Send request
if err := stream.Send(&rpc.InvokeRequest{...}); err != nil {
    return err
}

// Receive response
resp, err := stream.Recv()
```

---

## Rust Node API

### Method Registry

```rust
use grpc_mesh_node::{MethodRegistry, RpcResult};
use std::sync::Arc;

// Create registry
let registry = MethodRegistry::default();

// Register methods
registry.register("calculator.add", Arc::new(|payload: Vec<u8>| -> RpcResult<Vec<u8>> {
    let input: serde_json::Value = serde_json::from_slice(&payload)?;
    let a = input["a"].as_i64().unwrap_or(0);
    let b = input["b"].as_i64().unwrap_or(0);
    let result = serde_json::json!({"result": a + b});
    Ok(serde_json::to_vec(&result)?)
}));

registry.register("calculator.multiply", Arc::new(|payload: Vec<u8>| -> RpcResult<Vec<u8>> {
    // Handler implementation...
    Ok(vec![])
}));

// List registered methods
let methods = registry.methods();
println!("Registered methods: {:?}", methods);

// Invoke method locally (for testing)
let result = registry.invoke("calculator.add", payload)?;
```

### Tunnel Connection

```rust
use grpc_mesh_node::tunnel::{ConnectorConfig, TunnelConnector, HandshakeBuilder};
use tokio::sync::watch;
use std::time::Duration;

// Configure connection
let config = ConnectorConfig {
    server_addr: "mesh-server.example.com:8443".into(),
    ca_certs: vec![std::fs::read("ca.crt")?],
    connect_timeout: Duration::from_secs(10),
    heartbeat_interval: Duration::from_secs(15),
    ..Default::default()
};

// Build handshake
let handshake = HandshakeBuilder::new("my-node-id")
    .version("1.0.0")
    .feature("streaming")
    .metadata("region", "us-west")
    .build()?;

// Create shutdown channel
let (shutdown_tx, shutdown_rx) = watch::channel(false);

// Connect with automatic retry
let mut connector = TunnelConnector::new(config, shutdown_rx)?;
let incoming = connector.connect_with_backoff(handshake).await?;

// incoming is now ready for tonic server
```

### Service Setup

```rust
use grpc_mesh_node::{MethodRegistry, rpc::InvokeService};
use tonic::transport::Server;

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    // 1. Create registry and register methods
    let registry = MethodRegistry::default();
    registry.register("echo", Arc::new(|payload| Ok(payload)));
    
    // 2. Connect to mesh server
    let config = ConnectorConfig::default();
    let handshake = HandshakeBuilder::new("my-node").build()?;
    let (_tx, rx) = watch::channel(false);
    
    let mut connector = TunnelConnector::new(config, rx)?;
    let incoming = connector.connect_with_backoff(handshake).await?;
    
    // 3. Create and serve gRPC service
    let service = InvokeService::new(registry).into_server();
    
    Server::builder()
        .add_service(service)
        .serve_with_incoming(incoming)
        .await?;
    
    Ok(())
}
```

---

## Configuration Examples

### Go Server Config (config.yaml)

```yaml
server:
  grpc_address: ":50051"
  metrics_address: ":9090"

listener:
  address: ":8443"
  cert_file: "certs/server.crt"
  key_file: "certs/server.key"
  ca_file: "certs/ca.crt"

logging:
  level: "info"

auth:
  enabled: true
  allowed_tokens:
    - "secret-token-1"
    - "secret-token-2"
```

### Rust Node with TLS

```rust
// Load CA certificate
let ca_cert = std::fs::read("ca.crt")?;

let config = ConnectorConfig {
    server_addr: "localhost:8443".into(),
    ca_certs: vec![ca_cert],
    sni: Some("localhost".into()),
    connect_timeout: Duration::from_secs(10),
    max_backoff: Duration::from_secs(60),
    heartbeat_interval: Duration::from_secs(15),
};
```

---

## Common Patterns

### Go: Graceful Shutdown

```go
sigCh := make(chan os.Signal, 1)
signal.Notify(sigCh, os.Interrupt, syscall.SIGTERM)

srv.Start()

<-sigCh
log.Println("Shutting down...")
srv.Stop()
```

### Rust: Graceful Shutdown

```rust
let (shutdown_tx, shutdown_rx) = watch::channel(false);

// Clone for signal handler
let shutdown_tx_clone = shutdown_tx.clone();
tokio::spawn(async move {
    tokio::signal::ctrl_c().await.unwrap();
    shutdown_tx_clone.send(true).unwrap();
});

// Use shutdown_rx in connector
let mut connector = TunnelConnector::new(config, shutdown_rx)?;
```

### Go: Custom Timeout

```go
ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
defer cancel()

resp, err := gateway.Invoke(ctx, peerID, req)
```

### Rust: Error Handling

```rust
match registry.invoke("method", payload) {
    Ok(result) => println!("Success: {:?}", result),
    Err(RpcError::MethodNotFound(method)) => {
        eprintln!("Method {} not registered", method);
    }
    Err(RpcError::Internal(msg)) => {
        eprintln!("Internal error: {}", msg);
    }
    Err(e) => eprintln!("Error: {}", e),
}
```

---

## Monitoring

### Go: Prometheus Metrics

```go
// Metrics available at :9090/metrics by default
// - grpc_mesh_sessions_total
// - grpc_mesh_sessions_active
// - grpc_mesh_heartbeats_received_total
// - grpc_mesh_invocations_total
// - grpc_mesh_invocation_duration_seconds
```

### Go: Health Check

```go
// Check session health
sessions := registry.List()
healthy := 0
for _, s := range sessions {
    if s.Connected && time.Since(s.LastHeartbeat) < 30*time.Second {
        healthy++
    }
}
fmt.Printf("Healthy nodes: %d/%d\n", healthy, len(sessions))
```

### Rust: Debug Logging

```rust
// Enable debug logging
tracing_subscriber::fmt()
    .with_max_level(tracing::Level::DEBUG)
    .init();

// Logs will show:
// - Connection attempts and retries
// - Heartbeat sends
// - Method invocations
// - Error details
```

---

## Troubleshooting

### Connection Issues

**Go Server:**
```go
// Check if TLS certificates are valid
cfg, err := config.Load("config.yaml")
if err != nil {
    log.Fatalf("Config error: %v", err)
}

// Verify listener started
srv.Start()
// Look for: "tunnel listener started" log
```

**Rust Node:**
```rust
// Enable verbose logging
RUST_LOG=debug cargo run

// Common issues:
// - Invalid CA certificate
// - Wrong server address
// - Firewall blocking port 8443
```

### Method Not Found

**Rust:**
```rust
// Verify method is registered
let methods = registry.methods();
assert!(methods.contains(&"calculator.add".to_string()));

// Case-sensitive method names
registry.register("Calculator.Add", ...); // ❌
registry.register("calculator.add", ...); // ✅
```

### Timeout Issues

**Go:**
```go
// Increase timeout
gateway = gateway.WithInvokeTimeout(60 * time.Second)

// Or per-request
req.TimeoutMs = 60000 // 60 seconds
```

**Rust:**
```rust
// Adjust connect timeout
let config = ConnectorConfig {
    connect_timeout: Duration::from_secs(30),
    ..Default::default()
};
```

---

## Additional Resources

- **Full API Documentation:**
  - Go: `go doc pkg/server`
  - Rust: `cargo doc --open`

- **Example Applications:**
  - `examples/calculator-client` (Go)
  - `examples/calculator-service` (Rust)

- **Architecture Documentation:**
  - `README.md` - System overview
  - `ROADMAP.md` - Future plans
  - `API_DOCUMENTATION_IMPROVEMENTS.md` - Detailed API docs

---

**Version:** 1.0.0  
**Last Updated:** 2024