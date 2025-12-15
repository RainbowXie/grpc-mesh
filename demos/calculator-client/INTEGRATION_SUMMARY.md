# Calculator Client 集成总结

本文档总结了如何将 `grpc-mesh-server` 作为 **Go 库** 集成到业务应用中。

## 集成方式

### ❌ 错误方式（独立进程调用）

```
calculator-client (进程1)
    │ 通过 gRPC 客户端调用
    ↓
grpc-mesh-server (进程2)
    │ 独立运行的服务
    ↓
calculator-service (进程3)
```

### ✅ 正确方式（库集成）

```
calculator-client (单个 Go 进程)
├── import "github.com/grpc-mesh/grpc-mesh-server/pkg/server"
├── 内嵌启动 mesh server
│   ├── Tunnel listener (:8443)
│   ├── InvokePlane gRPC (:50051)
│   └── Registry
└── 业务代码直接调用 mesh 内部 API
    └── meshServer.ReverseGateway().Invoke(...)
```

## 核心代码

### 1. 导入依赖

```go
import (
    "github.com/grpc-mesh/grpc-mesh-server/pkg/config"
    "github.com/grpc-mesh/grpc-mesh-server/pkg/server"
    "github.com/grpc-mesh/grpc-mesh-server/pkg/registry"
    "github.com/grpc-mesh/grpc-mesh-server/pkg/rpc"
)
```

### 2. 配置 mesh server

```go
meshCfg := &config.Config{
    Server: config.ServerConfig{
        GRPCAddress:       ":50051",
        MetricsAddress:    ":9090",
        HeartbeatInterval: 15 * time.Second,
        InvokeTimeout:     30 * time.Second,
    },
    Listener: config.ListenerConfig{
        Address:            ":8443",
        TLSCertPath:        "./tls/server-chain.crt",
        TLSKeyPath:         "./tls/server.key",
        PreferServerCipher: true,
    },
    Tunnel: config.TunnelConfig{
        AcceptBacklog:     128,
        EnableKeepAlive:   true,
        MaxStreamWindow:   1048576,
        KeepAliveInterval: 30 * time.Second,
        KeepAliveTimeout:  90 * time.Second,
    },
    Security: config.SecurityConfig{
        RequireToken: true,
        AllowedTokens: []string{
            "waemu_7RCx4i4T6gU3O9Gqcx4-SvHMRN1V8dJ9",
        },
        NodeWhitelist:     []string{},
        HandshakeDeadline: 5 * time.Second,
    },
    Observability: config.Observability{
        LogLevel: "info",
    },
}
```

### 3. 创建并启动 mesh server

```go
meshServer, err := server.New(meshCfg)
if err != nil {
    log.Fatalf("Failed to create mesh server: %v", err)
}

if err := meshServer.Start(); err != nil {
    log.Fatalf("Failed to start mesh server: %v", err)
}
defer meshServer.Stop()
```

### 4. 调用远程节点

```go
// 获取 Gateway
gateway := meshServer.ReverseGateway()

// 构造请求
invokeReq := &rpc.InvokeRequest{
    PeerId:    "calculator-service",
    Method:    "calculator.v1.Calculator/Add",
    Payload:   payload,  // 序列化的 protobuf 数据
    TimeoutMs: 5000,
}

// 调用
resp, err := gateway.Invoke(ctx, registry.PeerID("calculator-service"), invokeReq)
if err != nil {
    // 处理错误
}

// 检查结果
if !resp.Success {
    log.Printf("Error: %s", resp.Error.Message)
}

// 解析响应
var result MyResponse
proto.Unmarshal(resp.Result, &result)
```

### 5. 查询节点状态

```go
// 获取注册表
reg := meshServer.Registry()

// 检查节点是否在线
session, exists := reg.Get(registry.PeerID("calculator-service"))
if exists {
    log.Printf("Node online: %s", session.ID)
    log.Printf("Version: %s", session.Handshake.Version)
    log.Printf("Connected at: %s", session.ConnectedAt)
}

// 列出所有节点
sessions := reg.List()
for _, sess := range sessions {
    fmt.Printf("Node: %s, Features: %v\n", sess.ID, sess.Features)
}

// 获取节点数量
count := reg.Size()
```

## 关键 API

### server.Server

**创建服务器：**
```go
func New(cfg *config.Config) (*Server, error)
```

**启动和停止：**
```go
func (s *Server) Start() error
func (s *Server) Stop()
```

**获取组件：**
```go
func (s *Server) Registry() *registry.SessionManager
func (s *Server) ReverseGateway() *reverse.Gateway
func (s *Server) ReverseDialer() *reverse.Dialer
```

### reverse.Gateway

**调用节点：**
```go
func (g *Gateway) Invoke(
    ctx context.Context,
    peerID registry.PeerID,
    req *rpc.InvokeRequest,
    opts ...grpc.CallOption,
) (*rpc.InvokeResponse, error)
```

**流式调用：**
```go
func (g *Gateway) InvokeStream(
    ctx context.Context,
    peerID registry.PeerID,
    opts ...grpc.CallOption,
) (rpc.InvokePlane_InvokeStreamClient, func() error, error)
```

**设置超时：**
```go
func (g *Gateway) WithInvokeTimeout(timeout time.Duration) *Gateway
```

### registry.SessionManager

**节点查询：**
```go
func (m *SessionManager) Get(id PeerID) (*SessionState, bool)
func (m *SessionManager) List() []SessionState
func (m *SessionManager) Size() int
```

**打开 stream：**
```go
func (m *SessionManager) OpenStream(ctx context.Context, id PeerID) (net.Conn, error)
```

**控制通道：**
```go
func (m *SessionManager) ControlChannel(id PeerID) (*control.ControlStream, error)
```

## 典型使用场景

### 场景 1：HTTP 网关调用内网服务

```go
func (h *Handler) HandleRequest(c *gin.Context) {
    // 1. 解析 HTTP 请求
    var req RequestBody
    c.BindJSON(&req)
    
    // 2. 序列化为 protobuf
    payload, _ := proto.Marshal(&pb.ServiceRequest{
        Data: req.Data,
    })
    
    // 3. 通过 mesh 调用
    gateway := h.meshServer.ReverseGateway()
    resp, err := gateway.Invoke(c.Request.Context(), 
        registry.PeerID(req.NodeID), 
        &rpc.InvokeRequest{
            PeerId:  req.NodeID,
            Method:  req.ServiceMethod,
            Payload: payload,
        })
    
    // 4. 返回结果
    c.JSON(200, gin.H{"result": resp.Result})
}
```

### 场景 2：负载均衡选择节点

```go
func (h *Handler) CallWithLoadBalance(ctx context.Context, method string, payload []byte) error {
    reg := h.meshServer.Registry()
    sessions := reg.List()
    
    // 过滤健康节点
    var healthyNodes []registry.PeerID
    for _, sess := range sessions {
        if !sess.Closed && time.Since(sess.LastHeartbeat) < 30*time.Second {
            healthyNodes = append(healthyNodes, sess.ID)
        }
    }
    
    if len(healthyNodes) == 0 {
        return errors.New("no healthy nodes available")
    }
    
    // 轮询选择
    nodeID := healthyNodes[h.counter%len(healthyNodes)]
    h.counter++
    
    // 调用
    gateway := h.meshServer.ReverseGateway()
    _, err := gateway.Invoke(ctx, nodeID, &rpc.InvokeRequest{
        PeerId:  string(nodeID),
        Method:  method,
        Payload: payload,
    })
    
    return err
}
```

### 场景 3：基于元数据路由

```go
func (h *Handler) CallByRegion(ctx context.Context, region string) error {
    reg := h.meshServer.Registry()
    sessions := reg.List()
    
    // 根据元数据筛选节点
    for _, sess := range sessions {
        if sess.Metadata["region"] == region {
            gateway := h.meshServer.ReverseGateway()
            _, err := gateway.Invoke(ctx, sess.ID, &rpc.InvokeRequest{
                PeerId: string(sess.ID),
                Method: "service/method",
            })
            return err
        }
    }
    
    return errors.New("no node in region: " + region)
}
```

## 配置管理

### go.mod

```go
module myapp

require (
    github.com/grpc-mesh/grpc-mesh-server v0.0.0
    github.com/gin-gonic/gin v1.9.1
    google.golang.org/grpc v1.77.0
    google.golang.org/protobuf v1.36.11
)

replace github.com/grpc-mesh/grpc-mesh-server => ../grpc-mesh-server
```

### 配置文件示例

**config.json:**
```json
{
  "app": {
    "listen_address": ":8080",
    "target_node_id": "my-service"
  },
  "mesh": {
    "grpc_address": ":50051",
    "tunnel_address": ":8443",
    "metrics_address": ":9090",
    "tls_cert_path": "./config/tls/server-chain.crt",
    "tls_key_path": "./config/tls/server.key",
    "ca_cert_path": "./config/tls/ca.crt"
  }
}
```

## 优雅停止

```go
func main() {
    // 启动 mesh
    meshServer, _ := server.New(meshCfg)
    meshServer.Start()
    
    // 启动业务服务
    httpServer := startHTTPServer()
    
    // 等待信号
    quit := make(chan os.Signal, 1)
    signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
    <-quit
    
    log.Println("Shutting down...")
    
    // 先停止 HTTP 服务（停止接收新请求）
    ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
    defer cancel()
    httpServer.Shutdown(ctx)
    
    // 再停止 mesh（关闭所有连接）
    meshServer.Stop()
    
    log.Println("Stopped gracefully")
}
```

## 监控和日志

### 日志级别

```go
meshCfg.Observability.LogLevel = "debug"  // debug, info, warn, error
```

### Prometheus 指标

访问 `http://localhost:9090/metrics` 获取：
- 连接节点数
- Invoke 请求数和延迟
- Yamux 流数量
- 错误率

### 自定义日志

```go
import "go.uber.org/zap"

logger := zap.NewProduction()
// mesh server 内部使用 zap，会自动输出到标准日志
```

## 常见问题

### Q1: 如何在多个 handler 中共享 meshServer？

**A:** 通过依赖注入传递：

```go
type Handler struct {
    meshServer *server.Server
}

func NewHandler(meshServer *server.Server) *Handler {
    return &Handler{meshServer: meshServer}
}
```

### Q2: 可以动态重载配置吗？

**A:** 需要重启 meshServer：

```go
func (s *Service) ReloadMesh(newCfg *config.Config) error {
    s.meshServer.Stop()
    
    newServer, err := server.New(newCfg)
    if err != nil {
        return err
    }
    
    if err := newServer.Start(); err != nil {
        return err
    }
    
    s.meshServer = newServer
    return nil
}
```

### Q3: 如何处理节点连接/断开事件？

**A:** 定期轮询注册表：

```go
func (s *Service) MonitorNodes(ctx context.Context) {
    ticker := time.NewTicker(10 * time.Second)
    defer ticker.Stop()
    
    for {
        select {
        case <-ctx.Done():
            return
        case <-ticker.C:
            sessions := s.meshServer.Registry().List()
            for _, sess := range sessions {
                if time.Since(sess.LastHeartbeat) > 30*time.Second {
                    log.Printf("Node %s appears dead", sess.ID)
                }
            }
        }
    }
}
```

### Q4: TLS 证书路径如何配置？

**A:** 建议使用相对路径或环境变量：

```go
certPath := os.Getenv("TLS_CERT_PATH")
if certPath == "" {
    certPath = "./config/tls/server-chain.crt"
}
meshCfg.Listener.TLSCertPath = certPath
```

### Q5: 如何支持多个业务实例共享节点？

**A:** 每个实例内嵌独立的 mesh，节点可以连接到多个实例：

```
calculator-client-1 (:8080, mesh :8443) ← calculator-service-1
calculator-client-2 (:8081, mesh :8444) ← calculator-service-2
calculator-client-3 (:8082, mesh :8445) ← calculator-service-3
```

或使用共享的独立 mesh 服务器（回到独立部署模式）。

## 最佳实践

1. **配置管理**
   - 使用环境变量覆盖关键配置（端口、证书路径）
   - 敏感信息（token）从环境变量或密钥管理系统读取

2. **错误处理**
   - 检查 `InvokeResponse.Success`
   - 实现重试逻辑（带指数退避）
   - 记录详细错误日志

3. **性能优化**
   - 调整 `InvokeTimeout` 适应业务需求
   - 使用 context 传递截止时间
   - 避免频繁调用 `Registry().List()`（缓存结果）

4. **监控**
   - 暴露 Prometheus 指标
   - 记录调用延迟和错误率
   - 监控节点在线状态

5. **测试**
   - 编写单元测试时 mock `reverse.Gateway`
   - 集成测试启动完整的 meshServer

## 总结

将 `grpc-mesh-server` 作为库集成的核心步骤：

1. **导入包**: `import "github.com/grpc-mesh/grpc-mesh-server/pkg/server"`
2. **构造配置**: `meshCfg := &config.Config{...}`
3. **创建服务器**: `meshServer, _ := server.New(meshCfg)`
4. **启动**: `meshServer.Start()`
5. **调用**: `gateway := meshServer.ReverseGateway(); gateway.Invoke(...)`
6. **查询**: `reg := meshServer.Registry(); reg.Get(...)`
7. **停止**: `meshServer.Stop()`

这种方式适合单一业务应用需要最简部署和最低延迟的场景。