## Context

Go server 已是可内嵌的库（calculator-client 以 `server.New(cfg)` + `ReverseGateway().Invoke()` 进程内使用）。Python 需要对等能力。两个可行形态已评估：Python 重实现完整 server（被否决——引入第三份协议实现，且 Python 侧 gRPC-over-yamux 客户端无成熟库路径：grpcio 没有自定义拨号 API，需以 h2 手写成帧；纯 Python yamux 成熟度未知）；cgo c-shared 嵌入（选定）。

关键既有事实：`pkg/config.Load` 的 schema 即配置契约（env 覆盖、cert/key 配对校验已在此层实现）；`ReverseGateway().Invoke` 返回错误封装在 `InvokeResponse.Error` 中；`Registry().List()` 的快照含 `SessionState.Methods()`；`buildmode=c-shared` 要求导出函数位于 main 包并用 `//export` 注释。

## Goals / Non-Goals

**Goals:**

- Python 应用进程内承载完整控制平面：隧道监听、认证、会话管理、对节点的通用调用与节点查询。
- 行为与 Go server 逐比特一致（同一份代码编译产出）。
- 最小 C ABI 面，边界上只有 C 类型（句柄、int64、char*、字节缓冲）。

**Non-Goals:**

- 事件订阅回调（节点上下线）；`list_nodes()` 轮询先行，回调 API 后续立项。
- asyncio 接口；类型化 `Dial` 直调（Python 侧生成 stub 无处安放，后续与 reflection 一并评估）。
- Windows 支持（构建矩阵先 linux + macOS）。
- 修改任何 `pkg/` 现有行为。

## Decisions

### D1：cgo c-shared，而不是 Python 重实现

协议只需一份实现：控制面（TLS+yamux+长度前缀 JSON 控制帧+心跳+`auth.node_tokens` 身份绑定+staleness 清理）刚完成修复并有测试锁定，c-shared 直接继承。重实现的两个硬块（h2 手写 gRPC 客户端成帧、yamux 纯 Python 实现）均无成熟库路径，且多实现漂移在本项目有两次实锤先例。

### D2：ABI 面收敛到 8 个函数 + 2 个释放函数

```
mesh_server_new(json_config) -> handle | 0
mesh_server_start(handle) -> err
mesh_server_stop(handle) -> err            // 幂等
mesh_server_free(handle)                   // 释放句柄（内部先 stop）
mesh_invoke(handle, peer_id, method, payload, payload_len, timeout_ms, out_json) -> err
mesh_list_nodes(handle, out_json) -> err
mesh_free_string(cstr)
mesh_last_error() -> cstr                  // 便捷取最近错误
```

事件回调本期不做：Python↔Go 回调需要 GIL 状态管理，复杂度不成比例；轮询 `list_nodes` 覆盖当前场景。

### D3：配置与结果都走 JSON 字符串

配置直接复用 `pkg/config` 的 schema（Python 侧传 dict，序列化后经 `config.Load` 同路径解析——单一配置真相）；`mesh_invoke` 返回 `InvokeResponse` 的 JSON（`result`/`error.details` 字节字段 base64 编码），Python 侧解码为对象。避免在 C 边界铺结构体字段，未来 message 演进不用改 ABI。

### D4：导出层放在独立 main 包，`pkg/` 零改动

`buildmode=c-shared` 需要 main 包：新增 `cmd/meshlib/`（空 `main()` + `//export` 函数 + recover 兜底），组装逻辑复用 `pkg/server`。纯 Go 使用者与现有构建不受影响；单测以普通 Go 测试直接调用导出函数（等价于 C 调用路径）。

### D5：内存所有权归调用方，释放函数集中提供

ABI 返回的堆字符串/缓冲由 `mesh_free_string` 释放（内部记录分配来源，重复释放 no-op）。句柄内部持有 `*server.Server` 与错误 slot；`mesh_last_error` 返回线程局部的最近错误字符串。

### D6：Python 层同步 API + ctypes，零第三方运行时依赖

ctypes 在外部调用期间默认释放 GIL（满足"阻塞不独占解释器"）。`MeshServer` 持句柄，`invoke` 返回 dataclass（`success/result/error`），`DIAL_FAILED` 等按返回对象字段暴露而非异常（与 Go 侧 `Invoke` 的语义一致）；仅句柄/加载/配置错误抛 Python 异常。动态库解析顺序：环境变量 `GRPC_MESH_LIB` → 包内平台目录 → 明确报错（附构建说明）。

### D7：分发为"包 + 平台 .so"

wheel 打包当前平台的 `.so`（`bindings/python/grpc_mesh/_native/<plat>/`）；`make build-python-lib` 产出 linux amd64/arm64、macOS arm64/x86_64 四个 `.so`。源码安装回退需 Go 工具链并现场构建。Go runtime 信号问题以显式初始化规避：`mesh_server_new` 内配置 Go 不抢占 SIGINT/SIGTERM（信号归宿主），Go 文档化的 `os/signal` 约束在 c-shared 下默认即不安装处理器，验证项列入测试。

## Risks / Trade-offs

- [分发矩阵与体积（每平台一个约 10-20MB 的 .so）] → wheel 按平台拆分，源码构建回退；release 附独立 .so。
- [双运行时调试困难（Go 崩溃带走 Python 进程）] → 所有导出函数 recover 兜底；ABI 测试覆盖错误路径。
- [无事件回调，节点状态靠轮询] → 本期接受；回调作为后续独立 change。
- [cgo 构建（需要 CGO_ENABLED=1 + 平台交叉工具链）] → macOS 交叉编译受限于 cgo 工具链，矩阵中 macOS x86_64 可能需 CI runner；先保证本机平台，其余按矩阵补齐。
- [GIL 释放依赖 ctypes 默认行为] → Python 侧测试显式验证并发场景（spec 场景即测试用例）。

## Migration Plan

1. Go 侧 `cmd/meshlib` + 构建脚本 + Go 单测（server 子模块）。
2. Python 包 + ctypes 封装 + 单测（父仓库 `bindings/python/`）。
3. 端到端验收：Python 驱动真实 server + Rust calculator-service 节点。
4. 回滚：删除 `cmd/meshlib` 与 `bindings/python/` 即可，无配置/数据迁移。

## Open Questions

- Windows 的 cgo 工具链与加载约定（MinGW vs MSVC）——立项评估。
- 事件订阅回调的 ABI 形态（函数指针 + 用户上下文）——待有真实需求。
- asyncio 适配层（executor 包装即可，但 API 形态待定）。
