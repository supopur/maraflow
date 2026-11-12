package cz.maramat.ui.canvas;

import cz.maramat.model.BlockCatalog;
import cz.maramat.model.BlockDefinition;
import cz.maramat.model.BlockInstance;
import cz.maramat.model.DataType;
import cz.maramat.model.HeaderColor;
import cz.maramat.model.IecLanguage;
import cz.maramat.model.LadderBranch;
import cz.maramat.model.LadderElement;
import cz.maramat.model.LadderRung;
import cz.maramat.model.Page;
import cz.maramat.model.PinInstance;
import cz.maramat.model.PlcThread;
import cz.maramat.model.Project;
import cz.maramat.settings.AppSettings;
import cz.maramat.sim.SimulationEngine;
import cz.maramat.ui.AppFonts;
import cz.maramat.ui.dialogs.ModulePropertiesDialog;
import cz.maramat.ui.dialogs.OperandPickerDialog;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.JViewport;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.TransferHandler;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Frame;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.datatransfer.DataFlavor;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Structured Rung-based IEC 61131-3 Ladder Diagram (LD) Editor Canvas.
 * Supports:
 * - Left Power Rail (L+) and Right Return Rail (L-)
 * - Numbered horizontal rungs with ThreadId (T:x), Order (O:x), and comments
 * - Contacts (--| |--, --|/|--, --|P|--, --|N|--)
 * - Parallel OR branches spanning across main rung columns
 * - Embedded Function Blocks (Timers, Comparators, Math, Logic, I/O, Registers, Macros) with EN/ENO power flow
 *   and clickable input/output operand pills (e.g. AI0..AI7, AO0..AO3, PWM0..PWM3, REG_0..15)
 * - Output Coils (--( )--, --(/)--, --(S)--, --(R)--, --(P)--, --(N)--) and Analog Output / Move Coils (--[AO :=]--)
 * - Live green power-flow highlighting, interactive contact toggling, and live Analog Input slider adjustment during simulation
 */
public class LadderCanvas extends JPanel {

    private static final Color COLOR_BG = Color.WHITE;
    private static final Color COLOR_RAIL = new Color(0x1E, 0x29, 0x3B);
    private static final Color COLOR_WIRE_OFF = new Color(0x33, 0x33, 0x33);
    private static final Color COLOR_POWER_ON = new Color(0x1E, 0x84, 0x49); // Green power flow
    private static final Color COLOR_RUNG_HEADER_BG = new Color(0xEE, 0xF2, 0xF7);
    private static final Color COLOR_RUNG_SELECTED_BG = new Color(0xE1, 0xEC, 0xFA);
    private static final Color COLOR_ELEMENT_SELECTED = new Color(0x00, 0x66, 0xCC);

    private Project project;
    private int activePageIndex = 0;
    private final SimulationEngine simEngine;
    private final FbdCanvas.CanvasEditListener editListener;

    private double zoom = 1.0;
    private boolean showGrid = true;
    private boolean showExecutionInfo = true;

    private int selectedRungIndex = 0;
    private LadderElement selectedElement = null;
    private LadderBranch selectedBranch = null;

    // Hit-test regions rebuilt during paint
    private record RungHitBox(int rungIndex, Rectangle bounds, Rectangle headerBounds) {}
    private record ElementHitBox(int rungIndex, LadderElement element, LadderBranch parentBranch, int columnIndex, Rectangle bounds) {}
    private record PinOperandHitBox(int rungIndex, BlockInstance block, int pinIndex, boolean isInput, Rectangle bounds) {}

    private final List<RungHitBox> rungHitBoxes = new ArrayList<>();
    private final List<ElementHitBox> elementHitBoxes = new ArrayList<>();
    private final List<PinOperandHitBox> pinHitBoxes = new ArrayList<>();

    private Point panStartScreen = null;
    private Point panStartViewPos = null;
    private long lastPopupTimestamp = 0;

    private static boolean isPopupTrigger(MouseEvent e) {
        return e.isPopupTrigger()
                || SwingUtilities.isRightMouseButton(e)
                || (e.isControlDown() && SwingUtilities.isLeftMouseButton(e))
                || (e.getModifiersEx() & (MouseEvent.BUTTON3_DOWN_MASK | MouseEvent.BUTTON2_DOWN_MASK)) != 0;
    }

    public LadderCanvas(Project project, SimulationEngine simEngine, FbdCanvas.CanvasEditListener editListener) {
        this.project = project;
        this.simEngine = simEngine;
        this.editListener = editListener;

        setBackground(COLOR_BG);
        setFocusable(true);
        installMouseHandlers();
        installDropTarget();
    }

    private Page forcedPage = null;

    public void setForcedPage(Page page) {
        this.forcedPage = page;
        updatePreferredSizeFromRungs();
        revalidate();
        repaint();
    }

    public Page getActivePage() {
        if (forcedPage != null) return forcedPage;
        if (project == null || project.getPages().isEmpty()) return null;
        int idx = Math.clamp(activePageIndex, 0, project.getPages().size() - 1);
        return project.getPages().get(idx);
    }

    public void setProjectAndPage(Project project, int pageIndex) {
        this.project = project;
        this.activePageIndex = pageIndex;
        Page page = getActivePage();
        if (page != null && page.getLanguage() == IecLanguage.LD && page.getRungs().isEmpty()) {
            LadderRung r1 = new LadderRung(project.getDefaultThreadId(), 10, "Rung 1");
            page.getRungs().add(r1);
        }
        this.selectedRungIndex = 0;
        this.selectedElement = null;
        this.selectedBranch = null;
        updatePreferredSizeFromRungs();
        revalidate();
        repaint();
    }

    public int getSelectedRungIndex() {
        Page p = getActivePage();
        if (p == null || p.getRungs().isEmpty()) return -1;
        return Math.clamp(selectedRungIndex, 0, p.getRungs().size() - 1);
    }

    public void selectAndCenterRung(int rungIndex) {
        Page p = getActivePage();
        if (p == null || rungIndex < 0 || rungIndex >= p.getRungs().size()) return;
        this.selectedRungIndex = rungIndex;
        this.selectedElement = null;
        this.selectedBranch = null;

        for (RungHitBox rhb : rungHitBoxes) {
            if (rhb.rungIndex == rungIndex) {
                if (getParent() instanceof JViewport vp) {
                    int vy = Math.max(0, rhb.bounds.y - 60);
                    vp.setViewPosition(new Point(0, vy));
                } else {
                    scrollRectToVisible(new Rectangle(0, Math.max(0, rhb.bounds.y - 60), getWidth(), rhb.bounds.height + 120));
                }
                break;
            }
        }
        repaint();
    }

    // =========================================================================
    // Toolbar & Menu Insertion Actions for Ladder
    // =========================================================================

    public void addNewRungBelowSelected() {
        Page page = getActivePage();
        if (page == null) return;
        Page before = page.copy();

        int tid = project != null ? project.getDefaultThreadId() : 2;
        int order = page.nextOrderForThread(tid);
        int insertIdx = page.getRungs().isEmpty() ? 0 : Math.min(page.getRungs().size(), getSelectedRungIndex() + 1);
        LadderRung newRung = new LadderRung(tid, order, "Rung " + (insertIdx + 1));
        newRung.getMainElements().add(new LadderElement(LadderElement.Kind.NO_CONTACT, "DI0"));
        newRung.getCoils().add(new LadderElement(LadderElement.Kind.NORMAL_COIL, "DO0"));

        page.getRungs().add(insertIdx, newRung);
        renumberRungOrders(page);
        selectedRungIndex = insertIdx;
        selectedElement = null;
        commitEdit("Add Ladder Rung", before);
    }

    public void insertContactOnSelectedRung(LadderElement.Kind contactKind) {
        Page page = getActivePage();
        if (page == null) return;
        ensureAtLeastOneRung(page);
        Page before = page.copy();

        LadderRung rung = page.getRungs().get(getSelectedRungIndex());
        LadderElement contact = new LadderElement(contactKind, "DI0");

        if (selectedBranch != null && rung.getParallelBranches().contains(selectedBranch)) {
            selectedBranch.getElements().add(contact);
        } else {
            int idx = rung.getMainElements().size();
            if (selectedElement != null) {
                int selPos = rung.getMainElements().indexOf(selectedElement);
                if (selPos >= 0) idx = selPos + 1;
            }
            rung.getMainElements().add(idx, contact);
        }
        selectedElement = contact;
        commitEdit("Insert " + contactKind.getDisplayName(), before);
    }

    public void insertCoilOnSelectedRung(LadderElement.Kind coilKind) {
        Page page = getActivePage();
        if (page == null) return;
        ensureAtLeastOneRung(page);
        Page before = page.copy();

        LadderRung rung = page.getRungs().get(getSelectedRungIndex());
        LadderElement coil;
        if (coilKind == LadderElement.Kind.ANALOG_COIL) {
            coil = new LadderElement(LadderElement.Kind.ANALOG_COIL, "AI0", "AO0");
        } else {
            String defaultOp = "DO" + rung.getCoils().size();
            coil = new LadderElement(coilKind, defaultOp);
        }
        rung.getCoils().add(coil);
        selectedElement = coil;
        commitEdit("Insert " + coilKind.getDisplayName(), before);
    }

    public void addParallelBranchOnSelectedRung() {
        Page page = getActivePage();
        if (page == null) return;
        ensureAtLeastOneRung(page);
        Page before = page.copy();

        LadderRung rung = page.getRungs().get(getSelectedRungIndex());
        int startCol = 0;
        int endCol = Math.max(0, rung.getMainElements().size() - 1);
        if (selectedElement != null) {
            int selIdx = rung.getMainElements().indexOf(selectedElement);
            if (selIdx >= 0) {
                startCol = selIdx;
                endCol = selIdx;
            }
        }
        LadderBranch branch = new LadderBranch(startCol, endCol);
        LadderElement brContact = new LadderElement(LadderElement.Kind.NO_CONTACT, "M0");
        branch.getElements().add(brContact);
        rung.getParallelBranches().add(branch);
        selectedBranch = branch;
        selectedElement = brContact;
        commitEdit("Add Parallel Branch", before);
    }

    public void insertFunctionBlockOnSelectedRung(BlockDefinition def) {
        if (def == null) return;
        Page page = getActivePage();
        if (page == null) return;

        // Only Ladder elements are permitted in LD pages (no FBD or Macro blocks)
        if (!def.getId().startsWith("Ladder.")) {
            return;
        }

        if (def.getId().startsWith("Ladder.Contact.")) {
            switch (def.getId()) {
                case "Ladder.Contact.NO" -> insertContactOnSelectedRung(LadderElement.Kind.NO_CONTACT);
                case "Ladder.Contact.NC" -> insertContactOnSelectedRung(LadderElement.Kind.NC_CONTACT);
                case "Ladder.Contact.POS" -> insertContactOnSelectedRung(LadderElement.Kind.POS_EDGE_CONTACT);
                case "Ladder.Contact.NEG" -> insertContactOnSelectedRung(LadderElement.Kind.NEG_EDGE_CONTACT);
            }
            return;
        }
        if (def.getId().startsWith("Ladder.Coil.")) {
            switch (def.getId()) {
                case "Ladder.Coil.NORMAL" -> insertCoilOnSelectedRung(LadderElement.Kind.NORMAL_COIL);
                case "Ladder.Coil.NEGATED" -> insertCoilOnSelectedRung(LadderElement.Kind.NEGATED_COIL);
                case "Ladder.Coil.SET" -> insertCoilOnSelectedRung(LadderElement.Kind.SET_COIL);
                case "Ladder.Coil.RESET" -> insertCoilOnSelectedRung(LadderElement.Kind.RESET_COIL);
                case "Ladder.Coil.POS" -> insertCoilOnSelectedRung(LadderElement.Kind.POS_EDGE_COIL);
                case "Ladder.Coil.NEG" -> insertCoilOnSelectedRung(LadderElement.Kind.NEG_EDGE_COIL);
                case "Ladder.Coil.ANALOG" -> insertCoilOnSelectedRung(LadderElement.Kind.ANALOG_COIL);
                case "Ladder.Coil.WRITE_REG" -> {
                    ensureAtLeastOneRung(page);
                    Page before = page.copy();
                    LadderRung rung = page.getRungs().get(getSelectedRungIndex());
                    LadderElement coil = new LadderElement(LadderElement.Kind.ANALOG_COIL, "1", "REG_0");
                    rung.getCoils().add(coil);
                    selectedElement = coil;
                    commitEdit("Insert Write Register Coil", before);
                }
            }
        }
    }

    public void deleteSelection() {
        Page page = getActivePage();
        if (page == null || page.getRungs().isEmpty()) return;
        Page before = page.copy();

        int rIdx = getSelectedRungIndex();
        LadderRung rung = page.getRungs().get(rIdx);

        if (selectedElement != null) {
            boolean removed = rung.getMainElements().remove(selectedElement)
                    || rung.getCoils().remove(selectedElement);
            if (!removed) {
                for (var it = rung.getParallelBranches().iterator(); it.hasNext(); ) {
                    LadderBranch br = it.next();
                    if (br.getElements().remove(selectedElement)) {
                        removed = true;
                        if (br.getElements().isEmpty()) {
                            it.remove();
                        }
                        break;
                    }
                }
            }
            selectedElement = null;
            if (removed) {
                commitEdit("Delete Ladder Element", before);
                return;
            }
        }

        if (selectedBranch != null && rung.getParallelBranches().remove(selectedBranch)) {
            selectedBranch = null;
            commitEdit("Delete Parallel Branch", before);
            return;
        }

        if (page.getRungs().size() > 1) {
            page.getRungs().remove(rIdx);
            selectedRungIndex = Math.clamp(rIdx, 0, page.getRungs().size() - 1);
            renumberRungOrders(page);
            commitEdit("Delete Ladder Rung", before);
        } else {
            rung.getMainElements().clear();
            rung.getParallelBranches().clear();
            rung.getCoils().clear();
            commitEdit("Clear Ladder Rung", before);
        }
    }

    public void duplicateSelectedRung() {
        Page page = getActivePage();
        if (page == null || page.getRungs().isEmpty()) return;
        Page before = page.copy();

        int rIdx = getSelectedRungIndex();
        LadderRung copy = page.getRungs().get(rIdx).copy();
        copy.setComment(copy.getComment() + " (Copy)");
        for (LadderElement el : copy.getMainElements()) {
            if (el.getKind() == LadderElement.Kind.FUNCTION_BLOCK && el.getBlockInstance() != null) {
                String newName = page.nextUniqueBlockName(el.getBlockInstance().getTypeName());
                el.getBlockInstance().setName(newName);
                el.setOperand(newName);
            }
        }
        page.getRungs().add(rIdx + 1, copy);
        renumberRungOrders(page);
        selectedRungIndex = rIdx + 1;
        commitEdit("Duplicate Ladder Rung", before);
    }

    public void moveSelectedRung(int delta) {
        Page page = getActivePage();
        if (page == null || page.getRungs().size() <= 1) return;
        int rIdx = getSelectedRungIndex();
        int target = rIdx + delta;
        if (target < 0 || target >= page.getRungs().size()) return;

        Page before = page.copy();
        LadderRung r = page.getRungs().remove(rIdx);
        page.getRungs().add(target, r);
        renumberRungOrders(page);
        selectedRungIndex = target;
        commitEdit("Move Ladder Rung", before);
    }

    private void ensureAtLeastOneRung(Page page) {
        if (page.getRungs().isEmpty()) {
            int tid = project != null ? project.getDefaultThreadId() : 2;
            page.getRungs().add(new LadderRung(tid, 10, "Rung 1"));
            selectedRungIndex = 0;
        }
    }

    private void renumberRungOrders(Page page) {
        int ord = 10;
        for (LadderRung r : page.getRungs()) {
            r.setOrder(ord);
            ord += 10;
            for (LadderElement el : r.getMainElements()) {
                if (el.getKind() == LadderElement.Kind.FUNCTION_BLOCK && el.getBlockInstance() != null) {
                    el.getBlockInstance().setThreadId(r.getThreadId());
                    el.getBlockInstance().setOrder(ord);
                    ord += 5;
                }
            }
        }
    }

    private void commitEdit(String actionName, Page snapshotBefore) {
        if (simEngine.getState() != SimulationEngine.State.STOPPED) {
            simEngine.stopSimulation(project);
        }
        Page after = getActivePage().copy();
        editListener.onAfterPageEdit(actionName, snapshotBefore, after);
        updatePreferredSizeFromRungs();
        revalidate();
        repaint();
    }

    // =========================================================================
    // Mouse & Drag-and-Drop Handlers
    // =========================================================================

    private void installMouseHandlers() {
        MouseAdapter ma = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                requestFocusInWindow();

                if (SwingUtilities.isMiddleMouseButton(e)) {
                    panStartScreen = e.getLocationOnScreen();
                    if (getParent() instanceof JViewport vp) {
                        panStartViewPos = vp.getViewPosition();
                    }
                    return;
                }

                if (isPopupTrigger(e)) {
                    lastPopupTimestamp = System.currentTimeMillis();
                    handlePopupAt(e);
                    return;
                }

                Point logicPt = toLogicalPoint(e.getPoint());

                // 1. Check Pin Operand boxes on Function Blocks
                for (PinOperandHitBox phb : pinHitBoxes) {
                    if (phb.bounds().contains(logicPt)) {
                        selectedRungIndex = phb.rungIndex();
                        if (e.getClickCount() == 2) {
                            promptEditPinOperand(phb);
                        } else if (e.getClickCount() == 1 && simEngine.getState() == SimulationEngine.State.RUNNING && phb.isInput()) {
                            String op = phb.block().getInputs().get(phb.pinIndex()).getConstantValue();
                            if (op != null && (op.toUpperCase().startsWith("AI") || op.toUpperCase().startsWith("REG_"))) {
                                showLiveAnalogAdjusterDialog(op);
                            }
                        }
                        repaint();
                        return;
                    }
                }

                // 2. Check Ladder Elements (Contacts, Coils, Analog Coils, Function Blocks)
                for (ElementHitBox ehb : elementHitBoxes) {
                    if (ehb.bounds().contains(logicPt)) {
                        selectedRungIndex = ehb.rungIndex();
                        selectedElement = ehb.element();
                        selectedBranch = ehb.parentBranch();
                        repaint();

                        if (e.getClickCount() == 2) {
                            openElementEditor(ehb.element());
                        } else if (e.getClickCount() == 1 && simEngine.getState() == SimulationEngine.State.RUNNING) {
                            if (ehb.element().getKind().isContact()) {
                                simEngine.toggleBooleanOperand(project, ehb.element().getOperand());
                                repaint();
                            } else if (ehb.element().getKind() == LadderElement.Kind.ANALOG_COIL) {
                                String src = ehb.element().getSourceOperand();
                                if (src.toUpperCase().startsWith("AI") || src.toUpperCase().startsWith("REG_")) {
                                    showLiveAnalogAdjusterDialog(src);
                                }
                            }
                        }
                        return;
                    }
                }

                // 3. Check Rung headers / bounds
                for (RungHitBox rhb : rungHitBoxes) {
                    if (rhb.bounds().contains(logicPt)) {
                        selectedRungIndex = rhb.rungIndex();
                        selectedElement = null;
                        selectedBranch = null;
                        repaint();

                        if (e.getClickCount() == 2 && rhb.headerBounds().contains(logicPt)) {
                            promptEditRungProperties(rhb.rungIndex());
                        }
                        return;
                    }
                }
            }

            private void handlePopupAt(MouseEvent e) {
                Point logicPt = toLogicalPoint(e.getPoint());
                for (PinOperandHitBox phb : pinHitBoxes) {
                    if (phb.bounds().contains(logicPt)) {
                        selectedRungIndex = phb.rungIndex();
                        showPinContextMenu(phb, e.getX(), e.getY());
                        repaint();
                        return;
                    }
                }
                for (ElementHitBox ehb : elementHitBoxes) {
                    if (ehb.bounds().contains(logicPt)) {
                        selectedRungIndex = ehb.rungIndex();
                        selectedElement = ehb.element();
                        selectedBranch = ehb.parentBranch();
                        repaint();
                        showElementContextMenu(ehb, e.getX(), e.getY());
                        return;
                    }
                }
                for (RungHitBox rhb : rungHitBoxes) {
                    if (rhb.bounds().contains(logicPt)) {
                        selectedRungIndex = rhb.rungIndex();
                        selectedElement = null;
                        selectedBranch = null;
                        repaint();
                        showRungContextMenu(rhb.rungIndex(), e.getX(), e.getY());
                        return;
                    }
                }
                showRungContextMenu(getSelectedRungIndex(), e.getX(), e.getY());
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (SwingUtilities.isMiddleMouseButton(e) && panStartScreen != null && panStartViewPos != null) {
                    if (getParent() instanceof JViewport vp) {
                        int dx = panStartScreen.x - e.getLocationOnScreen().x;
                        int dy = panStartScreen.y - e.getLocationOnScreen().y;
                        Point np = new Point(
                                Math.max(0, panStartViewPos.x + dx),
                                Math.max(0, panStartViewPos.y + dy)
                        );
                        vp.setViewPosition(np);
                    }
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                panStartScreen = null;
                panStartViewPos = null;
                if (isPopupTrigger(e)) {
                    long now = System.currentTimeMillis();
                    if (now - lastPopupTimestamp > 250) {
                        lastPopupTimestamp = now;
                        handlePopupAt(e);
                    }
                }
            }

            @Override
            public void mouseWheelMoved(MouseWheelEvent e) {
                if (e.isControlDown() || e.isMetaDown()) {
                    if (e.getWheelRotation() < 0) {
                        zoomIn();
                    } else {
                        zoomOut();
                    }
                    e.consume();
                } else if (getParent() != null) {
                    getParent().dispatchEvent(SwingUtilities.convertMouseEvent(LadderCanvas.this, e, getParent()));
                }
            }
        };

        addMouseListener(ma);
        addMouseMotionListener(ma);
        addMouseWheelListener(ma);
    }

    private void installDropTarget() {
        setTransferHandler(new TransferHandler() {
            @Override
            public boolean canImport(TransferSupport support) {
                return support.isDataFlavorSupported(DataFlavor.stringFlavor);
            }

            @Override
            public boolean importData(TransferSupport support) {
                if (!canImport(support)) return false;
                try {
                    String defId = (String) support.getTransferable().getTransferData(DataFlavor.stringFlavor);
                    BlockDefinition def = BlockCatalog.getById(defId);
                    if (def == null) return false;

                    Point dropPt = toLogicalPoint(support.getDropLocation().getDropPoint());
                    for (RungHitBox rhb : rungHitBoxes) {
                        if (rhb.bounds().contains(dropPt)) {
                            selectedRungIndex = rhb.rungIndex();
                            break;
                        }
                    }
                    insertFunctionBlockOnSelectedRung(def);
                    return true;
                } catch (Exception ex) {
                    return false;
                }
            }
        });
    }

    // =========================================================================
    // Element & Rung Editors and Context Menus
    // =========================================================================

    private void openElementEditor(LadderElement el) {
        if (el == null) return;
        Page page = getActivePage();
        if (page == null) return;

        if (el.getKind() == LadderElement.Kind.FUNCTION_BLOCK && el.getBlockInstance() != null) {
            Page before = page.copy();
            Frame owner = (Frame) SwingUtilities.getWindowAncestor(this);
            ModulePropertiesDialog dlg = new ModulePropertiesDialog(owner, page, el.getBlockInstance());
            dlg.setVisible(true);
            if (dlg.isConfirmed()) {
                el.setOperand(el.getBlockInstance().getName());
                commitEdit("Edit Function Block Properties", before);
            }
            return;
        }

        if (el.getKind() == LadderElement.Kind.ANALOG_COIL) {
            openAnalogCoilEditor(el);
            return;
        }

        Frame owner = (Frame) SwingUtilities.getWindowAncestor(this);
        OperandPickerDialog dlg = new OperandPickerDialog(
                owner,
                project,
                "Assign Operand - " + el.getKind().getDisplayName(),
                el.getOperand(),
                el.getComment(),
                true
        );
        dlg.setVisible(true);
        if (dlg.isConfirmed()) {
            Page before = page.copy();
            el.setOperand(dlg.getSelectedOperand());
            el.setComment(dlg.getSelectedComment());
            commitEdit("Assign Ladder Operand (" + el.getOperand() + ")", before);
        }
    }

    private void openAnalogCoilEditor(LadderElement el) {
        Page page = getActivePage();
        if (page == null) return;
        Frame owner = (Frame) SwingUtilities.getWindowAncestor(this);

        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 6, 4, 6);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        JTextField srcField = new JTextField(el.getSourceOperand(), 16);
        JButton pickSrcBtn = new JButton("Pick...");
        pickSrcBtn.addActionListener(e -> {
            OperandPickerDialog picker = new OperandPickerDialog(owner, project, "Select Source Analog Input / Register / Pin", srcField.getText(), "", false);
            picker.setVisible(true);
            if (picker.isConfirmed()) {
                srcField.setText(picker.getSelectedOperand());
            }
        });

        JTextField dstField = new JTextField(el.getOperand(), 16);
        JButton pickDstBtn = new JButton("Pick...");
        pickDstBtn.addActionListener(e -> {
            OperandPickerDialog picker = new OperandPickerDialog(owner, project, "Select Target Analog Output (AO / PWM / REG)", dstField.getText(), "", false);
            picker.setVisible(true);
            if (picker.isConfirmed()) {
                dstField.setText(picker.getSelectedOperand());
            }
        });

        JTextField commentField = new JTextField(el.getComment(), 22);

        gbc.gridx = 0; gbc.gridy = 0;
        panel.add(new JLabel("Source Operand / Value (e.g. AI0, Scale1.Y, 1024):"), gbc);
        gbc.gridx = 1;
        panel.add(srcField, gbc);
        gbc.gridx = 2;
        panel.add(pickSrcBtn, gbc);

        gbc.gridx = 0; gbc.gridy = 1;
        panel.add(new JLabel("Target Analog Output / Register (e.g. AO0, PWM0, REG_0):"), gbc);
        gbc.gridx = 1;
        panel.add(dstField, gbc);
        gbc.gridx = 2;
        panel.add(pickDstBtn, gbc);

        gbc.gridx = 0; gbc.gridy = 2;
        panel.add(new JLabel("Comment:"), gbc);
        gbc.gridx = 1; gbc.gridwidth = 2;
        panel.add(commentField, gbc);

        int res = JOptionPane.showConfirmDialog(this, panel, "Configure Analog Output / Move (--[AO :=]--)",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (res == JOptionPane.OK_OPTION) {
            Page before = page.copy();
            el.setSourceOperand(srcField.getText().trim());
            el.setOperand(dstField.getText().trim());
            el.setComment(commentField.getText().trim());
            commitEdit("Configure Analog Output (" + el.getSourceOperand() + " -> " + el.getOperand() + ")", before);
        }
    }

    private void promptEditPinOperand(PinOperandHitBox phb) {
        Page page = getActivePage();
        if (page == null) return;
        PinInstance pin = phb.isInput()
                ? phb.block().getInputs().get(phb.pinIndex())
                : phb.block().getOutputs().get(phb.pinIndex());
        if (phb.isInput() && pin.getShortName().equalsIgnoreCase("En")) return;

        Frame owner = (Frame) SwingUtilities.getWindowAncestor(this);
        String title = phb.isInput()
                ? ("Bind Input Pin Operand / Constant - " + phb.block().getName() + "." + pin.getShortName())
                : ("Bind Output Pin Destination (AO / PWM / REG) - " + phb.block().getName() + "." + pin.getShortName());
        OperandPickerDialog dlg = new OperandPickerDialog(
                owner,
                project,
                title,
                pin.getConstantValue(),
                "",
                false
        );
        dlg.setVisible(true);
        if (dlg.isConfirmed()) {
            Page before = page.copy();
            pin.setConstantValue(dlg.getSelectedOperand());
            commitEdit("Bind Pin " + phb.block().getName() + "." + pin.getShortName(), before);
        }
    }

    private void showLiveAnalogAdjusterDialog(String operandName) {
        Frame owner = (Frame) SwingUtilities.getWindowAncestor(this);
        JDialog dlg = new JDialog(owner, "Live Analog / Register Value: " + operandName, false);

        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));

        double currentVal = simEngine.getOperandValue(project, operandName);
        int initSlider = Math.clamp((int) Math.round(currentVal), 0, 4095);

        JLabel title = new JLabel("Adjust " + operandName + " (12-bit ADC range 0..4095 or custom numeric):");
        title.setFont(AppFonts.ui(Font.BOLD, 11));
        root.add(title, BorderLayout.NORTH);

        JSlider slider = new JSlider(0, 4095, initSlider);
        slider.setMajorTickSpacing(1024);
        slider.setMinorTickSpacing(256);
        slider.setPaintTicks(true);
        slider.setPaintLabels(true);

        JSpinner spinner = new JSpinner(new SpinnerNumberModel(currentVal, -1_000_000.0, 1_000_000.0, 10.0));
        spinner.setPreferredSize(new Dimension(110, 26));

        slider.addChangeListener(e -> {
            int v = slider.getValue();
            spinner.setValue((double) v);
            simEngine.setOperandValue(operandName, v);
            repaint();
        });

        spinner.addChangeListener(e -> {
            double v = ((Number) spinner.getValue()).doubleValue();
            simEngine.setOperandValue(operandName, v);
            if (v >= 0 && v <= 4095 && slider.getValue() != (int) Math.round(v)) {
                slider.setValue((int) Math.round(v));
            }
            repaint();
        });

        JPanel center = new JPanel(new BorderLayout(8, 4));
        center.add(slider, BorderLayout.CENTER);
        center.add(spinner, BorderLayout.EAST);
        root.add(center, BorderLayout.CENTER);

        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton closeBtn = new JButton("Close");
        closeBtn.addActionListener(e -> dlg.dispose());
        bottom.add(closeBtn);
        root.add(bottom, BorderLayout.SOUTH);

        dlg.setContentPane(root);
        dlg.pack();
        dlg.setLocationRelativeTo(this);
        dlg.setVisible(true);
    }

    private void promptEditRungProperties(int rungIdx) {
        Page page = getActivePage();
        if (page == null || rungIdx < 0 || rungIdx >= page.getRungs().size()) return;
        LadderRung rung = page.getRungs().get(rungIdx);

        JPanel p = new JPanel(new GridLayout(3, 2, 6, 6));
        JTextField commentField = new JTextField(rung.getComment(), 24);
        JComboBox<PlcThread> threadBox = new JComboBox<>(PlcThread.values());
        threadBox.setSelectedItem(PlcThread.fromId(rung.getThreadId()));
        JSpinner orderSpin = new JSpinner(new SpinnerNumberModel(rung.getOrder(), 1, 9999, 10));

        p.add(new JLabel("Rung Comment / Title:"));
        p.add(commentField);
        p.add(new JLabel("Execution Thread (T:):"));
        p.add(threadBox);
        p.add(new JLabel("Execution Order (O:):"));
        p.add(orderSpin);

        int res = JOptionPane.showConfirmDialog(this, p, "Rung " + (rungIdx + 1) + " Properties",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (res == JOptionPane.OK_OPTION) {
            Page before = page.copy();
            rung.setComment(commentField.getText().trim());
            PlcThread selT = (PlcThread) threadBox.getSelectedItem();
            if (selT != null) {
                rung.setThreadId(selT.getId());
            }
            rung.setOrder((Integer) orderSpin.getValue());
            commitEdit("Edit Rung Properties", before);
        }
    }

    private void showPinContextMenu(PinOperandHitBox phb, int x, int y) {
        JPopupMenu menu = new JPopupMenu();
        PinInstance pin = phb.isInput()
                ? phb.block().getInputs().get(phb.pinIndex())
                : phb.block().getOutputs().get(phb.pinIndex());
        String pinRef = phb.block().getName() + "." + pin.getShortName();

        if (!pin.getShortName().equalsIgnoreCase("En")) {
            JMenuItem bindItem = new JMenuItem(phb.isInput()
                    ? "Bind Input Operand / Constant (" + pinRef + ")..."
                    : "Bind Output to AO / PWM / Register (" + pinRef + ")...");
            bindItem.addActionListener(e -> promptEditPinOperand(phb));
            menu.add(bindItem);

            String boundOp = pin.getConstantValue();
            if (boundOp != null && !boundOp.isBlank() && !boundOp.equals("0")) {
                JMenuItem adjustItem = new JMenuItem("Adjust Live Value of '" + boundOp + "' (0..4095)...");
                adjustItem.addActionListener(e -> showLiveAnalogAdjusterDialog(boundOp));
                menu.add(adjustItem);
            }
            menu.addSeparator();
        }

        boolean onScope = editListener.isPinOnScope(pinRef);
        JMenuItem scopeItem = new JMenuItem(onScope
                ? "Remove " + pinRef + " from Scope"
                : "Add " + pinRef + " to Scope");
        scopeItem.addActionListener(e -> editListener.onTogglePinOnScope(pinRef));
        menu.add(scopeItem);

        menu.show(this, x, y);
    }

    private void showElementContextMenu(ElementHitBox ehb, int x, int y) {
        JPopupMenu menu = new JPopupMenu();
        LadderElement el = ehb.element();

        JMenuItem editItem = new JMenuItem(el.getKind() == LadderElement.Kind.FUNCTION_BLOCK
                ? "Block Properties... (Double-Click)"
                : (el.getKind() == LadderElement.Kind.ANALOG_COIL
                ? "Configure Analog Output / Move... (Double-Click)"
                : "Assign Operand / Tag... (Double-Click)"));
        editItem.addActionListener(e -> openElementEditor(el));
        menu.add(editItem);

        if (el.getKind().isContact()) {
            JMenu typeMenu = new JMenu("Change Contact Type");
            for (LadderElement.Kind k : new LadderElement.Kind[]{
                    LadderElement.Kind.NO_CONTACT,
                    LadderElement.Kind.NC_CONTACT,
                    LadderElement.Kind.POS_EDGE_CONTACT,
                    LadderElement.Kind.NEG_EDGE_CONTACT
            }) {
                JMenuItem item = new JMenuItem(k.getDisplayName() + " (" + k.getSymbolText() + ")");
                item.addActionListener(ev -> {
                    Page before = getActivePage().copy();
                    el.setKind(k);
                    commitEdit("Change Contact Type", before);
                });
                typeMenu.add(item);
            }
            menu.add(typeMenu);

            JMenuItem toggleStateItem = new JMenuItem("Toggle Operand '" + el.getOperand() + "' (0 / 1)");
            toggleStateItem.addActionListener(e -> {
                simEngine.toggleBooleanOperand(project, el.getOperand());
                repaint();
            });
            menu.add(toggleStateItem);
        } else if (el.getKind().isCoil()) {
            JMenu typeMenu = new JMenu("Change Coil Type");
            for (LadderElement.Kind k : new LadderElement.Kind[]{
                    LadderElement.Kind.NORMAL_COIL,
                    LadderElement.Kind.NEGATED_COIL,
                    LadderElement.Kind.SET_COIL,
                    LadderElement.Kind.RESET_COIL,
                    LadderElement.Kind.POS_EDGE_COIL,
                    LadderElement.Kind.NEG_EDGE_COIL,
                    LadderElement.Kind.ANALOG_COIL
            }) {
                JMenuItem item = new JMenuItem(k.getDisplayName() + " (" + k.getSymbolText() + ")");
                item.addActionListener(ev -> {
                    Page before = getActivePage().copy();
                    el.setKind(k);
                    commitEdit("Change Coil Type", before);
                });
                typeMenu.add(item);
            }
            menu.add(typeMenu);

            if (el.getKind() == LadderElement.Kind.ANALOG_COIL) {
                JMenuItem adjSrcItem = new JMenuItem("Adjust Live Source '" + el.getSourceOperand() + "' Value...");
                adjSrcItem.addActionListener(e -> showLiveAnalogAdjusterDialog(el.getSourceOperand()));
                menu.add(adjSrcItem);
            }
        }

        menu.addSeparator();

        String scopeRef = el.getOperand();
        boolean onScope = editListener.isPinOnScope(scopeRef);
        JMenuItem scopeItem = new JMenuItem(onScope
                ? "Remove '" + scopeRef + "' from Scope"
                : "Add '" + scopeRef + "' to Scope");
        scopeItem.addActionListener(e -> editListener.onTogglePinOnScope(scopeRef));
        menu.add(scopeItem);

        menu.addSeparator();

        JMenuItem addBrItem = new JMenuItem("Add Parallel Branch (OR) Around Here");
        addBrItem.addActionListener(e -> addParallelBranchOnSelectedRung());
        menu.add(addBrItem);

        JMenuItem delItem = new JMenuItem("Delete Element");
        delItem.addActionListener(e -> deleteSelection());
        menu.add(delItem);

        menu.show(this, x, y);
    }

    private void showRungContextMenu(int rungIdx, int x, int y) {
        JPopupMenu menu = new JPopupMenu();

        JMenuItem propsItem = new JMenuItem("Rung Properties (Comment / Thread / Order)...");
        propsItem.addActionListener(e -> promptEditRungProperties(rungIdx));
        menu.add(propsItem);

        menu.addSeparator();

        JMenuItem addNoItem = new JMenuItem("Insert NO Contact (--| |--)");
        addNoItem.addActionListener(e -> insertContactOnSelectedRung(LadderElement.Kind.NO_CONTACT));
        menu.add(addNoItem);

        JMenuItem addNcItem = new JMenuItem("Insert NC Contact (--|/|--)" );
        addNcItem.addActionListener(e -> insertContactOnSelectedRung(LadderElement.Kind.NC_CONTACT));
        menu.add(addNcItem);

        JMenuItem addBranchItem = new JMenuItem("Add Parallel OR Branch");
        addBranchItem.addActionListener(e -> addParallelBranchOnSelectedRung());
        menu.add(addBranchItem);

        JMenuItem addCoilItem = new JMenuItem("Add Boolean Output Coil (--( )--)");
        addCoilItem.addActionListener(e -> insertCoilOnSelectedRung(LadderElement.Kind.NORMAL_COIL));
        menu.add(addCoilItem);

        JMenuItem addAnalogCoilItem = new JMenuItem("Add Analog Output / Move Coil (--[AO :=]--)");
        addAnalogCoilItem.addActionListener(e -> insertCoilOnSelectedRung(LadderElement.Kind.ANALOG_COIL));
        menu.add(addAnalogCoilItem);

        JMenuItem adjustAiItem = new JMenuItem("Adjust Live Analog Input (AI0..AI7)...");
        adjustAiItem.addActionListener(e -> showLiveAnalogAdjusterDialog("AI0"));
        menu.add(adjustAiItem);

        menu.addSeparator();

        JMenuItem newRungItem = new JMenuItem("Insert New Rung Below");
        newRungItem.addActionListener(e -> addNewRungBelowSelected());
        menu.add(newRungItem);

        JMenuItem dupRungItem = new JMenuItem("Duplicate Rung (Ctrl+D)");
        dupRungItem.addActionListener(e -> duplicateSelectedRung());
        menu.add(dupRungItem);

        JMenuItem moveUpItem = new JMenuItem("Move Rung Up");
        moveUpItem.addActionListener(e -> moveSelectedRung(-1));
        menu.add(moveUpItem);

        JMenuItem moveDownItem = new JMenuItem("Move Rung Down");
        moveDownItem.addActionListener(e -> moveSelectedRung(1));
        menu.add(moveDownItem);

        menu.addSeparator();

        JMenuItem delRungItem = new JMenuItem("Delete Rung");
        delRungItem.addActionListener(e -> {
            selectedElement = null;
            selectedBranch = null;
            deleteSelection();
        });
        menu.add(delRungItem);

        menu.show(this, x, y);
    }

    // =========================================================================
    // Zoom & View Controls
    // =========================================================================

    public void zoomIn() {
        setZoom(zoom * 1.15);
    }

    public void zoomOut() {
        setZoom(zoom / 1.15);
    }

    public void zoom100() {
        setZoom(1.0);
    }

    public void fitToScreen() {
        setZoom(1.0);
    }

    private void setZoom(double newZoom) {
        this.zoom = Math.clamp(newZoom, 0.35, 3.0);
        updatePreferredSizeFromRungs();
        revalidate();
        repaint();
    }

    public void setShowGrid(boolean showGrid) {
        this.showGrid = showGrid;
        repaint();
    }

    public void setShowExecutionInfo(boolean showExecutionInfo) {
        this.showExecutionInfo = showExecutionInfo;
        repaint();
    }

    private Point toLogicalPoint(Point screenPt) {
        return new Point((int) Math.round(screenPt.x / zoom), (int) Math.round(screenPt.y / zoom));
    }

    private void updatePreferredSizeFromRungs() {
        Page page = getActivePage();
        int rungCount = (page != null) ? page.getRungs().size() : 1;
        int w = (int) Math.round(1250 * zoom);
        int h = (int) Math.round(Math.max(700, 120 + rungCount * 195) * zoom);
        setPreferredSize(new Dimension(w, h));
    }

    // =========================================================================
    // Rendering (Zero geometric AA, Text-Only AA ON)
    // =========================================================================

    private boolean exporting = false;

    private int computeExportRightRailX(Page page) {
        int maxContentX = 460;
        int leftRailX = 48;
        int startColX = leftRailX + 28;
        for (LadderRung rung : page.getRungs()) {
            int curX = startColX;
            for (LadderElement el : rung.getMainElements()) {
                int elW = (el.getKind() == LadderElement.Kind.FUNCTION_BLOCK) ? 186 : 88;
                curX += 16 + elW + 16;
            }
            for (LadderBranch br : rung.getParallelBranches()) {
                int brX = startColX + 16;
                for (LadderElement el : br.getElements()) {
                    int elW = (el.getKind() == LadderElement.Kind.FUNCTION_BLOCK) ? 186 : 88;
                    brX += 16 + elW + 16;
                }
                curX = Math.max(curX, brX + 10);
            }
            maxContentX = Math.max(maxContentX, curX + 190);
        }
        return Math.max(640, maxContentX);
    }

    /**
     * Computes tight pixel bounds for exporting a Ladder {@code page}.
     */
    public Rectangle computeExportBounds(Page page) {
        if (page == null || page.getRungs().isEmpty()) {
            return new Rectangle(0, 0, 680, 180);
        }
        int rightRailX = computeExportRightRailX(page);
        int curY = 20;
        for (LadderRung rung : page.getRungs()) {
            curY += computeRungHeight(rung) + 12;
        }
        int railBottom = curY + 14;
        return new Rectangle(8, 0, rightRailX + 36, railBottom + 14);
    }

    /**
     * Renders a Ladder {@code page} onto {@code g2} for SVG/PDF/PNG export (no selection highlights).
     */
    public void paintPageForExport(Graphics2D g2, Page page) {
        if (page == null) return;
        boolean prevExporting = this.exporting;
        this.exporting = true;
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            int leftRailX = 48;
            int rightRailX = computeExportRightRailX(page);
            int curY = 20;
            boolean liveSim = simEngine.isShowingLiveOutputs();

            for (int ri = 0; ri < page.getRungs().size(); ri++) {
                LadderRung rung = page.getRungs().get(ri);
                int rungHeight = computeRungHeight(rung);
                drawRung(g2, ri, rung, leftRailX, rightRailX, curY, rungHeight, liveSim);
                curY += rungHeight + 12;
            }

            int railBottom = Math.max(curY + 14, 120);
            Stroke oldStroke = g2.getStroke();
            g2.setStroke(new BasicStroke(3f));
            g2.setColor(liveSim ? COLOR_POWER_ON : COLOR_RAIL);
            g2.drawLine(leftRailX, 14, leftRailX, railBottom);
            g2.setColor(COLOR_RAIL);
            g2.drawLine(rightRailX, 14, rightRailX, railBottom);
            g2.setStroke(oldStroke);

            g2.setFont(AppFonts.ui(Font.BOLD, 11));
            g2.setColor(liveSim ? COLOR_POWER_ON : COLOR_RAIL);
            g2.drawString("L+ (24VDC)", leftRailX - 24, 12);
            g2.setColor(COLOR_RAIL);
            g2.drawString("L- (0V)", rightRailX - 18, 12);
        } finally {
            this.exporting = prevExporting;
        }
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g.create();

        // Enforce strict Maraflow rendering rule: NO geometric AA, ONLY text AA
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        g2.scale(zoom, zoom);

        int viewW = (int) Math.ceil(getWidth() / zoom);
        int viewH = (int) Math.ceil(getHeight() / zoom);

        // Note: Ladder Diagrams render purely on clean rung rails without background placement grid


        rungHitBoxes.clear();
        elementHitBoxes.clear();
        pinHitBoxes.clear();

        Page page = getActivePage();
        if (page == null) {
            g2.dispose();
            return;
        }

        int leftRailX = 48;
        int rightRailX = Math.max(980, viewW - 60);
        int curY = 20;

        boolean liveSim = simEngine.isShowingLiveOutputs();

        for (int ri = 0; ri < page.getRungs().size(); ri++) {
            LadderRung rung = page.getRungs().get(ri);
            int rungHeight = computeRungHeight(rung);
            Rectangle rungBounds = new Rectangle(12, curY, rightRailX - 12 + 24, rungHeight);
            Rectangle headerBounds = new Rectangle(leftRailX, curY, rightRailX - leftRailX, 22);
            rungHitBoxes.add(new RungHitBox(ri, rungBounds, headerBounds));

            drawRung(g2, ri, rung, leftRailX, rightRailX, curY, rungHeight, liveSim);
            curY += rungHeight + 12;
        }

        // Draw Left Power Rail (L+) and Right Return Rail (L-)
        int railBottom = Math.max(curY + 20, viewH - 20);
        Stroke oldStroke = g2.getStroke();
        g2.setStroke(new BasicStroke(3f));
        g2.setColor(liveSim ? COLOR_POWER_ON : COLOR_RAIL);
        g2.drawLine(leftRailX, 14, leftRailX, railBottom);

        g2.setColor(COLOR_RAIL);
        g2.drawLine(rightRailX, 14, rightRailX, railBottom);
        g2.setStroke(oldStroke);

        g2.setFont(AppFonts.ui(Font.BOLD, 11));
        g2.setColor(liveSim ? COLOR_POWER_ON : COLOR_RAIL);
        g2.drawString("L+ (24VDC)", leftRailX - 24, 12);
        g2.setColor(COLOR_RAIL);
        g2.drawString("L- (0V)", rightRailX - 18, 12);

        g2.dispose();
    }

    private int computeRungHeight(LadderRung rung) {
        int baseH = 98;
        for (LadderElement el : rung.getMainElements()) {
            if (el.getKind() == LadderElement.Kind.FUNCTION_BLOCK && el.getBlockInstance() != null) {
                int pinRows = Math.max(el.getBlockInstance().getInputs().size(), el.getBlockInstance().getOutputs().size());
                int fbH = 54 + pinRows * 18;
                baseH = Math.max(baseH, fbH + 38);
            }
        }
        int branchExtra = rung.getParallelBranches().size() * 58;
        int coilExtra = Math.max(0, rung.getCoils().size() - 1) * 48;
        return Math.max(baseH + branchExtra, 70 + coilExtra);
    }

    private void drawRung(Graphics2D g2, int ri, LadderRung rung, int leftRailX, int rightRailX,
                          int topY, int rungHeight, boolean liveSim) {
        boolean isRungSelected = !exporting && (ri == getSelectedRungIndex());

        if (isRungSelected) {
            g2.setColor(new Color(0xF6, 0xFA, 0xFF));
            g2.fillRect(leftRailX + 1, topY, rightRailX - leftRailX - 1, rungHeight);
        }

        g2.setColor(isRungSelected ? COLOR_RUNG_SELECTED_BG : COLOR_RUNG_HEADER_BG);
        g2.fillRect(leftRailX + 1, topY, rightRailX - leftRailX - 1, 22);
        g2.setColor(isRungSelected ? COLOR_ELEMENT_SELECTED : new Color(0xC5, 0xCE, 0xD8));
        g2.drawRect(leftRailX, topY, rightRailX - leftRailX, 22);

        g2.setFont(AppFonts.ui(Font.BOLD, 11));
        g2.setColor(isRungSelected ? COLOR_ELEMENT_SELECTED : new Color(0x44, 0x55, 0x66));
        String numStr = String.format(Locale.US, "%03d", ri + 1);
        g2.drawString(numStr, 14, topY + 15);

        StringBuilder hdr = new StringBuilder("Rung ").append(ri + 1);
        if (showExecutionInfo) {
            hdr.append("   [T:").append(rung.getThreadId()).append(" | O:").append(rung.getOrder()).append("]");
        }
        if (!rung.getComment().isBlank()) {
            hdr.append("   // ").append(rung.getComment());
        }
        g2.setFont(AppFonts.ui(Font.BOLD, 11));
        g2.setColor(new Color(0x1E, 0x29, 0x3B));
        g2.drawString(hdr.toString(), leftRailX + 8, topY + 15);

        int wireY = topY + 56;
        int startColX = leftRailX + 28;

        drawPowerLine(g2, leftRailX, wireY, startColX, wireY, liveSim);

        List<LadderElement> mainEls = rung.getMainElements();
        int[] colStartX = new int[mainEls.size() + 1];
        int[] colEndX = new int[mainEls.size() + 1];
        int curX = startColX;

        for (int col = 0; col < mainEls.size(); col++) {
            LadderElement el = mainEls.get(col);
            colStartX[col] = curX;
            int elWidth = (el.getKind() == LadderElement.Kind.FUNCTION_BLOCK) ? 186 : 88;

            boolean wireInOn = liveSim && el.isEnergizedIn();
            drawPowerLine(g2, curX, wireY, curX + 16, wireY, wireInOn);

            Rectangle elRect = drawSeriesElement(g2, ri, el, null, col, curX + 16, wireY, elWidth, liveSim);
            elementHitBoxes.add(new ElementHitBox(ri, el, null, col, elRect));

            boolean wireOutOn = liveSim && el.isEnergizedOut();
            int nextX = curX + 16 + elWidth + 16;
            drawPowerLine(g2, curX + 16 + elWidth, wireY, nextX, wireY, wireOutOn);

            colEndX[col] = nextX;
            curX = nextX;
        }
        colStartX[mainEls.size()] = curX;
        colEndX[mainEls.size()] = curX;

        // Draw Parallel OR Branches below the main series line
        int branchY = wireY + 56;
        for (LadderBranch br : rung.getParallelBranches()) {
            int sCol = Math.clamp(br.getStartColumn(), 0, Math.max(0, mainEls.size()));
            int eCol = Math.clamp(br.getEndColumn(), sCol, Math.max(0, mainEls.size() - 1));

            int dropX = (mainEls.isEmpty()) ? startColX : colStartX[sCol] + 6;
            int rejoinX = (mainEls.isEmpty()) ? (startColX + 120) : (colEndX[eCol] - 6);

            boolean dropOn = liveSim && br.isBranchEnergizedIn();
            boolean rejoinOn = liveSim && br.isBranchEnergizedOut();

            drawPowerLine(g2, dropX, wireY, dropX, branchY, dropOn);

            int brCurX = dropX + 14;
            drawPowerLine(g2, dropX, branchY, brCurX, branchY, dropOn);

            for (int bi = 0; bi < br.getElements().size(); bi++) {
                LadderElement bel = br.getElements().get(bi);
                int belW = 84;
                Rectangle belRect = drawSeriesElement(g2, ri, bel, br, bi, brCurX, branchY, belW, liveSim);
                elementHitBoxes.add(new ElementHitBox(ri, bel, br, bi, belRect));
                boolean belOut = liveSim && bel.isEnergizedOut();
                drawPowerLine(g2, brCurX + belW, branchY, brCurX + belW + 14, branchY, belOut);
                brCurX += belW + 14;
            }

            rejoinX = Math.max(rejoinX, brCurX + 6);
            drawPowerLine(g2, brCurX, branchY, rejoinX, branchY, rejoinOn);
            drawPowerLine(g2, rejoinX, branchY, rejoinX, wireY, rejoinOn);

            curX = Math.max(curX, rejoinX + 10);
            branchY += 56;
        }

        // Connect from end of condition logic across to Right-Side Output Coils
        int coilStartX = rightRailX - 156;
        boolean finalRungPower = liveSim && rung.isRungOutputEnergized();
        if (curX < coilStartX) {
            drawPowerLine(g2, curX, wireY, coilStartX, wireY, finalRungPower);
        }

        // Draw Output Coils on the right side
        List<LadderElement> coils = rung.getCoils();
        if (coils.isEmpty()) {
            drawPowerLine(g2, coilStartX, wireY, rightRailX, wireY, finalRungPower);
        } else {
            for (int ci = 0; ci < coils.size(); ci++) {
                LadderElement coil = coils.get(ci);
                int cY = wireY + ci * 46;
                if (ci > 0) {
                    drawPowerLine(g2, coilStartX, wireY, coilStartX, cY, finalRungPower);
                }
                int cWidth = (coil.getKind() == LadderElement.Kind.ANALOG_COIL) ? 124 : 84;
                drawPowerLine(g2, coilStartX, cY, coilStartX + 12, cY, finalRungPower);
                Rectangle cRect = drawCoilElement(g2, coil, coilStartX + 12, cY, cWidth, liveSim);
                elementHitBoxes.add(new ElementHitBox(ri, coil, null, ci, cRect));
                drawPowerLine(g2, coilStartX + 12 + cWidth, cY, rightRailX, cY, liveSim && coil.isEnergizedOut());
            }
        }
    }

    private Rectangle drawSeriesElement(Graphics2D g2, int rungIndex, LadderElement el, LadderBranch parentBranch,
                                        int colIndex, int x, int wireY, int width, boolean liveSim) {
        if (el.getKind() == LadderElement.Kind.FUNCTION_BLOCK && el.getBlockInstance() != null) {
            return drawFunctionBlockOnRung(g2, rungIndex, el, x, wireY, width, liveSim);
        }

        int h = 42;
        int topY = wireY - h / 2;
        Rectangle bounds = new Rectangle(x, topY, width, h);

        boolean selected = !exporting && (el == selectedElement);
        boolean energized = liveSim && el.isEnergizedOut();
        boolean opHigh = liveSim && el.isOperandState();

        if (selected) {
            g2.setColor(new Color(0xE8, 0xF2, 0xFF));
            g2.fillRect(x, topY, width, h);
            g2.setColor(COLOR_ELEMENT_SELECTED);
            g2.drawRect(x, topY, width, h);
        }

        int midX = x + width / 2;
        int barLeftX = midX - 9;
        int barRightX = midX + 9;

        drawPowerLine(g2, x, wireY, barLeftX, wireY, liveSim && el.isEnergizedIn());
        drawPowerLine(g2, barRightX, wireY, x + width, wireY, energized);

        Stroke oldStroke = g2.getStroke();
        g2.setStroke(new BasicStroke(2f));
        g2.setColor(energized ? COLOR_POWER_ON : (opHigh ? new Color(0x1F, 0x61, 0x8D) : Color.BLACK));
        g2.drawLine(barLeftX, wireY - 10, barLeftX, wireY + 10);
        g2.drawLine(barRightX, wireY - 10, barRightX, wireY + 10);

        switch (el.getKind()) {
            case NC_CONTACT -> g2.drawLine(barLeftX - 2, wireY + 9, barRightX + 2, wireY - 9);
            case POS_EDGE_CONTACT -> {
                g2.setFont(AppFonts.ui(Font.BOLD, 11));
                g2.drawString("P", midX - 4, wireY + 4);
            }
            case NEG_EDGE_CONTACT -> {
                g2.setFont(AppFonts.ui(Font.BOLD, 11));
                g2.drawString("N", midX - 4, wireY + 4);
            }
            default -> {}
        }
        g2.setStroke(oldStroke);

        g2.setFont(AppFonts.ui(Font.BOLD, 11));
        FontMetrics fm = g2.getFontMetrics();
        String opLabel = el.getOperand();
        if (liveSim) {
            opLabel += opHigh ? " (=1)" : " (=0)";
        }
        int tw = fm.stringWidth(opLabel);
        g2.setColor(opHigh ? COLOR_POWER_ON : new Color(0x11, 0x11, 0x11));
        g2.drawString(opLabel, midX - tw / 2, wireY - 13);

        if (!el.getComment().isBlank()) {
            g2.setFont(AppFonts.ui(Font.PLAIN, 9));
            FontMetrics cfm = g2.getFontMetrics();
            int cw = cfm.stringWidth(el.getComment());
            g2.setColor(new Color(0x55, 0x66, 0x77));
            g2.drawString(el.getComment(), midX - cw / 2, wireY + 21);
        }

        return bounds;
    }

    private Rectangle drawCoilElement(Graphics2D g2, LadderElement coil, int x, int wireY, int width, boolean liveSim) {
        int h = 40;
        int topY = wireY - h / 2;
        Rectangle bounds = new Rectangle(x, topY, width, h);

        boolean selected = !exporting && (coil == selectedElement);
        boolean energized = liveSim && coil.isEnergizedOut();
        boolean opHigh = liveSim && coil.isOperandState();

        if (coil.getKind() == LadderElement.Kind.ANALOG_COIL) {
            return drawAnalogCoilElement(g2, coil, x, wireY, width, liveSim, selected, energized);
        }

        if (selected) {
            g2.setColor(new Color(0xE8, 0xF2, 0xFF));
            g2.fillRect(x, topY, width, h);
            g2.setColor(COLOR_ELEMENT_SELECTED);
            g2.drawRect(x, topY, width, h);
        }

        int midX = x + width / 2;
        int coilLeftX = midX - 11;
        int coilRightX = midX + 11;

        drawPowerLine(g2, x, wireY, coilLeftX, wireY, liveSim && coil.isEnergizedIn());
        drawPowerLine(g2, coilRightX, wireY, x + width, wireY, energized);

        Stroke oldStroke = g2.getStroke();
        g2.setStroke(new BasicStroke(2f));
        g2.setColor((energized || opHigh) ? COLOR_POWER_ON : Color.BLACK);

        g2.drawLine(coilLeftX + 4, wireY - 10, coilLeftX, wireY - 5);
        g2.drawLine(coilLeftX, wireY - 5, coilLeftX, wireY + 5);
        g2.drawLine(coilLeftX, wireY + 5, coilLeftX + 4, wireY + 10);

        g2.drawLine(coilRightX - 4, wireY - 10, coilRightX, wireY - 5);
        g2.drawLine(coilRightX, wireY - 5, coilRightX, wireY + 5);
        g2.drawLine(coilRightX, wireY + 5, coilRightX - 4, wireY + 10);

        String innerSym = switch (coil.getKind()) {
            case NEGATED_COIL -> "/";
            case SET_COIL -> "S";
            case RESET_COIL -> "R";
            case POS_EDGE_COIL -> "P";
            case NEG_EDGE_COIL -> "N";
            default -> "";
        };
        if (!innerSym.isEmpty()) {
            g2.setFont(AppFonts.ui(Font.BOLD, 11));
            FontMetrics sfm = g2.getFontMetrics();
            g2.drawString(innerSym, midX - sfm.stringWidth(innerSym) / 2, wireY + 4);
        }
        g2.setStroke(oldStroke);

        g2.setFont(AppFonts.ui(Font.BOLD, 11));
        FontMetrics fm = g2.getFontMetrics();
        String opLabel = coil.getOperand();
        if (liveSim) {
            opLabel += opHigh ? " (=1)" : " (=0)";
        }
        int tw = fm.stringWidth(opLabel);
        g2.setColor(opHigh ? COLOR_POWER_ON : new Color(0x11, 0x11, 0x11));
        g2.drawString(opLabel, midX - tw / 2, wireY - 13);

        if (!coil.getComment().isBlank()) {
            g2.setFont(AppFonts.ui(Font.PLAIN, 9));
            FontMetrics cfm = g2.getFontMetrics();
            int cw = cfm.stringWidth(coil.getComment());
            g2.setColor(new Color(0x55, 0x66, 0x77));
            g2.drawString(coil.getComment(), midX - cw / 2, wireY + 21);
        }

        return bounds;
    }

    private Rectangle drawAnalogCoilElement(Graphics2D g2, LadderElement coil, int x, int wireY, int width,
                                            boolean liveSim, boolean selected, boolean energized) {
        int boxW = width - 16;
        int boxH = 30;
        int boxX = x + 8;
        int boxY = wireY - boxH / 2;
        Rectangle bounds = new Rectangle(x, boxY - 6, width, boxH + 12);

        drawPowerLine(g2, x, wireY, boxX, wireY, liveSim && coil.isEnergizedIn());
        drawPowerLine(g2, boxX + boxW, wireY, x + width, wireY, energized);

        g2.setColor(energized ? new Color(0xE8, 0xF8, 0xF0) : new Color(0xF0, 0xF4, 0xF8));
        g2.fillRect(boxX, boxY, boxW, boxH);

        Stroke old = g2.getStroke();
        g2.setStroke(new BasicStroke(selected ? 2f : 1.5f));
        g2.setColor(selected ? COLOR_ELEMENT_SELECTED : (energized ? COLOR_POWER_ON : new Color(0x1F, 0x61, 0x8D)));
        g2.drawRect(boxX, boxY, boxW, boxH);
        g2.setStroke(old);

        // Top mini header inside box: WRITE REG or ANALOG OUT
        g2.setColor(energized ? COLOR_POWER_ON : new Color(0x1F, 0x61, 0x8D));
        g2.fillRect(boxX, boxY, boxW, 11);
        g2.setColor(Color.WHITE);
        g2.setFont(AppFonts.ui(Font.BOLD, 8));
        String targetOp = coil.getOperand() == null ? "" : coil.getOperand().toUpperCase();
        String hdr = (targetOp.startsWith("AO") || targetOp.startsWith("PWM")) ? "ANALOG OUT [:=]" : "WRITE REG [:=]";
        FontMetrics hfm = g2.getFontMetrics();
        g2.drawString(hdr, boxX + (boxW - hfm.stringWidth(hdr)) / 2, boxY + 9);

        // Bottom assignment expression: src -> dst (=val)
        g2.setFont(AppFonts.ui(Font.BOLD, 10));
        FontMetrics fm = g2.getFontMetrics();
        String expr = coil.getSourceOperand() + " -> " + coil.getOperand();
        g2.setColor(Color.BLACK);
        g2.drawString(expr, boxX + (boxW - fm.stringWidth(expr)) / 2, boxY + 24);

        if (liveSim) {
            String valLabel = coil.getOperand() + " = " + DataType.FLOAT.formatRuntimeValue(coil.getNumericRuntimeValue(), true);
            g2.setColor(COLOR_POWER_ON);
            g2.drawString(valLabel, boxX + (boxW - fm.stringWidth(valLabel)) / 2, boxY - 3);
        }

        return bounds;
    }

    private Rectangle drawFunctionBlockOnRung(Graphics2D g2, int rungIndex, LadderElement el,
                                              int x, int wireY, int width, boolean liveSim) {
        BlockInstance b = el.getBlockInstance();
        List<PinInstance> ins = b.getInputs();
        List<PinInstance> outs = b.getOutputs();
        int rows = Math.max(1, Math.max(ins.size(), outs.size()));
        int headerH = 26;
        int rowH = 18;
        int bodyH = rows * rowH;
        int totalH = headerH + bodyH;

        int topY = wireY - headerH - (rowH / 2);
        int blockX = x + 44;
        int blockW = width - 88;

        Rectangle bounds = new Rectangle(blockX, topY, blockW, totalH);
        boolean selected = !exporting && (el == selectedElement);

        drawPowerLine(g2, x, wireY, blockX, wireY, liveSim && el.isEnergizedIn());
        drawPowerLine(g2, blockX + blockW, wireY, x + width, wireY, liveSim && el.isEnergizedOut());

        g2.setColor(b.getHeaderColor().getColor());
        g2.fillRect(blockX, topY, blockW, headerH);
        g2.setColor(Color.BLACK);
        g2.drawRect(blockX, topY, blockW, headerH);

        g2.setColor(Color.WHITE);
        g2.setFont(AppFonts.ui(Font.BOLD, 10));
        FontMetrics hfm = g2.getFontMetrics();
        g2.drawString(b.getTypeName(), blockX + (blockW - hfm.stringWidth(b.getTypeName())) / 2, topY + 11);
        g2.drawString(b.getName(), blockX + (blockW - hfm.stringWidth(b.getName())) / 2, topY + 22);

        int bodyY = topY + headerH;
        g2.setColor(new Color(0xC3, 0xC7, 0xCB));
        g2.fillRect(blockX, bodyY, blockW, bodyH);
        g2.setColor(Color.BLACK);
        g2.drawRect(blockX, bodyY, blockW, bodyH);

        int pinColW = 26;
        // Input pins on left
        for (int i = 0; i < ins.size(); i++) {
            PinInstance pin = ins.get(i);
            int py = bodyY + i * rowH;
            g2.setColor(pin.getDataType().getPinBackground());
            g2.fillRect(blockX, py, pinColW, rowH);
            g2.setColor(Color.BLACK);
            g2.drawRect(blockX, py, pinColW, rowH);
            g2.setFont(AppFonts.ui(Font.PLAIN, 9));
            g2.drawString(pin.getShortName(), blockX + 3, py + 13);

            if (!(i == 0 && pin.getShortName().equalsIgnoreCase("En"))) {
                String opStr = pin.getConstantValue();
                if (liveSim) {
                    opStr = opStr + "=" + pin.getDataType().formatRuntimeValue(pin.getRuntimeValue(), true);
                }
                Rectangle pillRect = new Rectangle(x - 2, py + 1, blockX - x + 2, rowH - 2);
                pinHitBoxes.add(new PinOperandHitBox(rungIndex, b, i, true, pillRect));

                g2.setColor(new Color(0xFF, 0xFE, 0xE8));
                g2.fillRect(pillRect.x, pillRect.y, pillRect.width, pillRect.height);
                g2.setColor(new Color(0x66, 0x66, 0x66));
                g2.drawRect(pillRect.x, pillRect.y, pillRect.width, pillRect.height);
                g2.setColor(Color.BLACK);
                g2.drawString(opStr, pillRect.x + 2, py + 12);
            }
        }

        // Output pins on right (including clickable Output Operand pills for binding to AO0/PWM0/REG_0)
        for (int i = 0; i < outs.size(); i++) {
            PinInstance pin = outs.get(i);
            int py = bodyY + i * rowH;
            int px = blockX + blockW - pinColW;
            g2.setColor(pin.getDataType().getPinBackground());
            g2.fillRect(px, py, pinColW, rowH);
            g2.setColor(Color.BLACK);
            g2.drawRect(px, py, pinColW, rowH);
            g2.setFont(AppFonts.ui(Font.PLAIN, 9));
            g2.drawString(pin.getShortName(), px + 3, py + 13);

            String boundOut = pin.getConstantValue();
            boolean hasBoundTarget = boundOut != null && !boundOut.isBlank() && !boundOut.equals("0");

            Rectangle outPillRect = new Rectangle(blockX + blockW, py + 1, 44, rowH - 2);
            pinHitBoxes.add(new PinOperandHitBox(rungIndex, b, i, false,
                    new Rectangle(px, py, pinColW + 44, rowH)));

            if (hasBoundTarget || i > 0 || pin.getDataType() != DataType.BOOL) {
                g2.setColor(hasBoundTarget ? new Color(0xE8, 0xF4, 0xFD) : new Color(0xF8, 0xF9, 0xFA));
                g2.fillRect(outPillRect.x, outPillRect.y, outPillRect.width, outPillRect.height);
                g2.setColor(new Color(0x77, 0x88, 0x99));
                g2.drawRect(outPillRect.x, outPillRect.y, outPillRect.width, outPillRect.height);

                String disp = hasBoundTarget ? boundOut : "->[..]";
                if (liveSim) {
                    disp = (hasBoundTarget ? (boundOut + "=") : "=")
                            + pin.getDataType().formatRuntimeValue(pin.getRuntimeValue(), true);
                }
                g2.setColor(hasBoundTarget ? new Color(0x1F, 0x61, 0x8D) : new Color(0x55, 0x55, 0x55));
                g2.drawString(disp, outPillRect.x + 2, py + 12);
            }
        }

        if (showExecutionInfo) {
            int courtX = blockX + pinColW;
            int courtW = blockW - 2 * pinColW;
            g2.setFont(AppFonts.ui(Font.BOLD, 9));
            FontMetrics cfm = g2.getFontMetrics();
            g2.setColor(Color.BLACK);
            String tStr = "T:" + b.getThreadId();
            String oStr = "O:" + b.getOrder();
            g2.drawString(tStr, courtX + (courtW - cfm.stringWidth(tStr)) / 2, bodyY + bodyH - 12);
            g2.drawString(oStr, courtX + (courtW - cfm.stringWidth(oStr)) / 2, bodyY + bodyH - 2);
        }

        if (selected) {
            Stroke old = g2.getStroke();
            g2.setStroke(new BasicStroke(2f));
            g2.setColor(COLOR_ELEMENT_SELECTED);
            g2.drawRect(blockX - 2, topY - 2, blockW + 4, totalH + 4);
            g2.setStroke(old);
        }

        return bounds;
    }

    private void drawPowerLine(Graphics2D g2, int x1, int y1, int x2, int y2, boolean energized) {
        Stroke old = g2.getStroke();
        if (energized) {
            g2.setStroke(new BasicStroke(2f));
            g2.setColor(COLOR_POWER_ON);
        } else {
            g2.setStroke(new BasicStroke(1f));
            g2.setColor(COLOR_WIRE_OFF);
        }
        g2.drawLine(x1, y1, x2, y2);
        g2.setStroke(old);
    }
}
