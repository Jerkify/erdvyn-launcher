import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

public final class IconGenerator {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("input output");
        BufferedImage source = ImageIO.read(Path.of(args[0]).toFile());
        boolean ico=args[1].toLowerCase().endsWith(".ico");int size = ico?256:512, inset = ico?9:18;
        BufferedImage output = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = output.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setComposite(AlphaComposite.Clear);g.fillRect(0,0,size,size);g.setComposite(AlphaComposite.SrcOver);
        Ellipse2D circle = new Ellipse2D.Double(inset,inset,size-inset*2.0,size-inset*2.0);
        g.setColor(new Color(7,10,17));g.fill(circle);
        var previous = g.getClip();g.clip(circle);g.drawImage(source,inset,inset,size-inset*2,size-inset*2,null);g.setClip(previous);
        g.setStroke(new BasicStroke(14,BasicStroke.CAP_BUTT,BasicStroke.JOIN_MITER));g.setColor(new Color(218,184,92));g.draw(circle);
        g.setStroke(new BasicStroke(3));g.setColor(new Color(255,224,139,190));g.draw(new Ellipse2D.Double(inset+10,inset+10,size-(inset+10)*2.0,size-(inset+10)*2.0));
        g.dispose();
        if(!ico){ImageIO.write(output,"png",Path.of(args[1]).toFile());return;}
        ByteArrayOutputStream pngBytes=new ByteArrayOutputStream();ImageIO.write(output,"png",pngBytes);byte[] png=pngBytes.toByteArray();
        try(DataOutputStream data=new DataOutputStream(Files.newOutputStream(Path.of(args[1])))){
            writeLeShort(data,0);writeLeShort(data,1);writeLeShort(data,1);data.writeByte(0);data.writeByte(0);data.writeByte(0);data.writeByte(0);writeLeShort(data,1);writeLeShort(data,32);writeLeInt(data,png.length);writeLeInt(data,22);data.write(png);
        }
    }
    private static void writeLeShort(DataOutputStream out,int value)throws Exception{out.writeByte(value&255);out.writeByte(value>>>8&255);}
    private static void writeLeInt(DataOutputStream out,int value)throws Exception{out.writeByte(value&255);out.writeByte(value>>>8&255);out.writeByte(value>>>16&255);out.writeByte(value>>>24&255);}
}
