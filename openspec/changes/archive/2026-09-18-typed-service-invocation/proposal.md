## Why

当前函数调用只经过通用 `InvokePlaneService.Invoke` 分发：方法名是运行时字符串、参数是不透明 `bytes`，proto 里定义的函数签名不参与线上调用，类型契约只靠调用双方使用同一份 proto 编解码的自觉。项目的设计意图是"函数定义由 gRPC 协议表达"，需要把类型化调用路径建立起来，与通用分发并存。

## What Changes

- 节点（Rust，tonic）在同一条隧道上可以同时提供两类服务：proto 定义的类型化 gRPC 服务，以及现有的通用 `Invoke` 字符串分发（保留作兜底）。
- server（Go）新增公共拨号入口 `Gateway.Dial(ctx, peerID)`：对已注册节点开出标准 `*grpc.ClientConn`，调用方使用自己按 proto 生成的客户端代码直接调用节点上的类型化服务，不再经过字符串分发。
- 方法上报：节点在握手 metadata 中携带 `mesh.methods` 键（逗号分隔的方法名清单，取自 `MethodRegistry.methods()`），server 侧 `SessionState.Methods()` 提供查询。
- demos：calculator-service 把 `CalculatorServer` 挂到 tonic 服务器（同时修复其 `ConnectorConfig` 初始化缺少 `insecure_skip_verify` 字段导致的编译失败 E0063）；calculator-client 新增 `/calculate-typed` 类型化调用端点，`/nodes` 展示方法清单，`config.yaml` 从旧 schema（`security.allowed_tokens` 等现行代码不读取的键）迁移到现行 schema 并以 `auth.node_tokens` 演示节点身份绑定。
- 通用 `Invoke` 路径的行为保持不变；不迁移控制面（握手/心跳仍为 JSON 帧）；不实现 gRPC reflection（server 调用未编译进二进制的 proto 属后续变更）。

## Capabilities

### New Capabilities

- `typed-invocation`: 节点在既有 TLS+yamux 隧道上提供 proto 定义的类型化 gRPC 服务；server 侧通过生成的客户端代码直接调用这些服务；类型化路径与通用 Invoke 分发并存且互不影响。
- `method-reporting`: 节点在握手时上报可调用方法清单，控制平面在会话上暴露该清单供查询与展示。

### Modified Capabilities

（无——`openspec/specs/` 当前为空，没有既有 capability 的需求变更。）

## Impact

- `grpc-mesh-server`（子模块）：`pkg/reverse/gateway.go` 新增 `Dial`；`pkg/registry/registry.go` 新增 `SessionState.Methods` 及 `mesh.methods` 解析；配套单元测试。
- `demos/calculator-service`（父仓库，Rust）：`src/main.rs` 挂载 `CalculatorServer`、握手携带方法清单、补齐 `insecure_skip_verify`。
- `demos/calculator-client`（父仓库，Go）：`main.go` 新增 `/calculate-typed`、`/nodes` 输出 `methods`；`config.yaml` 对齐现行配置 schema。
- `grpc-mesh-node`（子模块）：无 API 变更——`MethodRegistry.methods()` 已存在，仅被使用。
- 线上协议：不改变既有 wire 格式；握手 metadata 新增键向后兼容（未知 metadata 键双方原本即透传/忽略）。无 **BREAKING**。
