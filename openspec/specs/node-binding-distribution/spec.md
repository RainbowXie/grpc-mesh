# node-binding-distribution Specification

## Purpose

定义 Node FFI 与 Java/Android binding 的仓库归属、二进制跟踪策略、Alpha 发布产物、可重复构建和分层发布门禁。

## Requirements

### Requirement: FFI crate 与 Node Core 同仓版本化
通用 FFI crate SHALL 位于 `grpc-mesh-node` 仓库并与 Node Core 处于同一 Cargo workspace 或同一受控源码树。FFI crate SHALL 使用路径依赖锁定当前 Core，不得由业务仓库通过未固定分支直接拼装内部 Rust API。

#### Scenario: Core 与 FFI 同步构建
- **WHEN** grpc-mesh-node 提交修改了被 FFI 使用的公开 facade
- **THEN** 同仓 CI 同时编译 Core、FFI 和 ABI 合约测试，接口不匹配导致提交失败

#### Scenario: 业务仓库消费 binding
- **WHEN** Java 业务项目需要嵌入节点
- **THEN** 项目依赖发布的 AAR 或版本化 artifact，不复制通用 wrapper crate

### Requirement: Git 不跟踪原生发布二进制
仓库 SHALL 跟踪 Rust、C 头文件、JNI、Java/Kotlin、Gradle 配置、测试和发布清单模板，但 SHALL NOT 跟踪生成的 `.so`、`.a`、AAR、Cargo target 或 Gradle build 目录。发布二进制 SHALL 由可重复构建流程生成。

#### Scenario: 本地构建后检查 Git 状态
- **WHEN** 开发者构建 FFI 和 AAR
- **THEN** 生成的原生库与 AAR 被精确 ignore，Git 状态不出现未跟踪二进制

#### Scenario: 源码提交包含二进制
- **WHEN** 提交尝试加入 `.so`、`.a` 或 AAR
- **THEN** CI 或发布检查拒绝提交，除非经过独立批准的 vendor 例外流程

### Requirement: Alpha 发布包含可核验产物
首个 Alpha release SHALL 发布 Android `arm64-v8a` AAR、对应 Node FFI `.so`、C 头文件、版本清单和每个文件的 SHA-256。版本清单 SHALL 记录父仓 commit、grpc-mesh-node commit、C ABI 版本、crate 版本、Java binding 版本、Android minSdk 和目标 ABI。

#### Scenario: 发布 Alpha artifact
- **WHEN** CI 为一个带版本 tag 的提交构建 Alpha
- **THEN** release 中的 AAR、`.so`、头文件、manifest 与 SHA-256 全部来自同一最终提交世界

#### Scenario: artifact 与 manifest 不一致
- **WHEN** 解包 AAR 得到的 `.so` hash、ABI 版本或 Node commit 与 manifest 不一致
- **THEN** 发布门禁失败且不得上传该批 artifact

### Requirement: 构建过程可重复且不依赖业务仓库补丁
正式 artifact SHALL 从干净 checkout 构建，使用锁定的 Rust toolchain、Android NDK 和 Gradle 依赖。业务项目不得在消费阶段修改 grpc-mesh-node 源码才能获得声明能力；必要的 Android 适配 SHALL 先进入 Node Core 或 FFI crate。

#### Scenario: 干净 checkout 重建
- **WHEN** CI 在相同 tag、toolchain 和 NDK 下从干净 checkout 重建
- **THEN** 产物接口、版本清单和测试结果一致，二进制差异若不可避免必须可解释并记录

#### Scenario: 需要 Android 适配修复
- **WHEN** binding 发现 Node Core 在 Android 上缺少能力或存在兼容问题
- **THEN** 修复提交到 grpc-mesh-node 并由同仓测试覆盖，不在业务仓库维护永久 patch

### Requirement: 发布前执行分层验证
发布门禁 SHALL 分别验证 Rust Core/FFI 单元测试、C ABI 合约测试、JNI 测试、Android instrumentation 或真机 smoke test，以及通过 AAR 的最小端到端节点接入与方法调用。仅编译成功不得视为发布通过。

#### Scenario: Alpha 发布门禁全绿
- **WHEN** 准备上传 Alpha artifact
- **THEN** 所有分层测试在最终 tag 对应提交上通过，且最终构建后工作区、gitlink、子仓 HEAD 与远端 ref 完成收据核对

#### Scenario: 某一验证层缺失
- **WHEN** JNI 回调、Android 加载或 AAR 端到端测试没有执行
- **THEN** release 标记为未完成并禁止对外发布
