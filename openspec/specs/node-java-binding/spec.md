# node-java-binding Specification

## Purpose

定义基于 Node C ABI 的 Java/Android binding，包括资源安全 API、Java 方法处理器、JNI 线程与引用管理、异常映射、Android ABI 加载和并发关闭语义。

## Requirements

### Requirement: Java binding 提供资源安全的节点 API
Java/Android binding SHALL 提供封装原生节点句柄的公开 API，并实现 `AutoCloseable`。构造、启动、停止和关闭 SHALL 映射 Node C ABI 的生命周期；关闭后继续调用 SHALL 抛出明确的 Java 状态异常，不得访问陈旧原生句柄。

#### Scenario: try-with-resources 生命周期
- **WHEN** 应用以 try-with-resources 创建、启动并使用节点
- **THEN** 作用域退出时 binding 停止并释放原生节点，即使业务代码抛出异常也不泄漏句柄

#### Scenario: 关闭后调用
- **WHEN** 应用在 close 后再次注册方法或启动节点
- **THEN** binding 抛出稳定的 IllegalStateException 或专用状态异常，不进入 JNI

### Requirement: Java binding 支持 Java 方法处理器
Java binding SHALL 允许按完整方法名注册 Java/Kotlin 处理器。处理器 SHALL 接收不可变请求对象或字节数组，并返回响应字节或结构化异常；JNI shim SHALL 保存必要的 GlobalRef，并在节点关闭且所有在途回调结束后释放。

#### Scenario: Java 处理器返回响应
- **WHEN** 远端请求命中已注册 Java 处理器且处理器返回字节数组
- **THEN** binding 把完整字节传回 C ABI，远端收到对应成功响应

#### Scenario: Java 处理器抛出异常
- **WHEN** Java 处理器抛出异常
- **THEN** JNI 清除 pending exception，把类名和消息映射为结构化远端错误，并保持 JVM 与 Rust runtime 可继续工作

#### Scenario: 处理器替换或注销
- **WHEN** 应用在节点启动前替换或注销某方法处理器
- **THEN** 旧 GlobalRef 被安全释放，方法清单与最终注册状态一致

### Requirement: JNI 回调正确管理线程附着
Rust runtime 工作线程回调 Java 时，JNI shim SHALL 取得有效 JNIEnv：未附着线程必须临时附着，回调结束后按附着所有权决定是否分离；已经由 JVM 管理的线程不得被错误分离。

#### Scenario: Rust 工作线程首次回调 Java
- **WHEN** 未附着 JVM 的 Rust runtime 线程执行 Java 方法处理器
- **THEN** JNI 临时附着该线程，成功调用处理器，并在回调结束后安全分离

#### Scenario: 已附着线程执行回调
- **WHEN** 当前线程已经附着 JVM
- **THEN** JNI 复用现有 JNIEnv 且不在回调结束时分离该线程

### Requirement: Java binding 映射原生错误
Node C ABI 的配置、版本、生命周期和基础设施错误 SHALL 映射为带错误码与原始消息的 Java 异常。远端业务错误 SHALL 通过调用结果或处理器错误语义传递，不得与动态库加载失败或 ABI 不兼容混为同一异常。

#### Scenario: 原生库缺失
- **WHEN** 当前 Android ABI 没有对应原生库
- **THEN** binding 抛出包含设备 ABI 与预期库名的可操作加载异常

#### Scenario: ABI 不兼容
- **WHEN** AAR 中 Java 层要求的 ABI 主版本与加载的 `.so` 不同
- **THEN** binding 在创建节点前拒绝运行并报告双方版本

#### Scenario: 配置错误
- **WHEN** Node C ABI 因配置非法拒绝创建节点
- **THEN** binding 抛出包含原生错误码与描述的配置异常

### Requirement: Android binding 按 ABI 加载原生库
首个 Alpha AAR SHALL 包含并只声明已验证的 `arm64-v8a` 原生库。运行在未包含的 Android ABI 上时 SHALL 明确失败，不得回退加载其他架构或静默禁用节点功能。

#### Scenario: arm64-v8a 加载
- **WHEN** arm64-v8a Android 进程加载 Alpha AAR
- **THEN** System.loadLibrary 成功并通过 ABI 版本检查

#### Scenario: 未支持 ABI
- **WHEN** x86、x86_64 或 armeabi-v7a 设备使用仅含 arm64-v8a 的 Alpha AAR
- **THEN** binding 返回明确的不支持 ABI 错误，不尝试加载不兼容二进制

### Requirement: Java binding 提供并发安全语义
Java 公开对象 SHALL 能安全处理多个远端并发请求以及与 stop/close 并发的回调。binding SHALL 阻止 close 后的新回调进入，并在原生层确认在途回调退出后释放 Java 引用。

#### Scenario: 多线程并发处理
- **WHEN** 多个远端请求同时进入同一 Java 处理器
- **THEN** 每次调用获得独立请求数据，binding 不共享可变响应缓冲区

#### Scenario: close 与回调并发
- **WHEN** Java 线程调用 close 时存在执行中的处理器
- **THEN** close 遵循 C ABI 的有界关闭语义，不提前释放处理器 GlobalRef
