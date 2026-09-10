import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

public class MakeIcon {
    public static void main(String[] a) throws Exception {
        BufferedImage src = keyOut(ImageIO.read(new File(a[0])));
        System.out.println("source " + src.getWidth() + "x" + src.getHeight());
        System.out.println("corner alpha TL=" + (src.getRGB(0,0)>>>24)
            + " TR=" + (src.getRGB(src.getWidth()-1,0)>>>24)
            + " BL=" + (src.getRGB(0,src.getHeight()-1)>>>24)
            + " BR=" + (src.getRGB(src.getWidth()-1,src.getHeight()-1)>>>24));

        BufferedImage square;
        if (src.getWidth() == src.getHeight()) {
            // Already square, so it was composed as an icon and its margins are
            // somebody's decision. Trimming and re-padding would silently
            // re-frame it — the fox would grow and the breathing room shrink to
            // whatever the constant below happens to say.
            square = src;
            System.out.println("already square; framing left alone");
        } else {
            // Trim fully transparent margins so the art fills the square evenly.
            int minX=src.getWidth(), minY=src.getHeight(), maxX=-1, maxY=-1;
            for (int y=0;y<src.getHeight();y++) for (int x=0;x<src.getWidth();x++)
                if ((src.getRGB(x,y)>>>24) > 8) { if(x<minX)minX=x; if(x>maxX)maxX=x; if(y<minY)minY=y; if(y>maxY)maxY=y; }
            BufferedImage trimmed = src.getSubimage(minX, minY, maxX-minX+1, maxY-minY+1);
            System.out.println("trimmed to " + trimmed.getWidth() + "x" + trimmed.getHeight());

            // Pad to a square with ~6% breathing room, centred. Never stretched.
            int side = (int)(Math.max(trimmed.getWidth(), trimmed.getHeight()) * 1.12);
            square = new BufferedImage(side, side, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = square.createGraphics();
            g.drawImage(trimmed, (side-trimmed.getWidth())/2, (side-trimmed.getHeight())/2, null);
            g.dispose();
        }

        Path outPng = Paths.get(a[1]), outIco = Paths.get(a[2]);
        Files.createDirectories(outPng.getParent());
        Files.createDirectories(outIco.getParent());
        ImageIO.write(scale(square, 256), "png", outPng.toFile());

        int[] sizes = {16, 32, 48, 64, 128, 256};
        List<byte[]> blobs = new ArrayList<>();
        for (int s : sizes) blobs.add(s == 256 ? png(scale(square, s)) : bmp(scale(square, s)));

        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(outIco)))) {
            writeLE16(out, 0); writeLE16(out, 1); writeLE16(out, sizes.length);
            int offset = 6 + 16 * sizes.length;
            for (int i = 0; i < sizes.length; i++) {
                int s = sizes[i];
                out.writeByte(s >= 256 ? 0 : s); out.writeByte(s >= 256 ? 0 : s);
                out.writeByte(0); out.writeByte(0);
                writeLE16(out, 1); writeLE16(out, 32);
                writeLE32(out, blobs.get(i).length); writeLE32(out, offset);
                offset += blobs.get(i).length;
            }
            for (byte[] b : blobs) out.write(b);
        }
        System.out.println("wrote " + outPng + " and " + outIco + " (" + Files.size(outIco) + " bytes, " + sizes.length + " sizes)");
    }
    /**
     * Makes the checkerboard background transparent.
     *
     * The supplied art has the checker *painted in* rather than being an alpha
     * channel, so every corner reads fully opaque. Keying by colour alone would
     * punch holes in the fox, which is white through the face and the ears —
     * so the fill spreads from the border instead, and stops at the first
     * saturated pixel. The foxs own white is never reached, because it is not
     * connected to the edge.
     */
    static BufferedImage keyOut(BufferedImage in) {
        int w = in.getWidth(), h = in.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        out.getGraphics().drawImage(in, 0, 0, null);
        boolean[] bg = new boolean[w * h];
        java.util.ArrayDeque<int[]> queue = new java.util.ArrayDeque<>();
        for (int x = 0; x < w; x++) { seed(out, bg, queue, x, 0, w); seed(out, bg, queue, x, h - 1, w); }
        for (int y = 0; y < h; y++) { seed(out, bg, queue, 0, y, w); seed(out, bg, queue, w - 1, y, w); }
        int[][] step = {{1,0},{-1,0},{0,1},{0,-1}};
        while (!queue.isEmpty()) {
            int[] p = queue.poll();
            for (int[] d : step) {
                int nx = p[0] + d[0], ny = p[1] + d[1];
                if (nx >= 0 && ny >= 0 && nx < w && ny < h) seed(out, bg, queue, nx, ny, w);
            }
        }
        int cleared = 0;
        for (int i = 0; i < bg.length; i++) if (bg[i]) { out.setRGB(i % w, i / w, 0x00000000); cleared++; }
        System.out.println("keyed out " + cleared + " background px of " + (w * h));
        return out;
    }

    /** Background is bright and unsaturated; the fox is blue, or an edge blend. */
    static void seed(BufferedImage img, boolean[] bg, java.util.ArrayDeque<int[]> q, int x, int y, int w) {
        int i = y * w + x;
        if (bg[i]) return;
        int p = img.getRGB(x, y);
        int r = (p >> 16) & 0xff, g = (p >> 8) & 0xff, b = p & 0xff;
        int max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
        if (max < 200 || max - min > 14) return;
        bg[i] = true;
        q.add(new int[]{x, y});
    }


    static BufferedImage scale(BufferedImage in, int size) {
        BufferedImage o = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = o.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.drawImage(in, 0, 0, size, size, null);
        g.dispose();
        return o;
    }

    static byte[] png(BufferedImage img) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        ImageIO.write(img, "png", b);
        return b.toByteArray();
    }

    /** BITMAPINFOHEADER + BGRA bottom-up + AND mask, as classic ICO entries want. */
    static byte[] bmp(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        int maskStride = ((w + 31) / 32) * 4;
        ByteBuffer buf = ByteBuffer.allocate(40 + w * h * 4 + maskStride * h).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(40).putInt(w).putInt(h * 2).putShort((short)1).putShort((short)32)
           .putInt(0).putInt(w * h * 4).putInt(0).putInt(0).putInt(0).putInt(0);
        for (int y = h - 1; y >= 0; y--)
            for (int x = 0; x < w; x++) {
                int p = img.getRGB(x, y);
                buf.put((byte)(p)).put((byte)(p >> 8)).put((byte)(p >> 16)).put((byte)(p >>> 24));
            }
        for (int y = h - 1; y >= 0; y--) {
            byte[] row = new byte[maskStride];
            for (int x = 0; x < w; x++) if ((img.getRGB(x, y) >>> 24) == 0) row[x / 8] |= (byte)(0x80 >> (x % 8));
            buf.put(row);
        }
        return buf.array();
    }

    static void writeLE16(DataOutputStream o, int v) throws IOException { o.writeByte(v & 0xff); o.writeByte((v >> 8) & 0xff); }
    static void writeLE32(DataOutputStream o, int v) throws IOException { for (int i=0;i<4;i++) o.writeByte((v >> (8*i)) & 0xff); }
}
