This directory holds the platform c-shared libraries (libmesh.so).
Place them as `_native/<platform>/libmesh.so`, e.g.
`_native/linux-aarch64/libmesh.so` or `_native/darwin-arm64/libmesh.so`.

Build with: `cd grpc-mesh-server && make build-meshlib`, or point the
`GRPC_MESH_LIB` environment variable at an existing libmesh.so.
