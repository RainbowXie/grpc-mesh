# grpc-mesh Python Binding

在 Python 进程内承载完整的 gRPC-Mesh 控制平面：TLS 隧道监听、节点认证（`auth.node_tokens` 身份绑定）、会话管理、对节点的方法调用与节点查询。底层是 Go server 编译的 c-shared 动态库（`libmesh.so`），协议行为与 Go server 完全一致。

## 安装

Python 包是纯 Python；动态库在**运行时**定位，不随包分发。三种方式按省事程度排列（均已实测）：

**方式一：直接从 GitHub 安装 + 下载预构建库（推荐，无需 Go 工具链）**

```bash
# 1. 安装包（pip >= 21，monorepo 子目录；也可装 Release 里的 wheel）
pip install "git+https://github.com/RainbowXie/grpc-mesh.git@python-v0.1.0a2#subdirectory=bindings/python"

# 2. 下载预构建动态库（linux x86_64）并校验完整性，运行时指向它
curl --fail --location --output libmesh-linux-x86_64.so \
  https://github.com/RainbowXie/grpc-mesh/releases/download/python-v0.1.0a2/libmesh-linux-x86_64.so
curl --fail --location --output SHA256SUMS \
  https://github.com/RainbowXie/grpc-mesh/releases/download/python-v0.1.0a2/SHA256SUMS
grep libmesh-linux-x86_64.so SHA256SUMS | sha256sum -c -
export GRPC_MESH_LIB=$PWD/libmesh-linux-x86_64.so
```

**方式二：源码树使用（开发/贡献）**

```bash
git clone --recursive https://github.com/RainbowXie/grpc-mesh.git
cd grpc-mesh/bindings/python
PYTHONPATH=. python3 examples/calculator.py   # 不安装直接用
```

**方式三：自行构建动态库（其他平台，或不想用预构建产物）**

```bash
cd grpc-mesh-server
make build-meshlib        # 产出 libmesh.so 与 libmesh.h；需要 Go ≥ 1.25、CGO
export GRPC_MESH_LIB=/path/to/libmesh.so
# 或放入包目录：grpc_mesh/_native/<platform>/libmesh.so
```

库缺失时实例化会抛出 `MeshLibraryNotFound`，错误信息包含构建指引。

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

预构建 `.so` 目前只有 linux x86_64（见 [Release 资产](https://github.com/RainbowXie/grpc-mesh/releases/tag/python-v0.1.0a2)，内含版本与安装说明）。其他平台（linux aarch64、macOS arm64/x86_64）用 `make build-meshlib` 自行构建（`.so` 放入 `_native/<sys.platform>-<machine>/` 或经 `GRPC_MESH_LIB` 指定）；平台 tag 的 wheel 分发是后续变更。Windows 未支持。

## 故障排查

- **`MeshLibraryNotFound`**：按错误信息构建/放置 `libmesh.so`，或设 `GRPC_MESH_LIB`。
- **`MeshError: invalid config: ... must be configured together`**：`cert_file`/`key_file` 必须成对配置。
- **`invoke` 返回 `error.code == "DIAL_FAILED"`**：目标 `peer_id` 未连接；先用 `list_nodes()` 确认。
- **构建 `libmesh.so` 失败**：确认 `CGO_ENABLED=1` 且本机有 C 编译器（`make build-meshlib` 已内置）。

## 测试

```bash
python3 -m unittest discover -s tests   # 无库时跳过依赖库的用例
```
