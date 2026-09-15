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

The client reads configuration from `config.yaml`（经 `CONFIG_PATH` 环境变量可覆盖路径）:

```yaml
# Application-specific settings
app:
  listen_address: ":8080"
  target_node_id: "calculator-service"

# Embedded gRPC Mesh Server Configuration
# 以下键与 grpc-mesh-server 的 pkg/config 读取的 schema 一致
server:
  grpc_address: ":50051"
  metrics_address: ":9090"

listener:
  address: ":8443"
  cert_file: "../../grpc-mesh-server/config/tls/server-chain.crt"
  key_file: "../../grpc-mesh-server/config/tls/server.key"
  ca_file: ""  # 留空禁用 mTLS（客户端证书校验）

logging:
  level: "info"

auth:
  enabled: true
  # 每节点独立令牌：demo 节点只有同时声明 node id "calculator-service"
  # 且持有该令牌才会被接受
  node_tokens:
    calculator-service: "waemu_7RCx4i4T6gU3O9Gqcx4-SvHMRN1V8dJ9"
```

**Important Notes:**
- 配置由与 grpc-mesh-server 相同的 `config.Load()` 加载，`server`/`listener`/`logging`/`auth` 键为其真实 schema；环境变量可覆盖（如 `SERVER_GRPC_ADDRESS`、`LISTENER_ADDRESS`）。
- `auth.node_tokens` 把令牌绑定到节点身份；旧的 `security.allowed_tokens` 等键当前代码不读取。
- `ca_file: ""` disables mTLS. Set it to a CA cert path if you want to verify client certificates.

## API 端点

### POST /calculate（通用 Invoke 分发路径）

```bash
curl -X POST http://localhost:8080/calculate \
  -H 'Content-Type: application/json' \
  -d '{"operation": "add", "a": 10, "b": 5}'
# {"result": 15}
```

### POST /calculate-typed（类型化 gRPC 直调路径）

不经 `Invoke(method, payload)` 字符串分发，而是 `Gateway.Dial` 取得 gRPC 连接后用生成的 `CalculatorClient` 直接调用节点上的 `calculator.v1.Calculator` 服务。错误保留 gRPC 状态语义：

```bash
curl -X POST http://localhost:8080/calculate-typed \
  -H 'Content-Type: application/json' \
  -d '{"operation": "add", "a": 10, "b": 5}'
# {"result": 15}

curl -X POST http://localhost:8080/calculate-typed \
  -H 'Content-Type: application/json' \
  -d '{"operation": "divide", "a": 1, "b": 0}'
# {"error":"rpc error: code = InvalidArgument desc = Division by zero"}
```

### GET /nodes

```bash
curl http://localhost:8080/nodes
# {"total": 1, "nodes": [{"node_id": "calculator-service",
#   "methods": ["calculator.v1.Calculator/Add", "..."], ...}]}
```

`methods` 来自节点握手时上报的方法清单（metadata 键 `mesh.methods`）。

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

// 配置 mesh（字段与 config.yaml 的 schema 一一对应）
meshCfg := &config.Config{
    Server: config.ServerConfig{
        GRPCAddress:    ":50051",
        MetricsAddress: ":9090",
    },
    Listener: config.ListenerConfig{
        Address:  ":8443",
        CertFile: "tls/server-chain.crt",
        KeyFile:  "tls/server.key",
    },
    Auth: config.AuthConfig{
        Enabled:    true,
        NodeTokens: map[string]string{"calculator-service": "waemu_..."},
    },
}

// 创建并启动
meshServer, _ := server.New(meshCfg)
meshServer.Start()
defer meshServer.Stop()
```

### 2. 调用节点

通用分发路径（方法名字符串 + protobuf 编码的 payload 字节）：

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
        PeerId:  "calculator-service",
        Method:  "calculator.v1.Calculator/Add",
        Payload: payload, // proto.Marshal(&pb.CalcRequest{A: 10, B: 5})
        TimeoutMs: 5000,
    })
```

类型化直调路径（`Gateway.Dial` + 按 proto 生成的客户端代码）：

```go
// 连接建立在节点会话的一条 yamux 流上，用完必须 Close
conn, err := gateway.Dial(ctx, registry.PeerID("calculator-service"))
if err != nil { /* 节点未注册 */ }
defer conn.Close()

client := pb.NewCalculatorClient(conn)
resp, err := client.Add(ctx, &pb.CalcRequest{A: 10, B: 5})
// err 保留 gRPC 状态：节点返回 invalid_argument 时为 InvalidArgument
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