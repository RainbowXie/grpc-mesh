# grpc-mesh Python Binding

在 Python 进程内承载完整的 gRPC-Mesh 控制平面：TLS 隧道监听、节点认证（`auth.node_tokens` 身份绑定）、会话管理、对节点的方法调用与节点查询。底层是 Go server 编译的 c-shared 动态库（`libmesh.so`），协议行为与 Go server 完全一致。

## 安装

当前以源码树方式使用；动态库在**运行时**定位，不随包分发（多平台
wheel 与构建矩阵是后续变更，尚未实现）。

```bash
# 1. 构建本机动态库（需要 Go ≥ 1.25、CGO 可用）
cd ../../grpc-mesh-server
make build-meshlib        # 产出 libmesh.so 与 libmesh.h

# 2. 二选一：放入包目录（按平台命名），或用环境变量指定
cp libmesh.so ../bindings/python/grpc_mesh/_native/linux-x86_64/
# 或：export GRPC_MESH_LIB=/path/to/libmesh.so

# 3. 安装 Python 包（纯 Python；也可不装，直接 PYTHONPATH=. 使用）
pip install .
```

库缺失时导入/实例化会抛出 `MeshLibraryNotFound`，错误信息包含上述构建指引。

## 快速开始

```python
from grpc_mesh import MeshServer

config = {
    "server": {"grpc_address": "127.0.0.1:50051", "metrics_address": "127.0.0.1:9090"},
    "listener": {
        "address": "127.0.0.1:8443",
        "cert_file": "certs/server-chain.crt",   # 省略则生成一次性开发证书
        "key_file": "certs/server.key",
    },
    "auth": {
        "enabled": True,
        "node_tokens": {"calculator-service": "waemu_..."},  # token 绑定 node_id
    },
}

with MeshServer(config) as mesh:
    nodes = mesh.list_nodes()          # [{"node_id": ..., "methods": [...], ...}]
    result = mesh.invoke(
        "calculator-service",
        "calculator.v1.Calculator/Add",
        calc_request_bytes,            # protobuf 编码的参数
        timeout_ms=5000,
    )
    if result.success:
        calc_response_bytes = result.result
```

完整可运行示例与分主题示例文档见 [examples/](examples/README.md)（含参数编码、错误三分法、多线程与常见错误对照）。

## API 摘要

| 成员 | 说明 |
|---|---|
| `MeshServer(config: dict)` | 按服务端 `pkg/config` schema 构造；配置非法抛 `MeshError` |
| `with MeshServer(cfg) as mesh:` | 进入即启动，退出即停止（幂等） |
| `mesh.invoke(peer_id, method, payload: bytes, timeout_ms)` | 通用 Invoke 分发调用，返回 `InvokeResult`；基础设施故障抛 `MeshError`，节点/业务失败体现在 `result.success=False` 与 `result.error`（如 `{"code": "DIAL_FAILED"}`） |
| `mesh.list_nodes()` | 已连接节点列表（含握手上报的 `methods` 清单） |
| `InvokeResult` | `peer_id / method / success / result(bytes) / error / correlation_id / elapsed_ms` |

## 线程语义

阻塞调用（`invoke`）在等待期间释放 GIL（ctypes `CDLL` 行为），其他 Python 线程可正常执行。验收场景：节点被 `SIGSTOP` 后 2.5s 超时的 `invoke` 期间，计数线程执行了千万级循环（见 `openspec/changes/python-server-binding`）。

## 平台支持

`make build-meshlib` 构建当前平台（`.so` 放入 `_native/<sys.platform>-<machine>/`）。跨平台分发（linux x86_64/aarch64、macOS arm64/x86_64 的构建矩阵与带平台 tag 的 wheel）尚未实现——在非构建平台上使用需要在该平台自行构建或通过 `GRPC_MESH_LIB` 提供库。Windows 未支持。

## 故障排查

- **`MeshLibraryNotFound`**：按错误信息构建/放置 `libmesh.so`，或设 `GRPC_MESH_LIB`。
- **`MeshError: invalid config: ... must be configured together`**：`cert_file`/`key_file` 必须成对配置。
- **`invoke` 返回 `error.code == "DIAL_FAILED"`**：目标 `peer_id` 未连接；先用 `list_nodes()` 确认。
- **构建 `libmesh.so` 失败**：确认 `CGO_ENABLED=1` 且本机有 C 编译器（`make build-meshlib` 已内置）。

## 测试

```bash
python3 -m unittest discover -s tests   # 无库时跳过依赖库的用例
```
