"""grpc-mesh Python binding.

Embeds the gRPC-Mesh control plane (TLS tunnel listener, session registry,
authentication, reverse invocation) in a Python process through the libmesh
c-shared library. See bindings/python/README.md for installation and usage.
"""

from __future__ import annotations

import base64
import binascii
import ctypes
import json
from dataclasses import dataclass, field
from typing import Any

from ._lib import BUILD_INSTRUCTIONS, MeshLibraryNotFound, load_library

__all__ = [
    "MeshServer",
    "InvokeResult",
    "MeshError",
    "MeshLibraryNotFound",
]


class MeshError(RuntimeError):
    """Infrastructure-level failure (invalid config, library error, ...).

    Business-level failures of a remote invocation are NOT raised: they are
    reported on the returned InvokeResult, mirroring the Go Gateway.Invoke
    semantics.
    """


@dataclass
class InvokeResult:
    """One invocation outcome, decoded from the ABI's InvokeResponse JSON."""

    peer_id: str
    method: str
    success: bool
    result: bytes
    error: dict[str, Any] | None = None
    correlation_id: str = ""
    elapsed_ms: int = 0


@dataclass
class _NodeInfo:
    node_id: str
    version: str
    methods: list[str] = field(default_factory=list)
    connected_at: str = ""
    last_heartbeat: str = ""


def _decode_error(raw: dict[str, Any] | None) -> dict[str, Any] | None:
    """Decode the base64 ``details`` field; malformed data is a hard error,
    not silently passed through as a string."""
    if not raw:
        return None
    err = dict(raw)
    details = err.get("details", "")
    if isinstance(details, str):
        try:
            err["details"] = base64.b64decode(details, validate=True)
        except (ValueError, binascii.Error) as exc:
            raise MeshError(
                f"malformed base64 in invoke error.details: {exc}"
            ) from exc
    return err


class MeshServer:
    """In-process gRPC-Mesh control plane.

    ``config`` follows the grpc-mesh-server ``pkg/config`` schema, e.g.::

        mesh = MeshServer({
            "server": {"grpc_address": "127.0.0.1:50051",
                        "metrics_address": "127.0.0.1:9090"},
            "listener": {"address": "127.0.0.1:8443"},
            "auth": {"enabled": True,
                      "node_tokens": {"calculator-service": "waemu_..."}},
        })

    Use as a context manager to start on entry and stop on exit, or call
    ``start()``/``stop()`` explicitly (``stop()`` is idempotent).
    """

    def __init__(self, config: dict[str, Any]):
        self._lib = load_library()
        handle = self._lib.mesh_server_new(
            json.dumps(config).encode("utf-8")
        )
        if handle == 0:
            raise MeshError(self._last_error())
        self._handle = handle
        self._started = False

    # -- lifecycle ------------------------------------------------------

    def start(self) -> None:
        rc = self._lib.mesh_server_start(self._handle)
        if rc != 0:
            raise MeshError(self._last_error())
        self._started = True

    def stop(self) -> None:
        rc = self._lib.mesh_server_stop(self._handle)
        if rc != 0:
            raise MeshError(self._last_error())
        self._started = False

    def close(self) -> None:
        self._lib.mesh_server_free(self._handle)

    def __enter__(self) -> "MeshServer":
        self.start()
        return self

    def __exit__(self, exc_type, exc, tb) -> None:
        self.stop()
        self.close()

    def __del__(self):  # pragma: no cover - best effort
        lib = getattr(self, "_lib", None)
        handle = getattr(self, "_handle", 0)
        if lib is not None and handle:
            lib.mesh_server_free(handle)

    # -- operations -----------------------------------------------------

    def invoke(
        self,
        peer_id: str,
        method: str,
        payload: bytes = b"",
        timeout_ms: int = 5000,
    ) -> InvokeResult:
        """Invoke a method on a node via the generic Invoke dispatch.

        Infrastructure failures raise MeshError; node-side/business failures
        are reported on the returned InvokeResult (``success=False`` and the
        ``error`` dict, e.g. ``{"code": "DIAL_FAILED", ...}``).
        """
        str_handle = self._lib.mesh_invoke(
            self._handle,
            peer_id.encode("utf-8"),
            method.encode("utf-8"),
            bytes(payload),
            len(payload),
            int(timeout_ms),
        )
        if not str_handle:
            raise MeshError(self._last_error())

        obj = json.loads(self._read_string_handle(str_handle))
        result_b64 = obj.get("result") or ""
        result = base64.b64decode(result_b64) if result_b64 else b""
        return InvokeResult(
            peer_id=obj.get("peerId", peer_id),
            method=obj.get("method", method),
            success=bool(obj.get("success")),
            result=result,
            error=_decode_error(obj.get("error")),
            correlation_id=obj.get("correlationId", ""),
            elapsed_ms=int(obj.get("elapsedMs", 0)),
        )

    def list_nodes(self) -> list[dict[str, Any]]:
        """Return connected nodes with their reported method lists."""
        str_handle = self._lib.mesh_list_nodes(self._handle)
        if not str_handle:
            raise MeshError(self._last_error())

        return json.loads(self._read_string_handle(str_handle))

    # -- internals ------------------------------------------------------

    def _read_string_handle(self, str_handle: int) -> str:
        """Read a library string handle and release it exactly once."""
        cs = self._lib.mesh_str_data(str_handle)
        if not cs:
            raise MeshError("string handle vanished before it could be read")
        try:
            return cs.decode("utf-8")
        finally:
            self._lib.mesh_str_release(str_handle)

    def _last_error(self) -> str:
        cs = self._lib.mesh_last_error()
        return cs.decode("utf-8", errors="replace") if cs else "unknown error"
