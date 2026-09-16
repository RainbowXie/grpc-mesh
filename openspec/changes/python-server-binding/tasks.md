## 1. Go 侧 C ABI（grpc-mesh-server 子模块）

- [x] 1.1 新增 `cmd/meshlib/`（main 包）：`mesh_server_new/start/stop/free`、`mesh_invoke`、`mesh_list_nodes`、`mesh_str_data`/`mesh_str_release`（字符串句柄）、`mesh_last_error`，全部 `//export` + recover 兜底；配置经 `pkg/config` schema 的 JSON 解析，句柄内部持有 `*server.Server`
- [x] 1.2 `InvokeResponse`/节点列表的 JSON 序列化（字节字段 base64），错误写入线程局部 slot 供 `mesh_last_error` 读取
- [x] 1.3 Go 单测：以 C 调用等价方式覆盖 spec 场景（非法配置、启动端口冲突、stop 幂等、未注册节点 DIAL_FAILED、重复释放 no-op、并发 invoke）
- [x] 1.4 `make build-meshlib`：`CGO_ENABLED=1 go build -buildmode=c-shared`，产出 `.so`/`.h`；`go build ./... && go vet ./... && go test ./...` 全绿

## 2. Python 包（父仓库 bindings/python/）

- [x] 2.1 ctypes 加载层：`GRPC_MESH_LIB` 环境变量 → 包内 `_native/<plat>/` → 报错信息附构建说明；函数签名绑定
- [x] 2.2 `MeshServer`：dict 配置序列化、上下文管理器（`__enter__` start / `__exit__` stop）、句柄与异常（`MeshError`、`MeshLibraryNotFound`）
- [x] 2.3 `invoke(peer_id, method, payload, timeout_ms)` 返回 dataclass（success/result/error），`list_nodes()` 返回含 `methods` 的字典列表；base64 解码
- [x] 2.4 单测：无库报错路径 + mock 库签名；阻塞调用释放 GIL 的双线程测试（spec 场景）

## 3. 端到端验收

- [x] 3.1 环境搭建：`make build-meshlib` 产 `.so` → Python `MeshServer` 启动（测试 CA 证书 + `auth.node_tokens`）+ Rust calculator-service 节点接入
- [x] 3.2 `list_nodes()` 显示节点与方法清单；`invoke("calculator-service", "calculator.v1.Calculator/Add", proto 字节)` 得到正确结果（10+5=15，与 Go 路径一致）
- [x] 3.3 `with` 退出后进程干净结束（无残留 goroutine/监听端口）；重复 `stop()` 无异常
- [x] 3.4 Python 侧示例脚本留档（`bindings/python/examples/calculator.py`）

## 4. 文档与收尾

- [x] 4.1 `bindings/python/README.md`：安装（wheel/源码）、快速开始、配置 schema 说明、平台矩阵、故障排查（库缺失/构建要求 Go ≥ 1.25）
- [x] 4.2 主 README 链接 Python binding 入口；openspec validate 通过
- [x] 4.3 分仓提交推送（server 子模块 + 父仓 + 指针）

## 5. 复审修复（round 1）

- [x] 5.1 CRITICAL：字符串 ABI 由指针释放改为单调 id 句柄（`mesh_str_data`/`mesh_str_release`），消除地址复用下陈旧释放误杀活字符串的缺陷；新增"释放 A、分配 B 复用地址、再释放 A、B 仍有效"回归测试（cmd/meshlib）
- [x] 5.2 MAJOR：收缩分发承诺——pyproject 不再随 wheel 捆绑 `.so`（消除 py3-none-any 含 ELF 的矛盾），README/proposal/design 的多平台矩阵与 wheel 表述改为"后续变更"；当前为源码树 + 运行时定位（GRPC_MESH_LIB / _native/<platform>）
- [x] 5.3 MINOR：`_decode_error` 窄化异常捕获（ValueError/binascii.Error）并将畸形 base64 转为 MeshError；新增畸形/合法/空 details 三例单测
- [x] 5.4 MINOR：两个 change 文档的中英文间距清扫（proto 与汉字间补空格等）
- [x] 5.5 回归：Go 测试（-race）、Python 单测 9/9、全量 e2e 25 项重跑通过

## 6. 复审修复（round 2）

- [x] 6.1 CRITICAL 收据：陈旧 wheel 已于 round-1 后用 git-filter-repo 从全部历史抹除（git log/ls-files 双验证），并以最强形式重建收据——`git archive HEAD` 干净树构建 wheel、解包断言（清单纯净、purelib 元数据、新 ABI 符号、与源码逐字节一致）、全新 venv 安装后跑生命周期/方法上报/invoke/错误语义集成测试通过。仓库不再提交任何 wheel（dist/ 已忽略），分发走 GitHub Release。
- [x] 6.2 MAJOR：版本号 0.1.0a2 已提交（含发布 tag）；未跟踪文件逐项处理——AGENTS.md 与 .agents/ 技能、openspec/config.yaml 入库，CLAUDE.md 去重为指向 AGENTS.md 的单行指针，.gitignore 的 .gitnexus 行入库。git status 仅剩本 change 的修复文件与并行会话的 node-ffi-java-binding 草稿（不属本 change，不动）。
- [x] 6.3 MINOR：_lib.py 模块文档改为"用户自行放置或从源码/Release 提供"；design.md 残留的矩阵措辞（Non-Goals 与 cgo 风险两处）统一为"当前平台本机构建，多平台矩阵属后续 change"。
- [x] 6.4 MINOR：tasks.md 双反引号修正；design.md 手动换行段落恢复单行；对全部相关 Markdown 执行连续中文短行检测，0 候选。
- [x] 6.5 回归：干净世界安装态集成通过后，重跑全量 e2e 与 Go/Python 测试，openspec validate 通过。
