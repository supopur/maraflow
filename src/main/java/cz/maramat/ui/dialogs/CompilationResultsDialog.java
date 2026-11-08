package cz.maramat.ui.dialogs;

import cz.maramat.compiler.BytecodeCompiler.CompilationReport;
import cz.maramat.model.DataType;
import cz.maramat.ui.icons.VectorIcons;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.util.Map;

public class CompilationResultsDialog extends JDialog {
    public enum NextAction {
        NONE,
        UPLOAD,
        UPLOAD_AND_WATCH
    }

    private NextAction nextAction = NextAction.NONE;
    private final CompilationReport report;

    public CompilationResultsDialog(Frame owner, CompilationReport report) {
        super(owner, "Compilation Results", true);
        this.report = report;
        buildUi();
        pack();
        setMinimumSize(new Dimension(690, 460));
        setLocationRelativeTo(owner);
    }

    private void buildUi() {
        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // Top section: Left = Progress Bars (x/max_allowed), Right = RAM Data Type Pie Chart
        JPanel topSplit = new JPanel(new BorderLayout(10, 0));
        topSplit.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(0xCC, 0xCC, 0xCC)),
                BorderFactory.createEmptyBorder(8, 10, 8, 10)
        ));

        JPanel barsPanel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(3, 4, 3, 4);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        int r = 0;
        addMetricBar(barsPanel, gbc, r++, "Memory Usage (RAM):", report.ramUsedBytes(), report.ramMaxBytes(), "B");
        addMetricBar(barsPanel, gbc, r++, "Flash Usage (Program):", report.flashUsedBytes(), report.flashMaxBytes(), "B");
        addMetricBar(barsPanel, gbc, r++, "EEPROM Usage (64KiB):", report.eepromUsedBytes(), report.eepromMaxBytes(), "B");
        addMetricBar(barsPanel, gbc, r++, "Thread 1 (1s) Exec Time:", report.threadEstUs()[1], report.threadMaxUs()[1], "us");
        addMetricBar(barsPanel, gbc, r++, "Thread 2 (100ms) Exec Time:", report.threadEstUs()[2], report.threadMaxUs()[2], "us");
        addMetricBar(barsPanel, gbc, r++, "Thread 3 (20ms) Exec Time:", report.threadEstUs()[3], report.threadMaxUs()[3], "us");
        addMetricBar(barsPanel, gbc, r++, "Thread 4 (5ms) Exec Time:", report.threadEstUs()[4], report.threadMaxUs()[4], "us");
        addMetricBar(barsPanel, gbc, r++, "Total CPU Usage (Est.):", report.totalCpuPercent(), 100, "%");

        topSplit.add(barsPanel, BorderLayout.CENTER);
        topSplit.add(new RamPieChartPanel(report), BorderLayout.EAST);

        root.add(topSplit, BorderLayout.NORTH);

        // Middle section: Compiler Log
        JPanel logPanel = new JPanel(new BorderLayout());
        logPanel.setBorder(BorderFactory.createTitledBorder("Log:"));
        JTextArea logArea = new JTextArea(6, 50);
        logArea.setEditable(false);
        logArea.setFont(new Font("Monospaced", Font.PLAIN, 12));
        StringBuilder sb = new StringBuilder();
        for (String line : report.logLines()) {
            sb.append(line).append("\n");
        }
        logArea.setText(sb.toString());
        logArea.setCaretPosition(0);
        logPanel.add(new JScrollPane(logArea), BorderLayout.CENTER);
        root.add(logPanel, BorderLayout.CENTER);

        // Bottom buttons: Close, Close and Upload, Close, Upload, Watch
        JPanel bottomBar = new JPanel(new FlowLayout(FlowLayout.CENTER, 16, 2));

        JButton closeBtn = new JButton("Close", VectorIcons.redCrossIcon());
        closeBtn.setPreferredSize(new Dimension(145, 28));
        closeBtn.addActionListener(e -> {
            nextAction = NextAction.NONE;
            dispose();
        });

        JButton uploadBtn = new JButton("Close and Upload", VectorIcons.uploadIcon());
        uploadBtn.setPreferredSize(new Dimension(165, 28));
        uploadBtn.addActionListener(e -> {
            nextAction = NextAction.UPLOAD;
            dispose();
        });

        JButton uploadWatchBtn = new JButton("Close, Upload, Watch", VectorIcons.watchGlassesIcon());
        uploadWatchBtn.setPreferredSize(new Dimension(185, 28));
        uploadWatchBtn.addActionListener(e -> {
            nextAction = NextAction.UPLOAD_AND_WATCH;
            dispose();
        });

        bottomBar.add(closeBtn);
        bottomBar.add(uploadBtn);
        bottomBar.add(uploadWatchBtn);
        root.add(bottomBar, BorderLayout.SOUTH);

        getRootPane().setDefaultButton(closeBtn);
        setContentPane(root);
    }

    private void addMetricBar(JPanel panel, GridBagConstraints gbc, int row, String label, int val, int max, String unit) {
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.weightx = 0.0;
        JLabel lbl = new JLabel(label, SwingConstants.RIGHT);
        lbl.setPreferredSize(new Dimension(165, 18));
        panel.add(lbl, gbc);

        gbc.gridx = 1;
        gbc.weightx = 1.0;
        JProgressBar bar = new JProgressBar(0, Math.max(1, max));
        bar.setValue(Math.clamp(val, 0, max));
        bar.setPreferredSize(new Dimension(210, 16));
        bar.setForeground(new Color(0x06, 0xB0, 0x25));
        bar.setBackground(new Color(0xE6, 0xE6, 0xE6));
        panel.add(bar, gbc);

        gbc.gridx = 2;
        gbc.weightx = 0.0;
        JLabel valLbl = new JLabel(val + "/" + max + (unit.isEmpty() ? "" : " " + unit));
        valLbl.setPreferredSize(new Dimension(105, 18));
        panel.add(valLbl, gbc);
    }

    public NextAction getNextAction() {
        return nextAction;
    }

    private static class RamPieChartPanel extends JPanel {
        private final CompilationReport report;

        RamPieChartPanel(CompilationReport report) {
            this.report = report;
            setPreferredSize(new Dimension(215, 195));
            setBorder(BorderFactory.createTitledBorder("RAM Data Types"));
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            Map<DataType, Integer> map = report.ramBytesByDataType();
            int bBool = map.getOrDefault(DataType.BOOL, 0);
            int bInt = map.getOrDefault(DataType.INT, 0);
            int bLong = map.getOrDefault(DataType.LONG, 0);
            int bFloat = map.getOrDefault(DataType.FLOAT, 0);
            int totalData = bBool + bInt + bLong + bFloat;

            int[] values = {bBool, bInt, bLong, bFloat};
            String[] names = {"BOOL", "INT", "LONG", "FLOAT"};
            Color[] colors = {
                    DataType.BOOL.getWireColor(),
                    new Color(0x33, 0x33, 0x33),
                    DataType.LONG.getWireColor(),
                    DataType.FLOAT.getWireColor()
            };

            int pieSize = 86;
            int px = 14;
            int py = 26;

            if (totalData <= 0) {
                g2.setColor(new Color(0xDD, 0xDD, 0xDD));
                g2.fillOval(px, py, pieSize, pieSize);
                g2.setColor(Color.DARK_GRAY);
                g2.drawOval(px, py, pieSize, pieSize);
            } else {
                double startAngle = 90.0;
                for (int i = 0; i < values.length; i++) {
                    if (values[i] <= 0) continue;
                    double extent = -360.0 * values[i] / totalData;
                    g2.setColor(colors[i]);
                    g2.fill(new Arc2D.Double(px, py, pieSize, pieSize, startAngle, extent, Arc2D.PIE));
                    startAngle += extent;
                }
                g2.setColor(Color.BLACK);
                g2.drawOval(px, py, pieSize, pieSize);
            }

            // Legend on right side of pie
            g2.setFont(new Font("SansSerif", Font.PLAIN, 10));
            int lx = px + pieSize + 10;
            int ly = py + 12;
            for (int i = 0; i < names.length; i++) {
                g2.setColor(colors[i]);
                g2.fillRect(lx, ly - 8, 9, 9);
                g2.setColor(Color.BLACK);
                g2.drawRect(lx, ly - 8, 9, 9);
                g2.drawString(names[i] + ": " + values[i] + "B", lx + 13, ly);
                ly += 18;
            }
            g2.setColor(new Color(0x44, 0x44, 0x44));
            g2.drawString("VM Sys: " + report.vmOverheadRamBytes() + "B", px, py + pieSize + 20);
            g2.dispose();
        }
    }
}
