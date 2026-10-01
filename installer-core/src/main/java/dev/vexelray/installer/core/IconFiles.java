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
