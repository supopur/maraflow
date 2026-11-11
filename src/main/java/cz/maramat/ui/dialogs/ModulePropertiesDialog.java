package cz.maramat.ui.dialogs;

import cz.maramat.model.BlockCatalog;
import cz.maramat.model.BlockDefinition;
import cz.maramat.model.BlockInstance;
import cz.maramat.model.DataType;
import cz.maramat.model.HeaderColor;
import cz.maramat.model.Page;
import cz.maramat.model.PinInstance;
import cz.maramat.model.PlcThread;
import cz.maramat.model.Project;
import cz.maramat.ui.icons.VectorIcons;

import javax.swing.BorderFactory;
import javax.swing.DefaultCellEditor;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableCellEditor;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ModulePropertiesDialog extends JDialog {
    private boolean confirmed = false;
    private final Project project;
    private final BlockInstance block;
    private final Page page;

    private String editedName;
    private PlcThread editedThread;
    private int editedOrder;
    private HeaderColor editedColor;
    private String editedDescription;

    private final Map<String, JComponent> specificControls = new LinkedHashMap<>();
    private JTable propTable;
    private JTable macroPinTable;
    private MacroPinsTableModel macroPinsModel;

    private static final String[] STM32_PIN_IDS = {
            "PA0", "PA1", "PA2", "PA3", "PA4", "PA5", "PA6", "PA7",
            "PB0", "PB1", "PB2", "PB3", "PB4", "PB5", "PB6", "PB7",
            "PC0", "PC1", "PC2", "PC3", "PC4", "PC5", "PC6", "PC7",
            "PD0", "PD1", "PD2", "PD3", "PD4", "PD5", "PD6", "PD7"
    };

    public ModulePropertiesDialog(Frame owner, Page page, BlockInstance block) {
        this(owner, null, page, block);
    }

    public ModulePropertiesDialog(Frame owner, Project project, Page page, BlockInstance block) {
        super(owner, "Module properties", true);
        this.project = project;
        this.page = page;
        this.block = block;

        this.editedName = block.getName();
        this.editedThread = PlcThread.fromId(block.getThreadId());
        this.editedOrder = block.getOrder();
        this.editedColor = block.getHeaderColor();
        this.editedDescription = block.getDescription();

        buildUi();
        pack();
        setMinimumSize(new Dimension(620, 380));
        setLocationRelativeTo(owner);
    }

    private void buildUi() {
        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        // 1. Left: Standard Properties Table (Property | Value)
        StandardPropsTableModel tableModel = new StandardPropsTableModel();
        JComboBox<PlcThread> threadCombo = new JComboBox<>(PlcThread.values());
        JComboBox<HeaderColor> colorCombo = new JComboBox<>(HeaderColor.values());

        propTable = new JTable(tableModel) {
            @Override
            public TableCellEditor getCellEditor(int row, int column) {
                if (column == 1) {
                    if (row == 1) return new DefaultCellEditor(threadCombo);
                    if (row == 3) return new DefaultCellEditor(colorCombo);
                }
                return super.getCellEditor(row, column);
            }
        };
        propTable.setRowHeight(22);
        propTable.setShowGrid(true);
        propTable.setGridColor(new Color(0xDC, 0xDC, 0xDC));
        propTable.getTableHeader().setReorderingAllowed(false);
        propTable.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);

        JScrollPane tableScroll = new JScrollPane(propTable);
        tableScroll.setPreferredSize(new Dimension(265, 240));

        // 2. Right: Block-Specific Configuration Panel
        JPanel specificsPanel = buildBlockSpecificsPanel();
        JScrollPane specificsScroll = new JScrollPane(specificsPanel);
        specificsScroll.setBorder(BorderFactory.createTitledBorder("Block Specifics (" + block.getTypeName() + ")"));
        specificsScroll.setPreferredSize(new Dimension(285, 240));

        JPanel centerSplit = new JPanel(new BorderLayout(8, 0));
        centerSplit.add(tableScroll, BorderLayout.WEST);
        centerSplit.add(specificsScroll, BorderLayout.CENTER);
        root.add(centerSplit, BorderLayout.CENTER);

        // 3. Bottom: OK and Cancel buttons with Vector Icons ({ICON} {Text})
        JPanel bottomBar = new JPanel(new FlowLayout(FlowLayout.CENTER, 14, 2));
        JButton okBtn = new JButton("OK", VectorIcons.greenCheckIcon());
        okBtn.setPreferredSize(new Dimension(96, 28));
        okBtn.addActionListener(e -> onConfirm());

        JButton cancelBtn = new JButton("Cancel", VectorIcons.redCrossIcon());
        cancelBtn.setPreferredSize(new Dimension(96, 28));
        cancelBtn.addActionListener(e -> dispose());

        bottomBar.add(okBtn);
        bottomBar.add(cancelBtn);
        root.add(bottomBar, BorderLayout.SOUTH);

        getRootPane().setDefaultButton(okBtn);
        setContentPane(root);
    }

    private JPanel buildBlockSpecificsPanel() {
        String defId = block.getDefinitionId();
        if ("Macro.MacroIn".equals(defId) || "Macro.MacroOut".equals(defId)) {
            return buildMacroBoundaryPanel("Macro.MacroIn".equals(defId));
        }

        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(3, 4, 3, 4);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.anchor = GridBagConstraints.NORTHWEST;

        BlockDefinition def = BlockCatalog.getById(defId);
        int row = 0;

        if (def != null && def.isVariableInputCount()) {
            int curCount = Integer.parseInt(block.getCustomProperties().getOrDefault("InputCount", "2"));
            JSpinner sp = new JSpinner(new SpinnerNumberModel(curCount, 2, 16, 1));
            addRow(p, gbc, row++, "Input Pin Count:", sp);
            specificControls.put("InputCount", sp);
        }

        if (def != null && def.isConfigurableDataType()) {
            String curDt = block.getCustomProperties().getOrDefault("DataType", "INT");
            JComboBox<DataType> dtBox = new JComboBox<>(DataType.values());
            dtBox.setSelectedItem(DataType.valueOf(curDt));
            addRow(p, gbc, row++, "Data Type:", dtBox);
            specificControls.put("DataType", dtBox);
        }

        if (defId.equals("IO.8xDI") || defId.equals("IO.8xDO")) {
            String pinLabelPrefix = defId.endsWith("DI") ? "DI" : "DO";
            for (int i = 0; i < 8; i++) {
                String key = "Pin" + i;
                String currentPin = block.getCustomProperties().getOrDefault(key, "PA" + i);
                JComboBox<String> pinCombo = new JComboBox<>(STM32_PIN_IDS);
                pinCombo.setSelectedItem(currentPin);
                addRow(p, gbc, row++, pinLabelPrefix + i + " Hardware Pin:", pinCombo);
                specificControls.put(key, pinCombo);
            }
        } else {
            for (Map.Entry<String, String> entry : block.getCustomProperties().entrySet()) {
                String k = entry.getKey();
                if (k.equals("InputCount") || k.equals("DataType")) continue;
                String v = entry.getValue();

                if (k.equals("InputType")) {
                    JComboBox<String> modeBox = new JComboBox<>(new String[]{"Switch", "Slider"});
                    modeBox.setSelectedItem(v);
                    addRow(p, gbc, row++, "Input Mode:", modeBox);
                    specificControls.put(k, modeBox);
                } else if (k.equals("TimerMode")) {
                    JComboBox<String> tmBox = new JComboBox<>(new String[]{"TON (On-Delay)", "TOF (Off-Delay)", "TP (Pulse)"});
                    tmBox.setSelectedItem(v);
                    addRow(p, gbc, row++, "Timer Mode:", tmBox);
                    specificControls.put(k, tmBox);
                } else if (k.equals("RetentiveEeprom")) {
                    JComboBox<String> retBox = new JComboBox<>(new String[]{"false", "true"});
                    retBox.setSelectedItem(v);
                    addRow(p, gbc, row++, "Retentive (EEPROM):", retBox);
                    specificControls.put(k, retBox);
                } else if (k.equals("HardwareChannel")) {
                    JComboBox<String> chBox = new JComboBox<>(STM32_PIN_IDS);
                    chBox.setEditable(true);
                    chBox.setSelectedItem(v);
                    addRow(p, gbc, row++, "Hardware Pin:", chBox);
                    specificControls.put(k, chBox);
                } else if (k.equals("RegisterName")) {
                    List<String> regNames = project != null
                            ? project.getAllKnownRegisterAndTagNames()
                            : List.of("REG_0", "REG_1", "REG_2", "REG_3", "REG_4", "REG_5", "REG_6", "REG_7");
                    JComboBox<String> regBox = new JComboBox<>(regNames.toArray(new String[0]));
                    regBox.setEditable(true);
                    regBox.setSelectedItem(v);
                    addRow(p, gbc, row++, "Register / Tag:", regBox);
                    specificControls.put(k, regBox);
                } else if (k.equals("TriggerMode")) {
                    JComboBox<String> trigBox = new JComboBox<>(new String[]{
                            "Continuous (While En=1)",
                            "Rising Edge (0 -> 1)"
                    });
                    trigBox.setSelectedItem(v);
                    addRow(p, gbc, row++, "Trigger Mode:", trigBox);
                    specificControls.put(k, trigBox);
                } else if (k.equals("InterruptSource")) {
                    JComboBox<String> srcBox = new JComboBox<>(new String[]{
                            "TIM_IC1",
                            "TIM_IC2",
                            "EXTI_DI0", "EXTI_DI1", "EXTI_DI2", "EXTI_DI3",
                            "EXTI_DI4", "EXTI_DI5", "EXTI_DI6", "EXTI_DI7",
                            "UART_RX_IDLE", "ADC_WATCHDOG"
                    });
                    srcBox.setEditable(true);
                    String selVal = v;
                    if (selVal != null && selVal.startsWith("TIM_IC1")) selVal = "TIM_IC1";
                    else if (selVal != null && selVal.startsWith("TIM_IC2")) selVal = "TIM_IC2";
                    srcBox.setSelectedItem(selVal);
                    addRow(p, gbc, row++, "Interrupt Source:", srcBox);
                    specificControls.put(k, srcBox);
                } else if (k.equals("TriggerEdge")) {
                    JComboBox<String> edgeBox = new JComboBox<>(new String[]{"RISING", "FALLING", "BOTH"});
                    edgeBox.setSelectedItem(v);
                    addRow(p, gbc, row++, "Trigger Edge:", edgeBox);
                    specificControls.put(k, edgeBox);
                } else if (k.equals("CaptureMode")) {
                    JComboBox<String> capBox = new JComboBox<>(new String[]{
                            "GPIO_STATE (0/1)",
                            "PULSE_WIDTH_US",
                            "PERIOD_US",
                            "EDGE_COUNT"
                    });
                    capBox.setSelectedItem(v);
                    addRow(p, gbc, row++, "Capture Mode:", capBox);
                    specificControls.put(k, capBox);
                } else if (k.equals("LatchPolicy")) {
                    JComboBox<String> latchBox = new JComboBox<>(new String[]{
                            "HOLD_FIRST_UNTIL_RST",
                            "OVERWRITE_LATEST"
                    });
                    latchBox.setSelectedItem(v);
                    addRow(p, gbc, row++, "Latch Policy:", latchBox);
                    specificControls.put(k, latchBox);
                } else if (k.equals("Channel") && defId.startsWith("IO.")) {
                    String[] channels = buildChannelOptionsForBlock(defId);
                    JComboBox<String> chBox = new JComboBox<>(channels);
                    chBox.setEditable(true);
                    chBox.setSelectedItem(v);
                    addRow(p, gbc, row++, "I/O Channel:", chBox);
                    specificControls.put(k, chBox);
                } else {
                    JTextField tf = new JTextField(v, 12);
                    addRow(p, gbc, row++, k + ":", tf);
                    specificControls.put(k, tf);
                }
            }
        }

        if (row == 0) {
            gbc.gridx = 0;
            gbc.gridy = 0;
            gbc.gridwidth = 2;
            JLabel noneLabel = new JLabel("Standard execution block.");
            noneLabel.setFont(noneLabel.getFont().deriveFont(Font.ITALIC));
            noneLabel.setForeground(new Color(0x66, 0x66, 0x66));
            p.add(noneLabel, gbc);
        }

        // Push rows to top
        gbc.gridx = 0;
        gbc.gridy = row + 1;
        gbc.weighty = 1.0;
        p.add(new JPanel(), gbc);
        return p;
    }

    private String[] buildChannelOptionsForBlock(String defId) {
        return switch (defId) {
            case "IO.DI" -> new String[]{
                    "DI0", "DI1", "DI2", "DI3", "DI4", "DI5", "DI6", "DI7",
                    "DI8", "DI9", "DI10", "DI11", "DI12", "DI13", "DI14", "DI15"
            };
            case "IO.DO" -> new String[]{
                    "DO0", "DO1", "DO2", "DO3", "DO4", "DO5", "DO6", "DO7",
                    "DO8", "DO9", "DO10", "DO11", "DO12", "DO13", "DO14", "DO15"
            };
            case "IO.AI" -> new String[]{"AI0", "AI1", "AI2", "AI3", "AI4", "AI5", "AI6", "AI7"};
            case "IO.AO" -> new String[]{"AO0", "AO1", "AO2", "AO3"};
            case "IO.PWM" -> new String[]{"PWM0", "PWM1", "PWM2", "PWM3"};
            default -> STM32_PIN_IDS;
        };
    }

    private JPanel buildMacroBoundaryPanel(boolean isMacroIn) {
        JPanel panel = new JPanel(new BorderLayout(4, 4));
        panel.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

        JLabel title = new JLabel(isMacroIn
                ? "Macro Input Pins (exposed on left of Macro):"
                : "Macro Output Pins (exposed on right of Macro):");
        title.setFont(new Font("SansSerif", Font.BOLD, 11));
        panel.add(title, BorderLayout.NORTH);

        List<PinInstance> existingPins = isMacroIn ? block.getOutputs() : block.getInputs();
        macroPinsModel = new MacroPinsTableModel(existingPins, isMacroIn);
        JComboBox<DataType> dtCombo = new JComboBox<>(DataType.values());

        macroPinTable = new JTable(macroPinsModel) {
            @Override
            public TableCellEditor getCellEditor(int row, int column) {
                if (column == 1) {
                    return new DefaultCellEditor(dtCombo);
                }
                return super.getCellEditor(row, column);
            }
        };
        macroPinTable.setRowHeight(22);
        macroPinTable.setShowGrid(true);
        macroPinTable.setGridColor(new Color(0xDC, 0xDC, 0xDC));
        macroPinTable.getTableHeader().setReorderingAllowed(false);
        macroPinTable.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);

        JScrollPane scroll = new JScrollPane(macroPinTable);
        scroll.setPreferredSize(new Dimension(280, 150));
        panel.add(scroll, BorderLayout.CENTER);

        JPanel btnBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        JButton addBtn = new JButton("+ Pin");
        JButton remBtn = new JButton("- Pin");
        JButton upBtn = new JButton("Up");
        JButton downBtn = new JButton("Down");

        for (JButton b : new JButton[]{addBtn, remBtn, upBtn, downBtn}) {
            b.setMargin(new Insets(2, 6, 2, 6));
            b.setFont(new Font("SansSerif", Font.PLAIN, 11));
            btnBar.add(b);
        }

        addBtn.addActionListener(e -> {
            if (macroPinTable.isEditing()) macroPinTable.getCellEditor().stopCellEditing();
            macroPinsModel.addPin();
            int last = macroPinsModel.getRowCount() - 1;
            if (last >= 0) macroPinTable.setRowSelectionInterval(last, last);
        });
        remBtn.addActionListener(e -> {
            if (macroPinTable.isEditing()) macroPinTable.getCellEditor().stopCellEditing();
            int sel = macroPinTable.getSelectedRow();
            macroPinsModel.removePin(sel);
            int rows = macroPinsModel.getRowCount();
            if (rows > 0) {
                int nextSel = Math.min(sel, rows - 1);
                if (nextSel >= 0) macroPinTable.setRowSelectionInterval(nextSel, nextSel);
            }
        });
        upBtn.addActionListener(e -> {
            if (macroPinTable.isEditing()) macroPinTable.getCellEditor().stopCellEditing();
            int sel = macroPinTable.getSelectedRow();
            if (macroPinsModel.movePin(sel, -1)) {
                macroPinTable.setRowSelectionInterval(sel - 1, sel - 1);
            }
        });
        downBtn.addActionListener(e -> {
            if (macroPinTable.isEditing()) macroPinTable.getCellEditor().stopCellEditing();
            int sel = macroPinTable.getSelectedRow();
            if (macroPinsModel.movePin(sel, 1)) {
                macroPinTable.setRowSelectionInterval(sel + 1, sel + 1);
            }
        });

        panel.add(btnBar, BorderLayout.SOUTH);
        return panel;
    }

    private void addRow(JPanel panel, GridBagConstraints gbc, int row, String labelText, JComponent comp) {
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.weightx = 0.0;
        panel.add(new JLabel(labelText), gbc);

        gbc.gridx = 1;
        gbc.weightx = 1.0;
        panel.add(comp, gbc);
    }

    private void onConfirm() {
        if (propTable.isEditing()) {
            propTable.getCellEditor().stopCellEditing();
        }
        if (macroPinTable != null && macroPinTable.isEditing()) {
            macroPinTable.getCellEditor().stopCellEditing();
        }
        String trimmedName = editedName == null ? "" : editedName.trim();
        if (trimmedName.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Block Name cannot be empty.", "Validation Error", JOptionPane.ERROR_MESSAGE);
            return;
        }
        for (BlockInstance other : page.getBlocks()) {
            if (other != block && other.getName().equalsIgnoreCase(trimmedName)) {
                JOptionPane.showMessageDialog(this, "A block named '" + trimmedName + "' already exists on this page.", "Validation Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
        }
        if (editedOrder < 0) {
            JOptionPane.showMessageDialog(this, "Execution Order cannot be negative.", "Validation Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        if (macroPinsModel != null) {
            String err = macroPinsModel.validatePins();
            if (err != null) {
                JOptionPane.showMessageDialog(this, err, "Macro Pin Validation Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
        }

        block.setName(trimmedName);
        block.setThreadId(editedThread.getId());
        block.setOrder(editedOrder);
        block.setHeaderColor(editedColor);
        block.setDescription(editedDescription);

        // Apply macro pins if editing MacroIn or MacroOut
        if (macroPinsModel != null) {
            boolean isMacroIn = "Macro.MacroIn".equals(block.getDefinitionId());
            List<PinInstance> targetList = isMacroIn ? block.getOutputs() : block.getInputs();

            List<PinInstance> updatedPins = new ArrayList<>();
            List<MacroPinRow> rows = macroPinsModel.getRows();
            for (int i = 0; i < rows.size(); i++) {
                MacroPinRow r = rows.get(i);
                String name = r.name.trim();
                String shortName = name.length() > 3 ? name.substring(0, 3) : name;
                String defVal = r.defaultVal == null || r.defaultVal.isBlank() ? "0" : r.defaultVal.trim();
                try {
                    defVal = r.dataType.validateAndNormalizeConstant(defVal);
                } catch (IllegalArgumentException ex) {
                    defVal = "0";
                }
                PinInstance p = new PinInstance(shortName, name, r.dataType, !isMacroIn, defVal);
                updatedPins.add(p);
            }
            targetList.clear();
            targetList.addAll(updatedPins);

            int maxPinCount = updatedPins.size();
            if (isMacroIn) {
                page.getWires().removeIf(w -> w.getSourceBlockId().equals(block.getId()) && w.getSourcePinIndex() >= maxPinCount);
            } else {
                page.getWires().removeIf(w -> w.getTargetBlockId().equals(block.getId()) && w.getTargetPinIndex() >= maxPinCount);
            }
        }

        // Apply specific controls
        for (Map.Entry<String, JComponent> entry : specificControls.entrySet()) {
            String key = entry.getKey();
            JComponent comp = entry.getValue();
            if (key.equals("InputCount") && comp instanceof JSpinner sp) {
                int newCount = (Integer) sp.getValue();
                block.setVariableInputCount(newCount);
            } else if (key.equals("DataType") && comp instanceof JComboBox<?> cb) {
                DataType dt = (DataType) cb.getSelectedItem();
                if (dt != null) {
                    block.setPrimaryDataType(dt);
                }
            } else if (comp instanceof JComboBox<?> cb) {
                Object sel = cb.isEditable() ? cb.getEditor().getItem() : cb.getSelectedItem();
                if (sel != null) {
                    block.getCustomProperties().put(key, sel.toString().trim());
                }
            } else if (comp instanceof JTextField tf) {
                block.getCustomProperties().put(key, tf.getText().trim());
            }
        }

        page.updateWiresForBlock(block);
        if (project != null && page.isMacro()) {
            BlockCatalog.syncMacrosFromProject(project);
        }
        confirmed = true;
        dispose();
    }

    public boolean isConfirmed() {
        return confirmed;
    }

    private class StandardPropsTableModel extends AbstractTableModel {
        private final String[] propNames = {"Name", "ThreadId", "Order", "Color", "Description"};

        @Override
        public int getRowCount() {
            return propNames.length;
        }

        @Override
        public int getColumnCount() {
            return 2;
        }

        @Override
        public String getColumnName(int column) {
            return column == 0 ? "Property" : "Value";
        }

        @Override
        public boolean isCellEditable(int rowIndex, int columnIndex) {
            return columnIndex == 1;
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            if (columnIndex == 0) return propNames[rowIndex];
            return switch (rowIndex) {
                case 0 -> editedName;
                case 1 -> editedThread;
                case 2 -> editedOrder;
                case 3 -> editedColor;
                case 4 -> editedDescription;
                default -> "";
            };
        }

        @Override
        public void setValueAt(Object aValue, int rowIndex, int columnIndex) {
            if (columnIndex != 1 || aValue == null) return;
            switch (rowIndex) {
                case 0 -> editedName = aValue.toString();
                case 1 -> {
                    if (aValue instanceof PlcThread pt) editedThread = pt;
                }
                case 2 -> {
                    try {
                        int v = Integer.parseInt(aValue.toString().trim());
                        if (v < 0) {
                            JOptionPane.showMessageDialog(ModulePropertiesDialog.this,
                                    "Order cannot be below 0.", "Invalid Order", JOptionPane.ERROR_MESSAGE);
                        } else {
                            editedOrder = v;
                        }
                    } catch (NumberFormatException ex) {
                        JOptionPane.showMessageDialog(ModulePropertiesDialog.this,
                                "Order must be a valid integer.", "Invalid Order", JOptionPane.ERROR_MESSAGE);
                    }
                }
                case 3 -> {
                    if (aValue instanceof HeaderColor hc) editedColor = hc;
                }
                case 4 -> editedDescription = aValue.toString();
            }
            fireTableCellUpdated(rowIndex, columnIndex);
        }
    }

    private static class MacroPinRow {
        String name;
        DataType dataType;
        String defaultVal;

        MacroPinRow(String name, DataType dataType, String defaultVal) {
            this.name = name;
            this.dataType = dataType;
            this.defaultVal = defaultVal;
        }
    }

    private class MacroPinsTableModel extends AbstractTableModel {
        private final String[] cols = {"Pin Name", "Data Type", "Default"};
        private final List<MacroPinRow> rows = new ArrayList<>();
        private final boolean isMacroIn;

        MacroPinsTableModel(List<PinInstance> existingPins, boolean isMacroIn) {
            this.isMacroIn = isMacroIn;
            for (PinInstance p : existingPins) {
                String n = (p.getFullName() != null && !p.getFullName().isBlank()) ? p.getFullName() : p.getShortName();
                rows.add(new MacroPinRow(n, p.getDataType(), p.getConstantValue()));
            }
            if (rows.isEmpty()) {
                rows.add(new MacroPinRow(isMacroIn ? "X1" : "Y1", DataType.INT, "0"));
            }
        }

        List<MacroPinRow> getRows() {
            return rows;
        }

        void addPin() {
            String prefix = isMacroIn ? "X" : "Y";
            int idx = rows.size() + 1;
            String candidate = prefix + idx;
            while (containsName(candidate)) {
                idx++;
                candidate = prefix + idx;
            }
            rows.add(new MacroPinRow(candidate, DataType.INT, "0"));
            fireTableRowsInserted(rows.size() - 1, rows.size() - 1);
        }

        private boolean containsName(String name) {
            for (MacroPinRow r : rows) {
                if (r.name != null && r.name.equalsIgnoreCase(name)) return true;
            }
            return false;
        }

        void removePin(int idx) {
            if (rows.size() <= 1) {
                JOptionPane.showMessageDialog(ModulePropertiesDialog.this,
                        "A Macro boundary block must have at least 1 pin.",
                        "Cannot Remove Pin", JOptionPane.WARNING_MESSAGE);
                return;
            }
            if (idx >= 0 && idx < rows.size()) {
                rows.remove(idx);
                fireTableRowsDeleted(idx, idx);
            }
        }

        boolean movePin(int idx, int delta) {
            int target = idx + delta;
            if (idx < 0 || idx >= rows.size() || target < 0 || target >= rows.size()) {
                return false;
            }
            MacroPinRow tmp = rows.get(idx);
            rows.set(idx, rows.get(target));
            rows.set(target, tmp);
            fireTableRowsUpdated(Math.min(idx, target), Math.max(idx, target));
            return true;
        }

        String validatePins() {
            if (rows.isEmpty()) {
                return "At least one pin is required.";
            }
            for (int i = 0; i < rows.size(); i++) {
                MacroPinRow r = rows.get(i);
                String n = r.name == null ? "" : r.name.trim();
                if (n.isEmpty()) {
                    return "Pin name on row " + (i + 1) + " cannot be empty.";
                }
                if (n.equalsIgnoreCase("En")) {
                    return "Pin name 'En' is reserved for the Macro execution enable input.";
                }
                for (int j = i + 1; j < rows.size(); j++) {
                    if (n.equalsIgnoreCase(rows.get(j).name.trim())) {
                        return "Duplicate pin name '" + n + "'.";
                    }
                }
            }
            return null;
        }

        @Override
        public int getRowCount() {
            return rows.size();
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
        public boolean isCellEditable(int rowIndex, int columnIndex) {
            return true;
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            MacroPinRow r = rows.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> r.name;
                case 1 -> r.dataType;
                case 2 -> r.defaultVal;
                default -> "";
            };
        }

        @Override
        public void setValueAt(Object aValue, int rowIndex, int columnIndex) {
            if (aValue == null || rowIndex < 0 || rowIndex >= rows.size()) return;
            MacroPinRow r = rows.get(rowIndex);
            switch (columnIndex) {
                case 0 -> r.name = aValue.toString().trim();
                case 1 -> {
                    if (aValue instanceof DataType dt) r.dataType = dt;
                }
                case 2 -> r.defaultVal = aValue.toString().trim();
            }
            fireTableCellUpdated(rowIndex, columnIndex);
        }
    }
}
