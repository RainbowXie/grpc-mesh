# Calculator Service

Rust 服务节点示例，展示如何使用 `grpc-mesh-node` 库连接到控制平面。

## 特性

- 🦀 **Rust + Tonic**：高性能 gRPC 服务实现
- 🔄 **反向隧道**：主动连接控制平面，无需公网 IP
- 🔐 **TLS 加密**：安全的双向认证
- 📊 **自动重连**：网络断开时自动重连
- 📝 **配置灵活**：支持配置文件或环境变量

## 架构

```
calculator-service (Rust)
├── TLS 客户端
├── Yamux 连接
├── gRPC 服务实现
│   ├── Add (加法)
│   ├── Subtract (减法)
│   ├── Multiply (乘法)
│   ├── Divide (除法)
│   └── Health (健康检查)
└── 连接到 → grpc-mesh-server:8443
```

## 快速开始

### 方式 1：使用配置文件（推荐）

```bash
# 1. 确保配置文件存在
cat config/config.json

# 2. 构建
cargo build --release

# 3. 启动（自动读取 config/config.json）
cargo run --release
```

### 方式 2：使用环境变量

```bash
# 设置环境变量
export TUNNEL_SERVER="localhost:8443"
export TUNNEL_TOKEN="waemu_7RCx4i4T6gU3O9Gqcx4-SvHMRN1V8dJ9"
export NODE_ID="calculator-service"

# 启动
cargo run --release
```

### 测试服务

通过 calculator-client 调用：

```bash
curl -X POST http://localhost:8080/calculate \
  -H 'Content-Type: application/json' \
  -d '{"operation": "add", "a": 10, "b": 5}'
```

## 配置说明

### 配置加载优先级

1. **配置文件**（优先）：`config/config.json` 或 `$CONFIG_PATH`
2. **环境变量**（回退）：如果配置文件不存在，使用环境变量

### 配置文件格式

`config/config.json`:

```json
{
  "server": {
    "address": "localhost:8443",
    "tls": {
      "server_name": "localhost"
    }
  },
  "node": {
    "id": "calculator-service",
    "token": "waemu_7RCx4i4T6gU3O9Gqcx4-SvHMRN1V8dJ9"
  }
}
```

**字段说明：**

| 字段 | 必填 | 默认值 | 说明 |
|------|------|--------|------|
| `server.address` | ✅ | - | gRPC-Mesh 控制平面地址 |
| `server.tls.server_name` | ❌ | `localhost` | TLS SNI 服务器名称 |
| `node.id` | ✅ | - | 节点唯一标识符 |
| `node.token` | ✅ | - | 认证 Token（从 grpc-mesh-server 生成）|

### 环境变量配置

| 环境变量 | 对应配置 | 说明 |
|----------|----------|------|
| `CONFIG_PATH` | - | 配置文件路径（默认：`config/config.json`）|
| `TUNNEL_SERVER` / `SERVER_ADDRESS` | `server.address` | 服务器地址 |
| `TUNNEL_TOKEN` / `WA_NODE_TOKEN` | `node.token` | 认证 Token |
| `NODE_ID` / `WA_NODE_ID` | `node.id` | 节点 ID |
| `CA_CERT_PATH` | - | CA 证书路径（默认：`../../grpc-mesh-server/config/tls/ca.crt`）|

**示例：使用环境变量覆盖**

```bash
# 使用配置文件，但通过环境变量覆盖 Token
export TUNNEL_TOKEN="waemu_NewRotatedToken"
cargo run --release
```

## TLS 证书配置

### 自动加载 CA 证书

服务会自动尝试从以下位置加载 CA 证书：

1. 环境变量 `CA_CERT_PATH` 指定的路径
2. 默认路径：`../../grpc-mesh-server/config/tls/ca.crt`

如果 CA 证书未找到，将使用系统 CA 存储（**自签名证书会失败**）。

### 证书生成

```bash
# 在 grpc-mesh-server 目录下生成证书
cd ../../grpc-mesh-server
make certs
```

生成的文件：
```
grpc-mesh-server/config/tls/
├── ca.crt              # CA 根证书（节点需要）
├── ca.key              # CA 私钥
├── server.crt          # 服务器证书
├── server.key          # 服务器私钥
└── server-chain.crt    # 服务器证书链
```

## Proto 定义

`proto/calculator.proto`:

```protobuf
syntax = "proto3";
package calculator.v1;

service Calculator {
  rpc Add(CalcRequest) returns (CalcResponse);
  rpc Subtract(CalcRequest) returns (CalcResponse);
  rpc Multiply(CalcRequest) returns (CalcResponse);
  rpc Divide(CalcRequest) returns (CalcResponse);
  rpc Health(HealthRequest) returns (HealthResponse);
}

message CalcRequest {
  double a = 1;
  double b = 2;
}

message CalcResponse {
  double result = 1;
}

message HealthRequest {
  string message = 1;
}

message HealthResponse {
  string status = 1;
  string message = 2;
}
```

## 核心代码实现

### 1. 加载配置

```rust
use serde::{Deserialize, Serialize};

#[derive(Debug, Deserialize, Serialize)]
struct Config {
    server: ServerConfig,
    node: NodeConfig,
}

// 优先从配置文件加载，失败时从环境变量加载
let config = load_config()?;
```

### 2. 连接到控制平面

```rust
use grpc_mesh::tunnel::{ConnectorConfig, Handshake, TunnelConnector};

// 创建连接器配置
let connector_config = ConnectorConfig {
    server_addr: config.server.address.clone(),
    ca_certs: vec![ca_cert_pem],
    sni: None,
    connect_timeout: Duration::from_secs(10),
    max_backoff: Duration::from_secs(30),
};

// 创建连接器
let mut connector = TunnelConnector::new(connector_config, shutdown_rx)?;

// 构建握手信息
let handshake = Handshake::builder(&config.node.id, "1.0.0")
    .token(config.node.token)
    .add_feature("grpc")
    .add_feature("calculator")
    .build()?;

// 建立隧道连接（带自动重连）
let mut tunnel = connector.connect_with_backoff(handshake).await?;
```

### 3. 实现 gRPC 服务

```rust
use tonic::{Request, Response, Status};
use calculator::calculator_server::Calculator;

pub struct CalculatorService;

#[tonic::async_trait]
impl Calculator for CalculatorService {
    async fn add(&self, request: Request<CalcRequest>) 
        -> Result<Response<CalcResponse>, Status> {
        let req = request.into_inner();
        let result = req.a + req.b;
        
        info!("Add: {} + {} = {}", req.a, req.b, result);
        
        Ok(Response::new(CalcResponse { result }))
    }
    
    async fn divide(&self, request: Request<CalcRequest>) 
        -> Result<Response<CalcResponse>, Status> {
        let req = request.into_inner();
        
        if req.b == 0.0 {
            return Err(Status::invalid_argument("Division by zero"));
        }
        
        let result = req.a / req.b;
        info!("Divide: {} / {} = {}", req.a, req.b, result);
        
        Ok(Response::new(CalcResponse { result }))
    }
}
```

### 4. 启动服务

```rust
use tonic::transport::Server;
use calculator::calculator_server::CalculatorServer;

// 获取 incoming stream adapter
let incoming = tunnel.take_incoming().ok_or("Failed to get incoming")?;

// 创建 gRPC 服务
let calculator_service = CalculatorServer::new(CalculatorService);

// 通过隧道提供服务
Server::builder()
    .add_service(calculator_service)
    .serve_with_incoming(incoming)
    .await?;
```

## 添加新操作

### 1. 更新 Proto

`proto/calculator.proto`:

```protobuf
service Calculator {
  // ... 现有方法
  rpc Power(CalcRequest) returns (CalcResponse);
}
```

### 2. 实现方法

`src/main.rs`:

```rust
#[tonic::async_trait]
impl Calculator for CalculatorService {
    // ... 现有方法
    
    async fn power(&self, request: Request<CalcRequest>) 
        -> Result<Response<CalcResponse>, Status> {
        let req = request.into_inner();
        let result = req.a.powf(req.b);
        
        info!("Power: {} ^ {} = {}", req.a, req.b, result);
        
        Ok(Response::new(CalcResponse { result }))
    }
}
```

### 3. 重新编译

```bash
cargo build --release
```

## 依赖管理

`Cargo.toml`:

```toml
[dependencies]
grpc-mesh = { path = "../../grpc-mesh-node" }
tonic = { version = "0.14.2", features = ["transport"] }
prost = "0.14.1"
tonic-prost = "0.14.2"
tokio = { version = "1.48", features = ["full"] }
tracing = "0.1"
tracing-subscriber = { version = "0.3", features = ["fmt", "env-filter"] }
serde = { version = "1.0", features = ["derive"] }
serde_json = "1.0"
anyhow = "1.0"

[build-dependencies]
tonic-prost-build = "0.14.2"
```

**注意**：tonic/prost 版本必须与 `grpc-mesh` 一致！

## 日志配置

### 设置日志级别

```bash
# Info 级别（默认）
export RUST_LOG=info
cargo run --release

# Debug 级别
export RUST_LOG=debug
cargo run --release

# 只显示本服务日志
export RUST_LOG=calculator_service=debug
cargo run --release
```

### 日志输出示例

```
2024-01-15T10:30:00.123Z INFO  calculator_service: ╔════════════════════════════╗
2024-01-15T10:30:00.123Z INFO  calculator_service: ║  Calculator Service        ║
2024-01-15T10:30:00.123Z INFO  calculator_service: ╚════════════════════════════╝
2024-01-15T10:30:00.124Z INFO  calculator_service: 📄 Loading configuration from: config/config.json
2024-01-15T10:30:00.125Z INFO  calculator_service: ✅ Configuration loaded from file
2024-01-15T10:30:00.126Z INFO  calculator_service: ✅ Loaded CA certificate from: ../../grpc-mesh-server/config/tls/ca.crt
2024-01-15T10:30:00.127Z INFO  calculator_service: Configuration:
2024-01-15T10:30:00.127Z INFO  calculator_service:   Tunnel Server: localhost:8443
2024-01-15T10:30:00.127Z INFO  calculator_service:   Node ID: calculator-service
2024-01-15T10:30:00.127Z INFO  calculator_service:   Token: waemu_7RCx...
2024-01-15T10:30:01.234Z INFO  calculator_service: 📡 Connecting to gRPC-Mesh control plane...
2024-01-15T10:30:01.456Z INFO  calculator_service: ✅ Tunnel established successfully
2024-01-15T10:30:01.457Z INFO  calculator_service: ✅ Service registered with control plane
2024-01-15T10:30:01.457Z INFO  calculator_service: 🔒 TLS connection secured
2024-01-15T10:30:01.457Z INFO  calculator_service: 🌐 Yamux multiplexing enabled
2024-01-15T10:30:01.458Z INFO  calculator_service: 🚀 Calculator Service is now listening through the reverse tunnel
2024-01-15T10:30:01.458Z INFO  calculator_service: ✨ Ready to handle requests
```

## 故障排查

### 问题 1：Token 未找到

**错误信息：**
```
❌ Token not found. Set TUNNEL_TOKEN environment variable or provide config.json
```

**解决方案：**
```bash
# 方案 1：创建配置文件
cat > config/config.json <<EOF
{
  "server": {
    "address": "localhost:8443",
    "tls": {
      "server_name": "localhost"
    }
  },
  "node": {
    "id": "calculator-service",
    "token": "waemu_YOUR_TOKEN_HERE"
  }
}
EOF

# 方案 2：设置环境变量
export TUNNEL_TOKEN="waemu_YOUR_TOKEN_HERE"
```

### 问题 2：连接失败

**错误信息：**
```
❌ Failed to establish tunnel: connection refused
```

**排查步骤：**

1. 检查 grpc-mesh-server 是否运行：
   ```bash
   lsof -i :8443
   # 或
   netstat -tuln | grep 8443
   ```

2. 检查防火墙：
   ```bash
   # Linux
   sudo iptables -L -n | grep 8443
   
   # macOS
   sudo pfctl -s rules | grep 8443
   ```

3. 检查配置中的地址：
   ```bash
   cat config/config.json | jq '.server.address'
   ```

### 问题 3：TLS 证书验证失败

**错误信息：**
```
❌ Failed to establish tunnel: invalid certificate
```

**解决方案：**

1. 确认 CA 证书文件存在：
   ```bash
   ls -la ../../grpc-mesh-server/config/tls/ca.crt
   ```

2. 重新生成证书：
   ```bash
   cd ../../grpc-mesh-server
   make certs
   ```

3. 检查证书有效期：
   ```bash
   openssl x509 -in ../../grpc-mesh-server/config/tls/ca.crt -noout -dates
   ```

### 问题 4：编译错误

**错误信息：**
```
error: failed to select a version for `tonic`
```

**解决方案：**

```bash
# 清理 Cargo 缓存
cargo clean
rm Cargo.lock

# 确保版本一致
cd ../../grpc-mesh-node
grep "tonic =" Cargo.toml

cd ../../demos/calculator-service
grep "tonic =" Cargo.toml

# 重新构建
cargo build
```

## 性能优化

### Release 编译优化

`Cargo.toml`:

```toml
[profile.release]
opt-level = 3           # 最高优化级别
lto = true              # 链接时优化
codegen-units = 1       # 单个编码单元（更好的优化）
strip = true            # 去除调试符号
panic = 'abort'         # Panic 时直接终止（减小二进制大小）
```

### 运行时优化

```bash
# 设置 Tokio 线程数
export TOKIO_WORKER_THREADS=4

# 启用 jemalloc（需要在 Cargo.toml 中添加依赖）
cargo build --release --features jemalloc
```

## 生产部署

### Systemd 服务

`/etc/systemd/system/calculator-service.service`:

```ini
[Unit]
Description=Calculator Service - gRPC Mesh Node
After=network.target

[Service]
Type=simple
User=grpc-mesh
WorkingDirectory=/opt/calculator-service
ExecStart=/opt/calculator-service/target/release/calculator-service
Restart=always
RestartSec=10

# 环境变量（可选）
Environment="RUST_LOG=info"
Environment="CA_CERT_PATH=/opt/calculator-service/certs/ca.crt"

# 安全加固
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome=true
ReadWritePaths=/var/log/calculator-service

[Install]
WantedBy=multi-user.target
```

启动服务：

```bash
sudo systemctl daemon-reload
sudo systemctl enable calculator-service
sudo systemctl start calculator-service
sudo systemctl status calculator-service
```

### Docker 部署

`Dockerfile`:

```dockerfile
FROM rust:1.77 as builder

WORKDIR /app
COPY . .
RUN cargo build --release

FROM debian:bookworm-slim

RUN apt-get update && apt-get install -y ca-certificates && rm -rf /var/lib/apt/lists/*

COPY --from=builder /app/target/release/calculator-service /usr/local/bin/
COPY config /app/config
COPY certs /app/certs

WORKDIR /app

CMD ["calculator-service"]
```

构建和运行：

```bash
docker build -t calculator-service:latest .
docker run -d \
  --name calculator-service \
  -v $(pwd)/config:/app/config:ro \
  -v $(pwd)/certs:/app/certs:ro \
  calculator-service:latest
```

## 参考文档

- [Demo 总览](../README.md) - 完整演示说明
- [配置指南](../CONFIG_GUIDE.md) - 详细配置说明
- [grpc-mesh-node SDK](../../grpc-mesh-node/README.md) - Rust SDK 文档
- [Proto 定义](proto/calculator.proto) - gRPC 接口定义

## 许可证

MIT License