## Why

`grpc-mesh-node` 目前只能由 Rust 应用直接嵌入，Java、Android 及其他语言无法在进程内复用同一套隧道、握手、方法注册与通用 Invoke 实现。当前已经出现跨项目嵌入需求，应在 Rust Core 外建立稳定且可版本化的 C ABI，并以该 ABI 为底座提供 Java/Android binding，避免每个业务仓库各自复制 wrapper、生命周期和内存所有权规则。

## What Changes

- 在 `grpc-mesh-node` 仓库内新增独立的 FFI crate，产出 `cdylib` 与 C 头文件，封装节点创建、方法注册、启动、停止、释放、错误读取和 ABI 版本查询。
- C ABI 采用不透明单调句柄、显式字节缓冲区所有权和宿主回调表，不向外暴露 Rust 类型、Tokio 类型或内部指针。
- 节点在 FFI 层内部拥有 Tokio runtime、隧道连接器、MethodRegistry 与关闭状态；`stop` 和 `free` 具备明确的幂等与并发语义。
- 新增 Java/Android binding：JNI shim 只做 JVM 类型转换、线程附着、GlobalRef 生命周期和异常映射，上层提供可关闭的 Java/Kotlin API。
- 首个发布目标为 Android `arm64-v8a` AAR；其他 Android ABI、桌面 JVM 与其他语言 binding 属后续变更，但 C ABI 保持语言无关。
- `.so` 不直接提交到 Git；源码、头文件、JNI/AAR 构建配置与合约测试进入版本库，预编译 `.so`/AAR 由 CI 或 release artifact 发布并附校验值。
- 业务仓库只消费发布的 AAR 或 C ABI artifact，不承载通用 FFI crate，也不直接依赖 `grpc-mesh-node` 的 Rust 内部 API。

## Capabilities

### New Capabilities

- `node-c-abi`: `grpc-mesh-node` 的稳定 C ABI，包括版本协商、节点生命周期、方法回调、错误模型、字节缓冲区所有权、并发与关闭规则。
- `node-java-binding`: 基于 Node C ABI 的 Java/Android binding，包括 JNI 线程管理、Java 回调、异常映射、AAR 打包和 Android ABI 加载行为。
- `node-binding-distribution`: Node FFI 与 Java binding 的构建和分发契约，包括 crate 归属、产物命名、Git 跟踪策略、release artifact 和校验信息。

### Modified Capabilities

（无——`openspec/specs/` 当前没有既有 capability。）

## Impact

- `grpc-mesh-node` 子模块：改为 workspace 或加入内部 FFI crate；复用现有 `TunnelConnector`、`Handshake`、`MethodRegistry`、`InvokeService` 与关闭通道；必要时为可嵌入生命周期抽取公开但受控的 Rust facade。
- 父仓库：新增 Java/Android binding 工程、AAR 集成测试和最小示例；记录子模块指针并提供统一构建入口。
- 公共 API：新增版本化 C 头文件和 Java/Kotlin API；首个 Alpha 阶段允许 ABI 版本为 1，但 ABI 不兼容变更必须递增版本并在加载时拒绝不匹配调用方。
- 构建依赖：Rust Android target、Android NDK、Gradle/Android Gradle Plugin、JNI；宿主 Java API 不直接暴露 Rust 或 C 内存。
- 分发：Git 不跟踪 `.so`、AAR 或中间构建目录；release 附 `arm64-v8a` AAR、原生库、C 头文件、版本清单和 SHA-256。