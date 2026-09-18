# node-c-abi Specification

## Purpose

定义 `grpc-mesh-node` 面向非 Rust 宿主的稳定 C ABI，包括版本协商、节点生命周期、宿主方法回调、内存所有权、并发关闭和线程局部错误规则。

## Requirements

### Requirement: Node C ABI 提供版本协商与稳定符号集
Node FFI 动态库 SHALL 导出 ABI 版本查询函数和带统一前缀的稳定符号集。调用方 MUST 在创建节点前校验 ABI 主版本；不兼容版本 SHALL 被明确拒绝，不得继续调用未确认兼容的函数。

#### Scenario: ABI 版本匹配
- **WHEN** 调用方读取动态库 ABI 版本且主版本等于编译期头文件要求
- **THEN** 调用方可以继续创建节点并使用该版本声明的函数

#### Scenario: ABI 版本不匹配
- **WHEN** 调用方要求的 ABI 主版本与动态库返回值不同
- **THEN** binding 拒绝初始化并返回可诊断的版本不兼容错误

### Requirement: Node C ABI 管理节点生命周期
C ABI SHALL 使用不透明单调句柄管理节点实例，并提供创建、启动、停止和释放函数。FFI 层 SHALL 拥有节点使用的 Tokio runtime、隧道连接器、方法注册表与关闭通道；`stop` 和 `free` SHALL 幂等，已释放句柄不得影响后续节点实例。

#### Scenario: 创建并启动节点
- **WHEN** 调用方以合法 JSON 配置和回调表创建节点并调用 start
- **THEN** 节点启动内部 runtime，建立隧道并开始提供已注册方法

#### Scenario: 非法配置创建失败
- **WHEN** JSON 配置缺少 node id、token 或 server address 等必需字段
- **THEN** 创建返回空句柄并通过错误接口提供描述性错误

#### Scenario: 重复停止与释放
- **WHEN** 调用方对同一节点重复调用 stop 和 free
- **THEN** 重复调用为 no-op 或返回稳定的已关闭状态，进程不崩溃且不影响其他节点

#### Scenario: 陈旧节点句柄
- **WHEN** 节点 A 已释放，节点 B 随后创建，调用方再次使用 A 的旧句柄
- **THEN** 操作被拒绝且 B 不受影响

### Requirement: Node C ABI 支持宿主方法回调
C ABI SHALL 允许调用方在节点启动前按完整方法名注册宿主回调。回调 SHALL 接收只读请求字节、请求元数据与用户上下文，并通过受控响应句柄返回成功字节或结构化错误；Rust panic、宿主错误和取消 SHALL 被转换为稳定 ABI 状态，不得跨 FFI 边界展开。

#### Scenario: 方法调用成功
- **WHEN** 远端调用一个已注册方法且宿主回调返回成功响应字节
- **THEN** 节点通过现有 InvokeService 返回相同响应字节

#### Scenario: 方法未注册
- **WHEN** 远端调用未注册的方法
- **THEN** 节点返回现有 NOT_FOUND 语义且不调用任何宿主回调

#### Scenario: 宿主回调返回错误
- **WHEN** 宿主回调返回错误码、消息和可选详情
- **THEN** 节点把错误映射为稳定的 Invoke 错误响应，不泄漏宿主内存

#### Scenario: 二进制请求包含 NUL
- **WHEN** 请求 payload 中包含一个或多个 NUL 字节
- **THEN** 回调按显式指针与长度接收完整字节序列，不使用 C 字符串截断

### Requirement: Node C ABI 定义内存所有权
所有跨 ABI 的字符串和字节缓冲区 SHALL 具有单一明确所有者。传入指针只在调用或回调期间借用；由 FFI 返回的数据 SHALL 使用不透明单调句柄读取和释放，不得要求调用方直接 `free` Rust 分配的内存。重复释放和陈旧释放 SHALL 不影响活跃分配。

#### Scenario: 响应句柄读取与释放
- **WHEN** 宿主取得 FFI 返回的响应句柄，读取数据并释放该句柄
- **THEN** 数据在释放前有效，释放后读取失败，重复释放不崩溃

#### Scenario: 地址复用下的陈旧释放
- **WHEN** 已释放句柄 A 的底层地址被新分配 B 复用，调用方再次释放 A
- **THEN** B 仍然有效且内容不变

### Requirement: Node C ABI 规定并发关闭语义
方法回调 MAY 并发执行；FFI SHALL 跟踪在途回调并禁止释放仍被回调使用的用户上下文。`stop` SHALL 阻止新请求进入，取消连接与 runtime 工作，并在有界时间内等待在途回调结束；超时 SHALL 返回明确错误而不是无限阻塞或强行释放活跃上下文。

#### Scenario: 并发方法调用
- **WHEN** 多个远端请求同时调用同一注册方法
- **THEN** 回调可以并发执行，每个请求的输入、响应和错误相互隔离

#### Scenario: 回调期间停止节点
- **WHEN** 一个回调仍在执行时宿主调用 stop
- **THEN** 新请求被拒绝，已有回调获得完成或取消机会，用户上下文只在回调退出后释放

#### Scenario: 停止超时
- **WHEN** 在途回调未在配置的关闭期限内退出
- **THEN** stop 返回超时错误并保持可诊断状态，不执行不安全的强制释放

### Requirement: Node C ABI 提供线程局部错误信息
ABI 基础设施错误 SHALL 通过稳定状态码与线程局部错误字符串暴露。错误字符串由库拥有，调用方不得释放；下一次同线程 ABI 调用可以覆盖该字符串。

#### Scenario: 并发调用分别读取错误
- **WHEN** 两个宿主线程同时触发不同 ABI 错误并分别读取 last error
- **THEN** 每个线程获得自己的错误信息，不互相覆盖
