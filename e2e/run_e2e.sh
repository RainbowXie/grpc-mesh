#!/usr/bin/env bash
# gRPC-Mesh 全链路端到端测试。
#
# 覆盖：心跳消费、通用/类型化双调用路径、gRPC 错误语义、身份绑定
# （正向 + 冒充拒绝）、方法上报、Python binding（含 GIL 释放）、断线
# 重连、TLS 永久错误分类、死节点回收。
#
# 用法：  e2e/run_e2e.sh
# 产物：  日志与证书写入 $E2E_WORK（默认 /tmp/grpc-mesh-e2e-full）
# 依赖：  Go >= 1.25、Rust/cargo、python3；首次构建需要网络拉依赖
# 注意：  过程中会临时修改 grpc-mesh-node 的两个嵌入配置文件用于编译
#         reverse_gateway（结束自动恢复；中断由 trap 兜底）。

set -u

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK="${E2E_WORK:-/tmp/grpc-mesh-e2e-full}"
TOKEN="waemu_e2e_token_000000000000000000"
NODE_BIN_ID="calculator-service"
GW_NODE_ID="gateway-node"

# 端口分配（避免冲突）
A_TUN=18501; A_GRPC=18502; A_MET=18503   # serverA（主测服务器，证书 A）
B_TUN=18511                                # serverB（不匹配证书，TLS 分类用）
C_TUN=18521; C_GRPC=18522; C_MET=18523; C_HTTP=18524  # calc-client 内嵌
P_TUN=18531; P_GRPC=18532; P_MET=18533    # Python binding

PASS_N=0; FAIL_N=0; FAILED_CASES=()
PIDS=()

pass() { PASS_N=$((PASS_N+1)); echo "  PASS  $1"; }
fail() { FAIL_N=$((FAIL_N+1)); FAILED_CASES+=("$1"); echo "  FAIL  $1"; }

check() { # check <描述> <命令...>：命令退出 0 即通过
  local desc=$1; shift
  if "$@" >/dev/null 2>&1; then pass "$desc"; else fail "$desc"; fi
}

wait_until() { # wait_until <超时秒> <描述> <命令...>
  local timeout=$1 desc=$2; shift 2
  local deadline=$((SECONDS + timeout))
  while (( SECONDS < deadline )); do
    if "$@" >/dev/null 2>&1; then pass "$desc"; return 0; fi
    sleep 0.3
  done
  fail "$desc（${timeout}s 超时）"; return 1
}

cleanup() {
  for pid in "${PIDS[@]:-}"; do kill "$pid" 2>/dev/null; done
  restore_node_configs
}
trap cleanup EXIT

restore_node_configs() {
  if [[ -f "$ROOT/grpc-mesh-node/config/ca.crt.e2e-bak" ]]; then
    mv "$ROOT/grpc-mesh-node/config/ca.crt.e2e-bak" "$ROOT/grpc-mesh-node/config/ca.crt"
  fi
  if [[ -f "$ROOT/grpc-mesh-node/config/reverse_gateway_config.json.e2e-bak" ]]; then
    mv "$ROOT/grpc-mesh-node/config/reverse_gateway_config.json.e2e-bak" \
       "$ROOT/grpc-mesh-node/config/reverse_gateway_config.json"
  fi
}

gen_certs() {
  (cd "$WORK" &&
    openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:P-256 \
      -keyout caA.key -out caA.crt -days 1 -nodes -subj "/CN=e2e-ca-a" 2>/dev/null &&
    openssl req -newkey ec -pkeyopt ec_paramgen_curve:P-256 \
      -keyout serverA.key -out serverA.csr -nodes -subj "/CN=localhost" 2>/dev/null &&
    printf "subjectAltName=DNS:localhost,IP:127.0.0.1\n" > sA.ext &&
    openssl x509 -req -in serverA.csr -CA caA.crt -CAkey caA.key -CAcreateserial \
      -out serverA.crt -days 1 -extfile sA.ext 2>/dev/null &&
    # 证书 B：独立 CA，节点信任 A 时应得到 UnknownIssuer（永久错误）
    openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:P-256 \
      -keyout caB.key -out caB.crt -days 1 -nodes -subj "/CN=e2e-ca-b" 2>/dev/null &&
    openssl req -newkey ec -pkeyopt ec_paramgen_curve:P-256 \
      -keyout serverB.key -out serverB.csr -nodes -subj "/CN=localhost" 2>/dev/null &&
    printf "subjectAltName=DNS:localhost,IP:127.0.0.1\n" > sB.ext &&
    openssl x509 -req -in serverB.csr -CA caB.crt -CAkey caB.key -CAcreateserial \
      -out serverB.crt -days 1 -extfile sB.ext 2>/dev/null)
}

start_calc_service() { # start_calc_service <node_id> <tunnel_port> <log>
  local node_id=$1 tun=$2 log=$3
  cat > "$WORK/node-$node_id.json" <<EOF
{"server": {"address": "127.0.0.1:$tun", "tls": {"server_name": "localhost"}},
 "node": {"id": "$node_id", "token": "$TOKEN"}}
EOF
  CONFIG_PATH="$WORK/node-$node_id.json" CA_CERT_PATH="$WORK/caA.crt" \
    "$ROOT/demos/calculator-service/target/debug/calculator-service" >"$log" 2>&1 &
  PIDS+=($!)
}

echo "== gRPC-Mesh 端到端测试 $(date '+%F %T') =="
mkdir -p "$WORK"

# 证书必须先于 reverse_gateway 构建生成：其 CA 在编译期嵌入二进制
echo "-- [0/10] 证书"
gen_certs && pass "生成测试证书（A/B 两套 CA）" || { fail "生成测试证书"; exit 1; }

echo "-- [1/10] 构建产物"
( cd "$ROOT/grpc-mesh-server" && go build -o "$WORK/mesh-server" ./cmd/server ) \
  && pass "build mesh-server" || fail "build mesh-server"
( GOPROXY=https://goproxy.cn,direct cd "$ROOT/demos/calculator-client" && \
  go build -o "$WORK/calc-client" . ) && pass "build calc-client" || fail "build calc-client"
( cd "$ROOT/demos/calculator-service" && cargo build -q ) \
  && pass "build calculator-service" || fail "build calculator-service"
( cd "$ROOT/grpc-mesh-server" && make build-meshlib >/dev/null 2>&1 && \
  mkdir -p "$ROOT/bindings/python/grpc_mesh/_native/linux-x86_64" && \
  cp libmesh.so "$ROOT/bindings/python/grpc_mesh/_native/linux-x86_64/" ) \
  && pass "build libmesh.so (python)" || fail "build libmesh.so (python)"

# reverse_gateway：临时替换嵌入配置（地址指向 serverA、固定 node id、token、CA A）
GW_CFG="$ROOT/grpc-mesh-node/config/reverse_gateway_config.json"
GW_CA="$ROOT/grpc-mesh-node/config/ca.crt"
cp "$GW_CFG" "$GW_CFG.e2e-bak"; cp "$GW_CA" "$GW_CA.e2e-bak"
python3 - "$GW_CFG" "$A_TUN" "$GW_NODE_ID" "$TOKEN" <<'EOF'
import json, sys
p, tun, nid, tok = sys.argv[1:]
cfg = json.load(open(p))
cfg["server"]["address"] = f"127.0.0.1:{tun}"
cfg["node"]["id"] = nid
cfg["node"]["token"] = tok
open(p, "w").write(json.dumps(cfg, indent=2))
EOF
cp "$WORK/caA.crt" "$GW_CA"
( cd "$ROOT/grpc-mesh-node" && \
  cargo build -q --target x86_64-unknown-linux-gnu --bin reverse_gateway ) \
  && pass "build reverse_gateway" || fail "build reverse_gateway"
restore_node_configs

echo "-- [2/10] 证书（已在构建前生成）"
check "证书文件就位" test -f "$WORK/caA.crt" -a -f "$WORK/serverB.crt"

echo "-- [3/10] serverA 启动 + 节点接入 + 心跳"
cat > "$WORK/serverA.yaml" <<EOF
server: {grpc_address: "127.0.0.1:$A_GRPC", metrics_address: "127.0.0.1:$A_MET"}
listener:
  address: "127.0.0.1:$A_TUN"
  cert_file: "$WORK/serverA.crt"
  key_file: "$WORK/serverA.key"
logging: {level: "debug"}
auth:
  enabled: true
  node_tokens:
    $NODE_BIN_ID: "$TOKEN"
    $GW_NODE_ID: "$TOKEN"
EOF
"$WORK/mesh-server" --config "$WORK/serverA.yaml" > "$WORK/serverA.log" 2>&1 &
PIDS+=($!); SERVER_A=$!
wait_until 5 "serverA 监听启动" grep -aq "tunnel listener started" "$WORK/serverA.log"

start_calc_service "$NODE_BIN_ID" "$A_TUN" "$WORK/nodeA.log"
wait_until 10 "节点握手通过 node_tokens 认证" \
  grep -aq "session established.*$NODE_BIN_ID" "$WORK/serverA.log"
wait_until 20 "心跳被控制面消费（>=2 帧）" \
  bash -c "test \$(grep -ac 'heartbeat received' '$WORK/serverA.log') -ge 2"

echo "-- [4/10] 身份绑定：冒充拒绝"
start_calc_service "impostor-node" "$A_TUN" "$WORK/node-impostor.log"
wait_until 10 "冒充节点被拒绝（token 与 node_id 不匹配）" \
  grep -aq "not authorized" "$WORK/serverA.log"
check "冒充节点未注册会话" \
  bash -c "! grep -aq 'registered session.*impostor' '$WORK/serverA.log'"

echo "-- [5/10] 死节点回收（读循环 EOF 路径）"
# 冒充节点未注册会话，先收掉；再 SIGKILL 已注册的合法节点验证回收
kill -9 "${PIDS[${#PIDS[@]}-1]}" 2>/dev/null   # impostor
kill -9 "${PIDS[${#PIDS[@]}-2]}" 2>/dev/null   # 合法 calculator-service
wait_until 10 "节点断开后会话被移除" \
  grep -aq "removed session" "$WORK/serverA.log"

echo "-- [6/10] 内嵌 server（Go）双调用路径"
cat > "$WORK/calc-client.yaml" <<EOF
app: {listen_address: "127.0.0.1:$C_HTTP", target_node_id: "$NODE_BIN_ID"}
server: {grpc_address: "127.0.0.1:$C_GRPC", metrics_address: "127.0.0.1:$C_MET"}
listener:
  address: "127.0.0.1:$C_TUN"
  cert_file: "$WORK/serverA.crt"
  key_file: "$WORK/serverA.key"
logging: {level: "info"}
auth:
  enabled: true
  node_tokens: {$NODE_BIN_ID: "$TOKEN"}
EOF
CONFIG_PATH="$WORK/calc-client.yaml" "$WORK/calc-client" > "$WORK/calc-client.log" 2>&1 &
PIDS+=($!); CALC_CLIENT=$!
wait_until 5 "calc-client（内嵌 server）启动" grep -aq "Mesh server started" "$WORK/calc-client.log"
start_calc_service "$NODE_BIN_ID" "$C_TUN" "$WORK/nodeC.log"
wait_until 10 "节点接入内嵌 server" grep -aq "session established" "$WORK/calc-client.log"
wait_until 5 "方法清单上报（/nodes.methods）" \
  bash -c "curl -s http://127.0.0.1:$C_HTTP/nodes | grep -aq calculator.v1.Calculator/Add"
ADD_BODY='{"operation":"add","a":10,"b":5}'
R_GENERIC=$(curl -s -X POST "http://127.0.0.1:$C_HTTP/calculate" -H 'Content-Type: application/json' -d "$ADD_BODY")
check "通用 Invoke 路径 10+5=15" test "$R_GENERIC" = '{"result":15}'
R_TYPED=$(curl -s -X POST "http://127.0.0.1:$C_HTTP/calculate-typed" -H 'Content-Type: application/json' -d "$ADD_BODY")
check "类型化直调路径 10+5=15" test "$R_TYPED" = '{"result":15}'
check "类型化除零保留 InvalidArgument" bash -c \
  "curl -s -X POST http://127.0.0.1:$C_HTTP/calculate-typed -H 'Content-Type: application/json' -d '{\"operation\":\"divide\",\"a\":1,\"b\":0}' | grep -aq 'InvalidArgument'"
check "通用路径除零为包装错误" bash -c \
  "curl -s -X POST http://127.0.0.1:$C_HTTP/calculate -H 'Content-Type: application/json' -d '{\"operation\":\"divide\",\"a\":1,\"b\":0}' | grep -aq 'Division by zero'"

echo "-- [7/10] Python binding（真实 server + Rust 节点 + GIL）"
cat > "$WORK/py_e2e.py" <<'EOF'
import os, signal, struct, subprocess, sys, threading, time
sys.path.insert(0, os.environ["PY_PKG"])
from grpc_mesh import MeshServer

node_id, tun, grpc_p, met = sys.argv[1:5]
enc = lambda a, b: b"\x09" + struct.pack("<d", a) + b"\x11" + struct.pack("<d", b)
dec = lambda d: struct.unpack("<d", d[1:9])[0]

node = subprocess.Popen([os.environ["NODE_BIN"]], env={
    **os.environ, "CONFIG_PATH": os.environ["NODE_CFG"], "CA_CERT_PATH": os.environ["CA"]},
    stdout=open(os.environ["NODE_LOG"], "w"), stderr=subprocess.STDOUT)
try:
    cfg = {"server": {"grpc_address": f"127.0.0.1:{grpc_p}", "metrics_address": f"127.0.0.1:{met}"},
           "listener": {"address": f"127.0.0.1:{tun}",
                        "cert_file": os.environ["CERT"], "key_file": os.environ["KEY"]},
           "auth": {"enabled": True, "node_tokens": {node_id: os.environ["TOKEN"]}}}
    with MeshServer(cfg) as mesh:
        for _ in range(50):
            nodes = mesh.list_nodes()
            if any(n["node_id"] == node_id for n in nodes): break
            time.sleep(0.2)
        else: sys.exit("node never connected")
        assert "calculator.v1.Calculator/Add" in nodes[0]["methods"], "methods not reported"
        r = mesh.invoke(node_id, "calculator.v1.Calculator/Add", enc(10, 5), 5000)
        assert r.success and dec(r.result) == 15.0, f"invoke failed: {r.error}"

        # GIL：SIGSTOP 节点制造慢调用，验证计数线程不被阻塞
        os.kill(node.pid, signal.SIGSTOP); time.sleep(0.3)
        ticks = [0]; stop = threading.Event()
        t = threading.Thread(target=lambda: [ticks.__setitem__(0, ticks[0]+1) for _ in iter(stop.is_set, True)])
        t.start(); t0 = time.time()
        mesh.invoke(node_id, "calculator.v1.Calculator/Add", enc(1, 1), 2500)
        dt = time.time() - t0; stop.set(); t.join()
        assert dt >= 2.0 and ticks[0] > 100_000, f"dt={dt} ticks={ticks[0]}"
        os.kill(node.pid, signal.SIGCONT)

        # 死节点回收：SIGKILL 后 list_nodes 变空（读循环 EOF）
        node.kill(); node.wait()
        for _ in range(50):
            if not mesh.list_nodes(): break
            time.sleep(0.2)
        else: sys.exit("dead node session not removed")
    print("PY_E2E_OK")
finally:
    if node.poll() is None: node.kill()
EOF
cat > "$WORK/node-py.json" <<EOF
{"server": {"address": "127.0.0.1:$P_TUN", "tls": {"server_name": "localhost"}},
 "node": {"id": "$NODE_BIN_ID", "token": "$TOKEN"}}
EOF
PY_PKG="$ROOT/bindings/python" NODE_BIN="$ROOT/demos/calculator-service/target/debug/calculator-service" \
  NODE_CFG="$WORK/node-py.json" CA="$WORK/caA.crt" CERT="$WORK/serverA.crt" KEY="$WORK/serverA.key" \
  TOKEN="$TOKEN" NODE_LOG="$WORK/nodeP.log" \
  python3 "$WORK/py_e2e.py" "$NODE_BIN_ID" "$P_TUN" "$P_GRPC" "$P_MET" > "$WORK/py_e2e.out" 2>&1
# nodeC 占用了同名配置文件，py 用独立配置
check "Python binding 全场景（接入/方法上报/调用/GIL/死节点回收/干净停止）" \
  grep -aq "PY_E2E_OK" "$WORK/py_e2e.out" || { echo "---- py 输出 ----"; cat "$WORK/py_e2e.out"; }

echo "-- [8/10] 断线重连（reverse_gateway 监督循环）"
NODE_C=${PIDS[${#PIDS[@]}-1]}
kill "$NODE_C" 2>/dev/null   # 释放（py 场景已杀，保险）
"$ROOT/grpc-mesh-node/target/x86_64-unknown-linux-gnu/debug/reverse_gateway" \
  > "$WORK/gateway.log" 2>&1 &
PIDS+=($!); GW=$!
wait_until 15 "gateway 接入 serverA" grep -aq "session established.*$GW_NODE_ID" "$WORK/serverA.log"
kill "$SERVER_A"; sleep 2
check "server 死后 gateway 存活并进入重连" \
  bash -c "kill -0 $GW && grep -aq 'reconnecting' '$WORK/gateway.log'"
"$WORK/mesh-server" --config "$WORK/serverA.yaml" > "$WORK/serverA2.log" 2>&1 &
PIDS+=($!); SERVER_A=$!
wait_until 15 "server 重启后 gateway 自动重连" \
  grep -aq "session established.*$GW_NODE_ID" "$WORK/serverA2.log"

echo "-- [9/10] TLS 永久错误分类（错 CA 不无限重试）"
kill "$SERVER_A"; sleep 1
# serverB 必须复用 serverA 的隧道端口：gateway 嵌入的连接地址不变，
# 换上不匹配证书的 server 才能让它撞上 UnknownIssuer
cat > "$WORK/serverB.yaml" <<EOF
server: {grpc_address: "127.0.0.1:$((B_TUN+1))", metrics_address: "127.0.0.1:$((B_TUN+2))"}
listener:
  address: "127.0.0.1:$A_TUN"
  cert_file: "$WORK/serverB.crt"
  key_file: "$WORK/serverB.key"
logging: {level: "info"}
EOF
"$WORK/mesh-server" --config "$WORK/serverB.yaml" > "$WORK/serverB.log" 2>&1 &
PIDS+=($!)
wait_until 15 "gateway 遇错 CA 立即以 Config 错误退出（不重试风暴）" \
  bash -c "! kill -0 $GW 2>/dev/null && grep -aq 'TLS handshake rejected' '$WORK/gateway.log'"

echo "-- [10/10] 清理"
for pid in "${PIDS[@]:-}"; do kill "$pid" 2>/dev/null; done
PIDS=()

echo
echo "================ 结果 ================"
echo "PASS: $PASS_N  FAIL: $FAIL_N"
if (( FAIL_N > 0 )); then
  printf '失败用例:\n'; printf '  - %s\n' "${FAILED_CASES[@]}"
  echo "日志目录: $WORK"
  exit 1
fi
echo "全部通过。日志目录: $WORK"
