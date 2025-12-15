# gRPC Mesh 配置指南

本指南说明 gRPC Mesh 各组件的配置文件位置和格式。

---

## 📋 配置文件总览

| 组件 | 配置文件 | 格式 | 用途 |
|------|---------|------|------|
| **grpc-mesh-server** (独立部署) | `grpc-mesh-server/config/config.yaml` | YAML | 独立运行的控制平面服务器 |
| **calculator-client** (嵌入式) | `demos/calculator-client/config.yaml` | YAML | 嵌入 mesh server 的业务应用 |
| **calculator-service** (Rust 节点) | `demos/calculator-service/config/config.json` | JSON | Rust 实现的计算器服务节点 |
| **grpc-mesh-node** (通用 Rust 节点) | `grpc-mesh-node/config/config.json` | JSON | 通用的 Rust 节点实现 |

---

## 🎯 配置策略说明

### 方案一：独立部署模式

**适用场景**：控制平面和业务应用分离部署

```
┌─────────────────┐
│ 业务应用 (Go/Rust) │ → 通过 gRPC 调用
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ grpc-mesh-server │ ← 使用 grpc-mesh-server/config/config.yaml
│   (独立进程)      │
└────────┬────────┘
         │
         ▼
  内网节点 (Rust)
```

**配置文件**：`grpc-mesh-server/config/config.yaml`

```yaml
server:
  grpc_address: ":50051"      # InvokePlane gRPC 服务端口
  metrics_address: ":9090"
  heartbeat_interval: "15s"
  invoke_timeout: "30s"

listener:
  address: ":8443"            # Yamux tunnel 监听端口
  tls_cert_path: "./config/tls/server-chain.crt"
  tls_key_path: "./config/tls/server.key"

security:
  require_token: true
  allowed_tokens:
    - "waemu_your_token_here"
```

**启动方式**：
```bash
cd grpc-mesh-server
./bin/grpc-mesh-server -config config/config.yaml
```

---

### 方案二：嵌入式部署模式 ⭐ 推荐

**适用场景**：业务应用内嵌控制平面，单进程部署

```
┌────────────────────────────┐
│  calculator-client         │
│  ┌──────────────────────┐  │
│  │ HTTP API Handler     │  │ ← 使用 demos/calculator-client/config.yaml
│  └──────────┬───────────┘  │
│             │              │
│  ┌──────────▼───────────┐  │
│  │ Embedded Mesh Server │  │ (同一进程)
│  └──────────┬───────────┘  │
└─────────────┼──────────────┘
              │
              ▼
       内网节点 (Rust)
```

**配置文件**：`demos/calculator-client/config.yaml`

```yaml
# 业务应用专属配置
app:
  listen_address: ":8080"       # HTTP API 端口
  target_node_id: "calculator-service"

# 嵌入的 mesh server 配置（与 grpc-mesh-server/config/config.yaml 格式相同）
server:
  grpc_address: ":50051"
  metrics_address: ":9090"
  heartbeat_interval: "15s"
  invoke_timeout: "30s"

listener:
  address: ":8443"
  tls_cert_path: "../../grpc-mesh-server/config/tls/server-chain.crt"
  tls_key_path: "../../grpc-mesh-server/config/tls/server.key"

security:
  require_token: true
  allowed_tokens:
    - "waemu_7RCx4i4T6gU3O9Gqcx4-SvHMRN1V8dJ9"
```

**启动方式**：
```bash
cd demos/calculator-client
./calculator-client  # 自动读取 config.yaml
```

**优势**：
- ✅ 单进程部署，简化运维
- ✅ 直接函数调用，无网络开销
- ✅ 配置统一，易于管理
- ✅ 适合微服务架构

---

## 🦀 Rust 节点配置

### calculator-service 配置

**配置加载优先级**：
1. **配置文件**（优先）：`config/config.json` 或 `$CONFIG_PATH`
2. **环境变量**（回退）：如果配置文件不存在，使用环境变量

**配置文件**：`demos/calculator-service/config/config.json`

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

**启动方式**：

```bash
# 使用配置文件（推荐）
cd demos/calculator-service
cargo run --release

# 使用环境变量
export TUNNEL_SERVER="localhost:8443"
export TUNNEL_TOKEN="waemu_7RCx4i4T6gU3O9Gqcx4-SvHMRN1V8dJ9"
export NODE_ID="calculator-service"
cargo run --release
```

---

## 🔐 TLS 证书配置

### 证书文件位置

所有组件共享同一套证书（位于 `grpc-mesh-server/config/tls/`）：

```
grpc-mesh-server/config/tls/
├── ca.crt              # CA 根证书
├── ca.key              # CA 私钥
├── server.crt          # 服务器证书
├── server.key          # 服务器私钥
└── server-chain.crt    # 服务器证书链 (server.crt + ca.crt)
```

### 证书生成

```bash
cd grpc-mesh-server
make certs
```

### 证书配置要点

1. **服务端（mesh server）**：
   - 使用 `server-chain.crt`（包含完整证书链）
   - 使用 `server.key`

2. **客户端（Rust 节点）**：
   - 使用 `ca.crt` 验证服务器证书
   - 配置 `server_name` 与证书 CN/SAN 匹配

---

## 🔑 Token 配置

### 生成 Token

```bash
cd grpc-mesh-server
make token
```

输出示例：
```
Generated token: waemu_7RCx4i4T6gU3O9Gqcx4-SvHMRN1V8dJ9
```

### 配置 Token

**Go 端（服务器侧）**：

在 `config.yaml` 的 `security.allowed_tokens` 中添加：

```yaml
security:
  require_token: true
  allowed_tokens:
    - "waemu_7RCx4i4T6gU3O9Gqcx4-SvHMRN1V8dJ9"
    - "waemu_SecondaryToken"  # 支持多个 token（用于轮换）
```

**Rust 端（节点侧）**：

在 `config.json` 中配置：

```json
{
  "node": {
    "token": "waemu_7RCx4i4T6gU3O9Gqcx4-SvHMRN1V8dJ9"
  }
}
```

或通过环境变量：

```bash
export WA_NODE_TOKEN="waemu_7RCx4i4T6gU3O9Gqcx4-SvHMRN1V8dJ9"
cargo run --release
```

---

## 📝 配置加载优先级

### Go 端 (calculator-client)

1. 环境变量：`CONFIG_PATH=/path/to/config.yaml`
2. 默认路径：`./config.yaml`

**环境变量覆盖**：
- `LISTEN_ADDRESS` → 覆盖 `app.listen_address`
- `TARGET_NODE_ID` → 覆盖 `app.target_node_id`

### Rust 端 (calculator-service)

**配置加载优先级**：
1. 配置文件优先：`config/config.json` 或 `$CONFIG_PATH`
2. 环境变量回退：如果配置文件不存在，从环境变量加载

**支持的环境变量**：
- `CONFIG_PATH` → 配置文件路径（默认：`config/config.json`）
- `TUNNEL_TOKEN` / `WA_NODE_TOKEN` → Token（配置文件不存在时必填）
- `TUNNEL_SERVER` / `SERVER_ADDRESS` → 服务器地址
- `NODE_ID` / `WA_NODE_ID` → 节点 ID
- `CA_CERT_PATH` → CA 证书路径

**注意**：配置文件存在时，环境变量**不会**覆盖文件中的值（与 Go 端不同）

---

## 🚀 快速启动配置检查清单

### ✅ 启动前检查

**1. 证书文件存在**
```bash
ls -l grpc-mesh-server/config/tls/
# 应该包含: ca.crt, server-chain.crt, server.key
```

**2. Token 已配置且匹配**
```bash
# 检查 Go 端（服务器侧）
grep allowed_tokens demos/calculator-client/config.yaml

# 检查 Rust 端（节点侧）
cat demos/calculator-service/config/config.json | jq '.node.token'

# 或检查环境变量
echo $TUNNEL_TOKEN
```

**3. 端口不冲突**
```bash
# 确保以下端口未被占用
netstat -tuln | grep -E '(8080|8443|50051|9090)'
```

### ✅ 配置文件模板

**嵌入式部署最小配置**：

`demos/calculator-client/config.yaml`:
```yaml
app:
  listen_address: ":8080"
  target_node_id: "calculator-service"

server:
  grpc_address: ":50051"
  metrics_address: ":9090"

listener:
  address: ":8443"
  tls_cert_path: "../../grpc-mesh-server/config/tls/server-chain.crt"
  tls_key_path: "../../grpc-mesh-server/config/tls/server.key"

security:
  require_token: true
  allowed_tokens:
    - "waemu_YOUR_TOKEN_HERE"
```

`demos/calculator-service/config/config.json`:
```json
{
  "server": {
    "address": "localhost:8443",
    "tls": {
      "server_name": "localhost",
      "ca_cert_path": "../../grpc-mesh-server/config/tls/ca.crt"
    }
  },
  "node": {
    "id": "calculator-service",
    "token": "waemu_YOUR_TOKEN_HERE"
  }
}
```

---

## ❓ 常见问题

### Q: 为什么 Go 端用 YAML，Rust 端用 JSON？

**A**: 
- Go 生态习惯使用 YAML（更易读，支持注释）
- Rust 生态通常使用 JSON 或 TOML（我们选择 JSON 因为简单且无需额外依赖）
- 两者功能等价，选择各自社区习惯的格式

### Q: Rust 端的配置文件和环境变量能同时使用吗？

**A**: 
- 如果配置文件存在，环境变量**不会**覆盖文件中的值
- 如果配置文件不存在，会完全从环境变量读取配置
- 这与 Go 端不同（Go 端的环境变量可以覆盖配置文件）

### Q: 能否在 calculator-client 中使用 grpc-mesh-server/config/config.yaml？

**A**: 不推荐。虽然技术上可行，但：
- `calculator-client` 需要额外的 `app` 配置段
- 保持独立配置文件便于部署和版本管理
- 证书路径可能需要调整

### Q: 如何在生产环境管理配置？

**A**: 推荐方案：
1. 使用环境变量覆盖敏感信息（token、密码）
2. 配置文件存储在配置中心（如 etcd、Consul）
3. 容器化部署时通过 ConfigMap/Secret 注入
4. 定期轮换 Token（支持多 token 并存）

### Q: 如何禁用 Token 认证（开发环境）？

**A**: 
```yaml
security:
  require_token: false  # 设置为 false
  allowed_tokens: []
```

⚠️ 生产环境**必须**启用 Token 认证！

---

## 🔗 相关文档

- [快速开始](./README.md)
- [Token 策略](../grpc-mesh-server/docs/token_policy.md)
- [TLS 配置指南](../grpc-mesh-server/docs/tls_setup.md)
- [部署最佳实践](./INTEGRATION_SUMMARY.md)

---

**总结**：

- ✅ **嵌入式部署**：使用 `demos/calculator-client/config.yaml`（推荐）
- ✅ **独立部署**：使用 `grpc-mesh-server/config/config.yaml`
- ✅ **Rust 节点**：使用 `demos/calculator-service/config/config.json`
- ✅ **配置格式统一**：Go 端统一 YAML，Rust 端统一 JSON
- ✅ **证书共享**：所有组件使用同一套 TLS 证书

如有疑问，请参考各子项目的 README 或提交 Issue。