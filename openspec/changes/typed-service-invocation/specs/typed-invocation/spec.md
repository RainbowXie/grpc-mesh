## ADDED Requirements

### Requirement: 节点在隧道上提供类型化 gRPC 服务

节点 SHALL 能在同一条 TLS+yamux 隧道上同时提供两类 gRPC 服务：按 proto 定义、由生成代码实现的类型化服务（例如 tonic 生成的 service server），以及现有的通用 `InvokePlaneService` 字符串分发服务。两类服务共存于同一 tonic 服务器，互不干扰。

#### Scenario: 类型化服务与通用分发同时可调用

- **WHEN** 一个节点同时挂载类型化服务（如 `calculator.v1.Calculator`）和通用 `InvokeService`，并已与控制平面建立隧道
- **THEN** 通过隧道对该节点发起的 `calculator.v1.Calculator/Add` 类型化调用和 `InvokePlaneService.Invoke` 通用调用均能成功返回

#### Scenario: 通用分发行为不受类型化服务影响

- **WHEN** 节点在已提供通用分发的 tonic 服务器上追加挂载类型化服务
- **THEN** 已有的 `Invoke(method, payload)` 调用行为与追加前一致，不出现方法丢失或语义变化

### Requirement: server 提供对节点的类型化拨号入口

server 的反向网关 SHALL 提供公共方法 `Gateway.Dial(ctx, peerID) (*grpc.ClientConn, error)`，对指定已注册节点开出标准 gRPC 连接；连接建立在节点会话的一条 yamux 流上，调用方 SHALL 在使用完毕后关闭该连接。调用方可以使用按 proto 生成的客户端代码在该连接上直接调用节点的类型化服务。

#### Scenario: 通过生成的客户端代码调用节点服务

- **WHEN** 调用方持有节点 proto（如 `calculator.v1`）生成的客户端代码，并调用 `Gateway.Dial` 取得连接后发起 `Add(CalcRequest{a, b})`
- **THEN** 调用返回 `CalcResponse`，其 `result` 等于节点实现计算的 a+b

#### Scenario: 拨号不存在的节点

- **WHEN** 调用方对未注册的 peerID 调用 `Gateway.Dial`
- **THEN** 返回错误，且不建立任何连接

#### Scenario: 节点未挂载被调用的类型化服务

- **WHEN** 调用方通过 `Gateway.Dial` 调用某节点上未挂载的类型化服务方法
- **THEN** 调用以 gRPC `Unimplemented` 状态错误失败，连接本身不中断会话

### Requirement: 类型化调用保留 gRPC 状态语义

经类型化路径传播的错误 SHALL 保留 gRPC status（状态码与消息），不做通用分发路径的 `ErrorDetail` 包装转换。

#### Scenario: 节点返回的错误状态原样到达调用方

- **WHEN** 节点的类型化服务实现返回 `Status::invalid_argument("Division by zero")`，调用方经 `Gateway.Dial` 发起该调用
- **THEN** 调用方收到状态码为 `InvalidArgument`、消息为 "Division by zero" 的 gRPC 错误
