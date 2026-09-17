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


class TestAbiSignatureBinding(unittest.TestCase):
    """ISSUE round-3: assert the ctypes signatures bound for all nine
    exported ABI functions, without needing the real library."""

    def test_all_nine_signatures(self):
        import ctypes
        from types import SimpleNamespace

        fake = SimpleNamespace()
        for name in (
            "mesh_server_new", "mesh_server_start", "mesh_server_stop",
            "mesh_server_free", "mesh_invoke", "mesh_list_nodes",
            "mesh_str_data", "mesh_str_release", "mesh_last_error",
        ):
            setattr(fake, name, ctypes.CFUNCTYPE(None)())  # placeholder instance

        _lib._bind(fake)

        expect = {
            "mesh_server_new": ([ctypes.c_char_p], ctypes.c_uint64),
            "mesh_server_start": ([ctypes.c_uint64], ctypes.c_int),
            "mesh_server_stop": ([ctypes.c_uint64], ctypes.c_int),
            "mesh_server_free": ([ctypes.c_uint64], None),
            "mesh_invoke": ([
                ctypes.c_uint64, ctypes.c_char_p, ctypes.c_char_p,
                ctypes.c_char_p, ctypes.c_int, ctypes.c_uint32,
            ], ctypes.c_uint64),
            "mesh_list_nodes": ([ctypes.c_uint64], ctypes.c_uint64),
            "mesh_str_data": ([ctypes.c_uint64], ctypes.c_char_p),
            "mesh_str_release": ([ctypes.c_uint64], None),
            "mesh_last_error": ([], ctypes.c_char_p),
        }
        for name, (args, res) in expect.items():
            fn = getattr(fake, name)
            self.assertEqual(list(fn.argtypes), args, name)
            self.assertIs(fn.restype, res, name)


class TestDecodeError(unittest.TestCase):
    """ISSUE-003: malformed base64 in error.details must fail loudly."""

    def test_malformed_details_raises(self):
        from grpc_mesh import MeshError, _decode_error

        with self.assertRaises(MeshError):
            _decode_error({"code": "X", "details": "!!!not-base64!!!"})

    def test_valid_details_decodes_to_bytes(self):
        from grpc_mesh import _decode_error

        err = _decode_error({"code": "X", "details": "aGk="})
        self.assertEqual(err["details"], b"hi")

    def test_empty_details_decodes_to_empty_bytes(self):
        from grpc_mesh import _decode_error

        err = _decode_error({"code": "X", "details": ""})
        self.assertEqual(err["details"], b"")


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

    def test_closed_server_rejects_use(self):
        mesh = MeshServer(dict(self.FREE_PORTS_CFG))
        mesh.close()
        with self.assertRaises(MeshError):
            mesh.list_nodes()
        with self.assertRaises(MeshError):
            mesh.invoke("x", "y", b"")
        with self.assertRaises(MeshError):
            mesh.start()
        mesh.close()  # second close stays a no-op

    def test_invoke_unknown_peer_reports_dial_failed(self):
        with MeshServer(dict(self.FREE_PORTS_CFG)) as mesh:
            result = mesh.invoke("no-such-node", "a.B/C", b"", timeout_ms=1000)
            self.assertFalse(result.success)
            self.assertEqual(result.error["code"], "DIAL_FAILED")


if __name__ == "__main__":
    unittest.main()
