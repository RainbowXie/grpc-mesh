# gRPC Mesh - 反向隧道框架

[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Go Version](https://img.shields.io/badge/go-1.21+-blue.svg)](https://golang.org)
[![Rust Version](https://img.shields.io/badge/rust-1.77+-orange.svg)](https://www.rust-lang.org)
[![Status](https://img.shields.io/badge/status-operational-success.svg)](#项目状态)

> 基于 TLS + Yamux + gRPC 的内网服务反向网关解决方案

**最后更新:** 2025-12-15  
**状态:** ✅ 所有核心功能已实现并测试通过

---

## 📚 文档导航

- **[开发路线图](ROADMAP.md)** - 项目进度、已完成功能、未来计划
- **[Go Server API](grpc-mesh-server/docs/API.md)** - 控制平面 API 详细文档
- **[Rust Node API](grpc-mesh-node/docs/API.md)** - 节点 SDK API 详细文档
- **[Demo 应用](demos/README.md)** - Calculator 演示应用

---

## 📋 目录

- [概述](#概述)
- [架构](#架构)
- [快速开始](#快速开始)
- [配置说明](#配置说明)
- [API 参考](#api-参考)
- [部署指南](#部署指南)
- [开发指南](#开发指南)
- [故障排查](#故障排查)
- [项目状态](#项目状态)

---

## 概述

gRPC Mesh 是一个完整的反向网关框架，用于将内网服务安全地暴露给外部调用，无需复杂的网络配置。适用于边缘计算、IoT 设备管理、多租户 SaaS 等场景。

### 核心特性

- ✅ **TLS 1.3 加密** - 端到端加密通信
- ✅ **Yamux 多路复用** - 单连接承载多个逻辑流
- ✅ **自动重连** - 断线自动重连，指数退避
- ✅ **反向调用** - 内网主动出网，公网反向调用
- ✅ **Token 认证** - 安全的节点身份验证
- ✅ **自动心跳** - 15 秒间隔自动保活
- ✅ **方法路由** - 基于 MethodRegistry 的动态路由
- ✅ **可观测性** - Prometheus 指标 + 结构化日志

### 使用场景

- **边缘计算** - 管理分布在各地的边缘节点
- **IoT 设备** - 远程管理物联网设备
- **内网穿透** - 无需公网 IP 访问内网服务
- **多租户 SaaS** - 每个租户独立的服务实例
- **混合云** - 统一管理公有云和私有云服务

---

## 架构

```
┌─────────────────────────────────────────────────────────────────┐
│                      External Network                            │
│  ┌──────────────────┐            ┌──────────────────┐          │
│  │ Calculator Client│            │  External Apps   │          │
│  │  (Go + HTTP API) │            │                  │          │
│  │                  │            │                  │          │
│  │ Embedded Mesh:   │            │   via gRPC       │          │
│  │ - Tunnel Server  │            │                  │          │
│  │ - InvokePlane    │            │                  │          │
│  │ - Gateway        │            │                  │          │
│  └────────┬─────────┘            └────────┬─────────┘          │
│           │ :8443 (TLS)                   │ :50051 (gRPC)      │
└───────────┼───────────────────────────────┼────────────────────┘
            │                               │
            │        Control Plane          │
            │   ┌───────────────────────┐   │
            │   │  grpc-mesh-server     │◄──┘
            │   │  - Tunnel Listener    │
            │   │  - Session Manager    │
            │   │  - Reverse Gateway    │
            │   │  - InvokePlane gRPC   │
            │   └───────────┬───────────┘
            │               │
            │        Yamux Multiplexing
            │               │
┌───────────┼───────────────┼────────────────────────────────────┐
│           │               │         Internal Network            │
│           │               │                                     │
│   ┌───────▼───────────────▼────────┐                           │
│   │   Yamux Session (over TLS)     │                           │
│   │   - Control Stream (heartbeat) │                           │
│   │   - Data Streams (gRPC calls)  │                           │
│   └───────────────┬────────────────┘                           │
│                   │                                             │
│        ┌──────────▼──────────┐                                 │
│        │ Calculator Service  │                                 │
│        │  (Rust)             │                                 │
│        │                     │                                 │
│        │ - InvokePlane gRPC  │                                 │
│        │ - MethodRegistry    │                                 │
│        │ - Calculator Logic  │                                 │
│        └─────────────────────┘                                 │
│         No inbound ports!                                       │
└─────────────────────────────────────────────────────────────────┘
```

### 组件说明

1. **grpc-mesh-server (Go)** - 控制平面
   - 监听 TLS 连接 (`:8443`)
   - 管理 Yamux 会话
   - 提供 InvokePlane gRPC 服务
   - 反向拨号到内网节点

2. **grpc-mesh-node (Rust)** - 节点 SDK
   - 主动连接控制平面
   - 实现 InvokeService (InvokePlane 接口)
   - MethodRegistry 路由方法调用
   - 自动心跳保活

3. **Demo Applications**
   - calculator-client: HTTP API + 嵌入式 mesh 服务器
   - calculator-service: Rust 计算器实现

---

## 快速开始

### 前置要求

```bash
# Go 1.21+
go version

# Rust 1.70+
rustc --version

# Protocol Buffers compiler
protoc --version
```

### 1. 生成 TLS 证书

```bash
cd grpc-mesh-server
make certs

# 这会生成：
# - config/tls/ca.crt          (CA 证书)
# - config/tls/server.key      (服务器私钥)
# - config/tls/server.crt      (服务器证书)
# - config/tls/server-chain.crt (证书链，自动创建)
```

**注意:** CA 证书会自动复制到 `grpc-mesh-node/config/ca.crt`

**LAN 测试:**
```bash
make certs-lan IP=192.168.1.100
```

**生产环境:**
```bash
make certs-prod DOMAIN=example.com IPS=203.0.113.10
```

### 2. 编译组件

```bash
# Calculator Client (Go + 嵌入式 mesh)
cd demos/calculator-client
go build -o calculator-client .

# Calculator Service (Rust)
cd ../calculator-service
cargo build --release
```

### 3. 启动服务

**Terminal 1 - 启动 Calculator Client:**
```bash
cd demos/calculator-client
./calculator-client

# 输出:
# ✅ Mesh server started
#    - Tunnel listener: :8443
#    - InvokePlane gRPC: :50051
#    - Metrics: :9090
# 🚀 Calculator Client listening on :8080
```

**Terminal 2 - 启动 Calculator Service:**
```bash
cd demos/calculator-service
./target/release/calculator-service

# 输出:
# ✅ Tunnel established successfully
# ✅ Service registered with control plane
# ✨ Ready to handle requests
```

### 4. 测试

```bash
# 检查连接的节点
curl http://localhost:8080/nodes
# 返回: {"total":1,"nodes":[{"node_id":"calculator-service",...}]}

# 执行计算: 10 + 5
curl -X POST http://localhost:8080/calculate \
  -H 'Content-Type: application/json' \
  -d '{"operation": "add", "a": 10, "b": 5}'
# 返回: {"result":15}

# 乘法: 7 * 6
curl -X POST http://localhost:8080/calculate \
  -H 'Content-Type: application/json' \
  -d '{"operation": "multiply", "a": 7, "b": 6}'
# 返回: {"result":42}

# 健康检查
curl http://localhost:8080/health
# 返回: {"status":"healthy","message":"..."}
```

---

## 配置说明

### Calculator Client 配置

文件: `demos/calculator-client/config.yaml`

```yaml
# 应用配置
app:
  listen_address: ":8080"              # HTTP API 监听地址
  target_node_id: "calculator-service" # 目标节点 ID

# Mesh Server 配置
server:
  grpc_address: ":50051"      # InvokePlane gRPC 监听地址
  metrics_address: ":9090"    # Prometheus metrics 地址

# Tunnel 监听器配置
listener:
  address: ":8443"            # TLS 监听地址
  cert_file: "../../grpc-mesh-server/config/tls/server-chain.crt"
  key_file: "../../grpc-mesh-server/config/tls/server.key"
  ca_file: ""                 # 可选，启用 mTLS

# 安全配置
security:
  require_token: true
  allowed_tokens:
    - "waemu_7RCx4i4T6gU3O9Gqcx4-SvHMRN1V8dJ9"
```

### Calculator Service 配置

文件: `demos/calculator-service/config/config.json`

```json
{
  "server": {
    "address": "127.0.0.1:8443",  // Mesh server 地址
    "tls": {
      "server_name": "localhost"   // TLS SNI
    }
  },
  "node": {
    "id": "calculator-service",    // 节点 ID (唯一)
    "token": "waemu_7RCx4i4T6gU3O9Gqcx4-SvHMRN1V8dJ9"
  }
}
```

**环境变量配置:**
```bash
export TUNNEL_SERVER="mesh.example.com:8443"
export NODE_ID="my-service"
export TUNNEL_TOKEN="waemu_xxx"
export CA_CERT_PATH="/path/to/ca.crt"
```

---

## API 参考

### InvokePlane gRPC Service

所有 mesh 节点必须实现 InvokePlane 服务。

```protobuf
service InvokePlane {
  rpc Invoke(InvokeRequest) returns (InvokeResponse);
  rpc InvokeStream(stream InvokeRequest) returns (stream InvokeResponse);
}

message InvokeRequest {
  string peer_id = 1;        // 目标节点 ID
  string method = 2;         // 方法名 (例如: "calculator.v1.Calculator/Add")
  bytes payload = 3;         // Protobuf 编码的请求
  uint32 timeout_ms = 4;     // 超时时间 (毫秒)
  string correlation_id = 5; // 关联 ID (可选)
}

message InvokeResponse {
  string peer_id = 1;
  string method = 2;
  bytes result = 3;          // Protobuf 编码的响应
  bool success = 4;
  ErrorDetail error = 5;
  string correlation_id = 6;
  uint64 elapsed_ms = 7;
}
```

### Calculator HTTP API

**GET /nodes**
- 列出所有连接的节点
- 响应: `{"total": N, "nodes": [...]}`

**POST /calculate**
- 请求: `{"operation": "add|subtract|multiply|divide", "a": float, "b": float}`
- 响应: `{"result": float}` 或 `{"error": "message"}`

**GET /health**
- 健康检查
- 响应: `{"status": "healthy|unhealthy", "message": "..."}`

### MethodRegistry API (Rust)

在 Rust 节点中注册方法处理器：

```rust
use grpc_mesh::{MethodRegistry, RpcResult};
use prost::Message;
use std::sync::Arc;

let registry = MethodRegistry::default();

// 注册方法处理器
registry.register(
    "calculator.v1.Calculator/Add",  // 方法全名
    Arc::new(|payload: Vec<u8>| -> RpcResult<Vec<u8>> {
        // 1. 解码请求
        let req = CalcRequest::decode(&payload[..])?;
        
        // 2. 处理业务逻辑
        let result = req.a + req.b;
        
        // 3. 编码响应
        let resp = CalcResponse { result };
        let mut buf = Vec::new();
        resp.encode(&mut buf)?;
        Ok(buf)
    }),
);

// 使用 InvokeService
let invoke_service = InvokeService::new(registry).into_server();

// 在 tonic Server 中提供服务
Server::builder()
    .add_service(invoke_service)
    .serve_with_incoming(incoming)
    .await?;
```

### Prometheus Metrics

访问 `http://localhost:9090/metrics` 查看指标：

- `grpc_mesh_sessions_active` - 当前活跃会话数
- `grpc_mesh_invocations_total` - 总调用次数
- `grpc_mesh_invocation_duration_seconds` - 调用延迟直方图
- `grpc_mesh_heartbeats_received_total` - 接收的心跳总数

---

## 部署指南

### Docker 部署

**Dockerfile (calculator-service):**

```dockerfile
FROM rust:1.75 AS builder
WORKDIR /app
COPY . .
RUN cargo build --release

FROM debian:bookworm-slim
RUN apt-get update && apt-get install -y ca-certificates && rm -rf /var/lib/apt/lists/*
COPY --from=builder /app/target/release/calculator-service /usr/local/bin/
COPY config/ /app/config/
WORKDIR /app
CMD ["calculator-service"]
```

**docker-compose.yml:**

```yaml
version: '3.8'
services:
  calculator-client:
    build: ./demos/calculator-client
    ports:
      - "8080:8080"
      - "8443:8443"
    volumes:
      - ./grpc-mesh-server/config/tls:/tls:ro
    environment:
      - CONFIG_PATH=/app/config.yaml

  calculator-service:
    build: ./demos/calculator-service
    environment:
      - TUNNEL_SERVER=calculator-client:8443
      - CA_CERT_PATH=/ca/ca.crt
    volumes:
      - ./grpc-mesh-server/config/tls/ca.crt:/ca/ca.crt:ro
    depends_on:
      - calculator-client
```

### Kubernetes 部署

```yaml
apiVersion: v1
kind: Secret
metadata:
  name: mesh-tls
type: Opaque
data:
  server.key: <base64-encoded-key>
  server-chain.crt: <base64-encoded-cert>
---
apiVersion: v1
kind: ConfigMap
metadata:
  name: mesh-ca
data:
  ca.crt: |
    -----BEGIN CERTIFICATE-----
    # Your CA certificate here
    -----END CERTIFICATE-----
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: calculator-client
spec:
  replicas: 1
  selector:
    matchLabels:
      app: calculator-client
  template:
    metadata:
      labels:
        app: calculator-client
    spec:
      containers:
      - name: client
        image: calculator-client:latest
        ports:
        - containerPort: 8080
          name: http
        - containerPort: 8443
          name: tunnel
        - containerPort: 9090
          name: metrics
        volumeMounts:
        - name: tls
          mountPath: /tls
          readOnly: true
      volumes:
      - name: tls
        secret:
          secretName: mesh-tls
---
apiVersion: v1
kind: Service
metadata:
  name: calculator-client
spec:
  selector:
    app: calculator-client
  ports:
  - name: http
    port: 8080
    targetPort: 8080
  - name: tunnel
    port: 8443
    targetPort: 8443
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: calculator-service
spec:
  replicas: 3  # 可扩展多个副本
  selector:
    matchLabels:
      app: calculator-service
  template:
    metadata:
      labels:
        app: calculator-service
    spec:
      containers:
      - name: service
        image: calculator-service:latest
        env:
        - name: TUNNEL_SERVER
          value: "calculator-client:8443"
        - name: NODE_ID
          valueFrom:
            fieldRef:
              fieldPath: metadata.name  # 使用 pod 名称作为唯一 ID
        volumeMounts:
        - name: ca
          mountPath: /ca
          readOnly: true
      volumes:
      - name: ca
        configMap:
          name: mesh-ca
```

### 生产环境建议

1. **TLS 证书**
   - 使用 Let's Encrypt 或内部 PKI
   - 定期轮换证书
   - 存储在 Kubernetes secrets 或 vault

2. **监控**
   - 使用 Prometheus 采集指标
   - 设置告警 (会话断开、心跳延迟)
   - 使用 Grafana 可视化

3. **安全**
   - 定期轮换 token
   - 启用 mTLS (设置 `ca_file`)
   - 使用 ACL 策略限制访问
   - 配置 rate limiting

4. **扩展**
   - Mesh server 可以水平扩展 (无状态)
   - 节点可以独立扩展
   - 使用负载均衡器分发流量

---

## 开发指南

### 项目结构

```
grpc-mesh/
├── grpc-mesh-server/          # 控制平面 (Go)
│   ├── cmd/                   # 主程序入口
│   ├── pkg/
│   │   ├── config/            # 配置管理
│   │   ├── control/           # 控制流协议
│   │   ├── registry/          # 会话管理
│   │   ├── reverse/           # 反向拨号
│   │   ├── rpc/               # InvokePlane proto
│   │   ├── server/            # 主服务器
│   │   └── tunnel/            # TLS + Yamux
│   └── config/tls/            # TLS 证书
│
├── grpc-mesh-node/            # 节点 SDK (Rust)
│   ├── src/
│   │   ├── tunnel/            # 连接器、握手
│   │   ├── rpc/               # InvokeService
│   │   ├── generated/         # Proto 生成代码
│   │   └── lib.rs             # MethodRegistry
│   └── config/                # CA 证书
│
└── demos/
    ├── calculator-client/     # HTTP gateway (Go)
    └── calculator-service/    # 实现 (Rust)
```

### 添加新方法

1. **定义 protobuf:**
```protobuf
service Calculator {
  rpc NewMethod(NewRequest) returns (NewResponse);
}
```

2. **重新生成代码:**
```bash
cd demos/calculator-service
cargo build
```

3. **注册到 MethodRegistry:**
```rust
registry.register(
    "calculator.v1.Calculator/NewMethod",
    Arc::new(|payload: Vec<u8>| -> RpcResult<Vec<u8>> {
        let req = NewRequest::decode(&payload[..])?;
        // ... 实现逻辑
        let resp = NewResponse { /* ... */ };
        let mut buf = Vec::new();
        resp.encode(&mut buf)?;
        Ok(buf)
    }),
);
```

4. **添加 HTTP 端点 (可选):**
```go
methodMap["newmethod"] = "calculator.v1.Calculator/NewMethod"
```

### 运行测试

```bash
# Go 测试
cd grpc-mesh-server
go test ./... -v

# Rust 测试
cd grpc-mesh-node
cargo test --all-features

# 端到端测试
cd demos
./test-calculator.sh
```

---

## 故障排查

### 常见问题

**Q: "Failed to load TLS certificate"**

A: 运行 `make certs` 在 `grpc-mesh-server/` 目录生成证书。

**Q: "Handshake failed"**

A: 检查 token 是否在客户端和服务器配置中匹配。

**Q: "Session not found"**

A: 确保节点已成功连接。检查日志中的连接错误。

**Q: "Method not found"**

A: 验证方法名完全匹配（区分大小写）。例如: `calculator.v1.Calculator/Add`

**Q: "Connection refused"**

A: 确保 mesh server 正在运行，防火墙允许连接到 8443 端口。

**Q: "Division by zero"**

A: 这是业务逻辑错误，不是框架问题。

### 调试技巧

1. **启用详细日志:**
```bash
# Go
export LOG_LEVEL=debug

# Rust
export RUST_LOG=debug
```

2. **查看 Prometheus 指标:**
```bash
curl http://localhost:9090/metrics | grep grpc_mesh
```

3. **测试连接:**
```bash
# 测试 TLS 连接
openssl s_client -connect localhost:8443

# 测试 gRPC
grpcurl -plaintext localhost:50051 list
```

4. **检查会话:**
```bash
curl http://localhost:8080/nodes | jq
```

---

## 项目状态

### 已完成功能 ✅

- ✅ TLS 隧道建立
- ✅ Yamux 多路复用
- ✅ 控制流协议（握手、心跳）
- ✅ 会话管理
- ✅ 反向拨号
- ✅ InvokePlane gRPC 服务
- ✅ MethodRegistry 路由
- ✅ 自动心跳（15s 间隔）
- ✅ Token 认证
- ✅ Prometheus 指标
- ✅ Demo 应用
- ✅ 端到端测试

### 测试结果

**最后测试:** 2025-12-15 22:53 CST

- ✅ 所有四则运算通过 (add, subtract, multiply, divide)
- ✅ 健康检查通过
- ✅ 节点注册和心跳正常
- ✅ Go 客户端编译成功
- ✅ Rust 服务编译成功
- ✅ 端到端通信验证

### 已知限制

1. **单控制流** - 每个节点一个控制流用于心跳，数据流按需创建
2. **无负载均衡** - 相同 ID 的节点后连接的会覆盖，需使用不同 ID
3. **无持久化** - 服务器重启后会话丢失，节点会自动重连
4. **手动证书管理** - 需手动生成和轮换证书

### 性能指标

- **心跳间隔:** 15 秒
- **连接建立:** < 10ms (本地)
- **RPC 延迟:** 1-3ms (本地，通过 Yamux)
- **吞吐量:** 受 Yamux 窗口大小限制（默认 1MB）

---

## 相关资源

### 技术文档

- [gRPC 官方文档](https://grpc.io/docs/)
- [Protocol Buffers Guide](https://developers.google.com/protocol-buffers)
- [Tonic (Rust gRPC)](https://github.com/hyperium/tonic)
- [Hashicorp Yamux](https://github.com/hashicorp/yamux)

### 类似项目

- [frp](https://github.com/fatedier/frp) - Fast Reverse Proxy
- [Ngrok](https://ngrok.com/) - Secure tunnels
- [Inlets](https://inlets.dev/) - Cloud-native tunnel
- [Teleport](https://goteleport.com/) - Infrastructure access

---

## 贡献

欢迎贡献代码、报告问题或提出建议！

### 开发流程

1. Fork 项目
2. 创建特性分支
3. 提交更改
4. 运行测试
5. 提交 Pull Request

### 代码规范

**Go:**
- 运行 `gofmt -s -w .`
- 运行 `go vet ./...`
- 遵循 [Effective Go](https://golang.org/doc/effective_go)

**Rust:**
- 运行 `cargo fmt`
- 运行 `cargo clippy -- -D warnings`
- 遵循 [Rust API Guidelines](https://rust-lang.github.io/api-guidelines/)

---

## 许可证

本项目采用 MIT 许可证。详见 [LICENSE](LICENSE) 文件。

---

## 联系方式

- **Issues:** [GitHub Issues](https://github.com/yourusername/grpc-mesh/issues)
- **Discussions:** [GitHub Discussions](https://github.com/yourusername/grpc-mesh/discussions)

---

**gRPC Mesh** - 让内网服务触手可及 🚀