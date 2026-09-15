"""Minimal calculator demo: embed the mesh control plane in Python and
invoke the Rust calculator-service node over the tunnel.

Prerequisites:
  1. Build and place libmesh.so (see bindings/python/README.md).
  2. Have the Rust demo node running against the listener address below:
     cd demos/calculator-service
     CONFIG_PATH=... CA_CERT_PATH=... cargo run

The protobuf encoding of CalcRequest{a, b} / CalcResponse{result} is done
by hand below (field 1/2 doubles, wire type 1) to keep the example
dependency-free; use the `protobuf` package in real code.
"""

from __future__ import annotations

import struct
import sys
import time

from grpc_mesh import MeshServer

TUNNEL_PORT = 18443
NODE_ID = "calculator-service"


def encode_calc_request(a: float, b: float) -> bytes:
    return b"\x09" + struct.pack("<d", a) + b"\x11" + struct.pack("<d", b)


def decode_calc_response(data: bytes) -> float:
    # field 1 (result), wire type 1: tag 0x09 + 8 bytes little-endian double
    assert data[0] == 0x09 and len(data) >= 9, f"unexpected payload: {data!r}"
    return struct.unpack("<d", data[1:9])[0]


def main() -> int:
    config = {
        "server": {"grpc_address": "127.0.0.1:150051", "metrics_address": "127.0.0.1:19090"},
        "listener": {"address": f"127.0.0.1:{TUNNEL_PORT}"},
        # If you bring your own certs:
        # "listener": {"address": ..., "cert_file": "...", "key_file": "..."},
        "auth": {
            "enabled": True,
            "node_tokens": {NODE_ID: "waemu_7RCx4i4T6gU3O9Gqcx4-SvHMRN1V8dJ9"},
        },
    }

    with MeshServer(config) as mesh:
        print("mesh control plane started, waiting for node ...")
        for _ in range(50):
            nodes = mesh.list_nodes()
            if any(n["node_id"] == NODE_ID for n in nodes):
                break
            time.sleep(0.2)
        else:
            print(f"node {NODE_ID} never connected")
            return 1

        for node in nodes:
            print(f"node {node['node_id']} methods={node['methods']}")

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
