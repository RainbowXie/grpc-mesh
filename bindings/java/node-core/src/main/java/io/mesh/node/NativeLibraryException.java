package io.mesh.node;

/**
 * The native node library could not be loaded. The message names the library
 * that was attempted and the ABIs available on this device so the failure is
 * actionable (e.g. an arm64-only Alpha AAR on an x86 emulator).
 */
public class NativeLibraryException extends RuntimeException {

    public NativeLibraryException(String supportedAbis, String libraryName, Throwable cause) {
        super(
                "cannot load "
                        + libraryName
                        + ": no native library for this device (supported ABIs: "
                        + supportedAbis
                        + "); the Alpha ships arm64-v8a only"
                        + (cause == null ? "" : " [" + cause + "]"),
                cause);
    }
}
