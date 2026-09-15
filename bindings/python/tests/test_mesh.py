"""Unit tests for the grpc-mesh Python binding.

Tests that need the real libmesh.so are skipped when the library is not
present, so the suite also passes in source-only checkouts.
"""

from __future__ import annotations

import ctypes
import os
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from grpc_mesh import MeshError, MeshLibraryNotFound, MeshServer  # noqa: E402
from grpc_mesh import _lib  # noqa: E402


def library_available() -> bool:
    try:
        _lib.load_library()
        return True
    except MeshLibraryNotFound:
        return False


class TestLibraryLoading(unittest.TestCase):
    def test_missing_library_raises_actionable_error(self):
        with self.assertRaises(MeshLibraryNotFound) as ctx:
            _lib.load_library(search_paths=["/nonexistent/libmesh.so"])
        msg = str(ctx.exception)
        self.assertIn("GRPC_MESH_LIB", msg)
        self.assertIn("make build-meshlib", msg)

    @unittest.skipUnless(library_available(), "libmesh.so not available")
    def test_loader_uses_cdll_releasing_gil(self):
        lib = _lib.load_library()
        # CDLL (as opposed to PyDLL) releases the GIL during foreign calls,
        # which is what lets blocking mesh_invoke calls coexist with other
        # Python threads. The end-to-end GIL demonstration lives in the
        # acceptance run (see bindings/python/README.md).
        self.assertIsInstance(lib, ctypes.CDLL)


@unittest.skipUnless(library_available(), "libmesh.so not available")
class TestMeshServerLifecycle(unittest.TestCase):
    FREE_PORTS_CFG = {
        "server": {"grpc_address": "127.0.0.1:0", "metrics_address": "127.0.0.1:0"},
        "listener": {"address": "127.0.0.1:0"},
    }

    def test_context_manager_starts_and_stops(self):
        with MeshServer(dict(self.FREE_PORTS_CFG)) as mesh:
            self.assertEqual(mesh.list_nodes(), [])

    def test_stop_is_idempotent(self):
        mesh = MeshServer(dict(self.FREE_PORTS_CFG))
        try:
            mesh.start()
            mesh.stop()
            mesh.stop()  # no-op, must not raise
        finally:
            mesh.close()

    def test_invalid_config_raises_mesh_error(self):
        # cert without key violates the pairing rule
        with self.assertRaises(MeshError):
            MeshServer({"listener": {"cert_file": "/nonexistent.crt"}})

    def test_invoke_unknown_peer_reports_dial_failed(self):
        with MeshServer(dict(self.FREE_PORTS_CFG)) as mesh:
            result = mesh.invoke("no-such-node", "a.B/C", b"", timeout_ms=1000)
            self.assertFalse(result.success)
            self.assertEqual(result.error["code"], "DIAL_FAILED")


if __name__ == "__main__":
    unittest.main()
