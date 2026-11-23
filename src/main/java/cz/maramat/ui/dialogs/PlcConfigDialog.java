package cz.maramat.ui.dialogs;

import cz.maramat.comm.MaraProtClient;
import cz.maramat.model.EStopConfig;
import cz.maramat.model.IecLanguage;
import cz.maramat.model.Page;
import cz.maramat.model.Project;
import cz.maramat.model.UnitConfig;
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
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.border.EmptyBorder;
import javax.swing.table.AbstractTableModel;
import javax.swing.text.AbstractDocument;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.DocumentFilter;
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
    private final MaraProtClient connectedClient;

    // Unit Config fields
    private JComboBox<String> platformCombo;
    private JTextField unitNameField;
    private JTextField stationIdField;
    private JComboBox<UnitConfig.IpMode> ipModeCombo;
    private JTextField staticIpField;
    private JTextField subnetMaskField;
    private JTextField gatewayField;
    private JTextField tcpPortField;
    private JComboBox<String> runOutCombo;
    private JComboBox<String> faultOutCombo;
    private JComboBox<String> estopOutCombo;
    private JComboBox<UnitConfig.BootMode> bootModeCombo;
    private JCheckBox safeOutputsCheck;
    private JCheckBox watchdogCheck;
    private JComboBox<UnitConfig.Rs485Mode> rs485ModeCombo;
    private JComboBox<Integer> rs485BaudCombo;
    private JPasswordField accessPassField;

    // E-Stop fields
    private JCheckBox eStopEnabledCheck;
    private JComboBox<EStopConfig.Mode> eStopModeCombo;
    private JCheckBox manualResetCheck;
    private JTextField eStopPageField;
    private final List<EStopConfig.InputRule> workingRules = new ArrayList<>();
    private EStopRulesTableModel rulesTableModel;
    private final List<UnitConfig.ModbusSlaveEntry> workingModbusMap = new ArrayList<>();
    private ModbusMapTableModel modbusMapTableModel;

    private static final String[] TARGET_PLATFORMS = {"Duino - S"};

    private static final String[] DI_CHANNELS = {
            "DI0", "DI1", "DI2", "DI3", "DI4", "DI5", "DI6", "DI7",
            "DI8", "DI9", "DI10", "DI11", "DI12", "DI13", "DI14", "DI15"
    };

    private static final String[] DO_CHANNELS = {
            "None", "DO0", "DO1", "DO2", "DO3", "DO4", "DO5", "DO6", "DO7",
            "DO8", "DO9", "DO10", "DO11", "DO12", "DO13", "DO14", "DO15"
    };

    private static final String[] POLARITY_OPTIONS = {
            "Active Low",
            "Active High"
    };

    public PlcConfigDialog(Frame owner, Project project) {
        this(owner, project, null, null);
    }

    public PlcConfigDialog(Frame owner, Project project, Consumer<Page> onOpenPageCallback) {
        this(owner, project, onOpenPageCallback, null);
    }

    public PlcConfigDialog(Frame owner, Project project, Consumer<Page> onOpenPageCallback, MaraProtClient connectedClient) {
        super(owner, connectedClient != null ? "Unit Config (Connected)" : "Unit Config", true);
        this.project = project;
        this.onOpenPageCallback = onOpenPageCallback;
        this.connectedClient = connectedClient;
        for (EStopConfig.InputRule r : project.getEStopConfig().getInputRules()) {
            workingRules.add(r.copy());
        }
        for (UnitConfig.ModbusSlaveEntry e : project.getUnitConfig().getModbusSlaveMap()) {
            workingModbusMap.add(e.copy());
        }
        buildUi();
        pack();
        setMinimumSize(new Dimension(AppFonts.scaled(640), AppFonts.scaled(480)));
        setLocationRelativeTo(owner);
    }

    private void buildUi() {
        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(new EmptyBorder(10, 10, 10, 10));

        JTabbedPane tabs = new JTabbedPane();
        tabs.setFont(AppFonts.uiBold());
        tabs.addTab("Unit Config", buildUnitConfigTab());
        tabs.addTab("Modbus Slave Map", buildModbusMapTab());
        tabs.addTab("E-Stop", buildEStopTab());
        root.add(tabs, BorderLayout.CENTER);

        JPanel btnBar = new JPanel(new FlowLayout(FlowLayout.CENTER, 14, 2));
        JButton okBtn = new JButton(connectedClient != null ? "Write to Unit" : "OK", VectorIcons.greenCheckIcon());
        okBtn.setPreferredSize(new Dimension(AppFonts.scaled(125), AppFonts.scaled(28)));
        okBtn.addActionListener(e -> applyAndClose());

        JButton cancelBtn = new JButton("Cancel", VectorIcons.redCrossIcon());
        cancelBtn.setPreferredSize(new Dimension(AppFonts.scaled(100), AppFonts.scaled(28)));
        cancelBtn.addActionListener(e -> {
            closeClientQuietly();
            dispose();
        });

        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                closeClientQuietly();
            }
        });

        btnBar.add(okBtn);
        btnBar.add(cancelBtn);
        root.add(btnBar, BorderLayout.SOUTH);

        getRootPane().setDefaultButton(okBtn);
        setContentPane(root);
    }

    private JPanel buildUnitConfigTab() {
        UnitConfig ucfg = project.getUnitConfig();
        JPanel container = new JPanel(new GridBagLayout());
        container.setBorder(new EmptyBorder(4, 4, 4, 4));
        GridBagConstraints rootGc = new GridBagConstraints();
        rootGc.gridx = 0;
        rootGc.weightx = 1.0;
        rootGc.fill = GridBagConstraints.HORIZONTAL;
        rootGc.insets = new Insets(4, 2, 4, 2);

        // 1. Identity & Hardware
        JPanel idPanel = new JPanel(new GridBagLayout());
        idPanel.setBorder(BorderFactory.createTitledBorder("Hardware & Identity"));
        GridBagConstraints g1 = createGbc();
        platformCombo = new JComboBox<>(TARGET_PLATFORMS);
        platformCombo.setSelectedItem(project.getTargetPlatform());
        unitNameField = new JTextField(ucfg.getUnitName(), 16);
        stationIdField = createDigitsOnlyField(String.valueOf(ucfg.getStationId()), 6);
        addRow(idPanel, g1, 0, "PLC Model:", platformCombo);
        addRow(idPanel, g1, 1, "Unit Name:", unitNameField);
        addRow(idPanel, g1, 2, "Station ID:", stationIdField);
        rootGc.gridy = 0;
        container.add(idPanel, rootGc);

        // 2. Network / IP Config
        JPanel netPanel = new JPanel(new GridBagLayout());
        netPanel.setBorder(BorderFactory.createTitledBorder("Network / IP Configuration"));
        GridBagConstraints g2 = createGbc();
        ipModeCombo = new JComboBox<>(UnitConfig.IpMode.values());
        ipModeCombo.setSelectedItem(ucfg.getIpMode());
        staticIpField = new JTextField(ucfg.getStaticIp(), 16);
        subnetMaskField = new JTextField(ucfg.getSubnetMask(), 16);
        gatewayField = new JTextField(ucfg.getGateway(), 16);
        tcpPortField = createDigitsOnlyField(String.valueOf(ucfg.getTcpPort()), 8);
        Runnable syncIpEnabled = () -> {
            boolean isStatic = ipModeCombo.getSelectedItem() == UnitConfig.IpMode.STATIC;
            staticIpField.setEnabled(isStatic);
            subnetMaskField.setEnabled(isStatic);
            gatewayField.setEnabled(isStatic);
        };
        ipModeCombo.addActionListener(e -> syncIpEnabled.run());
        syncIpEnabled.run();
        addRow(netPanel, g2, 0, "IP Mode:", ipModeCombo);
        addRow(netPanel, g2, 1, "Static IPv4:", staticIpField);
        addRow(netPanel, g2, 2, "Subnet Mask:", subnetMaskField);
        addRow(netPanel, g2, 3, "Gateway:", gatewayField);
        addRow(netPanel, g2, 4, "MaraProt TCP Port:", tcpPortField);
        rootGc.gridy = 1;
        container.add(netPanel, rootGc);

        // 2b. RS-485 / Serial Configuration
        JPanel rs485Panel = new JPanel(new GridBagLayout());
        rs485Panel.setBorder(BorderFactory.createTitledBorder("RS-485 / Serial Bus Configuration"));
        GridBagConstraints gRs = createGbc();
        rs485ModeCombo = new JComboBox<>(UnitConfig.Rs485Mode.values());
        rs485ModeCombo.setSelectedItem(ucfg.getRs485Mode());
        rs485BaudCombo = new JComboBox<>();
        for (int b : UnitConfig.RS485_BAUD_RATES) {
            rs485BaudCombo.addItem(b);
        }
        rs485BaudCombo.setSelectedItem(ucfg.getRs485BaudRate());
        addRow(rs485Panel, gRs, 0, "RS-485 Mode:", rs485ModeCombo);
        addRow(rs485Panel, gRs, 1, "RS-485 Baud Rate:", rs485BaudCombo);
        rootGc.gridy = 2;
        container.add(rs485Panel, rootGc);

        // 3. Status Outputs & Startup
        JPanel ioPanel = new JPanel(new GridBagLayout());
        ioPanel.setBorder(BorderFactory.createTitledBorder("Status Outputs, Boot & Security"));
        GridBagConstraints g3 = createGbc();
        runOutCombo = new JComboBox<>(DO_CHANNELS);
        runOutCombo.setSelectedItem(ucfg.getRunOutputPin());
        faultOutCombo = new JComboBox<>(DO_CHANNELS);
        faultOutCombo.setSelectedItem(ucfg.getFaultOutputPin());
        estopOutCombo = new JComboBox<>(DO_CHANNELS);
        estopOutCombo.setSelectedItem(ucfg.getEstopOutputPin());
        bootModeCombo = new JComboBox<>(UnitConfig.BootMode.values());
        bootModeCombo.setSelectedItem(ucfg.getBootMode());
        safeOutputsCheck = new JCheckBox("Force Outputs to Safe State on STOP / Fault", ucfg.isSafeOutputsOnStop());
        watchdogCheck = new JCheckBox("Enable Hardware Watchdog", ucfg.isWatchdogEnabled());
        accessPassField = new JPasswordField(ucfg.getAccessPassword(), 14);

        addRow(ioPanel, g3, 0, "RUN Output:", runOutCombo);
        addRow(ioPanel, g3, 1, "FAULT Output:", faultOutCombo);
        addRow(ioPanel, g3, 2, "E-STOP Output:", estopOutCombo);
        addRow(ioPanel, g3, 3, "Power-On Boot Mode:", bootModeCombo);
        addRow(ioPanel, g3, 4, "Access Password:", accessPassField);

        g3.gridx = 0;
        g3.gridy = 5;
        g3.gridwidth = 2;
        ioPanel.add(safeOutputsCheck, g3);
        g3.gridy = 6;
        ioPanel.add(watchdogCheck, g3);

        rootGc.gridy = 3;
        rootGc.weighty = 1.0;
        rootGc.anchor = GridBagConstraints.NORTH;
        container.add(ioPanel, rootGc);

        return container;
    }

    private JPanel buildEStopTab() {
        JPanel tab = new JPanel(new BorderLayout(8, 8));
        tab.setBorder(new EmptyBorder(6, 6, 6, 6));

        EStopConfig cfg = project.getEStopConfig();

        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createTitledBorder("E-Stop Reaction & Reset Policy"));
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(4, 6, 4, 6);
        gc.anchor = GridBagConstraints.WEST;
        gc.fill = GridBagConstraints.HORIZONTAL;

        eStopEnabledCheck = new JCheckBox("Enable E-Stop Supervision", cfg.isEnabled());
        eStopEnabledCheck.setFont(AppFonts.uiBold());
        gc.gridx = 0;
        gc.gridy = 0;
        gc.gridwidth = 3;
        form.add(eStopEnabledCheck, gc);

        gc.gridwidth = 1;
        gc.gridx = 0;
        gc.gridy = 1;
        form.add(new JLabel("Reaction Mode:"), gc);

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

        JButton openOrCreatePageBtn = new JButton("Create / Open Page...");
        openOrCreatePageBtn.addActionListener(e -> ensureAndOpenEStopPage());
        gc.gridx = 2;
        gc.gridy = 2;
        gc.weightx = 0;
        form.add(openOrCreatePageBtn, gc);

        manualResetCheck = new JCheckBox("Require Manual Reset", cfg.isRequireManualReset());
        gc.gridx = 0;
        gc.gridy = 3;
        gc.gridwidth = 3;
        form.add(manualResetCheck, gc);

        tab.add(form, BorderLayout.NORTH);

        JPanel tablePanel = new JPanel(new BorderLayout(4, 4));
        tablePanel.setBorder(BorderFactory.createTitledBorder("E-Stop Input Channels"));

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
        rulesTable.getColumnModel().getColumn(2).setPreferredWidth(AppFonts.scaled(140));
        rulesTable.getColumnModel().getColumn(3).setPreferredWidth(AppFonts.scaled(260));

        tablePanel.add(new JScrollPane(rulesTable), BorderLayout.CENTER);

        JPanel ruleBtns = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        JButton addRuleBtn = new JButton("+ Add Input");
        addRuleBtn.addActionListener(e -> {
            int nextCh = Math.min(15, workingRules.size());
            workingRules.add(new EStopConfig.InputRule("DI" + nextCh, true, true, ""));
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
                    "Select language for E-Stop Page:",
                    "Create E-Stop Page",
                    JOptionPane.QUESTION_MESSAGE,
                    null,
                    langs,
                    project.getPrimaryLanguage()
            );
            if (chosen == null) return;

            existing = new Page(rawName, chosen);
            existing.setEStopPage(true);
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

        UnitConfig ucfg = project.getUnitConfig();
        ucfg.setUnitName(unitNameField.getText());
        ucfg.setStationId(parseIntOrDefault(stationIdField.getText(), ucfg.getStationId()));
        ucfg.setIpMode((UnitConfig.IpMode) ipModeCombo.getSelectedItem());
        ucfg.setStaticIp(staticIpField.getText());
        ucfg.setSubnetMask(subnetMaskField.getText());
        ucfg.setGateway(gatewayField.getText());
        ucfg.setTcpPort(parseIntOrDefault(tcpPortField.getText(), ucfg.getTcpPort()));
        ucfg.setRunOutputPin(String.valueOf(runOutCombo.getSelectedItem()));
        ucfg.setFaultOutputPin(String.valueOf(faultOutCombo.getSelectedItem()));
        ucfg.setEstopOutputPin(String.valueOf(estopOutCombo.getSelectedItem()));
        ucfg.setBootMode((UnitConfig.BootMode) bootModeCombo.getSelectedItem());
        ucfg.setSafeOutputsOnStop(safeOutputsCheck.isSelected());
        ucfg.setWatchdogEnabled(watchdogCheck.isSelected());
        ucfg.setRs485Mode((UnitConfig.Rs485Mode) rs485ModeCombo.getSelectedItem());
        Object selBaud = rs485BaudCombo.getSelectedItem();
        if (selBaud instanceof Integer b) {
            ucfg.setRs485BaudRate(b);
        }
        ucfg.setAccessPassword(new String(accessPassField.getPassword()));

        EStopConfig cfg = project.getEStopConfig();
        cfg.setEnabled(eStopEnabledCheck.isSelected());
        cfg.setMode((EStopConfig.Mode) eStopModeCombo.getSelectedItem());
        cfg.setRequireManualReset(manualResetCheck.isSelected());
        cfg.setDedicatedEStopPageName(eStopPageField.getText());
        cfg.getInputRules().clear();
        for (EStopConfig.InputRule r : workingRules) {
            cfg.getInputRules().add(r.copy());
        }
        ucfg.getModbusSlaveMap().clear();
        for (UnitConfig.ModbusSlaveEntry e : workingModbusMap) {
            ucfg.getModbusSlaveMap().add(e.copy());
        }
        project.setDirty(true);
    }

    private void applyAndClose() {
        applyChangesToProject();
        if (connectedClient != null) {
            try {
                connectedClient.writeUnitConfig(project.getUnitConfig());
                connectedClient.writeModbusMap(project.getUnitConfig(), project);
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(
                        this,
                        "Failed to write Unit Config to unit:\n" + ex.getMessage(),
                        "Write Error",
                        JOptionPane.ERROR_MESSAGE
                );
                return;
            } finally {
                closeClientQuietly();
            }
        }
        dispose();
    }

    private void closeClientQuietly() {
        if (connectedClient != null) {
            try { connectedClient.close(); } catch (Exception ignored) {}
        }
    }

    private static GridBagConstraints createGbc() {
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(3, 6, 3, 6);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.anchor = GridBagConstraints.WEST;
        return gbc;
    }

    private static void addRow(JPanel panel, GridBagConstraints gbc, int row, String label, java.awt.Component comp) {
        gbc.gridwidth = 1;
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.weightx = 0.0;
        panel.add(new JLabel(label), gbc);
        gbc.gridx = 1;
        gbc.weightx = 1.0;
        panel.add(comp, gbc);
    }

    public static JTextField createDigitsOnlyField(String initial, int cols) {
        JTextField tf = new JTextField(initial, cols);
        ((AbstractDocument) tf.getDocument()).setDocumentFilter(new DocumentFilter() {
            @Override
            public void insertString(FilterBypass fb, int offset, String string, AttributeSet attr) throws BadLocationException {
                if (string != null && string.chars().allMatch(Character::isDigit)) {
                    super.insertString(fb, offset, string, attr);
                }
            }

            @Override
            public void replace(FilterBypass fb, int offset, int length, String text, AttributeSet attrs) throws BadLocationException {
                if (text != null && text.chars().allMatch(Character::isDigit)) {
                    super.replace(fb, offset, length, text, attrs);
                }
            }
        });
        return tf;
    }

    private static int parseIntOrDefault(String s, int def) {
        if (s == null || s.isBlank()) return def;
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return def; }
    }

    private final class EStopRulesTableModel extends AbstractTableModel {
        private final String[] cols = {"Enabled", "Input Pin", "Trip Polarity", "Description"};

        @Override
        public int getRowCount() { return workingRules.size(); }

        @Override
        public int getColumnCount() { return cols.length; }

        @Override
        public String getColumnName(int column) { return cols[column]; }

        @Override
        public Class<?> getColumnClass(int columnIndex) {
            return columnIndex == 0 ? Boolean.class : String.class;
        }

        @Override
        public boolean isCellEditable(int rowIndex, int columnIndex) { return true; }

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

    private JPanel buildModbusMapTab() {
        JPanel tab = new JPanel(new BorderLayout(8, 8));
        tab.setBorder(new EmptyBorder(6, 6, 6, 6));

        JLabel info = new JLabel("Map Modbus RTU Slave addresses (up to 64 entries) to any PLC I/O pin, Virtual Register, or VM slot (BOOL:N / INT:N / FLOAT:N).");
        tab.add(info, BorderLayout.NORTH);

        modbusMapTableModel = new ModbusMapTableModel();
        JTable table = new JTable(modbusMapTableModel);
        table.setRowHeight(AppFonts.scaled(22));
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        JComboBox<UnitConfig.ModbusTable> tblCombo = new JComboBox<>(UnitConfig.ModbusTable.values());
        table.getColumnModel().getColumn(0).setCellEditor(new DefaultCellEditor(tblCombo));

        java.util.LinkedHashSet<String> targets = new java.util.LinkedHashSet<>();
        for (int i = 0; i <= 27; i++) targets.add("DI" + i);
        for (int i = 0; i <= 27; i++) targets.add("DO" + i);
        for (int i = 0; i <= 3; i++) targets.add("AI" + i);
        for (int i = 0; i <= 1; i++) targets.add("AO" + i);
        for (Page p : project.getPages()) {
            for (var b : p.getAllBlockInstances()) {
                if (b.getDefinitionId().equals("Main.Execution.ReadReg") || b.getDefinitionId().equals("Main.Execution.WriteReg")) {
                    String rn = b.getCustomProperties().get("RegisterName");
                    if (rn != null && !rn.isBlank()) targets.add(rn.trim().toUpperCase());
                }
            }
        }
        JComboBox<String> targetCombo = new JComboBox<>(targets.toArray(new String[0]));
        targetCombo.setEditable(true);
        table.getColumnModel().getColumn(2).setCellEditor(new DefaultCellEditor(targetCombo));

        table.getColumnModel().getColumn(0).setPreferredWidth(AppFonts.scaled(160));
        table.getColumnModel().getColumn(1).setPreferredWidth(AppFonts.scaled(110));
        table.getColumnModel().getColumn(2).setPreferredWidth(AppFonts.scaled(180));
        table.getColumnModel().getColumn(3).setPreferredWidth(AppFonts.scaled(80));

        tab.add(new JScrollPane(table), BorderLayout.CENTER);

        JPanel btns = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        JButton addBtn = new JButton("+ Add Mapping");
        addBtn.addActionListener(e -> {
            if (workingModbusMap.size() >= 64) {
                JOptionPane.showMessageDialog(this, "Maximum 64 Modbus Slave mappings supported.", "Limit Reached", JOptionPane.WARNING_MESSAGE);
                return;
            }
            int nextAddr = workingModbusMap.size();
            workingModbusMap.add(new UnitConfig.ModbusSlaveEntry(UnitConfig.ModbusTable.HOLDING_REGISTER, nextAddr, "INT:0", false));
            modbusMapTableModel.fireTableDataChanged();
        });
        JButton removeBtn = new JButton("- Remove Selected");
        removeBtn.addActionListener(e -> {
            int row = table.getSelectedRow();
            if (row >= 0 && row < workingModbusMap.size()) {
                workingModbusMap.remove(row);
                modbusMapTableModel.fireTableDataChanged();
            }
        });
        btns.add(addBtn);
        btns.add(removeBtn);
        tab.add(btns, BorderLayout.SOUTH);
        return tab;
    }

    private final class ModbusMapTableModel extends AbstractTableModel {
        private final String[] cols = {"Modbus Table", "Address (0..65535)", "PLC Target", "Read-Only"};

        @Override
        public int getRowCount() { return workingModbusMap.size(); }

        @Override
        public int getColumnCount() { return cols.length; }

        @Override
        public String getColumnName(int column) { return cols[column]; }

        @Override
        public Class<?> getColumnClass(int columnIndex) {
            return switch (columnIndex) {
                case 0 -> UnitConfig.ModbusTable.class;
                case 1 -> Integer.class;
                case 3 -> Boolean.class;
                default -> String.class;
            };
        }

        @Override
        public boolean isCellEditable(int rowIndex, int columnIndex) { return true; }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            UnitConfig.ModbusSlaveEntry e = workingModbusMap.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> e.getTable();
                case 1 -> e.getModbusAddr();
                case 2 -> e.getTargetRef();
                case 3 -> e.isReadOnly();
                default -> "";
            };
        }

        @Override
        public void setValueAt(Object aValue, int rowIndex, int columnIndex) {
            UnitConfig.ModbusSlaveEntry e = workingModbusMap.get(rowIndex);
            switch (columnIndex) {
                case 0 -> {
                    if (aValue instanceof UnitConfig.ModbusTable t) e.setTable(t);
                }
                case 1 -> {
                    try { e.setModbusAddr(Integer.parseInt(String.valueOf(aValue).trim())); }
                    catch (NumberFormatException ignored) {}
                }
                case 2 -> e.setTargetRef(String.valueOf(aValue));
                case 3 -> e.setReadOnly(Boolean.TRUE.equals(aValue));
            }
            fireTableRowsUpdated(rowIndex, rowIndex);
        }
    }
}
