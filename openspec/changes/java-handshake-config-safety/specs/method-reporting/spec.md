# Spec Delta

## MODIFIED Requirements

### Requirement: 节点在握手中上报方法清单

节点 SHALL 在建立隧道的握手 metadata 中以 `mesh.methods` 键上报其可调用方法名清单：取自 `MethodRegistry.methods()`，以英文逗号连接。清单为空或键缺失时视为未上报。`mesh.methods` SHALL 由框架独占生成；用户自定义 metadata 试图设置该 key 时 SHALL 被明确拒绝，不得覆盖、合并或伪造实际可调用方法清单。

#### Scenario: 握手携带方法清单

- **WHEN** 节点的 `MethodRegistry` 已注册 `calculator.v1.Calculator/Add` 与 `calculator.v1.Calculator/Health` 并发起握手
- **THEN** 握手 metadata 的 `mesh.methods` 值包含这两个方法名

#### Scenario: 未注册任何方法

- **WHEN** 节点的 `MethodRegistry` 为空并发起握手
- **THEN** 握手正常完成，`mesh.methods` 键为空值或缺失，连接不被拒绝

#### Scenario: 用户 metadata 不能伪造方法清单

- **WHEN** 宿主配置 metadata `mesh.methods=fake.Service/NotRegistered`
- **THEN** 节点以配置错误拒绝该输入，不向服务端通告虚假的方法

#### Scenario: 通用 metadata 不干扰实际方法上报

- **WHEN** 宿主配置合法业务 metadata，并在启动前注册、替换或注销方法
- **THEN** 握手同时包含合法 metadata 和最终实际可调用的方法清单，不包含已注销的方法
