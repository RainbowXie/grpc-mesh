# server-c-abi Specification

## Purpose

定义 grpc-mesh-server 作为 c-shared 动态库暴露的稳定 C ABI，包括服务器生命周期、通用调用、节点查询、字符串所有权和并发规则。

## Requirements

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

### Requirement: C ABI 的字符串句柄与线程规则

`mesh_invoke` 与 `mesh_list_nodes` SHALL 返回字符串句柄（0 表示失败）；内容经 `mesh_str_data` 以借用指针读出（调用方不得 free，句柄释放前有效），经 `mesh_str_release` 释放。句柄身份 SHALL 为单调 id 而非内存地址：同一句柄重复释放 SHALL 为 no-op，且**地址复用下的陈旧释放不得影响后续分配**。`mesh_invoke` 与 `mesh_list_nodes` SHALL 可被多线程并发调用。

#### Scenario: 释放与重复释放

- **WHEN** 调用方读取 `mesh_list_nodes` 返回句柄的内容后调用 `mesh_str_release`，随后再次对同一句柄调用 `mesh_str_release`
- **THEN** 第二次释放为 no-op；该句柄再经 `mesh_str_data` 读取得到 NULL，进程不崩溃

#### Scenario: 地址复用下的陈旧释放

- **WHEN** 句柄 A 已释放，新的分配 B 复用了 A 的底层内存地址，调用方此时再次释放 A
- **THEN** B 的内容不受影响且仍可正常读取（释放按 id 生效，与地址无关）

#### Scenario: 并发调用

- **WHEN** 两个线程同时对同一句柄调用 `mesh_invoke`
- **THEN** 两次调用各自独立返回，不产生数据竞争或崩溃
