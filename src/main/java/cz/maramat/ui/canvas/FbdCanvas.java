package cz.maramat.ui.canvas;

import cz.maramat.model.BlockCatalog;
import cz.maramat.model.BlockDefinition;
import cz.maramat.model.BlockInstance;
import cz.maramat.model.Bus;
import cz.maramat.model.DataType;
import cz.maramat.model.GraphicElement;
import cz.maramat.model.HeaderColor;
import cz.maramat.model.Page;
import cz.maramat.model.PinInstance;
import cz.maramat.model.PlcThread;
import cz.maramat.model.Project;
import cz.maramat.model.Wire;
import cz.maramat.settings.AppSettings;
import cz.maramat.sim.SimulationEngine;
import cz.maramat.ui.AppFonts;
import cz.maramat.ui.dialogs.ModulePropertiesDialog;
import cz.maramat.ui.dialogs.VisualElementDialogs;
import cz.maramat.ui.render.FbdRenderer;
import cz.maramat.ui.toolbar.EditorToolbar.ToolMode;

import javax.swing.BorderFactory;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.JViewport;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.ToolTipManager;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Frame;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Point;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.datatransfer.DataFlavor;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DropTarget;
import java.awt.dnd.DropTargetAdapter;
import java.awt.dnd.DropTargetDropEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.geom.Line2D;
import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class FbdCanvas extends JPanel {

    public interface CanvasEditListener {
        void onBeforePageEdit(String actionName, Page snapshotBefore);
        void onAfterPageEdit(String actionName, Page snapshotBefore, Page snapshotAfter);
        void onResetToolToCursor();
        void onInspectMacroPage(String macroTypeName);
        boolean isPinOnScope(String pinRef);
        void onTogglePinOnScope(String pinRef);
        default void onTriggerFind() {}
    }

    private static final int[] ZOOM_GRID_SIZES = {5, 7, 8, 10, 12, 15, 20, 30};
    private int zoomIndex = 3; // Default 100% -> G = 10px

    private Project project;
    private int activePageIndex = 0;
    private final SimulationEngine simEngine;
    private final CanvasEditListener editListener;

    private boolean showGrid = true;
    private boolean showExecutionInfo = true;

    private ToolMode currentToolMode = ToolMode.CURSOR;
    private GraphicElement.Kind currentShapeKind = GraphicElement.Kind.RECTANGLE;
    private int customNgonSides = 6;

    // Selection state
    private final Set<String> selectedBlockIds = new HashSet<>();
    private final Set<String> selectedGraphicIds = new HashSet<>();
    private String selectedWireId = null;
    private Rectangle marqueePixelRect = null;
    private Point marqueeStartPixel = null;

    // Clipboard state
    private final List<BlockInstance> clipboardBlocks = new ArrayList<>();
    private final List<Wire> clipboardWires = new ArrayList<>();
    private final List<GraphicElement> clipboardGraphics = new ArrayList<>();

    // Block / Graphic dragging state
    private boolean draggingSelection = false;
    private Page dragBeforeSnapshot = null;
    private int dragStartMouseGridX;
    private int dragStartMouseGridY;
    private final Map<String, Point> initialBlockPositions = new HashMap<>();
    private final Map<String, Point> initialGraphicPositions = new HashMap<>();

    // Wire creation drag state (throttled to ~60 FPS)
    private boolean creatingWire = false;
    private BlockInstance wireStartBlock = null;
    private int wireStartPinIndex = -1;
    private boolean wireStartIsOutput = true;
    private Point wireStartPixel = null;
    private Point wireCurrentPixel = null;
    private long lastWireDragRepaintMs = 0L;

    // Blender-style wire & bus edge dragging state
    private Wire draggingWire = null;
    private int draggingWireSegmentIndex = -1;
    private Bus draggingBus = null;
    private int draggingBusSegmentIndex = -1;
    private String selectedBusId = null;
    private Page wireEditBeforeSnapshot = null;

    // Shape creation drag state
    private GraphicElement creatingGraphic = null;
    private Page graphicBeforeSnapshot = null;

    // Shape resize state
    private enum ResizeHandle {
        NW, N, NE, E, SE, S, SW, W, LINE_START, LINE_END
    }

    private record GraphicResizeHit(GraphicElement elem, ResizeHandle handle) {}

    private enum ZOrderAction {
        SEND_TO_FOREGROUND, MOVE_UP, MOVE_DOWN, SEND_TO_BACKGROUND
    }

    private GraphicElement resizingGraphic = null;
    private ResizeHandle resizingHandle = null;
    private Page resizeBeforeSnapshot = null;
    private int resizeOrigX1, resizeOrigY1, resizeOrigX2, resizeOrigY2;

    // Middle-mouse panning state
    private boolean panningWithMmb = false;
    private Point panStartScreenPoint = null;
    private Point panStartViewPosition = null;

    // Inline constant editor JTextField
    private JTextField activeConstantField = null;

    // Visual Button momentary press tracking
    private GraphicElement heldVisualButton = null;
    // xVar momentary press tracking
    private BlockInstance heldMomentaryXVar = null;
    private long lastPopupTimestamp = 0;

    private static boolean isCanvasPopupTrigger(MouseEvent e) {
        return e.isPopupTrigger()
                || SwingUtilities.isRightMouseButton(e)
                || (e.isControlDown() && SwingUtilities.isLeftMouseButton(e))
                || (e.getModifiersEx() & (MouseEvent.BUTTON3_DOWN_MASK | MouseEvent.BUTTON2_DOWN_MASK)) != 0;
    }

    public FbdCanvas(Project project, SimulationEngine simEngine, CanvasEditListener editListener) {
        super(null);
        this.project = project;
        this.simEngine = simEngine;
        this.editListener = editListener;

        setBackground(Color.WHITE);
        setFocusable(true);
        ToolTipManager.sharedInstance().registerComponent(this);
        ToolTipManager.sharedInstance().setInitialDelay(200);

        setupDropTarget();
        setupMouseHandlers();
        updateCanvasPreferredSize();
    }

    public int getGridUnitPx() {
        return ZOOM_GRID_SIZES[zoomIndex];
    }

    public int getZoomPercent() {
        return getGridUnitPx() * 10;
    }

    private Page forcedPage = null;

    public void setForcedPage(Page page) {
        this.forcedPage = page;
        updateCanvasPreferredSize();
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
        closeInlineConstantEditor(false);
        this.project = project;
        this.activePageIndex = pageIndex;
        clearSelection();
        updateCanvasPreferredSize();
        repaint();
    }

    public void setToolMode(ToolMode mode, GraphicElement.Kind shapeKind, int ngonSides) {
        closeInlineConstantEditor(true);
        this.currentToolMode = mode;
        this.currentShapeKind = shapeKind;
        this.customNgonSides = ngonSides;
        setCursor(mode == ToolMode.CURSOR
                ? Cursor.getDefaultCursor()
                : Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
    }

    public void clearSelection() {
        selectedBlockIds.clear();
        selectedGraphicIds.clear();
        selectedWireId = null;
        repaint();
    }

    public void selectAndCenterBlock(BlockInstance b) {
        if (b == null) return;
        selectedBlockIds.clear();
        selectedGraphicIds.clear();
        selectedWireId = null;
        selectedBlockIds.add(b.getId());

        int g = getGridUnitPx();
        int bx = b.getGridX() * g;
        int by = b.getGridY() * g;
        int bw = b.getGridWidth() * g;
        int bh = b.getGridHeight() * g;

        if (getParent() instanceof JViewport vp) {
            int vx = Math.max(0, (bx + bw / 2) - vp.getWidth() / 2);
            int vy = Math.max(0, (by + bh / 2) - vp.getHeight() / 2);
            vp.setViewPosition(new Point(vx, vy));
        } else {
            scrollRectToVisible(new Rectangle(bx - 80, by - 80, bw + 160, bh + 160));
        }
        repaint();
    }

    public void selectAll() {
        Page page = getActivePage();
        if (page == null) return;
        selectedBlockIds.clear();
        selectedGraphicIds.clear();
        for (BlockInstance b : page.getBlocks()) selectedBlockIds.add(b.getId());
        for (GraphicElement g : page.getGraphics()) {
            if (!isVisualButtonLockedInLiveMode(g)) {
                selectedGraphicIds.add(g.getId());
            }
        }
        repaint();
    }

    public Set<String> getSelectedBlockIds() {
        return Set.copyOf(selectedBlockIds);
    }

    public Set<String> getSelectedGraphicIds() {
        return Set.copyOf(selectedGraphicIds);
    }

    public void zoomIn() {
        if (zoomIndex < ZOOM_GRID_SIZES.length - 1) {
            zoomIndex++;
            closeInlineConstantEditor(true);
            updateCanvasPreferredSize();
            repaint();
        }
    }

    public void zoomOut() {
        if (zoomIndex > 0) {
            zoomIndex--;
            closeInlineConstantEditor(true);
            updateCanvasPreferredSize();
            repaint();
        }
    }

    public void zoom100() {
        zoomIndex = 3;
        closeInlineConstantEditor(true);
        updateCanvasPreferredSize();
        repaint();
    }

    public void fitToScreen() {
        Page page = getActivePage();
        if (page == null || page.getBlocks().isEmpty()) {
            zoom100();
            return;
        }
        int maxGx = 40;
        int maxGy = 30;
        for (BlockInstance b : page.getBlocks()) {
            maxGx = Math.max(maxGx, b.getGridX() + b.getGridWidth() + 6);
            maxGy = Math.max(maxGy, b.getGridY() + b.getGridHeight() + 6);
        }
        if (getParent() instanceof JViewport vp) {
            int vw = vp.getWidth();
            int vh = vp.getHeight();
            int bestIdx = 0;
            for (int i = 0; i < ZOOM_GRID_SIZES.length; i++) {
                int g = ZOOM_GRID_SIZES[i];
                if (maxGx * g <= vw && maxGy * g <= vh) {
                    bestIdx = i;
                }
            }
            zoomIndex = bestIdx;
            vp.setViewPosition(new Point(0, 0));
        }
        updateCanvasPreferredSize();
        repaint();
    }

    public boolean isShowGrid() {
        return showGrid;
    }

    public void setShowGrid(boolean showGrid) {
        this.showGrid = showGrid;
        repaint();
    }

    public boolean isShowExecutionInfo() {
        return showExecutionInfo;
    }

    public void setShowExecutionInfo(boolean showExecutionInfo) {
        this.showExecutionInfo = showExecutionInfo;
        repaint();
    }

    private void exitSimOrWatchBeforeStructuralEdit() {
        if (simEngine.getState() != SimulationEngine.State.STOPPED) {
            simEngine.stopSimulation(project);
        }
    }

    private boolean isVisualButtonLockedInLiveMode(GraphicElement elem) {
        return elem != null
                && elem.getKind() == GraphicElement.Kind.VISUAL_BUTTON
                && simEngine.isShowingLiveOutputs();
    }

    // =========================================================================
    // Copy / Cut / Paste / Duplicate / Delete Operations
    // =========================================================================

    public void copySelection() {
        Page page = getActivePage();
        if (page == null) return;
        clipboardBlocks.clear();
        clipboardWires.clear();
        clipboardGraphics.clear();

        for (BlockInstance b : page.getBlocks()) {
            if (selectedBlockIds.contains(b.getId())) {
                clipboardBlocks.add(b.copy());
            }
        }
        for (Wire w : page.getWires()) {
            if (selectedBlockIds.contains(w.getSourceBlockId()) && selectedBlockIds.contains(w.getTargetBlockId())) {
                clipboardWires.add(w.copy());
            }
        }
        for (GraphicElement g : page.getGraphics()) {
            if (selectedGraphicIds.contains(g.getId()) && !isVisualButtonLockedInLiveMode(g)) {
                clipboardGraphics.add(g.copy());
            }
        }
    }

    public void cutSelection() {
        Page page = getActivePage();
        if (page == null) return;
        boolean hasCuttable = !selectedBlockIds.isEmpty() || selectedWireId != null || page.getGraphics().stream()
                .anyMatch(g -> selectedGraphicIds.contains(g.getId()) && !isVisualButtonLockedInLiveMode(g));
        if (!hasCuttable) return;
        copySelection();
        deleteSelection();
    }

    public void pasteClipboard() {
        Page page = getActivePage();
        if (page == null || (clipboardBlocks.isEmpty() && clipboardGraphics.isEmpty())) return;
        exitSimOrWatchBeforeStructuralEdit();

        Page before = page.copy();
        selectedBlockIds.clear();
        selectedGraphicIds.clear();

        Map<String, String> oldToNewBlockId = new HashMap<>();
        for (BlockInstance cb : clipboardBlocks) {
            BlockInstance nb = cb.copy();
            String newId = UUID.randomUUID().toString();
            oldToNewBlockId.put(cb.getId(), newId);
            nb.setId(newId);
            nb.setName(page.nextUniqueBlockName(cb.getTypeName().replaceAll("\\s+", "")));
            nb.setGridX(cb.getGridX() + 3);
            nb.setGridY(cb.getGridY() + 3);
            nb.setOrder(page.nextOrderForThread(nb.getThreadId()));
            page.getBlocks().add(nb);
            selectedBlockIds.add(newId);
        }

        for (Wire cw : clipboardWires) {
            String newSrc = oldToNewBlockId.get(cw.getSourceBlockId());
            String newDst = oldToNewBlockId.get(cw.getTargetBlockId());
            if (newSrc != null && newDst != null) {
                Wire nw = cw.copy();
                nw.setSourceBlockId(newSrc);
                nw.setTargetBlockId(newDst);
                if (nw.getBusId() != null) {
                    nw.setBusId(newSrc + "->" + newDst);
                }
                nw.getGridPoints().forEach(pt -> { pt.x += 3; pt.y += 3; });
                page.getWires().add(nw);
            }
        }

        for (GraphicElement cg : clipboardGraphics) {
            GraphicElement ng = cg.copy();
            ng.setId(UUID.randomUUID().toString());
            ng.setGridX(cg.getGridX() + 3);
            ng.setGridY(cg.getGridY() + 3);
            page.getGraphics().add(ng);
            selectedGraphicIds.add(ng.getId());
        }

        editListener.onAfterPageEdit("Paste Selection", before, page);
        updateCanvasPreferredSize();
        repaint();
    }

    public boolean canPaste() {
        return !clipboardBlocks.isEmpty() || !clipboardGraphics.isEmpty();
    }

    public void duplicateSelection() {
        Page page = getActivePage();
        if (page == null) return;
        boolean hasDuplicable = !selectedBlockIds.isEmpty() || page.getGraphics().stream()
                .anyMatch(g -> selectedGraphicIds.contains(g.getId()) && !isVisualButtonLockedInLiveMode(g));
        if (!hasDuplicable) return;
        copySelection();
        pasteClipboard();
    }

    public void deleteSelection() {
        Page page = getActivePage();
        if (page == null) return;
        Set<String> deletableGraphicIds = new HashSet<>();
        for (GraphicElement g : page.getGraphics()) {
            if (selectedGraphicIds.contains(g.getId()) && !isVisualButtonLockedInLiveMode(g)) {
                deletableGraphicIds.add(g.getId());
            }
        }
        if (selectedBlockIds.isEmpty() && deletableGraphicIds.isEmpty() && selectedWireId == null) return;

        exitSimOrWatchBeforeStructuralEdit();
        Page before = page.copy();

        if (selectedWireId != null) {
            page.getWires().removeIf(w -> w.getId().equals(selectedWireId));
            selectedWireId = null;
        }
        if (!selectedBlockIds.isEmpty()) {
            page.getBlocks().removeIf(b -> selectedBlockIds.contains(b.getId()));
            page.getWires().removeIf(w -> selectedBlockIds.contains(w.getSourceBlockId())
                    || selectedBlockIds.contains(w.getTargetBlockId()));
            selectedBlockIds.clear();
        }
        if (!deletableGraphicIds.isEmpty()) {
            page.getGraphics().removeIf(g -> deletableGraphicIds.contains(g.getId()));
            selectedGraphicIds.removeAll(deletableGraphicIds);
        }

        if (page.isMacro()) {
            BlockCatalog.syncMacrosFromProject(project);
        }

        editListener.onAfterPageEdit("Delete Selection", before, page);
        updateCanvasPreferredSize();
        repaint();
    }

    // =========================================================================
    // File-Like Drag-and-Drop Block Placement from Library Tree
    // =========================================================================

    private void setupDropTarget() {
        new DropTarget(this, DnDConstants.ACTION_COPY, new DropTargetAdapter() {
            @Override
            public void drop(DropTargetDropEvent dtde) {
                try {
                    if (!dtde.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                        dtde.rejectDrop();
                        return;
                    }
                    dtde.acceptDrop(DnDConstants.ACTION_COPY);
                    String defId = (String) dtde.getTransferable().getTransferData(DataFlavor.stringFlavor);
                    if (defId == null || defId.startsWith("Ladder.")) {
                        dtde.dropComplete(false);
                        return;
                    }
                    BlockDefinition def = BlockCatalog.getById(defId);
                    Page page = getActivePage();
                    if (def == null || page == null) {
                        dtde.dropComplete(false);
                        return;
                    }
                    if (!page.isMacro() && (defId.equals("Macro.MacroIn") || defId.equals("Macro.MacroOut"))) {
                        dtde.dropComplete(false);
                        return;
                    }
                    if (page.isMacro() && defId.equals("Macro.User." + page.getCleanName())) {
                        dtde.dropComplete(false);
                        return;
                    }
                    if (page.isMacro() && (defId.equals("Macro.MacroIn") || defId.equals("Macro.MacroOut"))) {
                        boolean alreadyExists = page.getBlocks().stream()
                                .anyMatch(b -> b.getDefinitionId().equals(defId));
                        if (alreadyExists) {
                            JOptionPane.showMessageDialog(FbdCanvas.this,
                                    "Only one " + def.getTypeName() + " block is allowed per Macro.\n"
                                            + "Double-click the existing " + def.getTypeName() + " block to add or edit pins.",
                                    "Macro Boundary Block", JOptionPane.INFORMATION_MESSAGE);
                            dtde.dropComplete(false);
                            return;
                        }
                    }

                    exitSimOrWatchBeforeStructuralEdit();
                    Page before = page.copy();

                    int g = getGridUnitPx();
                    Point dropPt = dtde.getLocation();
                    int gx = Math.max(2, Math.round((float) dropPt.x / g));
                    int gy = Math.max(2, Math.round((float) dropPt.y / g));

                    String instName = page.nextUniqueBlockName(def.getInstancePrefix());
                    int tid = project.getDefaultThreadId();
                    int order = page.nextOrderForThread(tid);

                    BlockInstance block = new BlockInstance(def, instName, gx, gy, tid, order);
                    block.setHeaderColor(AppSettings.get().getDefaultHeaderColor());
                    page.getBlocks().add(block);

                    selectedBlockIds.clear();
                    selectedGraphicIds.clear();
                    selectedBlockIds.add(block.getId());

                    if (page.isMacro() && (defId.equals("Macro.MacroIn") || defId.equals("Macro.MacroOut"))) {
                        BlockCatalog.syncMacrosFromProject(project);
                    }

                    editListener.onAfterPageEdit("Place Block " + instName, before, page);
                    updateCanvasPreferredSize();
                    repaint();
                    requestFocusInWindow();
                    dtde.dropComplete(true);
                } catch (Exception ex) {
                    dtde.dropComplete(false);
                }
            }
        });
    }

    // =========================================================================
    // Dynamic Bounding Box Calculation for Graphic / Text Elements
    // =========================================================================

    private Rectangle getGraphicPixelBounds(GraphicElement elem, int g) {
        return FbdRenderer.getGraphicPixelBounds(elem, g);
    }

    // =========================================================================
    // Mouse & Wheel Event Handling
    // =========================================================================

    private void setupMouseHandlers() {
        MouseAdapter ma = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                requestFocusInWindow();
                closeInlineConstantEditor(true);

                // Middle Mouse Button Panning
                if (SwingUtilities.isMiddleMouseButton(e)) {
                    if (getParent() instanceof JViewport vp) {
                        panningWithMmb = true;
                        panStartScreenPoint = e.getLocationOnScreen();
                        panStartViewPosition = vp.getViewPosition();
                        setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                    }
                    return;
                }

                Page page = getActivePage();
                if (page == null) return;
                int g = getGridUnitPx();
                int mx = e.getX();
                int my = e.getY();
                int gx = Math.round((float) mx / g);
                int gy = Math.round((float) my / g);

                // Right-Click Context Menu on Pin, Wire, Block, or Canvas
                if (isCanvasPopupTrigger(e)) {
                    lastPopupTimestamp = System.currentTimeMillis();
                    handleRightClick(page, mx, my, gx, gy);
                    return;
                }

                if (!SwingUtilities.isLeftMouseButton(e)) return;

                // Non-cursor decorative/button tools
                if (currentToolMode != ToolMode.CURSOR) {
                    exitSimOrWatchBeforeStructuralEdit();
                    graphicBeforeSnapshot = page.copy();
                    if (currentToolMode == ToolMode.TEXT) {
                        String txt = JOptionPane.showInputDialog(FbdCanvas.this, "Enter Annotation Text:", "Section 1");
                        if (txt != null && !txt.isBlank()) {
                            GraphicElement textElem = new GraphicElement(GraphicElement.Kind.TEXT, gx, gy, 2, 1);
                            textElem.setText(txt.trim());
                            page.getGraphics().add(textElem);
                            editListener.onAfterPageEdit("Add Text '" + txt.trim() + "'", graphicBeforeSnapshot, page);
                        }
                        editListener.onResetToolToCursor();
                        repaint();
                        return;
                    }
                    if (currentToolMode == ToolMode.VISUAL_BUTTON) {
                        GraphicElement btnElem = new GraphicElement(GraphicElement.Kind.VISUAL_BUTTON, gx, gy, 5, 2);
                        page.getGraphics().add(btnElem);
                        Frame owner = AppFonts.resolveFrameOwner(FbdCanvas.this);
                        VisualElementDialogs.showVisualButtonDialog(owner, page, btnElem);
                        editListener.onAfterPageEdit("Place Visual Button " + btnElem.getText(), graphicBeforeSnapshot, page);
                        editListener.onResetToolToCursor();
                        repaint();
                        return;
                    }

                    creatingGraphic = new GraphicElement(currentShapeKind, gx, gy, 1, 1);
                    creatingGraphic.setNgonSides(customNgonSides);
                    page.getGraphics().add(creatingGraphic);
                    repaint();
                    return;
                }

                // 1. Check if double-clicking an input pin to edit its compile-time constant (=x)
                PinHit pinHit = findPinAtPixel(page, mx, my);
                if (pinHit != null) {
                    if (e.getClickCount() == 2 && pinHit.isInput) {
                        if (!page.isInputPinConnected(pinHit.block.getId(), pinHit.pinIndex)) {
                            exitSimOrWatchBeforeStructuralEdit();
                            openInlineConstantEditor(page, pinHit.block, pinHit.pinIndex);
                        }
                        return;
                    }
                    // Single LMB press on a pin starts routing a wire
                    exitSimOrWatchBeforeStructuralEdit();
                    creatingWire = true;
                    wireStartBlock = pinHit.block;
                    wireStartPinIndex = pinHit.pinIndex;
                    wireStartIsOutput = !pinHit.isInput;
                    Point2D.Double anchorG = wireStartIsOutput
                            ? wireStartBlock.getOutputPinAnchorGrid(wireStartPinIndex)
                            : wireStartBlock.getInputPinAnchorGrid(wireStartPinIndex);
                    wireStartPixel = new Point((int) Math.round(anchorG.x * g), (int) Math.round(anchorG.y * g));
                    wireCurrentPixel = new Point(mx, my);
                    repaint();
                    return;
                }

                // 1b. Check if clicking a resize handle of an already-selected GraphicElement
                GraphicResizeHit resizeHit = findGraphicResizeHandle(page, mx, my);
                if (resizeHit != null) {
                    exitSimOrWatchBeforeStructuralEdit();
                    resizingGraphic = resizeHit.elem();
                    resizingHandle = resizeHit.handle();
                    resizeBeforeSnapshot = page.copy();
                    if (resizingGraphic.getKind() == GraphicElement.Kind.LINE) {
                        resizeOrigX1 = resizingGraphic.getGridX();
                        resizeOrigY1 = resizingGraphic.getGridY();
                        resizeOrigX2 = resizingGraphic.getGridX() + resizingGraphic.getGridW();
                        resizeOrigY2 = resizingGraphic.getGridY() + resizingGraphic.getGridH();
                    } else {
                        int x1 = Math.min(resizingGraphic.getGridX(), resizingGraphic.getGridX() + resizingGraphic.getGridW());
                        int y1 = Math.min(resizingGraphic.getGridY(), resizingGraphic.getGridY() + resizingGraphic.getGridH());
                        int x2 = Math.max(x1 + 1, Math.max(resizingGraphic.getGridX(), resizingGraphic.getGridX() + resizingGraphic.getGridW()));
                        int y2 = Math.max(y1 + 1, Math.max(resizingGraphic.getGridY(), resizingGraphic.getGridY() + resizingGraphic.getGridH()));
                        resizeOrigX1 = x1;
                        resizeOrigY1 = y1;
                        resizeOrigX2 = x2;
                        resizeOrigY2 = y2;
                    }
                    return;
                }

                // 2. Check if clicking a Block
                BlockInstance hitBlock = findBlockAtPixel(page, mx, my);
                if (hitBlock != null) {
                    if (hitBlock.getDefinitionId().startsWith("Visual.Vars.") && simEngine.isShowingLiveOutputs()) {
                        String inputType = hitBlock.getCustomProperties().getOrDefault("InputType", "Switch");
                        if ("Switch".equalsIgnoreCase(inputType)) {
                            String swMode = hitBlock.getCustomProperties().getOrDefault("SwitchMode", "Toggle");
                            String valA = hitBlock.getCustomProperties().getOrDefault("ValA", "0");
                            String valB = hitBlock.getCustomProperties().getOrDefault("ValB", "1");
                            PinInstance outPin = hitBlock.getOutputs().getFirst();

                            if ("Momentary".equalsIgnoreCase(swMode)) {
                                hitBlock.getCustomProperties().put("CurrentValue", valB);
                                outPin.setRuntimeValue(outPin.getDataType().toNumericValue(valB));
                                heldMomentaryXVar = hitBlock;
                            } else {
                                String cur = hitBlock.getCustomProperties().getOrDefault("CurrentValue", "0");
                                String next = cur.equals(valA) ? valB : valA;
                                hitBlock.getCustomProperties().put("CurrentValue", next);
                                outPin.setRuntimeValue(outPin.getDataType().toNumericValue(next));
                            }
                            repaint();
                            return;
                        } else {
                            // Slider single click
                            Frame owner = AppFonts.resolveFrameOwner(FbdCanvas.this);
                            VisualElementDialogs.triggerXVarAction(owner, hitBlock, FbdCanvas.this::repaint);
                            return;
                        }
                    }

                    if (e.getClickCount() == 2) {
                        onBlockDoubleClicked(page, hitBlock);
                        return;
                    }

                    // During simulation or watch, prevent moving xVar blocks
                    if (hitBlock.getDefinitionId().startsWith("Visual.Vars.") && simEngine.isShowingLiveOutputs()) {
                        return;
                    }

                    boolean ctrlDown = (e.getModifiersEx() & (KeyEvent.CTRL_DOWN_MASK | KeyEvent.META_DOWN_MASK)) != 0;
                    if (ctrlDown) {
                        if (selectedBlockIds.contains(hitBlock.getId())) {
                            selectedBlockIds.remove(hitBlock.getId());
                        } else {
                            selectedBlockIds.add(hitBlock.getId());
                        }
                    } else if (!selectedBlockIds.contains(hitBlock.getId())) {
                        selectedBlockIds.clear();
                        selectedGraphicIds.clear();
                        selectedWireId = null;
                        selectedBlockIds.add(hitBlock.getId());
                    }

                    startDraggingSelection(page, gx, gy);
                    repaint();
                    return;
                }

                // 3. Check if clicking a Visual Button or Decorative Graphic Element
                GraphicElement hitGraphic = findGraphicAtPixel(page, mx, my);
                if (hitGraphic != null) {
                    if (isVisualButtonLockedInLiveMode(hitGraphic)) {
                        if (hitGraphic.isToggleButton()) {
                            hitGraphic.setActiveState(!hitGraphic.isActiveState());
                        } else {
                            hitGraphic.setActiveState(true);
                            heldVisualButton = hitGraphic;
                        }
                        repaint();
                        return;
                    }

                    if (e.getClickCount() == 2) {
                        exitSimOrWatchBeforeStructuralEdit();
                        Page before = page.copy();
                        Frame owner = AppFonts.resolveFrameOwner(FbdCanvas.this);
                        boolean changed = (hitGraphic.getKind() == GraphicElement.Kind.VISUAL_BUTTON)
                                ? VisualElementDialogs.showVisualButtonDialog(owner, page, hitGraphic)
                                : VisualElementDialogs.showGraphicPropertiesDialog(owner, page, hitGraphic);
                        if (changed) {
                            editListener.onAfterPageEdit("Edit Graphic Properties", before, page);
                            repaint();
                        }
                        return;
                    }

                    if (!selectedGraphicIds.contains(hitGraphic.getId())) {
                        selectedBlockIds.clear();
                        selectedGraphicIds.clear();
                        selectedWireId = null;
                        selectedGraphicIds.add(hitGraphic.getId());
                    }
                    startDraggingSelection(page, gx, gy);
                    repaint();
                    return;
                }

                // 4. Check if clicking a Wire or Bus Segment for Blender-style Edge Dragging
                WireHit wireHit = findWireOrFlagAtPixel(page, mx, my);
                if (wireHit != null && !wireHit.wire.isHidden() && wireHit.segmentIndex >= 0) {
                    exitSimOrWatchBeforeStructuralEdit();
                    selectedBlockIds.clear();
                    selectedGraphicIds.clear();
                    if (wireHit.bus != null) {
                        selectedWireId = null;
                        selectedBusId = wireHit.bus.getName();
                        draggingBus = wireHit.bus;
                        draggingBusSegmentIndex = wireHit.segmentIndex;
                    } else {
                        selectedBusId = null;
                        selectedWireId = wireHit.wire.getId();
                        draggingWire = wireHit.wire;
                        draggingWireSegmentIndex = wireHit.segmentIndex;
                    }
                    wireEditBeforeSnapshot = page.copy();
                    repaint();
                    return;
                }

                // 5. Empty canvas click -> Start Selection Rectangle (Marquee)
                if ((e.getModifiersEx() & (KeyEvent.CTRL_DOWN_MASK | KeyEvent.META_DOWN_MASK)) == 0) {
                    selectedBlockIds.clear();
                    selectedGraphicIds.clear();
                    selectedWireId = null;
                    selectedBusId = null;
                }
                marqueeStartPixel = new Point(mx, my);
                marqueePixelRect = new Rectangle(mx, my, 0, 0);
                repaint();
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (panningWithMmb && getParent() instanceof JViewport vp) {
                    Point curScreen = e.getLocationOnScreen();
                    int dx = panStartScreenPoint.x - curScreen.x;
                    int dy = panStartScreenPoint.y - curScreen.y;
                    Point newPos = new Point(
                            Math.max(0, panStartViewPosition.x + dx),
                            Math.max(0, panStartViewPosition.y + dy)
                    );
                    vp.setViewPosition(newPos);
                    return;
                }

                Page page = getActivePage();
                if (page == null) return;
                int g = getGridUnitPx();
                int mx = e.getX();
                int my = e.getY();
                int gx = Math.round((float) mx / g);
                int gy = Math.round((float) my / g);

                if (creatingGraphic != null) {
                    creatingGraphic.setGridW(gx - creatingGraphic.getGridX());
                    creatingGraphic.setGridH(gy - creatingGraphic.getGridY());
                    repaint();
                    return;
                }

                if (resizingGraphic != null && resizingHandle != null) {
                    exitSimOrWatchBeforeStructuralEdit();
                    if (resizingHandle == ResizeHandle.LINE_START) {
                        int nx1 = Math.max(1, gx);
                        int ny1 = Math.max(1, gy);
                        resizingGraphic.setGridX(nx1);
                        resizingGraphic.setGridY(ny1);
                        resizingGraphic.setGridW(resizeOrigX2 - nx1);
                        resizingGraphic.setGridH(resizeOrigY2 - ny1);
                    } else if (resizingHandle == ResizeHandle.LINE_END) {
                        int nx2 = Math.max(1, gx);
                        int ny2 = Math.max(1, gy);
                        resizingGraphic.setGridX(resizeOrigX1);
                        resizingGraphic.setGridY(resizeOrigY1);
                        resizingGraphic.setGridW(nx2 - resizeOrigX1);
                        resizingGraphic.setGridH(ny2 - resizeOrigY1);
                    } else {
                        int nx1 = resizeOrigX1;
                        int ny1 = resizeOrigY1;
                        int nx2 = resizeOrigX2;
                        int ny2 = resizeOrigY2;
                        if (resizingHandle == ResizeHandle.NW || resizingHandle == ResizeHandle.W || resizingHandle == ResizeHandle.SW) {
                            nx1 = Math.max(1, Math.min(gx, resizeOrigX2 - 1));
                        }
                        if (resizingHandle == ResizeHandle.NE || resizingHandle == ResizeHandle.E || resizingHandle == ResizeHandle.SE) {
                            nx2 = Math.max(resizeOrigX1 + 1, gx);
                        }
                        if (resizingHandle == ResizeHandle.NW || resizingHandle == ResizeHandle.N || resizingHandle == ResizeHandle.NE) {
                            ny1 = Math.max(1, Math.min(gy, resizeOrigY2 - 1));
                        }
                        if (resizingHandle == ResizeHandle.SW || resizingHandle == ResizeHandle.S || resizingHandle == ResizeHandle.SE) {
                            ny2 = Math.max(resizeOrigY1 + 1, gy);
                        }
                        resizingGraphic.setGridX(nx1);
                        resizingGraphic.setGridY(ny1);
                        resizingGraphic.setGridW(nx2 - nx1);
                        resizingGraphic.setGridH(ny2 - ny1);
                    }
                    repaint();
                    return;
                }

                if (creatingWire) {
                    wireCurrentPixel = new Point(mx, my);
                    long now = System.currentTimeMillis();
                    if (now - lastWireDragRepaintMs >= 16) {
                        lastWireDragRepaintMs = now;
                        repaint();
                    }
                    return;
                }

                if (draggingWire != null && draggingWireSegmentIndex >= 0) {
                    List<Point2D.Double> pts = draggingWire.getGridPoints();
                    if (draggingWireSegmentIndex < pts.size() - 1) {
                        Point2D.Double a = pts.get(draggingWireSegmentIndex);
                        Point2D.Double b = pts.get(draggingWireSegmentIndex + 1);
                        boolean horizontal = (Math.abs(a.y - b.y) < 0.001 && Math.abs(a.x - b.x) >= 0.001);
                        double targetCoord = horizontal ? (gy + 0.5) : gx;
                        draggingWireSegmentIndex = draggingWire.moveSegmentEdge(draggingWireSegmentIndex, targetCoord);
                        repaint();
                    }
                    return;
                }

                if (draggingBus != null && draggingBusSegmentIndex >= 0) {
                    List<Point2D.Double> pts = draggingBus.getGridPoints();
                    if (draggingBusSegmentIndex < pts.size() - 1) {
                        Point2D.Double a = pts.get(draggingBusSegmentIndex);
                        Point2D.Double b = pts.get(draggingBusSegmentIndex + 1);
                        boolean horizontal = (Math.abs(a.y - b.y) < 0.001 && Math.abs(a.x - b.x) >= 0.001);
                        double targetCoord = horizontal ? gy : gx;
                        draggingBusSegmentIndex = draggingBus.moveSegmentEdge(draggingBusSegmentIndex, targetCoord);
                        repaint();
                    }
                    return;
                }

                if (draggingSelection) {
                    exitSimOrWatchBeforeStructuralEdit();
                    int dgx = gx - dragStartMouseGridX;
                    int dgy = gy - dragStartMouseGridY;
                    for (BlockInstance b : page.getBlocks()) {
                        Point init = initialBlockPositions.get(b.getId());
                        if (init != null) {
                            b.setGridX(Math.max(1, init.x + dgx));
                            b.setGridY(Math.max(1, init.y + dgy));
                            page.updateWiresForBlock(b);
                        }
                    }
                    for (GraphicElement ge : page.getGraphics()) {
                        Point init = initialGraphicPositions.get(ge.getId());
                        if (init != null) {
                            ge.setGridX(Math.max(1, init.x + dgx));
                            ge.setGridY(Math.max(1, init.y + dgy));
                        }
                    }
                    repaint();
                    return;
                }

                if (marqueeStartPixel != null) {
                    int x = Math.min(marqueeStartPixel.x, mx);
                    int y = Math.min(marqueeStartPixel.y, my);
                    int w = Math.abs(mx - marqueeStartPixel.x);
                    int h = Math.abs(my - marqueeStartPixel.y);
                    marqueePixelRect = new Rectangle(x, y, w, h);

                    selectedBlockIds.clear();
                    selectedGraphicIds.clear();
                    for (BlockInstance b : page.getBlocks()) {
                        Rectangle br = new Rectangle(b.getGridX() * g, b.getGridY() * g, b.getGridWidth() * g, b.getGridHeight() * g);
                        if (marqueePixelRect.intersects(br)) {
                            selectedBlockIds.add(b.getId());
                        }
                    }
                    for (GraphicElement ge : page.getGraphics()) {
                        if (!isVisualButtonLockedInLiveMode(ge) && marqueePixelRect.intersects(getGraphicPixelBounds(ge, g))) {
                            selectedGraphicIds.add(ge.getId());
                        }
                    }
                    repaint();
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (panningWithMmb) {
                    panningWithMmb = false;
                    setCursor(currentToolMode == ToolMode.CURSOR
                            ? Cursor.getDefaultCursor()
                            : Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
                    return;
                }

                if (heldVisualButton != null) {
                    heldVisualButton.setActiveState(false);
                    heldVisualButton = null;
                    repaint();
                }

                if (heldMomentaryXVar != null) {
                    String valA = heldMomentaryXVar.getCustomProperties().getOrDefault("ValA", "0");
                    heldMomentaryXVar.getCustomProperties().put("CurrentValue", valA);
                    PinInstance outPin = heldMomentaryXVar.getOutputs().getFirst();
                    outPin.setRuntimeValue(outPin.getDataType().toNumericValue(valA));
                    heldMomentaryXVar = null;
                    repaint();
                }

                Page page = getActivePage();
                if (page == null) return;

                if (isCanvasPopupTrigger(e)) {
                    long now = System.currentTimeMillis();
                    if (now - lastPopupTimestamp > 250) {
                        lastPopupTimestamp = now;
                        int g = getGridUnitPx();
                        int mx = e.getX();
                        int my = e.getY();
                        int gx = Math.round((float) mx / g);
                        int gy = Math.round((float) my / g);
                        handleRightClick(page, mx, my, gx, gy);
                    }
                    return;
                }

                if (creatingGraphic != null) {
                    if (creatingGraphic.getGridW() == 0 && creatingGraphic.getGridH() == 0) {
                        creatingGraphic.setGridW(5);
                        creatingGraphic.setGridH(3);
                    }
                    String kindName = creatingGraphic.getKind().getDisplayName();
                    creatingGraphic = null;
                    editListener.onAfterPageEdit("Draw " + kindName, graphicBeforeSnapshot, page);
                    editListener.onResetToolToCursor();
                    updateCanvasPreferredSize();
                    repaint();
                    return;
                }

                if (resizingGraphic != null) {
                    String kindName = resizingGraphic.getKind().getDisplayName();
                    resizingGraphic = null;
                    resizingHandle = null;
                    if (resizeBeforeSnapshot != null) {
                        editListener.onAfterPageEdit("Resize " + kindName, resizeBeforeSnapshot, page);
                        resizeBeforeSnapshot = null;
                    }
                    updateCanvasPreferredSize();
                    repaint();
                    return;
                }

                if (creatingWire) {
                    creatingWire = false;
                    PinHit endHit = findPinAtPixel(page, e.getX(), e.getY());
                    if (endHit != null && endHit.block != wireStartBlock && (endHit.isInput == wireStartIsOutput)) {
                        BlockInstance srcBlock = wireStartIsOutput ? wireStartBlock : endHit.block;
                        int srcPin = wireStartIsOutput ? wireStartPinIndex : endHit.pinIndex;
                        BlockInstance dstBlock = wireStartIsOutput ? endHit.block : wireStartBlock;
                        int dstPin = wireStartIsOutput ? endHit.pinIndex : wireStartPinIndex;

                        Page before = page.copy();
                        page.getWires().removeIf(w -> w.getTargetBlockId().equals(dstBlock.getId()) && w.getTargetPinIndex() == dstPin);

                        String netName = srcBlock.getName() + "." + srcBlock.getOutputs().get(srcPin).getShortName();
                        Point2D.Double startG = srcBlock.getOutputPinAnchorGrid(srcPin);
                        Point2D.Double endG = dstBlock.getInputPinAnchorGrid(dstPin);
                        Wire newWire = new Wire(srcBlock.getId(), srcPin, dstBlock.getId(), dstPin, netName, startG, endG);
                        page.getWires().add(newWire);

                        // If dstBlock is "Visual.Value", auto-adjust its input pin DataType to match srcPin's DataType
                        if ("Visual.Value".equals(dstBlock.getDefinitionId()) && dstPin == 0 && srcPin < srcBlock.getOutputs().size()) {
                            DataType srcType = srcBlock.getOutputs().get(srcPin).getDataType();
                            dstBlock.getInputs().get(0).setDataType(srcType);
                        }

                        // Auto-bundle into Bus if enabled in Settings or if block pair is already bundled!
                        List<Wire> pairWires = findWiresBetweenBlockPair(page, srcBlock.getId(), dstBlock.getId());
                        boolean alreadyBundled = pairWires.stream().anyMatch(w -> w.getBusId() != null);
                        if (pairWires.size() >= 2 && (alreadyBundled || AppSettings.get().isAutoBundleBus())) {
                            String busKey = srcBlock.getId() + "->" + dstBlock.getId();
                            for (Wire pw : pairWires) {
                                pw.setBusId(busKey);
                            }
                        }

                        String dstPinName = dstBlock.getInputs().get(dstPin).getShortName();
                        editListener.onAfterPageEdit("Connect " + netName + " -> " + dstBlock.getName() + "." + dstPinName, before, page);
                    }
                    wireStartBlock = null;
                    repaint();
                    return;
                }

                if (draggingWire != null) {
                    String net = draggingWire.getNetName();
                    draggingWire = null;
                    draggingWireSegmentIndex = -1;
                    editListener.onAfterPageEdit("Move Wire Edge " + net, wireEditBeforeSnapshot, page);
                    repaint();
                    return;
                }

                if (draggingBus != null) {
                    String bName = draggingBus.getName();
                    draggingBus = null;
                    draggingBusSegmentIndex = -1;
                    editListener.onAfterPageEdit("Move Bus Edge " + bName, wireEditBeforeSnapshot, page);
                    repaint();
                    return;
                }

                if (draggingSelection) {
                    draggingSelection = false;
                    boolean moved = false;
                    for (BlockInstance b : page.getBlocks()) {
                        Point init = initialBlockPositions.get(b.getId());
                        if (init != null && (init.x != b.getGridX() || init.y != b.getGridY())) {
                            moved = true;
                            break;
                        }
                    }
                    if (!moved) {
                        for (GraphicElement ge : page.getGraphics()) {
                            Point init = initialGraphicPositions.get(ge.getId());
                            if (init != null && (init.x != ge.getGridX() || init.y != ge.getGridY())) {
                                moved = true;
                                break;
                            }
                        }
                    }
                    if (moved) {
                        editListener.onAfterPageEdit("Move Selection", dragBeforeSnapshot, page);
                        updateCanvasPreferredSize();
                    }
                    initialBlockPositions.clear();
                    initialGraphicPositions.clear();
                    repaint();
                }

                if (marqueePixelRect != null) {
                    marqueePixelRect = null;
                    marqueeStartPixel = null;
                    repaint();
                }
            }

            @Override
            public void mouseMoved(MouseEvent e) {
                if (currentToolMode != ToolMode.CURSOR) return;
                Page page = getActivePage();
                if (page == null) return;
                GraphicResizeHit resizeHit = findGraphicResizeHandle(page, e.getX(), e.getY());
                if (resizeHit != null) {
                    setCursor(cursorForResizeHandle(resizeHit.handle()));
                    return;
                }
                WireHit hit = findWireOrFlagAtPixel(page, e.getX(), e.getY());
                if (hit != null && !hit.wire.isHidden() && hit.segmentIndex >= 0) {
                    List<Point2D.Double> pts = hit.bus != null ? hit.bus.getGridPoints() : hit.wire.getGridPoints();
                    if (hit.segmentIndex < pts.size() - 1) {
                        Point2D.Double a = pts.get(hit.segmentIndex);
                        Point2D.Double b = pts.get(hit.segmentIndex + 1);
                        boolean horizontal = (Math.abs(a.y - b.y) < 0.001);
                        setCursor(Cursor.getPredefinedCursor(horizontal ? Cursor.N_RESIZE_CURSOR : Cursor.E_RESIZE_CURSOR));
                    } else {
                        setCursor(Cursor.getDefaultCursor());
                    }
                } else {
                    setCursor(Cursor.getDefaultCursor());
                }
            }

            @Override
            public void mouseWheelMoved(MouseWheelEvent e) {
                boolean ctrlOrCmd = (e.getModifiersEx() & (KeyEvent.CTRL_DOWN_MASK | KeyEvent.META_DOWN_MASK)) != 0;
                if (ctrlOrCmd) {
                    if (e.getWheelRotation() < 0) {
                        zoomIn();
                    } else if (e.getWheelRotation() > 0) {
                        zoomOut();
                    }
                    e.consume();
                } else if (getParent() != null) {
                    getParent().dispatchEvent(e);
                }
            }
        };

        addMouseListener(ma);
        addMouseMotionListener(ma);
        addMouseWheelListener(ma);
    }

    private List<Wire> findWiresBetweenBlockPair(Page page, String srcBlockId, String dstBlockId) {
        List<Wire> list = new ArrayList<>();
        for (Wire w : page.getWires()) {
            if (!w.isHidden() && w.getSourceBlockId().equals(srcBlockId) && w.getTargetBlockId().equals(dstBlockId)) {
                list.add(w);
            }
        }
        return list;
    }

    private void showPinScopeContextMenu(PinHit pinHit, int mx, int my) {
        PinInstance pin = pinHit.isInput
                ? pinHit.block.getInputs().get(pinHit.pinIndex)
                : pinHit.block.getOutputs().get(pinHit.pinIndex);
        String ref = pinHit.block.getName() + "." + pin.getShortName();
        boolean onScope = editListener.isPinOnScope(ref);

        JPopupMenu menu = new JPopupMenu();
        JMenuItem item = new JMenuItem((onScope ? "Remove '" : "Add '") + ref + "' " + (onScope ? "from Scope" : "to Scope"));
        item.addActionListener(e -> editListener.onTogglePinOnScope(ref));
        menu.add(item);
        menu.show(this, mx, my);
    }

    private void startDraggingSelection(Page page, int gx, int gy) {
        draggingSelection = true;
        dragBeforeSnapshot = page.copy();
        dragStartMouseGridX = gx;
        dragStartMouseGridY = gy;
        initialBlockPositions.clear();
        initialGraphicPositions.clear();
        for (BlockInstance b : page.getBlocks()) {
            if (selectedBlockIds.contains(b.getId())) {
                initialBlockPositions.put(b.getId(), new Point(b.getGridX(), b.getGridY()));
            }
        }
        for (GraphicElement ge : page.getGraphics()) {
            if (selectedGraphicIds.contains(ge.getId()) && !isVisualButtonLockedInLiveMode(ge)) {
                initialGraphicPositions.put(ge.getId(), new Point(ge.getGridX(), ge.getGridY()));
            }
        }
    }

    private void onBlockDoubleClicked(Page page, BlockInstance block) {
        Frame owner = AppFonts.resolveFrameOwner(this);
        if (block.getDefinitionId().startsWith("Visual.Vars.") && simEngine.isShowingLiveOutputs()) {
            // During simulation or watch, properties modal is blocked to prevent accidental stop
            return;
        }
        if (block.getDefinitionId().startsWith("Macro.User.") && simEngine.isShowingLiveOutputs()) {
            editListener.onInspectMacroPage(block.getTypeName());
            return;
        }
        exitSimOrWatchBeforeStructuralEdit();
        Page before = page.copy();
        ModulePropertiesDialog dlg = new ModulePropertiesDialog(owner, project, page, block);
        dlg.setVisible(true);
        if (dlg.isConfirmed()) {
            if (page.isMacro()) {
                BlockCatalog.syncMacrosFromProject(project);
            }
            editListener.onAfterPageEdit("Edit Properties " + block.getName(), before, page);
            updateCanvasPreferredSize();
            repaint();
        }
    }

    // =========================================================================
    // Inline Compile-Time Constant Editor (=x) on Double-Clicking Input Pin
    // =========================================================================

    private void openInlineConstantEditor(Page page, BlockInstance block, int pinIndex) {
        closeInlineConstantEditor(false);
        int g = getGridUnitPx();
        PinInstance pin = block.getInputs().get(pinIndex);

        String labelText = "(=" + pin.getConstantValue() + ")";
        int boxW = Math.max(3 * g, labelText.length() * (g / 2) + g);
        int boxX = block.getGridX() * g - boxW;
        int boxY = (block.getGridY() + BlockInstance.HEADER_GRID_HEIGHT + pinIndex) * g;

        Page before = page.copy();
        JTextField tf = new JTextField(pin.getConstantValue());
        tf.setFont(new Font("SansSerif", Font.PLAIN, Math.max(9, g - 1)));
        tf.setBorder(BorderFactory.createLineBorder(new Color(0x00, 0x55, 0xCC), 1));
        tf.setBounds(boxX, boxY, boxW, g + 2);
        tf.selectAll();

        Runnable commitAction = () -> {
            if (activeConstantField != tf) return;
            String raw = tf.getText();
            remove(tf);
            activeConstantField = null;
            try {
                String oldVal = pin.getConstantValue();
                pin.setConstantValue(raw);
                if (!oldVal.equals(pin.getConstantValue())) {
                    editListener.onAfterPageEdit("Set Constant " + block.getName() + "." + pin.getShortName() + " = " + pin.getConstantValue(), before, page);
                }
            } catch (IllegalArgumentException ex) {
                JOptionPane.showMessageDialog(FbdCanvas.this, ex.getMessage(), "Invalid Constant Value", JOptionPane.ERROR_MESSAGE);
            }
            repaint();
        };

        tf.addActionListener(e -> commitAction.run());
        tf.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ESCAPE) {
                    closeInlineConstantEditor(false);
                }
            }
        });
        tf.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                commitAction.run();
            }
        });

        activeConstantField = tf;
        add(tf);
        tf.requestFocusInWindow();
        repaint();
    }

    private void closeInlineConstantEditor(boolean commit) {
        if (activeConstantField != null) {
            JTextField tf = activeConstantField;
            activeConstantField = null;
            remove(tf);
            repaint();
        }
    }

    // =========================================================================
    // Right-Click Context Menus (Wires, Blocks, Pins, Canvas)
    // =========================================================================

    private void handleRightClick(Page page, int mx, int my, int gx, int gy) {
        PinHit pinHit = findPinAtPixel(page, mx, my);
        if (pinHit != null) {
            showPinScopeContextMenu(pinHit, mx, my);
            return;
        }
        WireHit hit = findWireOrFlagAtPixel(page, mx, my);
        if (hit != null) {
            selectedWireId = hit.wire.getId();
            repaint();
            showWireContextMenu(page, hit.wire, hit.segmentIndex, new Point2D.Double(gx, gy + 0.5), mx, my);
            return;
        }
        BlockInstance hitBlock = findBlockAtPixel(page, mx, my);
        if (hitBlock != null) {
            if (!selectedBlockIds.contains(hitBlock.getId())) {
                selectedBlockIds.clear();
                selectedGraphicIds.clear();
                selectedWireId = null;
                selectedBlockIds.add(hitBlock.getId());
                repaint();
            }
            showBlockContextMenu(page, hitBlock, mx, my);
            return;
        }
        GraphicElement hitGraphic = findGraphicAtPixel(page, mx, my);
        if (hitGraphic != null) {
            if (!isVisualButtonLockedInLiveMode(hitGraphic) && !selectedGraphicIds.contains(hitGraphic.getId())) {
                selectedBlockIds.clear();
                selectedGraphicIds.clear();
                selectedWireId = null;
                selectedGraphicIds.add(hitGraphic.getId());
                repaint();
            }
            showGraphicContextMenu(page, hitGraphic, mx, my);
            return;
        }
        showCanvasContextMenu(page, gx, gy, mx, my);
    }

    private void showBlockContextMenu(Page page, BlockInstance block, int mx, int my) {
        JPopupMenu menu = new JPopupMenu();

        JMenuItem propsItem = new JMenuItem("Properties... (Double-Click)");
        propsItem.setFont(new Font(propsItem.getFont().getName(), Font.BOLD, propsItem.getFont().getSize()));
        propsItem.addActionListener(e -> onBlockDoubleClicked(page, block));
        if (block.getDefinitionId().startsWith("Visual.Vars.") && simEngine.isShowingLiveOutputs()) {
            propsItem.setEnabled(false);
        }
        menu.add(propsItem);

        JMenuItem renameItem = new JMenuItem("Rename Block...");
        renameItem.addActionListener(e -> {
            String newName = JOptionPane.showInputDialog(this, "Rename Block '" + block.getName() + "':", block.getName());
            if (newName != null && !newName.isBlank() && !newName.trim().equals(block.getName())) {
                exitSimOrWatchBeforeStructuralEdit();
                Page before = page.copy();
                String trimmed = newName.trim();
                block.setName(trimmed);
                for (Wire w : page.getWires()) {
                    if (w.getSourceBlockId().equals(block.getId()) && !w.isCustomNetName()) {
                        if (w.getSourcePinIndex() < block.getOutputs().size()) {
                            w.setNetName(trimmed + "." + block.getOutputs().get(w.getSourcePinIndex()).getShortName(), false);
                        }
                    }
                }
                editListener.onAfterPageEdit("Rename Block to " + trimmed, before, page);
                repaint();
            }
        });
        menu.add(renameItem);

        menu.addSeparator();

        JMenuItem dupItem = new JMenuItem("Duplicate (Ctrl+D / Cmd+D)");
        dupItem.addActionListener(e -> {
            exitSimOrWatchBeforeStructuralEdit();
            duplicateSelection();
        });
        menu.add(dupItem);

        JMenuItem delItem = new JMenuItem("Delete Block (Delete / Backspace)");
        delItem.addActionListener(e -> {
            exitSimOrWatchBeforeStructuralEdit();
            Page before = page.copy();
            page.getBlocks().remove(block);
            page.getWires().removeIf(w -> w.getSourceBlockId().equals(block.getId()) || w.getTargetBlockId().equals(block.getId()));
            selectedBlockIds.remove(block.getId());
            editListener.onAfterPageEdit("Delete Block " + block.getName(), before, page);
            repaint();
        });
        menu.add(delItem);

        menu.addSeparator();

        // Header Color submenu
        JMenu colorMenu = new JMenu("Header Color");
        for (HeaderColor hc : HeaderColor.values()) {
            JMenuItem ci = new JMenuItem(hc.getLabel());
            ci.addActionListener(e -> {
                exitSimOrWatchBeforeStructuralEdit();
                Page before = page.copy();
                block.setHeaderColor(hc);
                editListener.onAfterPageEdit("Change Header Color to " + hc.getLabel(), before, page);
                repaint();
            });
            colorMenu.add(ci);
        }
        menu.add(colorMenu);

        // Variable Inputs (for expandable logic blocks like AND, OR, MUX)
        if (block.getCustomProperties().containsKey("InputCount")) {
            int curCount = Integer.parseInt(block.getCustomProperties().getOrDefault("InputCount", "2"));
            JMenuItem addInItem = new JMenuItem("Add Input Pin (" + (curCount + 1) + ")");
            addInItem.setEnabled(curCount < 16);
            addInItem.addActionListener(e -> {
                exitSimOrWatchBeforeStructuralEdit();
                Page before = page.copy();
                block.setVariableInputCount(curCount + 1);
                editListener.onAfterPageEdit("Add Input Pin to " + block.getName(), before, page);
                updateCanvasPreferredSize();
                repaint();
            });
            menu.add(addInItem);

            JMenuItem remInItem = new JMenuItem("Remove Input Pin (" + (curCount - 1) + ")");
            remInItem.setEnabled(curCount > 2);
            remInItem.addActionListener(e -> {
                exitSimOrWatchBeforeStructuralEdit();
                Page before = page.copy();
                page.getWires().removeIf(w -> w.getTargetBlockId().equals(block.getId()) && w.getTargetPinIndex() == curCount - 1);
                block.setVariableInputCount(curCount - 1);
                editListener.onAfterPageEdit("Remove Input Pin from " + block.getName(), before, page);
                updateCanvasPreferredSize();
                repaint();
            });
            menu.add(remInItem);
        }

        // Thread and Execution Order quick item
        JMenuItem execPropsItem = new JMenuItem("Execution Properties (Thread " + block.getThreadId() + ", Order " + block.getOrder() + ")...");
        execPropsItem.addActionListener(e -> promptEditBlockExecution(page, block));
        menu.add(execPropsItem);

        menu.show(this, mx, my);
    }

    private void promptEditBlockExecution(Page page, BlockInstance block) {
        JPanel p = new JPanel(new GridLayout(2, 2, 6, 6));
        JComboBox<PlcThread> threadBox = new JComboBox<>(PlcThread.values());
        threadBox.setSelectedItem(PlcThread.fromId(block.getThreadId()));
        JSpinner orderSpin = new JSpinner(new SpinnerNumberModel(block.getOrder(), 1, 9999, 10));

        p.add(new JLabel("PLC Thread:"));
        p.add(threadBox);
        p.add(new JLabel("Execution Order:"));
        p.add(orderSpin);

        int res = JOptionPane.showConfirmDialog(this, p, "Execution Properties - " + block.getName(),
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (res == JOptionPane.OK_OPTION) {
            exitSimOrWatchBeforeStructuralEdit();
            Page before = page.copy();
            PlcThread selT = (PlcThread) threadBox.getSelectedItem();
            if (selT != null) block.setThreadId(selT.getId());
            block.setOrder((Integer) orderSpin.getValue());
            editListener.onAfterPageEdit("Edit Execution Properties " + block.getName(), before, page);
            repaint();
        }
    }

    private void showGraphicContextMenu(Page page, GraphicElement elem, int mx, int my) {
        JPopupMenu menu = new JPopupMenu();
        boolean lockedInSim = isVisualButtonLockedInLiveMode(elem);

        JMenuItem propsItem = new JMenuItem("Properties... (Double-Click)");
        propsItem.setFont(new Font(propsItem.getFont().getName(), Font.BOLD, propsItem.getFont().getSize()));
        propsItem.setEnabled(!lockedInSim);
        propsItem.addActionListener(e -> {
            if (isVisualButtonLockedInLiveMode(elem)) return;
            exitSimOrWatchBeforeStructuralEdit();
            Page before = page.copy();
            Frame owner = AppFonts.resolveFrameOwner(FbdCanvas.this);
            boolean changed = (elem.getKind() == GraphicElement.Kind.VISUAL_BUTTON)
                    ? VisualElementDialogs.showVisualButtonDialog(owner, page, elem)
                    : VisualElementDialogs.showGraphicPropertiesDialog(owner, page, elem);
            if (changed) {
                editListener.onAfterPageEdit("Edit Graphic Properties", before, page);
                repaint();
            }
        });
        menu.add(propsItem);

        menu.addSeparator();

        List<GraphicElement> graphics = page.getGraphics();
        int idx = graphics.indexOf(elem);
        boolean canGoUp = !lockedInSim && idx >= 0 && idx < graphics.size() - 1;
        boolean canGoDown = !lockedInSim && idx > 0;

        JMenuItem toFrontItem = new JMenuItem("Send to Foreground");
        toFrontItem.setEnabled(canGoUp);
        toFrontItem.addActionListener(e -> reorderGraphicElement(page, elem, ZOrderAction.SEND_TO_FOREGROUND));
        menu.add(toFrontItem);

        JMenuItem moveUpItem = new JMenuItem("Move Up");
        moveUpItem.setEnabled(canGoUp);
        moveUpItem.addActionListener(e -> reorderGraphicElement(page, elem, ZOrderAction.MOVE_UP));
        menu.add(moveUpItem);

        JMenuItem moveDownItem = new JMenuItem("Move Down");
        moveDownItem.setEnabled(canGoDown);
        moveDownItem.addActionListener(e -> reorderGraphicElement(page, elem, ZOrderAction.MOVE_DOWN));
        menu.add(moveDownItem);

        JMenuItem toBackItem = new JMenuItem("Send to Background");
        toBackItem.setEnabled(canGoDown);
        toBackItem.addActionListener(e -> reorderGraphicElement(page, elem, ZOrderAction.SEND_TO_BACKGROUND));
        menu.add(toBackItem);

        menu.addSeparator();

        JMenuItem dupItem = new JMenuItem("Duplicate (Ctrl+D / Cmd+D)");
        dupItem.setEnabled(!lockedInSim);
        dupItem.addActionListener(e -> {
            if (isVisualButtonLockedInLiveMode(elem)) return;
            exitSimOrWatchBeforeStructuralEdit();
            duplicateSelection();
        });
        menu.add(dupItem);

        JMenuItem delItem = new JMenuItem("Delete (Delete / Backspace)");
        delItem.setEnabled(!lockedInSim);
        delItem.addActionListener(e -> {
            if (isVisualButtonLockedInLiveMode(elem)) return;
            exitSimOrWatchBeforeStructuralEdit();
            Page before = page.copy();
            page.getGraphics().remove(elem);
            selectedGraphicIds.remove(elem.getId());
            editListener.onAfterPageEdit("Delete " + elem.getKind().getDisplayName(), before, page);
            repaint();
        });
        menu.add(delItem);

        menu.show(this, mx, my);
    }

    private void reorderGraphicElement(Page page, GraphicElement elem, ZOrderAction action) {
        if (isVisualButtonLockedInLiveMode(elem)) return;
        List<GraphicElement> graphics = page.getGraphics();
        int idx = graphics.indexOf(elem);
        if (idx < 0) return;

        int targetIdx = switch (action) {
            case SEND_TO_FOREGROUND -> graphics.size() - 1;
            case MOVE_UP -> Math.min(graphics.size() - 1, idx + 1);
            case MOVE_DOWN -> Math.max(0, idx - 1);
            case SEND_TO_BACKGROUND -> 0;
        };
        if (targetIdx == idx) return;

        exitSimOrWatchBeforeStructuralEdit();
        Page before = page.copy();
        int liveIdx = -1;
        for (int i = 0; i < graphics.size(); i++) {
            if (graphics.get(i).getId().equals(elem.getId())) {
                liveIdx = i;
                break;
            }
        }
        if (liveIdx < 0) return;
        GraphicElement moved = graphics.remove(liveIdx);
        graphics.add(targetIdx, moved);

        String label = switch (action) {
            case SEND_TO_FOREGROUND -> "Send to Foreground";
            case MOVE_UP -> "Move Up";
            case MOVE_DOWN -> "Move Down";
            case SEND_TO_BACKGROUND -> "Send to Background";
        };
        editListener.onAfterPageEdit(label + " (" + elem.getKind().getDisplayName() + ")", before, page);
        repaint();
    }

    private void showCanvasContextMenu(Page page, int gx, int gy, int mx, int my) {
        JPopupMenu menu = new JPopupMenu();

        JMenuItem findItem = new JMenuItem("Find Block... (Cmd+F / Ctrl+F)");
        findItem.addActionListener(e -> editListener.onTriggerFind());
        menu.add(findItem);

        JMenuItem pasteItem = new JMenuItem("Paste (Cmd+V / Ctrl+V)");
        pasteItem.setEnabled(canPaste());
        pasteItem.addActionListener(e -> pasteClipboard());
        menu.add(pasteItem);

        JMenuItem selAllItem = new JMenuItem("Select All (Cmd+A / Ctrl+A)");
        selAllItem.addActionListener(e -> selectAll());
        menu.add(selAllItem);

        menu.addSeparator();

        JMenuItem zoom100Item = new JMenuItem("Zoom 100%");
        zoom100Item.addActionListener(e -> zoom100());
        menu.add(zoom100Item);

        JMenuItem fitItem = new JMenuItem("Fit to Screen");
        fitItem.addActionListener(e -> fitToScreen());
        menu.add(fitItem);

        menu.show(this, mx, my);
    }

    private void showWireContextMenu(Page page, Wire wire, int segmentIndex, Point2D.Double clickGrid, int mx, int my) {
        JPopupMenu menu = new JPopupMenu();

        Bus hitBus = (!wire.isHidden() && wire.getBusId() != null) ? page.findBusByName(wire.getBusId()) : null;
        if (!wire.isHidden() && segmentIndex >= 0) {
            JMenuItem addPtItem = new JMenuItem(hitBus != null ? "Add Point to Bus" : "Add Point");
            addPtItem.addActionListener(e -> {
                exitSimOrWatchBeforeStructuralEdit();
                Page before = page.copy();
                if (hitBus != null) {
                    hitBus.addPointOnSegment(segmentIndex, new Point2D.Double(Math.round(clickGrid.x), Math.round(clickGrid.y)));
                    editListener.onAfterPageEdit("Add Point to Bus", before, page);
                } else {
                    wire.addPointOnSegment(segmentIndex, clickGrid);
                    editListener.onAfterPageEdit("Add Point to Wire " + wire.getNetName(), before, page);
                }
                repaint();
            });
            menu.add(addPtItem);

            if (hitBus != null && hitBus.isUserEditedRoute()) {
                JMenuItem resetBusItem = new JMenuItem("Reset Bus Route");
                resetBusItem.addActionListener(e -> {
                    exitSimOrWatchBeforeStructuralEdit();
                    Page before = page.copy();
                    hitBus.setUserEditedRoute(false);
                    editListener.onAfterPageEdit("Reset Bus Route", before, page);
                    repaint();
                });
                menu.add(resetBusItem);
            }
        }

        JMenuItem hideToggleItem = new JMenuItem(wire.isHidden() ? "Show Connection (Unhide)" : "Hide Connection");
        hideToggleItem.addActionListener(e -> {
            exitSimOrWatchBeforeStructuralEdit();
            Page before = page.copy();
            wire.setHidden(!wire.isHidden());
            if (wire.isHidden()) {
                wire.setBusId(null);
            }
            editListener.onAfterPageEdit((wire.isHidden() ? "Hide " : "Show ") + "Connection " + wire.getNetName(), before, page);
            repaint();
        });
        menu.add(hideToggleItem);

        JMenuItem renameNetItem = new JMenuItem("Rename Net...");
        renameNetItem.addActionListener(e -> {
            String input = JOptionPane.showInputDialog(this, "Enter Net / Label Name:", wire.getNetName());
            if (input != null && !input.isBlank()) {
                exitSimOrWatchBeforeStructuralEdit();
                Page before = page.copy();
                wire.setNetName(input.trim(), true);
                editListener.onAfterPageEdit("Rename Net to " + input.trim(), before, page);
                repaint();
            }
        });
        menu.add(renameNetItem);

        // Automatic Bus Bundling of all wires running from this wire's source block to destination block
        List<Wire> pairWires = findWiresBetweenBlockPair(page, wire.getSourceBlockId(), wire.getTargetBlockId());
        boolean isBundled = (wire.getBusId() != null);
        JMenuItem busToggleItem = new JMenuItem(isBundled
                ? "Unbundle Bus (" + pairWires.size() + " wires)"
                : "Bundle Block-Pair Wires into Bus (" + pairWires.size() + " wires)");
        busToggleItem.setEnabled(isBundled || pairWires.size() >= 2);
        busToggleItem.addActionListener(e -> {
            exitSimOrWatchBeforeStructuralEdit();
            Page before = page.copy();
            String newBusKey = isBundled ? null : (wire.getSourceBlockId() + "->" + wire.getTargetBlockId());
            for (Wire pw : pairWires) {
                pw.setBusId(newBusKey);
            }
            editListener.onAfterPageEdit(isBundled ? "Unbundle Bus" : "Bundle Wires into Bus", before, page);
            repaint();
        });
        menu.add(busToggleItem);

        menu.addSeparator();

        JMenuItem removeItem = new JMenuItem("Remove Connection");
        removeItem.addActionListener(e -> {
            exitSimOrWatchBeforeStructuralEdit();
            Page before = page.copy();
            page.getWires().remove(wire);
            selectedWireId = null;
            editListener.onAfterPageEdit("Remove Connection " + wire.getNetName(), before, page);
            repaint();
        });
        menu.add(removeItem);

        menu.show(this, mx, my);
    }

    // =========================================================================
    // Hit Detection & Hover Tooltips
    // =========================================================================

    private record PinHit(BlockInstance block, int pinIndex, boolean isInput) {}
    private record WireHit(Wire wire, Bus bus, int segmentIndex) {
        WireHit(Wire wire, int segmentIndex) {
            this(wire, null, segmentIndex);
        }
    }

    private PinHit findPinAtPixel(Page page, int px, int py) {
        int g = getGridUnitPx();
        for (int bi = page.getBlocks().size() - 1; bi >= 0; bi--) {
            BlockInstance b = page.getBlocks().get(bi);
            int bx = b.getGridX() * g;
            int by = b.getGridY() * g;
            int bw = b.getGridWidth() * g;
            if (b.isIoRef()) {
                if (b.isInputRef() && !b.getOutputs().isEmpty()) {
                    Rectangle pinRect = new Rectangle(bx + bw - g, by, g + 2, g);
                    if (pinRect.contains(px, py)) {
                        return new PinHit(b, 0, false);
                    }
                } else if (b.isOutputRef() && !b.getInputs().isEmpty()) {
                    Rectangle pinRect = new Rectangle(bx - 2, by, g + 2, g);
                    if (pinRect.contains(px, py)) {
                        return new PinHit(b, 0, true);
                    }
                }
                continue;
            }
            boolean isXVar = b.getDefinitionId().startsWith("Visual.Vars.");
            int pinW = isXVar ? g : BlockInstance.PIN_COL_GRID_WIDTH * g;
            int headerH = BlockInstance.HEADER_GRID_HEIGHT * g;

            for (int i = 0; i < b.getInputs().size(); i++) {
                int py0 = by + headerH + i * g;
                Rectangle pinRect = new Rectangle(bx - 2, py0, pinW + 2, g);
                if (pinRect.contains(px, py)) {
                    return new PinHit(b, i, true);
                }
            }
            for (int i = 0; i < b.getOutputs().size(); i++) {
                int py0 = by + headerH + i * g;
                Rectangle pinRect = new Rectangle(bx + bw - pinW, py0, pinW + 2, g);
                if (pinRect.contains(px, py)) {
                    return new PinHit(b, i, false);
                }
            }
        }
        return null;
    }

    private BlockInstance findBlockAtPixel(Page page, int px, int py) {
        int g = getGridUnitPx();
        for (int i = page.getBlocks().size() - 1; i >= 0; i--) {
            BlockInstance b = page.getBlocks().get(i);
            Rectangle r = new Rectangle(b.getGridX() * g, b.getGridY() * g, b.getGridWidth() * g, b.getGridHeight() * g);
            if (r.contains(px, py)) return b;
        }
        return null;
    }

    private GraphicElement findGraphicAtPixel(Page page, int px, int py) {
        int g = getGridUnitPx();
        for (int i = page.getGraphics().size() - 1; i >= 0; i--) {
            GraphicElement ge = page.getGraphics().get(i);
            Rectangle r = getGraphicPixelBounds(ge, g);
            if (new Rectangle(r.x - 3, r.y - 3, r.width + 6, r.height + 6).contains(px, py)) {
                return ge;
            }
        }
        return null;
    }

    private List<Map.Entry<ResizeHandle, Point>> getGraphicHandlePoints(GraphicElement elem, int g) {
        if (elem.getKind() == GraphicElement.Kind.TEXT) {
            return List.of();
        }
        if (elem.getKind() == GraphicElement.Kind.LINE) {
            int x1 = elem.getGridX() * g;
            int y1 = elem.getGridY() * g;
            int x2 = (elem.getGridX() + elem.getGridW()) * g;
            int y2 = (elem.getGridY() + elem.getGridH()) * g;
            return List.of(
                    Map.entry(ResizeHandle.LINE_START, new Point(x1, y1)),
                    Map.entry(ResizeHandle.LINE_END, new Point(x2, y2))
            );
        }
        Rectangle r = getGraphicPixelBounds(elem, g);
        int x0 = r.x;
        int y0 = r.y;
        int xm = r.x + r.width / 2;
        int ym = r.y + r.height / 2;
        int x1 = r.x + r.width;
        int y1 = r.y + r.height;
        return List.of(
                Map.entry(ResizeHandle.NW, new Point(x0, y0)),
                Map.entry(ResizeHandle.N, new Point(xm, y0)),
                Map.entry(ResizeHandle.NE, new Point(x1, y0)),
                Map.entry(ResizeHandle.E, new Point(x1, ym)),
                Map.entry(ResizeHandle.SE, new Point(x1, y1)),
                Map.entry(ResizeHandle.S, new Point(xm, y1)),
                Map.entry(ResizeHandle.SW, new Point(x0, y1)),
                Map.entry(ResizeHandle.W, new Point(x0, ym))
        );
    }

    private GraphicResizeHit findGraphicResizeHandle(Page page, int px, int py) {
        if (selectedGraphicIds.isEmpty()) return null;
        int g = getGridUnitPx();
        int tol = 5;
        for (int i = page.getGraphics().size() - 1; i >= 0; i--) {
            GraphicElement ge = page.getGraphics().get(i);
            if (!selectedGraphicIds.contains(ge.getId())) continue;
            if (isVisualButtonLockedInLiveMode(ge)) continue;
            for (Map.Entry<ResizeHandle, Point> entry : getGraphicHandlePoints(ge, g)) {
                Point p = entry.getValue();
                if (Math.abs(px - p.x) <= tol && Math.abs(py - p.y) <= tol) {
                    return new GraphicResizeHit(ge, entry.getKey());
                }
            }
        }
        return null;
    }

    private static Cursor cursorForResizeHandle(ResizeHandle h) {
        return Cursor.getPredefinedCursor(switch (h) {
            case NW -> Cursor.NW_RESIZE_CURSOR;
            case N -> Cursor.N_RESIZE_CURSOR;
            case NE -> Cursor.NE_RESIZE_CURSOR;
            case E -> Cursor.E_RESIZE_CURSOR;
            case SE -> Cursor.SE_RESIZE_CURSOR;
            case S -> Cursor.S_RESIZE_CURSOR;
            case SW -> Cursor.SW_RESIZE_CURSOR;
            case W -> Cursor.W_RESIZE_CURSOR;
            case LINE_START, LINE_END -> Cursor.CROSSHAIR_CURSOR;
        });
    }

    private WireHit findWireOrFlagAtPixel(Page page, int px, int py) {
        int g = getGridUnitPx();
        double tolerance = Math.max(4.0, g * 0.45);

        for (Wire w : page.getWires()) {
            BlockInstance src = page.findBlockById(w.getSourceBlockId());
            BlockInstance dst = page.findBlockById(w.getTargetBlockId());
            if (src == null || dst == null) continue;

            if (w.isHidden()) {
                int flagW = 4 * g;
                if (w.getSourcePinIndex() < src.getOutputs().size()) {
                    Point2D.Double pOut = src.getOutputPinAnchorGrid(w.getSourcePinIndex());
                    Rectangle rOut = new Rectangle((int) Math.round(pOut.x * g), (int) Math.round(pOut.y * g - g / 2.0), flagW, g);
                    if (rOut.contains(px, py)) return new WireHit(w, -1);
                }
                if (w.getTargetPinIndex() < dst.getInputs().size()) {
                    Point2D.Double pIn = dst.getInputPinAnchorGrid(w.getTargetPinIndex());
                    Rectangle rIn = new Rectangle((int) Math.round(pIn.x * g - flagW), (int) Math.round(pIn.y * g - g / 2.0), flagW, g);
                    if (rIn.contains(px, py)) return new WireHit(w, -1);
                }
                continue;
            }

            if (w.getBusId() != null) {
                Bus bus = page.findBusByName(w.getBusId());
                if (bus != null && bus.getGridPoints().size() >= 2) {
                    List<Point2D.Double> bPts = bus.getGridPoints();
                    for (int i = 0; i < bPts.size() - 1; i++) {
                        Point2D.Double a = bPts.get(i);
                        Point2D.Double b = bPts.get(i + 1);
                        double dist = Line2D.ptSegDist(a.x * g, a.y * g, b.x * g, b.y * g, px, py);
                        if (dist <= tolerance + 2.0) {
                            return new WireHit(w, bus, i);
                        }
                    }
                }
                continue;
            }

            List<Point2D.Double> pts = w.getGridPoints();
            for (int i = 0; i < pts.size() - 1; i++) {
                Point2D.Double a = pts.get(i);
                Point2D.Double b = pts.get(i + 1);
                double dist = Line2D.ptSegDist(a.x * g, a.y * g, b.x * g, b.y * g, px, py);
                if (dist <= tolerance) {
                    return new WireHit(w, i);
                }
            }
        }
        return null;
    }

    @Override
    public String getToolTipText(MouseEvent event) {
        Page page = getActivePage();
        if (page == null) return null;
        int mx = event.getX();
        int my = event.getY();
        int g = getGridUnitPx();

        PinHit pinHit = findPinAtPixel(page, mx, my);
        if (pinHit != null) {
            PinInstance p = pinHit.isInput
                    ? pinHit.block.getInputs().get(pinHit.pinIndex)
                    : pinHit.block.getOutputs().get(pinHit.pinIndex);
            return p.getFullName() + " [" + p.getDataType().getDisplayName() + "] (Right-click to Add/Remove on Scope)";
        }

        if (simEngine.isShowingLiveOutputs()) {
            for (BlockInstance b : page.getBlocks()) {
                int bx = b.getGridX() * g;
                int by = b.getGridY() * g;
                int bw = b.getGridWidth() * g;
                int headerH = BlockInstance.HEADER_GRID_HEIGHT * g;
                for (int i = 0; i < b.getOutputs().size(); i++) {
                    PinInstance out = b.getOutputs().get(i);
                    Rectangle outBox = new Rectangle(bx + bw, by + headerH + i * g, 4 * g, g);
                    if (outBox.contains(mx, my)) {
                        return b.getName() + "." + out.getFullName() + " = " + out.getFormattedRuntimeValue(false);
                    }
                }
            }
        }

        BlockInstance block = findBlockAtPixel(page, mx, my);
        if (block != null) {
            if (!block.getDescription().isBlank()) {
                return block.getName() + " (" + block.getTypeName() + "): " + block.getDescription();
            }
            return block.getName() + " [" + block.getTypeName() + ", T:" + block.getThreadId() + ", O:" + block.getOrder() + "]";
        }
        return null;
    }

    // =========================================================================
    // Rendering Pipeline (Strict Non-Antialiased Geometry + Antialiased Text Only)
    // =========================================================================

    public Rectangle computeExportBounds(Page page, int g) {
        return FbdRenderer.computeExportBounds(page, g);
    }

    /**
     * Renders {@code page} onto {@code g2} for SVG/PDF/PNG export (no background grid or selection UI).
     */
    public void paintPageForExport(Graphics2D g2, Page page, int g) {
        FbdRenderer.paintPage(g2, page, g, FbdRenderer.RenderOptions.forExport());
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Page page = getActivePage();
        if (page == null) return;

        Graphics2D g2 = (Graphics2D) g.create();
        int gridPx = getGridUnitPx();

        if (showGrid) {
            paintDottedGrid(g2, gridPx);
        }

        boolean live = simEngine.isShowingLiveOutputs();
        FbdRenderer.RenderOptions opts = new FbdRenderer.RenderOptions(
                live,
                showExecutionInfo,
                false,
                AppSettings.get().isAntialiasText(),
                selectedBlockIds,
                selectedWireId,
                selectedBusId,
                selectedGraphicIds,
                live
        );
        FbdRenderer.paintPage(g2, page, gridPx, opts);

        if (creatingWire && wireStartPixel != null && wireCurrentPixel != null) {
            g2.setColor(new Color(0x00, 0x66, 0xCC));
            g2.setStroke(new BasicStroke(1.2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[]{4f, 4f}, 0f));
            g2.drawLine(wireStartPixel.x, wireStartPixel.y, wireCurrentPixel.x, wireCurrentPixel.y);
        }

        if (marqueePixelRect != null) {
            g2.setColor(new Color(0x00, 0x78, 0xD7, 35));
            g2.fillRect(marqueePixelRect.x, marqueePixelRect.y, marqueePixelRect.width, marqueePixelRect.height);
            g2.setColor(new Color(0x00, 0x78, 0xD7));
            g2.setStroke(new BasicStroke(1.0f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[]{3f, 3f}, 0f));
            g2.drawRect(marqueePixelRect.x, marqueePixelRect.y, marqueePixelRect.width, marqueePixelRect.height);
        }

        g2.dispose();
    }

    private int cachedGridStepPx = -1;
    private java.awt.TexturePaint cachedGridPaint = null;
    private static final Color GRID_MINOR_DOT = new Color(0xBB, 0xC3, 0xCD);
    private static final Color GRID_MAJOR_DOT = new Color(0x7F, 0x8C, 0x9B);

    private void paintDottedGrid(Graphics2D g2, int g) {
        if (g <= 0) return;
        if (cachedGridPaint == null || cachedGridStepPx != g) {
            int tileSize = g * 10;
            java.awt.image.BufferedImage tile = new java.awt.image.BufferedImage(tileSize, tileSize, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            Graphics2D tg = tile.createGraphics();
            tg.setColor(GRID_MINOR_DOT);
            for (int gx = 0; gx < 10; gx++) {
                int px = gx * g;
                for (int gy = 0; gy < 10; gy++) {
                    if (gx == 0 && gy == 0) continue;
                    tg.fillRect(px, gy * g, 1, 1);
                }
            }
            tg.setColor(GRID_MAJOR_DOT);
            tg.fillRect(0, 0, 2, 2);
            tg.dispose();
            cachedGridPaint = new java.awt.TexturePaint(tile, new Rectangle(0, 0, tileSize, tileSize));
            cachedGridStepPx = g;
        }

        Rectangle clip = g2.getClipBounds();
        if (clip == null) clip = new Rectangle(0, 0, getWidth(), getHeight());
        java.awt.Paint oldPaint = g2.getPaint();
        g2.setPaint(cachedGridPaint);
        g2.fillRect(clip.x, clip.y, clip.width, clip.height);
        g2.setPaint(oldPaint);
    }

    public static void syncVisualValuePinDataTypes(Page page) {
        FbdRenderer.syncVisualValuePinDataTypes(page);
    }

    public String getSelectedSingleBlockDefinitionId() {
        if (selectedBlockIds.size() != 1) return null;
        Page page = getActivePage();
        if (page == null) return null;
        String id = selectedBlockIds.iterator().next();
        BlockInstance b = page.findBlockById(id);
        return b != null ? b.getDefinitionId() : null;
    }

    public void updateCanvasPreferredSize() {
        Page page = getActivePage();
        int g = getGridUnitPx();
        int maxW = 140 * g;
        int maxH = 90 * g;
        if (page != null) {
            for (BlockInstance b : page.getBlocks()) {
                maxW = Math.max(maxW, (b.getGridX() + b.getGridWidth() + 20) * g);
                maxH = Math.max(maxH, (b.getGridY() + b.getGridHeight() + 15) * g);
            }
        }
        setPreferredSize(new Dimension(maxW, maxH));
        revalidate();
    }
}
