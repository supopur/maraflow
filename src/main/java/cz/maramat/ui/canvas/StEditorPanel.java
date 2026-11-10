package cz.maramat.ui.canvas;

import cz.maramat.model.BlockCatalog;
import cz.maramat.model.DataType;
import cz.maramat.model.Page;
import cz.maramat.model.Project;
import cz.maramat.model.StVariable;
import cz.maramat.sim.StDeclarationSync;
import cz.maramat.sim.StInterpreter;
import cz.maramat.ui.AppFonts;

import javax.swing.BorderFactory;
import javax.swing.DefaultCellEditor;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextPane;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.text.BadLocationException;
import javax.swing.text.Style;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Split-pane IEC 61131-3 Structured Text (ST) Editor:
 * - Top pane: Variable / Macro Pin Declaration Table (VAR_INPUT, VAR_OUTPUT, VAR, VAR_TEMP)
 * - Bottom pane: Monospaced ST Code Editor with line numbers, syntax highlighting, and live syntax diagnostics.
 */
public class StEditorPanel extends JPanel {

    private static final Set<String> ST_KEYWORDS = Set.of(
            "IF", "THEN", "ELSIF", "ELSEIF", "ELSE", "END_IF", "ENDIF",
            "CASE", "OF", "END_CASE", "ENDCASE",
            "FOR", "TO", "BY", "DO", "END_FOR", "ENDFOR",
            "WHILE", "END_WHILE", "ENDWHILE",
            "REPEAT", "UNTIL", "END_REPEAT", "ENDREPEAT",
            "EXIT", "RETURN", "AND", "OR", "XOR", "NOT", "MOD", "TRUE", "FALSE",
            "VAR", "VAR_INPUT", "VAR_OUTPUT", "VAR_TEMP", "END_VAR",
            "BOOL", "INT", "LONG", "FLOAT", "REAL", "DINT", "LINT"
    );

    private static final Set<String> ST_FUNCTIONS = Set.of(
            "ABS", "SQRT", "SIN", "COS", "TAN", "TRUNC", "ROUND",
            "INT_TO_FLOAT", "INT_TO_REAL", "FLOAT_TO_INT", "REAL_TO_INT",
            "BOOL_TO_INT", "INT_TO_BOOL", "MIN", "MAX", "LIMIT", "CLAMP", "SEL"
    );

    private static final Pattern TOKEN_PATTERN = Pattern.compile(
            "(//[^\\n]*)|(\\(\\*[\\s\\S]*?\\*\\))|(/\\*[\\s\\S]*?\\*/)|(\\b(?:16#[0-9A-Fa-f_]+|2#[01_]+|\\d+(?:\\.\\d+)?)\\b)|(\\b[A-Za-z_][A-Za-z0-9_]*\\b)"
    );

    private Project project;
    private Page page;
    private final Runnable onStateChanged;

    private final VarTableModel varTableModel = new VarTableModel();
    private final JTable varTable = new JTable(varTableModel);
    private final JComboBox<String> threadCombo = new JComboBox<>(new String[]{
            "T:1 (1000 ms)", "T:2 (100 ms)", "T:3 (10 ms)", "T:4 (1 ms Fast)", "T:5 (Idle Task)"
    });
    private final JSpinner orderSpinner = new JSpinner(new SpinnerNumberModel(10, 0, 9999, 10));
    private final JLabel pageModeBadge = new JLabel(" [ST Page] ");

    private final JTextPane codePane = new JTextPane();
    private final LineNumberGutter gutter = new LineNumberGutter(codePane);
    private final JLabel statusLabel = new JLabel("ST Syntax OK");
    private final JSplitPane splitPane;

    private boolean suppressEvents = false;
    private boolean highlighting = false;
    private final Timer highlightDebounce;

    private final Style styleDefault;
    private final Style styleKeyword;
    private final Style styleFunction;
    private final Style styleComment;
    private final Style styleNumber;
    private final Style styleHardwareIo;

    public StEditorPanel(Project project, Page page, Runnable onStateChanged) {
        super(new BorderLayout());
        this.project = project;
        this.page = page;
        this.onStateChanged = onStateChanged;
        setBackground(new Color(0xEC, 0xEC, 0xEC));

        // Configure Styles
        StyledDocument doc = codePane.getStyledDocument();
        styleDefault = codePane.addStyle("default", null);
        StyleConstants.setForeground(styleDefault, new Color(0x14, 0x18, 0x1F));

        styleKeyword = codePane.addStyle("keyword", null);
        StyleConstants.setForeground(styleKeyword, new Color(0x00, 0x33, 0x99));
        StyleConstants.setBold(styleKeyword, true);

        styleFunction = codePane.addStyle("function", null);
        StyleConstants.setForeground(styleFunction, new Color(0x00, 0x66, 0x77));
        StyleConstants.setBold(styleFunction, true);

        styleComment = codePane.addStyle("comment", null);
        StyleConstants.setForeground(styleComment, new Color(0x2E, 0x7D, 0x32));
        StyleConstants.setItalic(styleComment, true);

        styleNumber = codePane.addStyle("number", null);
        StyleConstants.setForeground(styleNumber, new Color(0x7B, 0x1F, 0xA2));

        styleHardwareIo = codePane.addStyle("hwio", null);
        StyleConstants.setForeground(styleHardwareIo, new Color(0x9A, 0x34, 0x12));
        StyleConstants.setBold(styleHardwareIo, true);

        // 1. Top Panel: Variable Declaration Table
        JPanel topPanel = buildVariableDeclarationPanel();

        // 2. Bottom Panel: Monospaced ST Code Editor
        JPanel bottomPanel = buildCodeEditorPanel();

        splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT, topPanel, bottomPanel);
        splitPane.setDividerSize(6);
        splitPane.setDividerLocation(AppFonts.scaled(190));
        splitPane.setResizeWeight(0.28);
        splitPane.setBorder(null);
        add(splitPane, BorderLayout.CENTER);

        highlightDebounce = new Timer(120, e -> {
            applySyntaxHighlighting();
            validateStCode();
        });
        highlightDebounce.setRepeats(false);

        doc.addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                onCodeEdited();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                onCodeEdited();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {}
        });

        setPage(project, page);
    }

    private JPanel buildVariableDeclarationPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 2));
        panel.setBackground(new Color(0xEC, 0xEC, 0xEC));
        panel.setBorder(BorderFactory.createEmptyBorder(3, 4, 2, 4));

        // Toolbar above Variable Table
        JPanel bar = new JPanel(new BorderLayout(6, 0));
        bar.setBackground(new Color(0xE4, 0xE6, 0xEA));
        bar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createEtchedBorder(),
                BorderFactory.createEmptyBorder(2, 6, 2, 6)
        ));

        JPanel leftControls = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        leftControls.setOpaque(false);

        pageModeBadge.setFont(AppFonts.uiBold());
        pageModeBadge.setOpaque(true);
        pageModeBadge.setBackground(new Color(0xDC, 0xE6, 0xF4));
        pageModeBadge.setForeground(new Color(0x10, 0x30, 0x60));
        pageModeBadge.setBorder(BorderFactory.createLineBorder(new Color(0x70, 0x90, 0xB8)));
        leftControls.add(pageModeBadge);

        JButton addVarBtn = createBarButton("+ Variable", "Add local variable or Macro input/output pin");
        addVarBtn.addActionListener(e -> addVariableRow());
        leftControls.add(addVarBtn);

        JButton delVarBtn = createBarButton("- Variable", "Remove selected variable declaration");
        delVarBtn.addActionListener(e -> removeSelectedVariableRow());
        leftControls.add(delVarBtn);

        JButton upBtn = createBarButton("Up", "Move variable up");
        upBtn.addActionListener(e -> moveSelectedVariable(-1));
        leftControls.add(upBtn);

        JButton downBtn = createBarButton("Down", "Move variable down");
        downBtn.addActionListener(e -> moveSelectedVariable(1));
        leftControls.add(downBtn);

        bar.add(leftControls, BorderLayout.WEST);

        JPanel rightControls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        rightControls.setOpaque(false);
        JLabel thLbl = new JLabel("Thread:");
        thLbl.setFont(AppFonts.uiPlain());
        rightControls.add(thLbl);

        threadCombo.setFont(AppFonts.uiPlain());
        threadCombo.addActionListener(e -> {
            if (!suppressEvents && page != null) {
                page.setStThreadId(threadCombo.getSelectedIndex() + 1);
                if (project != null) project.setDirty(true);
                if (onStateChanged != null) onStateChanged.run();
            }
        });
        rightControls.add(threadCombo);

        JLabel ordLbl = new JLabel("Order:");
        ordLbl.setFont(AppFonts.uiPlain());
        rightControls.add(ordLbl);

        orderSpinner.setFont(AppFonts.uiPlain());
        orderSpinner.setPreferredSize(new Dimension(AppFonts.scaled(68), AppFonts.scaled(22)));
        orderSpinner.addChangeListener(e -> {
            if (!suppressEvents && page != null) {
                page.setStOrder(((Number) orderSpinner.getValue()).intValue());
                if (project != null) project.setDirty(true);
                if (onStateChanged != null) onStateChanged.run();
            }
        });
        rightControls.add(orderSpinner);

        bar.add(rightControls, BorderLayout.EAST);
        panel.add(bar, BorderLayout.NORTH);

        // Configure JTable
        varTable.setFont(AppFonts.uiPlain());
        varTable.getTableHeader().setFont(AppFonts.uiBold());
        varTable.setRowHeight(AppFonts.scaled(21));
        varTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        varTable.setGridColor(new Color(0xC8, 0xCC, 0xD2));
        varTable.setShowGrid(true);

        JComboBox<StVariable.Scope> scopeEditor = new JComboBox<>(StVariable.Scope.values());
        scopeEditor.setFont(AppFonts.uiPlain());
        varTable.getColumnModel().getColumn(0).setCellEditor(new DefaultCellEditor(scopeEditor));

        JComboBox<DataType> typeEditor = new JComboBox<>(DataType.values());
        typeEditor.setFont(AppFonts.uiPlain());
        varTable.getColumnModel().getColumn(2).setCellEditor(new DefaultCellEditor(typeEditor));

        varTable.getColumnModel().getColumn(0).setPreferredWidth(AppFonts.scaled(115));
        varTable.getColumnModel().getColumn(1).setPreferredWidth(AppFonts.scaled(140));
        varTable.getColumnModel().getColumn(2).setPreferredWidth(AppFonts.scaled(85));
        varTable.getColumnModel().getColumn(3).setPreferredWidth(AppFonts.scaled(95));
        varTable.getColumnModel().getColumn(4).setPreferredWidth(AppFonts.scaled(110));
        varTable.getColumnModel().getColumn(5).setPreferredWidth(AppFonts.scaled(280));

        // Custom cell renderer for Runtime Value column
        varTable.getColumnModel().getColumn(4).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                           boolean hasFocus, int row, int column) {
                Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                c.setFont(AppFonts.canvasMonoBold(11f));
                if (!isSelected) {
                    c.setBackground(new Color(0xF0, 0xF6, 0xEC));
                    c.setForeground(new Color(0x0B, 0x5E, 0x20));
                }
                return c;
            }
        });

        JScrollPane tableScroll = new JScrollPane(varTable);
        tableScroll.setBorder(BorderFactory.createLoweredBevelBorder());
        panel.add(tableScroll, BorderLayout.CENTER);

        return panel;
    }

    private JPanel buildCodeEditorPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 2));
        panel.setBackground(new Color(0xEC, 0xEC, 0xEC));
        panel.setBorder(BorderFactory.createEmptyBorder(2, 4, 4, 4));

        // Code Editor Snippet & Validation Bar
        JPanel bar = new JPanel(new BorderLayout(6, 0));
        bar.setBackground(new Color(0xE4, 0xE6, 0xEA));
        bar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createEtchedBorder(),
                BorderFactory.createEmptyBorder(2, 6, 2, 6)
        ));

        JPanel snippetPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        snippetPanel.setOpaque(false);

        JLabel stTitle = new JLabel("Structured Text (IEC 61131-3):");
        stTitle.setFont(AppFonts.uiBold());
        snippetPanel.add(stTitle);

        JButton ifBtn = createBarButton("+ IF..THEN", "Insert IF / ELSE / END_IF statement");
        ifBtn.addActionListener(e -> insertSnippet("IF DI0 THEN\n    DO0 := TRUE;\nELSE\n    DO0 := FALSE;\nEND_IF;\n"));
        snippetPanel.add(ifBtn);

        JButton caseBtn = createBarButton("+ CASE", "Insert CASE / OF / END_CASE statement");
        caseBtn.addActionListener(e -> insertSnippet("CASE State OF\n    0:\n        DO0 := FALSE;\n    1:\n        DO0 := TRUE;\n    ELSE\n        DO0 := FALSE;\nEND_CASE;\n"));
        snippetPanel.add(caseBtn);

        JButton forBtn = createBarButton("+ FOR", "Insert bounded FOR / TO / DO / END_FOR loop");
        forBtn.addActionListener(e -> insertSnippet("FOR i := 0 TO 7 DO\n    REG_0 := REG_0 + 1;\nEND_FOR;\n"));
        snippetPanel.add(forBtn);

        JButton checkBtn = createBarButton("Check Syntax", "Validate IEC 61131-3 ST syntax now");
        checkBtn.addActionListener(e -> validateStCode());
        snippetPanel.add(checkBtn);

        bar.add(snippetPanel, BorderLayout.WEST);

        JLabel ioHint = new JLabel("Direct I/O & Regs: DI0..15, DO0..15, AI0..7, AO0..3, PWM0..3, M0..15, REG_0..15");
        ioHint.setFont(AppFonts.uiSmall());
        ioHint.setForeground(new Color(0x50, 0x55, 0x60));
        bar.add(ioHint, BorderLayout.EAST);

        panel.add(bar, BorderLayout.NORTH);

        codePane.setFont(AppFonts.canvasMono(13f));
        codePane.setBackground(Color.WHITE);
        codePane.setCaretColor(Color.BLACK);

        JScrollPane codeScroll = new JScrollPane(codePane);
        codeScroll.setRowHeaderView(gutter);
        codeScroll.setBorder(BorderFactory.createLoweredBevelBorder());
        panel.add(codeScroll, BorderLayout.CENTER);

        // Status / Diagnostic Footer
        JPanel footer = new JPanel(new BorderLayout(8, 0));
        footer.setBackground(new Color(0xE8, 0xEA, 0xEE));
        footer.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLoweredBevelBorder(),
                BorderFactory.createEmptyBorder(2, 6, 2, 6)
        ));
        statusLabel.setFont(AppFonts.uiBold());
        statusLabel.setForeground(new Color(0x1B, 0x5E, 0x20));
        footer.add(statusLabel, BorderLayout.CENTER);
        panel.add(footer, BorderLayout.SOUTH);

        return panel;
    }

    private JButton createBarButton(String text, String tooltip) {
        JButton b = new JButton(text);
        b.setFont(AppFonts.uiPlain());
        b.setToolTipText(tooltip);
        b.setFocusPainted(false);
        b.setMargin(new java.awt.Insets(1, 6, 1, 6));
        return b;
    }

    public void setPage(Project project, Page page) {
        this.project = project;
        this.page = page;
        suppressEvents = true;
        try {
            if (page != null) {
                threadCombo.setSelectedIndex(Math.clamp(page.getStThreadId() - 1, 0, 4));
                orderSpinner.setValue(page.getStOrder());
                updatePageBadge();

                // If code is empty or has no declarations while variables exist, sync them to code
                if (!page.getStVariables().isEmpty() && StDeclarationSync.parseDeclarationsFromCode(page.getStCode()) == null) {
                    page.setStCode(StDeclarationSync.replaceDeclarationsInCode(page.getStCode(), page.getStVariables()));
                }

                String currentText = codePane.getText();
                if (!currentText.equals(page.getStCode())) {
                    codePane.setText(page.getStCode());
                    codePane.setCaretPosition(0);
                }
                varTableModel.fireTableDataChanged();
                applySyntaxHighlighting();
                validateStCode();
            }
        } finally {
            suppressEvents = false;
        }
    }

    private void updatePageBadge() {
        if (page == null) return;
        if (page.isEStopPage()) {
            pageModeBadge.setText(" [!E-STOP SAFETY ST HANDLER] ");
            pageModeBadge.setBackground(new Color(0xF8, 0xD7, 0xDA));
            pageModeBadge.setForeground(new Color(0x84, 0x10, 0x18));
        } else if (page.isMacro()) {
            pageModeBadge.setText(" [*ST MACRO FUNCTION BLOCK: " + page.getCleanName() + "] ");
            pageModeBadge.setBackground(new Color(0xE8, 0xDA, 0xF8));
            pageModeBadge.setForeground(new Color(0x4A, 0x14, 0x8C));
        } else {
            pageModeBadge.setText(" [ST PAGE: " + page.getName() + "] ");
            pageModeBadge.setBackground(new Color(0xDC, 0xE6, 0xF4));
            pageModeBadge.setForeground(new Color(0x10, 0x30, 0x60));
        }
    }

    public void refreshRuntimeValues() {
        if (page == null || page.getStVariables().isEmpty()) return;
        if (!varTable.isEditing()) {
            varTableModel.fireTableRowsUpdated(0, page.getStVariables().size() - 1);
        }
    }

    private void onCodeEdited() {
        if (suppressEvents || highlighting || page == null) return;
        String newCode = codePane.getText();
        page.setStCode(newCode);
        if (project != null) project.setDirty(true);
        gutter.repaint();

        // Bi-directional synchronization: check if code defines VAR..END_VAR blocks
        List<StVariable> parsedVars = StDeclarationSync.parseDeclarationsFromCode(newCode);
        if (parsedVars != null && !isVariablesListIdentical(page.getStVariables(), parsedVars)) {
            // Keep existing runtime values where variable names match
            Map<String, Double> prevVals = new HashMap<>();
            for (StVariable v : page.getStVariables()) {
                prevVals.put(v.getName().toLowerCase(), v.getRuntimeValue());
            }
            for (StVariable nv : parsedVars) {
                Double prev = prevVals.get(nv.getName().toLowerCase());
                if (prev != null) nv.setRuntimeValue(prev);
            }
            page.getStVariables().clear();
            page.getStVariables().addAll(parsedVars);
            varTableModel.fireTableDataChanged();
            syncMacroCatalogIfNeeded();
        }

        highlightDebounce.restart();
    }

    private boolean isVariablesListIdentical(List<StVariable> listA, List<StVariable> listB) {
        if (listA.size() != listB.size()) return false;
        for (int i = 0; i < listA.size(); i++) {
            StVariable a = listA.get(i);
            StVariable b = listB.get(i);
            if (a.getScope() != b.getScope()
                    || !a.getName().equalsIgnoreCase(b.getName())
                    || a.getDataType() != b.getDataType()
                    || !a.getInitialValue().equals(b.getInitialValue())
                    || !a.getDescription().equals(b.getDescription())) {
                return false;
            }
        }
        return true;
    }

    private void syncTableToCode() {
        if (page == null) return;
        String updated = StDeclarationSync.replaceDeclarationsInCode(codePane.getText(), page.getStVariables());
        page.setStCode(updated);
        suppressEvents = true;
        try {
            int caret = Math.min(codePane.getCaretPosition(), updated.length());
            codePane.setText(updated);
            codePane.setCaretPosition(caret);
        } finally {
            suppressEvents = false;
        }
        gutter.repaint();
        applySyntaxHighlighting();
        validateStCode();
    }

    private void insertSnippet(String snippet) {
        try {
            int pos = codePane.getCaretPosition();
            codePane.getDocument().insertString(pos, snippet, styleDefault);
            codePane.requestFocusInWindow();
        } catch (BadLocationException ignored) {}
    }

    private void addVariableRow() {
        if (page == null) return;
        int idx = page.getStVariables().size() + 1;
        StVariable.Scope defaultScope = page.isMacro() ? StVariable.Scope.VAR_INPUT : StVariable.Scope.VAR;
        page.getStVariables().add(new StVariable(defaultScope, "Var" + idx, DataType.INT, "0", ""));
        varTableModel.fireTableDataChanged();
        syncTableToCode();
        syncMacroCatalogIfNeeded();
    }

    private void removeSelectedVariableRow() {
        if (page == null) return;
        int row = varTable.getSelectedRow();
        if (row >= 0 && row < page.getStVariables().size()) {
            page.getStVariables().remove(row);
            varTableModel.fireTableDataChanged();
            syncTableToCode();
            syncMacroCatalogIfNeeded();
        }
    }

    private void moveSelectedVariable(int delta) {
        if (page == null) return;
        int row = varTable.getSelectedRow();
        int target = row + delta;
        if (row >= 0 && target >= 0 && target < page.getStVariables().size()) {
            StVariable v = page.getStVariables().remove(row);
            page.getStVariables().add(target, v);
            varTableModel.fireTableDataChanged();
            varTable.setRowSelectionInterval(target, target);
            syncTableToCode();
            syncMacroCatalogIfNeeded();
        }
    }

    private void syncMacroCatalogIfNeeded() {
        if (project != null) {
            project.setDirty(true);
            BlockCatalog.syncMacrosFromProject(project);
        }
        if (onStateChanged != null) {
            onStateChanged.run();
        }
    }

    private void applySyntaxHighlighting() {
        if (highlighting) return;
        SwingUtilities.invokeLater(() -> {
            if (highlighting) return;
            highlighting = true;
            try {
                StyledDocument doc = codePane.getStyledDocument();
                String text = doc.getText(0, doc.getLength());
                doc.setCharacterAttributes(0, text.length(), styleDefault, true);

                Matcher m = TOKEN_PATTERN.matcher(text);
                while (m.find()) {
                    int start = m.start();
                    int len = m.end() - start;
                    if (m.group(1) != null || m.group(2) != null || m.group(3) != null) {
                        doc.setCharacterAttributes(start, len, styleComment, true);
                    } else if (m.group(4) != null) {
                        doc.setCharacterAttributes(start, len, styleNumber, true);
                    } else if (m.group(5) != null) {
                        String word = m.group(5).toUpperCase();
                        if (ST_KEYWORDS.contains(word)) {
                            doc.setCharacterAttributes(start, len, styleKeyword, true);
                        } else if (ST_FUNCTIONS.contains(word)) {
                            doc.setCharacterAttributes(start, len, styleFunction, true);
                        } else if (word.matches("(?:DI|DO|AI|AO|PWM|M)\\d+|REG_\\d+")) {
                            doc.setCharacterAttributes(start, len, styleHardwareIo, true);
                        }
                    }
                }
            } catch (BadLocationException ignored) {
            } finally {
                highlighting = false;
            }
        });
    }

    private void validateStCode() {
        if (page == null) return;
        List<StInterpreter.Diagnostic> diags = StInterpreter.validate(page.getStCode(), page.getStVariables());
        if (diags.isEmpty()) {
            statusLabel.setText("IEC 61131-3 ST Syntax OK  |  " + page.getStVariables().size() + " variable(s) declared");
            statusLabel.setForeground(new Color(0x1B, 0x5E, 0x20));
        } else {
            StInterpreter.Diagnostic first = diags.getFirst();
            statusLabel.setText("Syntax Error (" + first + ")");
            statusLabel.setForeground(new Color(0xB7, 0x1C, 0x1C));
        }
    }

    private final class VarTableModel extends AbstractTableModel {
        private final String[] cols = {"Scope", "Name", "Type", "Initial Value", "Runtime Value", "Description"};

        @Override
        public int getRowCount() {
            return page == null ? 0 : page.getStVariables().size();
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
            if (page == null || rowIndex < 0 || rowIndex >= page.getStVariables().size()) return "";
            StVariable v = page.getStVariables().get(rowIndex);
            return switch (columnIndex) {
                case 0 -> v.getScope();
                case 1 -> v.getName();
                case 2 -> v.getDataType();
                case 3 -> v.getInitialValue();
                case 4 -> v.getDataType().formatRuntimeValue(v.getRuntimeValue(), false);
                case 5 -> v.getDescription();
                default -> "";
            };
        }

        @Override
        public void setValueAt(Object aValue, int rowIndex, int columnIndex) {
            if (page == null || rowIndex < 0 || rowIndex >= page.getStVariables().size()) return;
            StVariable v = page.getStVariables().get(rowIndex);
            switch (columnIndex) {
                case 0 -> {
                    if (aValue instanceof StVariable.Scope s) v.setScope(s);
                }
                case 1 -> v.setName(String.valueOf(aValue));
                case 2 -> {
                    if (aValue instanceof DataType dt) v.setDataType(dt);
                }
                case 3 -> v.setInitialValue(String.valueOf(aValue));
                case 4 -> {
                    try {
                        String s = String.valueOf(aValue).trim();
                        if ("TRUE".equalsIgnoreCase(s)) v.setRuntimeValue(1.0);
                        else if ("FALSE".equalsIgnoreCase(s)) v.setRuntimeValue(0.0);
                        else v.setRuntimeValue(Double.parseDouble(s));
                    } catch (NumberFormatException ignored) {}
                }
                case 5 -> v.setDescription(String.valueOf(aValue));
            }
            fireTableRowsUpdated(rowIndex, rowIndex);
            if (columnIndex != 4) {
                syncTableToCode();
            }
            syncMacroCatalogIfNeeded();
        }
    }

    private static final class LineNumberGutter extends JPanel {
        private final JTextPane pane;

        LineNumberGutter(JTextPane pane) {
            this.pane = pane;
            setBackground(new Color(0xEC, 0xEE, 0xF2));
            setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, new Color(0xB8, 0xBE, 0xC8)));
        }

        @Override
        public Dimension getPreferredSize() {
            int lines = Math.max(1, countLines());
            int digits = Math.max(3, String.valueOf(lines).length());
            Font f = AppFonts.canvasMono(12f);
            FontMetrics fm = getFontMetrics(f);
            int w = fm.charWidth('0') * digits + AppFonts.scaled(14);
            return new Dimension(w, pane.getPreferredSize().height);
        }

        private int countLines() {
            String t = pane.getText();
            int count = 1;
            for (int i = 0; i < t.length(); i++) {
                if (t.charAt(i) == '\n') count++;
            }
            return count;
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (g instanceof java.awt.Graphics2D g2) {
                g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_OFF);
                g2.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            }
            g.setFont(AppFonts.canvasMono(12f));
            g.setColor(new Color(0x55, 0x60, 0x70));
            FontMetrics fm = g.getFontMetrics();
            int lineHeight = pane.getFontMetrics(pane.getFont()).getHeight();
            int ascent = pane.getFontMetrics(pane.getFont()).getAscent();
            int lines = countLines();
            int y = ascent + 2;
            for (int i = 1; i <= lines; i++) {
                String s = String.valueOf(i);
                int x = getWidth() - fm.stringWidth(s) - 6;
                g.drawString(s, x, y);
                y += lineHeight;
            }
        }
    }
}
