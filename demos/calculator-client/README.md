# Calculator Client

Go 客户端示例，展示如何将 `grpc-mesh-server` 作为**库**集成到业务应用中。

## 特性

- 🔧 **单进程部署**：内嵌 grpc-mesh-server，无需单独运行
- 🚀 **高性能**：直接调用内部 API，无额外网络开销
- 📊 **节点管理**：实时查询节点状态和元数据
- 🔍 **直接访问注册表**：支持自定义路由和负载均衡

## 架构

```
calculator-client (单个 Go 进程)
├── HTTP REST API (:8080)
└── 内嵌 grpc-mesh-server
    ├── Tunnel 监听器 (:8443) ← calculator-service 连接
    ├── InvokePlane (:50051)
    └── Registry (节点注册表)
```

## 快速开始

```bash
# 构建
go build -o calculator-client .

# 启动
./calculator-client

# 测试
curl -X POST http://localhost:8080/calculate \
  -H 'Content-Type: application/json' \
  -d '{"operation": "add", "a": 10, "b": 5}'
```
## Configuration File

The client reads configuration from `config.yaml`:

```yaml
# Application-specific settings
app:
  listen_address: ":8080"
  target_node_id: "calculator-service"

# Embedded gRPC Mesh Server Configuration
server:
  grpc_address: ":50051"
  metrics_address: ":9090"
  heartbeat_interval: "15s"
  invoke_timeout: "30s"

listener:
  address: ":8443"
  tls_cert_path: "../../grpc-mesh-server/config/tls/server-chain.crt"
  tls_key_path: "../../grpc-mesh-server/config/tls/server.key"
  ca_file: ""  # Empty string disables mTLS (client cert verification)
  prefer_server_cipher_suites: true

tunnel:
  accept_backlog: 128
  enable_keepalive: true
  max_stream_window: 1048576
  keepalive_interval: "30s"
  keepalive_timeout: "90s"

security:
  require_token: true
  allowed_tokens:
    - "waemu_7RCx4i4T6gU3O9Gqcx4-SvHMRN1V8dJ9"
  node_whitelist: []
  handshake_deadline: "5s"

observability:
  log_level: "info"
```

**Important Notes:**
- This format is **identical** to `grpc-mesh-server/config/config.yaml`, with an additional `app` section for application-specific settings.
- `ca_file: ""` disables mTLS (mutual TLS). Set it to a CA cert path if you want to verify client certificates.
- Configuration is loaded using the same `config.Load()` function as grpc-mesh-server, ensuring consistency.

## API 端点

### POST /calculate
```bash
curl -X POST http://localhost:8080/calculate \
  -d '{"operation": "add", "a": 10, "b": 5}'
# {"result": 15}
```

### GET /nodes
```bash
curl http://localhost:8080/nodes
# {"total": 1, "nodes": [...]}
```

### GET /health
```bash
curl http://localhost:8080/health
# {"status": "healthy"}
```

## 核心代码

### 1. 内嵌启动 mesh

```go
import (
    "github.com/grpc-mesh/grpc-mesh-server/pkg/config"
    "github.com/grpc-mesh/grpc-mesh-server/pkg/server"
)

// 配置 mesh
meshCfg := &config.Config{
    Server: config.ServerConfig{
        GRPCAddress: ":50051",
        InvokeTimeout: 30 * time.Second,
    },
    Listener: config.ListenerConfig{
        Address: ":8443",
        TLSCertPath: "tls/server-chain.crt",
        TLSKeyPath: "tls/server.key",
    },
    // ...
}

// 创建并启动
meshServer, _ := server.New(meshCfg)
meshServer.Start()
defer meshServer.Stop()
```

### 2. 调用节点

```go
import (
    "github.com/grpc-mesh/grpc-mesh-server/pkg/registry"
    "github.com/grpc-mesh/grpc-mesh-server/pkg/rpc"
)

// 获取 Gateway
gateway := meshServer.ReverseGateway()

// 调用
resp, err := gateway.Invoke(ctx, 
    registry.PeerID("calculator-service"),
    &rpc.InvokeRequest{
        PeerId: "calculator-service",
        Method: "calculator.v1.Calculator/Add",
        Payload: payload,
        TimeoutMs: 5000,
    })
```

### 3. 查询节点

```go
reg := meshServer.Registry()

// 检查节点是否在线
session, exists := reg.Get(registry.PeerID("calculator-service"))

// 列出所有节点
sessions := reg.List()
```

## 关键 API

### server.Server

```go
func New(cfg *config.Config) (*Server, error)
func (s *Server) Start() error
func (s *Server) Stop()
func (s *Server) Registry() *registry.SessionManager
func (s *Server) ReverseGateway() *reverse.Gateway
```

### reverse.Gateway

```go
func (g *Gateway) Invoke(
    ctx context.Context,
    peerID registry.PeerID,
    req *rpc.InvokeRequest,
) (*rpc.InvokeResponse, error)
```

### registry.SessionManager

```go
func (m *SessionManager) Get(id PeerID) (*SessionState, bool)
func (m *SessionManager) List() []SessionState
func (m *SessionManager) Size() int
```

## 使用场景

### 场景 1: 负载均衡

```go
func selectHealthyNode(reg *registry.SessionManager) registry.PeerID {
    sessions := reg.List()
    
    var healthy []registry.PeerID
    for _, s := range sessions {
        if !s.Closed && time.Since(s.LastHeartbeat) < 30*time.Second {
            healthy = append(healthy, s.ID)
        }
    }
    
    return healthy[rand.Intn(len(healthy))]
}
```

### 场景 2: 基于元数据路由

```go
func findNodeByRegion(reg *registry.SessionManager, region string) registry.PeerID {
    for _, s := range reg.List() {
        if s.Metadata["region"] == region {
            return s.ID
        }
    }
    return ""
}
```

## 依赖管理

`go.mod`:
```go
require (
    github.com/grpc-mesh/grpc-mesh-server v0.0.0
    github.com/gin-gonic/gin v1.9.1
    google.golang.org/grpc v1.77.0
    google.golang.org/protobuf v1.36.11
)

replace github.com/grpc-mesh/grpc-mesh-server => ../../grpc-mesh-server
```

## 故障排查

**端口占用：**
```bash
lsof -i :8080
lsof -i :8443
lsof -i :50051
```

**节点未连接：**
```bash
curl http://localhost:8080/nodes
# 检查 calculator-service 是否启动
```

**TLS 错误：**
- 确认证书文件存在
- 使用 `server-chain.crt`（包含 CA）

## 参考

- [../README.md](../README.md) - 完整 Demo 说明
- [INTEGRATION_SUMMARY.md](INTEGRATION_SUMMARY.md) - 详细集成文档
- [../../INTEGRATION_GUIDE.md](../../INTEGRATION_GUIDE.md) - 集成指南