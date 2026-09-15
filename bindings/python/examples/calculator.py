"""Turnkey example: embed the mesh control plane in Python and invoke the
Rust calculator-service node.

Run it next to a running demo node (repository certificates and the
node's defaults all line up):

    # terminal 1 — the Rust demo node (uses repo TLS + its config.json)
    cd demos/calculator-service
    cargo run

    # terminal 2 — this example (server listens on 127.0.0.1:8443)
    cd bindings/python
    python3 examples/calculator.py

The protobuf encoding of CalcRequest{a, b} / CalcResponse{result} is done
by hand below (doubles, wire type 1) to keep the example dependency-free;
see examples/README.md for the `protobuf`-package version.
"""

from __future__ import annotations

import struct
import sys
import time
from pathlib import Path

from grpc_mesh import MeshServer

# Repository layout: examples/ lives at bindings/python/examples.
REPO_TLS = Path(__file__).resolve().parents[3] / "grpc-mesh-server" / "config" / "tls"

NODE_ID = "calculator-service"
# Must match demos/calculator-service/config/config.json (node.token).
NODE_TOKEN = "waemu_7RCx4i4T6gU3O9Gqcx4-SvHMRN1V8dJ9"


def encode_calc_request(a: float, b: float) -> bytes:
    # CalcRequest{ double a = 1; double b = 2; }
    return b"\x09" + struct.pack("<d", a) + b"\x11" + struct.pack("<d", b)


def decode_calc_response(data: bytes) -> float:
    # CalcResponse{ double result = 1; }
    assert data[0] == 0x09 and len(data) >= 9, f"unexpected payload: {data!r}"
    return struct.unpack("<d", data[1:9])[0]


def main() -> int:
    config = {
        # Ports only used by this embedded server; the node only needs the
        # listener address below to match its config.json.
        "server": {"grpc_address": "127.0.0.1:50061", "metrics_address": "127.0.0.1:19061"},
        "listener": {
            "address": "127.0.0.1:8443",
            # The node verifies the server against config/tls/ca.crt; leaving
            # cert/key out would generate an ephemeral cert the node cannot
            # verify.
            "cert_file": str(REPO_TLS / "server-chain.crt"),
            "key_file": str(REPO_TLS / "server.key"),
        },
        "auth": {
            "enabled": True,
            # Token is bound to the node id: the demo node is only accepted
            # when it both holds this token and claims NODE_ID.
            "node_tokens": {NODE_ID: NODE_TOKEN},
        },
    }

    with MeshServer(config) as mesh:
        print("mesh control plane on 127.0.0.1:8443, waiting for node ...")
        for _ in range(50):
            nodes = mesh.list_nodes()
            if any(n["node_id"] == NODE_ID for n in nodes):
                break
            time.sleep(0.2)
        else:
            print(f"node {NODE_ID} never connected; is calculator-service running?")
            return 1

        for node in nodes:
            print(f"node {node['node_id']}: methods={node['methods']}")

        result = mesh.invoke(
            NODE_ID,
            "calculator.v1.Calculator/Add",
            encode_calc_request(10, 5),
            timeout_ms=5000,
        )
        if not result.success:
            print(f"invoke failed: {result.error}")
            return 1
        print(f"10 + 5 = {decode_calc_response(result.result)}")

    print("mesh stopped cleanly")
    return 0


if __name__ == "__main__":
    sys.exit(main())
