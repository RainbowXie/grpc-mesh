# method-reporting Specification

## Purpose

定义节点通过握手上报可调用方法清单，以及控制平面从会话状态安全读取该清单的行为。

## Requirements

### Requirement: 节点在握手中上报方法清单

节点 SHALL 在建立隧道的握手 metadata 中以 `mesh.methods` 键上报其可调用方法名清单：取自 `MethodRegistry.methods()`，以英文逗号连接。清单为空或键缺失时视为未上报。

#### Scenario: 握手携带方法清单

- **WHEN** 节点的 `MethodRegistry` 已注册 `calculator.v1.Calculator/Add` 与 `calculator.v1.Calculator/Health` 并发起握手
- **THEN** 握手 metadata 的 `mesh.methods` 值包含这两个方法名

#### Scenario: 未注册任何方法

- **WHEN** 节点的 `MethodRegistry` 为空并发起握手
- **THEN** 握手正常完成，`mesh.methods` 键为空值或缺失，连接不被拒绝

### Requirement: 会话侧暴露方法清单

控制平面 SHALL 在会话状态上提供 `SessionState.Methods()`，返回该节点握手时上报的方法名列表（去除首尾空白、忽略空段）；未上报时返回空列表。

#### Scenario: 查询已上报节点的方法

- **WHEN** 节点以 `mesh.methods: " calculator.v1.Calculator/Add , calculator.v1.Calculator/Health ,"` 完成握手，控制平面查询该会话的 `Methods()`
- **THEN** 返回 `["calculator.v1.Calculator/Add", "calculator.v1.Calculator/Health"]`

#### Scenario: 查询未上报节点的方法

- **WHEN** 会话对应的握手不包含 `mesh.methods` 键（或节点 `Handshake` 为 nil）
- **THEN** `Methods()` 返回空列表，不报错
