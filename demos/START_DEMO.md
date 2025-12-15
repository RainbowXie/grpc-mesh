# gRPC Mesh Demo 启动指南

本指南提供详细的步骤帮助你从零开始运行 Calculator Demo。

---

## 📋 第一步：检查环境

### 1.1 确认依赖已安装

```bash
# 检查 Go 版本（需要 1.21+）
go version

# 检查 Rust 版本（需要 1.77+）
rustc --version
cargo --version

# 检查 OpenSSL（用于生成证书）
openssl version
```

### 1.2 确认项目结构

```bash
cd /path/to/grpc-mesh
ls -la

# 应该看到：
# ├── demos/
# │   ├── calculator-client/
# │   └── calculator-service/
# ├── grpc-mesh-server/
# └── grpc-mesh-node/
```

---

## 🔐 第二步：生成 TLS 证书

```bash
cd grpc-mesh-server

# 生成证书
make certs

# 验证证书已生成
ls -la config/tls/
# 应该包含：
# ✅ ca.crt
# ✅ ca.key
# ✅ server.crt
# ✅ server.key
# ✅ server-chain.crt
```

**如果 `make certs` 失败，手动生成：**

```bash
mkdir -p config/tls
cd config/tls

# 生成 CA
openssl req -x509 -newkey rsa:4096 -sha256 -days 3650 \
  -nodes -keyout ca.key -out ca.crt \
  -subj "/CN=gRPC-Mesh-CA"

# 生成服务器证书
openssl req -newkey rsa:4096 -nodes \
  -keyout server.key -out server.csr \
  -subj "/CN=localhost"

openssl x509 -req -in server.csr -CA ca.crt -CAkey ca.key \
  -CAcreateserial -out server.crt -days 365 \
  -extfile <(echo "subjectAltName=DNS:localhost,IP:127.0.0.1")

# 创建证书链
cat server.crt ca.crt > server-chain.crt

# 清理临时文件
rm server.csr ca.srl

cd ../..
```

---

## 🔑 第三步：生成和配置 Token

### 3.1 生成 Token

```bash
cd grpc-mesh-server
make token
```

输出示例：
```
Generated token: waemu_7RCx4i4T6gU3O9Gqcx4-SvHMRN1V8dJ9
```

**保存这个 token！** 接下来需要配置到两个地方。

### 3.2 配置 Go 端（calculator-client）

编辑 `demos/calculator-client/config.yaml`：

```yaml
security:
  require_token: true
  allowed_tokens:
    - "waemu_7RCx4i4T6gU3O9Gqcx4-SvHMRN1V8dJ9"  # 替换为你的 token
```

### 3.3 配置 Rust 端（calculator-service）

编辑 `demos/calculator-service/config/config.json`：

```json
{
  "server": {
    "address": "127.0.0.1:8443",
    "tls": {
      "server_name": "localhost"
    }
  },
  "node": {
    "id": "calculator-service",
    "token": "waemu_7RCx4i4T6gU3O9Gqcx4-SvHMRN1V8dJ9"  // 替换为你的 token
  }
}
```

**确保两端的 token 一致！**

---

## 🏗️ 第四步：编译服务

### 4.1 编译 calculator-client（Go）

```bash
cd demos/calculator-client

# 编译
go build -o calculator-client

# 验证
ls -lh calculator-client
```

### 4.2 编译 calculator-service（Rust）

```bash
cd ../calculator-service

# 编译（Release 模式，性能更好）
cargo build --release

# 验证
ls -lh target/release/calculator-service
```

**注意**：首次编译 Rust 项目会下载依赖，可能需要几分钟。

---

## 🚀 第五步：启动服务

### 5.1 终端 1：启动 calculator-client

```bash
cd demos/calculator-client
./calculator-client
```

**预期输出：**

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
{"level":"info","ts":"...","msg":"starting grpc-mesh-server"}
{"level":"info","ts":"...","msg":"TLS listener ready","addr":":8443"}
✅ Mesh server started
   - Tunnel listener: :8443
   - InvokePlane gRPC: :50051
   - Metrics: :9090
🚀 Calculator Client listening on :8080
📖 API Documentation: http://localhost:8080/
```

**如果看到此输出，说明 calculator-client 成功启动！**

保持这个终端运行，打开新终端继续。

### 5.2 终端 2：启动 calculator-service

```bash
cd demos/calculator-service
cargo run --release
```

**预期输出：**

```
╔════════════════════════════════════════════════════╗
║  Calculator Service - gRPC-Mesh Demo              ║
╚════════════════════════════════════════════════════╝
📄 Loading configuration from: config/config.json
✅ Configuration loaded from file
✅ Loaded CA certificate from: ../../grpc-mesh-server/config/tls/ca.crt
Configuration:
  Tunnel Server: 127.0.0.1:8443
  Node ID: calculator-service
  Token: waemu_7RCx...
📡 Connecting to gRPC-Mesh control plane...
✅ Tunnel established successfully
✅ Service registered with control plane
🔒 TLS connection secured
🌐 Yamux multiplexing enabled
🚀 Calculator Service is now listening through the reverse tunnel
💡 Note: No local port is exposed - all traffic comes through the tunnel
✨ Ready to handle requests
```

**同时在终端 1 应该看到连接日志：**

```json
{"level":"info","ts":"...","msg":"session established","node_id":"calculator-service","version":"0.1.0"}
```

**如果看到这些日志，说明两个服务已成功建立隧道连接！** 🎉

保持两个终端运行，打开第三个终端测试。

---

## ✅ 第六步：测试服务

### 6.1 快速测试

打开新终端：

```bash
# 测试健康检查
curl http://localhost:8080/health
```

预期响应：
```json
{"status":"healthy","message":"pong"}
```

```bash
# 测试加法
curl -X POST http://localhost:8080/calculate \
  -H 'Content-Type: application/json' \
  -d '{"operation": "add", "a": 10, "b": 5}'
```

预期响应：
```json
{"result":15}
```

### 6.2 完整测试套件

```bash
cd demos
./test-calculator.sh
```

预期输出：

```
╔════════════════════════════════════════════════════╗
║  Calculator Demo - End-to-End Test                ║
╚════════════════════════════════════════════════════╝

[1/5] Checking calculator-client status...
✅ calculator-client is running

[2/5] Testing health check...
✅ Health check passed

[3/5] Testing addition (10 + 5)...
✅ Addition: 10 + 5 = 15

[4/5] Testing subtraction (10 - 5)...
✅ Subtraction: 10 - 5 = 5

[5/5] Testing multiplication (10 * 5)...
✅ Multiplication: 10 * 5 = 50

[Bonus] Testing division (10 / 5)...
✅ Division: 10 / 5 = 2

[Error Test] Testing divide by zero...
✅ Divide by zero error handled correctly

╔════════════════════════════════════════════════════╗
║  🎉 All Tests Passed!                              ║
╚════════════════════════════════════════════════════╝
```

---

## 🎯 第七步：理解架构

现在你已经有一个运行的系统：

```
┌─────────────────────────────────────────┐
│  终端 3: curl 测试                       │
│  http://localhost:8080/calculate        │
└──────────────┬──────────────────────────┘
               │ HTTP Request
               ▼
┌─────────────────────────────────────────┐
│  终端 1: calculator-client (Go)         │
│                                         │
│  ┌────────────────────────────────┐    │
│  │ HTTP Handler (:8080)           │    │
│  └──────────┬─────────────────────┘    │
│             │                           │
│  ┌──────────▼─────────────────────┐    │
│  │ Embedded Mesh Server           │    │
│  │ - InvokePlane gRPC (:50051)    │    │
│  │ - Tunnel Listener (:8443)      │    │
│  │ - Metrics (:9090)              │    │
│  └──────────┬─────────────────────┘    │
└─────────────┼─────────────────────────┘
              │ TLS + Yamux Tunnel
              │ (Reverse Connection)
              ▼
┌─────────────────────────────────────────┐
│  终端 2: calculator-service (Rust)      │
│                                         │
│  ┌────────────────────────────────┐    │
│  │ Tunnel Connector               │    │
│  │ - Outbound connection to :8443 │    │
│  │ - Heartbeat sender (15s)       │    │
│  └──────────┬─────────────────────┘    │
│             │                           │
│  ┌──────────▼─────────────────────┐    │
│  │ gRPC Calculator Service        │    │
│  │ - Add, Subtract, Multiply...   │    │
│  └────────────────────────────────┘    │
└─────────────────────────────────────────┘
```

**关键点：**
- ✅ calculator-service **主动连接**到 calculator-client（反向隧道）
- ✅ calculator-service **不暴露**任何本地端口
- ✅ 所有流量通过加密的 Yamux 隧道传输
- ✅ 心跳每 15 秒发送一次，保持会话活跃

---

## 📊 第八步：监控和日志

### 查看 Prometheus 指标

```bash
curl http://localhost:9090/metrics | grep tunnel
```

关键指标：
- `tunnel_active_sessions` - 当前活跃会话数（应该是 1）
- `invoke_requests_total` - 总调用次数
- `invoke_duration_seconds` - 调用延迟

### 查看已连接节点

```bash
curl http://localhost:8080/nodes | jq .
```

预期响应：
```json
{
  "total": 1,
  "nodes": [
    {
      "node_id": "calculator-service",
      "version": "0.1.0",
      "features": ["grpc", "calculator"],
      "metadata": {
        "language": "rust",
        "service": "calculator"
      },
      "connected_at": "2025-12-15T16:55:00Z",
      "last_heartbeat": "2025-12-15T16:56:15Z"
    }
  ]
}
```

### 启用 DEBUG 日志

**Go 端（calculator-client）：**

修改 `config.yaml`：
```yaml
observability:
  log_level: "debug"
```

重启 calculator-client。

**Rust 端（calculator-service）：**

```bash
export RUST_LOG=debug
cargo run --release
```

---

## 🛑 停止服务

在各个终端按 `Ctrl+C` 优雅关闭服务。

预期看到：
```
🛑 Shutting down server...
✅ Server stopped gracefully
```

---

## 🐛 故障排查

### 问题 1：calculator-client 启动失败

**症状：**
```
Failed to create mesh server: certPath and keyPath must be provided
```

**解决：**
1. 确认证书文件存在：
   ```bash
   ls -la grpc-mesh-server/config/tls/
   ```
2. 检查 `config.yaml` 中的路径是否正确
3. 重新生成证书（见第二步）

### 问题 2：calculator-service 连接失败

**症状：**
```
Failed to establish tunnel: connection refused
```

**解决：**
1. 确认 calculator-client 已启动：
   ```bash
   netstat -tuln | grep 8443
   ```
2. 检查防火墙设置
3. 确认配置文件中地址为 `127.0.0.1:8443` 或 `localhost:8443`

### 问题 3：Token 认证失败

**症状：**
```
Handshake failed: unauthorized
```

**解决：**
```bash
# 检查 Go 端配置
grep allowed_tokens demos/calculator-client/config.yaml

# 检查 Rust 端配置
grep token demos/calculator-service/config/config.json

# 确保两者完全一致
```

### 问题 4：心跳超时

**症状：**
```
{"level":"error","msg":"control stream receive failed","error":"i/o deadline reached"}
```

**解决：**
1. 确保 calculator-service 是最新编译的版本（包含心跳功能）
2. 检查网络连接是否稳定
3. 查看 Rust 端日志是否有心跳发送记录：
   ```bash
   export RUST_LOG=debug
   cargo run --release
   # 应该每 15 秒看到：💓 Heartbeat sent
   ```

### 问题 5：端口被占用

**症状：**
```
bind: address already in use
```

**解决：**
```bash
# 找出占用端口的进程
lsof -i :8080  # 或 :8443, :50051, :9090

# 停止占用进程
kill <PID>

# 或修改配置文件使用其他端口
```

---

## 📚 下一步

恭喜！你已经成功运行了 gRPC Mesh Demo。

继续学习：
- ✅ [配置指南](CONFIG_GUIDE.md) - 详细配置说明
- ✅ [快速测试指南](QUICK_TEST.md) - 更多测试用例
- ✅ [架构文档](README.md) - 深入理解架构
- ✅ [集成指南](calculator-client/INTEGRATION_SUMMARY.md) - 集成到你的项目

---

## ✨ 成功检查清单

- [ ] ✅ TLS 证书已生成
- [ ] ✅ Token 已生成并配置到两端
- [ ] ✅ calculator-client 成功启动，监听 :8080, :8443, :50051, :9090
- [ ] ✅ calculator-service 成功连接并注册
- [ ] ✅ 健康检查返回 `healthy`
- [ ] ✅ 计算接口正常工作
- [ ] ✅ 心跳每 15 秒发送，会话保持活跃
- [ ] ✅ 所有测试用例通过

**如果所有检查项都通过，说明你的 gRPC Mesh Demo 已完美运行！** 🎉🎉🎉