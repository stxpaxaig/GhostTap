package dev.virtualesp32;

import android.content.Context;
import android.net.Uri;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;

/** Imports the classic ESP32 merged flash layout that Espressif's QEMU machine expects. */
final class FirmwareImageManager {
    static final class Inspection {
        final boolean valid; final File image; final String summary;
        Inspection(boolean ok,File f,String s){valid=ok;image=f;summary=s;}
    }
    static Inspection importAndInspect(Context context,Uri uri)throws Exception {
        File dir=new File(context.getFilesDir(),"esp32-qemu");if(!dir.exists()&&!dir.mkdirs())throw new IllegalStateException("Cannot create private firmware directory");
        File temp=new File(dir,"flash-image.importing");File image=new File(dir,"flash-image.bin");
        try(InputStream in=context.getContentResolver().openInputStream(uri);FileOutputStream out=new FileOutputStream(temp)){
            if(in==null)throw new IllegalArgumentException("Selected file could not be opened");byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)out.write(b,0,n);out.getFD().sync();
        }
        Inspection check=inspect(temp);
        if(!check.valid){temp.delete();return new Inspection(false,null,check.summary);}
        if(image.exists()&&!image.delete()){temp.delete();throw new IllegalStateException("Cannot replace prior private flash image");}
        if(!temp.renameTo(image)){temp.delete();throw new IllegalStateException("Could not store imported flash image");}
        return new Inspection(true,image,check.summary);
    }
    /** Removes the imported flash image (EJECT_FIRMWARE): deletes flash-image.bin
     * if present. Returns true when no imported image remains — already absent
     * counts as removed. Returns false only when the file exists and cannot be
     * deleted, so the caller can report failure instead of claiming ejection. */
    static boolean ejectImportedImage(Context context){
        File dir=new File(context.getFilesDir(),"esp32-qemu");
        File image=new File(dir,"flash-image.bin");
        if(!image.exists())return true;
        return image.delete();
    }
    static Inspection inspect(File f){
        try(RandomAccessFile r=new RandomAccessFile(f,"r")){
            long size=r.length();if(size!=2L<<20&&size!=4L<<20&&size!=8L<<20&&size!=16L<<20)return new Inspection(false,null,"QEMU ESP32 expects a 2, 4, 8, or 16 MiB flash image; this file is "+size+" bytes.");
            if(readU8(r,0x1000)!=0xe9)return new Inspection(false,null,"No ESP image header at bootloader offset 0x1000. Select an ESP-IDF merged flash image, not a standalone app .bin.");
            int apps=0;long firstApp=-1;
            for(long off=0x8000;off+32<=size;off+=32){int m0=readU8(r,off),m1=readU8(r,off+1);if(m0==0xff&&m1==0xff)break;if(m0==0xeb&&m1==0xeb)break;
                // ESP-IDF partition table magic is the byte sequence AA 50 (uint16 0x50AA little-endian).
                if(m0!=0xaa||m1!=0x50)break;int type=readU8(r,off+2);if(type==0){long part=(readU8(r,off+4)&255L)|((readU8(r,off+5)&255L)<<8)|((readU8(r,off+6)&255L)<<16)|((readU8(r,off+7)&255L)<<24);if(part<size&&readU8(r,part)==0xe9){if(firstApp<0)firstApp=part;apps++;}}}
            if(apps==0)return new Inspection(false,null,"Bootloader header found, but the default partition table at 0x8000 has no app partition with an ESP image header.");
            return new Inspection(true,f,String.format(java.util.Locale.US,"Merged-image structure found: %d MiB flash, bootloader @0x1000, %d app image(s) in partition table (first @0x%x). Structural check only; full firmware/SoC compatibility is not proven.",size/(1024*1024),apps,firstApp));
        }catch(Exception e){return new Inspection(false,null,"Could not inspect flash image: "+e.getMessage());}
    }
    static File efuseBackingFile(Context context)throws Exception {
        File dir=new File(context.getFilesDir(),"esp32-qemu");if(!dir.exists()&&!dir.mkdirs())throw new IllegalStateException("Cannot create private emulator directory");File f=new File(dir,"efuse.bin");
        if(!f.exists()){try(FileOutputStream out=new FileOutputStream(f)){out.write(new byte[124]);out.getFD().sync();}}
        if(f.length()!=124)throw new IllegalStateException("Classic ESP32 QEMU eFuse backing file must be 124 bytes");return f;
    }
    static File esp32RomDir(Context context)throws Exception {
        File dir=new File(context.getFilesDir(),"esp32-qemu");if(!dir.exists()&&!dir.mkdirs())throw new IllegalStateException("Cannot create private emulator directory");
        // Both ROMs required by the ESP32 machine (CPU0 and CPU1)
        String[] roms={"esp32-v3-rom.bin","esp32-v3-rom-app.bin"};
        for(String romName : roms){
            File f=new File(dir,romName);
            boolean needsCopy=!f.exists()||f.length()<400000;
            if(needsCopy){
                try(java.io.InputStream in=context.getAssets().open(romName);
                    java.io.FileOutputStream out=new java.io.FileOutputStream(f)){
                    byte[] buf=new byte[8192];int n;long total=0;
                    while((n=in.read(buf))!=-1){out.write(buf,0,n);total+=n;}
                    out.getFD().sync();
                    if(total<400000)throw new IllegalStateException("ROM copy failed for "+romName+": only "+total+" bytes");
                }
            }
            if(!f.exists()||!f.canRead())throw new IllegalStateException("ESP32 ROM not available at "+f.getAbsolutePath());
        }
        return dir;
    }
    private static int readU8(RandomAccessFile f,long offset)throws Exception{f.seek(offset);return f.readUnsignedByte();}
}
