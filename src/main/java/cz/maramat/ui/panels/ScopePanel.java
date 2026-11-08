package cz.maramat.ui.panels;

import cz.maramat.model.BlockInstance;
import cz.maramat.model.DataType;
import cz.maramat.model.Page;
import cz.maramat.model.PinInstance;
import cz.maramat.model.Project;
import cz.maramat.settings.AppSettings;
import cz.maramat.ui.icons.VectorIcons;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.JToolBar;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Frame;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

public class ScopePanel extends JPanel {

    private record ChannelSample(double value, DataType dataType) {}

    private final Supplier<Project> projectSupplier;
    private final Set<String> selectedPins = new LinkedHashSet<>();
    private final Map<String, Deque<Double>> channelHistory = new LinkedHashMap<>();
    private final Map<String, DataType> channelTypes = new LinkedHashMap<>();
    private final Map<String, Color> channelColors = new LinkedHashMap<>();

    private double xZoom = 1.0; // Shift + +/-
    private double yZoom = 1.0; // Ctrl + +/-
    private boolean frozen = false;

    private JLabel xZoomLabel;
    private JLabel yZoomLabel;
    private JButton freezeBtn;
    private final CanvasPlotArea plotArea;

    private static final Color[] TRACE_PALETTE = {
            new Color(0x00, 0x55, 0xCC),
            new Color(0xC0, 0x2B, 0x2B),
            new Color(0x1E, 0x84, 0x49),
            new Color(0x7D, 0x3C, 0x98),
            new Color(0xD3, 0x54, 0x00),
            new Color(0x00, 0x80, 0x80)
    };

    public ScopePanel(Supplier<Project> projectSupplier) {
        super(new BorderLayout());
        this.projectSupplier = projectSupplier;
        setBackground(Color.WHITE);

        // Pre-populate default pins from sample project
        selectedPins.add("8xDI1.DI0");
        selectedPins.add("And1.Y");
        selectedPins.add("Value1.In");

        add(buildScopeToolbar(), BorderLayout.NORTH);
        plotArea = new CanvasPlotArea();
        add(plotArea, BorderLayout.CENTER);

        installZoomKeybindings();
    }

    private JToolBar buildScopeToolbar() {
        JToolBar tb = new JToolBar();
        tb.setFloatable(false);
        tb.setBackground(new Color(0xF4, 0xF6, 0xF9));
        tb.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(0xCC, 0xCC, 0xCC)));
        tb.setMargin(new Insets(2, 4, 2, 4));

        JButton selectPinsBtn = new JButton("Select Pins...");
        selectPinsBtn.setFocusable(false);
        selectPinsBtn.setMargin(new Insets(1, 6, 1, 6));
        selectPinsBtn.addActionListener(e -> openPinSelectorDialog());
        tb.add(selectPinsBtn);

        tb.addSeparator(new Dimension(10, 18));

        // X Zoom Controls (Shift + -/+)
        tb.add(new JLabel("X Zoom (Shift +/-): "));
        JButton xOutBtn = createSmallBtn("-");
        xOutBtn.addActionListener(e -> adjustXZoom(0.8));
        tb.add(xOutBtn);

        xZoomLabel = new JLabel(" 100% ");
        tb.add(xZoomLabel);

        JButton xInBtn = createSmallBtn("+");
        xInBtn.addActionListener(e -> adjustXZoom(1.25));
        tb.add(xInBtn);

        tb.addSeparator(new Dimension(10, 18));

        // Y Zoom Controls (Ctrl + -/+)
        tb.add(new JLabel("Y Zoom (Ctrl +/-): "));
        JButton yOutBtn = createSmallBtn("-");
        yOutBtn.addActionListener(e -> adjustYZoom(0.8));
        tb.add(yOutBtn);

        yZoomLabel = new JLabel(" 100% ");
        tb.add(yZoomLabel);

        JButton yInBtn = createSmallBtn("+");
        yInBtn.addActionListener(e -> adjustYZoom(1.25));
        tb.add(yInBtn);

        tb.addSeparator(new Dimension(10, 18));

        freezeBtn = new JButton("Freeze");
        freezeBtn.setFocusable(false);
        freezeBtn.setMargin(new Insets(1, 6, 1, 6));
        freezeBtn.addActionListener(e -> {
            frozen = !frozen;
            freezeBtn.setText(frozen ? "Resume" : "Freeze");
        });
        tb.add(freezeBtn);

        JButton clearBtn = new JButton("Clear");
        clearBtn.setFocusable(false);
        clearBtn.setMargin(new Insets(1, 6, 1, 6));
        clearBtn.addActionListener(e -> clearHistory());
        tb.add(clearBtn);

        return tb;
    }

    private JButton createSmallBtn(String text) {
        JButton b = new JButton(text);
        b.setFocusable(false);
        b.setMargin(new Insets(0, 5, 0, 5));
        return b;
    }

    private void installZoomKeybindings() {
        int ctrlMask = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        int shiftMask = KeyEvent.SHIFT_DOWN_MASK;

        // Bind on WHEN_IN_FOCUSED_WINDOW so Ctrl +/- and Shift +/- work seamlessly while viewing the Scope
        var im = getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        var am = getActionMap();

        // Ctrl + Plus/Equals -> Y Zoom In
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_EQUALS, ctrlMask), "yZoomIn");
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_PLUS, ctrlMask), "yZoomIn");
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_ADD, ctrlMask), "yZoomIn");
        // Ctrl + Minus -> Y Zoom Out
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_MINUS, ctrlMask), "yZoomOut");
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_SUBTRACT, ctrlMask), "yZoomOut");

        // Shift + Plus/Equals -> X Zoom In
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_EQUALS, shiftMask), "xZoomIn");
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_PLUS, shiftMask), "xZoomIn");
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_ADD, shiftMask), "xZoomIn");
        // Shift + Minus -> X Zoom Out
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_MINUS, shiftMask), "xZoomOut");
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_SUBTRACT, shiftMask), "xZoomOut");

        am.put("yZoomIn", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { adjustYZoom(1.25); }
        });
        am.put("yZoomOut", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { adjustYZoom(0.8); }
        });
        am.put("xZoomIn", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { adjustXZoom(1.25); }
        });
        am.put("xZoomOut", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { adjustXZoom(0.8); }
        });
    }

    public void adjustXZoom(double factor) {
        xZoom = Math.clamp(xZoom * factor, 0.25, 16.0);
        xZoomLabel.setText(String.format(" %d%% ", Math.round(xZoom * 100)));
        plotArea.repaint();
    }

    public void adjustYZoom(double factor) {
        yZoom = Math.clamp(yZoom * factor, 0.25, 16.0);
        yZoomLabel.setText(String.format(" %d%% ", Math.round(yZoom * 100)));
        plotArea.repaint();
    }

    public synchronized void clearHistory() {
        channelHistory.clear();
        plotArea.repaint();
    }

    public synchronized boolean isPinSelected(String pinRef) {
        return selectedPins.contains(pinRef);
    }

    public synchronized void togglePinSelection(String pinRef) {
        if (selectedPins.contains(pinRef)) {
            selectedPins.remove(pinRef);
            channelHistory.remove(pinRef);
        } else {
            selectedPins.add(pinRef);
        }
        plotArea.repaint();
    }

    public synchronized void recordSample(Project project, double simTimeSeconds) {
        if (frozen || project == null) return;
        int maxSamples = AppSettings.get().getScopeMaxSamples();
        int colorIdx = 0;

        for (Page page : project.getPages()) {
            if (page.isMacro()) continue;
            for (BlockInstance b : page.getBlocks()) {
                for (PinInstance in : b.getInputs()) {
                    String ref = b.getName() + "." + in.getShortName();
                    if (selectedPins.contains(ref)) {
                        pushValue(ref, in.getRuntimeValue(), in.getDataType(), maxSamples, colorIdx++);
                    }
                }
                for (PinInstance out : b.getOutputs()) {
                    String ref = b.getName() + "." + out.getShortName();
                    if (selectedPins.contains(ref)) {
                        pushValue(ref, out.getRuntimeValue(), out.getDataType(), maxSamples, colorIdx++);
                    }
                }
            }
        }
        plotArea.repaint();
    }

    private void pushValue(String ref, double val, DataType dt, int maxSamples, int colorIdx) {
        Deque<Double> q = channelHistory.computeIfAbsent(ref, k -> new ArrayDeque<>());
        while (q.size() >= maxSamples) {
            q.removeFirst();
        }
        q.addLast(val);
        channelTypes.put(ref, dt);
        channelColors.putIfAbsent(ref, TRACE_PALETTE[colorIdx % TRACE_PALETTE.length]);
    }

    private void openPinSelectorDialog() {
        Project proj = projectSupplier.get();
        if (proj == null) return;
        Frame owner = (Frame) SwingUtilities.getWindowAncestor(this);
        JDialog dlg = new JDialog(owner, "Select Oscilloscope Pins", true);

        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        JPanel searchRow = new JPanel(new BorderLayout(4, 0));
        searchRow.add(new JLabel("Search Pin:"), BorderLayout.WEST);
        JTextField searchField = new JTextField();
        searchRow.add(searchField, BorderLayout.CENTER);
        root.add(searchRow, BorderLayout.NORTH);

        JPanel checkListPanel = new JPanel();
        checkListPanel.setLayout(new BoxLayout(checkListPanel, BoxLayout.Y_AXIS));
        checkListPanel.setBackground(Color.WHITE);

        record PinOption(String ref, String display, JCheckBox checkBox) {}
        List<PinOption> options = new ArrayList<>();

        for (Page page : proj.getPages()) {
            for (BlockInstance b : page.getBlocks()) {
                for (PinInstance out : b.getOutputs()) {
                    String ref = b.getName() + "." + out.getShortName();
                    String disp = ref + "  [OUT - " + out.getDataType().getDisplayName() + "]";
                    JCheckBox cb = new JCheckBox(disp, selectedPins.contains(ref));
                    cb.setBackground(Color.WHITE);
                    options.add(new PinOption(ref, disp, cb));
                    checkListPanel.add(cb);
                }
                for (PinInstance in : b.getInputs()) {
                    String ref = b.getName() + "." + in.getShortName();
                    String disp = ref + "  [IN - " + in.getDataType().getDisplayName() + "]";
                    JCheckBox cb = new JCheckBox(disp, selectedPins.contains(ref));
                    cb.setBackground(Color.WHITE);
                    options.add(new PinOption(ref, disp, cb));
                    checkListPanel.add(cb);
                }
            }
        }

        searchField.getDocument().addDocumentListener(new DocumentListener() {
            private void filter() {
                String q = searchField.getText().trim().toLowerCase();
                checkListPanel.removeAll();
                for (PinOption opt : options) {
                    if (q.isEmpty() || opt.display().toLowerCase().contains(q)) {
                        checkListPanel.add(opt.checkBox());
                    }
                }
                checkListPanel.revalidate();
                checkListPanel.repaint();
            }
            @Override public void insertUpdate(DocumentEvent e) { filter(); }
            @Override public void removeUpdate(DocumentEvent e) { filter(); }
            @Override public void changedUpdate(DocumentEvent e) { filter(); }
        });

        JScrollPane scroll = new JScrollPane(checkListPanel);
        scroll.setPreferredSize(new Dimension(360, 240));
        root.add(scroll, BorderLayout.CENTER);

        JPanel btnBar = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 2));
        JButton okBtn = new JButton("OK", VectorIcons.greenCheckIcon());
        okBtn.addActionListener(e -> {
            synchronized (ScopePanel.this) {
                selectedPins.clear();
                for (PinOption opt : options) {
                    if (opt.checkBox().isSelected()) {
                        selectedPins.add(opt.ref());
                    }
                }
                channelHistory.keySet().retainAll(selectedPins);
            }
            plotArea.repaint();
            dlg.dispose();
        });

        JButton cancelBtn = new JButton("Cancel", VectorIcons.redCrossIcon());
        cancelBtn.addActionListener(e -> dlg.dispose());

        btnBar.add(okBtn);
        btnBar.add(cancelBtn);
        root.add(btnBar, BorderLayout.SOUTH);

        dlg.setContentPane(root);
        dlg.pack();
        dlg.setLocationRelativeTo(this);
        dlg.setVisible(true);
    }

    private class CanvasPlotArea extends JPanel {

        CanvasPlotArea() {
            setBackground(Color.WHITE);
            setFocusable(true);
            addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    requestFocusInWindow();
                }
            });
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            if (AppSettings.get().isAntialiasText()) {
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            }

            int w = getWidth();
            int h = getHeight();
            int leftMargin = AppSettings.get().scale(110);
            int plotW = Math.max(20, w - leftMargin - 10);

            synchronized (ScopePanel.this) {
                List<String> numericChannels = new ArrayList<>();
                List<String> boolChannels = new ArrayList<>();

                // Classify selected channels into Numeric (INT, LONG, FLOAT) vs Boolean (BOOL)
                for (String ref : selectedPins) {
                    DataType dt = channelTypes.getOrDefault(ref, inferDataTypeFromProject(ref));
                    if (dt == DataType.BOOL) {
                        boolChannels.add(ref);
                    } else {
                        numericChannels.add(ref);
                    }
                }

                if (numericChannels.isEmpty() && boolChannels.isEmpty()) {
                    g2.setColor(new Color(0x55, 0x55, 0x55));
                    g2.drawString("No pins selected. Click 'Select Pins...' or right-click a pin on the canvas -> 'Add to Scope'.", 16, h / 2);
                    g2.dispose();
                    return;
                }

                // Allocate vertical space: Numeric section on top, dedicated Boolean strip bars at bottom
                int boolLaneHeight = Math.max(24, AppSettings.get().scale(28));
                int totalBoolH = boolChannels.size() * boolLaneHeight;
                if (numericChannels.isEmpty()) {
                    totalBoolH = h - 10;
                    boolLaneHeight = Math.max(24, totalBoolH / Math.max(1, boolChannels.size()));
                } else if (totalBoolH > h * 0.55) {
                    totalBoolH = (int) (h * 0.55);
                    boolLaneHeight = Math.max(18, totalBoolH / Math.max(1, boolChannels.size()));
                }

                int numSectionH = numericChannels.isEmpty() ? 0 : (h - totalBoolH - 8);

                // 1. Paint Numeric Channels Section (Top Bar)
                if (!numericChannels.isEmpty() && numSectionH > 20) {
                    paintNumericSection(g2, numericChannels, leftMargin, 4, plotW, numSectionH);
                }

                // 2. Paint Boolean Channels (Dedicated Digital Logic Analyzer Bars)
                int boolStartY = numericChannels.isEmpty() ? 4 : (numSectionH + 6);
                for (int i = 0; i < boolChannels.size(); i++) {
                    int laneY = boolStartY + i * boolLaneHeight;
                    paintBooleanBar(g2, boolChannels.get(i), leftMargin, laneY, plotW, boolLaneHeight - 3, i);
                }
            }

            g2.dispose();
        }

        private void paintNumericSection(Graphics2D g2, List<String> channels, int x0, int y0, int pw, int ph) {
            // White background + crisp light grey graticule
            g2.setColor(new Color(0xFC, 0xFD, 0xFE));
            g2.fillRect(x0, y0, pw, ph);

            g2.setColor(new Color(0xE2, 0xE6, 0xEC));
            for (int gx = x0; gx <= x0 + pw; gx += 40) {
                g2.drawLine(gx, y0, gx, y0 + ph);
            }
            for (int gy = y0; gy <= y0 + ph; gy += 25) {
                g2.drawLine(x0, gy, x0 + pw, gy);
            }
            g2.setColor(new Color(0x99, 0x99, 0x99));
            g2.drawRect(x0, y0, pw, ph);

            // Compute value bounds adjusted by yZoom
            double rawMin = 0.0;
            double rawMax = 10.0;
            for (String ch : channels) {
                Deque<Double> q = channelHistory.get(ch);
                if (q != null) {
                    for (double v : q) {
                        rawMin = Math.min(rawMin, v);
                        rawMax = Math.max(rawMax, v);
                    }
                }
            }
            if (Math.abs(rawMax - rawMin) < 1e-6) {
                rawMax = rawMin + 10.0;
            }
            double mid = (rawMax + rawMin) / 2.0;
            double halfSpan = ((rawMax - rawMin) / 2.0) / yZoom;
            double vMin = mid - halfSpan;
            double vMax = mid + halfSpan;

            // Left margin Y-axis labels & channel legend
            Font font = new Font("Monospaced", Font.PLAIN, AppSettings.get().scale(10));
            g2.setFont(font);
            g2.setColor(Color.BLACK);
            g2.drawString(String.format(java.util.Locale.US, "%.1f", vMax), 6, y0 + 11);
            g2.drawString(String.format(java.util.Locale.US, "%.1f", vMin), 6, y0 + ph - 3);

            int maxCapacity = AppSettings.get().getScopeMaxSamples();
            int visibleSamples = Math.max(10, (int) Math.round(maxCapacity / xZoom));

            int legY = y0 + 24;
            for (int ci = 0; ci < channels.size(); ci++) {
                String ch = channels.get(ci);
                Color col = channelColors.getOrDefault(ch, TRACE_PALETTE[ci % TRACE_PALETTE.length]);
                Deque<Double> q = channelHistory.get(ch);
                double lastVal = (q == null || q.isEmpty()) ? 0.0 : q.getLast();

                g2.setColor(col);
                g2.drawString(ch + ":" + String.format(java.util.Locale.US, "%.1f", lastVal), 6, legY);
                legY += AppSettings.get().scale(13);

                if (q == null || q.size() < 2) continue;
                Double[] arr = q.toArray(new Double[0]);
                int startIdx = Math.max(0, arr.length - visibleSamples);
                int count = arr.length - startIdx;

                g2.setStroke(new BasicStroke(1.5f));
                for (int i = 0; i < count - 1; i++) {
                    double v1 = arr[startIdx + i];
                    double v2 = arr[startIdx + i + 1];
                    int px1 = x0 + (i * pw) / Math.max(1, visibleSamples - 1);
                    int px2 = x0 + ((i + 1) * pw) / Math.max(1, visibleSamples - 1);
                    int py1 = y0 + ph - 4 - (int) Math.round(((v1 - vMin) / (vMax - vMin)) * (ph - 8));
                    int py2 = y0 + ph - 4 - (int) Math.round(((v2 - vMin) / (vMax - vMin)) * (ph - 8));
                    py1 = Math.clamp(py1, y0 + 1, y0 + ph - 1);
                    py2 = Math.clamp(py2, y0 + 1, y0 + ph - 1);
                    g2.drawLine(px1, py1, px2, py2);
                }
            }
            g2.setStroke(new BasicStroke(1.0f));
        }

        private void paintBooleanBar(Graphics2D g2, String ch, int x0, int y0, int pw, int lh, int idx) {
            g2.setColor(new Color(0xF8, 0xFA, 0xFC));
            g2.fillRect(x0, y0, pw, lh);
            g2.setColor(new Color(0xE2, 0xE6, 0xEC));
            for (int gx = x0; gx <= x0 + pw; gx += 40) {
                g2.drawLine(gx, y0, gx, y0 + lh);
            }
            g2.setColor(new Color(0xAA, 0xAA, 0xAA));
            g2.drawRect(x0, y0, pw, lh);

            Deque<Double> q = channelHistory.get(ch);
            boolean curHigh = (q != null && !q.isEmpty() && q.getLast() != 0.0);

            Font font = new Font("Monospaced", Font.BOLD, AppSettings.get().scale(10));
            g2.setFont(font);
            FontMetrics fm = g2.getFontMetrics();
            Color col = DataType.BOOL.getWireColor();

            g2.setColor(Color.BLACK);
            int ty = y0 + (lh + fm.getAscent() - fm.getDescent()) / 2;
            g2.drawString(ch + " [" + (curHigh ? "1" : "0") + "]", 6, ty);

            if (q == null || q.size() < 2) return;
            Double[] arr = q.toArray(new Double[0]);
            int maxCapacity = AppSettings.get().getScopeMaxSamples();
            int visibleSamples = Math.max(10, (int) Math.round(maxCapacity / xZoom));
            int startIdx = Math.max(0, arr.length - visibleSamples);
            int count = arr.length - startIdx;

            int highY = y0 + 4;
            int lowY = y0 + lh - 4;

            g2.setColor(col);
            g2.setStroke(new BasicStroke(1.6f));
            for (int i = 0; i < count - 1; i++) {
                boolean b1 = arr[startIdx + i] != 0.0;
                boolean b2 = arr[startIdx + i + 1] != 0.0;
                int px1 = x0 + (i * pw) / Math.max(1, visibleSamples - 1);
                int px2 = x0 + ((i + 1) * pw) / Math.max(1, visibleSamples - 1);
                int py1 = b1 ? highY : lowY;
                int py2 = b2 ? highY : lowY;

                // Digital step waveform
                g2.drawLine(px1, py1, px2, py1);
                if (py1 != py2) {
                    g2.drawLine(px2, py1, px2, py2);
                }
            }
            g2.setStroke(new BasicStroke(1.0f));
        }

        private DataType inferDataTypeFromProject(String ref) {
            Project proj = projectSupplier.get();
            if (proj == null) return DataType.BOOL;
            String[] parts = ref.split("\\.");
            if (parts.length != 2) return DataType.BOOL;
            for (Page page : proj.getPages()) {
                BlockInstance b = page.findBlockByName(parts[0]);
                if (b != null) {
                    for (PinInstance p : b.getOutputs()) {
                        if (p.getShortName().equalsIgnoreCase(parts[1])) return p.getDataType();
                    }
                    for (PinInstance p : b.getInputs()) {
                        if (p.getShortName().equalsIgnoreCase(parts[1])) return p.getDataType();
                    }
                }
            }
            return DataType.BOOL;
        }
    }
}
