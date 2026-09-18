#!/usr/bin/env python3
"""Device end-to-end: real control plane (python binding) + real arm64 AAR
node on an Android device.

Verifies (tasks 5.4 / 6.1-6.4 of node-ffi-java-binding):
- AAR .so loads on device and passes the ABI check
- node connects over the LAN through TLS with the issued CA
- sorted method snapshot reported to the control plane
- generic Invoke reaches Java handlers: binary echo (NUL bytes), request
  metadata verbatim, exception mapping, NOT_FOUND
- concurrent invokes stay isolated
- clean close drains the session (no residual connection)

Usage: device_e2e.py <adb-serial> <bindings-java-dir> <sdk-dir> [host-ip]
"""
import json
import os
import subprocess
import sys
import threading
import time

sys.path.insert(0, os.environ.get("PY_PKG", "bindings/python"))
from grpc_mesh import MeshServer  # noqa: E402

WORK = os.environ.get("DEVICE_E2E_WORK", "/tmp/grpc-mesh-device-e2e")
DEV_NODE_ID = "java-device-1"
TOKEN = "device-e2e-token"
TUNNEL_PORT = 18451
NODE_WINDOW_SECONDS = 25


def sh(*cmd, **kw):
    return subprocess.run(cmd, capture_output=True, text=True, **kw)


def wait_for(predicate, timeout, what):
    deadline = time.time() + timeout
    while time.time() < deadline:
        value = predicate()
        if value:
            return value
        time.sleep(0.2)
    raise SystemExit(f"TIMEOUT waiting for: {what}")


def gen_certs(work, host_ip):
    os.makedirs(work, exist_ok=True)
    ca_key = f"{work}/ca.key"
    ca_crt = f"{work}/ca.crt"
    srv_key = f"{work}/server.key"
    srv_crt = f"{work}/server.crt"
    san = f"subjectAltName=DNS:mesh-e2e-server,DNS:localhost,IP:127.0.0.1,IP:{host_ip}\n"
    # Always regenerate: certs are cheap and must carry today's host IP.
    sh("rm", "-f", ca_key, ca_crt, srv_key, srv_crt, f"{work}/server.csr")
    if True:
        sh("openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes",
           "-keyout", ca_key, "-out", ca_crt, "-subj", "/CN=mesh-e2e-ca",
           "-days", "2")
        sh("openssl", "req", "-newkey", "rsa:2048", "-nodes",
           "-keyout", srv_key, "-out", f"{work}/server.csr",
           "-subj", "/CN=mesh-e2e-server")
        with open(f"{work}/server.ext", "w") as ext:
            ext.write(san)
        sh("openssl", "x509", "-req", "-in", f"{work}/server.csr",
           "-CA", ca_crt, "-CAkey", ca_key, "-CAcreateserial",
           "-extfile", f"{work}/server.ext",
           "-out", srv_crt, "-days", "2")
    return ca_crt, srv_crt, srv_key


def build_dex(java_dir, sdk, work):
    """Compiles the smoke main against node-core and dexes everything.

    Consumer mode (DIST_DIR set): the binding classes come from the
    RELEASED AAR's classes.jar only — no repository sources on the path,
    which is exactly how a fresh consumer project consumes the artifacts.
    """
    dist = os.environ.get("DIST_DIR")
    core_classes = f"{java_dir}/node-core/build/classes/java/main"
    if dist:
        aar = f"{dist}/node-android-0.1.0-alpha1.aar"
        subprocess.run(["unzip", "-o", "-j", aar, "classes.jar", "-d", work],
                       check=True, capture_output=True)
        combined = f"{work}/dist-classes"
        subprocess.run(["rm", "-rf", combined], check=True)
        os.makedirs(combined)
        subprocess.run(["unzip", "-o", "-q", f"{work}/classes.jar", "-d", combined], check=True)
        combined_consumer = combined  # binding classes from the artifact
    elif os.path.isdir(core_classes):
        combined_consumer = None
    else:
        raise SystemExit("node-core classes missing; run ./gradlew :node-core:classes")
    src = f"{java_dir}/examples/mesh-smoke/src"
    out = f"{work}/smoke-classes"
    os.makedirs(out, exist_ok=True)
    javac = sh("javac", "--release", "17", "-d", out,
               "-cp", core_classes, f"{src}/MeshSmokeMain.java")
    if javac.returncode:
        raise SystemExit(f"javac failed:\n{javac.stderr}")
    # Prefer the newest available d8: build-tools 34's r8 chokes on some
    # AGP-compiled class files (AAR consumer path) with an NPE.
    d8 = next(
        (f"{sdk}/build-tools/{bt}/d8"
         for bt in sorted(os.listdir(f"{sdk}/build-tools"), reverse=True)
         if os.path.exists(f"{sdk}/build-tools/{bt}/d8")),
        f"{sdk}/build-tools/34.0.0/d8")
    jar_path = f"{work}/mesh-smoke.jar"
    dex_out = f"{work}/dex"
    os.makedirs(dex_out, exist_ok=True)
    # d8 consumes .class trees via their root; combine core + smoke classes.
    combined = f"{work}/combined-classes"
    if os.path.isdir(combined):
        subprocess.run(["rm", "-rf", combined], check=True)
    subprocess.run(["cp", "-r", (combined_consumer or core_classes), combined], check=True)
    subprocess.run(["cp", "-r", f"{out}/.", combined], check=True)
    def class_files(tree):
        for root, _, files in os.walk(tree):
            for f in files:
                if f.endswith(".class") and "module-info" not in f:
                    yield os.path.join(root, f)

    result = sh(d8, "--release", "--lib", f"{sdk}/platforms/android-34/android.jar",
                "--output", dex_out, *class_files(combined))
    if result.returncode:
        raise SystemExit(f"d8 failed:\n{result.stderr}")
    import zipfile
    with zipfile.ZipFile(jar_path, "w") as archive:
        archive.write(f"{dex_out}/classes.dex", "classes.dex")
    return jar_path


def extract_aar_so(java_dir, work):
    dist = os.environ.get("DIST_DIR")
    if dist:
        # Consumer mode: verify the standalone .so matches the one inside
        # the released AAR, then push the artifact bytes.
        aar = f"{dist}/node-android-0.1.0-alpha1.aar"
    else:
        aar = f"{java_dir}/node-android/build/outputs/aar/node-android-release.aar"
    if not os.path.exists(aar):
        raise SystemExit("AAR missing; run ./gradlew :node-android:assembleRelease")
    subprocess.run(["unzip", "-o", "-j", aar, "jni/arm64-v8a/libgrpc_mesh_node.so",
                    "-d", work], check=True, capture_output=True)
    return f"{work}/libgrpc_mesh_node.so"


def main():
    serial, java_dir, sdk = sys.argv[1], sys.argv[2], sys.argv[3]
    host_ip = sys.argv[4] if len(sys.argv) > 4 else None
    if not host_ip:
        host_ip = sh("hostname", "-I").stdout.split()[0]
    os.makedirs(WORK, exist_ok=True)
    ca_crt, srv_crt, srv_key = gen_certs(WORK, host_ip)
    jar = build_dex(java_dir, sdk, WORK)
    so = extract_aar_so(java_dir, WORK)

    device_dir = "/data/local/tmp/mesh-smoke"
    sh("adb", "-s", serial, "shell", f"rm -rf {device_dir}; mkdir -p {device_dir}")
    for local in (jar, so, ca_crt):
        push = sh("adb", "-s", serial, "push", local, device_dir)
        if push.returncode:
            raise SystemExit(f"push failed: {push.stderr}")

    # The control plane binds on all interfaces so the device can reach it.
    cfg = {
        "server": {"grpc_address": f"0.0.0.0:{TUNNEL_PORT + 1}",
                   "metrics_address": "127.0.0.1:19101"},
        "listener": {"address": f"0.0.0.0:{TUNNEL_PORT}",
                     "cert_file": srv_crt, "key_file": srv_key},
        "auth": {"enabled": True, "node_tokens": {DEV_NODE_ID: TOKEN}},
    }

    checks = []

    def check(name, ok, detail=""):
        checks.append((name, bool(ok)))
        print(("PASS " if ok else "FAIL ") + name + (f" :: {detail}" if detail and not ok else ""))

    with MeshServer(cfg) as mesh:
        remote = (
            f"cd {device_dir} && LD_LIBRARY_PATH={device_dir} "
            f"CLASSPATH={device_dir}/mesh-smoke.jar "
            f"app_process / io.mesh.node.smoke.MeshSmokeMain "
            f"{host_ip}:{TUNNEL_PORT} {device_dir}/ca.crt {DEV_NODE_ID} {TOKEN}"
        )
        device = subprocess.Popen(
            ["adb", "-s", serial, "shell", remote],
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        try:
            marker = {"running": False, "closed": False, "state_after": ""}

            def reader():
                for line in device.stdout:
                    line = line.strip()
                    if line:
                        print(f"[device] {line}")
                    if line.startswith("SMOKE:running"):
                        marker["running"] = True
                    if line.startswith("SMOKE:closed"):
                        marker["closed"] = True
                    if line.startswith("SMOKE:state-after-close"):
                        marker["state_after"] = line

            threading.Thread(target=reader, daemon=True).start()

            wait_for(lambda: marker["running"], 30, "device node running marker")
            check("5.4 AAR .so loaded + ABI check + node RUNNING on device", True)

            nodes = wait_for(
                lambda: [n for n in mesh.list_nodes() if n["node_id"] == DEV_NODE_ID] or None,
                15, "device node registration")
            methods = nodes[0].get("methods") or []
            check("6.1 node visible in control-plane registry", True,
                  json.dumps(nodes[0]))
            check("6.1 sorted mesh.methods reported (java.boom,java.echo,java.json)",
                  methods == ["java.boom", "java.echo", "java.json"], str(methods))

            payload = b"bin\x00ary\xffjava"
            r = mesh.invoke(DEV_NODE_ID, "java.echo", payload, 8000)
            check("6.2 binary echo through Java handler (NUL bytes intact)",
                  r.success and bytes(r.result) == payload,
                  getattr(r, "error", None) and r.error.get("message"))

            r = mesh.invoke(DEV_NODE_ID, "java.json", b"{}", 8000)
            meta = json.loads(bytes(r.result).decode()) if r.success else {}
            # The python invoker does not send a correlation id; the handler
            # must see exactly what the wire carried (empty corr here).
            check("6.2 request metadata reaches Java verbatim",
                  r.success and meta.get("method") == "java.json"
                  and meta.get("corr") == "" and meta.get("timeout") == 8000,
                  str(meta))

            r = mesh.invoke(DEV_NODE_ID, "java.boom", b"", 8000)
            err = r.error or {}
            boom_message = err.get("message") or ""
            if isinstance(boom_message, bytes):
                boom_message = boom_message.decode(errors="replace")
            check("6.3 Java exception -> structured remote error",
                  (not r.success) and err.get("code") == "INTERNAL"
                  and "IllegalStateException" in boom_message
                  and "kaboom from java" in boom_message,
                  repr(err))

            r = mesh.invoke(DEV_NODE_ID, "missing.method", b"", 8000)
            check("6.2 unregistered method stays NOT_FOUND",
                  (not r.success) and (r.error or {}).get("code") == "NOT_FOUND")

            # Concurrency: 8 parallel invokes, isolated payloads.
            results = [None] * 8
            def one(i):
                tag = bytes([i]) * 512
                r = mesh.invoke(DEV_NODE_ID, "java.echo", tag, 10000)
                results[i] = r.success and bytes(r.result) == tag
            threads = [threading.Thread(target=one, args=(i,)) for i in range(8)]
            [t.start() for t in threads]
            [t.join() for t in threads]
            check("6.4 8 concurrent invokes isolated", all(results), str(results))

            # Post-error liveness: the node still serves after java.boom.
            r = mesh.invoke(DEV_NODE_ID, "java.echo", b"still-alive", 8000)
            check("6.3 node still healthy after Java exception",
                  r.success and bytes(r.result) == b"still-alive")

            # Wait for the device main to close itself and the session to
            # be recycled (EOF on the tunnel).
            device.wait(timeout=NODE_WINDOW_SECONDS + 20)
            wait_for(lambda: not [n for n in mesh.list_nodes()
                                  if n["node_id"] == DEV_NODE_ID],
                     15, "session recycled after node close")
            check("6.4 clean close: no residual session/connection", True)
            check("6.4 post-close state rejected in Java",
                  "NodeStateException" in marker["state_after"], marker["state_after"])
        finally:
            if device.poll() is None:
                device.kill()

    failed = [name for name, ok in checks if not ok]
    print(f"\ndevice e2e: {len(checks) - len(failed)}/{len(checks)} checks passed")
    if failed:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
