package dev.virtualesp32;

import java.util.Arrays;

/** Host-JVM checks for the protobuf wire framing used by Android's provisioning service. */
public final class ProtoWireTest {
    public static void main(String[] args) throws Exception {
        byte[] session=ProtoWire.blob(10,ProtoWire.concat(ProtoWire.var(1,1),ProtoWire.blob(21,new byte[0])));
        byte[] expected={(byte)0x52,0x05,0x08,0x01,(byte)0xaa,0x01,0x00};
        if(!Arrays.equals(session,expected))throw new AssertionError("Security 0 SessionData response wire bytes");
        byte[] wifiRequest=ProtoWire.concat(ProtoWire.var(1,2),ProtoWire.blob(12,ProtoWire.concat(ProtoWire.blob(1,"ssid".getBytes("UTF-8")),ProtoWire.blob(2,"pw".getBytes("UTF-8")))));
        if(ProtoWire.number(wifiRequest,1,-1)!=2)throw new AssertionError("NetworkConfig command enum");
        byte[] cmd=ProtoWire.bytes(wifiRequest,12);
        if(!"ssid".equals(new String(ProtoWire.bytes(cmd,1),"UTF-8"))||!"pw".equals(new String(ProtoWire.bytes(cmd,2),"UTF-8")))throw new AssertionError("Wi-Fi SSID/passphrase fields");
        byte[] scanStart=ProtoWire.blob(10,ProtoWire.concat(ProtoWire.var(1,1),ProtoWire.var(2,0)));
        if(ProtoWire.number(scanStart,1,0)!=0||ProtoWire.find(scanStart,10,2)==null)throw new AssertionError("default enum scan-start command");
        byte[] response=ProtoWire.message(1,3,13,ProtoWire.var(1,0));
        if(ProtoWire.number(response,1,-1)!=3||ProtoWire.find(response,13,2)==null)throw new AssertionError("Wi-Fi config response envelope");
        System.out.println("PASS: Security 0 session envelope, Wi-Fi config command fields, zero-valued scan command, config response envelope");
    }
}
