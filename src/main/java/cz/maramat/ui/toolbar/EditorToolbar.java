package cz.maramat.ui.toolbar;

import cz.maramat.model.GraphicElement;
import cz.maramat.model.IecLanguage;
import cz.maramat.model.LadderElement;
import cz.maramat.model.PlcThread;
import cz.maramat.sim.BlockSorter.OrderSortMode;
import cz.maramat.sim.SimulationEngine;
import cz.maramat.ui.icons.VectorIcons;
import cz.maramat.undo.UndoManager50;
import cz.maramat.undo.UndoManager50.HistoryEntry;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JToggleButton;
import javax.swing.JToolBar;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

public class EditorToolbar extends JToolBar {

    public enum ToolMode {
        CURSOR,
        LINE,
        POLYGON_SHAPE,
        CIRCLE_SHAPE,
        TEXT,
        VISUAL_BUTTON
    }

    public interface ToolbarListener {
        void onToolChanged(ToolMode mode, GraphicElement.Kind shapeKind, int customNgonSides);
        void onUndoSteps(int count);
        void onRedoSteps(int count);
        void onSimPlay();
        void onSimPause();
        void onSimStop();
        void onSimSpeedChanged(double multiplier);
        void onSortGraphically();
        void onSortExecutionOrder(OrderSortMode sortMode);
        void onCompile();
        void onUpload();
        void onWatchToggle();
        void onPlcConfig();
        void onDefaultThreadChanged(int threadId);

        // Ladder (LD) specific toolbar actions
        default void onLadderAddRung() {}
        default void onLadderInsertContact(LadderElement.Kind contactKind) {}
        default void onLadderInsertCoil(LadderElement.Kind coilKind) {}
        default void onLadderAddParallelBranch() {}
    }

    private final ToolbarListener listener;
    private final UndoManager50 undoManager;

    private ToolMode currentToolMode = ToolMode.CURSOR;
    private GraphicElement.Kind currentPolygonKind = GraphicElement.Kind.RECTANGLE;
    private GraphicElement.Kind currentCircleKind = GraphicElement.Kind.CIRCLE;
    private int customNgonSides = 6;
    private OrderSortMode currentOrderSortMode = OrderSortMode.LEFT_TO_RIGHT;

    private final List<JComponent> fbdToolButtons = new ArrayList<>();
    private final List<JComponent> ladderToolButtons = new ArrayList<>();

    private JToggleButton cursorBtn;
    private JToggleButton lineBtn;
    private JToggleButton polyFlyoutBtn;
    private JToggleButton circleFlyoutBtn;
    private JToggleButton textBtn;
    private JToggleButton visualButtonBtn;

    private JButton undoMainBtn;
    private JButton undoDropBtn;
    private JButton redoMainBtn;
    private JButton redoDropBtn;

    private JButton playBtn;
    private JButton pauseBtn;
    private JButton stopBtn;
    private JButton watchBtn;
    private JComboBox<PlcThread> threadCombo;

    public EditorToolbar(UndoManager50 undoManager, ToolbarListener listener) {
        this.undoManager = undoManager;
        this.listener = listener;

        setFloatable(false);
        setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(0xB8, 0xB8, 0xB8)));
        setMargin(new Insets(2, 4, 2, 4));

        buildToolbar();
        undoManager.addChangeListener(this::refreshUndoRedoButtons);
        refreshUndoRedoButtons();
    }

    private void buildToolbar() {
        ButtonGroup toolGroup = new ButtonGroup();

        // 1. Cursor Tool
        cursorBtn = createToggleToolButton(VectorIcons.cursorToolIcon(), "Cursor / Select Tool");
        cursorBtn.setSelected(true);
        cursorBtn.addActionListener(e -> selectTool(ToolMode.CURSOR, GraphicElement.Kind.RECTANGLE));
        toolGroup.add(cursorBtn);
        add(cursorBtn);

        // 2. FBD Graphical Tools (Directly added to JToolBar so everything stays strictly left-aligned)
        lineBtn = createToggleToolButton(VectorIcons.lineToolIcon(), "Graphical Line Tool");
        lineBtn.addActionListener(e -> selectTool(ToolMode.LINE, GraphicElement.Kind.LINE));
        toolGroup.add(lineBtn);
        fbdToolButtons.add(lineBtn);
        add(lineBtn);

        polyFlyoutBtn = createToggleToolButton(
                VectorIcons.shapeToolIcon(currentPolygonKind, true),
                "Graphical Shape Tool (Hold LMB or Right-Click for Polygon/N-Gon Submenu)"
        );
        polyFlyoutBtn.addActionListener(e -> selectTool(ToolMode.POLYGON_SHAPE, currentPolygonKind));
        installPhotoshopFlyout(polyFlyoutBtn, new GraphicElement.Kind[]{
                GraphicElement.Kind.RECTANGLE,
                GraphicElement.Kind.ROUNDED_RECTANGLE,
                GraphicElement.Kind.TRIANGLE,
                GraphicElement.Kind.PENTAGON,
                GraphicElement.Kind.HEXAGON,
                GraphicElement.Kind.OCTAGON,
                GraphicElement.Kind.CUSTOM_NGON
        }, true);
        toolGroup.add(polyFlyoutBtn);
        fbdToolButtons.add(polyFlyoutBtn);
        add(polyFlyoutBtn);

        circleFlyoutBtn = createToggleToolButton(
                VectorIcons.shapeToolIcon(currentCircleKind, true),
                "Graphical Circle/Oval Tool (Hold LMB or Right-Click for Submenu)"
        );
        circleFlyoutBtn.addActionListener(e -> selectTool(ToolMode.CIRCLE_SHAPE, currentCircleKind));
        installPhotoshopFlyout(circleFlyoutBtn, new GraphicElement.Kind[]{
                GraphicElement.Kind.CIRCLE,
                GraphicElement.Kind.ELLIPSE,
                GraphicElement.Kind.ARC,
                GraphicElement.Kind.SECTOR
        }, false);
        toolGroup.add(circleFlyoutBtn);
        fbdToolButtons.add(circleFlyoutBtn);
        add(circleFlyoutBtn);

        textBtn = createToggleToolButton(VectorIcons.textToolIcon(), "Text Annotation Tool");
        textBtn.addActionListener(e -> selectTool(ToolMode.TEXT, GraphicElement.Kind.TEXT));
        toolGroup.add(textBtn);
        fbdToolButtons.add(textBtn);
        add(textBtn);

        visualButtonBtn = createToggleToolButton(VectorIcons.visualButtonToolIcon(), "Visual Button Tool (Interactive Input Driver)");
        visualButtonBtn.addActionListener(e -> selectTool(ToolMode.VISUAL_BUTTON, GraphicElement.Kind.VISUAL_BUTTON));
        toolGroup.add(visualButtonBtn);
        fbdToolButtons.add(visualButtonBtn);
        add(visualButtonBtn);

        // 3. Ladder (LD) Quick Insert Tools (Directly added to JToolBar)
        JButton addRungBtn = createActionBtn(VectorIcons.ladderAddRungIcon(), "Insert New Ladder Rung Below");
        addRungBtn.addActionListener(e -> listener.onLadderAddRung());
        ladderToolButtons.add(addRungBtn);
        add(addRungBtn);

        JButton noContactBtn = createActionBtn(VectorIcons.ladderContactIcon(false, ""), "Insert Normally Open Contact (--| |--)");
        noContactBtn.addActionListener(e -> listener.onLadderInsertContact(LadderElement.Kind.NO_CONTACT));
        ladderToolButtons.add(noContactBtn);
        add(noContactBtn);

        JButton ncContactBtn = createActionBtn(VectorIcons.ladderContactIcon(true, ""), "Insert Normally Closed Contact (--|/|--)" );
        ncContactBtn.addActionListener(e -> listener.onLadderInsertContact(LadderElement.Kind.NC_CONTACT));
        ladderToolButtons.add(ncContactBtn);
        add(ncContactBtn);

        JButton edgeContactBtn = createActionBtn(VectorIcons.ladderContactIcon(false, "P"), "Insert Rising/Falling Edge Contact (Click for --|P|--, Right-Click for --|N|--)");
        edgeContactBtn.addActionListener(e -> listener.onLadderInsertContact(LadderElement.Kind.POS_EDGE_CONTACT));
        edgeContactBtn.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (SwingUtilities.isRightMouseButton(e)) {
                    JPopupMenu pm = new JPopupMenu();
                    JMenuItem pos = new JMenuItem("Rising Edge Contact (--|P|--)");
                    pos.addActionListener(ev -> listener.onLadderInsertContact(LadderElement.Kind.POS_EDGE_CONTACT));
                    JMenuItem neg = new JMenuItem("Falling Edge Contact (--|N|--)");
                    neg.addActionListener(ev -> listener.onLadderInsertContact(LadderElement.Kind.NEG_EDGE_CONTACT));
                    pm.add(pos);
                    pm.add(neg);
                    pm.show(edgeContactBtn, 0, edgeContactBtn.getHeight());
                }
            }
        });
        ladderToolButtons.add(edgeContactBtn);
        add(edgeContactBtn);

        JButton branchBtn = createActionBtn(VectorIcons.ladderParallelBranchIcon(), "Add Parallel OR Branch on Selected Rung");
        branchBtn.addActionListener(e -> listener.onLadderAddParallelBranch());
        ladderToolButtons.add(branchBtn);
        add(branchBtn);

        JButton normalCoilBtn = createActionBtn(VectorIcons.ladderCoilIcon(""), "Insert Normal Output Coil (--( )--)");
        normalCoilBtn.addActionListener(e -> listener.onLadderInsertCoil(LadderElement.Kind.NORMAL_COIL));
        ladderToolButtons.add(normalCoilBtn);
        add(normalCoilBtn);

        JButton setResetCoilBtn = createActionBtn(VectorIcons.ladderCoilIcon("S"), "Insert Set/Reset/Negated Coil (Click for --(S)--, Right-Click for Submenu)");
        setResetCoilBtn.addActionListener(e -> listener.onLadderInsertCoil(LadderElement.Kind.SET_COIL));
        setResetCoilBtn.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (SwingUtilities.isRightMouseButton(e)) {
                    JPopupMenu pm = new JPopupMenu();
                    for (LadderElement.Kind k : new LadderElement.Kind[]{
                            LadderElement.Kind.SET_COIL,
                            LadderElement.Kind.RESET_COIL,
                            LadderElement.Kind.NEGATED_COIL,
                            LadderElement.Kind.POS_EDGE_COIL,
                            LadderElement.Kind.NEG_EDGE_COIL
                    }) {
                        JMenuItem item = new JMenuItem(k.getDisplayName() + " (" + k.getSymbolText() + ")");
                        item.addActionListener(ev -> listener.onLadderInsertCoil(k));
                        pm.add(item);
                    }
                    pm.show(setResetCoilBtn, 0, setResetCoilBtn.getHeight());
                }
            }
        });
        ladderToolButtons.add(setResetCoilBtn);
        add(setResetCoilBtn);

        JButton analogCoilBtn = createActionBtn(VectorIcons.ladderAnalogCoilIcon(), "Insert Analog Output / Move Coil (--[AO :=]--, transfers AI/Register/Block output to AO/PWM/Register)");
        analogCoilBtn.addActionListener(e -> listener.onLadderInsertCoil(LadderElement.Kind.ANALOG_COIL));
        ladderToolButtons.add(analogCoilBtn);
        add(analogCoilBtn);

        for (JComponent c : ladderToolButtons) {
            c.setVisible(false);
        }

        addToolbarSplitter();

        // 7. Undo + 50-op dropdown
        undoMainBtn = createActionBtn(VectorIcons.undoIcon(), "Undo (Ctrl+Z)");
        undoMainBtn.addActionListener(e -> listener.onUndoSteps(1));
        add(undoMainBtn);

        undoDropBtn = createDropArrowBtn("Undo History (Last 50 Operations)");
        undoDropBtn.addActionListener(e -> showHistoryDropdown(undoDropBtn, undoManager.getUndoEntries(), "Undo", listener::onUndoSteps));
        add(undoDropBtn);

        add(Box.createHorizontalStrut(2));

        // 8. Redo + 50-op dropdown
        redoMainBtn = createActionBtn(VectorIcons.redoIcon(), "Redo (Ctrl+Y)");
        redoMainBtn.addActionListener(e -> listener.onRedoSteps(1));
        add(redoMainBtn);

        redoDropBtn = createDropArrowBtn("Redo History (Last 50 Operations)");
        redoDropBtn.addActionListener(e -> showHistoryDropdown(redoDropBtn, undoManager.getRedoEntries(), "Redo", listener::onRedoSteps));
        add(redoDropBtn);

        addToolbarSplitter();

        // 9. Simulation Tools: Play, Pause, Halt/Stop, Sim Speed Multiplier Dropdown
        playBtn = createActionBtn(VectorIcons.playIcon(), "Play Simulation");
        playBtn.addActionListener(e -> listener.onSimPlay());
        add(playBtn);

        pauseBtn = createActionBtn(VectorIcons.pauseIcon(), "Pause Simulation");
        pauseBtn.addActionListener(e -> listener.onSimPause());
        add(pauseBtn);

        stopBtn = createActionBtn(VectorIcons.stopIcon(), "Halt / Stop Simulation");
        stopBtn.addActionListener(e -> listener.onSimStop());
        add(stopBtn);

        add(Box.createHorizontalStrut(4));
        String[] speedLabels = {
                "0.01x", "0.1x", "0.25x", "0.5x", "1x (Real-time)",
                "2x", "5x", "10x", "60x (1m/s)", "3600x (1h/s)", "Max Speed"
        };
        double[] speedValues = {
                0.01, 0.1, 0.25, 0.5, 1.0,
                2.0, 5.0, 10.0, 60.0, 3600.0, 10000.0
        };
        JComboBox<String> simSpeedCombo = new JComboBox<>(speedLabels);
        simSpeedCombo.setSelectedIndex(4);
        simSpeedCombo.setMaximumSize(new Dimension(115, 22));
        simSpeedCombo.setToolTipText("Global Simulation Speed Multiplier");
        simSpeedCombo.addActionListener(e -> {
            int idx = simSpeedCombo.getSelectedIndex();
            if (idx >= 0 && idx < speedValues.length) {
                listener.onSimSpeedChanged(speedValues[idx]);
            }
        });
        add(simSpeedCombo);

        addToolbarSplitter();

        // 10. Sorting Tools: Sort Blocks Graphically, Sort Block Execution Order (with dropdown)
        JButton sortGraphBtn = createActionBtn(VectorIcons.sortGraphicallyIcon(), "Sort Blocks Graphically into Columns");
        sortGraphBtn.addActionListener(e -> listener.onSortGraphically());
        add(sortGraphBtn);

        add(Box.createHorizontalStrut(2));

        JButton sortOrderBtn = createActionBtn(VectorIcons.sortExecutionOrderIcon(), "Sort Block Execution Order (Increments of 10)");
        sortOrderBtn.addActionListener(e -> listener.onSortExecutionOrder(currentOrderSortMode));
        add(sortOrderBtn);

        JButton sortOrderDropBtn = createDropArrowBtn("Select Execution Order Sort Algorithm");
        sortOrderDropBtn.addActionListener(e -> {
            JPopupMenu menu = new JPopupMenu();
            for (OrderSortMode m : OrderSortMode.values()) {
                JMenuItem item = new JMenuItem((m == currentOrderSortMode ? "* " : "  ") + m.getLabel());
                item.addActionListener(ev -> {
                    currentOrderSortMode = m;
                    listener.onSortExecutionOrder(currentOrderSortMode);
                });
                menu.add(item);
            }
            menu.show(sortOrderDropBtn, 0, sortOrderDropBtn.getHeight());
        });
        add(sortOrderDropBtn);

        addToolbarSplitter();

        // 11. Hardware PLC Options: Compile, Upload, Watch, Unit/PLC Config
        JButton compileBtn = createActionBtn(VectorIcons.compileIcon(), "Compile Program into Bytecode (F7)");
        compileBtn.addActionListener(e -> listener.onCompile());
        add(compileBtn);

        JButton uploadBtn = createActionBtn(VectorIcons.uploadIcon(), "Upload Compiled Bytecode to PLC");
        uploadBtn.addActionListener(e -> listener.onUpload());
        add(uploadBtn);

        watchBtn = createActionBtn(VectorIcons.watchGlassesIcon(), "Watch PLC Live Variables & Block Outputs");
        watchBtn.addActionListener(e -> listener.onWatchToggle());
        add(watchBtn);

        JButton plcConfigBtn = createActionBtn(VectorIcons.plcConfigGearIcon(), "Unit / PLC Configuration");
        plcConfigBtn.addActionListener(e -> listener.onPlcConfig());
        add(plcConfigBtn);

        addToolbarSplitter();

        // 12. Default Thread Selector
        add(new JLabel(" Thread: "));
        threadCombo = new JComboBox<>(PlcThread.values());
        threadCombo.setSelectedItem(PlcThread.THREAD_2);
        threadCombo.setMaximumSize(new Dimension(115, 22));
        threadCombo.setToolTipText("Default ThreadId assigned to newly placed blocks/rungs");
        threadCombo.addActionListener(e -> {
            PlcThread sel = (PlcThread) threadCombo.getSelectedItem();
            if (sel != null) {
                listener.onDefaultThreadChanged(sel.getId());
            }
        });
        add(threadCombo);

        // Push everything strictly to the left
        add(Box.createHorizontalGlue());
    }

    public void setActivePageLanguage(IecLanguage language) {
        boolean isLd = (language == IecLanguage.LD);
        for (JComponent c : fbdToolButtons) {
            c.setVisible(!isLd);
        }
        for (JComponent c : ladderToolButtons) {
            c.setVisible(isLd);
        }
        revalidate();
        repaint();
    }

    private void installPhotoshopFlyout(JToggleButton btn, GraphicElement.Kind[] kinds, boolean isPolygonGroup) {
        Timer holdTimer = new Timer(250, e -> showShapeFlyoutPopup(btn, kinds, isPolygonGroup));
        holdTimer.setRepeats(false);

        btn.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (SwingUtilities.isRightMouseButton(e)) {
                    holdTimer.stop();
                    showShapeFlyoutPopup(btn, kinds, isPolygonGroup);
                } else if (SwingUtilities.isLeftMouseButton(e)) {
                    holdTimer.restart();
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                holdTimer.stop();
            }

            @Override
            public void mouseExited(MouseEvent e) {
                holdTimer.stop();
            }
        });
    }

    private void showShapeFlyoutPopup(JToggleButton btn, GraphicElement.Kind[] kinds, boolean isPolygonGroup) {
        JPopupMenu popup = new JPopupMenu();
        for (GraphicElement.Kind k : kinds) {
            JMenuItem item = new JMenuItem(k.getDisplayName(), VectorIcons.shapeToolIcon(k, false));
            item.addActionListener(e -> {
                if (k == GraphicElement.Kind.CUSTOM_NGON) {
                    String in = JOptionPane.showInputDialog(btn, "Enter number of polygon sides (3..32):", customNgonSides);
                    if (in != null) {
                        try {
                            customNgonSides = Math.clamp(Integer.parseInt(in.trim()), 3, 32);
                        } catch (NumberFormatException ignored) {}
                    }
                }
                if (isPolygonGroup) {
                    currentPolygonKind = k;
                    btn.setIcon(VectorIcons.shapeToolIcon(k, true));
                    btn.setSelected(true);
                    selectTool(ToolMode.POLYGON_SHAPE, k);
                } else {
                    currentCircleKind = k;
                    btn.setIcon(VectorIcons.shapeToolIcon(k, true));
                    btn.setSelected(true);
                    selectTool(ToolMode.CIRCLE_SHAPE, k);
                }
            });
            popup.add(item);
        }
        popup.show(btn, 0, btn.getHeight());
    }

    private void showHistoryDropdown(JButton anchorBtn, List<HistoryEntry> entries, String verb, IntConsumer stepCallback) {
        if (entries.isEmpty()) return;
        JPopupMenu popup = new JPopupMenu();
        popup.setBorder(BorderFactory.createLineBorder(new Color(0x88, 0x88, 0x88)));

        JPanel listPanel = new JPanel(new GridLayout(entries.size(), 1, 0, 0));
        JLabel summaryLabel = new JLabel(" " + verb + " 1 operation", JLabel.LEFT);
        summaryLabel.setFont(new Font("SansSerif", Font.BOLD, 11));
        summaryLabel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(0xCC, 0xCC, 0xCC)),
                BorderFactory.createEmptyBorder(3, 6, 3, 6)
        ));
        summaryLabel.setOpaque(true);
        summaryLabel.setBackground(new Color(0xEE, 0xF2, 0xF7));

        List<JLabel> rowLabels = new ArrayList<>();
        Color highlightBg = new Color(0x00, 0x66, 0xCC);
        Color normalBg = Color.WHITE;

        for (int i = 0; i < entries.size(); i++) {
            final int stepCount = i + 1;
            HistoryEntry entry = entries.get(i);
            JLabel row = new JLabel("  " + stepCount + ". " + entry.actionName() + "  ");
            row.setOpaque(true);
            row.setBackground(i == 0 ? highlightBg : normalBg);
            row.setForeground(i == 0 ? Color.WHITE : Color.BLACK);
            row.setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 12));
            row.setFont(new Font("SansSerif", Font.PLAIN, 11));

            row.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent e) {
                    for (int j = 0; j < rowLabels.size(); j++) {
                        boolean hl = (j < stepCount);
                        rowLabels.get(j).setBackground(hl ? highlightBg : normalBg);
                        rowLabels.get(j).setForeground(hl ? Color.WHITE : Color.BLACK);
                    }
                    summaryLabel.setText(" " + verb + " " + stepCount + " operation" + (stepCount > 1 ? "s" : ""));
                }

                @Override
                public void mousePressed(MouseEvent e) {
                    popup.setVisible(false);
                    stepCallback.accept(stepCount);
                }
            });
            rowLabels.add(row);
            listPanel.add(row);
        }

        JPanel container = new JPanel(new BorderLayout());
        container.add(listPanel, BorderLayout.CENTER);
        container.add(summaryLabel, BorderLayout.SOUTH);
        popup.add(container);
        popup.show(anchorBtn, 0, anchorBtn.getHeight());
    }

    public void selectCursorTool() {
        cursorBtn.setSelected(true);
        selectTool(ToolMode.CURSOR, GraphicElement.Kind.RECTANGLE);
    }

    private void selectTool(ToolMode mode, GraphicElement.Kind kind) {
        this.currentToolMode = mode;
        listener.onToolChanged(mode, kind, customNgonSides);
    }

    public void refreshUndoRedoButtons() {
        boolean canU = undoManager.canUndo();
        boolean canR = undoManager.canRedo();
        if (undoMainBtn != null) undoMainBtn.setEnabled(canU);
        if (undoDropBtn != null) undoDropBtn.setEnabled(canU);
        if (redoMainBtn != null) redoMainBtn.setEnabled(canR);
        if (redoDropBtn != null) redoDropBtn.setEnabled(canR);
    }

    public void updateSimState(SimulationEngine.State state) {
        boolean watching = (state == SimulationEngine.State.WATCH_MODE);
        watchBtn.setBackground(watching ? new Color(0xB8, 0xE6, 0xC8) : null);
        playBtn.setEnabled(!watching && state != SimulationEngine.State.RUNNING);
        pauseBtn.setEnabled(!watching && state == SimulationEngine.State.RUNNING);
        stopBtn.setEnabled(state != SimulationEngine.State.STOPPED);
    }

    public void setDefaultThreadSelection(int threadId) {
        threadCombo.setSelectedItem(PlcThread.fromId(threadId));
    }

    private JToggleButton createToggleToolButton(javax.swing.Icon icon, String tooltip) {
        JToggleButton b = new JToggleButton(icon);
        b.setFocusable(false);
        b.setToolTipText(tooltip);
        b.setMargin(new Insets(3, 4, 3, 4));
        return b;
    }

    private JButton createActionBtn(javax.swing.Icon icon, String tooltip) {
        JButton b = new JButton(icon);
        b.setFocusable(false);
        b.setToolTipText(tooltip);
        b.setMargin(new Insets(3, 4, 3, 4));
        return b;
    }

    private JButton createDropArrowBtn(String tooltip) {
        JButton b = new JButton(VectorIcons.smallDownArrowIcon());
        b.setFocusable(false);
        b.setToolTipText(tooltip);
        b.setMargin(new Insets(3, 1, 3, 1));
        return b;
    }

    private void addToolbarSplitter() {
        add(Box.createHorizontalStrut(4));
        JToolBar.Separator sep = new JToolBar.Separator(new Dimension(6, 20));
        add(sep);
        add(Box.createHorizontalStrut(4));
    }
}
