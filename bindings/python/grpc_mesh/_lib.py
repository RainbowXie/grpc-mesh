"""ctypes binding layer for the libmesh c-shared library.

The library is produced by `make build-meshlib` in grpc-mesh-server
(CGO_ENABLED=1 go build -buildmode=c-shared). Loading order:

1. the ``GRPC_MESH_LIB`` environment variable (absolute path to libmesh.so)
2. ``grpc_mesh/_native/<platform>/libmesh.so`` shipped inside a platform
   wheel, where ``<platform>`` is e.g. ``linux-aarch64`` or ``darwin-arm64``
3. ``grpc_mesh/_native/libmesh.so`` (flat copy)

ctypes.CDLL releases the GIL while a foreign call runs, so blocking calls
such as ``mesh_invoke`` do not stall other Python threads.
"""

from __future__ import annotations

import ctypes
import os
import platform
import sys

BUILD_INSTRUCTIONS = (
    "grpc-mesh needs the libmesh c-shared library. Either set GRPC_MESH_LIB "
    "to an existing libmesh.so, or build it with: "
    "cd grpc-mesh-server && make build-meshlib "
    "(requires Go >= 1.25 with CGO enabled), then copy libmesh.so into "
    "grpc_mesh/_native/<platform>/ next to this package."
)


class MeshLibraryNotFound(ImportError):
    """Raised when no libmesh shared library can be located."""


def _platform_dirname() -> str:
    machine = platform.machine().lower()
    return f"{sys.platform}-{machine}"


def _candidate_paths() -> list[str]:
    explicit = os.environ.get("GRPC_MESH_LIB")
    if explicit:
        return [explicit]

    pkg_dir = os.path.dirname(__file__)
    return [
        os.path.join(pkg_dir, "_native", _platform_dirname(), "libmesh.so"),
        os.path.join(pkg_dir, "_native", "libmesh.so"),
    ]


def _bind(lib: ctypes.CDLL) -> None:
    c_str = ctypes.c_char_p
    out_str = ctypes.POINTER(ctypes.c_char)

    lib.mesh_server_new.argtypes = [c_str]
    lib.mesh_server_new.restype = ctypes.c_uint64

    lib.mesh_server_start.argtypes = [ctypes.c_uint64]
    lib.mesh_server_start.restype = ctypes.c_int

    lib.mesh_server_stop.argtypes = [ctypes.c_uint64]
    lib.mesh_server_stop.restype = ctypes.c_int

    lib.mesh_server_free.argtypes = [ctypes.c_uint64]
    lib.mesh_server_free.restype = None

    lib.mesh_invoke.argtypes = [
        ctypes.c_uint64,  # handle
        c_str,            # peer_id
        c_str,            # method
        c_str,            # payload (bytes, may contain NUL; length below)
        ctypes.c_int,     # payload length
        ctypes.c_uint32,  # timeout_ms
    ]
    lib.mesh_invoke.restype = out_str

    lib.mesh_list_nodes.argtypes = [ctypes.c_uint64]
    lib.mesh_list_nodes.restype = out_str

    lib.mesh_free_string.argtypes = [ctypes.c_void_p]
    lib.mesh_free_string.restype = None

    lib.mesh_last_error.argtypes = []
    lib.mesh_last_error.restype = c_str


def load_library(search_paths: list[str] | None = None) -> ctypes.CDLL:
    """Load and bind libmesh, raising MeshLibraryNotFound with actionable
    instructions when it cannot be located."""
    for path in search_paths if search_paths is not None else _candidate_paths():
        if path and os.path.exists(path):
            lib = ctypes.CDLL(path)
            _bind(lib)
            return lib
    raise MeshLibraryNotFound(BUILD_INSTRUCTIONS)
