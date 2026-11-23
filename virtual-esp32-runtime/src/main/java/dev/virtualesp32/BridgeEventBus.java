package dev.virtualesp32;

import java.util.concurrent.CopyOnWriteArrayList;

/** Internal event bus: lets Virtual ESP32 components (UART bridge, GPIO
 * monitor, emulator runtime) publish guest events, and lets the IPC bridge
 * service forward them to external clients (e.g. GHOST TAP) via AIDL callbacks.
 *
 * This keeps the existing components decoupled from IPC details. */
final class BridgeEventBus {
    interface Listener {
        void onGuestStarted();
        void onGuestStopped();
        void onGuestReset();
        void onUartRx(String data);
        void onGpioChanged(int pin, boolean high, boolean isOutput);
        void onError(String message);
    }

    private static final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    static void addListener(Listener l) {
        if (l != null) listeners.addIfAbsent(l);
    }

    static void removeListener(Listener l) {
        listeners.remove(l);
    }

    static void postGuestStarted() {
        for (Listener l : listeners) {
            try { l.onGuestStarted(); } catch (Exception ignored) {}
        }
    }

    static void postGuestStopped() {
        for (Listener l : listeners) {
            try { l.onGuestStopped(); } catch (Exception ignored) {}
        }
    }

    static void postGuestReset() {
        for (Listener l : listeners) {
            try { l.onGuestReset(); } catch (Exception ignored) {}
        }
    }

    static void postUartRx(String data) {
        for (Listener l : listeners) {
            try { l.onUartRx(data); } catch (Exception ignored) {}
        }
    }

    static void postGpioChanged(int pin, boolean high, boolean isOutput) {
        for (Listener l : listeners) {
            try { l.onGpioChanged(pin, high, isOutput); } catch (Exception ignored) {}
        }
    }

    static void postError(String message) {
        for (Listener l : listeners) {
            try { l.onError(message); } catch (Exception ignored) {}
        }
    }
}
