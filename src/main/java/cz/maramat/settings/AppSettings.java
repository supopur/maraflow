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

    // Communication Defaults
    private String defaultCommMethod = "USB (CDC)";
    private String defaultBaudRate = "115200";
    private String defaultSerialPort = "/dev/ttyACM0";

    // ST Editor & IntelliSense
    private boolean stIntelliSenseEnabled = true;
    private boolean stAutoPopup = true;
    private int stPopupDelayMs = 150;
    private boolean stRealtimeDiagnostics = true;
    private boolean stGutterMarkers = true;
    private boolean stSquigglyUnderlines = true;

    private final List<Runnable> listeners = new ArrayList<>();

    private AppSettings() {}

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
        int fontSize = Math.max(10, Math.round(12 * scale));
        Font scaledUiFont = new Font("Segoe UI", Font.PLAIN, fontSize);
        Font scaledBoldFont = new Font("Segoe UI", Font.BOLD, fontSize);

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
        listeners.add(r);
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
}
