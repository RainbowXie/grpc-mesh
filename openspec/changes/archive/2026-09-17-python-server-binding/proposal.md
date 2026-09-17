## Why

Python 应用目前无法以进程内方式承载 gRPC-Mesh 控制平面——Go 应用可以像 calculator-client 那样 `server.New()` 内嵌启动并经 `ReverseGateway` 调用节点，Python 侧没有对等能力。在 cgo 共享库嵌入与 Python 重实现两个可行形态中选前者：控制面协议（TLS+yamux+JSON 控制帧+心跳+身份绑定）只需要一份实现，行为与 Go server 完全一致；重实现会引入第三个协议实现，本项目已两次因多实现对协议的各自理解出错（握手字段漂移、CA 材料编码错配），且 Python 侧"在 yamux 流上跑 gRPC 客户端"没有成熟库路径（grpcio 无自定义拨号 API，需 h2 手写成帧）。

## What Changes

- grpc-mesh-server 新增 c-shared 构建目标：一个最小 C ABI（`mesh_*` 系列函数），覆盖生命周期（创建/启动/停止）、通用调用（invoke）、节点查询（list_nodes）。
- 新增 Python 包 `grpc-mesh`（父仓库 `bindings/python/`）：ctypes 加载平台 `.so`，提供 `MeshServer` 上下文管理器、`invoke(peer_id, method, payload)`、`list_nodes()`，错误以 Python 异常暴露。
- 构建产物：`make build-meshlib` 本机单平台 `.so`；运行时经 `GRPC_MESH_LIB` 或 `_native/<platform>/` 定位，不随 Python 包分发（多平台构建矩阵与平台 wheel 为后续变更）。Windows 未支持。
- 新增 Python 最小示例（对应 calculator-client 的内嵌用法）与文档。
- 不改变任何现有 Go 包的行为；C ABI 以独立构建目标导出，纯 Go 使用者不受影响。
- 非 goal：不做事件订阅回调（节点上下线以 `list_nodes()` 轮询，回调 API 后续另行立项）；不做 asyncio 接口（同步 API 先行）；不做 server 公网 gRPC 面的新增 RPC。

## Capabilities

### New Capabilities

- `server-c-abi`: Go server 以 c-shared 动态库导出的 C ABI 契约——生命周期、调用、节点查询、错误与内存所有权规则。
- `python-binding`: Python 包对 C ABI 的封装契约——安装加载、`MeshServer` 生命周期、调用与查询语义、异常映射、阻塞调用期间的线程行为。

### Modified Capabilities

（无——`openspec/specs/` 为空。）

## Impact

- `grpc-mesh-server`（子模块）：新增 C ABI 导出层（独立目录 + 构建脚本），不触碰 `pkg/` 现有行为；新增 Go 侧 ABI 单元测试。
- 父仓库：新增 `bindings/python/`（包源码、打包配置、示例、测试）；构建脚本与文档。
- 依赖：Python 包仅依赖标准库 ctypes（调用侧零第三方依赖）；构建 `.so` 需 Go 工具链。
- 分发：源码树 + `make build-meshlib`（Go ≥ 1.25）；wheel 仅含纯 Python 包，不捆绑动态库。
