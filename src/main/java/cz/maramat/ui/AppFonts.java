package cz.maramat.ui;

import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public final class AppFonts {
    private static final String UI_FONT_FAMILY;
    private static final String MONO_FONT_FAMILY;

    static {
        Set<String> available = new HashSet<>();
        for (Font f : GraphicsEnvironment.getLocalGraphicsEnvironment().getAllFonts()) {
            available.add(f.getFamily().toLowerCase());
            available.add(f.getName().toLowerCase());
        }

        // Classic Win95/Win7 utilitarian technical typography:
        // Tahoma was the official Windows 2000 / XP / 95-crossover UI font, followed by Segoe UI in Win 7.
        // On Linux/Wine without Tahoma, Arial or Liberation Sans provides the same compact bitmap-like crispness.
        String[] preferredUi = {"Tahoma", "Segoe UI", "Arial", "Liberation Sans", "DejaVu Sans", "SansSerif"};
        String chosenUi = "SansSerif";
        for (String candidate : preferredUi) {
            if (available.contains(candidate.toLowerCase())) {
                chosenUi = candidate;
                break;
            }
        }
        UI_FONT_FAMILY = chosenUi;

        String[] preferredMono = {"Consolas", "Lucida Console", "Liberation Mono", "DejaVu Sans Mono", "Monospaced"};
        String chosenMono = "Monospaced";
        for (String candidate : preferredMono) {
            if (available.contains(candidate.toLowerCase())) {
                chosenMono = candidate;
                break;
            }
        }
        MONO_FONT_FAMILY = chosenMono;
    }

    private AppFonts() {}

    public static String getUiFontFamily() {
        return UI_FONT_FAMILY;
    }

    public static String getMonoFontFamily() {
        return MONO_FONT_FAMILY;
    }

    private static final java.util.concurrent.ConcurrentHashMap<Long, Font> UI_FONT_CACHE = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentHashMap<Long, Font> MONO_FONT_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    public static Font ui(int style, float size) {
        int pt = Math.round(size);
        long key = ((long) style << 32) | (pt & 0xFFFFFFFFL);
        return UI_FONT_CACHE.computeIfAbsent(key, k -> new Font(UI_FONT_FAMILY, style, pt));
    }

    public static Font uiPlain() {
        return ui(Font.PLAIN, scaled(11));
    }

    public static Font uiBold() {
        return ui(Font.BOLD, scaled(11));
    }

    public static Font uiSmall() {
        return ui(Font.PLAIN, scaled(10));
    }

    public static Font mono(int style, float size) {
        int pt = Math.round(size);
        long key = ((long) style << 32) | (pt & 0xFFFFFFFFL);
        return MONO_FONT_CACHE.computeIfAbsent(key, k -> new Font(MONO_FONT_FAMILY, style, pt));
    }

    public static Font canvasMono(float baseSize) {
        return mono(Font.PLAIN, scaled(Math.round(baseSize)));
    }

    public static Font canvasMonoBold(float baseSize) {
        return mono(Font.BOLD, scaled(Math.round(baseSize)));
    }

    public static int scaled(int basePx) {
        return cz.maramat.settings.AppSettings.get().scale(basePx);
    }

    public static java.awt.Frame resolveFrameOwner(java.awt.Component c) {
        java.awt.Window w = javax.swing.SwingUtilities.getWindowAncestor(c);
        while (w != null && !(w instanceof java.awt.Frame)) {
            w = w.getOwner();
        }
        return (java.awt.Frame) w;
    }
}
