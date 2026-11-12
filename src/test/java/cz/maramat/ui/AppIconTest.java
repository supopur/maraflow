package cz.maramat.ui;

import org.junit.jupiter.api.Test;

import java.awt.Image;
import java.awt.image.BufferedImage;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AppIconTest {

    @Test
    void rendersAllMacOsSizesWithRoundedTransparentCornersAndWhiteTile() {
        List<Image> icons = AppIcon.createIconImages();
        assertEquals(8, icons.size(), "Expected 16, 32, 48, 64, 128, 256, 512, 1024 px icons");

        // Verify bundled classpath resources for macOS (.icns), Windows (.ico), Linux (.png), and all PNG sizes
        assertNotNull(AppIcon.class.getResource("/icons/MaraFlow.icns"), "MaraFlow.icns must be bundled");
        assertNotNull(AppIcon.class.getResource("/icons/MaraFlow.ico"), "MaraFlow.ico must be bundled");
        assertNotNull(AppIcon.class.getResource("/icons/MaraFlow.png"), "MaraFlow.png must be bundled");

        BufferedImage img1024 = AppIcon.loadIcon(1024);
        assertNotNull(img1024, "icon_1024.png must load from classpath");
        assertEquals(1024, img1024.getWidth());
        assertEquals(1024, img1024.getHeight());

        // Outer corner must be transparent (macOS rounded squircle margin)
        int cornerAlpha = (img1024.getRGB(0, 0) >>> 24) & 0xFF;
        assertEquals(0, cornerAlpha, "Outer corner should be transparent");

        // Inside the tile (e.g. near top-left padding inside rounded rect) must be solid white
        int whitePx = img1024.getRGB(220, 220);
        assertEquals(0xFFFFFFFF, whitePx, "Tile background should be white");

        // Center of MRF logo should contain #0f60aa blue pixels
        boolean foundBlue = false;
        for (int x = 200; x < 824 && !foundBlue; x += 4) {
            for (int y = 350; y < 700 && !foundBlue; y += 4) {
                if ((img1024.getRGB(x, y) & 0x00FFFFFF) == 0x0F60AA) {
                    foundBlue = true;
                }
            }
        }
        assertTrue(foundBlue, "Rendered icon should contain the #0f60aa vector logo");
    }
}
