package cz.maramat.settings;

import com.formdev.flatlaf.FlatLaf;
import cz.maramat.model.HeaderColor;
import cz.maramat.sim.BlockSorter.OrderSortMode;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.Font;
import java.awt.Window;
import java.util.ArrayList;
import java.util.List;

public final class AppSettings {
    private static final AppSettings INSTANCE = new AppSettings();

    // Appearance / Visual
    private int uiScalePercent = 100; // Snaps every 25: 100, 125, 150, 175, 200, 225, 250
    private HeaderColor defaultHeaderColor = HeaderColor.CL_DEFAULT;
    private boolean antialiasText = true;
    private boolean showGridByDefault = true;
    private boolean showExecInfoByDefault = true;

    // Editor & Wiring
    private boolean autoBundleBus = false;
    private int defaultZoomPercent = 100;
    private OrderSortMode defaultSortMode = OrderSortMode.LEFT_TO_RIGHT;

    // Simulation & Scope
    private int defaultThreadId = 2;
    private int scopeMaxSamples = 300;

    // Communication Defaults & Remembered Inputs
    private String defaultCommMethod = "USB (CDC)";
    private String defaultBaudRate = "115200";
    private String defaultSerialPort = "/dev/ttyACM0";
    private int lastUsbStationId = 1;
    private String lastRs485Port = "/dev/ttyUSB0";
    private String lastRs485Baud = "115200";
    private String lastRs485Parity = "None (8N1)";
    private int lastRs485StationId = 1;
    private String lastTcpIp = "192.168.1.50";
    private int lastTcpPort = 5020;
    private int lastTcpUnitId = 1;
    private final SecretsStore secrets = new SecretsStore("MaraFlow");

    // ST Editor & IntelliSense
    private boolean stIntelliSenseEnabled = true;
    private boolean stAutoPopup = true;
    private int stPopupDelayMs = 150;
    private boolean stRealtimeDiagnostics = true;
    private boolean stGutterMarkers = true;
    private boolean stSquigglyUnderlines = true;

    // Help
    private String helpLanguage = "auto"; // "auto", "en", "cs"

    private final List<Runnable> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    private AppSettings() {
        load();
    }

    public static AppSettings get() {
        return INSTANCE;
    }

    public float getScaleFactor() {
        return uiScalePercent / 100.0f;
    }

    public int scale(int basePx) {
        return Math.max(1, Math.round(basePx * getScaleFactor()));
    }

    public void applySettingsToUi() {
        float scale = getScaleFactor();
        int fontSize = Math.max(10, Math.round(11 * scale));
        Font scaledUiFont = cz.maramat.ui.AppFonts.ui(Font.PLAIN, fontSize);
        Font scaledBoldFont = cz.maramat.ui.AppFonts.ui(Font.BOLD, fontSize);

        UIManager.put("defaultFont", scaledUiFont);
        String[] fontKeys = {
                "Label.font", "Button.font", "ToggleButton.font", "ComboBox.font",
                "TextField.font", "PasswordField.font", "TextArea.font", "CheckBox.font",
                "Menu.font", "MenuItem.font", "CheckBoxMenuItem.font", "PopupMenu.font",
                "Tree.font", "Table.font", "TableHeader.font", "TabbedPane.font",
                "TitledBorder.font", "ToolTip.font", "List.font", "Spinner.font"
        };
        for (String key : fontKeys) {
            UIManager.put(key, key.equals("TitledBorder.font") ? scaledBoldFont : scaledUiFont);
        }

        UIManager.put("ScrollBar.width", scale(14));
        UIManager.put("TabbedPane.tabHeight", scale(22));
        UIManager.put("Tree.rowHeight", scale(19));
        UIManager.put("Table.rowHeight", scale(22));

        FlatLaf.updateUI();
        for (Window w : Window.getWindows()) {
            SwingUtilities.updateComponentTreeUI(w);
            w.revalidate();
            w.repaint();
        }
        for (Runnable r : listeners) {
            r.run();
        }
    }

    public void addChangeListener(Runnable r) {
        if (r != null && !listeners.contains(r)) {
            listeners.add(r);
        }
    }

    public void removeChangeListener(Runnable r) {
        listeners.remove(r);
    }

    public int getUiScalePercent() {
        return uiScalePercent;
    }

    public void setUiScalePercent(int pct) {
        int snapped = Math.round(pct / 25.0f) * 25;
        this.uiScalePercent = Math.clamp(snapped, 75, 250);
    }

    public HeaderColor getDefaultHeaderColor() {
        return defaultHeaderColor;
    }

    public void setDefaultHeaderColor(HeaderColor defaultHeaderColor) {
        this.defaultHeaderColor = defaultHeaderColor;
    }

    public boolean isAntialiasText() {
        return antialiasText;
    }

    public void setAntialiasText(boolean antialiasText) {
        this.antialiasText = antialiasText;
    }

    public boolean isShowGridByDefault() {
        return showGridByDefault;
    }

    public void setShowGridByDefault(boolean showGridByDefault) {
        this.showGridByDefault = showGridByDefault;
    }

    public boolean isShowExecInfoByDefault() {
        return showExecInfoByDefault;
    }

    public void setShowExecInfoByDefault(boolean showExecInfoByDefault) {
        this.showExecInfoByDefault = showExecInfoByDefault;
    }

    public boolean isAutoBundleBus() {
        return autoBundleBus;
    }

    public void setAutoBundleBus(boolean autoBundleBus) {
        this.autoBundleBus = autoBundleBus;
    }

    public int getDefaultZoomPercent() {
        return defaultZoomPercent;
    }

    public void setDefaultZoomPercent(int defaultZoomPercent) {
        this.defaultZoomPercent = defaultZoomPercent;
    }

    public OrderSortMode getDefaultSortMode() {
        return defaultSortMode;
    }

    public void setDefaultSortMode(OrderSortMode defaultSortMode) {
        this.defaultSortMode = defaultSortMode;
    }

    public int getDefaultThreadId() {
        return defaultThreadId;
    }

    public void setDefaultThreadId(int defaultThreadId) {
        this.defaultThreadId = Math.clamp(defaultThreadId, 1, 5);
    }

    public int getScopeMaxSamples() {
        return scopeMaxSamples;
    }

    public void setScopeMaxSamples(int scopeMaxSamples) {
        this.scopeMaxSamples = Math.clamp(scopeMaxSamples, 60, 2000);
    }

    public String getDefaultCommMethod() {
        return defaultCommMethod;
    }

    public void setDefaultCommMethod(String defaultCommMethod) {
        this.defaultCommMethod = defaultCommMethod;
    }

    public String getDefaultBaudRate() {
        return defaultBaudRate;
    }

    public void setDefaultBaudRate(String defaultBaudRate) {
        this.defaultBaudRate = defaultBaudRate;
    }

    public String getDefaultSerialPort() {
        return defaultSerialPort;
    }

    public void setDefaultSerialPort(String defaultSerialPort) {
        this.defaultSerialPort = defaultSerialPort;
    }

    public boolean isStIntelliSenseEnabled() {
        return stIntelliSenseEnabled;
    }

    public void setStIntelliSenseEnabled(boolean stIntelliSenseEnabled) {
        this.stIntelliSenseEnabled = stIntelliSenseEnabled;
    }

    public boolean isStAutoPopup() {
        return stAutoPopup;
    }

    public void setStAutoPopup(boolean stAutoPopup) {
        this.stAutoPopup = stAutoPopup;
    }

    public int getStPopupDelayMs() {
        return stPopupDelayMs;
    }

    public void setStPopupDelayMs(int stPopupDelayMs) {
        this.stPopupDelayMs = Math.clamp(stPopupDelayMs, 50, 1000);
    }

    public boolean isStRealtimeDiagnostics() {
        return stRealtimeDiagnostics;
    }

    public void setStRealtimeDiagnostics(boolean stRealtimeDiagnostics) {
        this.stRealtimeDiagnostics = stRealtimeDiagnostics;
    }

    public boolean isStGutterMarkers() {
        return stGutterMarkers;
    }

    public void setStGutterMarkers(boolean stGutterMarkers) {
        this.stGutterMarkers = stGutterMarkers;
    }

    public boolean isStSquigglyUnderlines() {
        return stSquigglyUnderlines;
    }

    public void setStSquigglyUnderlines(boolean stSquigglyUnderlines) {
        this.stSquigglyUnderlines = stSquigglyUnderlines;
    }

    public String getHelpLanguage() {
        return helpLanguage;
    }

    public void setHelpLanguage(String helpLanguage) {
        if ("en".equals(helpLanguage) || "cs".equals(helpLanguage)) {
            this.helpLanguage = helpLanguage;
        } else {
            this.helpLanguage = "auto";
        }
    }

    public String resolveHelpLocale() {
        if ("en".equals(helpLanguage) || "cs".equals(helpLanguage)) {
            return helpLanguage;
        }
        String lang = java.util.Locale.getDefault().getLanguage();
        return "cs".equalsIgnoreCase(lang) ? "cs" : "en";
    }

    public int getLastUsbStationId() { return lastUsbStationId; }
    public void setLastUsbStationId(int id) { this.lastUsbStationId = Math.clamp(id, 0, 254); }

    public String getLastRs485Port() { return lastRs485Port; }
    public void setLastRs485Port(String p) { if (p != null && !p.isBlank()) this.lastRs485Port = p.trim(); }

    public String getLastRs485Baud() { return lastRs485Baud; }
    public void setLastRs485Baud(String b) { if (b != null && !b.isBlank()) this.lastRs485Baud = b.trim(); }

    public String getLastRs485Parity() { return lastRs485Parity; }
    public void setLastRs485Parity(String p) { if (p != null && !p.isBlank()) this.lastRs485Parity = p.trim(); }

    public int getLastRs485StationId() { return lastRs485StationId; }
    public void setLastRs485StationId(int id) { this.lastRs485StationId = Math.clamp(id, 1, 247); }

    public String getLastTcpIp() { return lastTcpIp; }
    public void setLastTcpIp(String ip) { if (ip != null && !ip.isBlank()) this.lastTcpIp = ip.trim(); }

    public int getLastTcpPort() { return lastTcpPort; }
    public void setLastTcpPort(int port) { if (port >= 1 && port <= 65535) this.lastTcpPort = port; }

    public int getLastTcpUnitId() { return lastTcpUnitId; }
    public void setLastTcpUnitId(int id) { this.lastTcpUnitId = Math.clamp(id, 0, 254); }

    public String loadCommPassword(String profileKey, String defaultVal) {
        return secrets.loadSecret("comm." + profileKey + ".password", defaultVal);
    }

    public void saveCommPassword(String profileKey, String password) {
        secrets.saveSecret("comm." + profileKey + ".password", password);
    }

    public synchronized void load() {
        java.nio.file.Path file = OsPaths.getConfigDir("MaraFlow").resolve("settings.properties");
        if (!java.nio.file.Files.exists(file)) return;
        java.util.Properties props = new java.util.Properties();
        try (java.io.InputStream in = java.nio.file.Files.newInputStream(file)) {
            props.load(in);
            uiScalePercent = Math.clamp(parseInt(props.getProperty("uiScalePercent"), uiScalePercent), 75, 250);
            defaultZoomPercent = parseInt(props.getProperty("defaultZoomPercent"), defaultZoomPercent);
            antialiasText = Boolean.parseBoolean(props.getProperty("antialiasText", String.valueOf(antialiasText)));
            showGridByDefault = Boolean.parseBoolean(props.getProperty("showGridByDefault", String.valueOf(showGridByDefault)));
            showExecInfoByDefault = Boolean.parseBoolean(props.getProperty("showExecInfoByDefault", String.valueOf(showExecInfoByDefault)));
            autoBundleBus = Boolean.parseBoolean(props.getProperty("autoBundleBus", String.valueOf(autoBundleBus)));
            defaultCommMethod = props.getProperty("defaultCommMethod", defaultCommMethod);
            defaultBaudRate = props.getProperty("defaultBaudRate", defaultBaudRate);
            defaultSerialPort = props.getProperty("defaultSerialPort", defaultSerialPort);
            lastUsbStationId = Math.clamp(parseInt(props.getProperty("lastUsbStationId"), lastUsbStationId), 0, 254);
            lastRs485Port = props.getProperty("lastRs485Port", lastRs485Port);
            lastRs485Baud = props.getProperty("lastRs485Baud", lastRs485Baud);
            lastRs485Parity = props.getProperty("lastRs485Parity", lastRs485Parity);
            lastRs485StationId = Math.clamp(parseInt(props.getProperty("lastRs485StationId"), lastRs485StationId), 1, 247);
            lastTcpIp = props.getProperty("lastTcpIp", lastTcpIp);
            lastTcpPort = Math.clamp(parseInt(props.getProperty("lastTcpPort"), lastTcpPort), 1, 65535);
            lastTcpUnitId = Math.clamp(parseInt(props.getProperty("lastTcpUnitId"), lastTcpUnitId), 0, 254);
            helpLanguage = props.getProperty("helpLanguage", helpLanguage);
        } catch (Exception ignored) {
        }
    }

    public synchronized void save() {
        java.nio.file.Path file = OsPaths.getConfigDir("MaraFlow").resolve("settings.properties");
        java.util.Properties props = new java.util.Properties();
        props.setProperty("uiScalePercent", String.valueOf(uiScalePercent));
        props.setProperty("defaultZoomPercent", String.valueOf(defaultZoomPercent));
        props.setProperty("antialiasText", String.valueOf(antialiasText));
        props.setProperty("showGridByDefault", String.valueOf(showGridByDefault));
        props.setProperty("showExecInfoByDefault", String.valueOf(showExecInfoByDefault));
        props.setProperty("autoBundleBus", String.valueOf(autoBundleBus));
        props.setProperty("defaultCommMethod", defaultCommMethod);
        props.setProperty("defaultBaudRate", defaultBaudRate);
        props.setProperty("defaultSerialPort", defaultSerialPort);
        props.setProperty("lastUsbStationId", String.valueOf(lastUsbStationId));
        props.setProperty("lastRs485Port", lastRs485Port);
        props.setProperty("lastRs485Baud", lastRs485Baud);
        props.setProperty("lastRs485Parity", lastRs485Parity);
        props.setProperty("lastRs485StationId", String.valueOf(lastRs485StationId));
        props.setProperty("lastTcpIp", lastTcpIp);
        props.setProperty("lastTcpPort", String.valueOf(lastTcpPort));
        props.setProperty("lastTcpUnitId", String.valueOf(lastTcpUnitId));
        props.setProperty("helpLanguage", helpLanguage);
        try (java.io.OutputStream out = java.nio.file.Files.newOutputStream(file)) {
            props.store(out, "MaraFlow Application Settings");
        } catch (Exception ignored) {
        }
    }

    private static int parseInt(String s, int def) {
        if (s == null || s.isBlank()) return def;
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return def; }
    }
}
