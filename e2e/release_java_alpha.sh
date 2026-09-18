#!/usr/bin/env bash
# Tag-driven Alpha release builder for the Java/Android node binding
# (tasks 7.4/7.5 of openspec/changes/node-ffi-java-binding).
#
# Builds from a CLEAN checkout (git archive of HEAD) — never the working
# tree — and produces:
#   dist/node-android-<ver>.aar      (classes + jni/arm64-v8a/libgrpc_mesh_node.so)
#   dist/libgrpc_mesh_node.so        (standalone, aarch64-linux-android)
#   dist/grpc_mesh_node.h            (C ABI header)
#   dist/RELEASE_MANIFEST.json       (parent/node commits, versions, toolchains)
#   dist/SHA256SUMS
#
# Usage: release_java_alpha.sh [output-dir]
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${1:-$ROOT/dist/node-java-alpha}"
SDK="${ANDROID_HOME:-/home/ethan/Android/Sdk}"

PARENT_COMMIT="$(git -C "$ROOT" rev-parse HEAD)"
NODE_COMMIT="$(git -C "$ROOT/grpc-mesh-node" rev-parse HEAD)"
PARENT_DIRTY="$(git -C "$ROOT" status --porcelain | wc -l)"
NODE_DIRTY="$(git -C "$ROOT/grpc-mesh-node" status --porcelain | wc -l)"
if [ "$PARENT_DIRTY" -ne 0 ] || [ "$NODE_DIRTY" -ne 0 ]; then
  echo "REFUSING: working tree is dirty (parent=$PARENT_DIRTY node=$NODE_DIRTY)." >&2
  exit 1
fi

WORK="$(mktemp -d /tmp/grpc-mesh-java-release.XXXXXX)"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$OUT"

# --- clean checkout of both trees -------------------------------------------
git -C "$ROOT" archive "$PARENT_COMMIT" | tar -x -C "$WORK"
git -C "$ROOT/grpc-mesh-node" archive "$NODE_COMMIT" | tar -x -C "$WORK/grpc-mesh-node"

# --- build native (aarch64) + AAR + header ----------------------------------
(
  cd "$WORK/grpc-mesh-node"
  cargo build -p grpc-mesh-node-ffi --release --target aarch64-linux-android
  # Header generation runs on the host toolchain (the example binary itself
  # is never the shipped artifact).
  cargo run   -p grpc-mesh-node-ffi --example gen_header --release \
      --target x86_64-unknown-linux-gnu
)
(
  cd "$WORK/bindings/java"
  echo "sdk.dir=$SDK" > local.properties
  ./gradlew --no-daemon :node-android:assembleRelease
)

VERSION="0.1.0-alpha1"
AAR="$WORK/bindings/java/node-android/build/outputs/aar/node-android-release.aar"
SO="$WORK/grpc-mesh-node/target/aarch64-linux-android/release/libgrpc_mesh_node.so"
HDR="$WORK/grpc-mesh-node/crates/grpc-mesh-node-ffi/include/grpc_mesh_node.h"

[ -f "$AAR" ] || { echo "AAR missing after build" >&2; exit 1; }
[ -f "$SO" ]  || { echo ".so missing after build"  >&2; exit 1; }
[ -f "$HDR" ] || { echo "header missing after build" >&2; exit 1; }

cp "$AAR" "$OUT/node-android-$VERSION.aar"
cp "$SO"  "$OUT/libgrpc_mesh_node.so"
cp "$HDR" "$OUT/grpc_mesh_node.h"

# --- gate: AAR declares exactly arm64-v8a and its .so matches the build ------
unzip -o -j "$AAR" 'jni/arm64-v8a/libgrpc_mesh_node.so' -d "$WORK/aar-check" >/dev/null
ABI_DIRS="$(unzip -l "$AAR" | awk '/jni\/[^/]+\// {print $4}' | cut -d/ -f2 | grep -v '^$' | sort -u)"
[ "$ABI_DIRS" = "arm64-v8a" ] || { echo "gate: unexpected ABIs: $ABI_DIRS" >&2; exit 1; }
cmp "$WORK/aar-check/libgrpc_mesh_node.so" "$SO" \
  || { echo "gate: AAR .so differs from built .so" >&2; exit 1; }

# --- manifest (task 7.5) ------------------------------------------------------
ABI_MAJOR="$(grep -oE '#define MESH_NODE_ABI_VERSION \(\(([0-9]+)' "$HDR" | grep -oE '[0-9]+$')"
cat > "$OUT/RELEASE_MANIFEST.json" <<EOF
{
  "artifact": "node-android",
  "version": "$VERSION",
  "parent_commit": "$PARENT_COMMIT",
  "grpc_mesh_node_commit": "$NODE_COMMIT",
  "c_abi_version": "${ABI_MAJOR}.0",
  "crate_version": "$(grep -m1 '^version' "$WORK/grpc-mesh-node/crates/grpc-mesh-node-ffi/Cargo.toml" | awk '{print $3}' | tr -d '"')",
  "java_binding_version": "$VERSION",
  "android_min_sdk": 24,
  "target_abis": ["arm64-v8a"],
  "rust_toolchain": "$(rustc --version | head -1)",
  "android_ndk": "29.0.14033849",
  "gradle": "8.14.3",
  "notes": "built from clean git archive of the commits above"
}
EOF

(cd "$OUT" && sha256sum node-android-"$VERSION".aar libgrpc_mesh_node.so grpc_mesh_node.h RELEASE_MANIFEST.json > SHA256SUMS)

echo "release artifacts in $OUT:"
ls -la "$OUT"
cat "$OUT/SHA256SUMS"
