## ADDED Requirements

### Requirement: C ABI 提供服务器生命周期管理

C ABI SHALL 提供创建、启动、停止服务器的函数：`mesh_server_new`（接受符合 `pkg/config` schema 的 JSON 配置字符串，返回服务器句柄）、`mesh_server_start`、`mesh_server_stop`。启动失败（如端口占用）SHALL 通过错误码与错误字符串返回，不跨边界 panic（所有导出函数 MUST 以 recover 兜底）。`mesh_server_stop` SHALL 幂等。

#### Scenario: 从 JSON 配置创建并启动

- **WHEN** 调用方以包含 `listener`、`server`、`auth` 键的合法 JSON 配置调用 `mesh_server_new`，随后调用 `mesh_server_start`
- **THEN** 返回成功错误码，隧道监听与 gRPC 服务按配置启动

#### Scenario: 非法配置在创建时失败

- **WHEN** 传入的 JSON 缺失必填字段或不符合 schema（例如只配置了 cert_file 没配 key_file）
- **THEN** `mesh_server_new` 返回空句柄与描述性错误字符串

#### Scenario: 启动失败不崩溃宿主进程

- **WHEN** 配置的监听端口已被占用，调用 `mesh_server_start`
- **THEN** 返回错误码与 "address already in use" 类信息，宿主进程继续运行

### Requirement: C ABI 提供通用调用与节点查询

C ABI SHALL 提供 `mesh_invoke`（按 peer_id + 方法名 + payload 字节发起通用 Invoke 调用，超时以毫秒传入，返回 `InvokeResponse` 的 JSON 序列化）与 `mesh_list_nodes`（返回节点数组 JSON：node_id、version、methods、connected_at、last_heartbeat）。

#### Scenario: 调用在线节点

- **WHEN** 节点 `calculator-service` 在线且注册了 `calculator.v1.Calculator/Add`，调用方以该方法名与 protobuf 字节调用 `mesh_invoke`
- **THEN** 返回的 JSON 中 `success` 为真且 `result` 为节点回包字节（base64 编码）

#### Scenario: 调用未注册节点

- **WHEN** 对不存在的 peer_id 调用 `mesh_invoke`
- **THEN** 返回的 JSON 中 `success` 为假，`error.code` 为 `DIAL_FAILED`

#### Scenario: 查询节点列表

- **WHEN** 一个已上报方法清单的节点在线，调用 `mesh_list_nodes`
- **THEN** 返回的 JSON 数组含该节点的 `methods` 列表

### Requirement: C ABI 的内存与线程规则

字符串与缓冲区跨边界的所有权 SHALL 归调用方：ABI 提供释放函数（如 `mesh_free_string`/`mesh_free_buffer`），调用方用其释放 ABI 返回的堆内存。`mesh_invoke` 与 `mesh_list_nodes` SHALL 可被多线程并发调用。

#### Scenario: 释放返回的字符串

- **WHEN** 调用方对 `mesh_list_nodes` 返回的字符串句柄调用 ABI 提供的释放函数
- **THEN** 内存被释放且进程无泄漏崩溃；重复释放为 no-op 或可检测错误，不导致崩溃

#### Scenario: 并发调用

- **WHEN** 两个线程同时对同一句柄调用 `mesh_invoke`
- **THEN** 两次调用各自独立返回，不产生数据竞争或崩溃
