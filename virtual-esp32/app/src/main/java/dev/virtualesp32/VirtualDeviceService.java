package dev.virtualesp32;

import android.app.*;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.*;
import android.os.*;
import android.content.pm.PackageManager;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Android BLE peripheral adapter and Security-0 Espressif provisioning device. */
public class VirtualDeviceService extends Service {
    public static final String ACTION_START="dev.virtualesp32.START", ACTION_STOP="dev.virtualesp32.STOP";
    private static final UUID SERVICE=UUID.fromString("7ab1ffff-6b3d-4f60-9e2f-13572468f00d");
    private static final UUID DESC=UUID.fromString("00002901-0000-1000-8000-00805f9b34fb");
    private static final UUID CCCD=UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final String[] ENDPOINTS={"proto-ver","prov-session","prov-scan","prov-config","prov-ctrl","custom-data","virtual-state","virtual-uart","virtual-gpio","virtual-flash"};
    private static final int[] IDS={0xff53,0xff51,0xff54,0xff52,0xff55,0xff56,0xff57,0xff58,0xff59,0xff5a};
    private BluetoothManager manager; private BluetoothGattServer server; private BluetoothLeAdvertiser advertiser; private boolean advertising; private String priorBluetoothName;
    private final Map<UUID,byte[]> replies=new HashMap<>(); private VirtualHal hal;
    private List<AndroidNetworkHal.AccessPoint> wifiResults=Collections.emptyList(); private AndroidNetworkHal network;
    @Override public void onCreate(){super.onCreate();hal=VirtualHal.get(this);network=new AndroidNetworkHal(this);createChannel();}
    @Override public int onStartCommand(Intent i,int f,int id){startForeground(7,notification()); if(i!=null&&ACTION_STOP.equals(i.getAction()))stopDevice();else startDevice();return START_STICKY;}
    @Override public android.os.IBinder onBind(Intent i){return null;}
    private void createChannel(){if(Build.VERSION.SDK_INT>=26){NotificationChannel c=new NotificationChannel("virtual_esp","Virtual ESP32",NotificationManager.IMPORTANCE_LOW);((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(c);}}
    private Notification notification(){Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,"virtual_esp"):new Notification.Builder(this);return b.setContentTitle("Virtual ESP32").setContentText(advertising?"BLE device is advertising":"Virtual device is running").setSmallIcon(android.R.drawable.stat_sys_data_bluetooth).build();}
    private void startDevice(){
        if(!hal.powered()){hal.setPowered(true);}
        manager=(BluetoothManager)getSystemService(BLUETOOTH_SERVICE); BluetoothAdapter a=manager==null?null:manager.getAdapter();
        if(a==null||!a.isEnabled()){hal.log("BLE","Bluetooth unavailable or disabled");broadcast();return;}
        try{
            server=manager.openGattServer(this,callback); if(server==null)throw new IllegalStateException("openGattServer returned null");
            BluetoothGattService s=new BluetoothGattService(SERVICE,BluetoothGattService.SERVICE_TYPE_PRIMARY);
            for(int n=0;n<ENDPOINTS.length;n++){
                UUID u=endpointUuid(IDS[n]); BluetoothGattCharacteristic c=new BluetoothGattCharacteristic(u,BluetoothGattCharacteristic.PROPERTY_READ|BluetoothGattCharacteristic.PROPERTY_WRITE|BluetoothGattCharacteristic.PROPERTY_NOTIFY,BluetoothGattCharacteristic.PERMISSION_READ|BluetoothGattCharacteristic.PERMISSION_WRITE);
                BluetoothGattDescriptor d=new BluetoothGattDescriptor(DESC,BluetoothGattDescriptor.PERMISSION_READ);d.setValue(ENDPOINTS[n].getBytes(StandardCharsets.UTF_8));c.addDescriptor(d);
                c.addDescriptor(new BluetoothGattDescriptor(CCCD,BluetoothGattDescriptor.PERMISSION_READ|BluetoothGattDescriptor.PERMISSION_WRITE));s.addCharacteristic(c);
                replies.put(u,"virtual-state".equals(ENDPOINTS[n])?stateJson().getBytes(StandardCharsets.UTF_8):"proto-ver".equals(ENDPOINTS[n])?protocolInfo().getBytes(StandardCharsets.UTF_8):new byte[0]);
            }
            server.addService(s);hal.log("BLE","GATT service starting: "+SERVICE);broadcast();
        }catch(SecurityException e){hal.setBleEnabled(false);hal.log("BLE","Permission denied: "+e.getMessage());broadcast();}catch(Exception e){hal.setBleEnabled(false);hal.log("BLE","Start failed: "+e.getMessage());broadcast();}
    }
    private UUID endpointUuid(int id){return UUID.fromString(String.format(Locale.US,"7ab1%04x-6b3d-4f60-9e2f-13572468f00d",id));}
    private final BluetoothGattServerCallback callback=new BluetoothGattServerCallback(){
        @Override public void onServiceAdded(int status,BluetoothGattService service){if(status!=BluetoothGatt.GATT_SUCCESS){hal.setBleEnabled(false);hal.log("BLE","GATT service add failed: "+status);broadcast();return;} startAdvertising();}
        @Override public void onConnectionStateChange(BluetoothDevice d,int status,int state){hal.log("BLE",state==BluetoothProfile.STATE_CONNECTED?"Client connected":"Client disconnected");broadcast();}
        @Override public void onCharacteristicReadRequest(BluetoothDevice d,int requestId,int offset,BluetoothGattCharacteristic c){String ep=endpointFor(c.getUuid());byte[] v="virtual-state".equals(ep)?stateJson().getBytes(StandardCharsets.UTF_8):"proto-ver".equals(ep)?protocolInfo().getBytes(StandardCharsets.UTF_8):replies.getOrDefault(c.getUuid(),new byte[0]);byte[] out=offset>=v.length?new byte[0]:Arrays.copyOfRange(v,offset,v.length);server.sendResponse(d,requestId,BluetoothGatt.GATT_SUCCESS,offset,out);}
        @Override public void onCharacteristicWriteRequest(BluetoothDevice d,int requestId,BluetoothGattCharacteristic c,boolean prepared,boolean responseNeeded,int offset,byte[] value){
            String ep=endpointFor(c.getUuid());byte[] response=handle(ep,value==null?new byte[0]:value);replies.put(c.getUuid(),response);
            if(responseNeeded)server.sendResponse(d,requestId,BluetoothGatt.GATT_SUCCESS,0,new byte[0]);
            c.setValue(response);BluetoothGattDescriptor cccd=c.getDescriptor(CCCD);if(cccd!=null&&cccd.getValue()!=null&&cccd.getValue().length>0&&cccd.getValue()[0]!=0)try{server.notifyCharacteristicChanged(d,c,false);}catch(SecurityException ignored){}
        }
        @Override public void onDescriptorReadRequest(BluetoothDevice d,int requestId,int offset,BluetoothGattDescriptor desc){byte[] v=desc.getValue()==null?new byte[0]:desc.getValue();server.sendResponse(d,requestId,BluetoothGatt.GATT_SUCCESS,offset,offset>=v.length?new byte[0]:Arrays.copyOfRange(v,offset,v.length));}
        @Override public void onDescriptorWriteRequest(BluetoothDevice d,int requestId,BluetoothGattDescriptor desc,boolean prepared,boolean responseNeeded,int offset,byte[] value){desc.setValue(value);if(responseNeeded)server.sendResponse(d,requestId,BluetoothGatt.GATT_SUCCESS,offset,value);}
    };
    private String endpointFor(UUID id){for(int n=0;n<IDS.length;n++)if(endpointUuid(IDS[n]).equals(id))return ENDPOINTS[n];return "unknown";}
    private byte[] handle(String ep,byte[] data){
        hal.log("GATT",ep+" write "+data.length+" bytes");
        try {
            switch(ep){
                case "virtual-state": return stateJson().getBytes(StandardCharsets.UTF_8);
                case "custom-data": hal.put("lastCustomData",android.util.Base64.encodeToString(data,android.util.Base64.NO_WRAP)); return data;
                case "virtual-uart": String line=new String(data,StandardCharsets.UTF_8);hal.put("uart",hal.get("uart","")+line);hal.log("UART","BLE RX "+line);return "OK".getBytes(StandardCharsets.UTF_8);
                case "virtual-gpio": return gpio(data);
                case "virtual-flash": return flash(data);
                case "proto-ver": return protocolInfo().getBytes(StandardCharsets.UTF_8);
                case "prov-session": return session(data);
                case "prov-config": return config(data);
                case "prov-scan": return scan(data);
                case "prov-ctrl": return control(data);
                default: return new byte[0];
            }
        } catch(Exception e){hal.log("PROV","Invalid request on "+ep+": "+e.getMessage());return new byte[0];}
    }
    private byte[] gpio(byte[] data)throws Exception {JSONObject req=new JSONObject(new String(data,StandardCharsets.UTF_8));int pin=req.optInt("pin",2);JSONObject pins=new JSONObject(hal.get("gpio","{}"));String key=Integer.toString(pin);if(req.has("value")){pins.put(key,req.optBoolean("value",false));hal.put("gpio",pins.toString());hal.log("GPIO","GPIO"+pin+"="+(pins.optBoolean(key,false)?"HIGH":"LOW"));}return new JSONObject().put("pin",pin).put("value",pins.optBoolean(key,false)).put("physical",false).toString().getBytes(StandardCharsets.UTF_8);}
    private byte[] flash(byte[] data)throws Exception {JSONObject req=new JSONObject(new String(data,StandardCharsets.UTF_8));String op=req.optString("op","read"),key=req.optString("key","");JSONObject nvs=new JSONObject(hal.get("nvs","{}"));if("write".equals(op)){nvs.put(key,req.optString("value",""));hal.put("nvs",nvs.toString());}else if("erase".equals(op)){nvs.remove(key);hal.put("nvs",nvs.toString());if("wifi.ssid".equals(key)||"wifi.passphrase".equals(key)){hal.put("wifiConfigured","false");hal.put("wifiApplied","false");hal.put("wifiSsid","");}}else if("erase_all".equals(op)){nvs=new JSONObject();hal.put("nvs","{}");hal.put("wifiConfigured","false");hal.put("wifiApplied","false");hal.put("wifiSsid","");}JSONObject result=new JSONObject().put("ok",true).put("op",op);if(!key.isEmpty()&&nvs.has(key))result.put("value","wifi.passphrase".equals(key)?"<redacted>":nvs.get(key));return result.toString().getBytes(StandardCharsets.UTF_8);}
    private String protocolInfo(){
        org.json.JSONArray caps=new org.json.JSONArray().put("no_pop");
        if(network.canScan())caps.put("wifi_scan");
        try{return new JSONObject().put("prov",new JSONObject().put("ver","v1.1").put("sec_ver",0).put("sec_patch_ver",0).put("cap",caps)).toString();}catch(Exception e){return "{}";}
    }
    private byte[] session(byte[] data)throws Exception {
        // SessionData.sec0.sc -> SessionData.sec0.sr, Status.Success (proto3 default).
        ProtoWire.Field sec0=ProtoWire.find(data,10,2); if(sec0==null)return new byte[0];
        ProtoWire.Field request=ProtoWire.find(sec0.bytes,20,2); if(request==null)return new byte[0];
        byte[] sec0Response=ProtoWire.concat(ProtoWire.var(1,1),ProtoWire.blob(21,new byte[0]));
        hal.log("PROV","Security 0 session established (plaintext; no authentication)");
        return ProtoWire.blob(10,sec0Response);
    }
    private byte[] config(byte[] data)throws Exception {
        long msg=ProtoWire.number(data,1,0);
        if(msg==2){byte[] cmd=ProtoWire.bytes(data,12);String ssid=new String(ProtoWire.bytes(cmd,1),StandardCharsets.UTF_8);String pass=new String(ProtoWire.bytes(cmd,2),StandardCharsets.UTF_8);if(ssid.isEmpty())return configResponse(3,4,1);
            JSONObject nvs=new JSONObject(hal.get("nvs","{}"));nvs.put("wifi.ssid",ssid);nvs.put("wifi.passphrase",pass);nvs.put("wifi.state","configured-not-connected");hal.put("nvs",nvs.toString());hal.put("wifiSsid",ssid);hal.put("wifiConfigured","true");hal.log("NVS","Stored virtual Wi-Fi credentials; Android phone network was not changed");return configResponse(3,13,0);
        }
        if(msg==4){hal.put("wifiApplied","true");AndroidNetworkHal.Link link=network.currentWifi();boolean joined=link!=null&&!link.ssid.isEmpty()&&link.ssid.equals(hal.get("wifiSsid",""));hal.log("NETWORK",joined?"Virtual station is network-backed by the phone's matching active Wi-Fi":"Wi-Fi config applied; phone is not on the provisioned SSID, so virtual station remains disconnected");return configResponse(5,15,0);}
        if(msg==0&&ProtoWire.find(data,10,2)!=null){AndroidNetworkHal.Link link=network.currentWifi();boolean joined=link!=null&&!link.ssid.isEmpty()&&link.ssid.equals(hal.get("wifiSsid",""))&&hal.get("wifiApplied","false").equals("true");byte[] resp;
            if(joined){byte[] connected=ProtoWire.concat(ProtoWire.blob(1,link.ipv4.getBytes(StandardCharsets.UTF_8)),ProtoWire.blob(3,link.ssid.getBytes(StandardCharsets.UTF_8)),ProtoWire.blob(4,macBytes(link.bssid)),ProtoWire.var(5,link.channel));resp=ProtoWire.blob(11,connected);}
            else resp=ProtoWire.concat(ProtoWire.var(2,2));
            return ProtoWire.message(1,1,11,resp);}
        if(msg==6){byte[] resp=ProtoWire.concat(ProtoWire.var(1,0),ProtoWire.var(2,2));return ProtoWire.message(1,7,17,resp);}
        return new byte[0];
    }
    private byte[] configResponse(int type,int field,int status){byte[] payload=field==11||field==17?new byte[0]:ProtoWire.var(1,status);return ProtoWire.message(1,type,field,payload);}
    private byte[] scan(byte[] data)throws Exception {
        long msg=ProtoWire.number(data,1,0);
        if(msg==0&&ProtoWire.find(data,10,2)!=null){if(!network.canScan()){hal.log("SCAN","Wi-Fi scan unavailable: Android location permission is required");return ProtoWire.concat(ProtoWire.var(1,1),ProtoWire.var(2,4));}
            wifiResults=network.scan();hal.log("SCAN","Android returned "+wifiResults.size()+" real cached Wi-Fi observations; a fresh radio scan is OS-controlled and may complete asynchronously");return ProtoWire.concat(ProtoWire.var(1,1),ProtoWire.var(2,0),ProtoWire.blob(11,new byte[0]));}
        if(msg==2){int count=wifiResults==null?0:wifiResults.size();byte[] status=ProtoWire.concat(ProtoWire.var(1,1),ProtoWire.var(2,count));return ProtoWire.concat(ProtoWire.var(1,3),ProtoWire.var(2,0),ProtoWire.blob(13,status));}
        if(msg==4){byte[] cmd=ProtoWire.bytes(data,14);int start=(int)ProtoWire.number(cmd,1,0),count=(int)ProtoWire.number(cmd,2,0);byte[] entries=new byte[0];if(wifiResults!=null)for(int i=Math.max(0,start);i<wifiResults.size()&&i<start+count;i++){AndroidNetworkHal.AccessPoint r=wifiResults.get(i);byte[] entry=ProtoWire.concat(ProtoWire.blob(1,r.ssid.getBytes(StandardCharsets.UTF_8)),ProtoWire.var(2,r.channel),ProtoWire.var(3,r.rssi),ProtoWire.blob(4,macBytes(r.bssid)),ProtoWire.var(5,r.auth));entries=ProtoWire.concat(entries,ProtoWire.blob(1,entry));}byte[] payload=ProtoWire.concat(ProtoWire.var(1,5),ProtoWire.var(2,0),ProtoWire.blob(15,entries));return payload;}
        return new byte[0];
    }
    private byte[] control(byte[] data)throws Exception {long msg=ProtoWire.number(data,1,-1);if(msg==1||msg==3){if(msg==1){hal.put("wifiConfigured","false");hal.put("wifiApplied","false");hal.put("wifiSsid","");JSONObject nvs=new JSONObject(hal.get("nvs","{}"));nvs.remove("wifi.ssid");nvs.remove("wifi.passphrase");nvs.remove("wifi.state");hal.put("nvs",nvs.toString());hal.log("POWER","Virtual Wi-Fi settings reset");}else hal.log("PROV","Virtual Wi-Fi reprovision requested");int field=msg==1?12:14;return ProtoWire.concat(ProtoWire.var(1,msg+1),ProtoWire.var(2,0),ProtoWire.blob(field,new byte[0]));}return new byte[0];}
    private byte[] macBytes(String mac){try{String[] p=mac.split(":");byte[] b=new byte[p.length];for(int i=0;i<p.length;i++)b[i]=(byte)Integer.parseInt(p[i],16);return b;}catch(Exception e){return new byte[0];}}
    private String stateJson(){try{JSONObject j=hal.exportState();JSONObject n=j.optJSONObject("nvs");if(n!=null)n.remove("wifi.passphrase");return j.toString();}catch(Exception e){return "{}";}}
    private void startAdvertising(){try{BluetoothAdapter a=manager.getAdapter();advertiser=a.getBluetoothLeAdvertiser();if(advertiser==null){hal.log("BLE","BLE advertiser unavailable");return;}
        try{priorBluetoothName=a.getName();a.setName(hal.deviceName());}catch(Exception e){hal.log("BLE","Could not set advertised local name: "+e.getMessage());}
        AdvertiseSettings set=new AdvertiseSettings.Builder().setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY).setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM).setConnectable(true).build();
        AdvertiseData data=new AdvertiseData.Builder().setIncludeDeviceName(false).addServiceUuid(new android.os.ParcelUuid(SERVICE)).build();
        AdvertiseData scan=new AdvertiseData.Builder().setIncludeDeviceName(true).build(); advertiser.startAdvertising(set,data,scan,advCallback);
    }catch(SecurityException e){hal.log("BLE","Advertising permission denied");}}
    private final AdvertiseCallback advCallback=new AdvertiseCallback(){@Override public void onStartSuccess(AdvertiseSettings s){advertising=true;hal.setBleEnabled(true);hal.log("BLE","Advertising as "+hal.deviceName()+"; service "+SERVICE);broadcast();}@Override public void onStartFailure(int e){advertising=false;hal.setBleEnabled(false);hal.log("BLE","Advertising failed code="+e);broadcast();}};
    private void stopDevice(){try{if(advertiser!=null&&advertising)advertiser.stopAdvertising(advCallback);}catch(Exception ignored){}try{if(server!=null)server.close();}catch(Exception ignored){}try{if(priorBluetoothName!=null&&manager!=null)manager.getAdapter().setName(priorBluetoothName);}catch(Exception ignored){}advertising=false;server=null;hal.setBleEnabled(false);hal.log("BLE","Peripheral stopped");broadcast();stopForeground(true);stopSelf();}
    private void broadcast(){Intent i=new Intent("dev.virtualesp32.STATE");i.setPackage(getPackageName());sendBroadcast(i);}
    @Override public void onDestroy(){stopDevice();super.onDestroy();}
}
