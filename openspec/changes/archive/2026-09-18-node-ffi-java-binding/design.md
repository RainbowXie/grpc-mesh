## Context

`grpc-mesh-node` 已提供 Rust 原生的 `TunnelConnector`、`Handshake`、`MethodRegistry`、`InvokeService` 和基于 Tokio 的连接生命周期，但没有稳定的进程内跨语言边界。业务仓库若各自创建 JNI 或 cdylib wrapper，会重复设计 runtime 所有权、方法回调、内存释放、关闭竞态和 Android 构建规则，并直接耦合 Node Core 内部结构。该变更同时跨越 Rust Core、C ABI、JNI、Java/Kotlin API、Android 打包和 release artifact，因此需要先固定所有权与并发模型，再开始实现。

约束包括：首个 Alpha 只承诺 Android `arm64-v8a`；C ABI 必须保持语言无关；`.so` 和 AAR 不进入 Git；业务仓库只消费发布 artifact；所有非 Rust binding 共用同一 ABI 语义；不得以强制终止线程、泄漏 GlobalRef 或吞掉 panic/Java exception 的方式实现关闭。

## Goals / Non-Goals

**Goals:**

- 为 `grpc-mesh-node` 提供小型、版本化、可测试的稳定 C ABI。
- 允许宿主语言注册方法处理器，使远端 Invoke 请求调用宿主业务逻辑。
- 为 Java/Android 提供资源安全、异常清晰、并发安全的 binding 和 `arm64-v8a` AAR。
- 固定 runtime、句柄、回调、内存、错误和关闭所有权规则，供后续其他语言 binding 复用。
- 建立不提交二进制、从干净 checkout 构建并发布可核验 artifact 的流程。

**Non-Goals:**

- 本期不提供 Python、Go、C# 或桌面 JVM binding。
- 本期不提供 `armeabi-v7a`、x86 或 x86_64 Android 原生库。
- 本期不把类型化业务 proto 自动映射为 Java stub；宿主方法处理器仍使用完整方法名和 protobuf 字节。
- 本期不实现动态方法增量上报；方法注册在 start 前冻结并随初始握手上报。
- 本期不保证 ABI 1 在 Alpha 阶段永久冻结，但任何不兼容变更必须递增 ABI 主版本并被加载检查拒绝。

## Decisions

### D1：通用 FFI crate 放在 `grpc-mesh-node` 仓库

新增 `crates/grpc-mesh-node-ffi/`，与 Node Core 使用同一 Cargo workspace 和路径依赖。FFI 与 Core 在同一提交中编译、测试和版本对齐，Android 适配修复先进入 Node Core 或该 crate。父仓只保存 Java/Android binding、集成测试和 grpc-mesh-node gitlink。备选方案是把 wrapper crate 放到业务仓库并使用 Git 依赖；该方案会使每个业务仓库维护不同 patch、生命周期和 ABI，故不采用。

### D2：在 Core 与 FFI 之间增加 Rust facade

FFI 不直接拼装 `TunnelConnector`、`MethodRegistry` 和 tonic server 的内部细节，而是在 Node Core 中抽取受控的 `EmbeddedNode` facade。facade 接受配置、方法注册表和 shutdown token，拥有一次完整的连接与服务生命周期，并返回结构化 Rust 错误。这样 C ABI 只负责类型转换和句柄状态机，Core 行为仍可通过纯 Rust 测试验证。备选方案是在 FFI crate 复制 calculator-service 的组装代码；该方案容易与 Core 漂移，故不采用。

### D3：FFI 节点句柄采用单调 id，不导出 Rust 指针

全局句柄表保存 `Arc<NodeEntry>`，句柄 0 为失败哨兵，id 只增不复用。`NodeEntry` 状态机为 `Created -> Starting -> Running -> Stopping -> Stopped -> Freed`；非法转换返回状态错误。`free` 从句柄表移除条目，但只有在 runtime 和在途回调退出后才释放用户上下文。备选方案是把 `Box<NodeEntry>` 转为裸指针；该方案难以可靠识别陈旧句柄和重复释放，故不采用。

### D4：每个节点独占 Tokio runtime 和监督线程

`mesh_node_start` 创建一个专用 OS 线程，在该线程内建立 Tokio multi-thread runtime，运行隧道连接、tonic server 和 shutdown 监督。Java 调用线程不会被异步 runtime 占用；`stop` 通过 cancellation token 发起关闭并等待监督线程在配置期限内退出。首版不共享全局 runtime，避免一个宿主实例关闭或 panic 影响其他实例。代价是每个节点有固定线程与内存开销；后续可在不改变 C ABI 的前提下优化内部 runtime 池化。

### D5：方法注册在 start 前完成并冻结

C ABI 提供 `mesh_node_register_method` 和可选 `mesh_node_unregister_method`，但只允许在 `Created` 状态调用。start 时生成不可变方法快照，注册到现有 `MethodRegistry`，并把排序后的完整方法名通过 `mesh.methods` 握手 metadata 上报。运行期动态更新需要控制面 `UpdateMethods` 及并发 GlobalRef 替换，留作后续变更。

### D6：回调表使用函数指针、用户上下文和响应写入器

每个方法保存回调函数与 `void* user_data`。回调接收请求指针、长度、方法名、关联 id 和只读元数据；返回统一状态码，并通过 FFI 提供的 response writer 创建成功字节或错误信息。传入数据仅在回调期间有效。FFI 捕获 Rust panic 并转换为 `INTERNAL`；binding 负责把 Java exception 清除并转为结构化错误。不得让宿主直接释放 Rust 内存或让 Rust 保存临时 JNI local reference。

### D7：在途回调使用引用计数和关闭栅栏

NodeEntry 持有 `accepting_callbacks` 标志、在途计数器和条件变量。请求进入前先确认仍接受回调并递增计数，退出时递减并通知。stop 原子关闭入口、取消连接，然后在配置的 shutdown timeout 内等待计数归零。超时时保留上下文和句柄资源，返回 `SHUTDOWN_TIMEOUT`，允许调用方重试 stop；不得为了返回而提前释放 GlobalRef。

### D8：Java binding 分为 JNI shim 与公开 API

父仓新增 `bindings/java/android/`。原生 JNI shim 可以与通用 FFI crate 位于同一个 `cdylib` 中，但 JNI 导出函数只调用稳定 C ABI 逻辑，不直接依赖 Core 内部类型。公开 API 提供 `GrpcMeshNode implements AutoCloseable`、不可变配置对象、`MethodHandler`、结构化异常和状态查询。Java 层用 `AtomicLong` 保存句柄并以 CAS 保证 close 只执行一次。

### D9：JNI 回调持有 JavaVM 和 GlobalRef

`JNI_OnLoad` 保存 `JavaVM`。每个 Java handler 创建 GlobalRef，并将其包装为 method user_data。Rust 工作线程回调时先调用 `GetEnv`：未附着则 `AttachCurrentThread` 并在结束时分离，已附着则复用且不分离。每次回调使用 local frame 限制局部引用数量。Java exception 被读取、清除并映射为远端错误；若异常转换本身失败，则返回固定 `INTERNAL` 错误并记录日志。

### D10：首个 AAR 只发布 `arm64-v8a`

Gradle 构建通过 Rust Android target `aarch64-linux-android` 与锁定 NDK 生成 `jni/arm64-v8a/libgrpc_mesh_node.so`，再打入 AAR。Java 层在加载失败时报告 `Build.SUPPORTED_ABIS` 和预期 ABI，不尝试其他架构。AAR 不声明尚未提供的 ABI。后续扩展 ABI 必须分别构建和执行 smoke test。

### D11：生成产物不进入 Git，release artifact 绑定最终提交

`.so`、`.a`、AAR、Cargo target、Gradle build 和临时头文件进入精确 ignore。稳定 C 头文件可由 cbindgen 生成后通过 deterministic check 提交，CI 重生成并比较差异。release 使用 tag 触发，从干净 checkout 构建 AAR、独立 `.so`、头文件、manifest 和 SHA-256；manifest 记录父仓、Node 子仓、ABI 和工具链版本。业务项目使用 Maven/AAR 或 release 附件，不引用开发者本机路径。

### D12：首版公开 C ABI

首版符号集如下，具体 typedef 和错误码在头文件中固定：

```c
uint32_t mesh_node_abi_version(void);
mesh_node_handle mesh_node_new(const char *config_json);
int32_t mesh_node_register_method(mesh_node_handle node, const char *method, mesh_node_method_callback callback, void *user_data);
int32_t mesh_node_unregister_method(mesh_node_handle node, const char *method);
int32_t mesh_node_start(mesh_node_handle node);
int32_t mesh_node_stop(mesh_node_handle node, uint32_t timeout_ms);
void mesh_node_free(mesh_node_handle node);
const char *mesh_node_last_error(void);
```

方法响应通过 callback 的 `mesh_node_response_writer` 写入，避免把复杂 ownership 塞入函数返回值。ABI 版本使用高 16 位主版本、低 16 位次版本；主版本不兼容，次版本只允许向后兼容扩展。

## Risks / Trade-offs

- [Java 回调可能长期阻塞，导致 stop 超时] → 有界关闭、在途计数和可重试 stop；文档要求业务处理器自行限制耗时，后续可增加每方法 deadline。
- [JNI 线程附着或 GlobalRef 管理错误会导致崩溃或泄漏] → 独立 JNI 合约测试、CheckJNI instrumentation、回调压力测试和 close 竞态测试。
- [每节点独占 runtime 增加线程和内存] → Alpha 优先隔离与可预测所有权；后续仅优化内部实现，不改变 ABI。
- [C ABI Alpha 仍可能演进] → 强制 ABI 版本协商、统一前缀、符号清单测试和不兼容版本拒绝。
- [Android Rust 工具链与 NDK 组合复杂] → 锁定 rust-toolchain、NDK 版本和构建容器；CI 从干净 checkout 构建。
- [通用字节 API 缺少类型安全] → 由业务 proto 负责编码；类型化 Java stub 或描述符驱动能力另行立项。
- [Node Core 当前默认 Cargo target 指向 Android，主机测试易误跑 Android 二进制] → CI 显式指定主机测试 target 与 Android build target，不依赖仓库默认 target。

## Migration Plan

1. 在 `grpc-mesh-node` 中建立 workspace 和 `EmbeddedNode` facade，不改变现有 Rust API 行为。
2. 新增 FFI crate、C 头文件、ABI 版本与纯 C/host Rust 合约测试。
3. 在父仓新增 Android JNI/AAR binding 和 Java API，先用 fake callback 验证生命周期、异常与并发。
4. 建立真实控制面端到端测试：AAR 启动 Node、上报方法、远端 Invoke、Java 回调返回 protobuf 字节、关闭后无残留线程或连接。
5. 在 Android arm64 真机或受支持设备环境执行加载、回调和关闭 smoke test。
6. 创建 Alpha tag，从干净 checkout 生成 AAR、`.so`、头文件、manifest 与 SHA-256，并验证解包内容和版本一致性。
7. 回滚时业务项目退回上一个 AAR；Core 与 FFI 变更保持向现有 Rust 使用者兼容，不需要数据迁移。

## Open Questions

- Java 公开包名、Maven group/artifact id 和首个 Android minSdk 需在实现前确定。
- Alpha 是否需要暴露请求 metadata 的完整 map，还是只提供 correlation id、method 和 deadline。
- stop 默认超时时间以及超时后 Java `close()` 的异常策略需在 API 定稿时确定。
- C 头文件是提交生成结果并做 deterministic check，还是只作为 release artifact；设计倾向提交稳定头文件以方便 ABI review。