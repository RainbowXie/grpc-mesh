plugins {
    id("com.android.library")
}

group = "io.grpc-mesh"
version = "0.1.0-alpha1"

android {
    namespace = "io.mesh.node.android"
    compileSdk = 34
    ndkVersion = "29.0.14033849"

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // The Alpha ships exactly one verified ABI; adding another requires its
    // own build + smoke test first (spec: node-binding-distribution).
    defaultConfig {
        ndk {
            // Deliberately only arm64-v8a; enforced again by packaging.
            abiFilters += listOf("arm64-v8a")
        }
    }

    sourceSets {
        getByName("main") {
            // The Alpha ships a single artifact: the io.mesh.node classes
            // are compiled straight into this AAR (the node-core module
            // keeps the same sources for host JVM testing). The native
            // library lands in the generated jniLibs dir via buildNative;
            // nothing binary is committed to Git.
            java.srcDir("../node-core/src/main/java")
            jniLibs.srcDir(layout.buildDirectory.dir("generated/jniLibs"))
        }
    }
}

// No project dependency on purpose (see sourceSets note): consumers need
// exactly one artifact for the Alpha.

// ---------------------------------------------------------------------------
// Native library build (aarch64-linux-android)
// ---------------------------------------------------------------------------

val nodeCrate = rootProject.projectDir.resolve("../../grpc-mesh-node")

val buildNative by tasks.registering(Exec::class) {
    description = "Builds libgrpc_mesh_node.so for aarch64-linux-android via cargo."
    workingDir(nodeCrate)
    commandLine(
        "cargo", "build", "-p", "grpc-mesh-node-ffi", "--release",
        "--target", "aarch64-linux-android",
    )
    // Cargo is the dependency tracker; rerun cheaply and let it decide.
    outputs.file(nodeCrate.resolve("target/aarch64-linux-android/release/libgrpc_mesh_node.so"))
}

val copyNative by tasks.registering(Copy::class) {
    description = "Copies the built .so into the generated jniLibs dir."
    dependsOn(buildNative)
    from(nodeCrate.resolve("target/aarch64-linux-android/release/libgrpc_mesh_node.so"))
    into(layout.buildDirectory.dir("generated/jniLibs/arm64-v8a"))
}

tasks.matching { it.name.startsWith("preBuild") }.configureEach {
    dependsOn(copyNative)
}

// Gate: the AAR must not declare or package unverified ABIs.
tasks.matching { it.name.startsWith("mergeJniLibFolders") || it.name.startsWith("mergeDebugJniLibFolders") || it.name.startsWith("mergeReleaseJniLibFolders") }.configureEach {
    dependsOn(copyNative)
    doLast {
        val dir = layout.buildDirectory.dir("generated/jniLibs").get().asFile
        val abis = dir.listFiles()?.map { it.name } ?: emptyList()
        require(abis == listOf("arm64-v8a")) {
            "Alpha AAR must contain exactly arm64-v8a, found: $abis"
        }
    }
}
