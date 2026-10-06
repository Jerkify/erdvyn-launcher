import tr.erdvyn.launcher.ErdvynMark;

import javax.imageio.ImageIO;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes the app icons from the launcher's own mark (src/main/java/tr/erdvyn/launcher/ErdvynMark.java): a .png
 * output gets the 256 px mark, a .ico output every size Windows asks for. Each size is drawn, not resampled, so the
 * small ones keep their own simplified, pixel-sharp version. Compile it together with ErdvynMark.java.
 */
public final class IconGenerator {
    // Every size Windows asks for (title bar, taskbar, Explorer views at 100-200% scaling).
    private static final int[] ICO_SIZES={16,20,24,32,40,48,64,128,256};

    /** {@code --flat} before .png outputs writes the solid-letter variant (for game GUI textures that are shrunk without filtering). */
    public static void main(String[] args) throws Exception {
        if (args.length == 0) throw new IllegalArgumentException("[--flat] output.png | output.ico ...");
        boolean flat = false;
        for (String output : args) {
            if ("--flat".equals(output)) { flat = true; continue; }
            if(!output.toLowerCase().endsWith(".ico")){ImageIO.write(ErdvynMark.render(256,flat),"png",Path.of(output).toFile());continue;}
            List<byte[]> pngs=new ArrayList<>();
            for(int size:ICO_SIZES){ByteArrayOutputStream bytes=new ByteArrayOutputStream();ImageIO.write(ErdvynMark.render(size),"png",bytes);pngs.add(bytes.toByteArray());}
            try(DataOutputStream data=new DataOutputStream(Files.newOutputStream(Path.of(output)))){
                writeLeShort(data,0);writeLeShort(data,1);writeLeShort(data,ICO_SIZES.length);
                int offset=6+16*ICO_SIZES.length;
                for(int i=0;i<ICO_SIZES.length;i++){int s=ICO_SIZES[i]>=256?0:ICO_SIZES[i];data.writeByte(s);data.writeByte(s);data.writeByte(0);data.writeByte(0);writeLeShort(data,1);writeLeShort(data,32);writeLeInt(data,pngs.get(i).length);writeLeInt(data,offset);offset+=pngs.get(i).length;}
                for(byte[] png:pngs)data.write(png);
            }
        }
    }

    private static void writeLeShort(DataOutputStream out,int value)throws Exception{out.writeByte(value&255);out.writeByte(value>>>8&255);}
    private static void writeLeInt(DataOutputStream out,int value)throws Exception{out.writeByte(value&255);out.writeByte(value>>>8&255);out.writeByte(value>>>16&255);out.writeByte(value>>>24&255);}
}
