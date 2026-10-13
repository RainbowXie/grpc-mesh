# Spec Delta

## ADDED Requirements

### Requirement: 原生配置验证自定义握手 metadata
原生节点 SHALL 支持合法的 `node.features` 和 `node.metadata` 配置，保留 metadata value 的原始字符串。配置校验 SHALL 拒绝空白 key、带首尾空白的 key、非字符串 value，以及保留 key `mesh.methods`、`platform`、`arch`。通过 C ABI JSON 输入和直接 Rust facade 输入均 SHALL 遵守保留 key 规则；错误 SHALL 包含具体 key 或字段名，不得静默忽略、裁剪或替换非法值。C ABI 创建失败 SHALL 返回空句柄并提供现有配置错误状态与描述，不新增 ABI 符号。

#### Scenario: 合法 metadata 原值透传
- **WHEN** 宿主通过 C ABI 创建节点，metadata value 包含首尾空白或为空字符串
- **THEN** 创建成功且实际握手保留这些 value

#### Scenario: JSON 输入无法绕过保留字段保护
- **WHEN** C ABI JSON 的 `node.metadata` 包含 `mesh.methods`、`platform` 或 `arch`
- **THEN** 创建失败，返回空句柄及明确配置错误，节点不建立隧道

#### Scenario: 非法 key 和 value
- **WHEN** JSON 配置包含空白 key、带首尾空白的 key、null 或非字符串 value
- **THEN** 创建失败，错误包含相关 key 或字段名，没有静默修正

#### Scenario: 直接 Rust facade 无法绕过保护
- **WHEN** 宿主直接构造 Rust 配置并注入保留 metadata key
- **THEN** 节点在连接前以配置错误拒绝该输入，不生成受污染的握手
