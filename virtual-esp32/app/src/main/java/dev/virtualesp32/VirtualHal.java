package dev.virtualesp32;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Platform-neutral virtual peripherals; Android adapters live above this state layer. */
public final class VirtualHal {
    private static VirtualHal instance;
    private final SharedPreferences p;
    private VirtualHal(Context c) { p = c.getApplicationContext().getSharedPreferences("virtual_device", Context.MODE_PRIVATE); }
    public static synchronized VirtualHal get(Context c) { if (instance == null) instance = new VirtualHal(c); return instance; }
    public synchronized boolean powered() { return p.getBoolean("powered", true); }
    public synchronized void setPowered(boolean b) { p.edit().putBoolean("powered", b).apply(); log("POWER", b ? "Virtual MCU running" : "Virtual MCU stopped"); }
    public synchronized void softReset() { p.edit().putInt("bootCount",p.getInt("bootCount",0)+1).putBoolean("wifiApplied",false).apply(); log("BOOT","Virtual runtime rebooted; NVS retained"); }
    public synchronized boolean bleEnabled() { return p.getBoolean("ble", false); }
    public synchronized void setBleEnabled(boolean b) { p.edit().putBoolean("ble", b).apply(); }
    public synchronized String deviceName() { return p.getString("name", "PROV_PHONE32"); }
    public synchronized int bootCount() { return p.getInt("bootCount",0); }
    public synchronized String get(String key, String fallback) { return p.getString(key, fallback); }
    public synchronized void put(String key, String value) { p.edit().putString(key, value).apply(); }
    public synchronized void log(String tag, String message) {
        try {
            JSONArray a = new JSONArray(p.getString("logs", "[]"));
            a.put(new JSONObject().put("time", new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date())).put("tag", tag).put("message", message));
            while (a.length() > 100) a.remove(0);
            p.edit().putString("logs", a.toString()).apply();
        } catch (Exception ignored) { }
    }
    public synchronized JSONArray logs() { try { return new JSONArray(p.getString("logs", "[]")); } catch (Exception e) { return new JSONArray(); } }
    public synchronized JSONObject exportState() throws Exception {
        JSONObject j = new JSONObject(); j.put("format", "virtual-esp32-state-v1"); j.put("name", deviceName());
        j.put("powered", powered()); j.put("ble", bleEnabled()); j.put("gpio", new JSONObject(p.getString("gpio", "{}")));
        j.put("nvs", new JSONObject(p.getString("nvs", "{}"))); j.put("uart", p.getString("uart", ""));
        j.put("wifiConfigured", p.getBoolean("wifiConfigured", false)); j.put("wifiSsid", p.getString("wifiSsid", ""));
        j.put("customDataBase64", p.getString("lastCustomData", "")); j.put("bootCount", p.getInt("bootCount", 0)); j.put("logs", logs()); return j;
    }
    public synchronized void importState(JSONObject j) throws Exception {
        if (!"virtual-esp32-state-v1".equals(j.optString("format"))) throw new IllegalArgumentException("Unsupported state file format");
        p.edit().putString("name", j.optString("name", "PROV_PHONE32")).putBoolean("powered", j.optBoolean("powered", true))
                .putBoolean("ble", false).putString("gpio", j.optJSONObject("gpio") == null ? "{}" : j.getJSONObject("gpio").toString())
                .putString("nvs", j.optJSONObject("nvs") == null ? "{}" : j.getJSONObject("nvs").toString())
                .putString("uart", j.optString("uart", "")).putBoolean("wifiConfigured", j.optBoolean("wifiConfigured", false))
                .putString("wifiSsid", j.optString("wifiSsid", "")).putString("lastCustomData", j.optString("customDataBase64", ""))
                .putInt("bootCount", j.optInt("bootCount", 0)).apply();
        log("FLASH", "Imported virtual state");
    }
}
