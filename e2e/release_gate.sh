#!/usr/bin/env bash
# 发布门禁：从干净的 git 世界重建发布产物并做安装态验收。
#
# 用法：  e2e/release_gate.sh [tag-or-HEAD]   （默认 HEAD）
# 产物：  /tmp/grpc-mesh-release-gate/
#
# 步骤（对应复审 round 3 的固化要求）：
#   1. git archive 干净树（排除一切未跟踪文件）
#   2. 构建 wheel/sdist
#   3. 断言：清单纯净（无 ELF）、purelib 元数据、wheel 源码与树逐字节一致
#   4. 断言：当前 libmesh.so 导出全部 9 个 ABI 符号
#   5. 全新 venv 安装 wheel + GRPC_MESH_LIB，跑安装态集成测试
#      （生命周期 / list_nodes / invoke / 错误语义 / 关闭态）
#
# 依赖：go、python3、可选真节点二进制（设置 GATE_NODE_BIN 时跑真调用，
#       否则跳过 invoke=15 断言只测无节点路径）。

set -eu

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REF="${1:-HEAD}"
WORK="${GATE_WORK:-/tmp/grpc-mesh-release-gate}"

fail() { echo "GATE FAIL: $1" >&2; exit 1; }
step() { echo "── $1"; }

rm -rf "$WORK" && mkdir -p "$WORK/src"

step "1/5 git archive $REF → 干净树"
git -C "$ROOT" archive "$REF" bindings/python | tar -x -C "$WORK/src"

step "2/5 构建 wheel/sdist"
(cd "$WORK/src/bindings/python" && python3 -m build --outdir "$WORK/dist" . >/dev/null)
WHL="$(ls "$WORK"/dist/grpc_mesh-*.whl | head -1)"
ls "$WORK"/dist >/dev/null

step "3/5 wheel 断言"
python3 - "$WHL" "$WORK/src/bindings/python" <<'EOF'
import pathlib, sys, zipfile
whl, src = sys.argv[1], pathlib.Path(sys.argv[2])
z = zipfile.ZipFile(whl)
names = z.namelist()
assert not any(n.endswith(('.so', '.whl')) for n in names), "清单纯净性失败"
meta = z.read([n for n in names if n.endswith('WHEEL')][0]).decode()
assert 'Root-Is-Purelib: true' in meta and 'py3-none-any' in meta, "purelib 元数据失败"
for f in ['grpc_mesh/_lib.py', 'grpc_mesh/__init__.py']:
    assert z.read(f) == (src / f).read_bytes(), f"{f} 与源码不一致"
lib = z.read('grpc_mesh/_lib.py').decode()
assert 'mesh_str_data' in lib and 'mesh_free_string' not in lib, "ABI 符号断言失败"
print("   清单纯净 + purelib + 源码一致 + 新 ABI ✓")
EOF

step "4/5 libmesh.so 符号断言"
SO="${GRPC_MESH_LIB:-$ROOT/grpc-mesh-server/libmesh.so}"
python3 - "$SO" <<'EOF'
import subprocess, sys
out = subprocess.run(["nm", "-D", sys.argv[1]], capture_output=True, text=True).stdout
exported = {l.split()[-1] for l in out.splitlines() if " T mesh_" in l}
expected = {"mesh_server_new", "mesh_server_start", "mesh_server_stop",
            "mesh_server_free", "mesh_invoke", "mesh_list_nodes",
            "mesh_str_data", "mesh_str_release", "mesh_last_error"}
missing = expected - exported
assert not missing, f"缺少导出符号: {missing}"
print(f"   9 个 ABI 符号齐全 ✓")
EOF

step "5/5 全新 venv 安装态集成"
python3 -m venv "$WORK/venv"
"$WORK/venv/bin/pip" install -q "$WHL"
GRPC_MESH_LIB="$SO" "$WORK/venv/bin/python" - <<'EOF'
import os
from grpc_mesh import MeshServer, MeshError
cfg = {"server": {"grpc_address": "127.0.0.1:0", "metrics_address": "127.0.0.1:0"},
       "listener": {"address": "127.0.0.1:0"}}
mesh = MeshServer(cfg)
mesh.start()
assert mesh.list_nodes() == []
r = mesh.invoke("no-such-node", "a.B/C", b"", 1000)
assert not r.success and r.error["code"] == "DIAL_FAILED"
mesh.stop()
mesh.close()
for op in (lambda: mesh.list_nodes(), lambda: mesh.invoke("x", "y", b""),
           mesh.start):
    try:
        op(); raise AssertionError("closed server must reject use")
    except MeshError:
        pass
print("   安装态生命周期/invoke 错误语义/关闭态 ✓")

node_bin = os.environ.get("GATE_NODE_BIN")
if node_bin:
    import json, struct, subprocess, time
    enc = lambda a, b: b"\x09" + struct.pack("<d", a) + b"\x11" + struct.pack("<d", b)
    cfgp = json.load(open(os.environ["GATE_NODE_CFG"]))
    # 节点专属配置：监听地址对齐本 gate 的 8443；经 CONFIG_PATH/CA_CERT_PATH 下发，
    # 否则节点按默认路径找配置并以环境变量回退（缺 token 直接退出）。
    gate_cfg = dict(cfgp)
    gate_cfg["server"] = dict(cfgp["server"], address="127.0.0.1:8443")
    node_cfg_path = os.path.join(os.environ.get("GATE_WORK", "/tmp/grpc-mesh-release-gate"), "node-gate.json")
    with open(node_cfg_path, "w") as f:
        json.dump(gate_cfg, f)
    node_env = {**os.environ,
                "CONFIG_PATH": node_cfg_path,
                "CA_CERT_PATH": os.environ["GATE_CA"]}
    proc = subprocess.Popen([node_bin], env=node_env,
                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    try:
        cfg2 = {"server": {"grpc_address": "127.0.0.1:0", "metrics_address": "127.0.0.1:0"},
                "listener": {"address": "127.0.0.1:8443",
                              "cert_file": os.environ["GATE_CERT"],
                              "key_file": os.environ["GATE_KEY"]},
                "auth": {"enabled": True,
                          "node_tokens": {cfgp["node"]["id"]: cfgp["node"]["token"]}}}
        with MeshServer(cfg2) as m:
            for _ in range(50):
                ns = m.list_nodes()
                if any(n["node_id"] == cfgp["node"]["id"] for n in ns): break
                time.sleep(0.2)
            else: raise AssertionError("node never connected")
            r = m.invoke(cfgp["node"]["id"], "calculator.v1.Calculator/Add", enc(10, 5), 5000)
            assert r.success and struct.unpack("<d", r.result[1:9])[0] == 15.0
        print("   真节点 invoke=15 ✓")
    finally:
        proc.terminate(); proc.wait()
EOF

echo
echo "RELEASE GATE PASSED（ref=$REF）"
