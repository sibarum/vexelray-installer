package dev.vexelray.installer.core;

import javax.imageio.ImageIO;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Produces the {@code .ico} that Windows shortcuts need. An {@code .ico} is used as is; a square
 * {@code .png} is scaled down to the standard icon sizes and packed into one. SVG has to be
 * rasterised first, because Java has no SVG renderer and a shortcut can't show one anyway.
 */
final class IconFiles {
    private IconFiles() {}

    private static final int[] SIZES = {16, 24, 32, 48, 64, 128, 256};

    static void toIco(Path source, Path dest) throws IOException {
        String name = source.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".ico")) {
            Files.copy(source, dest, StandardCopyOption.REPLACE_EXISTING);
        } else if (name.endsWith(".png")) {
            Files.write(dest, icoFromPng(ImageIO.read(source.toFile()), source));
        } else if (name.endsWith(".svg")) {
            throw new InstallerException("icon " + source + " is an SVG; convert it to a square .png (256px or more) or an .ico first");
        } else {
            throw new InstallerException("icon " + source + " must be an .ico or a square .png");
        }
    }

    /**
     * The {@code .png} that Linux menus and file types need: a square {@code .png} as it is, or the largest frame of an
     * {@code .ico} when that frame is a PNG (as the 256px one usually is).
     */
    static void toPng(Path source, Path dest) throws IOException {
        String name = source.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".svg"))
            throw new InstallerException("icon " + source + " is an SVG; convert it to a square .png (256px or more) first");
        if (!name.endsWith(".png") && !name.endsWith(".ico"))
            throw new InstallerException("icon " + source + " must be an .ico or a square .png");
        Map.Entry<byte[], String> web = forWeb(source);
        if (!web.getValue().equals("png"))
            throw new InstallerException("icon " + source + " has no PNG frame for Linux to use; give a square .png (256px or more)");
        BufferedImage img = ImageIO.read(new java.io.ByteArrayInputStream(web.getKey()));
        if (img == null) throw new InstallerException("icon " + source + " is not a readable PNG");
        if (img.getWidth() != img.getHeight())
            throw new InstallerException("icon " + source + " must be square, not " + img.getWidth() + "x" + img.getHeight());
        Files.write(dest, web.getKey());
    }

    /** The width of a square PNG icon, which names its folder in an icon theme ({@code 256x256}). */
    static int pngSize(Path png) throws IOException {
        BufferedImage img = ImageIO.read(png.toFile());
        if (img == null) throw new InstallerException("not a readable PNG: " + png);
        return img.getWidth();
    }

    /**
     * The icon as a web page shows it: the largest frame of an {@code .ico} when that frame is a PNG (as the
     * 256px one usually is), else the file as it is. Returns the bytes and the extension to serve them under.
     */
    static Map.Entry<byte[], String> forWeb(Path source) throws IOException {
        byte[] d = Files.readAllBytes(source);
        String name = source.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!name.endsWith(".ico")) return Map.entry(d, name.substring(name.lastIndexOf('.') + 1));
        ByteBuffer b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN);
        int count = d.length >= 6 ? b.getShort(4) & 0xffff : 0;
        int best = -1, bestSize = -1;
        for (int i = 0; i < count && 6 + 16 * (i + 1) <= d.length; i++) {
            int e = 6 + 16 * i;
            int size = d[e] == 0 ? 256 : d[e] & 0xff;
            if (size > bestSize) { bestSize = size; best = e; }
        }
        if (best >= 0) {
            int len = b.getInt(best + 8), off = b.getInt(best + 12);
            if (off >= 0 && len > 8 && off + len <= d.length && d[off] == (byte) 0x89 && d[off + 1] == 'P'
                    && d[off + 2] == 'N' && d[off + 3] == 'G') {
                return Map.entry(java.util.Arrays.copyOfRange(d, off, off + len), "png");
            }
        }
        return Map.entry(d, "ico");
    }

    static byte[] icoFromPng(BufferedImage src, Path from) throws IOException {
        if (src == null) throw new InstallerException("icon " + from + " is not a readable PNG");
        if (src.getWidth() != src.getHeight())
            throw new InstallerException("icon " + from + " must be square, not " + src.getWidth() + "x" + src.getHeight());
        if (src.getWidth() < 16) throw new InstallerException("icon " + from + " is smaller than 16px");

        List<byte[]> frames = new ArrayList<>();
        List<Integer> sizes = new ArrayList<>();
        for (int size : SIZES) {
            if (size > src.getWidth()) break;
            frames.add(png(scale(src, size)));
            sizes.add(size);
        }
        if (sizes.get(sizes.size() - 1) < src.getWidth() && src.getWidth() < 256) {
            // keep the full-resolution original too (e.g. a 100px source)
            frames.add(png(src));
            sizes.add(src.getWidth());
        }

        int header = 6 + 16 * frames.size();
        int total = header;
        for (byte[] f : frames) total += f.length;
        ByteBuffer b = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
        b.putShort((short) 0).putShort((short) 1).putShort((short) frames.size());
        int offset = header;
        for (int i = 0; i < frames.size(); i++) {
            int s = sizes.get(i);
            b.put((byte) (s >= 256 ? 0 : s)).put((byte) (s >= 256 ? 0 : s));
            b.put((byte) 0).put((byte) 0);        // palette size, reserved
            b.putShort((short) 1).putShort((short) 32); // planes, bits per pixel
            b.putInt(frames.get(i).length).putInt(offset);
            offset += frames.get(i).length;
        }
        for (byte[] f : frames) b.put(f);
        return b.array();
    }

    /** Halves repeatedly while far above the target, then one final step: keeps small sizes crisp. */
    private static BufferedImage scale(BufferedImage src, int target) {
        BufferedImage cur = toArgb(src);
        while (cur.getWidth() / 2 >= target) cur = resize(cur, cur.getWidth() / 2);
        return cur.getWidth() == target ? cur : resize(cur, target);
    }

    private static BufferedImage toArgb(BufferedImage src) {
        if (src.getType() == BufferedImage.TYPE_INT_ARGB) return src;
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
        var g = out.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return out;
    }

    private static BufferedImage resize(BufferedImage src, int size) {
        BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        var g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(src, 0, 0, size, size, null);
        g.dispose();
        return out;
    }

    private static byte[] png(BufferedImage img) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }
}
