# Changelog

## [1.0.0] - 2025-12-15

### 🎉 项目完成

所有核心功能已实现并测试通过，项目达到生产可用状态。

### ✅ 已实现功能

- TLS 1.3 加密隧道
- Yamux 多路复用
- 控制流协议（握手、心跳）
- 会话管理和自动清理
- 反向拨号机制
- InvokePlane gRPC 服务
- MethodRegistry 动态路由
- 自动心跳（15秒间隔）
- Token 认证
- Prometheus 指标
- Calculator Demo 应用

### 🔧 关键修复

1. **握手协议** - Go server 直接解析 Handshake JSON
2. **服务注册** - InvokePlane 正确注册到 gRPC server
3. **方法路由** - Calculator service 使用 MethodRegistry
4. **gRPC 拨号** - 使用 DialContext 确保连接建立
5. **TLS 证书** - 自动生成 server-chain.crt
6. **配置映射** - 修复 YAML 字段名称

### 📊 测试结果

- ✅ 四则运算全部通过
- ✅ 健康检查正常
- ✅ 节点注册和心跳稳定
- ✅ 端到端通信验证

### 📚 文档更新

- 合并所有文档到 README.md 和 ROADMAP.md
- 删除重复和过时文档
- 保留核心 API 文档
- 更新快速开始指南

### 🗂️ 文档结构

**根目录:**
- README.md - 完整项目指南
- ROADMAP.md - 开发路线图和状态
- CHANGELOG.md - 本文件

**API 文档:**
- grpc-mesh-server/docs/API.md
- grpc-mesh-node/docs/API.md

**组件文档:**
- grpc-mesh-server/README.md
- grpc-mesh-node/README.md
- demos/README.md
- demos/calculator-client/README.md
- demos/calculator-service/README.md

### 🚀 下一步

建议继续完善：
- 添加更多单元测试
- 实现负载均衡
- 添加 WebSocket 支持
- 创建 Helm charts
- 编写性能基准测试

---

**项目状态:** 生产就绪 ✅  
**文档状态:** 完整 ✅  
**测试状态:** 通过 ✅
