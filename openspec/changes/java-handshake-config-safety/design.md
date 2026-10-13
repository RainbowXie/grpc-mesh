# Design

## Context

动机与范围见 proposal.md。本设计以审查提交 `14147a87e6842250fe48243a88047fa46eadc2cf` 为现状基线：Java 在构造配置时无条件调用 API 31 才提供的 `List.copyOf`、`Map.copyOf`，Android 模块仍声明 minSdk 24 且未配置 core library desugaring；`addMetadata` 裁剪 value 并把 null 替换为空字符串。Rust `EmbeddedNodeConfig::handshake` 先写框架 metadata，再写用户 metadata，因此存在覆盖。

用户在本 change 执行中先后调整支持范围，最终将 Android 最低支持版本定为 Android 10/API 29（取代中途的 API 31 要求，不是同时支持两个下限）。Java 模块必须回避 API 30/31 才提供的 `List.of`/`Set.of`/`copyOf`/`strip`/`isBlank` 等，已有防御性复制实现保留。

已执行探针确认 Java JSON 可以解析、Builder 快照隔离成立，实际 TLS/yamux 握手收到规范化 feature 和 metadata；也确认 `mesh.methods` 可以变为伪造的方法名。该捕获端只读取握手而不实现完整控制协议，运行随后出现 ShutdownTimeoutException，因此证据只支持握手字段结论，不支持完整生命周期或业务调用成功。JVM 7/7 与 lint 通过不能替代低版本 Android 验证；外部 Server 的 `no_eligible_nodes` 因果关系尚未核实。

## Goals / Non-Goals

**Goals:**
- 将 Java 配置映射到 Rust 已有握手协议，修复已复现的兼容性和数据保真问题，并在 Java 与原生边界共同保护框架字段。
- 用同一输入的基线失败与修复成功记录支撑整改，保证修复不改变默认配置、C ABI v1 或实际方法上报语义。

**Non-Goals:**
- 不新增业务 capability 筛选、readiness 计算或业务 metadata 键约定；不将通用 binding 注释绑定到外部项目内部函数。
- 不扩展 Android ABI，不更改 JNI 回调与关闭设计，不重写通用 JSON 序列化架构；Android 最低支持版本按用户最终决定定为 API 29（见 Decision 6），不算作本 change 的其他范围扩张。

## Decisions

### 1. 保留防御性复制的不可变集合快照

Java 构造器采用 `Collections.unmodifiableList(new ArrayList<>(...))` 与 `Collections.unmodifiableMap(new LinkedHashMap<>(...))`，保持防御性复制及稳定迭代顺序。在 API 29 下限下，`List.of`/`Set.of`（API 30）与 `List.copyOf`/`Map.copyOf`（API 31）不可用，因此该实现不仅是快照语义选择，也是兼容性要求；集合顺序仅用于可重复序列化，不成为线上协议约束。

### 2. metadata 保真且非法输入显式失败

key 为 null、空白或含首尾空白时拒绝，不通过 trim 隐式重命名；value 为 null 时拒绝，空字符串合法，其余字符原样保留。Java 错误包含 key 或参数名，Rust JSON 类型错误需包含相关字段路径。重复 key 保持现有 last-write-wins；feature 延续 Rust normalize_features 的现有规则，Java 拒绝 null 或空白 feature，不承担另一套业务规范化。

选择拒绝而非静默转换，是因为 key 变更可能制造碰撞，value 裁剪会改变业务数据。Java 和原生测试都必须验证这条约束，不能仅测试最终字符串看起来像合法 JSON。

### 3. 原生边界统一拒绝精确保留 key

保留集合为 `mesh.methods`、`platform`、`arch`，分别对应现有框架自动生成的注册表、平台和架构值。仅保留这三个精确 key，不保留整个 `mesh.*` 命名空间，也不扩张到未经验证的业务字段。Java 早期校验用于可诊断性；Rust Core 负责不可绕过的正确性，必须覆盖从 JSON 解析和直接构造公开配置两条路径，在开始连接前拒绝。

优先共享一个小型原生校验函数，分别在配置解析/验证和握手构造边界复用，避免公开字段可变导致解析后再次注入绕过。方法清单仍从实际 registry 生成。相比用户字段先写、框架最后覆盖，拒绝方案避免宿主误以为自定义值生效，并符合无静默 fallback 的错误策略。

### 4. 分层验收而非依赖业务 Server

纯 Java 测试用标准 JSON 解析器验证数组类型、转义、默认配置、空字符串、value 空白、重复 key 和快照隔离。Rust/C ABI 测试覆盖合法字段及保留键/非法值，直接构造配置是必测反例。网络测试使用本仓完整控制协议的测试 Server，捕获并断言真实握手，同时验证方法注册、替换、注销及正常关闭；不把仅捕获握手的探针退出码当作生命周期通过。

Android 回归使用消费工程且不启用 core library desugaring，在最低支持的真实 arm64-v8a Android 10/API 29 设备上执行默认与自定义 build/toJson，并在该设备上跑 JNI payload/并发/关闭回归；同时检查编译后方法引用和 lint。当前优先要求 API 29 真机实测（设备 192.168.123.219:5555 已授权）；若设备不可达，按标准 `adb reverse` 路由并如实记录前提。

### 5. 新版本承载公共 API 与校验变化

Java API 新入口与原生校验必须来自同一 clean-source 构建，发布 manifest 绑定父仓提交和 Node gitlink；AAR 与独立 `.so` 不允许混用来源。版本号在 apply 的版本任务中依据现有发布序列选取下一个未使用 Alpha，并使 Gradle、发布脚本和示例一致；不复用或替换已发布 Alpha2。准备可发布产物与验证不等于授权上传，实际发布须由用户确认。

### 6. 用户最终将 Android 最低支持版本定为 API 29

用户在本 change 执行中先由 API 24 改为 API 31，随后改定为最低 Android 10/API 29，取代 API 31 要求（不是同时支持两个下限）。因此 `node-android` 的 `minSdk`、发布 manifest 的 `android_min_sdk` 和 README 统一为 `29`；Android 12 阶段的产物与测试保留为历史，不计入 API 29 验收。Java 代码必须回避 API 30/31 才提供的方法；原生构建需避免引用高于 API 29 的符号，并使用真实 API 29 设备做产物级验收。测试环境已知需容忍旧设备时钟偏移（测试证书有效期向前回拨）。当前 NDK 工具链目标为 API 34，已在真实 API 29 设备上验证 .so 可加载并完成握手，未发现缺失符号，作为剩余风险跟踪。

## Risks / Trade-offs

- [历史配置使用保留 key] → 在迁移说明列出三个 key 和拒绝规则；调用方删除手工覆盖，方法清单使用真实注册 API。
- [元数据 key 校验比原实现严格] → 明确标注行为变化，对每类非法输入保留可诊断错误，禁止静默 trim。
- [Rust 公开配置可在解析后修改] → 在连接前重复执行共享校验，用直接构造/修改配置测试主动反证。
- [低版本 Android / 原生 ABI 兼容] → 支持范围为 API 29+；在真实 API 29 设备上跑产物回归，并对发现的实际兼容问题（TLS、Java 库、编译目标 API）做最小范围修复，不新增功能。
- [实现文件接近行数约束] → apply 前检查长度，单文件强制低于 1000 行；仅在必要时拆分验证代码，拆分前分析图影响，不借此扩张重构。

## Migration Plan

1. 保存父仓与 Node 子仓基线提交、原始行为和证据，先添加可复现失败测试；实现前运行 GitNexus impact，并对 HIGH/CRITICAL 或 UNKNOWN 结论按项目规则处理。
2. 修复 Java 与 Rust 校验，执行分层回归和独立审查/前提反证，修复全部阻塞问题；提交前执行 detect_changes 并核对 gitlink。
3. 更新通用 API 示例与拒绝规则，以新的 binding 版本生成 clean-source AAR、原生库、manifest 和校验和，验证消费工程使用最终产物而非源码缓存。
4. 用户确认后才上传新版本；发布验收包含重新下载校验。已有 tag 和资产保持不变。
5. 回滚通过父仓和 Node 子仓记录的基线提交及对应 gitlink 恢复源码；消费方回退到既有 Alpha2 时不使用新增 Builder 方法。新版本发生问题时标记缺陷并发布后继修复，不覆盖历史资产。
