package cz.maramat.ui;

import javax.imageio.ImageIO;
import java.awt.Image;
import java.awt.Taskbar;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads the bundled MaraFlow icon resources ({@code /icons/icon_*.png}) and applies them
 * to application windows (Windows/Linux) and the OS Dock/Taskbar (macOS).
 */
public final class AppIcon {

    private static final int[] ICON_SIZES = {16, 32, 48, 64, 128, 256, 512, 1024};

    private AppIcon() {}

    /**
     * Sets the OS-level Dock/Taskbar icon (e.g. macOS Dock) using the bundled 512 px PNG resource.
     */
    public static void applyDockIcon() {
        try {
            Toolkit.getDefaultToolkit();
            if (Taskbar.isTaskbarSupported()) {
                Taskbar taskbar = Taskbar.getTaskbar();
                if (taskbar.isSupported(Taskbar.Feature.ICON_IMAGE)) {
                    BufferedImage dockImg = loadIcon(512);
                    if (dockImg != null) {
                        taskbar.setIconImage(dockImg);
                    }
                }
            }
        } catch (UnsupportedOperationException | SecurityException ignored) {
            // Headless or platform without Taskbar icon support
        }
    }

    /**
     * Applies multi-resolution window icons (Windows/Linux/macOS) and updates the Dock/Taskbar icon.
     */
    public static void apply(Window window) {
        if (window != null) {
            List<Image> images = createIconImages();
            if (!images.isEmpty()) {
                window.setIconImages(images);
            }
        }
        applyDockIcon();
    }

    /**
     * Loads all bundled PNG icon sizes ({@code 16..1024} px) from {@code /icons/icon_<size>.png}.
     */
    public static List<Image> createIconImages() {
        List<Image> list = new ArrayList<>(ICON_SIZES.length);
        for (int size : ICON_SIZES) {
            BufferedImage img = loadIcon(size);
            if (img != null) {
                list.add(img);
            }
        }
        return list;
    }

    public static BufferedImage loadIcon(int size) {
        String path = "/icons/icon_" + size + ".png";
        try (InputStream in = AppIcon.class.getResourceAsStream(path)) {
            if (in != null) {
                return ImageIO.read(in);
            }
        } catch (IOException ignored) {
            // Resource unreadable
        }
        return null;
    }
}
