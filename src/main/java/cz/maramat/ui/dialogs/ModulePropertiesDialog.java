package cz.maramat.ui.dialogs;

import cz.maramat.model.BlockCatalog;
import cz.maramat.model.BlockDefinition;
import cz.maramat.model.BlockInstance;
import cz.maramat.model.DataType;
import cz.maramat.model.HeaderColor;
import cz.maramat.model.Page;
import cz.maramat.model.PlcThread;
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
import java.util.LinkedHashMap;
import java.util.Map;

public class ModulePropertiesDialog extends JDialog {
    private boolean confirmed = false;
    private final BlockInstance block;
    private final Page page;

    private String editedName;
    private PlcThread editedThread;
    private int editedOrder;
    private HeaderColor editedColor;
    private String editedDescription;

    private final Map<String, JComponent> specificControls = new LinkedHashMap<>();
    private JTable propTable;

    private static final String[] STM32_PIN_IDS = {
            "PA0", "PA1", "PA2", "PA3", "PA4", "PA5", "PA6", "PA7",
            "PB0", "PB1", "PB2", "PB3", "PB4", "PB5", "PB6", "PB7",
            "PC0", "PC1", "PC2", "PC3", "PC4", "PC5", "PC6", "PC7",
            "PD0", "PD1", "PD2", "PD3", "PD4", "PD5", "PD6", "PD7"
    };

    public ModulePropertiesDialog(Frame owner, Page page, BlockInstance block) {
        super(owner, "Module properties", true);
        this.page = page;
        this.block = block;

        this.editedName = block.getName();
        this.editedThread = PlcThread.fromId(block.getThreadId());
        this.editedOrder = block.getOrder();
        this.editedColor = block.getHeaderColor();
        this.editedDescription = block.getDescription();

        buildUi();
        pack();
        setMinimumSize(new Dimension(560, 360));
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
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(3, 4, 3, 4);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.anchor = GridBagConstraints.NORTHWEST;

        BlockDefinition def = BlockCatalog.getById(block.getDefinitionId());
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

        String defId = block.getDefinitionId();
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

        block.setName(trimmedName);
        block.setThreadId(editedThread.getId());
        block.setOrder(editedOrder);
        block.setHeaderColor(editedColor);
        block.setDescription(editedDescription);

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
                Object sel = cb.getSelectedItem();
                if (sel != null) {
                    block.getCustomProperties().put(key, sel.toString());
                }
            } else if (comp instanceof JTextField tf) {
                block.getCustomProperties().put(key, tf.getText().trim());
            }
        }

        page.updateWiresForBlock(block);
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
}
