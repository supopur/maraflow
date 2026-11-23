package cz.maramat.settings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Resolves OS-appropriate application configuration directories so settings
 * and fallback secrets are never scattered in the user's home root.
 */
public final class OsPaths {
    private OsPaths() {}

    public static Path getConfigDir(String appName) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String home = System.getProperty("user.home", ".");
        Path base;
        if (os.contains("mac")) {
            base = Path.of(home, "Library", "Application Support", "Maramat", appName);
        } else if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            if (appData != null && !appData.isBlank()) {
                base = Path.of(appData, "Maramat", appName);
            } else {
                base = Path.of(home, "AppData", "Roaming", "Maramat", appName);
            }
        } else {
            String xdg = System.getenv("XDG_CONFIG_HOME");
            if (xdg != null && !xdg.isBlank()) {
                base = Path.of(xdg, "maramat", appName.toLowerCase(Locale.ROOT));
            } else {
                base = Path.of(home, ".config", "maramat", appName.toLowerCase(Locale.ROOT));
            }
        }
        try {
            Files.createDirectories(base);
        } catch (IOException ignored) {
        }
        return base;
    }
}
