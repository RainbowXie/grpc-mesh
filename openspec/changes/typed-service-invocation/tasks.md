## 1. server（grpc-mesh-server 子模块）

- [x] 1.1 `pkg/registry/registry.go`：新增 `SessionState.Methods()` 与 `mesh.methods` 解析（容忍空白与空段、nil Handshake 安全），配套 `session_methods_test.go` 覆盖 spec 场景
- [x] 1.2 `pkg/reverse/gateway.go`：新增 `Gateway.Dial(ctx, peerID) (*grpc.ClientConn, error)` 委托内部 dialer，文档注明调用方负责 Close
- [x] 1.3 `go build ./... && go vet ./... && go test ./...` 全绿

## 2. 节点 demo（demos/calculator-service，父仓库）

- [x] 2.1 `ConnectorConfig` 初始化补 `insecure_skip_verify: false`，修复 E0063 编译失败
- [x] 2.2 重排 main：先建 `MethodRegistry` 并注册方法，再构建握手，`.metadata_entry("mesh.methods", methods().join(","))` 上报清单
- [x] 2.3 tonic 服务器并列挂载 `CalculatorServer::new(CalculatorService)` 与 `InvokeService`，`cargo build` 通过

## 3. 客户端 demo（demos/calculator-client，父仓库）

- [x] 3.1 `config.yaml` 迁移到现行 schema：删除 `security.`/`tunnel.`/`observability.` 等未读取键，改用 `auth.node_tokens` 绑定 `calculator-service`
- [x] 3.2 `main.go` 新增 `/calculate-typed`：`Gateway.Dial` + 生成的 `CalculatorClient` 按操作分发调用，gRPC 状态错误按原语义返回 HTTP
- [x] 3.3 `/nodes` 输出 `methods` 字段（`SessionState.Methods()`），根端点 API 文档补 `/calculate-typed`，`go build` 通过

## 4. 端到端验收（对照 spec 场景）

- [x] 4.1 环境搭建：测试 CA/证书 + 内嵌 server（calculator-client）+ calculator-service 指向同一隧道地址，节点握手成功且 `/nodes` 显示方法清单
- [x] 4.2 `/calculate`（通用 Invoke 路径）与 `/calculate-typed`（类型化路径）同输入返回一致结果（如 add 10+5=15）
- [x] 4.3 类型化错误语义：`/calculate-typed` 除零返回 `InvalidArgument "Division by zero"`；对未注册 peerID 拨号返回错误
- [x] 4.4 修改前证据留档：HEAD 上 calculator-service 的 E0063 编译失败输出、类型化服务无挂载的代码事实

## 5. 收尾

- [ ] 5.1 分仓提交：server 子模块一个 commit；父仓库 demos + 本 change 一个 commit；推送并更新父仓子模块指针
- [ ] 5.2 `openspec validate typed-service-invocation` 通过，`openspec archive` 待验收确认后执行
