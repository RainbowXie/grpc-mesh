# The binding loads libgrpc_mesh_node.so itself; keep the loader class and
# its native methods intact under consumer minification.
-keep class io.mesh.node.GrpcMeshNode { *; }
-keep class io.mesh.node.NativeBridge { *; }
