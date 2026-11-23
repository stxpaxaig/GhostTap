package dev.virtualesp32;

/** Public, safe availability probe for Ghost Tap's embedded classic-ESP32 QEMU runtime. */
public final class EmbeddedEsp32Runtime {
    private EmbeddedEsp32Runtime() {}

    public static boolean isAvailable() {
        try {
            return ClassicEsp32QemuBackend.available();
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static String unavailableReason() {
        try {
            return ClassicEsp32QemuBackend.unavailableReason();
        } catch (Throwable t) {
            return "Embedded QEMU backend unavailable: " + t.getClass().getSimpleName();
        }
    }
}
