plugins {
    `java-library`
}

group = "io.grpc-mesh"
version = providers.gradleProperty("bindingVersion").orElse("0.1.0-alpha1").get()

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    // The JVM tests link against the host-built cdylib (which carries the
    // JNI shim). GRPC_MESH_NODE_LIB overrides the location when needed.
    val repoRoot = layout.projectDirectory.dir("../../..")
    val defaultLib =
        repoRoot.file("grpc-mesh-node/target/x86_64-unknown-linux-gnu/debug/libgrpc_mesh_node.so")
    val lib = System.getenv("GRPC_MESH_NODE_LIB") ?: defaultLib.asFile.absolutePath
    systemProperty("io.mesh.node.lib", lib)
    testLogging {
        events("failed", "skipped")
        showStandardStreams = true
    }
    environment("RUST_BACKTRACE", "1")
}
