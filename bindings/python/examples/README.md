# grpc-mesh Python Binding 示例文档

本文的每个代码段都标注了运行前提；第一个示例可以直接跑通，输出为实跑记录。所有示例共用一个心智模型：

```
你的 Python 进程                         Rust 节点（demos/calculator-service）
┌──────────────────────────┐
│ MeshServer（内嵌控制平面） │
│  ├─ TLS 监听 :8443        │◄──────── 节点主动外连，用 ca.crt 验证你的证书
│  ├─ 会话注册/心跳/清理     │
│  └─ invoke / list_nodes   │───────── 按 mesh.methods 里上报的方法名调用
└──────────────────────────┘
```

节点不需要公网入口；它主动连你的 Python 进程，并验证 `cert_file`/`key_file` 对应的证书（CA 是 `grpc-mesh-server/config/tls/ca.crt`）。这就是为什么监听器必须配证书——留空的"开发证书"节点无法验证，会一直连不上。

## 前置条件

```bash
# 1. 构建 c-shared 库并放入包内（一次性；或设 GRPC_MESH_LIB 指向现成 .so）
cd grpc-mesh-server && make build-meshlib
cp libmesh.so ../bindings/python/grpc_mesh/_native/linux-x86_64/   # 按平台

# 2. 编译 Rust demo 节点（一次性）
cd demos/calculator-service && cargo build
```

## 示例 1：30 秒跑通（examples/calculator.py）

两个终端：

```bash
# 终端 1 —— Rust 计算器节点（读默认 config/config.json：连 127.0.0.1:8443，
#           用仓库 CA 验证证书，node id 与 token 均来自该配置）
cd demos/calculator-service
cargo run

# 终端 2 —— Python 侧（不需要 pip install，PYTHONPATH 指向包目录即可）
cd bindings/python
PYTHONPATH=. python3 examples/calculator.py
```

实跑输出：

```
mesh control plane on 127.0.0.1:8443, waiting for node ...
node calculator-service: methods=['calculator.v1.Calculator/Add', 'calculator.v1.Calculator/Divide', 'calculator.v1.Calculator/Health', 'calculator.v1.Calculator/Multiply', 'calculator.v1.Calculator/Subtract']
10 + 5 = 15.0
mesh stopped cleanly
```

`methods` 列表来自节点握手时上报的方法清单（`mesh.methods`），不是猜测——`list_nodes()` 返回的就是节点自己声明的名字，调用时照抄即可。

## 示例 2：invoke 的三种结果

```python
from grpc_mesh import MeshServer, MeshError

mesh = MeshServer(config)
mesh.start()
try:
    # ① 成功：success=True，result 是节点返回的原始字节
    r = mesh.invoke("calculator-service", "calculator.v1.Calculator/Add",
                    payload, timeout_ms=5000)
    if r.success:
        use(r.result)

    # ② 业务失败：节点不存在/方法没注册等，不抛异常，看 r.error
    r = mesh.invoke("no-such-node", "a.B/C", b"", timeout_ms=1000)
    assert not r.success
    print(r.error)          # {'code': 'DIAL_FAILED', 'message': 'failed to dial peer: ...'}

    # ③ 基础设施失败：库/句柄层面的错误，抛 MeshError
    #    （例如句柄已 free、库内部错误；正常业务路径几乎遇不到）
finally:
    mesh.stop()
    mesh.close()
```

三分法与 Go 侧 `ReverseGateway().Invoke` 的语义一致：`error` 装在返回对象里，异常只留给"这次调用根本没发生"的情况。

## 示例 3：节点发现

```python
for node in mesh.list_nodes():
    print(node["node_id"], node["version"], node["methods"])
    # node["connected_at"] / node["last_heartbeat"] 为 RFC3339 时间戳
```

轮询这个列表即可观察节点上下线（当前版本没有事件推送回调；清单是握手时快照，节点重连后刷新）。

## 示例 4：参数怎么编码——两种写法

`invoke` 的 `payload` 是不透明字节，编码由你和节点约定。calculator demo 的 proto 是 `CalcRequest{double a=1; double b=2;}`。

**推荐（真实项目）**：用 `protobuf` 包 + 按 proto 生成的代码：

```python
# pip install protobuf；protoc --python_out=. calculator.proto 之后：
req = calculator_pb2.CalcRequest(a=10, b=5)
payload = req.SerializeToString()

resp = calculator_pb2.CalcResponse()
resp.ParseFromString(result.result)
```

**零依赖（示例脚本采用）**：知道 wire format 时手编。下面两种写法产物逐字节相同（已验证：`090000000000002440110000000000001440`）：

```python
import struct
payload = b"\x09" + struct.pack("<d", 10) + b"\x11" + struct.pack("<d", 5)
```

`0x09` = 字段 1、wire type 1（64-bit double）；`0x11` = 字段 2。仅供理解协议，别在业务里这么写。

## 示例 5：多线程

`invoke` 等阻塞调用期间释放 GIL（ctypes `CDLL` 行为），其他线程照常跑 Python：

```python
import threading

results = []
def worker(a, b):
    r = mesh.invoke("calculator-service", "calculator.v1.Calculator/Add",
                    encode(a, b), timeout_ms=5000)
    results.append(decode(r.result))

threads = [threading.Thread(target=worker, args=(i, i)) for i in range(8)]
[t.start() for t in threads]; [t.join() for t in threads]
```

端到端测试里做过极端验证：节点被 `SIGSTOP` 冻结、invoke 挂满 2.5 秒超时期间，一个纯 Python 计数线程执行了约 1790 万次循环（`e2e/run_e2e.sh` 的 [7/10] 阶段）。

## 示例 6：生命周期

```python
# 首选：上下文管理器，退出即停止并释放（stop 幂等）
with MeshServer(config) as mesh:
    ...

# 手动模式：start/stop 可重复，close 释放句柄（内部会先 stop）
mesh = MeshServer(config)
mesh.start()
mesh.stop()
mesh.stop()      # 第二次是 no-op
mesh.close()
```

## 常见错误对照

| 现象 | 原因与处理 |
|---|---|
| `MeshLibraryNotFound` | 没找到 `libmesh.so`。构建放入 `_native/<platform>/`，或 `export GRPC_MESH_LIB=/path/to/libmesh.so` |
| `MeshError: invalid config: ... must be configured together` | 只配了 `cert_file` 没配 `key_file`（或反之） |
| 节点一直连不上、server 日志无握手 | 监听器没配证书（节点无法验证），或证书不是 `config/tls/ca.crt` 签发的 |
| `r.error['code'] == 'DIAL_FAILED'` | 目标 `peer_id` 未连接；先 `list_nodes()` 核对 |
| `r.error` 里 `MethodNotFound` | 方法名和节点上报的不一致；照抄 `list_nodes()` 里的 `methods` |

更多安装与打包细节见[上级 README](../README.md)。
