# wa-emu 反向网关框架

[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Go Version](https://img.shields.io/badge/go-1.21+-blue.svg)](https://golang.org)
[![Rust Version](https://img.shields.io/badge/rust-1.77+-orange.svg)](https://www.rust-lang.org)

> 基于 TLS + Yamux + gRPC 的内网服务反向网关解决方案

---

## 概述

`wa-emu` 是一个完整的反向网关框架，用于将内网服务安全地暴露给外部调用，无需复杂的网络配置。适用于边缘计算、IoT 设备管理、多租户 SaaS 等场景。

### 核心特性

- 🔐 **TLS 1.3 加密**：端到端加密通信
- 🚀 **Yamux 多路复用**：单连接承载多个逻辑流
- 🔄 **自动重连**：断线自动重连，指数退避
- 📡 **反向调用**：内网主动出网，公网反向调用
- 🔑 **Token 认证**：安全的节点身份验证
- 🚦 **灵活限流**：支持 QPS、并发、滑动窗口等多种策略
- 🛡️ **熔断保护**：防止故障扩散
- 📊 **可观测性**：Prometheus 指标 + 结构化日志
- 🔍 **服务发现**：动态方法注册表

### 架构图

```
┌─────────────────────────────────────────────────────────┐
│  外部业务系统                                              │
│  (REST API / gRPC Client / Dashboard)                   │
└──────────────────────┬──────────────────────────────────┘
                       │ HTTP/gRPC
                       ▼
┌─────────────────────────────────────────────────────────┐
│  grpc-mesh-server (公网控制平面 - Go)                        │
│                                                         │
│  ┌─────────────┐  ┌──────────────┐  ┌──────────────┐  │
│  │ InvokeProxy │→ │ SessionMgr   │→ │ ReverseDialer│  │
│  └─────────────┘  └──────────────┘  └──────────────┘  │
│         ↓                                               │
│  ┌─────────────┐  ┌──────────────┐  ┌──────────────┐  │
│  │    ACL      │  │  RateLimit   │  │CircuitBreaker│  │
│  └─────────────┘  └──────────────┘  └──────────────┘  │
└──────────────────────┬──────────────────────────────────┘
                       │ TLS 1.3 + Yamux
                       ▼
┌─────────────────────────────────────────────────────────┐
│  grpc-mesh-node (内网节点 - Rust)                              │
│                                                         │
│  ┌─────────────┐  ┌──────────────┐                     │
│  │  Transport  │→ │  gRPC Server │                     │
│  │  Connector  │  │  (业务服务)   │                     │
│  └─────────────┘  └──────────────┘                     │
└─────────────────────────────────────────────────────────┘
```

---

## 快速开始

### 前置要求

- **Go** 1.21+
- **Rust** 1.77+
- **OpenSSL**（用于生成证书）

### 5 分钟快速部署

```bash
# 1. 克隆项目
git clone https://github.com/wa-emu/wa-emu-module.git
cd wa-emu-module

# 2. 生成证书
cd grpc-mesh-server
make certs

# 3. 生成 Token
make token
# 输出: waemu_abc123xyz...（保存此 Token）

# 4. 配置 Token
vim config/config.yaml
# 添加 Token 到 security.allowed_tokens

# 5. 编译并启动控制平面
make build
./bin/grpc-mesh-server -config config/config.yaml

# 6. 编译并启动客户端（新终端）
cd ../grpc-mesh-node
export WA_NODE_TOKEN="waemu_abc123xyz..."
cargo build --release --bin reverse_gateway
./target/release/reverse_gateway

# 7. 测试（新终端）
grpcurl -plaintext \
  -d '{"peer_id": "your-node-id", "method": "health.echo", "payload": ""}' \
  localhost:50051 \
  wa_emu.InvokePlane/Invoke
```

详细教程请查看 [快速开始指南](QUICK_START.md)。

---

## 项目结构

```
wa-emu-module/
├── grpc-mesh-server/          # Go 控制平面
│   ├── cmd/server/         # 服务器入口
│   ├── pkg/
│   │   ├── server/         # 核心服务器逻辑
│   │   ├── registry/       # 会话和方法注册表
│   │   ├── tunnel/         # Yamux 隧道管理
│   │   ├── control/        # 控制流协议
│   │   ├── reverse/        # 反向拨号
│   │   └── fault/          # 熔断器
│   ├── config/             # 配置文件
│   ├── scripts/            # 工具脚本
│   └── docs/               # 文档
│
├── grpc-mesh-node/              # Rust 客户端
│   ├── src/
│   │   ├── bin/            # 二进制入口
│   │   ├── transport/      # 传输层
│   │   ├── control/        # 控制流
│   │   └── gateway/        # 网关核心
│   ├── config/             # 配置文件
│   └── docs/               # 文档
│
├── QUICK_START.md          # 快速开始指南
├── INTEGRATION_GUIDE.md    # 业务集成指南
├── ROADMAP.md              # 开发路线图
└── README.md               # 本文件
```

---

## 核心组件

### grpc-mesh-server (Go)

公网控制平面，负责：

- **会话管理**：维护内网节点连接状态
- **反向调用**：通过 Yamux 隧道调用内网服务
- **访问控制**：Token 认证 + ACL 授权
- **流量控制**：限流 + 熔断
- **服务发现**：方法注册表和动态路由

**API 文档**：[grpc-mesh-server/docs/API.md](grpc-mesh-server/docs/API.md)

### grpc-mesh-node (Rust)

内网客户端，负责：

- **隧道连接**：TCP → TLS → Yamux
- **自动重连**：指数退避重连策略
- **服务托管**：承载业务 gRPC 服务
- **控制流**：握手、心跳、方法注册

**API 文档**：[grpc-mesh-node/docs/API.md](grpc-mesh-node/docs/API.md)

---

## 使用场景

### 场景 1：内网服务暴露

将企业内网的多个微服务统一暴露给外部调用：

```
                外部调用
                   ↓
          [grpc-mesh-server]
           ↙     ↓     ↘
    [服务A] [服务B] [服务C]
     (内网)  (内网)  (内网)
```

### 场景 2：边缘设备管理

管理分布全球的边缘节点：

```
         中心控制台
              ↓
     [grpc-mesh-server]
      ↙    ↓    ↘
   [边缘1] [边缘2] [边缘3]
   (美国)  (欧洲)  (亚洲)
```

### 场景 3：多租户 SaaS

为每个租户部署独立实例：

```
      SaaS 平台
          ↓
  [grpc-mesh-server]
   ↙     ↓     ↘
[租户A] [租户B] [租户C]
 实例    实例    实例
```

---

## 业务集成

### Rust 端集成示例

```rust
use wa_emu_rs::gateway::ReverseGateway;
use wa_emu_rs::config::GatewayConfig;

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    // 1. 加载配置
    let config = GatewayConfig::load("config.json")?;
    
    // 2. 创建网关
    let gateway = ReverseGateway::new(config).await?;
    
    // 3. 注册业务服务
    gateway.add_service(MyServiceServer::new(my_service));
    
    // 4. 启动
    gateway.serve().await?;
    
    Ok(())
}
```

### Go 端调用示例

```go
// 连接控制平面
conn, _ := grpc.Dial("localhost:50051", grpc.WithInsecure())
client := pb.NewInvokePlaneClient(conn)

// 构造请求
req := &pb.InvokeRequest{
    PeerId:  "my-node-id",
    Method:  "myservice.GetUser",
    Payload: payload,
    TimeoutMs: 5000,
}

// 调用远程服务
resp, _ := client.Invoke(context.Background(), req)
```

详细集成指南请查看 [业务集成指南](INTEGRATION_GUIDE.md)。

---

## 配置说明

### grpc-mesh-server 配置

```yaml
server:
  listen_address: ":50051"    # gRPC 监听地址
  metrics_address: ":9090"    # Prometheus 指标端口

listener:
  address: ":8443"            # Yamux 监听地址
  tls_cert_path: "./config/tls/server.crt"
  tls_key_path: "./config/tls/server.key"

security:
  require_token: true
  allowed_tokens:
    - "waemu_your_token_here"

session:
  heartbeat_interval: "30s"
  heartbeat_timeout: "90s"
  cleanup_interval: "1m"
  inactive_timeout: "5m"

acl:
  enabled: true
  default_policy: "deny"
  rules:
    - peer_pattern: "*"
      method_pattern: "health.*"
      action: "allow"

rate_limit:
  enabled: true
  default_rate: 100
  default_burst: 200
```

### grpc-mesh-node 配置

```json
{
  "server": {
    "address": "gateway.example.com:8443",
    "tls": {
      "server_name": "gateway.example.com",
      "ca_cert_path": "config/ca.crt"
    }
  },
  "node": {
    "id": "auto",
    "token": "waemu_your_token_here",
    "version": "1.0.0",
    "supported_features": ["invoke", "health"]
  },
  "reconnect": {
    "base_delay_secs": 1,
    "max_delay_secs": 60,
    "max_retries": 0,
    "backoff_multiplier": 2.0
  }
}
```

---

## 运维指南

### 监控

```bash
# Prometheus 指标
curl http://localhost:9090/metrics

# 关键指标
# - tunnel_active_sessions: 活跃会话数
# - invoke_requests_total: 调用总数
# - invoke_duration_seconds: 调用延迟
# - circuit_breaker_state: 熔断器状态
```

### 日志

```bash
# 查看实时日志（结构化 JSON）
tail -f /var/log/grpc-mesh-server.log | jq .

# 过滤错误日志
tail -f /var/log/grpc-mesh-server.log | jq 'select(.level=="error")'
```

### 健康检查

```bash
# 控制平面健康检查
curl http://localhost:9090/healthz

# 查看会话状态
grpcurl -plaintext localhost:50051 list
```

---

## 部署方式

### Docker

```bash
# 构建镜像
docker build -t grpc-mesh-server:latest ./grpc-mesh-server
docker build -t grpc-mesh-node:latest ./grpc-mesh-node

# 运行
docker-compose up -d
```

### Kubernetes

```bash
# 部署到 K8s
kubectl apply -f deploy/k8s/
```

### Systemd

```bash
# 安装服务
sudo cp deploy/systemd/grpc-mesh-server.service /etc/systemd/system/
sudo systemctl enable grpc-mesh-server
sudo systemctl start grpc-mesh-server
```

详细部署指南请查看各子项目的 README。

---

## 性能指标

基于内部测试环境（仅供参考）：

| 指标 | 数值 |
|------|------|
| 握手延迟 (p99) | < 300ms |
| 调用延迟 (p99) | < 200ms |
| 吞吐量 | 1000+ RPS/节点 |
| 并发流 | 64+ 流/会话 |
| 最大会话数 | 1000+ |
| 内存占用 (Go) | ~50MB (空载) |
| 内存占用 (Rust) | ~10MB (空载) |

---

## 开发路线图

当前进度：**Phase 5 已完成**

- ✅ **Phase 1**: 协议冻结 & TLS 准备
- ✅ **Phase 2**: Yamux 隧道打底
- ✅ **Phase 3**: 控制流、握手与注册
- ✅ **Phase 4**: gRPC 反向注入
- ✅ **Phase 5**: 方法注册与业务完善
- 🔄 **Phase 6**: 观测性、压测与交付（进行中）

详细路线图请查看 [ROADMAP.md](ROADMAP.md)。

---

## 常见问题

**Q: 和 VPN 有什么区别？**

A: wa-emu 是应用层反向网关，只暴露特定的 gRPC 服务，不需要打通整个网络。更轻量、更安全。

**Q: 和 frp/ngrok 有什么区别？**

A: wa-emu 专为 gRPC 服务设计，内置 ACL、限流、熔断等企业级功能，更适合生产环境。

**Q: 性能如何？**

A: 单节点可达 1000+ RPS，Yamux 多路复用减少连接开销，适合大规模部署。

**Q: 支持 HTTP/WebSocket 吗？**

A: 当前版本专注于 gRPC，未来可能支持 HTTP。WebSocket 可通过 gRPC Stream 实现。

**Q: 如何保证安全？**

A: 多层安全机制：TLS 加密 + Token 认证 + ACL 授权 + 限流 + 审计日志。

---

## 贡献指南

欢迎贡献代码、文档或提出建议！

1. Fork 项目
2. 创建特性分支 (`git checkout -b feature/amazing-feature`)
3. 提交更改 (`git commit -m 'Add amazing feature'`)
4. 推送到分支 (`git push origin feature/amazing-feature`)
5. 提交 Pull Request

---

## 许可证

[MIT License](LICENSE)

---

## 技术支持

- 📧 **Email**: support@example.com
- 💬 **Issues**: [GitHub Issues](https://github.com/wa-emu/wa-emu-module/issues)
- 📖 **文档**: [在线文档](https://wa-emu.example.com/docs)
- 💡 **讨论**: [GitHub Discussions](https://github.com/wa-emu/wa-emu-module/discussions)

---

## 致谢

感谢以下开源项目：

- [tonic](https://github.com/hyperium/tonic) - gRPC 框架
- [yamux](https://github.com/hashicorp/yamux) - 多路复用协议
- [rustls](https://github.com/rustls/rustls) - TLS 实现
- [zap](https://github.com/uber-go/zap) - 结构化日志

---

<p align="center">
  Built with ❤️ by the wa-emu team
</p>
