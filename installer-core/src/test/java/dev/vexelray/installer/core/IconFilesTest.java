package dev.vexelray.installer.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class IconFilesTest {
    @TempDir Path tmp;

    Path png(int w, int h, String name) throws IOException {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < w; x++) for (int y = 0; y < h; y++) img.setRGB(x, y, 0xFF3FC06A);
        Path p = tmp.resolve(name);
        ImageIO.write(img, "png", p.toFile());
        return p;
    }

    @Test
    void pngBecomesAMultiSizeIco() throws IOException {
        Path ico = tmp.resolve("out.ico");
        IconFiles.toIco(png(256, 256, "a.png"), ico);
        ByteBuffer b = ByteBuffer.wrap(Files.readAllBytes(ico)).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(0, b.getShort(0));
        assertEquals(1, b.getShort(2));
        assertEquals(7, b.getShort(4)); // 16 24 32 48 64 128 256
        assertEquals(0, b.get(6 + 16 * 6));    // 256 is stored as 0
        assertEquals(16, b.get(6));
        // every frame is a real PNG
        for (int i = 0; i < 7; i++) {
            int len = b.getInt(6 + 16 * i + 8), off = b.getInt(6 + 16 * i + 12);
            assertEquals((byte) 0x89, b.get(off));
            assertEquals('P', b.get(off + 1));
            assertTrue(off + len <= b.limit());
        }
    }

    @Test
    void anOddSizeKeepsItsOriginalAsTheLargestFrame() throws IOException {
        Path ico = tmp.resolve("out.ico");
        IconFiles.toIco(png(192, 192, "b.png"), ico);
        ByteBuffer b = ByteBuffer.wrap(Files.readAllBytes(ico)).order(ByteOrder.LITTLE_ENDIAN);
        int n = b.getShort(4);
        assertEquals(7, n); // 16..128 plus the 192 original
        assertEquals((byte) 192, b.get(6 + 16 * (n - 1)));
    }

    @Test
    void anIcoIsCopiedUnchanged() throws IOException {
        Path src = tmp.resolve("x.ico");
        Files.write(src, new byte[] {0, 0, 1, 0, 0, 0});
        Path out = tmp.resolve("y.ico");
        IconFiles.toIco(src, out);
        assertArrayEquals(Files.readAllBytes(src), Files.readAllBytes(out));
    }

    @Test
    void badIconsAreRejectedWithAUsefulMessage() throws IOException {
        assertTrue(assertThrows(InstallerException.class, () -> IconFiles.toIco(tmp.resolve("m.svg"), tmp.resolve("o.ico"))).getMessage().contains("SVG"));
        assertTrue(assertThrows(InstallerException.class, () -> IconFiles.toIco(png(100, 50, "r.png"), tmp.resolve("o.ico"))).getMessage().contains("square"));
        assertTrue(assertThrows(InstallerException.class, () -> IconFiles.toIco(png(8, 8, "t.png"), tmp.resolve("o.ico"))).getMessage().contains("16px"));
    }
}
