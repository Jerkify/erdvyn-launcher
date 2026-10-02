import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class IconGenerator {
    // Every size Windows asks for (title bar, taskbar, Explorer views at 100-200% scaling).
    private static final int[] ICO_SIZES={16,20,24,32,40,48,64,128,256};

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("input output");
        BufferedImage source = cropToContent(ImageIO.read(Path.of(args[0]).toFile()));
        // 256 px: the source logo is 256 px, larger output would only be an upscale.
        if(!args[1].toLowerCase().endsWith(".ico")){ImageIO.write(render(source,256),"png",Path.of(args[1]).toFile());return;}
        List<byte[]> pngs=new ArrayList<>();
        for(int size:ICO_SIZES){ByteArrayOutputStream bytes=new ByteArrayOutputStream();ImageIO.write(render(source,size),"png",bytes);pngs.add(bytes.toByteArray());}
        try(DataOutputStream data=new DataOutputStream(Files.newOutputStream(Path.of(args[1])))){
            writeLeShort(data,0);writeLeShort(data,1);writeLeShort(data,ICO_SIZES.length);
            int offset=6+16*ICO_SIZES.length;
            for(int i=0;i<ICO_SIZES.length;i++){int s=ICO_SIZES[i]>=256?0:ICO_SIZES[i];data.writeByte(s);data.writeByte(s);data.writeByte(0);data.writeByte(0);writeLeShort(data,1);writeLeShort(data,32);writeLeInt(data,pngs.get(i).length);writeLeInt(data,offset);offset+=pngs.get(i).length;}
            for(byte[] png:pngs)data.write(png);
        }
    }

    /** Square crop around the opaque outline so the symbol fills the icon instead of floating in padding. */
    private static BufferedImage cropToContent(BufferedImage source){
        int minX=source.getWidth(),minY=source.getHeight(),maxX=-1,maxY=-1;
        for(int y=0;y<source.getHeight();y++)for(int x=0;x<source.getWidth();x++)if((source.getRGB(x,y)>>>24)>24){minX=Math.min(minX,x);minY=Math.min(minY,y);maxX=Math.max(maxX,x);maxY=Math.max(maxY,y);}
        if(maxX<0)return source;
        int side=Math.max(maxX-minX,maxY-minY)+1,cx=(minX+maxX)/2,cy=(minY+maxY)/2;
        BufferedImage square=new BufferedImage(side,side,BufferedImage.TYPE_INT_ARGB);Graphics2D g=square.createGraphics();
        g.drawImage(source,side/2-cx,side/2-cy,null);g.dispose();return square;
    }

    /** Transparent icon, no badge. Small sizes thicken the thin outline first so it survives downscaling. */
    private static BufferedImage render(BufferedImage content,int size){
        int inset=Math.max(1,Math.round(size*.06f)),target=size-inset*2;
        // ~1.3 px final stroke at 16 px; 0 above 48 px where the authored stroke already reads.
        int grow=size>48?0:(int)Math.ceil(content.getWidth()*(size<=16?.022:size<=24?.015:size<=32?.011:.007));
        BufferedImage image=grow==0?content:dilate(content,grow);
        while(image.getWidth()>target*2)image=scale(image,image.getWidth()/2);
        BufferedImage output=new BufferedImage(size,size,BufferedImage.TYPE_INT_ARGB);Graphics2D g=output.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BICUBIC);g.setRenderingHint(RenderingHints.KEY_RENDERING,RenderingHints.VALUE_RENDER_QUALITY);
        g.setComposite(AlphaComposite.Clear);g.fillRect(0,0,size,size);g.setComposite(AlphaComposite.SrcOver);
        g.drawImage(image,inset,inset,target,target,null);g.dispose();return flatten(output);
    }

    /** The mark is one flat amber: resampling leaves garbage RGB in near-transparent pixels, so repaint every pixel that colour. */
    private static BufferedImage flatten(BufferedImage image){
        long r=0,gr=0,b=0,n=0;
        for(int y=0;y<image.getHeight();y++)for(int x=0;x<image.getWidth();x++){int argb=image.getRGB(x,y);if((argb>>>24)>240){r+=argb>>16&255;gr+=argb>>8&255;b+=argb&255;n++;}}
        if(n==0)return image;int rgb=(int)(r/n)<<16|(int)(gr/n)<<8|(int)(b/n);
        for(int y=0;y<image.getHeight();y++)for(int x=0;x<image.getWidth();x++){int alpha=image.getRGB(x,y)>>>24;image.setRGB(x,y,alpha<8?0:alpha<<24|rgb);}
        return image;
    }

    // ponytail: disc stamp dilation is O(r^2) draws; fine for a 256 px monochrome logo, use a real morphology op for big art.
    private static BufferedImage dilate(BufferedImage source,int radius){
        BufferedImage out=new BufferedImage(source.getWidth()+radius*2,source.getHeight()+radius*2,BufferedImage.TYPE_INT_ARGB);Graphics2D g=out.createGraphics();
        for(int dy=-radius;dy<=radius;dy++)for(int dx=-radius;dx<=radius;dx++)if(dx*dx+dy*dy<=radius*radius)g.drawImage(source,radius+dx,radius+dy,null);
        g.dispose();return out;
    }

    private static BufferedImage scale(BufferedImage source,int size){
        BufferedImage out=new BufferedImage(size,size,BufferedImage.TYPE_INT_ARGB);Graphics2D g=out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BILINEAR);g.drawImage(source,0,0,size,size,null);g.dispose();return out;
    }

    private static void writeLeShort(DataOutputStream out,int value)throws Exception{out.writeByte(value&255);out.writeByte(value>>>8&255);}
    private static void writeLeInt(DataOutputStream out,int value)throws Exception{out.writeByte(value&255);out.writeByte(value>>>8&255);out.writeByte(value>>>16&255);out.writeByte(value>>>24&255);}
}
