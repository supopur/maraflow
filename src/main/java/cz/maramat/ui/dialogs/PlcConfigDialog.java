package cz.maramat.ui.dialogs;

import cz.maramat.model.EStopConfig;
import cz.maramat.model.IecLanguage;
import cz.maramat.model.LadderElement;
import cz.maramat.model.LadderRung;
import cz.maramat.model.Page;
import cz.maramat.model.PlcThread;
import cz.maramat.model.Project;
import cz.maramat.ui.AppFonts;
import cz.maramat.ui.icons.VectorIcons;

import javax.swing.BorderFactory;
import javax.swing.DefaultCellEditor;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.border.EmptyBorder;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class PlcConfigDialog extends JDialog {
    private final Project project;
    private final Consumer<Page> onOpenPageCallback;

    private JComboBox<String> platformCombo;
    private JCheckBox eStopEnabledCheck;
    private JComboBox<EStopConfig.Mode> eStopModeCombo;
    private JCheckBox manualResetCheck;
    private JTextField eStopPageField;
    private final List<EStopConfig.InputRule> workingRules = new ArrayList<>();
    private EStopRulesTableModel rulesTableModel;

    private static final String[] TARGET_PLATFORMS = {
            "STM32F407-Maraflow-Std",
            "STM32F407-Maraflow-IO64",
            "STM32F407-Maraflow-Mini"
    };

    private static final String[] DI_CHANNELS = {
            "DI0", "DI1", "DI2", "DI3", "DI4", "DI5", "DI6", "DI7",
            "DI8", "DI9", "DI10", "DI11", "DI12", "DI13", "DI14", "DI15"
    };

    private static final String[] POLARITY_OPTIONS = {
            "Active Low (NC - Failsafe 0V=Trip)",
            "Active High (NO - 24V=Trip)"
    };

    public PlcConfigDialog(Frame owner, Project project) {
        this(owner, project, null);
    }

    public PlcConfigDialog(Frame owner, Project project, Consumer<Page> onOpenPageCallback) {
        super(owner, "Unit / PLC & Safety E-Stop Configuration", true);
        this.project = project;
        this.onOpenPageCallback = onOpenPageCallback;
        for (EStopConfig.InputRule r : project.getEStopConfig().getInputRules()) {
            workingRules.add(r.copy());
        }
        buildUi();
        pack();
        setMinimumSize(new Dimension(AppFonts.scaled(640), AppFonts.scaled(430)));
        setLocationRelativeTo(owner);
    }

    private void buildUi() {
        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(new EmptyBorder(10, 10, 10, 10));

        JTabbedPane tabs = new JTabbedPane();
        tabs.setFont(AppFonts.uiBold());
        tabs.addTab("1. Hardware & RTOS Threads", buildHardwareTab());
        tabs.addTab("2. Safety PLC / Emergency Stop (E-Stop)", buildEStopTab());
        root.add(tabs, BorderLayout.CENTER);

        JPanel btnBar = new JPanel(new FlowLayout(FlowLayout.CENTER, 14, 2));
        JButton okBtn = new JButton("OK", VectorIcons.greenCheckIcon());
        okBtn.setPreferredSize(new Dimension(AppFonts.scaled(100), AppFonts.scaled(28)));
        okBtn.addActionListener(e -> applyAndClose());

        JButton cancelBtn = new JButton("Cancel", VectorIcons.redCrossIcon());
        cancelBtn.setPreferredSize(new Dimension(AppFonts.scaled(100), AppFonts.scaled(28)));
        cancelBtn.addActionListener(e -> dispose());

        btnBar.add(okBtn);
        btnBar.add(cancelBtn);
        root.add(btnBar, BorderLayout.SOUTH);

        getRootPane().setDefaultButton(okBtn);
        setContentPane(root);
    }

    private JPanel buildHardwareTab() {
        JPanel tab = new JPanel(new BorderLayout(8, 8));
        tab.setBorder(new EmptyBorder(6, 6, 6, 6));

        JPanel topPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        topPanel.setBorder(BorderFactory.createTitledBorder("Target Hardware Platform (STM32F407 + 64KiB EEPROM)"));
        topPanel.add(new JLabel("PLC Model:"));
        platformCombo = new JComboBox<>(TARGET_PLATFORMS);
        platformCombo.setSelectedItem(project.getTargetPlatform());
        topPanel.add(platformCombo);
        tab.add(topPanel, BorderLayout.NORTH);

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
        threadTable.setRowHeight(AppFonts.scaled(21));
        JScrollPane scroll = new JScrollPane(threadTable);
        scroll.setBorder(BorderFactory.createTitledBorder("Fixed FreeRTOS Execution Threads (Read-Only)"));
        tab.add(scroll, BorderLayout.CENTER);
        return tab;
    }

    private JPanel buildEStopTab() {
        JPanel tab = new JPanel(new BorderLayout(8, 8));
        tab.setBorder(new EmptyBorder(6, 6, 6, 6));

        EStopConfig cfg = project.getEStopConfig();

        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createTitledBorder("Emergency Stop (E-Stop) Reaction & Reset Policy"));
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(4, 6, 4, 6);
        gc.anchor = GridBagConstraints.WEST;
        gc.fill = GridBagConstraints.HORIZONTAL;

        eStopEnabledCheck = new JCheckBox("Enable Hardware & Simulation E-Stop Supervision", cfg.isEnabled());
        eStopEnabledCheck.setFont(AppFonts.uiBold());
        gc.gridx = 0;
        gc.gridy = 0;
        gc.gridwidth = 3;
        form.add(eStopEnabledCheck, gc);

        gc.gridwidth = 1;
        gc.gridx = 0;
        gc.gridy = 1;
        form.add(new JLabel("E-Stop Reaction Mode:"), gc);

        eStopModeCombo = new JComboBox<>(EStopConfig.Mode.values());
        eStopModeCombo.setSelectedItem(cfg.getMode());
        gc.gridx = 1;
        gc.gridy = 1;
        gc.gridwidth = 2;
        gc.weightx = 1.0;
        form.add(eStopModeCombo, gc);

        gc.gridwidth = 1;
        gc.weightx = 0;
        gc.gridx = 0;
        gc.gridy = 2;
        form.add(new JLabel("Dedicated E-Stop Page:"), gc);

        eStopPageField = new JTextField(cfg.getDedicatedEStopPageName(), 14);
        gc.gridx = 1;
        gc.gridy = 2;
        gc.weightx = 1.0;
        form.add(eStopPageField, gc);

        JButton openOrCreatePageBtn = new JButton("Create / Open !EStop Page...");
        openOrCreatePageBtn.addActionListener(e -> ensureAndOpenEStopPage());
        gc.gridx = 2;
        gc.gridy = 2;
        gc.weightx = 0;
        form.add(openOrCreatePageBtn, gc);

        manualResetCheck = new JCheckBox(
                "Require Manual Reset (Latch E-Stop trip until manual reset or EStop block Rst rising edge)",
                cfg.isRequireManualReset()
        );
        gc.gridx = 0;
        gc.gridy = 3;
        gc.gridwidth = 3;
        form.add(manualResetCheck, gc);

        tab.add(form, BorderLayout.NORTH);

        // Hardware E-Stop Inputs Table
        JPanel tablePanel = new JPanel(new BorderLayout(4, 4));
        tablePanel.setBorder(BorderFactory.createTitledBorder("Monitored Hardware E-Stop Input Channels (DI0..DI15)"));

        rulesTableModel = new EStopRulesTableModel();
        JTable rulesTable = new JTable(rulesTableModel);
        rulesTable.setRowHeight(AppFonts.scaled(22));
        rulesTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        JComboBox<String> chCombo = new JComboBox<>(DI_CHANNELS);
        rulesTable.getColumnModel().getColumn(1).setCellEditor(new DefaultCellEditor(chCombo));

        JComboBox<String> polCombo = new JComboBox<>(POLARITY_OPTIONS);
        rulesTable.getColumnModel().getColumn(2).setCellEditor(new DefaultCellEditor(polCombo));

        rulesTable.getColumnModel().getColumn(0).setPreferredWidth(AppFonts.scaled(65));
        rulesTable.getColumnModel().getColumn(1).setPreferredWidth(AppFonts.scaled(95));
        rulesTable.getColumnModel().getColumn(2).setPreferredWidth(AppFonts.scaled(220));
        rulesTable.getColumnModel().getColumn(3).setPreferredWidth(AppFonts.scaled(220));

        tablePanel.add(new JScrollPane(rulesTable), BorderLayout.CENTER);

        JPanel ruleBtns = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        JButton addRuleBtn = new JButton("+ Add E-Stop Input");
        addRuleBtn.addActionListener(e -> {
            int nextCh = Math.min(15, workingRules.size());
            workingRules.add(new EStopConfig.InputRule("DI" + nextCh, true, false, "Safety Channel " + (workingRules.size() + 1)));
            rulesTableModel.fireTableDataChanged();
        });
        JButton removeRuleBtn = new JButton("- Remove Selected");
        removeRuleBtn.addActionListener(e -> {
            int row = rulesTable.getSelectedRow();
            if (row >= 0 && row < workingRules.size()) {
                workingRules.remove(row);
                rulesTableModel.fireTableDataChanged();
            }
        });
        ruleBtns.add(addRuleBtn);
        ruleBtns.add(removeRuleBtn);
        tablePanel.add(ruleBtns, BorderLayout.SOUTH);

        tab.add(tablePanel, BorderLayout.CENTER);
        return tab;
    }

    private void ensureAndOpenEStopPage() {
        String rawName = eStopPageField.getText().trim();
        if (rawName.isEmpty()) rawName = "!EStop";
        if (!rawName.startsWith("!")) rawName = "!" + rawName;
        eStopPageField.setText(rawName);

        Page existing = null;
        for (Page p : project.getPages()) {
            if (p.getName().equalsIgnoreCase(rawName) || p.isEStopPage()) {
                existing = p;
                break;
            }
        }

        if (existing == null) {
            IecLanguage[] langs = IecLanguage.values();
            IecLanguage chosen = (IecLanguage) JOptionPane.showInputDialog(
                    this,
                    "Select IEC 61131-3 language for Dedicated E-Stop Page (" + rawName + "):",
                    "Create Dedicated E-Stop Safety Page",
                    JOptionPane.QUESTION_MESSAGE,
                    null,
                    langs,
                    project.getPrimaryLanguage()
            );
            if (chosen == null) return;

            existing = new Page(rawName, chosen);
            existing.setEStopPage(true);
            if (chosen == IecLanguage.LD) {
                LadderRung r1 = new LadderRung(4, 10, "Dedicated E-Stop Handler: Reset Drive DO0, Energize Alarm Beacon DO7");
                r1.getMainElements().add(new LadderElement(LadderElement.Kind.NO_CONTACT, "TRUE"));
                r1.getCoils().add(new LadderElement(LadderElement.Kind.RESET_COIL, "DO0"));
                r1.getCoils().add(new LadderElement(LadderElement.Kind.NORMAL_COIL, "DO7"));
                existing.getRungs().add(r1);
            } else if (chosen == IecLanguage.ST) {
                existing.setStThreadId(4);
                existing.setStOrder(10);
                existing.setStCode("""
                        // Dedicated E-Stop Safety Handler (Normal cyclic pages are halted)
                        DO0 := FALSE;
                        DO1 := FALSE;
                        DO7 := TRUE; // Energize E-Stop Alarm Beacon DO7
                        """);
            }
            project.getPages().add(existing);
            project.setDirty(true);
        }

        applyChangesToProject();
        dispose();
        if (onOpenPageCallback != null) {
            onOpenPageCallback.accept(existing);
        }
    }

    private void applyChangesToProject() {
        String sel = (String) platformCombo.getSelectedItem();
        if (sel != null && !sel.equals(project.getTargetPlatform())) {
            project.setTargetPlatform(sel);
        }
        EStopConfig cfg = project.getEStopConfig();
        cfg.setEnabled(eStopEnabledCheck.isSelected());
        cfg.setMode((EStopConfig.Mode) eStopModeCombo.getSelectedItem());
        cfg.setRequireManualReset(manualResetCheck.isSelected());
        cfg.setDedicatedEStopPageName(eStopPageField.getText());
        cfg.getInputRules().clear();
        for (EStopConfig.InputRule r : workingRules) {
            cfg.getInputRules().add(r.copy());
        }
        project.setDirty(true);
    }

    private void applyAndClose() {
        applyChangesToProject();
        dispose();
    }

    private final class EStopRulesTableModel extends AbstractTableModel {
        private final String[] cols = {"Enabled", "Input Pin", "Trip Polarity", "Description"};

        @Override
        public int getRowCount() {
            return workingRules.size();
        }

        @Override
        public int getColumnCount() {
            return cols.length;
        }

        @Override
        public String getColumnName(int column) {
            return cols[column];
        }

        @Override
        public Class<?> getColumnClass(int columnIndex) {
            return columnIndex == 0 ? Boolean.class : String.class;
        }

        @Override
        public boolean isCellEditable(int rowIndex, int columnIndex) {
            return true;
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            EStopConfig.InputRule r = workingRules.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> r.isEnabled();
                case 1 -> r.getInputChannel();
                case 2 -> r.isActiveLow() ? POLARITY_OPTIONS[0] : POLARITY_OPTIONS[1];
                case 3 -> r.getDescription();
                default -> "";
            };
        }

        @Override
        public void setValueAt(Object aValue, int rowIndex, int columnIndex) {
            EStopConfig.InputRule r = workingRules.get(rowIndex);
            switch (columnIndex) {
                case 0 -> r.setEnabled(Boolean.TRUE.equals(aValue));
                case 1 -> r.setInputChannel(String.valueOf(aValue));
                case 2 -> r.setActiveLow(String.valueOf(aValue).startsWith("Active Low"));
                case 3 -> r.setDescription(String.valueOf(aValue));
            }
            fireTableRowsUpdated(rowIndex, rowIndex);
        }
    }
}
