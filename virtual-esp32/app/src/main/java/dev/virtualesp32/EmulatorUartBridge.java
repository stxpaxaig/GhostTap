package dev.virtualesp32;

import android.content.Context;
import android.content.Intent;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Local TCP serial adapter: QEMU's emulated UART connects to this loopback socket.
 * QEMU is configured as the socket client with -serial tcp:127.0.0.1:<port>.
 * Handles QEMU (re)connections: the accept loop keeps running, each new connection
 * gets its own reader thread, and writes always go to the latest live socket. */
final class EmulatorUartBridge implements AutoCloseable {
    private final Context context;
    private final VirtualHal hal;
    private ServerSocket server;
    private volatile Socket guest;
    private volatile OutputStream toGuest;
    private Thread acceptThread;
    private volatile boolean closed;
    private int connectionSeq;
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();

    EmulatorUartBridge(Context c) {
        context = c.getApplicationContext();
        hal = VirtualHal.get(context);
    }

    synchronized int start() throws Exception {
        if (server != null) throw new IllegalStateException("UART bridge already started");
        try {
            server = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
        } catch (Exception e) {
            throw new Exception("UART bridge socket() failed: " + e.getMessage() + " (need INTERNET permission for TCP loopback)", e);
        }
        int port = server.getLocalPort();
        acceptThread = new Thread(this::acceptLoop, "virtual-esp32-qemu-uart");
        acceptThread.setDaemon(true);
        acceptThread.start();
        return port;
    }

    /** Accept loop: stays alive for the bridge lifetime so QEMU reconnects are picked up. */
    private void acceptLoop() {
        while (!closed) {
            try {
                Socket s = server.accept();
                int seq;
                synchronized (this) {
                    if (closed) {
                        try { s.close(); } catch (Exception ignored) {}
                        return;
                    }
                    seq = ++connectionSeq;
                    // Replace any previous connection.
                    Socket prev = guest;
                    guest = s;
                    try {
                        toGuest = s.getOutputStream();
                    } catch (Exception e) {
                        hal.log("EMU-UART", "UART connection #" + seq + " has no output stream: " + e);
                        try { s.close(); } catch (Exception ignored) {}
                        guest = null;
                        toGuest = null;
                        continue;
                    }
                    if (prev != null) {
                        try { prev.close(); } catch (Exception ignored) {}
                    }
                }
                hal.log("EMU-UART", "QEMU UART connected on loopback (#" + seq + ")");
                broadcast();
                startReader(s, seq);
            } catch (Exception e) {
                if (!closed) hal.log("EMU-UART", "UART accept ended: " + e);
                return;
            }
        }
    }

    /** One reader thread per connection; only the latest connection's data is shown. */
    private void startReader(final Socket s, final int seq) {
        Thread t = new Thread(() -> {
            try {
                InputStream in = s.getInputStream();
                byte[] b = new byte[1024];
                int n;
                while (!closed && (n = in.read(b)) != -1) {
                    synchronized (EmulatorUartBridge.this) {
                        if (guest != s) return; // superseded by a newer connection
                    }
                    String chunk = new String(b, 0, n, StandardCharsets.UTF_8);
                    String prior = hal.get("uart", "");
                    String combined = prior + chunk;
                    if (combined.length() > 32768) combined = combined.substring(combined.length() - 32768);
                    hal.put("uart", combined);
                    hal.log("UART", chunk.trim().isEmpty() ? "QEMU UART data" : chunk.trim());
                    BridgeEventBus.postUartRx(chunk);
                    broadcast();
                }
            } catch (Exception e) {
                if (!closed) hal.log("EMU-UART", "UART connection #" + seq + " ended: " + e);
            } finally {
                synchronized (EmulatorUartBridge.this) {
                    if (guest == s) {
                        guest = null;
                        toGuest = null;
                    }
                }
                try { s.close(); } catch (Exception ignored) {}
                broadcast();
            }
        }, "virtual-esp32-qemu-uart-rx-" + seq);
        t.setDaemon(true);
        t.start();
    }

    /** Write toward the guest. The socket I/O runs on a background thread because
     * Android throws NetworkOnMainThreadException for network writes on the UI thread.
     * Returns true if a write was queued to a live socket. */
    boolean sendHostText(String value) {
        final OutputStream out;
        synchronized (this) {
            out = toGuest;
        }
        if (out == null) {
            hal.log("EMU-UART", "HOST UART TX failed: no guest socket");
            return false;
        }
        final String fValue = value;
        ioExecutor.execute(() -> {
            try {
                byte[] raw = fValue.getBytes(StandardCharsets.UTF_8);
                out.write(raw);
                out.flush();
                hal.log("UART", "HOST UART TX: " + fValue.trim() + " (" + raw.length + " bytes)");
            } catch (java.io.IOException e) {
                hal.log("EMU-UART", "HOST UART TX failed: " + e.toString());
                // Genuine I/O error: drop the dead stream so a reconnect can replace it.
                synchronized (EmulatorUartBridge.this) {
                    if (toGuest == out) {
                        toGuest = null;
                        if (guest != null) {
                            try { guest.close(); } catch (Exception ignored) {}
                            guest = null;
                        }
                    }
                }
                broadcast();
            } catch (Exception e) {
                hal.log("EMU-UART", "HOST UART TX failed: " + e.toString());
            }
        });
        return true;
    }

    private void broadcast() {
        Intent i = new Intent("dev.virtualesp32.STATE").setPackage(context.getPackageName());
        context.sendBroadcast(i);
    }

    @Override public synchronized void close() {
        closed = true;
        ioExecutor.shutdownNow();
        try { if (guest != null) guest.close(); } catch (Exception ignored) {}
        try { if (server != null) server.close(); } catch (Exception ignored) {}
        server = null;
        guest = null;
        toGuest = null;
    }
}
