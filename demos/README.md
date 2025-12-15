# gRPC Mesh Demos

本目录包含 gRPC Mesh 框架的完整示例，展示如何通过反向隧道实现内网服务调用。

## 架构说明

```
┌─────────────────────────┐
│ calculator-client       │  HTTP REST API (:8080)
│ (Go - 内嵌 mesh)        │  ├─ 业务 HTTP 服务
│                         │  └─ 内嵌 grpc-mesh-server
│   ├─ Tunnel (:8443)     │      接受节点连接
│   ├─ InvokePlane (:50051)│    暴露调用 API
│   └─ Gateway            │      直接调用内部 API
└──────────┬──────────────┘
           │ Yamux/TLS 反向隧道
           ↓
┌─────────────────────────┐
│ calculator-service      │  业务节点 (Rust)
│ 主动连接 :8443          │  ├─ 加减乘除实现
│ 注册并处理请求          │  └─ gRPC 服务
└─────────────────────────┘
```

**关键设计：**
- `calculator-client` 将 `grpc-mesh-server` 作为**库**集成（单进程部署）
- `calculator-service` 主动连接，建立反向隧道
- 客户端通过内部 API 直接调用，无需额外网络开销

## 快速开始

### 1. 启动客户端（内嵌 mesh）

```bash
cd calculator-client
go build -o calculator-client .
./calculator-client
```

**输出：**
```
╔════════════════════════════════════════════════════╗
║  Calculator Client - Embedded gRPC Mesh           ║
╚════════════════════════════════════════════════════╝
Configuration:
  HTTP Listen Address: :8080
  Target Node ID: calculator-service
  Mesh gRPC Address: :50051
  Mesh Tunnel Address: :8443
📡 Starting embedded gRPC Mesh Server...
✅ Mesh server started
   - Tunnel listener: :8443
   - InvokePlane gRPC: :50051
   - Metrics: :9090
🚀 Calculator Client listening on :8080
```

### 2. 启动服务节点

```bash
cd calculator-service
cargo build --release
cargo run --release
```

**输出：**
```
Calculator Service starting...
Connecting to grpc-mesh-server at localhost:8443...
✓ TLS handshake succeeded
✓ Yamux session established
✓ Registered as node: calculator-service
✓ Ready to serve requests
```

### 3. 测试 API

```bash
# 加法
curl -X POST http://localhost:8080/calculate \
  -H 'Content-Type: application/json' \
  -d '{"operation": "add", "a": 10, "b": 5}'
# 输出: {"result":15}

# 查看已连接节点
curl http://localhost:8080/nodes

# 健康检查
curl http://localhost:8080/health
```

## Demo 说明

### calculator-client (Go)

**位置：** `demos/calculator-client/`

**功能：**
- 提供 HTTP REST API (`:8080`)
- **内嵌 grpc-mesh-server**（不需要单独运行）
- 通过内部 `Gateway` API 直接调用节点
- 支持节点管理和健康检查

**关键代码：**
```go
// 内嵌启动 mesh
meshServer, _ := server.New(meshCfg)
meshServer.Start()

// 直接调用内部 API
gateway := meshServer.ReverseGateway()
resp, _ := gateway.Invoke(ctx, registry.PeerID(nodeID), invokeReq)
```

**配置：** `config.json`
```json
{
  "listen_address": ":8080",
  "target_node_id": "calculator-service",
  "mesh": {
    "grpc_address": ":50051",
    "tunnel_address": ":8443",
    "metrics_address": ":9090",
    "tls_cert_path": "../../grpc-mesh-server/config/tls/server-chain.crt",
    "tls_key_path": "../../grpc-mesh-server/config/tls/server.key"
  }
}
```

**API 端点：**
- `POST /calculate` - 执行计算
- `GET /nodes` - 列出已连接节点
- `GET /health` - 健康检查
- `GET /` - API 文档

### calculator-service (Rust)

**位置：** `demos/calculator-service/`

**功能：**
- 实现计算器 gRPC 服务（加减乘除）
- 使用 `grpc-mesh-node` 库连接到控制平面
- 通过反向隧道接收和处理请求

**关键代码：**
```rust
use grpc_mesh::tunnel::TunnelConnector;

// 连接到控制平面
let connector = TunnelConnector::new(config, shutdown_rx)?;
let (handshake, connection) = connector.connect().await?;

// 服务 gRPC 请求
tonic::transport::Server::builder()
    .add_service(CalculatorServer::new(service))
    .serve_with_incoming(incoming)
    .await?;
```

**配置：** `config.json`
```json
{
  "node_id": "calculator-service",
  "server_address": "localhost:8443",
  "version": "1.0.0",
  "tls": {
    "ca_cert_path": "../../grpc-mesh-server/config/tls/ca.crt",
    "client_cert_path": "../../grpc-mesh-server/config/tls/client.crt",
    "client_key_path": "../../grpc-mesh-server/config/tls/client.key"
  }
}
```

## 调用流程

```
1. HTTP 请求到达 calculator-client
   POST /calculate {"operation": "add", "a": 10, "b": 5}

2. 业务代码序列化为 Protobuf
   CalcRequest { a: 10, b: 5 } → bytes

3. 包装为 InvokeRequest
   {
     peer_id: "calculator-service",
     method: "calculator.v1.Calculator/Add",
     payload: <bytes>,
     timeout_ms: 5000
   }

4. 通过内嵌 mesh 的 Gateway 直接调用
   gateway.Invoke(ctx, peer_id, request)
   └─ 查找节点注册表
   └─ 通过 Yamux stream 发送到节点

5. calculator-service 接收请求
   └─ 反序列化 payload
   └─ 调用 gRPC handler: Add(10, 5)
   └─ 返回结果: 15

6. 响应原路返回
   calculator-service → Yamux → mesh → HTTP JSON
```

## 开发指南

### 添加新操作

**1. 更新 proto 定义：**
```protobuf
service Calculator {
  rpc Power(CalcRequest) returns (CalcResponse);
}
```

**2. Rust 服务实现：**
```rust
async fn power(&self, request: Request<CalcRequest>) -> Result<Response<CalcResponse>, Status> {
    let req = request.into_inner();
    Ok(Response::new(CalcResponse { result: req.a.powf(req.b) }))
}
```

**3. Go 客户端添加路由：**
```go
methodMap := map[string]string{
    "power": "calculator.v1.Calculator/Power",
}
```

### 目录结构

```
demos/
├── README.md                    # 本文件
├── calculator-client/           # Go 客户端（内嵌 mesh）
│   ├── main.go                  # 入口
│   ├── config.json              # 配置
│   ├── proto/                   # Proto 定义
│   ├── README.md                # 详细文档
│   └── INTEGRATION_SUMMARY.md   # 集成说明
├── calculator-service/          # Rust 服务节点
│   ├── src/main.rs              # 入口
│   ├── config.json              # 配置
│   ├── proto/                   # Proto 定义
│   └── README.md                # 详细文档
└── test-calculator.sh           # 自动化测试脚本
```

## 测试脚本

运行完整的端到端测试：

```bash
./test-calculator.sh
```

测试内容：
- ✓ 健康检查
- ✓ 加法运算
- ✓ 减法运算
- ✓ 乘法运算
- ✓ 除法运算
- ✓ 除零错误处理

## 集成方式对比

### 方式 1：库集成（本 Demo）

**优点：**
- 单进程部署，运维简单
- 性能最优（无额外网络开销）
- 直接访问节点注册表

**缺点：**
- 无法多应用共享 mesh
- mesh 升级需要重新编译

**适用场景：**
- 单一业务应用
- 性能敏感场景
- 简化部署需求

### 方式 2：独立部署

启动独立的 grpc-mesh-server：

```bash
cd grpc-mesh-server
./bin/grpc-mesh-server -config config/config.yaml
```

客户端通过 gRPC 调用：

```go
conn, _ := grpc.Dial("localhost:50051", grpc.WithInsecure())
client := rpc.NewInvokePlaneClient(conn)
resp, _ := client.Invoke(ctx, &rpc.InvokeRequest{...})
```

**优点：**
- 多应用共享 mesh
- mesh 独立升级
- 职责分离

**缺点：**
- 需要部署两个进程
- 额外的网络往返

**适用场景：**
- 多业务共享节点
- 大规模部署
- 需要独立扩缩容

## 故障排查

### calculator-client 启动失败

**错误：** `bind: address already in use`

**原因：** 端口被占用

**解决：**
```bash
# 查看占用
lsof -i :8080
lsof -i :8443
lsof -i :50051

# 修改配置文件中的端口
```

### calculator-service 连接失败

**错误：** `connection refused`

**原因：** calculator-client 未启动或端口不对

**解决：**
1. 确认 calculator-client 已启动
2. 检查 calculator-service 配置中的 `server_address`

### 调用失败 "node not found"

**原因：** 节点未注册或已断开

**解决：**
```bash
# 查看已连接节点
curl http://localhost:8080/nodes

# 检查 calculator-service 日志
# 确认 node_id 配置一致
```

### TLS 证书错误

**错误：** `unknown certificate authority`

**原因：** 证书路径错误或证书链不完整

**解决：**
1. 确认证书文件存在
2. 使用 `server-chain.crt`（包含 CA）
3. 检查路径配置

## 性能指标

在 MacBook Pro (M1, 16GB) 上的测试结果：

- **延迟：** P50: 2ms, P99: 5ms
- **吞吐量：** ~10K requests/sec
- **内存：** calculator-client ~50MB, calculator-service ~10MB

## 下一步

1. **查看详细文档：**
   - [calculator-client/README.md](calculator-client/README.md) - 客户端详解
   - [calculator-service/README.md](calculator-service/README.md) - 服务端详解
   - [calculator-client/INTEGRATION_SUMMARY.md](calculator-client/INTEGRATION_SUMMARY.md) - 集成总结

2. **扩展示例：**
   - 添加更多计算操作
   - 实现服务发现和负载均衡
   - 添加监控和日志

3. **生产部署：**
   - 配置真实 CA 证书
   - 启用 mTLS 双向认证
   - 配置 Token 认证
   - 部署到 Kubernetes

## 参考文档

- [../INTEGRATION_GUIDE.md](../INTEGRATION_GUIDE.md) - 完整集成指南
- [../grpc-mesh-server/README.md](../grpc-mesh-server/README.md) - 控制平面文档
- [../grpc-mesh-node/README.md](../grpc-mesh-node/README.md) - 节点 SDK 文档

## License

MIT