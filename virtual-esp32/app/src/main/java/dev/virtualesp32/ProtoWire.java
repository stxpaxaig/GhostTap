package dev.virtualesp32;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Minimal protobuf wire-format reader/writer for the Espressif provisioning schemas.
 * Schema numbers are kept in PROTOCOL_NOTES.md and the upstream .proto files. */
final class ProtoWire {
    static final class Field { final int number, wire; final long value; final byte[] bytes;
        Field(int n,int w,long v,byte[] b){number=n;wire=w;value=v;bytes=b;} }
    static List<Field> parse(byte[] b) throws IOException {
        List<Field> out=new ArrayList<>(); int[] p={0};
        while(p[0]<b.length){long key=varint(b,p);int n=(int)(key>>>3),w=(int)(key&7);if(n==0)throw new IOException("invalid protobuf field");
            if(w==0)out.add(new Field(n,w,varint(b,p),null));
            else if(w==2){long len=varint(b,p);if(len<0||len>b.length-p[0])throw new IOException("invalid protobuf length");byte[] x=new byte[(int)len];System.arraycopy(b,p[0],x,0,x.length);p[0]+=x.length;out.add(new Field(n,w,0,x));}
            else if(w==1){p[0]+=8; if(p[0]>b.length)throw new IOException("truncated protobuf");}
            else if(w==5){p[0]+=4; if(p[0]>b.length)throw new IOException("truncated protobuf");}
            else throw new IOException("unsupported protobuf wire type "+w);
        } return out;
    }
    static Field find(byte[] b,int field,int wire) throws IOException {for(Field f:parse(b))if(f.number==field&&f.wire==wire)return f;return null;}
    static long number(byte[] b,int field,long fallback) throws IOException {Field f=find(b,field,0);return f==null?fallback:f.value;}
    static byte[] bytes(byte[] b,int field) throws IOException {Field f=find(b,field,2);return f==null?new byte[0]:f.bytes;}
    static byte[] var(int field,long value){ByteArrayOutputStream o=new ByteArrayOutputStream();rawVar(o,((long)field<<3));rawVar(o,value);return o.toByteArray();}
    static byte[] blob(int field,byte[] data){ByteArrayOutputStream o=new ByteArrayOutputStream();rawVar(o,((long)field<<3)|2);rawVar(o,data.length);o.write(data,0,data.length);return o.toByteArray();}
    static byte[] message(int enumField,long enumValue,int payloadField,byte[] payload){return concat(var(enumField,enumValue),blob(payloadField,payload));}
    static byte[] concat(byte[]... parts){ByteArrayOutputStream o=new ByteArrayOutputStream();for(byte[] x:parts)o.write(x,0,x.length);return o.toByteArray();}
    private static void rawVar(ByteArrayOutputStream o,long v){while((v&~0x7fL)!=0){o.write((int)(v&0x7f)|0x80);v>>>=7;}o.write((int)v);}
    private static long varint(byte[] b,int[] p)throws IOException{long v=0;int shift=0;while(shift<64){if(p[0]>=b.length)throw new IOException("truncated varint");int x=b[p[0]++]&255;v|=(long)(x&127)<<shift;if((x&128)==0)return v;shift+=7;}throw new IOException("oversized varint");}
}
