package cz.maramat.ui.canvas;

import cz.maramat.model.BlockCatalog;
import cz.maramat.model.BlockDefinition;
import cz.maramat.model.BlockInstance;
import cz.maramat.model.DataType;
import cz.maramat.model.GraphicElement;
import cz.maramat.model.Page;
import cz.maramat.model.PinInstance;
import cz.maramat.model.Project;
import cz.maramat.model.Wire;
import cz.maramat.settings.AppSettings;
import cz.maramat.sim.SimulationEngine;
import cz.maramat.ui.dialogs.ModulePropertiesDialog;
import cz.maramat.ui.dialogs.VisualElementDialogs;
import cz.maramat.ui.toolbar.EditorToolbar.ToolMode;

import javax.swing.BorderFactory;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JTextField;
import javax.swing.JViewport;
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

    // Blender-style wire edge dragging state
    private Wire draggingWire = null;
    private int draggingWireSegmentIndex = -1;
    private Page wireEditBeforeSnapshot = null;

    // Shape creation drag state
    private GraphicElement creatingGraphic = null;
    private Page graphicBeforeSnapshot = null;

    // Middle-mouse panning state
    private boolean panningWithMmb = false;
    private Point panStartScreenPoint = null;
    private Point panStartViewPosition = null;

    // Inline constant editor JTextField
    private JTextField activeConstantField = null;

    // Visual Button momentary press tracking
    private GraphicElement heldVisualButton = null;

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

    public Page getActivePage() {
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

    public void selectAll() {
        Page page = getActivePage();
        if (page == null) return;
        selectedBlockIds.clear();
        selectedGraphicIds.clear();
        for (BlockInstance b : page.getBlocks()) selectedBlockIds.add(b.getId());
        for (GraphicElement g : page.getGraphics()) selectedGraphicIds.add(g.getId());
        repaint();
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
            if (selectedGraphicIds.contains(g.getId())) {
                clipboardGraphics.add(g.copy());
            }
        }
    }

    public void cutSelection() {
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
                nw.getGridPoints().forEach(pt -> pt.translate(3, 3));
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

    public void duplicateSelection() {
        copySelection();
        pasteClipboard();
    }

    public void deleteSelection() {
        Page page = getActivePage();
        if (page == null) return;
        if (selectedBlockIds.isEmpty() && selectedGraphicIds.isEmpty() && selectedWireId == null) return;

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
        if (!selectedGraphicIds.isEmpty()) {
            page.getGraphics().removeIf(g -> selectedGraphicIds.contains(g.getId()));
            selectedGraphicIds.clear();
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
                    BlockDefinition def = BlockCatalog.getById(defId);
                    Page page = getActivePage();
                    if (def == null || page == null) {
                        dtde.dropComplete(false);
                        return;
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
        if (elem.getKind() == GraphicElement.Kind.TEXT) {
            Font f = new Font("Segoe UI", Font.PLAIN, Math.max(8, (elem.getFontSize() * g) / 10));
            FontMetrics fm = getFontMetrics(f);
            String txt = elem.getText() == null ? "" : elem.getText();
            int tw = Math.max(g, fm.stringWidth(txt) + 6);
            int th = Math.max(g, fm.getHeight() + 2);
            int rx = elem.getGridX() * g;
            int ry = elem.getGridY() * g;
            return new Rectangle(rx, ry, tw, th);
        }
        int x1 = elem.getGridX() * g;
        int y1 = elem.getGridY() * g;
        int x2 = (elem.getGridX() + elem.getGridW()) * g;
        int y2 = (elem.getGridY() + elem.getGridH()) * g;
        int rx = Math.min(x1, x2);
        int ry = Math.min(y1, y2);
        int rw = Math.max(g, Math.abs(x2 - x1));
        int rh = Math.max(g, Math.abs(y2 - y1));
        return new Rectangle(rx, ry, rw, rh);
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

                // Right-Click Context Menu on Pin, Wire, or Hidden Flag
                if (SwingUtilities.isRightMouseButton(e)) {
                    PinHit pinHit = findPinAtPixel(page, mx, my);
                    if (pinHit != null) {
                        showPinScopeContextMenu(pinHit, mx, my);
                        return;
                    }
                    WireHit hit = findWireOrFlagAtPixel(page, mx, my);
                    if (hit != null) {
                        selectedWireId = hit.wire.getId();
                        repaint();
                        showWireContextMenu(page, hit.wire, hit.segmentIndex, new Point(gx, gy), mx, my);
                    }
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
                        Frame owner = (Frame) SwingUtilities.getWindowAncestor(FbdCanvas.this);
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
                    Point anchorG = wireStartIsOutput
                            ? wireStartBlock.getOutputPinAnchorGrid(wireStartPinIndex)
                            : wireStartBlock.getInputPinAnchorGrid(wireStartPinIndex);
                    wireStartPixel = new Point(anchorG.x * g, anchorG.y * g);
                    wireCurrentPixel = new Point(mx, my);
                    repaint();
                    return;
                }

                // 2. Check if clicking a Block
                BlockInstance hitBlock = findBlockAtPixel(page, mx, my);
                if (hitBlock != null) {
                    if (e.getClickCount() == 2) {
                        onBlockDoubleClicked(page, hitBlock);
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
                    if (e.getClickCount() == 2) {
                        exitSimOrWatchBeforeStructuralEdit();
                        Page before = page.copy();
                        Frame owner = (Frame) SwingUtilities.getWindowAncestor(FbdCanvas.this);
                        boolean changed = (hitGraphic.getKind() == GraphicElement.Kind.VISUAL_BUTTON)
                                ? VisualElementDialogs.showVisualButtonDialog(owner, page, hitGraphic)
                                : VisualElementDialogs.showGraphicPropertiesDialog(owner, hitGraphic);
                        if (changed) {
                            editListener.onAfterPageEdit("Edit Graphic Properties", before, page);
                            repaint();
                        }
                        return;
                    }

                    if (hitGraphic.getKind() == GraphicElement.Kind.VISUAL_BUTTON && simEngine.isShowingLiveOutputs()) {
                        if (hitGraphic.isToggleButton()) {
                            hitGraphic.setActiveState(!hitGraphic.isActiveState());
                        } else {
                            hitGraphic.setActiveState(true);
                            heldVisualButton = hitGraphic;
                        }
                        repaint();
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

                // 4. Check if clicking a Wire Segment for Blender-style Edge Dragging
                WireHit wireHit = findWireOrFlagAtPixel(page, mx, my);
                if (wireHit != null && !wireHit.wire.isHidden() && wireHit.segmentIndex >= 0) {
                    exitSimOrWatchBeforeStructuralEdit();
                    selectedBlockIds.clear();
                    selectedGraphicIds.clear();
                    selectedWireId = wireHit.wire.getId();
                    draggingWire = wireHit.wire;
                    draggingWireSegmentIndex = wireHit.segmentIndex;
                    wireEditBeforeSnapshot = page.copy();
                    repaint();
                    return;
                }

                // 5. Empty canvas click -> Start Selection Rectangle (Marquee)
                if ((e.getModifiersEx() & (KeyEvent.CTRL_DOWN_MASK | KeyEvent.META_DOWN_MASK)) == 0) {
                    selectedBlockIds.clear();
                    selectedGraphicIds.clear();
                    selectedWireId = null;
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
                    List<Point> pts = draggingWire.getGridPoints();
                    if (draggingWireSegmentIndex < pts.size() - 1) {
                        Point a = pts.get(draggingWireSegmentIndex);
                        Point b = pts.get(draggingWireSegmentIndex + 1);
                        boolean horizontal = (a.y == b.y && a.x != b.x);
                        int targetCoord = horizontal ? gy : gx;
                        draggingWireSegmentIndex = draggingWire.moveSegmentEdge(draggingWireSegmentIndex, targetCoord);
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
                        if (marqueePixelRect.intersects(getGraphicPixelBounds(ge, g))) {
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

                Page page = getActivePage();
                if (page == null) return;

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
                        Point startG = srcBlock.getOutputPinAnchorGrid(srcPin);
                        Point endG = dstBlock.getInputPinAnchorGrid(dstPin);
                        Wire newWire = new Wire(srcBlock.getId(), srcPin, dstBlock.getId(), dstPin, netName, startG, endG);
                        page.getWires().add(newWire);

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
                WireHit hit = findWireOrFlagAtPixel(page, e.getX(), e.getY());
                if (hit != null && !hit.wire.isHidden() && hit.wire.getBusId() == null && hit.segmentIndex >= 0) {
                    List<Point> pts = hit.wire.getGridPoints();
                    Point a = pts.get(hit.segmentIndex);
                    Point b = pts.get(hit.segmentIndex + 1);
                    boolean horizontal = (a.y == b.y);
                    setCursor(Cursor.getPredefinedCursor(horizontal ? Cursor.N_RESIZE_CURSOR : Cursor.E_RESIZE_CURSOR));
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
            if (selectedGraphicIds.contains(ge.getId())) {
                initialGraphicPositions.put(ge.getId(), new Point(ge.getGridX(), ge.getGridY()));
            }
        }
    }

    private void onBlockDoubleClicked(Page page, BlockInstance block) {
        Frame owner = (Frame) SwingUtilities.getWindowAncestor(this);
        if (block.getDefinitionId().startsWith("Visual.Vars.") && simEngine.isShowingLiveOutputs()) {
            VisualElementDialogs.triggerXVarAction(owner, block, this::repaint);
            return;
        }
        if (block.getDefinitionId().startsWith("Macro.User.") && simEngine.isShowingLiveOutputs()) {
            editListener.onInspectMacroPage(block.getTypeName());
            return;
        }
        exitSimOrWatchBeforeStructuralEdit();
        Page before = page.copy();
        ModulePropertiesDialog dlg = new ModulePropertiesDialog(owner, page, block);
        dlg.setVisible(true);
        if (dlg.isConfirmed()) {
            if (page.isMacro() && (block.getDefinitionId().equals("Macro.MacroIn") || block.getDefinitionId().equals("Macro.MacroOut"))) {
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
    // Wire Right-Click Context Menu (Includes Automatic Block-Pair Bus Bundling)
    // =========================================================================

    private void showWireContextMenu(Page page, Wire wire, int segmentIndex, Point clickGrid, int mx, int my) {
        JPopupMenu menu = new JPopupMenu();

        if (!wire.isHidden() && wire.getBusId() == null && segmentIndex >= 0) {
            JMenuItem addPtItem = new JMenuItem("Add Point");
            addPtItem.addActionListener(e -> {
                exitSimOrWatchBeforeStructuralEdit();
                Page before = page.copy();
                wire.addPointOnSegment(segmentIndex, clickGrid);
                editListener.onAfterPageEdit("Add Point to Wire " + wire.getNetName(), before, page);
                repaint();
            });
            menu.add(addPtItem);
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
    private record WireHit(Wire wire, int segmentIndex) {}

    private PinHit findPinAtPixel(Page page, int px, int py) {
        int g = getGridUnitPx();
        for (int bi = page.getBlocks().size() - 1; bi >= 0; bi--) {
            BlockInstance b = page.getBlocks().get(bi);
            int bx = b.getGridX() * g;
            int by = b.getGridY() * g;
            int bw = b.getGridWidth() * g;
            int pinW = BlockInstance.PIN_COL_GRID_WIDTH * g;
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
                    Point pOut = src.getOutputPinAnchorGrid(w.getSourcePinIndex());
                    Rectangle rOut = new Rectangle(pOut.x * g, pOut.y * g - g, flagW, g);
                    if (rOut.contains(px, py)) return new WireHit(w, -1);
                }
                if (w.getTargetPinIndex() < dst.getInputs().size()) {
                    Point pIn = dst.getInputPinAnchorGrid(w.getTargetPinIndex());
                    Rectangle rIn = new Rectangle(pIn.x * g - flagW, pIn.y * g - g, flagW, g);
                    if (rIn.contains(px, py)) return new WireHit(w, -1);
                }
                continue;
            }

            if (w.getBusId() != null) {
                // Hit-test bus trunk and tap stubs
                Point pOut = src.getOutputPinAnchorGrid(w.getSourcePinIndex());
                Point pIn = dst.getInputPinAnchorGrid(w.getTargetPinIndex());
                int x1 = pOut.x * g;
                int y1 = pOut.y * g;
                int x2 = pIn.x * g;
                int y2 = pIn.y * g;
                if (Line2D.ptSegDist(x1, y1, x2, y2, px, py) <= tolerance * 2) {
                    return new WireHit(w, 0);
                }
                continue;
            }

            List<Point> pts = w.getGridPoints();
            for (int i = 0; i < pts.size() - 1; i++) {
                Point a = pts.get(i);
                Point b = pts.get(i + 1);
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

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Page page = getActivePage();
        if (page == null) return;

        Graphics2D g2 = (Graphics2D) g.create();
        // STRICT RULE: Antialiasing ONLY on text, NEVER on geometric elements!
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        g2.setRenderingHint(
                RenderingHints.KEY_TEXT_ANTIALIASING,
                AppSettings.get().isAntialiasText()
                        ? RenderingHints.VALUE_TEXT_ANTIALIAS_ON
                        : RenderingHints.VALUE_TEXT_ANTIALIAS_OFF
        );

        int gridPx = getGridUnitPx();

        // 1. Dotted Workspace Placement Grid
        if (showGrid) {
            paintDottedGrid(g2, gridPx);
        }

        // 2. Decorative Shapes, Lines, Text, & Visual Buttons
        paintGraphicElements(g2, page, gridPx);

        // 3. Busses & Wires (Split-Color, Active BOOL 4x Width, Fan-out Dots, Hidden KiCad Flags)
        paintBussesAndWires(g2, page, gridPx);

        // 4. FBD Blocks, White-Text Headers, Centered T:/O:, Pin Containers, (=x) Constants, & Live Values
        paintBlocks(g2, page, gridPx);

        // 5. Rubber-band Wire Creation Preview Line
        if (creatingWire && wireStartPixel != null && wireCurrentPixel != null) {
            g2.setColor(new Color(0x00, 0x66, 0xCC));
            g2.setStroke(new BasicStroke(1.2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[]{4f, 4f}, 0f));
            g2.drawLine(wireStartPixel.x, wireStartPixel.y, wireCurrentPixel.x, wireCurrentPixel.y);
        }

        // 6. Marquee Selection Rectangle
        if (marqueePixelRect != null) {
            g2.setColor(new Color(0x00, 0x78, 0xD7, 35));
            g2.fillRect(marqueePixelRect.x, marqueePixelRect.y, marqueePixelRect.width, marqueePixelRect.height);
            g2.setColor(new Color(0x00, 0x78, 0xD7));
            g2.setStroke(new BasicStroke(1.0f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[]{3f, 3f}, 0f));
            g2.drawRect(marqueePixelRect.x, marqueePixelRect.y, marqueePixelRect.width, marqueePixelRect.height);
        }

        g2.dispose();
    }

    private void paintDottedGrid(Graphics2D g2, int g) {
        Rectangle clip = g2.getClipBounds();
        if (clip == null) clip = new Rectangle(0, 0, getWidth(), getHeight());

        int startGx = Math.max(1, clip.x / g);
        int endGx = (clip.x + clip.width) / g + 1;
        int startGy = Math.max(1, clip.y / g);
        int endGy = (clip.y + clip.height) / g + 1;

        // Balanced contrast: clearly visible without being overly bright/distracting
        Color minorDot = new Color(0xBB, 0xC3, 0xCD);
        Color majorDot = new Color(0x7F, 0x8C, 0x9B);

        for (int gx = startGx; gx <= endGx; gx++) {
            int px = gx * g;
            boolean majorX = (gx % 10 == 0);
            for (int gy = startGy; gy <= endGy; gy++) {
                int py = gy * g;
                if (majorX && (gy % 10 == 0)) {
                    g2.setColor(majorDot);
                    g2.fillRect(px - 1, py - 1, 2, 2);
                } else {
                    g2.setColor(minorDot);
                    g2.fillRect(px, py, 1, 1);
                }
            }
        }
    }

    private void paintGraphicElements(Graphics2D g2, Page page, int g) {
        for (GraphicElement elem : page.getGraphics()) {
            Rectangle bounds = getGraphicPixelBounds(elem, g);
            int rx = bounds.x;
            int ry = bounds.y;
            int rw = bounds.width;
            int rh = bounds.height;

            if (elem.getKind() == GraphicElement.Kind.VISUAL_BUTTON) {
                boolean pressed = elem.isActiveState();
                g2.setColor(pressed ? new Color(0x76, 0xD7, 0xC4) : new Color(0xE2, 0xE6, 0xEA));
                g2.fillRect(rx, ry, rw, rh);
                g2.setColor(Color.BLACK);
                g2.setStroke(new BasicStroke(1.0f));
                g2.drawRect(rx, ry, rw, rh);
                g2.setColor(pressed ? new Color(0x88, 0x88, 0x88) : Color.WHITE);
                g2.drawLine(rx + 1, ry + 1, rx + rw - 2, ry + 1);
                g2.drawLine(rx + 1, ry + 1, rx + 1, ry + rh - 2);

                g2.setColor(Color.BLACK);
                g2.setFont(new Font("Segoe UI", Font.BOLD, Math.max(9, g)));
                FontMetrics fm = g2.getFontMetrics();
                String label = elem.getText();
                int tx = rx + (rw - fm.stringWidth(label)) / 2;
                int ty = ry + (rh + fm.getAscent() - fm.getDescent()) / 2;
                g2.drawString(label, tx, ty);

                if (!elem.getBoundTargetPin().isBlank()) {
                    g2.setFont(new Font("SansSerif", Font.PLAIN, Math.max(7, g - 2)));
                    g2.setColor(new Color(0x44, 0x44, 0x44));
                    g2.drawString("-> " + elem.getBoundTargetPin(), rx, ry - 2);
                }
            } else {
                Stroke stroke = elem.isDashed()
                        ? new BasicStroke(elem.getStrokeWidth(), BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[]{5f, 4f}, 0f)
                        : new BasicStroke(elem.getStrokeWidth());
                g2.setStroke(stroke);

                switch (elem.getKind()) {
                    case LINE -> {
                        int x1 = elem.getGridX() * g;
                        int y1 = elem.getGridY() * g;
                        int x2 = (elem.getGridX() + elem.getGridW()) * g;
                        int y2 = (elem.getGridY() + elem.getGridH()) * g;
                        g2.setColor(elem.getStrokeColor());
                        g2.drawLine(x1, y1, x2, y2);
                    }
                    case RECTANGLE -> {
                        if (elem.isFilled()) {
                            g2.setColor(elem.getFillColor());
                            g2.fillRect(rx, ry, rw, rh);
                        }
                        g2.setColor(elem.getStrokeColor());
                        g2.drawRect(rx, ry, rw, rh);
                    }
                    case ROUNDED_RECTANGLE -> {
                        int arc = Math.max(4, g);
                        if (elem.isFilled()) {
                            g2.setColor(elem.getFillColor());
                            g2.fillRoundRect(rx, ry, rw, rh, arc, arc);
                        }
                        g2.setColor(elem.getStrokeColor());
                        g2.drawRoundRect(rx, ry, rw, rh, arc, arc);
                    }
                    case TRIANGLE -> drawNgon(g2, elem, rx, ry, rw, rh, 3);
                    case PENTAGON -> drawNgon(g2, elem, rx, ry, rw, rh, 5);
                    case HEXAGON -> drawNgon(g2, elem, rx, ry, rw, rh, 6);
                    case OCTAGON -> drawNgon(g2, elem, rx, ry, rw, rh, 8);
                    case CUSTOM_NGON -> drawNgon(g2, elem, rx, ry, rw, rh, elem.getNgonSides());
                    case CIRCLE -> {
                        int d = Math.min(rw, rh);
                        if (elem.isFilled()) {
                            g2.setColor(elem.getFillColor());
                            g2.fillOval(rx, ry, d, d);
                        }
                        g2.setColor(elem.getStrokeColor());
                        g2.drawOval(rx, ry, d, d);
                    }
                    case ELLIPSE -> {
                        if (elem.isFilled()) {
                            g2.setColor(elem.getFillColor());
                            g2.fillOval(rx, ry, rw, rh);
                        }
                        g2.setColor(elem.getStrokeColor());
                        g2.drawOval(rx, ry, rw, rh);
                    }
                    case ARC -> {
                        g2.setColor(elem.getStrokeColor());
                        g2.drawArc(rx, ry, rw, rh, 20, 220);
                    }
                    case SECTOR -> {
                        if (elem.isFilled()) {
                            g2.setColor(elem.getFillColor());
                            g2.fillArc(rx, ry, rw, rh, 30, 240);
                        }
                        g2.setColor(elem.getStrokeColor());
                        g2.drawArc(rx, ry, rw, rh, 30, 240);
                    }
                    case TEXT -> {
                        g2.setColor(elem.getStrokeColor());
                        Font f = new Font("Segoe UI", Font.PLAIN, Math.max(8, (elem.getFontSize() * g) / 10));
                        g2.setFont(f);
                        FontMetrics fm = g2.getFontMetrics();
                        g2.drawString(elem.getText(), rx + 2, ry + fm.getAscent());
                    }
                    default -> {}
                }
            }

            if (selectedGraphicIds.contains(elem.getId())) {
                g2.setColor(new Color(0x00, 0x66, 0xCC));
                g2.setStroke(new BasicStroke(1.0f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[]{2f, 2f}, 0f));
                g2.drawRect(rx - 1, ry - 1, rw + 2, rh + 2);
            }
        }
        g2.setStroke(new BasicStroke(1.0f));
    }

    private void drawNgon(Graphics2D g2, GraphicElement elem, int rx, int ry, int rw, int rh, int sides) {
        int[] xs = new int[sides];
        int[] ys = new int[sides];
        double cx = rx + rw / 2.0;
        double cy = ry + rh / 2.0;
        double radX = rw / 2.0;
        double radY = rh / 2.0;
        for (int i = 0; i < sides; i++) {
            double ang = -Math.PI / 2 + (2 * Math.PI * i) / sides;
            xs[i] = (int) Math.round(cx + radX * Math.cos(ang));
            ys[i] = (int) Math.round(cy + radY * Math.sin(ang));
        }
        if (elem.isFilled()) {
            g2.setColor(elem.getFillColor());
            g2.fillPolygon(xs, ys, sides);
        }
        g2.setColor(elem.getStrokeColor());
        g2.drawPolygon(xs, ys, sides);
    }

    private void paintBussesAndWires(Graphics2D g2, Page page, int g) {
        boolean liveMode = simEngine.isShowingLiveOutputs();

        // Group bundled wires by their block-pair busId (srcBlockId->dstBlockId)
        Map<String, List<Wire>> busBundles = new LinkedHashMap<>();
        Map<String, List<Wire>> wiresBySourcePin = new HashMap<>();

        for (Wire w : page.getWires()) {
            if (!w.isHidden()) {
                if (w.getBusId() != null) {
                    busBundles.computeIfAbsent(w.getBusId(), k -> new ArrayList<>()).add(w);
                } else {
                    String key = w.getSourceBlockId() + ":" + w.getSourcePinIndex();
                    wiresBySourcePin.computeIfAbsent(key, k -> new ArrayList<>()).add(w);
                }
            }
        }

        // 1. Paint Block-Pair Bus Bundles (KiCad style: 45-deg tap into vertical collector -> single thick Bus -> 45-deg tap into target pins)
        for (List<Wire> bundle : busBundles.values()) {
            if (bundle.size() < 2) {
                // If only 1 wire remains in the bundle, unbundle it and render as normal wire
                for (Wire w : bundle) w.setBusId(null);
            } else {
                paintBlockPairBusBundle(g2, page, bundle, g, liveMode);
            }
        }

        // 2. Paint Standard Unbundled Wires (or KiCad Hidden Hierarchical Pin Flags)
        for (Wire w : page.getWires()) {
            if (!w.isHidden() && w.getBusId() != null) {
                continue; // Already rendered as part of a block-pair Bus bundle
            }

            BlockInstance src = page.findBlockById(w.getSourceBlockId());
            BlockInstance dst = page.findBlockById(w.getTargetBlockId());
            if (src == null || dst == null
                    || w.getSourcePinIndex() >= src.getOutputs().size()
                    || w.getTargetPinIndex() >= dst.getInputs().size()) {
                continue;
            }

            PinInstance srcPin = src.getOutputs().get(w.getSourcePinIndex());
            PinInstance dstPin = dst.getInputs().get(w.getTargetPinIndex());
            Color srcColor = srcPin.getDataType().getWireColor();
            Color dstColor = dstPin.getDataType().getWireColor();

            if (w.isHidden()) {
                paintKiCadHiddenFlags(g2, src, w.getSourcePinIndex(), dst, w.getTargetPinIndex(), w.getNetName(), srcPin.getDataType(), dstPin.getDataType(), g);
                continue;
            }

            float srcWidth = 1.0f;
            float dstWidth = 1.0f;
            if (liveMode) {
                if (srcPin.getDataType() == DataType.BOOL && srcPin.getRuntimeValue() != 0.0) {
                    srcWidth = Math.max(3.0f, g * 0.4f);
                }
                if (dstPin.getDataType() == DataType.BOOL && dstPin.getRuntimeValue() != 0.0) {
                    dstWidth = Math.max(3.0f, g * 0.4f);
                }
            }
            if (w.getId().equals(selectedWireId)) {
                srcWidth = Math.max(srcWidth, 2.0f);
                dstWidth = Math.max(dstWidth, 2.0f);
            }

            List<Point> pts = w.getGridPoints();
            double totalLen = 0.0;
            for (int i = 0; i < pts.size() - 1; i++) {
                Point a = pts.get(i);
                Point b = pts.get(i + 1);
                totalLen += Math.hypot((b.x - a.x) * g, (b.y - a.y) * g);
            }
            double halfLen = totalLen / 2.0;
            double walked = 0.0;

            for (int i = 0; i < pts.size() - 1; i++) {
                int x1 = pts.get(i).x * g;
                int y1 = pts.get(i).y * g;
                int x2 = pts.get(i + 1).x * g;
                int y2 = pts.get(i + 1).y * g;
                double segLen = Math.hypot(x2 - x1, y2 - y1);
                if (segLen < 0.5) continue;

                if (srcPin.getDataType() == dstPin.getDataType() || walked + segLen <= halfLen) {
                    g2.setColor(srcColor);
                    g2.setStroke(new BasicStroke(srcWidth));
                    g2.drawLine(x1, y1, x2, y2);
                } else if (walked >= halfLen) {
                    g2.setColor(dstColor);
                    g2.setStroke(new BasicStroke(dstWidth));
                    g2.drawLine(x1, y1, x2, y2);
                } else {
                    double ratio = (halfLen - walked) / segLen;
                    int mx = (int) Math.round(x1 + (x2 - x1) * ratio);
                    int my = (int) Math.round(y1 + (y2 - y1) * ratio);
                    g2.setColor(srcColor);
                    g2.setStroke(new BasicStroke(srcWidth));
                    g2.drawLine(x1, y1, mx, my);

                    g2.setColor(dstColor);
                    g2.setStroke(new BasicStroke(dstWidth));
                    g2.drawLine(mx, my, x2, y2);
                }
                walked += segLen;
            }
        }

        // 3. Draw Fan-Out Junction Dots where multiple wires from the same output pin branch
        g2.setStroke(new BasicStroke(1.0f));
        for (List<Wire> group : wiresBySourcePin.values()) {
            if (group.size() > 1) {
                Wire first = group.getFirst();
                List<Point> pts = first.getGridPoints();
                if (pts.size() >= 2) {
                    Point branchPt = pts.get(1);
                    BlockInstance src = page.findBlockById(first.getSourceBlockId());
                    if (src != null && first.getSourcePinIndex() < src.getOutputs().size()) {
                        g2.setColor(src.getOutputs().get(first.getSourcePinIndex()).getDataType().getWireColor());
                        int r = Math.max(2, g / 3);
                        g2.fillOval(branchPt.x * g - r, branchPt.y * g - r, r * 2, r * 2);
                    }
                }
            }
        }
    }

    /**
     * Renders a true KiCad-style Bus bundle connecting all wires between a single source block and destination block:
     * 1. Each wire exits its output pin horizontally for 1G, then angles at 45 degrees into the source Bus collector spine.
     * 2. A single thick Navy Bus trunk spans from the source collector spine to the destination distributor spine.
     * 3. Each wire branches out of the destination distributor spine at 45 degrees and enters its input pin horizontally.
     */
    private void paintBlockPairBusBundle(Graphics2D g2, Page page, List<Wire> bundle, int g, boolean liveMode) {
        Wire first = bundle.getFirst();
        BlockInstance src = page.findBlockById(first.getSourceBlockId());
        BlockInstance dst = page.findBlockById(first.getTargetBlockId());
        if (src == null || dst == null) return;

        int srcPinEdgeX = (src.getGridX() + src.getGridWidth()) * g;
        int dstPinEdgeX = dst.getGridX() * g;

        int srcCollectorX = srcPinEdgeX + 2 * g;
        int dstDistributorX = dstPinEdgeX - 2 * g;

        int minSrcJoinY = Integer.MAX_VALUE;
        int maxSrcJoinY = Integer.MIN_VALUE;
        int minDstJoinY = Integer.MAX_VALUE;
        int maxDstJoinY = Integer.MIN_VALUE;

        // 1. Draw each signal's short horizontal pin stub + 45-degree diagonal Bus tap entry/exit
        for (Wire w : bundle) {
            if (w.getSourcePinIndex() >= src.getOutputs().size() || w.getTargetPinIndex() >= dst.getInputs().size()) {
                continue;
            }
            PinInstance srcPin = src.getOutputs().get(w.getSourcePinIndex());
            PinInstance dstPin = dst.getInputs().get(w.getTargetPinIndex());

            int outY = src.getOutputPinAnchorGrid(w.getSourcePinIndex()).y * g;
            int inY = dst.getInputPinAnchorGrid(w.getTargetPinIndex()).y * g;

            // Source tap joins Bus collector 1G below (or above) pin Y at 45 degrees
            int srcJoinY = outY + g;
            int dstJoinY = inY + g;

            minSrcJoinY = Math.min(minSrcJoinY, srcJoinY);
            maxSrcJoinY = Math.max(maxSrcJoinY, srcJoinY);
            minDstJoinY = Math.min(minDstJoinY, dstJoinY);
            maxDstJoinY = Math.max(maxDstJoinY, dstJoinY);

            float srcW = (liveMode && srcPin.getDataType() == DataType.BOOL && srcPin.getRuntimeValue() != 0.0)
                    ? Math.max(2.5f, g * 0.35f) : 1.0f;
            float dstW = (liveMode && dstPin.getDataType() == DataType.BOOL && dstPin.getRuntimeValue() != 0.0)
                    ? Math.max(2.5f, g * 0.35f) : 1.0f;

            // Source stub + 45-deg tap into collector spine
            g2.setColor(srcPin.getDataType().getWireColor());
            g2.setStroke(new BasicStroke(srcW));
            g2.drawLine(srcPinEdgeX, outY, srcPinEdgeX + g, outY);
            g2.drawLine(srcPinEdgeX + g, outY, srcCollectorX, srcJoinY);

            // Destination 45-deg tap from distributor spine + horizontal stub into input pin
            g2.setColor(dstPin.getDataType().getWireColor());
            g2.setStroke(new BasicStroke(dstW));
            g2.drawLine(dstDistributorX, dstJoinY, dstPinEdgeX - g, inY);
            g2.drawLine(dstPinEdgeX - g, inY, dstPinEdgeX, inY);
        }

        if (minSrcJoinY == Integer.MAX_VALUE) return;

        // 2. Draw the thick Navy Blue Bus Spine & Trunk connecting source collector to destination distributor
        int trunkY = ((minSrcJoinY + maxSrcJoinY) / (2 * g)) * g;
        int dstTrunkY = ((minDstJoinY + maxDstJoinY) / (2 * g)) * g;

        int srcSpineTop = Math.min(minSrcJoinY, trunkY);
        int srcSpineBot = Math.max(maxSrcJoinY, trunkY);
        int dstSpineTop = Math.min(minDstJoinY, dstTrunkY);
        int dstSpineBot = Math.max(maxDstJoinY, dstTrunkY);

        g2.setColor(new Color(0x00, 0x33, 0x99));
        g2.setStroke(new BasicStroke(Math.max(3.0f, g * 0.32f), BasicStroke.CAP_SQUARE, BasicStroke.JOIN_MITER));

        // Source vertical collector spine
        g2.drawLine(srcCollectorX, srcSpineTop, srcCollectorX, srcSpineBot);
        // Destination vertical distributor spine
        g2.drawLine(dstDistributorX, dstSpineTop, dstDistributorX, dstSpineBot);

        // Single orthogonal Bus trunk across the middle span
        if (trunkY == dstTrunkY) {
            g2.drawLine(srcCollectorX, trunkY, dstDistributorX, dstTrunkY);
        } else {
            int midX = (((srcCollectorX + dstDistributorX) / 2) / g) * g;
            g2.drawLine(srcCollectorX, trunkY, midX, trunkY);
            g2.drawLine(midX, trunkY, midX, dstTrunkY);
            g2.drawLine(midX, dstTrunkY, dstDistributorX, dstTrunkY);
        }
        g2.setStroke(new BasicStroke(1.0f));
    }

    private void paintKiCadHiddenFlags(
            Graphics2D g2,
            BlockInstance src, int srcPinIdx,
            BlockInstance dst, int dstPinIdx,
            String netName,
            DataType srcType, DataType dstType,
            int g
    ) {
        g2.setFont(new Font("SansSerif", Font.PLAIN, Math.max(7, g - 2)));
        FontMetrics fm = g2.getFontMetrics();
        int textW = fm.stringWidth(netName);
        int flagW = Math.max(3 * g, textW + g + 4);
        int flagH = g;
        int tipW = Math.max(4, g / 2);

        Point pOut = src.getOutputPinAnchorGrid(srcPinIdx);
        int ox = pOut.x * g;
        int oy = pOut.y * g - flagH;
        Polygon outPoly = new Polygon(
                new int[]{ox, ox + flagW - tipW, ox + flagW, ox + flagW - tipW, ox},
                new int[]{oy, oy, oy + flagH / 2, oy + flagH, oy + flagH},
                5
        );
        g2.setColor(srcType.getPinBackground());
        g2.fillPolygon(outPoly);
        g2.setColor(Color.BLACK);
        g2.setStroke(new BasicStroke(1.0f));
        g2.drawPolygon(outPoly);
        g2.drawString(netName, ox + 3, oy + flagH - 2);

        Point pIn = dst.getInputPinAnchorGrid(dstPinIdx);
        int ix = pIn.x * g;
        int iy = pIn.y * g - flagH;
        Polygon inPoly = new Polygon(
                new int[]{ix - flagW, ix - tipW, ix, ix - tipW, ix - flagW},
                new int[]{iy, iy, iy + flagH / 2, iy + flagH, iy + flagH},
                5
        );
        g2.setColor(dstType.getPinBackground());
        g2.fillPolygon(inPoly);
        g2.setColor(Color.BLACK);
        g2.drawPolygon(inPoly);
        g2.drawString(netName, ix - flagW + 3, iy + flagH - 2);
    }

    private void paintBlocks(Graphics2D g2, Page page, int g) {
        Font headerFont = new Font("Segoe UI", Font.BOLD, Math.max(8, g - 1));
        Font pinFont = new Font("Segoe UI", Font.PLAIN, Math.max(7, g - 2));
        Font metaFont = new Font("Segoe UI", Font.BOLD, Math.max(7, g - 2));

        boolean showLiveOutputs = simEngine.isShowingLiveOutputs();
        g2.setStroke(new BasicStroke(1.0f));

        for (BlockInstance b : page.getBlocks()) {
            int bx = b.getGridX() * g;
            int by = b.getGridY() * g;
            int bw = b.getGridWidth() * g;
            int bh = b.getGridHeight() * g;
            int headerH = BlockInstance.HEADER_GRID_HEIGHT * g;
            int pinW = BlockInstance.PIN_COL_GRID_WIDTH * g;
            int bodyY = by + headerH;
            int bodyH = bh - headerH;

            // 1. Block Courtyard & Pin Containers Fill
            g2.setColor(new Color(0xC0, 0xC0, 0xC0));
            g2.fillRect(bx, bodyY, bw, bodyH);

            // 2. Block Header Fill
            g2.setColor(b.getHeaderColor().getColor());
            g2.fillRect(bx, by, bw, headerH);

            // 3. Input Pins inside Left Container (0 padding)
            g2.setFont(pinFont);
            FontMetrics pinFm = g2.getFontMetrics();
            List<PinInstance> ins = b.getInputs();
            for (int i = 0; i < ins.size(); i++) {
                PinInstance pin = ins.get(i);
                int py = bodyY + i * g;
                g2.setColor(pin.getDataType().getPinBackground());
                g2.fillRect(bx + 1, py + 1, pinW - 1, g - 1);
                g2.setColor(new Color(0x88, 0x88, 0x88));
                g2.drawLine(bx, py + g, bx + pinW, py + g);

                g2.setColor(Color.BLACK);
                String sName = pin.getShortName();
                int ty = py + (g + pinFm.getAscent() - pinFm.getDescent()) / 2;
                g2.drawString(sName, bx + 2, ty);

                if (!page.isInputPinConnected(b.getId(), i)) {
                    String constText = "(=" + pin.getConstantValue() + ")";
                    int cw = Math.max(2 * g, pinFm.stringWidth(constText) + 4);
                    int cx = bx - cw;
                    g2.setColor(Color.WHITE);
                    g2.fillRect(cx, py, cw, g);
                    g2.setColor(Color.BLACK);
                    g2.drawRect(cx, py, cw, g);
                    int ctx = cx + (cw - pinFm.stringWidth(constText)) / 2;
                    g2.drawString(constText, ctx, ty);
                }
            }

            // 4. Output Pins inside Right Container (0 padding)
            List<PinInstance> outs = b.getOutputs();
            int rightColX = bx + bw - pinW;
            for (int i = 0; i < outs.size(); i++) {
                PinInstance pin = outs.get(i);
                int py = bodyY + i * g;
                g2.setColor(pin.getDataType().getPinBackground());
                g2.fillRect(rightColX + 1, py + 1, pinW - 1, g - 1);
                g2.setColor(new Color(0x88, 0x88, 0x88));
                g2.drawLine(rightColX, py + g, bx + bw, py + g);

                g2.setColor(Color.BLACK);
                String sName = pin.getShortName();
                int sw = pinFm.stringWidth(sName);
                int ty = py + (g + pinFm.getAscent() - pinFm.getDescent()) / 2;
                g2.drawString(sName, bx + bw - sw - 2, ty);

                if (showLiveOutputs) {
                    String liveStr = "(" + pin.getFormattedRuntimeValue(true) + ")";
                    int vw = Math.max(2 * g, pinFm.stringWidth(liveStr) + 4);
                    int vx = bx + bw;
                    g2.setColor(Color.WHITE);
                    g2.fillRect(vx, py, vw, g);
                    g2.setColor(Color.BLACK);
                    g2.drawRect(vx, py, vw, g);
                    int vtx = vx + (vw - pinFm.stringWidth(liveStr)) / 2;
                    g2.drawString(liveStr, vtx, ty);
                }
            }

            // 5. Block Courtyard Visuals (7-Segment, Value Box, xVar Box, or Horizontally Centered T:/O:)
            int courtyardX = bx + pinW;
            int courtyardW = bw - 2 * pinW;

            if (b.getDefinitionId().equals("Visual.Segment")) {
                paint7SegmentCourtyard(g2, b, courtyardX, bodyY, courtyardW + pinW, bodyH, g);
            } else if (b.getDefinitionId().equals("Visual.Value")) {
                paintValueCourtyard(g2, b, courtyardX, bodyY, courtyardW + pinW, bodyH, g, showLiveOutputs);
            } else if (b.getDefinitionId().startsWith("Visual.Vars.")) {
                paintXVarCourtyard(g2, b, bx, bodyY, bw - pinW, bodyH, g);
            } else if (showExecutionInfo) {
                g2.setFont(metaFont);
                FontMetrics metaFm = g2.getFontMetrics();
                g2.setColor(Color.BLACK);
                String tStr = "T:" + b.getThreadId();
                String oStr = "O:" + b.getOrder();
                int tx = courtyardX + (courtyardW - metaFm.stringWidth(tStr)) / 2;
                int ox = courtyardX + (courtyardW - metaFm.stringWidth(oStr)) / 2;
                int bottomY = by + bh - 2;
                g2.drawString(tStr, tx, bottomY - g);
                g2.drawString(oStr, ox, bottomY);
            }

            // 6. Header Text — Always Crisp WHITE for High Contrast!
            g2.setFont(headerFont);
            FontMetrics hFm = g2.getFontMetrics();
            g2.setColor(Color.WHITE);

            String line1 = b.getTypeName();
            String line2 = b.getName();
            int l1x = bx + (bw - hFm.stringWidth(line1)) / 2;
            int l2x = bx + (bw - hFm.stringWidth(line2)) / 2;
            g2.drawString(line1, l1x, by + g - 1);
            g2.drawString(line2, l2x, by + 2 * g - 2);

            // 7. Single-Pixel Shared Outlines
            g2.setColor(Color.BLACK);
            g2.drawRect(bx, by, bw, bh);
            g2.drawLine(bx, bodyY, bx + bw, bodyY);
            if (!b.getDefinitionId().startsWith("Visual.Vars.")) {
                g2.drawLine(bx + pinW, bodyY, bx + pinW, by + bh);
            }
            if (!b.getDefinitionId().equals("Visual.Segment") && !b.getDefinitionId().equals("Visual.Value")) {
                g2.drawLine(rightColX, bodyY, rightColX, by + bh);
            }

            // Selection Highlight
            if (selectedBlockIds.contains(b.getId())) {
                g2.setColor(new Color(0x00, 0x55, 0xE5));
                Stroke oldStroke = g2.getStroke();
                g2.setStroke(new BasicStroke(1.0f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[]{3f, 2f}, 0f));
                g2.drawRect(bx - 2, by - 2, bw + 4, bh + 4);
                g2.setStroke(oldStroke);
            }
        }
    }

    private void paint7SegmentCourtyard(Graphics2D g2, BlockInstance b, int x, int y, int w, int h, int g) {
        g2.setColor(Color.WHITE);
        g2.fillRect(x, y, w, h);

        int padX = Math.max(4, w / 5);
        int padY = Math.max(4, h / 8);
        int sx = x + padX;
        int sy = y + padY;
        int sw = w - 2 * padX - Math.max(3, g / 2);
        int sh = h - 2 * padY;
        int midY = sy + sh / 2;
        int thick = Math.max(2, g / 3);

        boolean[] segOn = new boolean[8];
        for (int i = 0; i < Math.min(8, b.getInputs().size()); i++) {
            segOn[i] = b.getInputs().get(i).getRuntimeValue() != 0.0;
        }

        Color activeColor = new Color(0x22, 0x22, 0x22);
        Color inactiveColor = new Color(0xE0, 0xE0, 0xE0);

        drawSegmentBar(g2, sx + thick, sy, sw - 2 * thick, thick, segOn[0], activeColor, inactiveColor);
        drawSegmentBar(g2, sx + sw - thick, sy + thick, thick, sh / 2 - thick, segOn[1], activeColor, inactiveColor);
        drawSegmentBar(g2, sx + sw - thick, midY + thick / 2, thick, sh / 2 - thick, segOn[2], activeColor, inactiveColor);
        drawSegmentBar(g2, sx + thick, sy + sh - thick, sw - 2 * thick, thick, segOn[3], activeColor, inactiveColor);
        drawSegmentBar(g2, sx, midY + thick / 2, thick, sh / 2 - thick, segOn[4], activeColor, inactiveColor);
        drawSegmentBar(g2, sx, sy + thick, thick, sh / 2 - thick, segOn[5], activeColor, inactiveColor);
        drawSegmentBar(g2, sx + thick, midY - thick / 2, sw - 2 * thick, thick, segOn[6], activeColor, inactiveColor);

        int dpX = sx + sw + 2;
        int dpY = sy + sh - thick - 1;
        g2.setColor(segOn[7] ? activeColor : inactiveColor);
        g2.fillRect(dpX, dpY, thick + 1, thick + 1);
        g2.setColor(new Color(0x88, 0x88, 0x88));
        g2.drawRect(dpX, dpY, thick + 1, thick + 1);
    }

    private void drawSegmentBar(Graphics2D g2, int x, int y, int w, int h, boolean on, Color onCol, Color offCol) {
        g2.setColor(on ? onCol : offCol);
        g2.fillRect(x, y, w, h);
        g2.setColor(new Color(0x77, 0x77, 0x77));
        g2.drawRect(x, y, w, h);
    }

    private void paintValueCourtyard(Graphics2D g2, BlockInstance b, int x, int y, int w, int h, int g, boolean live) {
        g2.setColor(Color.WHITE);
        g2.fillRect(x, y, w, h);
        if (live && !b.getInputs().isEmpty()) {
            PinInstance in = b.getInputs().getFirst();
            String valStr = in.getFormattedRuntimeValue(true);
            g2.setColor(Color.BLACK);
            g2.setFont(new Font("Monospaced", Font.BOLD, Math.max(9, g)));
            FontMetrics fm = g2.getFontMetrics();
            int tx = x + (w - fm.stringWidth(valStr)) / 2;
            int ty = y + (h + fm.getAscent() - fm.getDescent()) / 2;
            g2.drawString(valStr, tx, ty);
        }
    }

    private void paintXVarCourtyard(Graphics2D g2, BlockInstance b, int x, int y, int w, int h, int g) {
        g2.setColor(new Color(0xF8, 0xFA, 0xFC));
        g2.fillRect(x, y, w, h);
        String mode = b.getCustomProperties().getOrDefault("InputType", "Switch");
        String curVal = b.getCustomProperties().getOrDefault("CurrentValue", "0");
        g2.setColor(new Color(0x11, 0x11, 0x11));
        g2.setFont(new Font("Monospaced", Font.BOLD, Math.max(8, g - 1)));
        FontMetrics fm = g2.getFontMetrics();
        String str = mode.substring(0, Math.min(3, mode.length())) + ":" + curVal;
        int tx = x + (w - fm.stringWidth(str)) / 2;
        int ty = y + (h + fm.getAscent() - fm.getDescent()) / 2;
        g2.drawString(str, tx, ty);
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
