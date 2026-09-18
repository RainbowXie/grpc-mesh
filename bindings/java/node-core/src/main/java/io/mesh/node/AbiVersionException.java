package io.mesh.node;

/**
 * The loaded native library speaks an incompatible Node C ABI major version.
 * Carries both the version this binding requires and the runtime version.
 */
public class AbiVersionException extends RuntimeException {

    private final int expectedMajor;
    private final int actualVersion;

    public AbiVersionException(int expectedMajor, int actualVersion) {
        super(
                "Node C ABI mismatch: binding requires major "
                        + expectedMajor
                        + ", native library reports "
                        + (actualVersion >>> 16)
                        + "."
                        + (actualVersion & 0xFFFF));
        this.expectedMajor = expectedMajor;
        this.actualVersion = actualVersion;
    }

    public int expectedMajor() {
        return expectedMajor;
    }

    public int actualVersion() {
        return actualVersion;
    }
}
