# Spec Delta

## ADDED Requirements

### Requirement: Java binding 提供通用握手配置
Java binding SHALL 允许宿主通过 `NodeConfig.Builder.addFeature` 和 `addMetadata` 配置握手通告，并通过 `node.features` JSON 字符串数组与 `node.metadata` JSON 字符串对象传递给原生节点。未配置这些字段时 SHALL 保持已有默认行为，不自动添加业务 capability。feature SHALL 使用现有 Rust 握手规则去除空白、转换为小写并去重；Java JSON 序列化 SHALL 正确处理引号、反斜线、换行和非 ASCII 字符。

#### Scenario: 自定义字段到达实际握手
- **WHEN** Java 宿主添加 feature `DG` 和 metadata `capability=dg`，创建并启动节点
- **THEN** 实际 TLS/yamux 握手的 `supported_features` 包含 `dg`，metadata 包含 `capability=dg`，无需改变 C ABI v1 函数签名

#### Scenario: 默认配置兼容
- **WHEN** 宿主不添加任何 feature 或 metadata
- **THEN** 节点沿用既有默认握手行为，不生成宿主未声明的业务能力

#### Scenario: 特殊字符序列化
- **WHEN** feature 或 metadata 字符串包含引号、反斜线、换行或中文
- **THEN** 输出可被标准 JSON 解析器读取，metadata 字符串内容与合法输入一致

### Requirement: Java 握手配置保留 metadata 原值与不可变快照
Java Builder SHALL 拒绝 null、空白 key 和带首尾空白的 key，拒绝 null value，允许空字符串 value，并保留合法 value 的全部字符而不裁剪空白。重复 key SHALL 使用最后一次赋值。build SHALL 创建不可修改且与 Builder 脱离的快照；后续 Builder 修改不得改变既有配置。Java SHALL 拒绝保留 key `mesh.methods`、`platform` 和 `arch`，并给出包含具体 key 的配置异常。

#### Scenario: 保留空白和空字符串
- **WHEN** 宿主分别添加 value 为 `  text  ` 和空字符串的合法 metadata
- **THEN** JSON 及实际握手中两个 value 分别仍为 `  text  ` 和空字符串

#### Scenario: 非法 metadata 明确拒绝
- **WHEN** 宿主传入 null key、空白 key、带首尾空白的 key 或 null value
- **THEN** Builder 抛出明确配置异常，不把 null 转为空字符串、不静默修正 key

#### Scenario: 快照隔离与重复 key
- **WHEN** Builder 添加同一 key 两次，build 后继续添加 feature 和修改该 key
- **THEN** 既有配置保留 build 时最后一次赋值和 feature 列表，新增修改仅影响之后 build 的配置

#### Scenario: 保留 key 不能覆盖框架字段
- **WHEN** Java 宿主尝试设置 `mesh.methods`、`platform` 或 `arch`
- **THEN** 配置在进入原生层前被拒绝，异常指出该 key 由框架管理

### Requirement: Java 配置兼容最低支持 Android 版本
在既有支持的 `arm64-v8a` 设备上，binding SHALL 从 Android API 24 起支持构造默认及自定义握手配置，不依赖 API 31 才提供的集合方法，也不得要求消费方另行配置 core library desugaring 才能执行配置构造。Android ABI 和 minSdk 声明 SHALL 保持一致。

#### Scenario: Android API 24 至 30 默认配置
- **WHEN** 未开启 core library desugaring 的 API 24 至 30 消费工程调用默认 Builder 的 build 和 toJson
- **THEN** 两个操作成功，不抛出 `NoSuchMethodError`

#### Scenario: 最低版本自定义配置
- **WHEN** 受支持的 API 24 消费工程添加 feature 和 metadata 并构造配置
- **THEN** 配置构造与序列化成功，集合快照仍满足隔离要求
