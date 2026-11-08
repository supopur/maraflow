package cz.maramat.ui.dialogs;

import cz.maramat.model.BlockInstance;
import cz.maramat.model.DataType;
import cz.maramat.model.Page;
import cz.maramat.model.PinInstance;
import cz.maramat.model.Project;
import cz.maramat.ui.AppFonts;
import cz.maramat.ui.icons.VectorIcons;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Searchable Tag / Operand Picker for Ladder Contacts, Coils, and Function Block Pins.
 * Lists Hardware DI/DO/AI/AO/PWM, Virtual Memory Bits (M0..M63), Virtual Registers (REG_0..REG_31),
 * Project Symbolic Tags, and Block Output Pin references (BlockName.PinName), plus direct constant entry.
 */
public class OperandPickerDialog extends JDialog {

    private record OperandEntry(String operand, String category, DataType dataType, String description) {}

    private final Project project;
    private final List<OperandEntry> allEntries = new ArrayList<>();
    private final List<OperandEntry> filteredEntries = new ArrayList<>();

    private JTextField valueField;
    private JTextField commentField;
    private JTextField searchField;
    private JComboBox<String> categoryBox;
    private JTable operandTable;
    private OperandTableModel tableModel;

    private boolean confirmed = false;
    private String selectedOperand;
    private String selectedComment;

    public OperandPickerDialog(Frame owner, Project project, String title, String currentOperand, String currentComment, boolean showCommentField) {
        super(owner, title, true);
        this.project = project;
        this.selectedOperand = currentOperand == null ? "" : currentOperand;
        this.selectedComment = currentComment == null ? "" : currentComment;

        populateOperandCatalog();
        buildUi(showCommentField);
        filterEntries();

        pack();
        setMinimumSize(new Dimension(620, 480));
        setLocationRelativeTo(owner);
    }

    private void populateOperandCatalog() {
        allEntries.clear();
        String platform = project != null ? project.getTargetPlatform() : "STM32F407-Maraflow-Std";
        int diCount = platform.contains("IO64") ? 32 : (platform.contains("Mini") ? 8 : 16);
        int doCount = platform.contains("IO64") ? 32 : (platform.contains("Mini") ? 8 : 16);
        int aiCount = platform.contains("Mini") ? 4 : 8;
        int aoCount = platform.contains("Mini") ? 2 : 4;

        // 1. Project Symbolic Tags
        if (project != null) {
            for (Map.Entry<String, DataType> e : project.getSymbolicTags().entrySet()) {
                String desc = project.getSymbolicTagDescriptions().getOrDefault(e.getKey(), "Symbolic project tag");
                allEntries.add(new OperandEntry(e.getKey(), "Symbolic Tags", e.getValue(), desc));
            }
        }

        // 2. Hardware Digital Inputs
        for (int i = 0; i < diCount; i++) {
            allEntries.add(new OperandEntry("DI" + i, "Hardware DI", DataType.BOOL, "Hardware Digital Input #" + i + " (" + platform + ")"));
        }

        // 3. Hardware Digital Outputs
        for (int i = 0; i < doCount; i++) {
            allEntries.add(new OperandEntry("DO" + i, "Hardware DO", DataType.BOOL, "Hardware Digital Output #" + i + " (" + platform + ")"));
        }

        // 4. Memory Bits / Internal Relays (M0..M31)
        for (int i = 0; i < 32; i++) {
            allEntries.add(new OperandEntry("M" + i, "Memory Bits (M)", DataType.BOOL, "Internal Boolean Relay / Marker M" + i));
        }

        // 5. Hardware Analog Inputs / Outputs / PWM
        for (int i = 0; i < aiCount; i++) {
            allEntries.add(new OperandEntry("AI" + i, "Hardware AI/AO", DataType.INT, "12-bit ADC Analog Input #" + i + " (0..4095)"));
        }
        for (int i = 0; i < aoCount; i++) {
            allEntries.add(new OperandEntry("AO" + i, "Hardware AI/AO", DataType.INT, "12-bit DAC Analog Output #" + i + " (0..4095)"));
            allEntries.add(new OperandEntry("PWM" + i, "Hardware AI/AO", DataType.INT, "Timer PWM Duty Output #" + i + " (0..1000)"));
        }

        // 6. Virtual Numeric Registers (shared with ReadReg / WriteReg)
        for (int i = 0; i < 16; i++) {
            allEntries.add(new OperandEntry("REG_" + i, "Virtual Registers", DataType.FLOAT, "Virtual Memory Register REG_" + i));
        }

        // 7. Block Output Pin References across Project
        if (project != null) {
            for (Page page : project.getPages()) {
                for (BlockInstance b : page.getAllBlockInstances()) {
                    for (PinInstance out : b.getOutputs()) {
                        String ref = b.getName() + "." + out.getShortName();
                        allEntries.add(new OperandEntry(
                                ref,
                                "Block Pins",
                                out.getDataType(),
                                page.getName() + " -> " + b.getTypeName() + " (" + out.getFullName() + ")"
                        ));
                    }
                }
            }
        }
    }

    private void buildUi(boolean showCommentField) {
        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        // Top: Selected Operand / Constant input + Filter controls
        JPanel topPanel = new JPanel(new GridBagLayout());
        topPanel.setBorder(BorderFactory.createTitledBorder("Operand / Constant Assignment"));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(3, 5, 3, 5);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.weightx = 0.0;
        topPanel.add(new JLabel("Operand / Value:"), gbc);

        valueField = new JTextField(selectedOperand, 22);
        valueField.setFont(AppFonts.ui(Font.BOLD, 12));
        gbc.gridx = 1;
        gbc.weightx = 1.0;
        topPanel.add(valueField, gbc);

        if (showCommentField) {
            gbc.gridx = 0;
            gbc.gridy = 1;
            gbc.weightx = 0.0;
            topPanel.add(new JLabel("Element Comment:"), gbc);

            commentField = new JTextField(selectedComment, 22);
            gbc.gridx = 1;
            gbc.weightx = 1.0;
            topPanel.add(commentField, gbc);
        }

        gbc.gridx = 0;
        gbc.gridy = 2;
        gbc.weightx = 0.0;
        topPanel.add(new JLabel("Search Filter:"), gbc);

        JPanel filterRow = new JPanel(new BorderLayout(6, 0));
        searchField = new JTextField();
        searchField.setToolTipText("Filter by operand name, category, or description");
        categoryBox = new JComboBox<>(new String[]{
                "All Categories",
                "Symbolic Tags",
                "Hardware DI",
                "Hardware DO",
                "Memory Bits (M)",
                "Hardware AI/AO",
                "Virtual Registers",
                "Block Pins"
        });
        filterRow.add(searchField, BorderLayout.CENTER);
        filterRow.add(categoryBox, BorderLayout.EAST);

        gbc.gridx = 1;
        gbc.weightx = 1.0;
        topPanel.add(filterRow, gbc);

        DocumentListener dl = new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { filterEntries(); }
            @Override public void removeUpdate(DocumentEvent e) { filterEntries(); }
            @Override public void changedUpdate(DocumentEvent e) { filterEntries(); }
        };
        searchField.getDocument().addDocumentListener(dl);
        categoryBox.addActionListener(e -> filterEntries());

        root.add(topPanel, BorderLayout.NORTH);

        // Center: Operand Table
        tableModel = new OperandTableModel();
        operandTable = new JTable(tableModel);
        operandTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        operandTable.setRowHeight(AppFonts.scaled(20));
        operandTable.getColumnModel().getColumn(0).setPreferredWidth(120);
        operandTable.getColumnModel().getColumn(1).setPreferredWidth(115);
        operandTable.getColumnModel().getColumn(2).setPreferredWidth(65);
        operandTable.getColumnModel().getColumn(3).setPreferredWidth(260);

        operandTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                int r = operandTable.getSelectedRow();
                if (r >= 0 && r < filteredEntries.size()) {
                    valueField.setText(filteredEntries.get(r).operand());
                }
            }
        });

        operandTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    int r = operandTable.rowAtPoint(e.getPoint());
                    if (r >= 0 && r < filteredEntries.size()) {
                        valueField.setText(filteredEntries.get(r).operand());
                        onConfirm();
                    }
                }
            }
        });

        root.add(new JScrollPane(operandTable), BorderLayout.CENTER);

        // South: Quick New Symbolic Tag + OK/Cancel buttons
        JPanel southPanel = new JPanel(new BorderLayout(4, 6));

        JPanel addTagBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        addTagBar.setBorder(BorderFactory.createTitledBorder("Define New Project Symbolic Tag"));
        JTextField newTagNameField = new JTextField(10);
        JComboBox<DataType> newTagTypeBox = new JComboBox<>(DataType.values());
        JTextField newTagDescField = new JTextField(14);
        JButton addTagBtn = new JButton("Add Tag");
        addTagBtn.addActionListener(e -> {
            String tn = newTagNameField.getText().trim();
            if (tn.isEmpty()) {
                JOptionPane.showMessageDialog(this, "Enter a symbolic tag name.", "Add Tag", JOptionPane.WARNING_MESSAGE);
                return;
            }
            DataType dt = (DataType) newTagTypeBox.getSelectedItem();
            String desc = newTagDescField.getText().trim();
            if (project != null) {
                project.putSymbolicTag(tn, dt, desc);
                project.setDirty(true);
            }
            populateOperandCatalog();
            filterEntries();
            valueField.setText(tn);
            newTagNameField.setText("");
            newTagDescField.setText("");
        });
        addTagBar.add(new JLabel("Name:"));
        addTagBar.add(newTagNameField);
        addTagBar.add(new JLabel("Type:"));
        addTagBar.add(newTagTypeBox);
        addTagBar.add(new JLabel("Desc:"));
        addTagBar.add(newTagDescField);
        addTagBar.add(addTagBtn);
        southPanel.add(addTagBar, BorderLayout.NORTH);

        JPanel btnBar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 2));
        JButton okBtn = new JButton("OK", VectorIcons.greenCheckIcon());
        okBtn.setPreferredSize(new Dimension(95, 28));
        okBtn.addActionListener(e -> onConfirm());

        JButton cancelBtn = new JButton("Cancel", VectorIcons.redCrossIcon());
        cancelBtn.setPreferredSize(new Dimension(95, 28));
        cancelBtn.addActionListener(e -> dispose());

        btnBar.add(okBtn);
        btnBar.add(cancelBtn);
        southPanel.add(btnBar, BorderLayout.SOUTH);

        root.add(southPanel, BorderLayout.SOUTH);
        getRootPane().setDefaultButton(okBtn);
        setContentPane(root);
    }

    private void filterEntries() {
        String q = searchField.getText().trim().toLowerCase(Locale.ROOT);
        String cat = (String) categoryBox.getSelectedItem();
        boolean allCats = (cat == null || "All Categories".equals(cat));

        filteredEntries.clear();
        for (OperandEntry entry : allEntries) {
            if (!allCats && !entry.category().equals(cat)) {
                continue;
            }
            if (!q.isEmpty()) {
                boolean match = entry.operand().toLowerCase(Locale.ROOT).contains(q)
                        || entry.category().toLowerCase(Locale.ROOT).contains(q)
                        || entry.description().toLowerCase(Locale.ROOT).contains(q);
                if (!match) continue;
            }
            filteredEntries.add(entry);
        }
        tableModel.fireTableDataChanged();
    }

    private void onConfirm() {
        String val = valueField.getText().trim();
        if (val.isEmpty()) {
            val = "DI0";
        }
        selectedOperand = val;
        if (commentField != null) {
            selectedComment = commentField.getText().trim();
        }
        confirmed = true;
        dispose();
    }

    public boolean isConfirmed() {
        return confirmed;
    }

    public String getSelectedOperand() {
        return selectedOperand;
    }

    public String getSelectedComment() {
        return selectedComment;
    }

    private class OperandTableModel extends AbstractTableModel {
        private final String[] cols = {"Operand", "Category", "Data Type", "Description"};

        @Override
        public int getRowCount() {
            return filteredEntries.size();
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
        public Object getValueAt(int rowIndex, int columnIndex) {
            OperandEntry e = filteredEntries.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> e.operand();
                case 1 -> e.category();
                case 2 -> e.dataType().name();
                case 3 -> e.description();
                default -> "";
            };
        }
    }
}
