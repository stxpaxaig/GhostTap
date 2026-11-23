package dev.virtualesp32;

import android.content.Context;
import android.content.Intent;
import org.json.JSONObject;

/** Polls the live emulated GPIO2 output state from QEMU's ESP32 GPIO model
 * and pushes changes into the Android runtime/UI.
 *
 * Source of truth: Xtensa guest firmware writes to the ESP32 GPIO MMIO
 * peripheral under QEMU -> QEMU's gpio model updates its real register
 * state -> this monitor reads it via JNI -> Android UI reflects it.
 *
 * Nothing here invents GPIO state; -1 (unavailable/not-an-output) is ignored. */
final class GuestGpioMonitor {
    private final Context context;
    private final VirtualHal hal;
    private Thread pollThread;
    private volatile boolean stopped;
    private int lastLevel = -2; // -2 = never observed

    GuestGpioMonitor(Context c) {
        context = c.getApplicationContext();
        hal = VirtualHal.get(context);
    }

    void start() {
        stopped = false;
        pollThread = new Thread(this::pollLoop, "virtual-esp32-gpio-monitor");
        pollThread.setDaemon(true);
        pollThread.start();
    }

    void stop() {
        stopped = true;
        Thread t = pollThread;
        pollThread = null;
        if (t != null) t.interrupt();
    }

    private void pollLoop() {
        while (!stopped) {
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                return;
            }
            if (stopped) return;
            int level;
            try {
                level = ClassicEsp32QemuBackend.nativeGetGpio2();
            } catch (Throwable t) {
                continue; // native not available; try again next poll
            }
            if (level < 0) continue; // unavailable or pin not configured as output
            if (level == lastLevel) continue;
            lastLevel = level;
            boolean high = level == 1;
            try {
                JSONObject j = new JSONObject(hal.get("gpio", "{}"));
                j.put("2", high);
                j.put("guest_driven", true);
                hal.put("gpio", j.toString());
            } catch (Exception ignored) {
            }
            hal.log("GPIO", "GUEST GPIO2 -> " + (high ? "HIGH" : "LOW"));
            BridgeEventBus.postGpioChanged(2, high, true);
            Intent i = new Intent("dev.virtualesp32.STATE").setPackage(context.getPackageName());
            context.sendBroadcast(i);
        }
    }
}
