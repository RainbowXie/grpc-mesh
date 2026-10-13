# Tasks

## 1. 基线与失败测试

- [x] 1.1 记录父仓、Node 子仓 HEAD/gitlink、工作区状态及审查基线，执行 GitNexus impact 检查 NodeConfig、配置解析和握手相关符号，产出 callers/processes/risk 及 UNKNOWN 补证记录，确认无未处理的影响分析阻塞。
- [x] 1.2 添加纯 Java 配置测试，使用标准 JSON 解析器覆盖默认字段、数组类型、转义、重复 key 与 Builder 快照隔离，并以 value 空白丢失、null 转空串、保留 key 可写反例确认整改前失败。
- [x] 1.3 添加 Rust/C ABI 配置失败测试，覆盖三个保留 key、空白/带首尾空白 key、null/非字符串 value 与直接构造/修改 Rust 配置；记录基线的伪造方法通告及测试失败输出。
- [x] 1.4 记录不启用 core library desugaring 的 arm64-v8a Android 12/API 31+ 消费工程与可用设备/镜像的 API 级别，确认真实 API 31 运行时是否可得；API 31 精确运行时缺失时如实记录为剩余风险而非跳过验收。用户已主动将最低支持版本由 API 24 调为 API 31，旧 API 24–30 验收未完成且不再作为门槛。

## 2. Java 配置修复

- [x] 2.1 用防御性复制与不可修改集合替换无条件 List.copyOf/Map.copyOf，验证默认及自定义配置构造成功、快照隔离测试通过且字节码不再引用两个 API 31 方法；该实现予保留，不因用户将下限提到 API 31 而重写。
- [x] 2.2 保留 metadata value 全部字符，拒绝 null、空白/带首尾空白 key 及 null value，保留空字符串和重复 key 最后赋值语义；验证每类输入的 JSON 内容或配置异常符合 node-java-binding delta。
- [x] 2.3 在 Java Builder 拒绝 mesh.methods/platform/arch，保留 feature 和数组序列化能力，并删除业务 Server 内部函数和不可证实因果注释；验证保留 key 异常含具体名称且合法 feature/metadata 测试通过。

## 3. 原生字段保护

- [x] 3.1 实现共享 metadata 校验，在 JSON 解析/配置验证与连接前握手边界拒绝非法及保留 key，验证 C ABI 返回空句柄和配置错误，直接构造或解析后修改 Rust 配置不能绕过保护。
- [x] 3.2 保持平台、架构和 mesh.methods 由框架生成，feature 继续使用既有规范化规则；验证合法 metadata 原值、feature 小写/去重、实际 registry 方法以及空 registry 场景均通过。
- [x] 3.3 检查文件行数与 C ABI 导出面，必要拆分只限校验模块并先分析影响；验证所有编辑文件低于 1000 行、header/symbol 合约通过且没有新增或删除 ABI v1 符号。

## 4. 集成与兼容性验收

- [x] 4.1 使用本仓完整测试 Server 捕获 Java→JNI→Rust 的真实 TLS/yamux 握手，断言自定义 feature、metadata 原值及注册/替换/注销后的方法清单，验证正常停止关闭；保留 key 测试确认拒绝且不建立连接。
- [x] 4.2 在受支持的 arm64-v8a Android 12/API 31+ 环境（不启用 core library desugaring）运行默认和自定义 build/toJson 测试，保存日志；执行 lint/API 引用检查。API 31 精确运行时不可用时如实记录剩余风险，不声称已实测最低版本。
- [x] 4.3 重新执行 JVM、Rust、C ABI harness/header/symbol 与既有 Android payload/并发/关闭回归，记录命令、输入、stdout/stderr、退出码和测试数量；确认没有用旧缓存或高版本设备结果替代新测试。

## 5. 文档、版本和可发布产物

- [x] 5.1 更新 Java README/API 注释和原生配置说明，包含通用 feature/metadata 示例、原值保留、三个保留 key、最低 Android 版本和迁移规则；逐项与 spec 对照，确保不宣称已验证外部业务筛选因果。
- [x] 5.2 选定下一个未使用 binding Alpha 版本，统一 Gradle/发布脚本/示例版本，用 clean-source archive 构建 AAR、原生库、header、manifest 和 SHA256SUMS，核验版本、父仓提交、Node gitlink 与全部哈希一致，不替换 Alpha2。
- [x] 5.3 用最终产物而非源码路径运行消费方 E2E，确认新增 Builder API 和原生保护来自同一版本，保存产物路径/哈希及通过日志；上传发布仅在用户另行授权后执行。

## 6. 审查与结单

- [ ] 6.1 执行代码审查与前提反证，重点挑战 Android 12/API 31+ 支持范围声明、所有入口保留 key 保护、metadata 逐字符保真及方法清单权威性；对阻塞 findings 修复并递归复审，提交前完成 GitNexus detect_changes。
- [ ] 6.2 核对父仓/子仓最终提交、gitlink、任务证据与产物 manifest，运行 openspec validate java-handshake-config-safety --strict 并完成实现验收；以 git 提交与测试证据结单，不再生成已删除的四角色回滚材料；未完成真实验证或版本交付时保持对应任务未勾选，不自动归档或发布。
