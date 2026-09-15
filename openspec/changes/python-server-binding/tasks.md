## 1. Go 侧 C ABI（grpc-mesh-server 子模块）

- [x] 1.1 新增 `cmd/meshlib/`（main 包）：`mesh_server_new/start/stop/free`、`mesh_invoke`、`mesh_list_nodes`、`mesh_free_string`、`mesh_last_error`，全部 `//export` + recover 兜底；配置经 `pkg/config` schema 的 JSON 解析，句柄内部持有 `*server.Server`
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
- [x] 3.2 `list_nodes()` 显示节点与方法清单；`invoke("calculator-service", "calculator.v1.Calculator/Add", proto字节)` 得到正确结果（10+5=15，与 Go 路径一致）
- [x] 3.3 `with` 退出后进程干净结束（无残留 goroutine/监听端口）；重复 `stop()` 无异常
- [x] 3.4 Python 侧示例脚本留档（`bindings/python/examples/calculator.py`）

## 4. 文档与收尾

- [x] 4.1 `bindings/python/README.md`：安装（wheel/源码）、快速开始、配置 schema 说明、平台矩阵、故障排查（库缺失/构建要求 Go ≥ 1.25）
- [x] 4.2 主 README 链接 Python binding 入口；openspec validate 通过
- [x] 4.3 分仓提交推送（server 子模块 + 父仓 + 指针）
