package dev.virtualesp32;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import java.net.Inet4Address;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Android-backed Wi-Fi adapter. ESP-compatible handlers use this boundary instead of Android APIs. */
final class AndroidNetworkHal {
    static final class AccessPoint { final String ssid,bssid; final int rssi,channel,auth;
        AccessPoint(String s,String b,int r,int c,int a){ssid=s;bssid=b;rssi=r;channel=c;auth=a;} }
    static final class Link { final String ssid,bssid,ipv4; final int frequency,channel;
        Link(String s,String b,String ip,int f){ssid=s;bssid=b;ipv4=ip;frequency=f;channel=frequencyToChannel(f);} }
    private final Context context; private final WifiManager wifi; private final ConnectivityManager connectivity;
    AndroidNetworkHal(Context c){context=c.getApplicationContext();wifi=(WifiManager)context.getSystemService(Context.WIFI_SERVICE);connectivity=(ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);}
    boolean canScan(){return wifi!=null&&(Build.VERSION.SDK_INT<23||context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED);}
    List<AccessPoint> scan(){if(wifi==null||!canScan())return Collections.emptyList();try{wifi.startScan();List<ScanResult> raw=wifi.getScanResults();List<AccessPoint> out=new ArrayList<>();for(ScanResult r:raw){if(r.SSID==null||r.SSID.isEmpty())continue;out.add(new AccessPoint(r.SSID,r.BSSID,r.level,frequencyToChannel(r.frequency),auth(r.capabilities)));}return out;}catch(SecurityException e){return Collections.emptyList();}}
    Link currentWifi(){if(connectivity==null)return null;try{Network n=connectivity.getActiveNetwork();NetworkCapabilities nc=connectivity.getNetworkCapabilities(n);if(n==null||nc==null||!nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))return null;String ssid="",bssid="";int frequency=0;if(wifi!=null&&canScan()){WifiInfo i=wifi.getConnectionInfo();if(i!=null){ssid=clean(i.getSSID());bssid=i.getBSSID()==null?"":i.getBSSID();frequency=i.getFrequency();}}String ipv4="";LinkProperties lp=connectivity.getLinkProperties(n);if(lp!=null)for(android.net.LinkAddress a:lp.getLinkAddresses())if(a.getAddress() instanceof Inet4Address){ipv4=a.getAddress().getHostAddress();break;}return new Link(ssid,bssid,ipv4,frequency);}catch(SecurityException e){return null;}}
    private String clean(String s){if(s==null||s.equals(WifiManager.UNKNOWN_SSID))return "";return s.length()>=2&&s.startsWith("\"")&&s.endsWith("\"")?s.substring(1,s.length()-1):s;}
    private static int frequencyToChannel(int f){if(f==2484)return 14;if(f>=2412&&f<=2472)return (f-2407)/5;if(f>=5000)return (f-5000)/5;return 0;}
    private int auth(String c){if(c==null)return 0;if(c.contains("SAE"))return 6;if(c.contains("WEP"))return 1;if(c.contains("WPA2")&&c.contains("WPA"))return 4;if(c.contains("WPA2"))return 3;if(c.contains("WPA"))return 2;return 0;}
}
