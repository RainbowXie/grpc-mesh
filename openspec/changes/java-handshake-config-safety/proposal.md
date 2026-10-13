# Proposal

## Why

Java `NodeConfig` 原先未暴露 Rust Core 已有的 `node.features`、`node.metadata`，存在真实的握手配置能力缺口；提交 `14147a87e6842250fe48243a88047fa46eadc2cf` 虽补上入口，但引入 Android API 24–30 的 `copyOf` 兼容性风险和 metadata 值裁剪，同时暴露了 Rust 允许用户 metadata 覆盖 `mesh.methods` 的既有问题。本 change 正式承接通用配置能力及其审查整改，不把外部业务 Server 的 capability 筛选规则当作 grpc-mesh 的固有协议。

## What Changes

- 保留并规范 Java Builder 的 `addFeature`、`addMetadata` 和对应 JSON 输出，将功能定位为通用握手配置而非某个业务节点的专用修复。
- 用最低支持 Android API 可用的防御性复制与不可修改集合替换无条件 `List.copyOf`、`Map.copyOf`；继续支持 `minSdk 24`，不要求消费方额外配置 core library desugaring 才能构造配置。
- metadata key 必须非空且不包含首尾空白；metadata value 必须非 null 并原样保留，包括空字符串、首尾空白、换行和转义字符。重复 key 使用最后一次赋值，已 build 的配置不受后续 Builder 修改影响。
- 在 Java Builder 和 Rust Core 配置/握手边界拒绝保留 metadata key `mesh.methods`、`platform`、`arch`，保证各语言宿主不能覆盖框架生成的实际方法清单或平台信息。**BREAKING**：此前使用这些 key 的自定义配置将被明确拒绝。
- 明确 feature 使用现有 Rust 握手规范化规则；Java 负责类型正确的 JSON 数组输出，不引入业务 capability 白名单、额外协议字段或新的 C ABI 符号。
- 增加 JSON、不可变快照、原生配置、真实握手和 Android 最低版本回归；将已发现的反例固定为确定性测试。
- 更新通用 API 文档，移除未经本仓验证的 `isEligibleByCapability`、`hasCapability` 和 `dg` 调度因果声明；以新 binding 版本交付新增公共 API，保留已发布 Alpha tag 和资产不可变。

## Capabilities

### New Capabilities

无。

### Modified Capabilities

- `node-java-binding`：新增可移植的握手配置 API、metadata 原值保留、不可变快照及 Android API 24 兼容性要求。
- `node-c-abi`：新增通过 JSON/C ABI 或直接 Rust facade 使用自定义握手字段时的校验和保留 key 拒绝规则。
- `method-reporting`：明确 `mesh.methods` 只由实际注册表生成，用户 metadata 不得覆盖该权威值。

## Impact

- 父仓：`bindings/java/node-core/src/main/java/io/mesh/node/NodeConfig.java`、Java 测试、Android 兼容性与真实握手验收、Java 文档和版本交付配置。
- Node 子仓：`grpc-mesh-node/src/embedded.rs` 的配置/握手校验、Rust 与 C ABI 合约测试；如抽取校验模块，须先执行 GitNexus impact 分析。
- 现有行为：未设置自定义字段的 JSON 语义不变；C ABI v1 函数签名和传输格式不变。非法 metadata 和保留 key 从容忍或静默覆盖改为明确错误。
- 范围边界：不修改 IntegritySwarmServer、MeshRegistration 或外部 readiness/capability 筛选逻辑，不以业务调用成功替代 SDK 合约测试。
