package cz.maramat.ui.dialogs;

import cz.maramat.model.PlcThread;
import cz.maramat.model.Project;
import cz.maramat.ui.icons.VectorIcons;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Frame;

public class PlcConfigDialog extends JDialog {
    private final Project project;
    private JComboBox<String> platformCombo;

    private static final String[] TARGET_PLATFORMS = {
            "STM32F407-Maraflow-Std",
            "STM32F407-Maraflow-IO64",
            "STM32F407-Maraflow-Mini"
    };

    public PlcConfigDialog(Frame owner, Project project) {
        super(owner, "Unit / PLC Configuration", true);
        this.project = project;
        buildUi();
        pack();
        setMinimumSize(new Dimension(500, 320));
        setLocationRelativeTo(owner);
    }

    private void buildUi() {
        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(new EmptyBorder(10, 10, 10, 10));

        JPanel topPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        topPanel.setBorder(BorderFactory.createTitledBorder("Target Hardware Platform (STM32F407 + 64KiB EEPROM)"));
        topPanel.add(new JLabel("PLC Model:"));
        platformCombo = new JComboBox<>(TARGET_PLATFORMS);
        platformCombo.setSelectedItem(project.getTargetPlatform());
        topPanel.add(platformCombo);
        root.add(topPanel, BorderLayout.NORTH);

        String[] cols = {"Thread ID", "RTOS Schedule", "Guaranteed Period", "Max Budget (us)"};
        Object[][] rows = new Object[PlcThread.values().length][4];
        int i = 0;
        for (PlcThread pt : PlcThread.values()) {
            rows[i][0] = pt.getId();
            rows[i][1] = pt.getDisplayLabel();
            rows[i][2] = (pt.getId() == 5) ? "Unpromised (Runs on Idle)" : (pt.getPeriodMs() + " ms");
            rows[i][3] = pt.getMaxAllowedUs();
            i++;
        }
        JTable threadTable = new JTable(rows, cols) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        threadTable.setRowHeight(21);
        JScrollPane scroll = new JScrollPane(threadTable);
        scroll.setBorder(BorderFactory.createTitledBorder("Fixed FreeRTOS Execution Threads (Read-Only)"));
        root.add(scroll, BorderLayout.CENTER);

        JPanel btnBar = new JPanel(new FlowLayout(FlowLayout.CENTER, 14, 2));
        JButton okBtn = new JButton("OK", VectorIcons.greenCheckIcon());
        okBtn.setPreferredSize(new Dimension(96, 28));
        okBtn.addActionListener(e -> {
            String sel = (String) platformCombo.getSelectedItem();
            if (sel != null && !sel.equals(project.getTargetPlatform())) {
                project.setTargetPlatform(sel);
                project.setDirty(true);
            }
            dispose();
        });

        JButton cancelBtn = new JButton("Cancel", VectorIcons.redCrossIcon());
        cancelBtn.setPreferredSize(new Dimension(96, 28));
        cancelBtn.addActionListener(e -> dispose());

        btnBar.add(okBtn);
        btnBar.add(cancelBtn);
        root.add(btnBar, BorderLayout.SOUTH);

        getRootPane().setDefaultButton(okBtn);
        setContentPane(root);
    }
}
