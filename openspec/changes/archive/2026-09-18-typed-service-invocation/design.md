## Context

数据面现状（已核实）：调用链是 `Gateway.Invoke` → `Dialer.DialPeer`（yamux 流上 `grpc.DialContext` + `WithContextDialer`）→ `InvokePlaneService.Invoke(method: string, payload: bytes)` → 节点 tonic 服务器里 `InvokeService` 按字符串查 `MethodRegistry`。传输层是标准 gRPC（HTTP/2 + protobuf），但函数签名不在线上：`grpc_mesh.proto` 中唯一的调用形状是通用 `Invoke`。

基础设施事实：`Dialer.DialPeer` 与节点侧 `YamuxIncoming` 对"隧道上跑什么 gRPC 服务"完全不感知；`MethodRegistry.methods()` 已存在（grpc-mesh-node `src/lib.rs`）；节点握手 metadata（`Handshake.metadata`）与 server 侧 `Handshake.Metadata` 字段名一致、双向可透传。demos 中类型化代码两侧各写了一半但都未接线（节点的 `CalculatorService` 实现从未 `add_service`，客户端的生成 stub 从未调用），且 calculator-service 的 `ConnectorConfig` 初始化缺少 `insecure_skip_verify` 字段，当前编译失败（E0063）。

## Goals / Non-Goals

**Goals:**

- 让"函数定义由 proto 表达"成为线上可用的调用路径：节点挂载类型化服务，server 用生成的客户端代码直调。
- 方法清单上报，使控制平面能回答"这个节点会什么"。
- 与通用 `Invoke` 分发并存，互不干扰，后者行为不变。

**Non-Goals:**

- 不实现 gRPC reflection（server 调用未编译进二进制的 proto 属后续变更）。
- 不迁移控制面协议（握手/心跳仍为 JSON 帧；`ControlPlaneService` 接线不在本次范围）。
- 不实现方法清单的动态增量上报（`UpdateMethods` 控制消息）——本次只做握手时一次性上报。
- 不改 `InvokeRequest`/`InvokeResponse` 的 wire 格式。

## Decisions

### D1：类型化服务与通用分发挂同一个 tonic 服务器，不分开连接

节点侧 `Server::builder().add_service(CalculatorServer).add_service(InvokeService)`。tonic 按路径（`/calculator.v1.Calculator/Add` vs `/grpc_mesh.rpc.v1.InvokePlaneService/Invoke`）路由，两者天然互不冲突。
备选：为类型化服务开独立隧道/独立端口——需要第二条连接和生命周期管理，收益为零，不采用。

### D2：server 侧暴露 `Gateway.Dial(ctx, peerID) (*grpc.ClientConn, error)`，而不是"每个业务一个 Gateway 方法"

调用方拿到标准 `*grpc.ClientConn` 后用自己生成的 stub 调用，Gateway 不需要知道任何具体业务 proto。这让 server 仓库与业务 proto 解耦——业务 proto（如 calculator.v1）只存在于调用方。
备选：在 Gateway 上为每个服务加包装方法——每加一个服务都要改 server 仓库，违背 mesh 的通用性，不采用。

### D3：方法清单走握手 metadata（键 `mesh.methods`，逗号分隔），不新增控制消息

握手 metadata 字段两侧已存在且透传，零协议新增；server 侧解析为 `SessionState.Methods()`。逗号分隔的纯字符串清单足以支撑展示与路由判断。
备选：接通 proto 里已定义的 `UpdateMethods`——需要新增控制流收发通道（节点侧控制流目前由心跳任务独占写），且本次没有动态增删方法的场景，留作后续；不采用。

### D4：demo 作为类型的落地点与验收载体

calculator-service 补 `insecure_skip_verify: false`（修复 E0063）、挂载 `CalculatorServer`、握手携带 `mesh.methods`；calculator-client 新增 `/calculate-typed`（`Gateway.Dial` + 生成 stub）与 `/calculate`（通用 Invoke）并存的端点，`/nodes` 输出 `methods`。两条路径同场景对照即验收演示。calculator-client 的 `config.yaml` 同时迁移到现行配置 schema（旧文件中 `security.allowed_tokens`、`tunnel.*` 等键现行 `pkg/config` 不读取，授权实际未生效），并用 `auth.node_tokens` 绑定 demo 节点身份。

### D5：每次 `Gateway.Dial` 开一条新 yamux 流 + 新 `ClientConn`

与现有 `Gateway.Invoke` 的资源模型一致（一调用一连接）。不在本次引入连接池——demo 与当前负载下无必要；若后续成为瓶颈，可在 `Dial` 之上按 peerID 池化，API 不变。

## Risks / Trade-offs

- [每请求一连接，流与 HTTP/2 握手有固定开销] → 与现状 `Invoke` 路径相同，不引入回退；池化留作后续优化点（D5）。
- [`mesh.methods` 是逗号分隔字符串，方法名含逗号会破坏解析] → 方法名来自 proto 的 package/Service/Method，合法字符集不含逗号；解析侧容忍空白与空段（见 spec 场景）。
- [握手时上报的清单与节点后续实际注册的方法可能漂移（本次不做动态更新）] → 清单语义定位为"连接时快照"；动态增量上报（`UpdateMethods`）为后续变更的既定方向。
- [调用方 proto 与节点 proto 版本不一致时，protobuf 未知字段按 proto3 语义忽略，可能产生静默兼容而非报错] → 与 gRPC 生态通用行为一致；reflection/描述符校验属后续。

## Migration Plan

1. server 子模块：`Gateway.Dial` + `SessionState.Methods`（含单测），独立提交。
2. 父仓库 demos：calculator-service、calculator-client 变更。
3. 部署顺序无约束：server 先升级或节点先升级均可——新能力全部向后兼容（未知 metadata 键被忽略；节点不挂载类型化服务时 `Dial` 出的连接调用返回 `Unimplemented`，与现状一致）。
4. 回滚：revert 两个提交即可，无数据或配置迁移。

## Open Questions

- 方法清单是否需要随 `MethodRegistry` 动态变化而更新（触发 `UpdateMethods` 控制消息）——待真实场景出现再立项。
- gRPC reflection 的引入时机（让 server 免编译调用新服务）——独立变更评估。
