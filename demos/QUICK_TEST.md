# 快速测试指南

本指南帮助你快速验证 gRPC Mesh Demo 是否正确运行。

---

## 📋 前置条件

### 1. 检查证书文件

```bash
ls -la grpc-mesh-server/config/tls/
```

应该包含：
- ✅ `ca.crt` - CA 根证书
- ✅ `server-chain.crt` - 服务器证书链
- ✅ `server.key` - 服务器私钥

**如果缺失，生成证书：**
```bash
cd grpc-mesh-server
make certs
```

### 2. 检查配置文件

**Go 端（calculator-client）：**
```bash
cat demos/calculator-client/config.yaml
```

确保包含：
- ✅ `app.listen_address` - HTTP 监听端口（默认 `:8080`）
- ✅ `listener.tls_cert_path` - 指向正确的证书路径
- ✅ `listener.ca_file: ""` - 空字符串（禁用 mTLS）
- ✅ `security.allowed_tokens` - 包含至少一个 token

**Rust 端（calculator-service）：**
```bash
cat demos/calculator-service/config/config.json
```

确保包含：
- ✅ `server.address` - 控制平面地址（默认 `localhost:8443`）
- ✅ `node.id` - 节点 ID（默认 `calculator-service`）
- ✅ `node.token` - 与 Go 端 `allowed_tokens` 匹配

### 3. 检查端口可用

```bash
# 确保以下端口未被占用
netstat -tuln | grep -E '(8080|8443|50051|9090)'
```

如果有占用，停止相关进程或修改配置文件中的端口。

---

## 🚀 启动服务

### 终端 1：启动 calculator-client（嵌入式 mesh server）

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
✅ Mesh server started
   - Tunnel listener: :8443
   - InvokePlane gRPC: :50051
   - Metrics: :9090
🚀 Calculator Client listening on :8080
```

**如果失败，检查：**
- ❌ `Failed to read config file` → 配置文件路径错误，运行 `pwd` 确认在正确目录
- ❌ `certPath and keyPath must be provided` → 证书路径配置错误或文件不存在
- ❌ `bind: address already in use` → 端口被占用，修改配置或停止占用进程

### 终端 2：启动 calculator-service（Rust 节点）

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
  Tunnel Server: localhost:8443
  Node ID: calculator-service
  Token: waemu_7RCx...
📡 Connecting to gRPC-Mesh control plane...
✅ Tunnel established successfully
✅ Service registered with control plane
🔒 TLS connection secured
🌐 Yamux multiplexing enabled
🚀 Calculator Service is now listening through the reverse tunnel
✨ Ready to handle requests
```

**如果失败，检查：**
- ❌ `Token not found` → 配置文件缺失或 token 未配置，参考 `calculator-service/README.md`
- ❌ `connection refused` → calculator-client 未启动或端口错误
- ❌ `certificate verify failed` → CA 证书路径错误或证书不匹配
- ❌ `Handshake failed: unauthorized` → Token 不匹配，检查两端配置

---

## ✅ 测试调用

### 终端 3：运行自动化测试

```bash
cd demos
./test-calculator.sh
```

**预期输出：**
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

### 或手动测试

**1. 检查服务健康：**
```bash
curl http://localhost:8080/health
```

预期：`{"status":"healthy","message":"pong"}`

**2. 测试加法：**
```bash
curl -X POST http://localhost:8080/calculate \
  -H 'Content-Type: application/json' \
  -d '{"operation": "add", "a": 10, "b": 5}'
```

预期：`{"result":15}`

**3. 测试除法：**
```bash
curl -X POST http://localhost:8080/calculate \
  -H 'Content-Type: application/json' \
  -d '{"operation": "divide", "a": 10, "b": 2}'
```

预期：`{"result":5}`

**4. 测试错误处理（除以零）：**
```bash
curl -X POST http://localhost:8080/calculate \
  -H 'Content-Type: application/json' \
  -d '{"operation": "divide", "a": 10, "b": 0}'
```

预期：`{"error":"failed to call service: rpc error: code = InvalidArgument desc = Division by zero"}`

**5. 查看已连接节点：**
```bash
curl http://localhost:8080/nodes
```

预期：
```json
{
  "total": 1,
  "nodes": [
    {
      "node_id": "calculator-service",
      "version": "0.1.0",
      "features": ["grpc", "calculator"],
      ...
    }
  ]
}
```

---

## 📊 监控和日志

### 查看 Prometheus 指标

```bash
curl http://localhost:9090/metrics
```

关键指标：
- `tunnel_active_sessions` - 当前活跃会话数（应该是 1）
- `invoke_requests_total` - 总调用次数
- `invoke_duration_seconds` - 调用延迟

### 查看 calculator-client 日志

日志输出在终端 1，JSON 格式：
```json
{"level":"info","ts":"2025-12-15T16:53:00.128+0800","caller":"server/server.go:107","msg":"starting grpc-mesh-server"}
{"level":"info","ts":"2025-12-15T16:53:00.128+0800","caller":"tunnel/server.go:267","msg":"TLS listener ready","addr":":8443"}
```

### 查看 calculator-service 日志

日志输出在终端 2：
```
INFO  calculator_service: Add: 10 + 5 = 15
INFO  calculator_service: Divide: 10 / 2 = 5
```

**启用 DEBUG 日志：**
```bash
export RUST_LOG=debug
cargo run --release
```

---

## 🐛 常见问题排查

### 问题 1：calculator-client 启动失败

**症状：**
```
Failed to read config file config.yaml: open config.yaml: no such file or directory
```

**解决：**
```bash
# 确认你在正确的目录
pwd  # 应该显示 .../demos/calculator-client

# 检查配置文件是否存在
ls -l config.yaml

# 如果不存在，复制示例配置
cp ../../grpc-mesh-server/config/config.yaml config.yaml
# 然后根据 calculator-client/README.md 调整配置
```

### 问题 2：calculator-service 连接失败

**症状：**
```
❌ Failed to establish tunnel: connection refused
```

**解决：**
1. 确认 calculator-client 已启动并监听 `:8443`
2. 检查防火墙设置
3. 确认配置中的地址正确（`localhost:8443` 或 `127.0.0.1:8443`）

### 问题 3：Token 认证失败

**症状：**
```
Handshake failed: unauthorized
```

**解决：**
```bash
# 1. 检查 calculator-client 的 allowed_tokens
grep allowed_tokens demos/calculator-client/config.yaml

# 2. 检查 calculator-service 的 token
cat demos/calculator-service/config/config.json | grep token

# 3. 确保两者匹配，如果不匹配，修改配置文件
# 或者重新生成 token：
cd grpc-mesh-server
make token
# 将生成的 token 同时更新到两个配置文件中
```

### 问题 4：TLS 证书错误

**症状：**
```
certificate verify failed
```

**解决：**
```bash
# 重新生成证书
cd grpc-mesh-server
make clean-certs
make certs

# 确认证书文件存在
ls -la config/tls/

# 重启两个服务
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

# 停止进程
kill <PID>

# 或修改配置文件使用其他端口
```

---

## 🔄 重启服务

如果需要重启：

1. **停止服务**：在两个终端按 `Ctrl+C`

2. **清理（可选）**：
   ```bash
   # 清理 Go 构建缓存
   cd demos/calculator-client
   go clean
   
   # 清理 Rust 构建缓存
   cd ../calculator-service
   cargo clean
   ```

3. **重新启动**：按照 "启动服务" 步骤重新启动

---

## 📚 下一步

- ✅ **阅读架构文档**：[demos/README.md](README.md)
- ✅ **了解配置选项**：[demos/CONFIG_GUIDE.md](CONFIG_GUIDE.md)
- ✅ **集成到你的项目**：[INTEGRATION_SUMMARY.md](calculator-client/INTEGRATION_SUMMARY.md)
- ✅ **生产部署**：[calculator-service/README.md](calculator-service/README.md#生产部署)

---

## ✨ 成功标志

当一切正常运行时，你应该看到：

1. ✅ calculator-client 启动并监听 `:8080`、`:8443`、`:50051`、`:9090`
2. ✅ calculator-service 成功连接并注册
3. ✅ `/health` 返回 `healthy`
4. ✅ `/nodes` 显示 1 个已连接节点
5. ✅ 所有计算操作正常返回结果
6. ✅ 错误处理正确（如除以零返回错误）

**恭喜！你的 gRPC Mesh Demo 已成功运行！** 🎉