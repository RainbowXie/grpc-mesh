# Calculator Service Integration TODO

## Current Status

❌ **NOT INTEGRATED** - This demo service is currently a standalone gRPC server and does NOT use the gRPC-Mesh (ReverseTunnel) framework.

## What's Wrong

The current implementation in `src/main.rs`:

```rust
// ❌ WRONG: Service listens on a port
Server::builder()
    .add_service(CalculatorServer::new(CalculatorService))
    .serve(addr)  // <-- Listening on [::1]:50052
    .await?;
```

This is a **normal gRPC server** that waits for clients to connect directly. This defeats the entire purpose of the ReverseTunnel framework.

## What Should Happen

The service should:

1. ✅ Connect **outbound** to `grpc-mesh-server:8443` (TLS)
2. ✅ Establish a Yamux multiplexed tunnel
3. ✅ Send handshake with `node_id` and `token`
4. ✅ Register itself in the control plane's service registry
5. ✅ Listen for **incoming gRPC calls through the tunnel**
6. ✅ Send responses back through the same tunnel

## Required Changes

### 1. Add Dependency

Edit `Cargo.toml`:

```toml
[dependencies]
# Add the gRPC-Mesh framework library
grpc-mesh = { path = "../../grpc-mesh-node" }

# Keep existing dependencies...
tonic = { version = "0.11", features = ["transport"] }
prost = "0.12"
tokio = { version = "1.38", features = ["full"] }
# ...
```

### 2. Refactor main.rs

Replace the standalone server code with tunnel integration:

```rust
use grpc_mesh::tunnel::{ConnectorConfig, TunnelConnector, HandshakeBuilder};
use tokio::sync::watch;
use std::time::Duration;

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    // Initialize logging
    tracing_subscriber::fmt::init();
    
    // Load configuration
    let tunnel_server = env::var("TUNNEL_SERVER")
        .unwrap_or_else(|_| "localhost:8443".to_string());
    let token = env::var("TUNNEL_TOKEN")
        .expect("TUNNEL_TOKEN environment variable required");
    
    // Create tunnel connector config
    let config = ConnectorConfig {
        server_addr: tunnel_server,
        ca_certs: Vec::new(),  // Use system CA for now
        sni: None,
        connect_timeout: Duration::from_secs(10),
        max_backoff: Duration::from_secs(30),
    };
    
    // Create shutdown channel
    let (shutdown_tx, shutdown_rx) = watch::channel(false);
    
    // Build tunnel connector
    let connector = TunnelConnector::new(config, shutdown_rx)?;
    
    // Build handshake
    let handshake = HandshakeBuilder::new()
        .node_id("calculator-service")
        .token(&token)
        .version(env!("CARGO_PKG_VERSION"))
        .add_feature("grpc")
        .add_metadata("service", "calculator")
        .build()?;
    
    info!("📡 Connecting to tunnel server...");
    
    // Connect and register
    let mut tunnel = connector.connect(handshake).await?;
    
    info!("✅ Tunnel established and registered");
    
    // Get the incoming stream adapter
    let incoming = tunnel.take_incoming()
        .expect("incoming adapter not available");
    
    // Create gRPC service
    let calc_service = CalculatorServer::new(CalculatorService);
    
    info!("🚀 Starting Calculator Service via ReverseTunnel");
    
    // Serve gRPC through the tunnel
    Server::builder()
        .add_service(calc_service)
        .serve_with_incoming(incoming)
        .await?;
    
    Ok(())
}
```

### 3. Configuration

Create a `config.json` file:

```json
{
  "tunnel_server": "localhost:8443",
  "node_id": "calculator-service",
  "log_level": "info"
}
```

Set environment variables:

```bash
export TUNNEL_SERVER="localhost:8443"
export TUNNEL_TOKEN="waemu_7RCx4i4T6gU3O9Gqcx4-SvHMRN1V8dJ9"
```

### 4. Update README

The README currently says:

> "In production, integrate with ReverseTunnel framework."

This should be updated to show the actual integrated code.

## Testing the Integration

### Step 1: Start Control Plane

```bash
cd ../../grpc-mesh-server
./bin/grpc-mesh-server -config config/config.yaml
```

Should output:
```
TLS listener ready on :8443
gRPC server ready on :50051
```

### Step 2: Start Calculator Service (Integrated)

```bash
cd ../demos/calculator-service
export TUNNEL_TOKEN="waemu_7RCx4i4T6gU3O9Gqcx4-SvHMRN1V8dJ9"
cargo run --release
```

Should output:
```
📡 Connecting to tunnel server...
✅ Tunnel established and registered
🚀 Starting Calculator Service via ReverseTunnel
```

**Note**: No port listening! The service connects outbound only.

### Step 3: Start Calculator Client

```bash
cd ../calculator-client
./calculator-client
```

Should output:
```
✅ Connected to ReverseTunnel Server
🚀 REST API listening on :8080
```

### Step 4: Test End-to-End

```bash
curl -X POST http://localhost:8080/calculate \
  -H "Content-Type: application/json" \
  -d '{"operation": "add", "a": 10, "b": 5}'
```

Expected response:
```json
{"result": 15}
```

## Current Workaround (For Testing Only)

Since the integration is not complete, you can test the services separately:

1. **Run calculator-service standalone**:
   ```bash
   cd calculator-service
   cargo run
   # Listens on [::1]:50052
   ```

2. **Modify calculator-client** to bypass the tunnel and connect directly:
   ```go
   // In main.go, replace InvokePlane call with direct gRPC:
   conn, err := grpc.Dial("localhost:50052", grpc.WithInsecure())
   ```

⚠️ **This defeats the purpose of the framework** and should only be used for testing the gRPC contract.

## Why This Integration Matters

Without proper integration, the demo:

- ❌ Doesn't demonstrate the core value proposition
- ❌ Requires port forwarding / firewall configuration
- ❌ Can't work across NAT/firewalls
- ❌ Doesn't show session management, heartbeats, reconnection
- ❌ Misses the "reverse tunnel" concept entirely

With proper integration:

- ✅ Service can run behind NAT/firewall
- ✅ No inbound ports needed
- ✅ Automatic reconnection on network issues
- ✅ Centralized service discovery
- ✅ Demonstrates the actual framework architecture

## Effort Estimate

- **Time**: 2-4 hours for a developer familiar with both Rust and the framework
- **Complexity**: Medium (requires understanding Tonic's `serve_with_incoming`)
- **Testing**: 1 hour
- **Documentation**: 1 hour

## References

- [ARCHITECTURE.md](./ARCHITECTURE.md) - Explains the correct architecture
- [../../grpc-mesh-node/src/tunnel/connector.rs](../../grpc-mesh-node/src/tunnel/connector.rs) - Tunnel connector implementation
- [../../INTEGRATION_GUIDE.md](../../INTEGRATION_GUIDE.md) - General integration guide
- [../../grpc-mesh-server/README.md](../../grpc-mesh-server/README.md) - Control plane documentation

## Questions?

If you need help with the integration, check:

1. Example code in `grpc-mesh-node/src/bin/` - May contain example binaries
2. Integration tests in `grpc-mesh-node/tests/`
3. The main documentation in the repository root