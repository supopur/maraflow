package cz.maramat.ui.canvas;

import cz.maramat.model.BlockCatalog;
import cz.maramat.model.BlockDefinition;
import cz.maramat.model.DataType;
import cz.maramat.model.Page;
import cz.maramat.model.Project;
import cz.maramat.model.StVariable;
import cz.maramat.settings.AppSettings;
import cz.maramat.sim.StDeclarationSync;
import cz.maramat.sim.StInterpreter;
import cz.maramat.sim.StLanguageServer;
import cz.maramat.ui.AppFonts;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.BorderFactory;
import javax.swing.DefaultCellEditor;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.JTextPane;
import javax.swing.JWindow;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.ToolTipManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultHighlighter;
import javax.swing.text.Highlighter;
import javax.swing.text.JTextComponent;
import javax.swing.text.Style;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import javax.swing.undo.CannotRedoException;
import javax.swing.undo.CannotUndoException;
import javax.swing.undo.UndoManager;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Shape;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.datatransfer.DataFlavor;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DropTarget;
import java.awt.dnd.DropTargetAdapter;
import java.awt.dnd.DropTargetDropEvent;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
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
            "BOOL_TO_INT", "INT_TO_BOOL", "MIN", "MAX", "LIMIT", "CLAMP", "SEL",
            "RANDOM", "RND", "SINGLEPULSE", "ONESHOT", "COUNTER", "CNTR"
    );

    private static final Pattern HW_IO_PATTERN = Pattern.compile("(?:DI|DO|AI|AO|PWM|M)\\d+|REG_\\d+");

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

    // ST Language Server State
    private final List<StLanguageServer.Diagnostic> diagnostics = new ArrayList<>();
    private final List<Object> diagnosticHighlights = new ArrayList<>();
    private final Highlighter.HighlightPainter errorPainter = new SquigglyUnderlinePainter(new Color(0xD3, 0x2F, 0x2F));
    private final Highlighter.HighlightPainter warningPainter = new SquigglyUnderlinePainter(new Color(0xF5, 0x7C, 0x00));

    private JWindow completionWindow;
    private JScrollPane completionScroll;
    private JList<StLanguageServer.CompletionItem> completionList;
    private DefaultListModel<StLanguageServer.CompletionItem> completionModel;
    private final Timer completionDebounce = new Timer(150, e -> triggerCompletion());
    private Consumer<String> macroNavigator;

    private final JTextPane codePane = new JTextPane();
    private final LineNumberGutter gutter = new LineNumberGutter(codePane);
    private final JLabel statusLabel = new JLabel("ST Syntax OK");
    private final JSplitPane splitPane;

    // Find & Replace in ST
    private JPanel findBar;
    private JPanel replaceRow;
    private final JTextField findField = new JTextField(16);
    private final JTextField replaceField = new JTextField(16);
    private final JCheckBox caseCheck = new JCheckBox("Match Case");
    private final JLabel findMatchesLabel = new JLabel("0 of 0");
    private final List<int[]> currentMatches = new ArrayList<>();
    private int currentMatchIndex = -1;
    private final Highlighter.HighlightPainter matchPainter = new DefaultHighlighter.DefaultHighlightPainter(new Color(255, 255, 140));
    private final Highlighter.HighlightPainter currentMatchPainter = new DefaultHighlighter.DefaultHighlightPainter(new Color(255, 160, 50));

    private boolean suppressEvents = false;
    private boolean highlighting = false;
    private final UndoManager stUndoManager = new UndoManager();
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

        doc.addUndoableEditListener(e -> {
            if (!suppressEvents && !highlighting) {
                stUndoManager.addEdit(e.getEdit());
            }
        });

        int menuMask = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        Action undoAction = new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                undo();
            }
        };
        Action redoAction = new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                redo();
            }
        };
        codePane.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_Z, menuMask), "stUndo");
        codePane.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_Z, KeyEvent.CTRL_DOWN_MASK), "stUndo");
        codePane.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_Z, KeyEvent.META_DOWN_MASK), "stUndo");
        codePane.getActionMap().put("stUndo", undoAction);

        codePane.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_Z, menuMask | InputEvent.SHIFT_DOWN_MASK), "stRedo");
        codePane.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_Z, KeyEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK), "stRedo");
        codePane.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_Z, KeyEvent.META_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK), "stRedo");
        codePane.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_Y, menuMask), "stRedo");
        codePane.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_Y, KeyEvent.CTRL_DOWN_MASK), "stRedo");
        codePane.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_Y, KeyEvent.META_DOWN_MASK), "stRedo");
        codePane.getActionMap().put("stRedo", redoAction);
        Action openFindAction = new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                openFindBar(false);
            }
        };
        Action openReplaceAction = new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                openFindBar(true);
            }
        };

        Action formatAction = new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                actionFormatCode();
            }
        };

        codePane.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_F, menuMask), "find");
        codePane.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_F, KeyEvent.CTRL_DOWN_MASK), "find");
        codePane.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_F, KeyEvent.META_DOWN_MASK), "find");
        codePane.getActionMap().put("find", openFindAction);

        codePane.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_H, menuMask), "replace");
        codePane.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_H, KeyEvent.CTRL_DOWN_MASK), "replace");
        codePane.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_H, KeyEvent.META_DOWN_MASK), "replace");
        codePane.getActionMap().put("replace", openReplaceAction);

        codePane.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_F, menuMask | KeyEvent.SHIFT_DOWN_MASK), "format");
        codePane.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_F, KeyEvent.ALT_DOWN_MASK | KeyEvent.SHIFT_DOWN_MASK), "format");
        codePane.getActionMap().put("format", formatAction);

        completionDebounce.setRepeats(false);
        // Close suggestions whenever the editor stops being visible (tab switch ST -> FBD/LD) or loses focus
        addHierarchyListener(ev -> {
            if ((ev.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0 && !isShowing()) {
                hideCompletionPopup();
            }
        });
        codePane.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override
            public void focusLost(java.awt.event.FocusEvent ev) {
                hideCompletionPopup();
            }
        });
        Thread warm = new Thread(StLanguageServer::warmUp, "st-completion-warmup");
        warm.setDaemon(true);
        warm.start();

        codePane.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (completionWindow != null && completionWindow.isVisible()) {
                    if (e.getKeyCode() == KeyEvent.VK_DOWN) {
                        if (completionModel != null && !completionModel.isEmpty()) {
                            if (completionList.getSelectedIndex() >= completionModel.size() - 1) {
                                loadMoreCompletions(COMPLETION_CHUNK);
                            }
                            int idx = (completionList.getSelectedIndex() + 1) % completionModel.size();
                            completionList.setSelectedIndex(idx);
                            completionList.ensureIndexIsVisible(idx);
                        }
                        e.consume();
                        return;
                    } else if (e.getKeyCode() == KeyEvent.VK_UP) {
                        if (completionModel != null && !completionModel.isEmpty()) {
                            if (completionList.getSelectedIndex() <= 0) {
                                loadMoreCompletions(Integer.MAX_VALUE); // wrap to the very end
                            }
                            int idx = (completionList.getSelectedIndex() - 1 + completionModel.size()) % completionModel.size();
                            completionList.setSelectedIndex(idx);
                            completionList.ensureIndexIsVisible(idx);
                        }
                        e.consume();
                        return;
                    } else if (e.getKeyCode() == KeyEvent.VK_ENTER || e.getKeyCode() == KeyEvent.VK_TAB) {
                        insertSelectedCompletion();
                        e.consume();
                        return;
                    } else if (e.getKeyCode() == KeyEvent.VK_ESCAPE) {
                        hideCompletionPopup();
                        e.consume();
                        return;
                    } else if (e.getKeyCode() == KeyEvent.VK_SPACE) {
                        hideCompletionPopup();
                        return; // Let space pass through to document
                    }
                }

                // Manual trigger: Ctrl+Space or Alt+Space (NOT Cmd+Space on Mac)
                if (e.getKeyCode() == KeyEvent.VK_SPACE && (e.isControlDown() || e.isAltDown())) {
                    triggerCompletion();
                    e.consume();
                }
            }

            @Override
            public void keyReleased(KeyEvent e) {
                char c = e.getKeyChar();
                if (Character.isLetterOrDigit(c) || c == '_') {
                    if (completionWindow != null && completionWindow.isVisible()) {
                        triggerCompletion();
                    } else if (AppSettings.get().isStIntelliSenseEnabled() && AppSettings.get().isStAutoPopup()) {
                        completionDebounce.setInitialDelay(AppSettings.get().getStPopupDelayMs());
                        completionDebounce.restart();
                    }
                } else if (e.getKeyCode() == KeyEvent.VK_BACK_SPACE || e.getKeyCode() == KeyEvent.VK_DELETE) {
                    if (completionWindow != null && completionWindow.isVisible()) {
                        triggerCompletion();
                    }
                } else if (c == ';' || c == '\n' || c == ')') {
                    hideCompletionPopup();
                }
            }
        });

        ToolTipManager.sharedInstance().registerComponent(codePane);
        codePane.addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                if (page == null) return;
                try {
                    int offset = codePane.viewToModel2D(e.getPoint());
                    String token = extractTokenAtOffset(codePane.getText(), offset);

                    boolean isJumpModifier = e.isControlDown() || e.isMetaDown();
                    if (isJumpModifier && isDeclarationTarget(token)) {
                        codePane.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                    } else {
                        codePane.setCursor(Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR));
                    }

                    String doc = StLanguageServer.getHoverDoc(token, page.getStVariables(), project);
                    codePane.setToolTipText(doc);
                } catch (Exception ignored) {
                    codePane.setToolTipText(null);
                    codePane.setCursor(Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR));
                }
            }
        });

        codePane.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 1 && (e.isControlDown() || e.isMetaDown())) {
                    try {
                        int offset = codePane.viewToModel2D(e.getPoint());
                        String token = extractTokenAtOffset(codePane.getText(), offset);
                        if (token != null && !token.isBlank()) {
                            jumpToDeclaration(token.trim());
                        }
                    } catch (Exception ignored) {}
                }
            }
        });

        getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(KeyEvent.VK_F, menuMask), "find");
        getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(KeyEvent.VK_F, KeyEvent.CTRL_DOWN_MASK), "find");
        getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(KeyEvent.VK_F, KeyEvent.META_DOWN_MASK), "find");
        getActionMap().put("find", openFindAction);

        setupDropTarget();
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

        JLabel stTitle = new JLabel("Structured Text:");
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

        JButton checkBtn = createBarButton("Check Syntax", "Validate ST syntax now");
        checkBtn.addActionListener(e -> validateStCode());
        snippetPanel.add(checkBtn);

        JButton formatBtn = createBarButton("Format", "Auto-format and indent ST code (Shift+Alt+F)");
        formatBtn.addActionListener(e -> actionFormatCode());
        snippetPanel.add(formatBtn);

        JButton findBtn = createBarButton("Find... (Cmd+F)", "Find & Replace in ST code (Cmd+F / Ctrl+F)");
        findBtn.addActionListener(e -> openFindBar(false));
        snippetPanel.add(findBtn);

        bar.add(snippetPanel, BorderLayout.WEST);

        panel.add(bar, BorderLayout.NORTH);

        codePane.setFont(AppFonts.canvasMono(13f));
        codePane.setBackground(Color.WHITE);
        codePane.setCaretColor(Color.BLACK);

        JScrollPane codeScroll = new JScrollPane(codePane);
        codeScroll.setRowHeaderView(gutter);
        codeScroll.setBorder(BorderFactory.createLoweredBevelBorder());

        findBar = buildFindBar();

        JPanel editorWithFind = new JPanel(new BorderLayout());
        editorWithFind.add(findBar, BorderLayout.NORTH);
        editorWithFind.add(codeScroll, BorderLayout.CENTER);
        panel.add(editorWithFind, BorderLayout.CENTER);

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

    private JPanel buildFindBar() {
        JPanel bar = new JPanel(new BorderLayout(4, 2));
        bar.setBackground(new Color(0xF3, 0xF5, 0xF8));
        bar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(0xBB, 0xC4, 0xD0)),
                BorderFactory.createEmptyBorder(3, 6, 3, 6)
        ));

        JPanel topRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 1));
        topRow.setOpaque(false);

        JLabel findLbl = new JLabel("Find:");
        findLbl.setFont(AppFonts.uiBold());
        topRow.add(findLbl);

        findField.setFont(AppFonts.canvasMono(12f));
        topRow.add(findField);

        JButton prevBtn = new JButton("▲");
        prevBtn.setMargin(new Insets(1, 4, 1, 4));
        prevBtn.setToolTipText("Previous Match (Shift+Enter)");
        prevBtn.addActionListener(e -> findPrev());
        topRow.add(prevBtn);

        JButton nextBtn = new JButton("▼");
        nextBtn.setMargin(new Insets(1, 4, 1, 4));
        nextBtn.setToolTipText("Next Match (Enter)");
        nextBtn.addActionListener(e -> findNext());
        topRow.add(nextBtn);

        caseCheck.setFont(AppFonts.uiSmall());
        caseCheck.setOpaque(false);
        caseCheck.addActionListener(e -> searchMatches(true));
        topRow.add(caseCheck);

        findMatchesLabel.setFont(AppFonts.uiSmall());
        findMatchesLabel.setForeground(new Color(0x40, 0x45, 0x50));
        topRow.add(findMatchesLabel);

        replaceRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 1));
        replaceRow.setOpaque(false);

        JLabel repLbl = new JLabel("Replace:");
        repLbl.setFont(AppFonts.uiBold());
        replaceRow.add(repLbl);

        replaceField.setFont(AppFonts.canvasMono(12f));
        replaceRow.add(replaceField);

        JButton repBtn = new JButton("Replace");
        repBtn.setFont(AppFonts.uiPlain());
        repBtn.setMargin(new Insets(1, 6, 1, 6));
        repBtn.addActionListener(e -> replaceCurrentAndNext());
        replaceRow.add(repBtn);

        JButton repAllBtn = new JButton("Replace All");
        repAllBtn.setFont(AppFonts.uiPlain());
        repAllBtn.setMargin(new Insets(1, 6, 1, 6));
        repAllBtn.addActionListener(e -> replaceAll());
        replaceRow.add(repAllBtn);

        JButton toggleReplaceBtn = new JButton("Replace ▾");
        toggleReplaceBtn.setFont(AppFonts.uiSmall());
        toggleReplaceBtn.setMargin(new Insets(1, 4, 1, 4));
        toggleReplaceBtn.addActionListener(e -> {
            replaceRow.setVisible(!replaceRow.isVisible());
            revalidate();
            repaint();
        });
        topRow.add(toggleReplaceBtn);

        JButton closeBtn = new JButton("✕");
        closeBtn.setFont(AppFonts.uiBold());
        closeBtn.setMargin(new Insets(1, 4, 1, 4));
        closeBtn.setToolTipText("Close Find Bar (Escape)");
        closeBtn.addActionListener(e -> closeFindBar());

        JPanel rightPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 1));
        rightPanel.setOpaque(false);
        rightPanel.add(closeBtn);

        JPanel mainFindContainer = new JPanel(new BorderLayout());
        mainFindContainer.setOpaque(false);
        mainFindContainer.add(topRow, BorderLayout.WEST);
        mainFindContainer.add(rightPanel, BorderLayout.EAST);

        bar.add(mainFindContainer, BorderLayout.NORTH);
        bar.add(replaceRow, BorderLayout.SOUTH);
        replaceRow.setVisible(false);

        findField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) { searchMatches(true); }
            @Override
            public void removeUpdate(DocumentEvent e) { searchMatches(true); }
            @Override
            public void changedUpdate(DocumentEvent e) { searchMatches(true); }
        });

        findField.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER) {
                    if (e.isShiftDown()) findPrev();
                    else findNext();
                    e.consume();
                } else if (e.getKeyCode() == KeyEvent.VK_ESCAPE) {
                    closeFindBar();
                    e.consume();
                }
            }
        });

        replaceField.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER) {
                    replaceCurrentAndNext();
                    e.consume();
                } else if (e.getKeyCode() == KeyEvent.VK_ESCAPE) {
                    closeFindBar();
                    e.consume();
                }
            }
        });

        bar.setVisible(false);
        return bar;
    }

    public void openFindBar(boolean showReplace) {
        String selected = codePane.getSelectedText();
        if (selected != null && !selected.isBlank() && !selected.contains("\n")) {
            findField.setText(selected);
        }
        findBar.setVisible(true);
        if (showReplace) {
            replaceRow.setVisible(true);
        }
        revalidate();
        repaint();
        findField.requestFocusInWindow();
        findField.selectAll();
        searchMatches(true);
    }

    public void closeFindBar() {
        findBar.setVisible(false);
        clearFindHighlights();
        codePane.requestFocusInWindow();
    }

    private void searchMatches(boolean selectCurrent) {
        clearFindHighlights();
        currentMatches.clear();
        String q = findField.getText();
        if (q == null || q.isEmpty()) {
            findMatchesLabel.setText("0 of 0");
            currentMatchIndex = -1;
            return;
        }

        String text = codePane.getText();
        boolean matchCase = caseCheck.isSelected();
        String searchIn = matchCase ? text : text.toLowerCase();
        String searchFor = matchCase ? q : q.toLowerCase();

        int idx = 0;
        int caret = codePane.getCaretPosition();
        int closestIdx = -1;
        while ((idx = searchIn.indexOf(searchFor, idx)) >= 0) {
            currentMatches.add(new int[]{idx, idx + searchFor.length()});
            if (closestIdx == -1 && idx >= caret) {
                closestIdx = currentMatches.size() - 1;
            }
            idx += searchFor.length();
        }

        if (currentMatches.isEmpty()) {
            findMatchesLabel.setText("No matches");
            currentMatchIndex = -1;
            return;
        }

        if (currentMatchIndex < 0 || currentMatchIndex >= currentMatches.size()) {
            currentMatchIndex = closestIdx >= 0 ? closestIdx : 0;
        }

        highlightAllMatches();
        if (selectCurrent) {
            jumpToMatch(currentMatchIndex);
        } else {
            updateMatchLabel();
        }
    }

    private void highlightAllMatches() {
        Highlighter hl = codePane.getHighlighter();
        hl.removeAllHighlights();
        for (int i = 0; i < currentMatches.size(); i++) {
            int[] m = currentMatches.get(i);
            try {
                hl.addHighlight(m[0], m[1], (i == currentMatchIndex) ? currentMatchPainter : matchPainter);
            } catch (BadLocationException ignored) {}
        }
        updateMatchLabel();
    }

    private void jumpToMatch(int index) {
        if (currentMatches.isEmpty() || index < 0 || index >= currentMatches.size()) return;
        currentMatchIndex = index;
        int[] m = currentMatches.get(index);
        codePane.setCaretPosition(m[0]);
        codePane.moveCaretPosition(m[1]);
        highlightAllMatches();
    }

    private void findNext() {
        if (currentMatches.isEmpty()) return;
        currentMatchIndex = (currentMatchIndex + 1) % currentMatches.size();
        jumpToMatch(currentMatchIndex);
    }

    private void findPrev() {
        if (currentMatches.isEmpty()) return;
        currentMatchIndex = (currentMatchIndex - 1 + currentMatches.size()) % currentMatches.size();
        jumpToMatch(currentMatchIndex);
    }

    private void updateMatchLabel() {
        if (currentMatches.isEmpty()) {
            findMatchesLabel.setText("No matches");
        } else {
            findMatchesLabel.setText((currentMatchIndex + 1) + " of " + currentMatches.size());
        }
    }

    private void clearFindHighlights() {
        codePane.getHighlighter().removeAllHighlights();
    }

    private void replaceCurrentAndNext() {
        if (currentMatches.isEmpty() || currentMatchIndex < 0 || currentMatchIndex >= currentMatches.size()) return;
        int[] m = currentMatches.get(currentMatchIndex);
        String rep = replaceField.getText();
        try {
            codePane.getDocument().remove(m[0], m[1] - m[0]);
            codePane.getDocument().insertString(m[0], rep, null);
            searchMatches(false);
            if (!currentMatches.isEmpty()) {
                jumpToMatch(Math.min(currentMatchIndex, currentMatches.size() - 1));
            }
        } catch (BadLocationException ignored) {}
    }

    private void replaceAll() {
        if (currentMatches.isEmpty()) return;
        String rep = replaceField.getText();
        String q = findField.getText();
        if (q == null || q.isEmpty()) return;
        boolean matchCase = caseCheck.isSelected();
        String text = codePane.getText();
        String newText = matchCase
                ? text.replace(q, rep)
                : Pattern.compile(Pattern.quote(q), Pattern.CASE_INSENSITIVE).matcher(text).replaceAll(Matcher.quoteReplacement(rep));
        codePane.setText(newText);
        searchMatches(true);
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
        completionDebounce.stop();
        hideCompletionPopup();
        this.project = project;
        this.page = page;
        stUndoManager.discardAllEdits();
        suppressEvents = true;
        try {
            if (page != null) {
                threadCombo.setSelectedIndex(Math.clamp(page.getStThreadId() - 1, 0, 4));
                orderSpinner.setValue(page.getStOrder());

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

    public void undo() {
        if (stUndoManager.canUndo()) {
            try {
                stUndoManager.undo();
            } catch (CannotUndoException ignored) {}
        }
    }

    public void redo() {
        if (stUndoManager.canRedo()) {
            try {
                stUndoManager.redo();
            } catch (CannotRedoException ignored) {}
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
        boolean prev = suppressEvents;
        suppressEvents = true;
        try {
            int caret = Math.min(codePane.getCaretPosition(), updated.length());
            codePane.setText(updated);
            codePane.setCaretPosition(caret);
        } finally {
            suppressEvents = prev;
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
                        } else if (HW_IO_PATTERN.matcher(word).matches()) {
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

    public void setMacroNavigator(Consumer<String> macroNavigator) {
        this.macroNavigator = macroNavigator;
    }

    private void validateStCode() {
        if (page == null) return;
        if (!AppSettings.get().isStRealtimeDiagnostics()) {
            diagnostics.clear();
            gutter.setDiagnostics(List.of());
            updateDiagnosticHighlights();
            statusLabel.setText("Diagnostics disabled in Settings");
            statusLabel.setForeground(Color.GRAY);
            return;
        }

        diagnostics.clear();
        diagnostics.addAll(StLanguageServer.analyze(page.getStCode(), page.getStVariables(), project));

        if (AppSettings.get().isStGutterMarkers()) {
            gutter.setDiagnostics(diagnostics);
        } else {
            gutter.setDiagnostics(List.of());
        }

        updateDiagnosticHighlights();

        if (diagnostics.isEmpty()) {
            statusLabel.setText("✔ ST Syntax OK  |  " + page.getStVariables().size() + " variable(s) declared");
            statusLabel.setForeground(new Color(0x1B, 0x5E, 0x20));
        } else {
            long errors = diagnostics.stream().filter(d -> d.severity() == StLanguageServer.Severity.ERROR).count();
            long warnings = diagnostics.stream().filter(d -> d.severity() == StLanguageServer.Severity.WARNING).count();
            StLanguageServer.Diagnostic first = diagnostics.getFirst();
            statusLabel.setText("✖ " + errors + " error(s), " + warnings + " warning(s) — Line " + first.line() + ": " + first.message());
            statusLabel.setForeground(errors > 0 ? new Color(0xB7, 0x1C, 0x1C) : new Color(0xE6, 0x51, 0x00));
        }
    }

    private void updateDiagnosticHighlights() {
        Highlighter hl = codePane.getHighlighter();
        for (Object tag : diagnosticHighlights) {
            hl.removeHighlight(tag);
        }
        diagnosticHighlights.clear();

        if (!AppSettings.get().isStSquigglyUnderlines()) return;

        String text = codePane.getText();
        String[] lines = text.split("\n", -1);
        int[] lineStarts = new int[lines.length + 1];
        int running = 0;
        for (int i = 0; i < lines.length; i++) {
            lineStarts[i] = running;
            running += lines[i].length() + 1;
        }

        for (StLanguageServer.Diagnostic d : diagnostics) {
            if (d.line() < 1 || d.line() > lines.length) continue;
            int lineIdx = d.line() - 1;
            int lineStart = lineStarts[lineIdx];
            int lineLen = lines[lineIdx].length();
            if (lineLen == 0) continue;

            int p0 = lineStart + Math.clamp(d.colStart(), 0, lineLen);
            int p1 = (d.colEnd() > d.colStart())
                    ? lineStart + Math.clamp(d.colEnd(), 0, lineLen)
                    : lineStart + lineLen;
            if (p1 <= p0) p1 = Math.min(p0 + 4, text.length());

            try {
                Highlighter.HighlightPainter painter = (d.severity() == StLanguageServer.Severity.ERROR)
                        ? errorPainter
                        : warningPainter;
                Object tag = hl.addHighlight(p0, p1, painter);
                diagnosticHighlights.add(tag);
            } catch (BadLocationException ignored) {}
        }
    }

    private static final int COMPLETION_CHUNK = 30;
    private List<StLanguageServer.CompletionItem> completionAll = List.of();
    private int completionLoaded = 0;

    private void triggerCompletion() {
        if (page == null || !AppSettings.get().isStIntelliSenseEnabled() || !isShowing()) return;
        int caret = codePane.getCaretPosition();
        String upToCaret;
        try {
            // Only the text up to the caret is needed for the prefix; avoids copying the whole document.
            int from = Math.max(0, caret - 64);
            upToCaret = codePane.getDocument().getText(from, caret - from);
            caret = upToCaret.length();
        } catch (BadLocationException ex) {
            hideCompletionPopup();
            return;
        }
        List<StLanguageServer.CompletionItem> items = StLanguageServer.getCompletions(upToCaret, caret, page.getStVariables(), project);
        if (items.isEmpty()) {
            hideCompletionPopup();
            return;
        }
        showCompletionPopup(items, codePane.getCaretPosition());
    }

    private void loadMoreCompletions(int count) {
        if (completionModel == null || completionLoaded >= completionAll.size()) return;
        int end = Math.min(completionAll.size(), completionLoaded + count);
        List<StLanguageServer.CompletionItem> chunk = completionAll.subList(completionLoaded, end);
        completionModel.addAll(chunk);
        completionLoaded = end;
    }

    private void showCompletionPopup(List<StLanguageServer.CompletionItem> items, int caret) {
        if (!AppSettings.get().isStIntelliSenseEnabled() || items.isEmpty()) {
            hideCompletionPopup();
            return;
        }

        Window owner = SwingUtilities.getWindowAncestor(this);
        if (owner == null) return;

        int rowHeight = AppFonts.scaled(20);
        if (completionWindow == null) {
            completionWindow = new JWindow(owner);
            completionWindow.setType(Window.Type.POPUP);
            completionWindow.setFocusableWindowState(false); // CRITICAL: Focus NEVER leaves codePane!
            completionWindow.setAutoRequestFocus(false);     // CRITICAL: Cannot steal focus!

            completionModel = new DefaultListModel<>();
            completionList = new JList<>(completionModel);
            completionList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            completionList.setFont(AppFonts.canvasMono(12f));
            completionList.setCellRenderer(new CompletionCellRenderer());
            completionList.setFixedCellHeight(rowHeight); // no per-row measuring

            completionList.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    if (e.getClickCount() == 2 || SwingUtilities.isLeftMouseButton(e)) {
                        insertSelectedCompletion();
                    }
                }
            });

            completionScroll = new JScrollPane(completionList);
            completionScroll.setBorder(BorderFactory.createLineBorder(new Color(0x60, 0x75, 0x90), 1));
            completionScroll.getVerticalScrollBar().addAdjustmentListener(ev -> {
                JScrollBar sb = completionScroll.getVerticalScrollBar();
                if (sb.getValue() + sb.getVisibleAmount() >= sb.getMaximum() - rowHeight * 3) {
                    loadMoreCompletions(COMPLETION_CHUNK);
                }
            });
            completionWindow.setContentPane(completionScroll);
        }

        // Lazy: only the first chunk goes into the model
        completionAll = items;
        completionLoaded = 0;
        completionModel.clear();
        loadMoreCompletions(COMPLETION_CHUNK);

        int maxLen = 0;
        for (int i = 0; i < completionLoaded; i++) {
            StLanguageServer.CompletionItem item = completionAll.get(i);
            int len = item.label().length() + item.detail().length() + 10;
            if (len > maxLen) maxLen = len;
        }
        completionList.setSelectedIndex(0);
        completionList.ensureIndexIsVisible(0);

        int visibleRows = Math.clamp(items.size(), 1, 8);
        int popupH = visibleRows * rowHeight + 4;
        int popupW = Math.max(AppFonts.scaled(280), Math.min(AppFonts.scaled(520), maxLen * 8 + AppFonts.scaled(40)));
        completionList.setFixedCellWidth(popupW - 20);
        Dimension size = new Dimension(popupW, popupH);
        if (!size.equals(completionScroll.getPreferredSize())) {
            completionScroll.setPreferredSize(size);
            completionWindow.pack();
        }

        try {
            Rectangle2D r = codePane.modelToView2D(caret);
            if (r != null) {
                Point screenPt = new Point((int) r.getX(), (int) (r.getY() + r.getHeight() + 2));
                SwingUtilities.convertPointToScreen(screenPt, codePane);
                completionWindow.setLocation(screenPt);
                if (!completionWindow.isVisible()) completionWindow.setVisible(true);
            }
        } catch (Exception ignored) {}
    }

    private void hideCompletionPopup() {
        completionDebounce.stop();
        if (completionWindow != null && completionWindow.isVisible()) {
            completionWindow.setVisible(false);
        }
    }

    private void insertSelectedCompletion() {
        StLanguageServer.CompletionItem item = (completionList != null) ? completionList.getSelectedValue() : null;
        if (item == null) return;
        hideCompletionPopup();

        int caret = codePane.getCaretPosition();
        String text = codePane.getText();
        String prefix = StLanguageServer.extractPrefix(text, caret);
        int replaceStart = caret - prefix.length();

        try {
            StyledDocument doc = codePane.getStyledDocument();
            doc.remove(replaceStart, prefix.length());
            doc.insertString(replaceStart, item.insertText(), styleDefault);
            codePane.setCaretPosition(replaceStart + item.insertText().length());
            codePane.requestFocusInWindow();
        } catch (BadLocationException ignored) {}
    }

    private void jumpToDeclaration(String token) {
        if (page == null || token == null || token.isBlank()) return;

        // 1. Check if token matches a declared variable
        List<StVariable> vars = page.getStVariables();
        for (int i = 0; i < vars.size(); i++) {
            StVariable v = vars.get(i);
            if (v.getName().equalsIgnoreCase(token)) {
                varTable.setRowSelectionInterval(i, i);
                varTable.scrollRectToVisible(varTable.getCellRect(i, 0, true));
                statusLabel.setText("Jumped to declaration of variable '" + v.getName() + "' [" + v.getScope() + " " + v.getDataType() + "]");
                statusLabel.setForeground(new Color(0x0D, 0x47, 0xA1));

                // If code also has textual VAR declaration, navigate caret to it
                Pattern varDeclPat = Pattern.compile("(?i)\\b" + Pattern.quote(token) + "\\s*:");
                Matcher m = varDeclPat.matcher(codePane.getText());
                if (m.find()) {
                    codePane.setCaretPosition(m.start());
                    codePane.requestFocusInWindow();
                }
                return;
            }
        }

        // 2. Check if token matches a project Macro
        if (project != null) {
            for (Page p : project.getPages()) {
                if (p.isMacro() && (p.getCleanName().equalsIgnoreCase(token) || p.getName().equalsIgnoreCase(token))) {
                    statusLabel.setText("Jumped to Macro definition '" + p.getName() + "'");
                    statusLabel.setForeground(new Color(0x4A, 0x14, 0x8C));
                    if (macroNavigator != null) {
                        macroNavigator.accept(p.getCleanName());
                    }
                    return;
                }
            }
        }

        // 3. Hardware I/O / Register check
        if (StLanguageServer.isHardwareIo(token)) {
            statusLabel.setText("Hardware I/O channel: " + token.toUpperCase());
            statusLabel.setForeground(new Color(0x9A, 0x34, 0x12));
        }
    }

    private boolean isDeclarationTarget(String token) {
        if (token == null || token.isBlank() || page == null) return false;
        for (StVariable v : page.getStVariables()) {
            if (v.getName().equalsIgnoreCase(token)) return true;
        }
        if (project != null) {
            for (Page p : project.getPages()) {
                if (p.isMacro() && (p.getCleanName().equalsIgnoreCase(token) || p.getName().equalsIgnoreCase(token))) return true;
            }
        }
        return false;
    }

    private void setupDropTarget() {
        new DropTarget(codePane, DnDConstants.ACTION_COPY, new DropTargetAdapter() {
            @Override
            public void drop(DropTargetDropEvent dtde) {
                try {
                    if (!dtde.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                        dtde.rejectDrop();
                        return;
                    }
                    dtde.acceptDrop(DnDConstants.ACTION_COPY);
                    String defId = (String) dtde.getTransferable().getTransferData(DataFlavor.stringFlavor);
                    if (defId == null || defId.isBlank() || defId.startsWith("Ladder.")) {
                        dtde.dropComplete(false);
                        return;
                    }
                    BlockDefinition def = BlockCatalog.getById(defId);
                    if (def == null || page == null) {
                        dtde.dropComplete(false);
                        return;
                    }

                    Point pt = dtde.getLocation();
                    int dropOffset = codePane.viewToModel2D(pt);
                    insertDroppedBlockTemplate(def, dropOffset);
                    dtde.dropComplete(true);
                } catch (Exception ex) {
                    dtde.dropComplete(false);
                }
            }
        });
    }

    private void insertDroppedBlockTemplate(BlockDefinition def, int offset) {
        if (page == null || def == null) return;
        boolean prev = suppressEvents;
        suppressEvents = true;
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("\n// Block: ").append(def.getId()).append("\n");

            List<BlockDefinition.PinSpec> inputs = def.getDefaultInputs().stream()
                    .filter(p -> !"En".equalsIgnoreCase(p.shortName()))
                    .toList();
            List<BlockDefinition.PinSpec> outputs = def.getDefaultOutputs();

            if (outputs.size() == 1) {
                BlockDefinition.PinSpec outPin = outputs.getFirst();
                String outVar = getUniqueVarName(page, def.getTypeName() + "_Out");
                String defVal = (outPin.defaultConstant() != null && !outPin.defaultConstant().isBlank())
                        ? outPin.defaultConstant()
                        : outPin.dataType().getDefaultConstant();
                page.getStVariables().add(new StVariable(StVariable.Scope.VAR, outVar, outPin.dataType(), defVal, "Output of " + def.getTypeName()));

                sb.append(outVar).append(" := ").append(def.getTypeName()).append("(\n");
                for (int i = 0; i < inputs.size(); i++) {
                    BlockDefinition.PinSpec inPin = inputs.get(i);
                    String val = (inPin.defaultConstant() != null && !inPin.defaultConstant().isBlank())
                            ? inPin.defaultConstant()
                            : inPin.dataType().getDefaultConstant();
                    sb.append("    ").append(inPin.shortName()).append(" := ").append(val);
                    if (i < inputs.size() - 1) sb.append(",");
                    sb.append(" // ").append(inPin.dataType()).append(": ").append(inPin.fullName()).append("\n");
                }
                sb.append(");\n");
            } else {
                String instName = getUniqueVarName(page, "Inst_" + def.getTypeName());
                page.getStVariables().add(new StVariable(StVariable.Scope.VAR, instName, DataType.INT, "0", "Instance of " + def.getTypeName()));
                List<String> outVarNames = new ArrayList<>();
                for (BlockDefinition.PinSpec outPin : outputs) {
                    String outVar = getUniqueVarName(page, def.getTypeName() + "_" + outPin.shortName());
                    String defVal = (outPin.defaultConstant() != null && !outPin.defaultConstant().isBlank())
                            ? outPin.defaultConstant()
                            : outPin.dataType().getDefaultConstant();
                    page.getStVariables().add(new StVariable(StVariable.Scope.VAR, outVar, outPin.dataType(), defVal, "Output " + outPin.fullName() + " of " + instName));
                    outVarNames.add(outVar);
                }

                sb.append(instName).append("(\n");
                int totalPins = inputs.size() + outputs.size();
                int current = 0;
                for (BlockDefinition.PinSpec inPin : inputs) {
                    String val = (inPin.defaultConstant() != null && !inPin.defaultConstant().isBlank())
                            ? inPin.defaultConstant()
                            : inPin.dataType().getDefaultConstant();
                    sb.append("    ").append(inPin.shortName()).append(" := ").append(val);
                    current++;
                    if (current < totalPins) sb.append(",");
                    sb.append(" // ").append(inPin.dataType()).append(": ").append(inPin.fullName()).append("\n");
                }
                for (int i = 0; i < outputs.size(); i++) {
                    BlockDefinition.PinSpec outPin = outputs.get(i);
                    String outVar = outVarNames.get(i);
                    sb.append("    ").append(outPin.shortName()).append(" => ").append(outVar);
                    current++;
                    if (current < totalPins) sb.append(",");
                    sb.append(" // ").append(outPin.dataType()).append(": ").append(outPin.fullName()).append("\n");
                }
                sb.append(");\n");
            }

            varTableModel.fireTableDataChanged();
            if (project != null) project.setDirty(true);

            String templateText = sb.toString();
            try {
                int insertPos = Math.clamp(offset, 0, codePane.getDocument().getLength());
                codePane.getDocument().insertString(insertPos, templateText, styleDefault);
                codePane.setCaretPosition(insertPos + templateText.length());
                codePane.requestFocusInWindow();
            } catch (BadLocationException ignored) {}

            syncTableToCode();
        } finally {
            suppressEvents = prev;
        }
        applySyntaxHighlighting();
        validateStCode();
        statusLabel.setText("Inserted template for " + def.getTypeName() + " with auto-created variables");
        statusLabel.setForeground(new Color(0x1B, 0x5E, 0x20));
    }

    private static String getUniqueVarName(Page page, String base) {
        String sanitized = base.replaceAll("[^A-Za-z0-9_]", "_");
        if (!Character.isLetter(sanitized.charAt(0)) && sanitized.charAt(0) != '_') {
            sanitized = "V_" + sanitized;
        }
        boolean exists = false;
        if (page != null) {
            for (StVariable v : page.getStVariables()) {
                if (v.getName().equalsIgnoreCase(sanitized)) {
                    exists = true;
                    break;
                }
            }
        }
        if (!exists) return sanitized;
        int num = 1;
        while (true) {
            String cand = sanitized + "_" + num;
            boolean match = false;
            for (StVariable v : page.getStVariables()) {
                if (v.getName().equalsIgnoreCase(cand)) {
                    match = true;
                    break;
                }
            }
            if (!match) return cand;
            num++;
        }
    }

    private void actionFormatCode() {
        if (page == null) return;
        String cur = codePane.getText();
        String formatted = StLanguageServer.formatCode(cur);
        if (formatted.equals(cur)) return;
        int caret = Math.min(codePane.getCaretPosition(), formatted.length());
        page.setStCode(formatted);
        if (project != null) project.setDirty(true);
        codePane.setText(formatted);
        codePane.setCaretPosition(caret);
        applySyntaxHighlighting();
        validateStCode();
        gutter.repaint();
    }

    private static String extractTokenAtOffset(String text, int offset) {
        if (text == null || offset < 0 || offset >= text.length()) return null;
        char c = text.charAt(offset);
        if (!Character.isLetterOrDigit(c) && c != '_') {
            if (offset > 0 && (Character.isLetterOrDigit(text.charAt(offset - 1)) || text.charAt(offset - 1) == '_')) {
                offset--;
            } else {
                return null;
            }
        }
        int start = offset;
        while (start > 0 && (Character.isLetterOrDigit(text.charAt(start - 1)) || text.charAt(start - 1) == '_')) {
            start--;
        }
        int end = offset;
        while (end < text.length() && (Character.isLetterOrDigit(text.charAt(end)) || text.charAt(end) == '_')) {
            end++;
        }
        return text.substring(start, end);
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

    private static final class SquigglyUnderlinePainter implements Highlighter.HighlightPainter {
        private final Color color;

        SquigglyUnderlinePainter(Color color) {
            this.color = color;
        }

        @Override
        public void paint(Graphics g, int p0, int p1, Shape bounds, JTextComponent c) {
            try {
                Rectangle2D r0 = c.modelToView2D(p0);
                Rectangle2D r1 = c.modelToView2D(p1);
                if (r0 == null || r1 == null) return;
                int y = (int) (r0.getY() + r0.getHeight() - 1);
                int x1 = (int) r0.getX();
                int x2 = (int) ((r0.getY() == r1.getY()) ? r1.getX() : r0.getX() + 30);
                if (x2 <= x1) x2 = x1 + 12;
                g.setColor(color);
                for (int x = x1; x < x2; x += 4) {
                    g.drawLine(x, y, x + 2, y + 2);
                    g.drawLine(x + 2, y + 2, Math.min(x + 4, x2), y);
                }
            } catch (Exception ignored) {}
        }
    }

    private static final class CompletionCellRenderer extends DefaultListCellRenderer {
        private static final Color VAR = new Color(0x6A1B9A), IO = new Color(0xD84315), FN = new Color(0x00695C),
                FB = new Color(0x1565C0), KW = new Color(0x0D47A1), SNIP = new Color(0x2E7D32);

        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            if (value instanceof StLanguageServer.CompletionItem item) {
                String badge;
                Color c;
                switch (item.kind()) {
                    case VARIABLE -> { badge = "[VAR]"; c = VAR; }
                    case HARDWARE_IO -> { badge = "[I/O]"; c = IO; }
                    case FUNCTION -> { badge = "[FN]"; c = FN; }
                    case FUNCTION_BLOCK -> { badge = "[FB]"; c = FB; }
                    case KEYWORD -> { badge = "[KW]"; c = KW; }
                    default -> { badge = "[SNIP]"; c = SNIP; }
                }
                setText(badge + " " + item.label() + "  \u2014 " + item.detail());
                if (!isSelected) setForeground(c);
            }
            return this;
        }
    }

    private static final class LineNumberGutter extends JPanel {
        private final JTextPane pane;
        private List<StLanguageServer.Diagnostic> diagnostics = List.of();

        LineNumberGutter(JTextPane pane) {
            this.pane = pane;
            setBackground(new Color(0xEC, 0xEE, 0xF2));
            setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, new Color(0xB8, 0xBE, 0xC8)));
            ToolTipManager.sharedInstance().registerComponent(this);
        }

        void setDiagnostics(List<StLanguageServer.Diagnostic> diags) {
            this.diagnostics = diags == null ? List.of() : new ArrayList<>(diags);
            repaint();
        }

        @Override
        public String getToolTipText(MouseEvent e) {
            int lineHeight = pane.getFontMetrics(pane.getFont()).getHeight();
            int line = Math.max(1, e.getY() / lineHeight + 1);
            List<StLanguageServer.Diagnostic> matches = diagnostics.stream()
                    .filter(d -> d.line() == line)
                    .toList();
            if (matches.isEmpty()) return null;
            StringBuilder sb = new StringBuilder("<html><b>Line ").append(line).append(":</b><br>");
            for (StLanguageServer.Diagnostic d : matches) {
                String color = d.severity() == StLanguageServer.Severity.ERROR ? "#D32F2F" : "#E65100";
                sb.append("<span style='color:").append(color).append(";'>")
                        .append(d.severity()).append(": ").append(d.message())
                        .append("</span><br>");
            }
            sb.append("</html>");
            return sb.toString();
        }

        @Override
        public Dimension getPreferredSize() {
            int lines = Math.max(1, countLines());
            int digits = Math.max(3, String.valueOf(lines).length());
            Font f = AppFonts.canvasMono(12f);
            FontMetrics fm = getFontMetrics(f);
            int w = fm.charWidth('0') * digits + AppFonts.scaled(20);
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
                g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            }
            FontMetrics fm = g.getFontMetrics(AppFonts.canvasMono(12f));
            int lineHeight = pane.getFontMetrics(pane.getFont()).getHeight();
            int ascent = pane.getFontMetrics(pane.getFont()).getAscent();
            int lines = countLines();
            int y = ascent + 2;
            for (int i = 1; i <= lines; i++) {
                final int lineNum = i;
                StLanguageServer.Diagnostic lineDiag = diagnostics.stream()
                        .filter(d -> d.line() == lineNum)
                        .findFirst()
                        .orElse(null);
                if (lineDiag != null) {
                    if (lineDiag.severity() == StLanguageServer.Severity.ERROR) {
                        g.setColor(new Color(0xD3, 0x2F, 0x2F));
                        g.fillOval(3, y - ascent + 2, 7, 7);
                    } else {
                        g.setColor(new Color(0xF5, 0x7C, 0x00));
                        g.fillOval(3, y - ascent + 2, 7, 7);
                    }
                }
                g.setFont(AppFonts.canvasMono(12f));
                g.setColor(new Color(0x55, 0x60, 0x70));
                String s = String.valueOf(i);
                int x = getWidth() - fm.stringWidth(s) - 6;
                g.drawString(s, x, y);
                y += lineHeight;
            }
        }
    }
}
