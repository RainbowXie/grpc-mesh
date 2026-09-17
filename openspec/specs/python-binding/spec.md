# python-binding Specification

## Purpose

定义 Python 应用通过 c-shared 动态库在进程内承载 gRPC-Mesh 控制平面的加载、生命周期、调用、节点查询、异常和线程语义。

## Requirements

### Requirement: Python 包可加载动态库并管理服务器生命周期

Python 包 `grpc-mesh` SHALL 在创建 `MeshServer` 实例时按当前平台加载对应的 c-shared 动态库（环境变量 `GRPC_MESH_LIB` 或包内 `_native/<platform>/` 用户放置的库；导入模块本身不触发加载）。`MeshServer(config: dict)` SHALL 接受与 `pkg/config` schema 一致的配置字典（序列化为 JSON 后传入 C ABI），支持作为上下文管理器使用（进入时启动、退出时停止）。

#### Scenario: 上下文管理器启动与停止

- **WHEN** 使用 `with MeshServer(cfg) as mesh:` 且配置合法
- **THEN** 进入时服务器启动，`with` 块退出后服务器停止且进程正常继续

#### Scenario: 动态库缺失给出可操作错误

- **WHEN** 当前平台没有对应的 `.so` 且未通过环境变量指定路径
- **THEN** 导入或实例化时抛出说明获取/构建方式的异常，而非底层 ctypes 的原始报错

### Requirement: Python 调用与查询语义

`MeshServer.invoke(peer_id, method, payload: bytes, timeout_ms)` SHALL 返回 `InvokeResponse` 对应的 Python 对象（含 `success`、`result: bytes`、`error`）；调用失败以异常暴露基础设施错误，业务失败体现在返回对象的 `error` 字段。`MeshServer.list_nodes()` SHALL 返回节点字典列表（含 `methods`）。

#### Scenario: 调用在线节点得到字节结果

- **WHEN** 节点在线，调用 `invoke("calculator-service", "calculator.v1.Calculator/Add", proto_bytes, 5000)`
- **THEN** 返回对象 `success` 为真，`result` 为可 `proto.Unmarshal` 的字节

#### Scenario: 节点不存在暴露为异常

- **WHEN** 对未注册节点调用 `invoke`
- **THEN** 返回对象 `success` 为假且 `error.code == "DIAL_FAILED"`（进程不崩溃）

#### Scenario: 查询方法清单

- **WHEN** 已上报方法清单的节点在线，调用 `list_nodes()`
- **THEN** 结果中该节点的 `methods` 为方法名字符串列表

### Requirement: 阻塞调用不独占解释器

`invoke` 等阻塞调用期间 SHALL 释放 GIL（ctypes 外部调用默认行为），其他 Python 线程可继续执行。

#### Scenario: 阻塞调用期间其他线程运行

- **WHEN** 线程 A 正在等待一个长耗时 `invoke` 返回，线程 B 执行纯 Python 计算
- **THEN** 线程 B 正常推进，不被线程 A 阻塞
