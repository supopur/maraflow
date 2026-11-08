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

    public static Font ui(int style, float size) {
        return new Font(UI_FONT_FAMILY, style, Math.round(size));
    }

    public static Font mono(int style, float size) {
        return new Font(MONO_FONT_FAMILY, style, Math.round(size));
    }
}
