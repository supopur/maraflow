package cz.maramat.settings;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/**
 * Stores sensitive values (e.g. PLC/server passwords) in the OS secrets store
 * (macOS Keychain, Windows DPAPI, Linux secret-tool) with a plain-text fallback
 * in the OS-specific app config directory.
 */
public final class SecretsStore {
    private static final String SERVICE_PREFIX = "cz.maramat.";
    private final String appName;
    private final Path fallbackFile;

    public SecretsStore(String appName) {
        this.appName = appName;
        this.fallbackFile = OsPaths.getConfigDir(appName).resolve("secrets.properties");
    }

    public void saveSecret(String key, String value) {
        if (key == null || key.isBlank()) return;
        String val = (value == null) ? "" : value;
        if (tryOsSave(key, val)) {
            removeFallback(key);
            return;
        }
        saveFallback(key, val);
    }

    public String loadSecret(String key, String defaultValue) {
        if (key == null || key.isBlank()) return defaultValue;
        String osVal = tryOsLoad(key);
        if (osVal != null) {
            return osVal;
        }
        return loadFallback(key, defaultValue);
    }

    private boolean tryOsSave(String key, String value) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String service = SERVICE_PREFIX + appName.toLowerCase(Locale.ROOT);
        try {
            if (os.contains("mac")) {
                Process p = new ProcessBuilder(
                        "/usr/bin/security", "add-generic-password",
                        "-U", "-a", key, "-s", service, "-w", value
                ).redirectErrorStream(true).start();
                if (p.waitFor(3, TimeUnit.SECONDS)) {
                    return p.exitValue() == 0;
                }
                p.destroyForcibly();
            } else if (os.contains("nux") || os.contains("nix")) {
                Process p = new ProcessBuilder(
                        "secret-tool", "store", "--label=" + appName + " " + key,
                        "service", service, "account", key
                ).redirectErrorStream(true).start();
                try (OutputStream out = p.getOutputStream()) {
                    out.write(value.getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
                if (p.waitFor(3, TimeUnit.SECONDS)) {
                    return p.exitValue() == 0;
                }
                p.destroyForcibly();
            } else if (os.contains("win")) {
                String psCmd = "$sec = ConvertTo-SecureString -String $env:MARA_SEC -AsPlainText -Force; "
                        + "[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes((ConvertFrom-SecureString $sec)))";
                ProcessBuilder pb = new ProcessBuilder("powershell", "-NoProfile", "-Command", psCmd);
                pb.environment().put("MARA_SEC", value);
                Process p = pb.start();
                String out = readAll(p.getInputStream()).trim();
                if (p.waitFor(4, TimeUnit.SECONDS) && p.exitValue() == 0 && !out.isEmpty()) {
                    saveFallback(key + ".dpapi", out);
                    return true;
                }
                p.destroyForcibly();
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private String tryOsLoad(String key) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String service = SERVICE_PREFIX + appName.toLowerCase(Locale.ROOT);
        try {
            if (os.contains("mac")) {
                Process p = new ProcessBuilder(
                        "/usr/bin/security", "find-generic-password",
                        "-a", key, "-s", service, "-w"
                ).start();
                String out = readAll(p.getInputStream());
                if (p.waitFor(3, TimeUnit.SECONDS) && p.exitValue() == 0) {
                    return out.replaceAll("[\\r\\n]+$", "");
                }
                p.destroyForcibly();
            } else if (os.contains("nux") || os.contains("nix")) {
                Process p = new ProcessBuilder(
                        "secret-tool", "lookup", "service", service, "account", key
                ).start();
                String out = readAll(p.getInputStream());
                if (p.waitFor(3, TimeUnit.SECONDS) && p.exitValue() == 0) {
                    return out.replaceAll("[\\r\\n]+$", "");
                }
                p.destroyForcibly();
            } else if (os.contains("win")) {
                String enc = loadFallback(key + ".dpapi", null);
                if (enc == null || enc.isEmpty()) return null;
                String psCmd = "$raw = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($env:MARA_DPAPI)); "
                        + "$sec = ConvertTo-SecureString $raw; "
                        + "[Runtime.InteropServices.Marshal]::PtrToStringAuto("
                        + "[Runtime.InteropServices.Marshal]::SecureStringToBSTR($sec))";
                ProcessBuilder pb = new ProcessBuilder("powershell", "-NoProfile", "-Command", psCmd);
                pb.environment().put("MARA_DPAPI", enc);
                Process p = pb.start();
                String out = readAll(p.getInputStream());
                if (p.waitFor(4, TimeUnit.SECONDS) && p.exitValue() == 0) {
                    return out.replaceAll("[\\r\\n]+$", "");
                }
                p.destroyForcibly();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private synchronized void saveFallback(String key, String value) {
        Properties props = loadProps();
        props.setProperty(key, value);
        storeProps(props);
    }

    private synchronized void removeFallback(String key) {
        if (!Files.exists(fallbackFile)) return;
        Properties props = loadProps();
        if (props.remove(key) != null) {
            storeProps(props);
        }
    }

    private synchronized String loadFallback(String key, String defaultValue) {
        Properties props = loadProps();
        return props.getProperty(key, defaultValue);
    }

    private Properties loadProps() {
        Properties props = new Properties();
        if (Files.exists(fallbackFile)) {
            try (InputStream in = Files.newInputStream(fallbackFile)) {
                props.load(in);
            } catch (IOException ignored) {
            }
        }
        return props;
    }

    private void storeProps(Properties props) {
        try {
            Files.createDirectories(fallbackFile.getParent());
            try (OutputStream out = Files.newOutputStream(fallbackFile)) {
                props.store(out, appName + " secrets fallback");
            }
            try {
                Files.setPosixFilePermissions(fallbackFile, EnumSet.of(
                        PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
            } catch (UnsupportedOperationException | IOException ignored) {
            }
        } catch (IOException ignored) {
        }
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[256];
        int n;
        while ((n = in.read(buf)) >= 0) {
            bos.write(buf, 0, n);
        }
        return bos.toString(StandardCharsets.UTF_8);
    }
}
