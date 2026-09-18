## 1. Node Core 嵌入 facade

- [x] 1.1 在修改 `grpc-mesh-node` 前对 `TunnelConnector`、`MethodRegistry`、`InvokeService` 与关闭路径执行 GitNexus impact/context 分析并记录风险
- [x] 1.2 将 `grpc-mesh-node` 组织为可容纳内部 crate 的 Cargo workspace，同时保持现有 crate、binary 和 Android 构建入口兼容
- [x] 1.3 先写 Rust RED 测试，覆盖 EmbeddedNode 的合法状态转换、非法重入、重复 stop、启动失败清理和关闭超时
- [x] 1.4 实现 `EmbeddedNode` facade，统一拥有配置解析、Handshake、MethodRegistry、TunnelConnector、tonic server、shutdown token 和监督任务
- [x] 1.5 实现 start 前方法快照与排序后的 `mesh.methods` 握手上报，禁止运行期注册变更
- [x] 1.6 增加 facade 集成测试，验证通用 Invoke 成功、未注册方法、业务错误、连接失败和停止后无残留任务

## 2. Node C ABI crate

- [x] 2.1 在 `grpc-mesh-node/crates/grpc-mesh-node-ffi/` 创建 `cdylib`/`staticlib` crate，并以路径依赖绑定同仓 Node Core
- [x] 2.2 定义 ABI v1 的 C 类型、状态码、句柄、回调签名、response writer、错误码和高低位版本编码
- [x] 2.3 实现单调 Node 句柄表与 `Created -> Starting -> Running -> Stopping -> Stopped -> Freed` 状态机，0 为失败哨兵且 id 不复用
- [x] 2.4 实现 `mesh_node_abi_version/new/register_method/unregister_method/start/stop/free/last_error`，所有导出函数捕获 panic 且不跨 FFI 展开
- [x] 2.5 实现每节点独占监督线程和 Tokio runtime，stop 使用 cancellation token、有界等待和可重试 `SHUTDOWN_TIMEOUT`
- [x] 2.6 实现方法回调桥、只读请求视图、response writer 与结构化错误转换，二进制 payload 全程使用指针加长度
- [x] 2.7 实现在途回调计数、关闭栅栏和用户上下文释放顺序，禁止 close 期间新回调进入
- [x] 2.8 实现线程局部 last error，并验证多个宿主线程不会互相覆盖错误字符串
- [x] 2.9 使用 cbindgen 生成稳定头文件并加入 deterministic regeneration check 和导出符号清单测试

## 3. C ABI 合约与反证测试

- [x] 3.1 编写纯 C 或等价 host harness，按生成头文件创建、启动、停止和释放真实节点
- [x] 3.2 覆盖非法配置、ABI 不匹配、启动失败、重复 stop/free、陈旧节点句柄和多个节点隔离
- [x] 3.3 覆盖已注册方法成功、未注册方法、宿主错误、panic 转换、包含 NUL 的 payload 和大 payload 边界
- [x] 3.4 覆盖并发回调、同方法多请求、回调期间 stop、关闭超时、重试 stop 与用户上下文延迟释放
- [x] 3.5 覆盖响应句柄的读取、释放、重复释放和地址复用下陈旧释放不影响活跃分配
- [x] 3.6 执行主机 target 的 Rust 单测与 sanitizer/等价内存检查，并显式执行 Android target 的 build，禁止依赖默认 Cargo target

## 4. Java/Android binding

- [x] 4.1 在父仓创建 `bindings/java/android/` Gradle 工程，确定 Java package、Maven coordinates、minSdk、targetSdk 和版本策略
- [x] 4.2 先写 JVM/JNI RED 测试，覆盖 AutoCloseable、关闭后调用、异常映射、ABI 不兼容和缺失原生库错误
- [x] 4.3 实现 `GrpcMeshNode`、配置对象、`MethodHandler`、结构化异常和基于 `AtomicLong` 的一次性 close
- [x] 4.4 实现 JNI shim 与 `JNI_OnLoad` JavaVM 保存，所有 JNI 导出只适配稳定 C ABI，不访问 Node Core 内部类型
- [x] 4.5 实现 MethodHandler GlobalRef 注册、替换、注销和节点关闭后的延迟释放
- [x] 4.6 实现 Rust 工作线程的 `GetEnv`、临时 attach/detach、local frame 和 Java exception 提取与清除
- [x] 4.7 实现 byte[] 与原生指针/长度转换，验证 NUL 字节、大 payload 和并发响应不共享可变缓冲区
- [x] 4.8 实现 close 与在途 Java 回调并发的栅栏，确保 GlobalRef 仅在原生回调退出后释放

## 5. Android arm64-v8a 构建与 AAR

- [x] 5.1 锁定 Rust toolchain、`aarch64-linux-android` target、Android NDK 版本和可重复构建命令
- [x] 5.2 构建 `libgrpc_mesh_node.so` 并打入 AAR 的 `jni/arm64-v8a/`，不得声明未提供的 ABI
- [x] 5.3 实现原生库加载与 ABI 检查，错误信息包含 `Build.SUPPORTED_ABIS`、库名和双方 ABI 版本
- [x] 5.4 在 arm64 Android 真机或受支持设备环境执行 AAR 加载、节点启动、方法回调、异常映射和关闭 smoke test
- [x] 5.5 新增最小 Android 示例，展示配置、方法注册、start、stop 和 try-with-resources 或等价安全关闭模式

## 6. 全链路验收

- [x] 6.1 启动真实 grpc-mesh-server，由 AAR 内 Node 连接并在 `/nodes` 或注册表中显示 Java 上报的方法清单
- [x] 6.2 从控制面通过通用 Invoke 调用 Java MethodHandler，验证 protobuf 请求/响应字节与 Rust 原生节点路径一致
- [x] 6.3 验证 Java handler 抛异常后远端收到结构化错误，后续请求仍可成功执行
- [x] 6.4 验证多线程并发 Invoke、回调期间 stop、关闭超时与重试、进程退出后无残留连接或线程
- [x] 6.5 执行现有 grpc-mesh-node、server、typed invocation 和 Python binding 回归，确认新增 workspace 与发布脚本未破坏既有能力

## 7. 分发与文档

- [ ] 7.1 更新 `.gitignore` 和 CI 门禁，禁止提交 `.so`、`.a`、AAR、Cargo target 与 Gradle build 产物
- [ ] 7.2 编写 Node C ABI 文档，明确状态机、线程模型、回调契约、内存所有权、错误码和 ABI 兼容规则
- [ ] 7.3 编写 Java/Android binding 文档，明确 arm64-v8a 限制、依赖方式、处理器并发、异常与关闭语义
- [ ] 7.4 建立 tag 驱动的 Alpha release 流程，从干净 checkout 生成 AAR、独立 `.so`、C 头文件、manifest 和 SHA-256
- [ ] 7.5 manifest 记录父仓 commit、grpc-mesh-node commit、ABI/crate/binding 版本、NDK、Rust toolchain、minSdk 和目标 ABI
- [ ] 7.6 在全新消费工程安装发布 AAR，不引用本地源码路径，完成一次真实接入和远端方法调用

## 8. 收尾审计

- [ ] 8.1 运行 `openspec validate node-ffi-java-binding` 并完成全部任务与场景映射
- [ ] 8.2 运行 GitNexus `detect_changes(scope=all)`，审查 HIGH/CRITICAL 影响、受影响流程与循环依赖
- [ ] 8.3 执行 code-audit、premise-audit 和 receipt-audit，主动反证句柄、回调、关闭、JNI 引用与 artifact 一致性断言
- [ ] 8.4 最后一次写入后核对父仓/子仓 HEAD、gitlink、远端 ref、工作区、release artifact hash 和 manifest，分仓提交并推送