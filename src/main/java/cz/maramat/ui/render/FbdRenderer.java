package cz.maramat.ui.render;

import cz.maramat.model.BlockInstance;
import cz.maramat.model.Bus;
import cz.maramat.model.DataType;
import cz.maramat.model.GraphicElement;
import cz.maramat.model.Page;
import cz.maramat.model.PinInstance;
import cz.maramat.model.Wire;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.geom.Arc2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Stateless renderer for FBD {@link Page} instances.
 * Shared between {@link cz.maramat.ui.canvas.FbdCanvas}, SVG/PDF/PNG export, and the built-in Help diagram engine.
 */
public final class FbdRenderer {

    private static final BufferedImage METRICS_IMG = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);

    private FbdRenderer() {}

    public record RenderOptions(
            boolean showLiveValues,
            boolean showExecutionInfo,
            boolean exporting,
            boolean antialiasText,
            Set<String> selectedBlockIds,
            String selectedWireId,
            String selectedBusId,
            Set<String> selectedGraphicIds,
            boolean visualButtonsLockedInLiveMode
    ) {
        public static RenderOptions forExport() {
            return new RenderOptions(false, true, true, true,
                    Collections.emptySet(), null, null, Collections.emptySet(), false);
        }

        public static RenderOptions forDiagram(boolean showLiveValues, boolean showExecutionInfo) {
            return new RenderOptions(showLiveValues, showExecutionInfo, true, true,
                    Collections.emptySet(), null, null, Collections.emptySet(), false);
        }
    }

    public static Rectangle computeExportBounds(Page page, int g) {
        if (page == null) return new Rectangle(0, 0, 200, 120);
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        boolean hasAny = false;

        for (BlockInstance b : page.getBlocks()) {
            hasAny = true;
            int bx = b.getGridX() * g;
            int by = b.getGridY() * g;
            int bw = b.getGridWidth() * g;
            int bh = b.getGridHeight() * g;
            minX = Math.min(minX, bx - 3 * g);
            minY = Math.min(minY, by - g);
            maxX = Math.max(maxX, bx + bw + 3 * g);
            maxY = Math.max(maxY, by + bh + g);
        }
        for (Wire w : page.getWires()) {
            for (Point2D.Double pt : w.getGridPoints()) {
                hasAny = true;
                int px = (int) Math.round(pt.x * g);
                int py = (int) Math.round(pt.y * g);
                minX = Math.min(minX, px - g);
                minY = Math.min(minY, py - g);
                maxX = Math.max(maxX, px + g);
                maxY = Math.max(maxY, py + g);
            }
        }
        for (GraphicElement ge : page.getGraphics()) {
            hasAny = true;
            Rectangle r = getGraphicPixelBounds(ge, g);
            minX = Math.min(minX, r.x - g);
            minY = Math.min(minY, r.y - g);
            maxX = Math.max(maxX, r.x + r.width + g);
            maxY = Math.max(maxY, r.y + r.height + g);
        }
        if (!hasAny) {
            return new Rectangle(0, 0, 240, 140);
        }
        return new Rectangle(minX, minY, Math.max(60, maxX - minX), Math.max(60, maxY - minY));
    }

    public static void paintPage(Graphics2D g2, Page page, int g, RenderOptions opts) {
        if (page == null) return;
        syncVisualValuePinDataTypes(page);
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        g2.setRenderingHint(
                RenderingHints.KEY_TEXT_ANTIALIASING,
                opts.antialiasText()
                        ? RenderingHints.VALUE_TEXT_ANTIALIAS_ON
                        : RenderingHints.VALUE_TEXT_ANTIALIAS_OFF
        );
        paintGraphicElements(g2, page, g, opts);
        paintBussesAndWires(g2, page, g, opts);
        paintBlocks(g2, page, g, opts);
    }

    public static void syncVisualValuePinDataTypes(Page page) {
        if (page == null) return;
        for (BlockInstance b : page.getBlocks()) {
            if ("Visual.Value".equals(b.getDefinitionId()) && !b.getInputs().isEmpty()) {
                Wire incoming = page.getWires().stream()
                        .filter(w -> w.getTargetBlockId().equals(b.getId()) && w.getTargetPinIndex() == 0)
                        .findFirst()
                        .orElse(null);
                if (incoming != null) {
                    BlockInstance srcB = page.getBlocks().stream()
                            .filter(sb -> sb.getId().equals(incoming.getSourceBlockId()))
                            .findFirst()
                            .orElse(null);
                    if (srcB != null && incoming.getSourcePinIndex() < srcB.getOutputs().size()) {
                        b.getInputs().get(0).setDataType(srcB.getOutputs().get(incoming.getSourcePinIndex()).getDataType());
                    }
                }
            }
        }
    }

    private static final Graphics2D METRICS_G2 = METRICS_IMG.createGraphics();

    public static Rectangle getGraphicPixelBounds(GraphicElement elem, int g) {
        if (elem.getKind() == GraphicElement.Kind.TEXT) {
            Font f = cz.maramat.ui.AppFonts.ui(Font.PLAIN, Math.max(8, (elem.getFontSize() * g) / 10));
            FontMetrics fm = METRICS_G2.getFontMetrics(f);
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

    public static void paintGraphicElements(Graphics2D g2, Page page, int g, RenderOptions opts) {
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
                boolean isIndicator = elem.isIndicatorEnabled() && elem.getIndicatorPin() != null && !elem.getIndicatorPin().isBlank();
                boolean active = isIndicator && isOutputPinActive(page, elem.getIndicatorPin());
                Color effectiveFill = isIndicator
                        ? (active ? elem.getIndicatorActiveColor() : elem.getIndicatorInactiveColor())
                        : elem.getFillColor();
                boolean shouldFill = elem.isFilled() || isIndicator;

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
                        if (shouldFill) {
                            g2.setColor(effectiveFill);
                            g2.fillRect(rx, ry, rw, rh);
                        }
                        g2.setColor(elem.getStrokeColor());
                        g2.drawRect(rx, ry, rw, rh);
                    }
                    case ROUNDED_RECTANGLE -> {
                        int arc = Math.max(4, g);
                        if (shouldFill) {
                            g2.setColor(effectiveFill);
                            g2.fillRoundRect(rx, ry, rw, rh, arc, arc);
                        }
                        g2.setColor(elem.getStrokeColor());
                        g2.drawRoundRect(rx, ry, rw, rh, arc, arc);
                    }
                    case TRIANGLE -> drawNgon(g2, elem, rx, ry, rw, rh, 3, shouldFill, effectiveFill);
                    case PENTAGON -> drawNgon(g2, elem, rx, ry, rw, rh, 5, shouldFill, effectiveFill);
                    case HEXAGON -> drawNgon(g2, elem, rx, ry, rw, rh, 6, shouldFill, effectiveFill);
                    case OCTAGON -> drawNgon(g2, elem, rx, ry, rw, rh, 8, shouldFill, effectiveFill);
                    case CUSTOM_NGON -> drawNgon(g2, elem, rx, ry, rw, rh, elem.getNgonSides(), shouldFill, effectiveFill);
                    case CIRCLE -> {
                        int d = Math.min(rw, rh);
                        if (shouldFill) {
                            g2.setColor(effectiveFill);
                            g2.fillOval(rx, ry, d, d);
                        }
                        g2.setColor(elem.getStrokeColor());
                        g2.drawOval(rx, ry, d, d);
                    }
                    case ELLIPSE -> {
                        if (shouldFill) {
                            g2.setColor(effectiveFill);
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
                        if (shouldFill) {
                            g2.setColor(effectiveFill);
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

            boolean locked = elem.getKind() == GraphicElement.Kind.VISUAL_BUTTON && opts.visualButtonsLockedInLiveMode();
            if (!opts.exporting() && opts.selectedGraphicIds().contains(elem.getId()) && !locked) {
                g2.setColor(new Color(0x00, 0x66, 0xCC));
                g2.setStroke(new BasicStroke(1.0f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[]{2f, 2f}, 0f));
                g2.drawRect(rx - 1, ry - 1, rw + 2, rh + 2);

                g2.setStroke(new BasicStroke(1.0f));
                for (Point p : computeGraphicHandlePoints(elem, bounds, g)) {
                    g2.setColor(Color.WHITE);
                    g2.fillRect(p.x - 3, p.y - 3, 6, 6);
                    g2.setColor(new Color(0x00, 0x66, 0xCC));
                    g2.drawRect(p.x - 3, p.y - 3, 6, 6);
                }
            }
        }
        g2.setStroke(new BasicStroke(1.0f));
    }

    private static List<Point> computeGraphicHandlePoints(GraphicElement elem, Rectangle r, int g) {
        List<Point> pts = new ArrayList<>();
        if (elem.getKind() == GraphicElement.Kind.LINE) {
            pts.add(new Point(elem.getGridX() * g, elem.getGridY() * g));
            pts.add(new Point((elem.getGridX() + elem.getGridW()) * g, (elem.getGridY() + elem.getGridH()) * g));
            return pts;
        }
        if (elem.getKind() == GraphicElement.Kind.TEXT) {
            return pts;
        }
        int midX = r.x + r.width / 2;
        int midY = r.y + r.height / 2;
        pts.add(new Point(r.x, r.y));
        pts.add(new Point(midX, r.y));
        pts.add(new Point(r.x + r.width, r.y));
        pts.add(new Point(r.x + r.width, midY));
        pts.add(new Point(r.x + r.width, r.y + r.height));
        pts.add(new Point(midX, r.y + r.height));
        pts.add(new Point(r.x, r.y + r.height));
        pts.add(new Point(r.x, midY));
        return pts;
    }

    private static void drawNgon(Graphics2D g2, GraphicElement elem, int rx, int ry, int rw, int rh, int sides, boolean shouldFill, Color fillCol) {
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
        if (shouldFill) {
            g2.setColor(fillCol);
            g2.fillPolygon(xs, ys, sides);
        }
        g2.setColor(elem.getStrokeColor());
        g2.drawPolygon(xs, ys, sides);
    }

    private static boolean isOutputPinActive(Page page, String pinRef) {
        if (page == null || pinRef == null || pinRef.isBlank()) return false;
        String[] parts = pinRef.split("\\.", 2);
        if (parts.length < 2) return false;
        String bName = parts[0].trim();
        String pName = parts[1].trim();
        for (BlockInstance b : page.getBlocks()) {
            if (b.getName().equalsIgnoreCase(bName) || b.getId().equalsIgnoreCase(bName) || b.getTypeName().equalsIgnoreCase(bName)) {
                for (PinInstance p : b.getOutputs()) {
                    if (p.getShortName().equalsIgnoreCase(pName) || p.getFullName().equalsIgnoreCase(pName)) {
                        return Math.abs(p.getRuntimeValue()) >= 0.5;
                    }
                }
            }
        }
        return false;
    }

    public static void paintBussesAndWires(Graphics2D g2, Page page, int g, RenderOptions opts) {
        boolean liveMode = opts.showLiveValues();

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

        Set<String> singleWireBusIds = java.util.Collections.emptySet();
        for (Map.Entry<String, List<Wire>> entry : busBundles.entrySet()) {
            if (entry.getValue().size() < 2) {
                if (singleWireBusIds.isEmpty()) singleWireBusIds = new HashSet<>();
                singleWireBusIds.add(entry.getKey());
            } else {
                paintBlockPairBusBundle(g2, page, entry.getValue(), g, liveMode, opts);
            }
        }

        Map<String, BlockInstance> blockById = new HashMap<>(Math.max(16, page.getBlocks().size() * 2));
        for (BlockInstance b : page.getBlocks()) {
            blockById.put(b.getId(), b);
        }

        for (Wire w : page.getWires()) {
            if (!w.isHidden() && w.getBusId() != null && !singleWireBusIds.contains(w.getBusId())) {
                continue;
            }

            BlockInstance src = blockById.get(w.getSourceBlockId());
            BlockInstance dst = blockById.get(w.getTargetBlockId());
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
                paintKiCadHiddenFlags(g2, src, w.getSourcePinIndex(), dst, w.getTargetPinIndex(), w.getNetName(), srcPin, dstPin, liveMode, g);
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
            if (!opts.exporting() && w.getId().equals(opts.selectedWireId())) {
                srcWidth = Math.max(srcWidth, 2.0f);
                dstWidth = Math.max(dstWidth, 2.0f);
            }

            List<Point2D.Double> pts = w.getGridPoints();
            double totalLen = 0.0;
            for (int i = 0; i < pts.size() - 1; i++) {
                Point2D.Double a = pts.get(i);
                Point2D.Double b = pts.get(i + 1);
                totalLen += Math.hypot((b.x - a.x) * g, (b.y - a.y) * g);
            }
            double halfLen = totalLen / 2.0;
            double walked = 0.0;

            for (int i = 0; i < pts.size() - 1; i++) {
                double x1 = pts.get(i).x * g;
                double y1 = pts.get(i).y * g;
                double x2 = pts.get(i + 1).x * g;
                double y2 = pts.get(i + 1).y * g;
                double segLen = Math.hypot(x2 - x1, y2 - y1);
                if (segLen < 0.5) continue;

                if (srcPin.getDataType() == dstPin.getDataType() || walked + segLen <= halfLen) {
                    g2.setColor(srcColor);
                    g2.setStroke(new BasicStroke(srcWidth));
                    g2.draw(new Line2D.Double(x1, y1, x2, y2));
                } else if (walked >= halfLen) {
                    g2.setColor(dstColor);
                    g2.setStroke(new BasicStroke(dstWidth));
                    g2.draw(new Line2D.Double(x1, y1, x2, y2));
                } else {
                    double ratio = (halfLen - walked) / segLen;
                    double mx = x1 + (x2 - x1) * ratio;
                    double my = y1 + (y2 - y1) * ratio;
                    g2.setColor(srcColor);
                    g2.setStroke(new BasicStroke(srcWidth));
                    g2.draw(new Line2D.Double(x1, y1, mx, my));

                    g2.setColor(dstColor);
                    g2.setStroke(new BasicStroke(dstWidth));
                    g2.draw(new Line2D.Double(mx, my, x2, y2));
                }
                walked += segLen;
            }
        }

        g2.setStroke(new BasicStroke(1.0f));
        for (List<Wire> group : wiresBySourcePin.values()) {
            List<Wire> visibleGroup = group.stream()
                    .filter(w -> !w.isHidden() && w.getBusId() == null && w.getGridPoints().size() >= 2)
                    .toList();
            if (visibleGroup.size() <= 1) continue;

            Wire first = visibleGroup.getFirst();
            BlockInstance src = page.findBlockById(first.getSourceBlockId());
            if (src == null || first.getSourcePinIndex() >= src.getOutputs().size()) continue;

            List<Point2D.Double> junctions = findBranchJunctionPoints(visibleGroup);
            if (junctions.isEmpty()) continue;

            g2.setColor(src.getOutputs().get(first.getSourcePinIndex()).getDataType().getWireColor());
            int r = Math.max(2, g / 4);
            for (Point2D.Double branchPt : junctions) {
                int bx = (int) Math.round(branchPt.x * g);
                int by = (int) Math.round(branchPt.y * g);
                g2.fillOval(bx - r, by - r, r * 2, r * 2);
            }
        }
    }

    private static List<Point2D.Double> findBranchJunctionPoints(List<Wire> wires) {
        final double eps = 1e-4;
        record GridPt(long qx, long qy) {
            static GridPt of(double x, double y) {
                return new GridPt(Math.round(x * 1000.0), Math.round(y * 1000.0));
            }
            double x() { return qx / 1000.0; }
            double y() { return qy / 1000.0; }
        }
        record GridSeg(GridPt a, GridPt b) {
            static GridSeg normalized(GridPt p1, GridPt p2) {
                if (p1.qx < p2.qx || (p1.qx == p2.qx && p1.qy <= p2.qy)) {
                    return new GridSeg(p1, p2);
                }
                return new GridSeg(p2, p1);
            }
        }

        Set<GridPt> vertices = new HashSet<>();
        List<GridSeg> rawSegments = new ArrayList<>();
        for (Wire w : wires) {
            List<Point2D.Double> pts = w.getGridPoints();
            for (int i = 0; i < pts.size(); i++) {
                vertices.add(GridPt.of(pts.get(i).x, pts.get(i).y));
                if (i + 1 < pts.size()) {
                    GridPt p1 = GridPt.of(pts.get(i).x, pts.get(i).y);
                    GridPt p2 = GridPt.of(pts.get(i + 1).x, pts.get(i + 1).y);
                    if (!p1.equals(p2)) {
                        rawSegments.add(new GridSeg(p1, p2));
                    }
                }
            }
        }

        Set<GridSeg> atomicSegments = new HashSet<>();
        for (GridSeg seg : rawSegments) {
            double x1 = seg.a.x(), y1 = seg.a.y();
            double x2 = seg.b.x(), y2 = seg.b.y();
            boolean horiz = Math.abs(y1 - y2) < eps;
            boolean vert = Math.abs(x1 - x2) < eps;

            List<GridPt> splits = new ArrayList<>();
            splits.add(seg.a);
            splits.add(seg.b);
            if (horiz) {
                double minX = Math.min(x1, x2), maxX = Math.max(x1, x2);
                for (GridPt v : vertices) {
                    if (Math.abs(v.y() - y1) < eps && v.x() > minX + eps && v.x() < maxX - eps) {
                        splits.add(v);
                    }
                }
                splits.sort(Comparator.comparingLong(GridPt::qx));
            } else if (vert) {
                double minY = Math.min(y1, y2), maxY = Math.max(y1, y2);
                for (GridPt v : vertices) {
                    if (Math.abs(v.x() - x1) < eps && v.y() > minY + eps && v.y() < maxY - eps) {
                        splits.add(v);
                    }
                }
                splits.sort(Comparator.comparingLong(GridPt::qy));
            }

            for (int i = 0; i + 1 < splits.size(); i++) {
                GridPt u = splits.get(i);
                GridPt v = splits.get(i + 1);
                if (!u.equals(v)) {
                    atomicSegments.add(GridSeg.normalized(u, v));
                }
            }
        }

        Map<GridPt, Integer> degree = new HashMap<>();
        for (GridSeg seg : atomicSegments) {
            degree.merge(seg.a, 1, Integer::sum);
            degree.merge(seg.b, 1, Integer::sum);
        }

        List<Point2D.Double> junctions = new ArrayList<>();
        for (Map.Entry<GridPt, Integer> e : degree.entrySet()) {
            if (e.getValue() >= 3) {
                junctions.add(new Point2D.Double(e.getKey().x(), e.getKey().y()));
            }
        }
        return junctions;
    }

    private static void paintBlockPairBusBundle(Graphics2D g2, Page page, List<Wire> bundle, int g, boolean liveMode, RenderOptions opts) {
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

        for (Wire w : bundle) {
            if (w.getSourcePinIndex() >= src.getOutputs().size() || w.getTargetPinIndex() >= dst.getInputs().size()) {
                continue;
            }
            PinInstance srcPin = src.getOutputs().get(w.getSourcePinIndex());
            PinInstance dstPin = dst.getInputs().get(w.getTargetPinIndex());

            int outY = (int) Math.round(src.getOutputPinAnchorGrid(w.getSourcePinIndex()).y * g);
            int inY = (int) Math.round(dst.getInputPinAnchorGrid(w.getTargetPinIndex()).y * g);

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

            g2.setColor(srcPin.getDataType().getWireColor());
            g2.setStroke(new BasicStroke(srcW));
            g2.drawLine(srcPinEdgeX, outY, srcPinEdgeX + g, outY);
            g2.drawLine(srcPinEdgeX + g, outY, srcCollectorX, srcJoinY);

            g2.setColor(dstPin.getDataType().getWireColor());
            g2.setStroke(new BasicStroke(dstW));
            g2.drawLine(dstDistributorX, dstJoinY, dstPinEdgeX - g, inY);
            g2.drawLine(dstPinEdgeX - g, inY, dstPinEdgeX, inY);
        }

        if (minSrcJoinY == Integer.MAX_VALUE) return;

        Point2D.Double[] trunkEnds = page.computeBusTrunkEndpoints(bundle);
        if (trunkEnds == null) return;

        int trunkY = (int) Math.round(trunkEnds[0].y * g);
        int dstTrunkY = (int) Math.round(trunkEnds[1].y * g);

        int srcSpineTop = Math.min(minSrcJoinY, trunkY);
        int srcSpineBot = Math.max(maxSrcJoinY, trunkY);
        int dstSpineTop = Math.min(minDstJoinY, dstTrunkY);
        int dstSpineBot = Math.max(maxDstJoinY, dstTrunkY);

        Bus bus = page.getOrCreateBusForBundle(first.getBusId(), trunkEnds[0], trunkEnds[1]);
        boolean busSelected = !opts.exporting() && bus.getName().equals(opts.selectedBusId());

        g2.setColor(busSelected ? new Color(0x00, 0x55, 0xE5) : new Color(0x00, 0x33, 0x99));
        g2.setStroke(new BasicStroke(Math.max(3.0f, g * 0.32f), BasicStroke.CAP_SQUARE, BasicStroke.JOIN_MITER));

        g2.drawLine(srcCollectorX, srcSpineTop, srcCollectorX, srcSpineBot);
        g2.drawLine(dstDistributorX, dstSpineTop, dstDistributorX, dstSpineBot);

        List<Point2D.Double> busPts = bus.getGridPoints();
        for (int i = 0; i < busPts.size() - 1; i++) {
            Point2D.Double p1 = busPts.get(i);
            Point2D.Double p2 = busPts.get(i + 1);
            g2.draw(new Line2D.Double(p1.x * g, p1.y * g, p2.x * g, p2.y * g));
        }
        g2.setStroke(new BasicStroke(1.0f));
    }

    private static void paintKiCadHiddenFlags(
            Graphics2D g2,
            BlockInstance src, int srcPinIdx,
            BlockInstance dst, int dstPinIdx,
            String netName,
            PinInstance srcPin, PinInstance dstPin,
            boolean liveMode,
            int g
    ) {
        DataType srcType = srcPin.getDataType();
        DataType dstType = dstPin.getDataType();
        Font flagFont = new Font("Segoe UI", Font.PLAIN, Math.max(7, g - 2));
        g2.setFont(flagFont);
        FontMetrics fm = g2.getFontMetrics();
        int textW = fm.stringWidth(netName);
        int flagGridW = Math.max(3, (int) Math.ceil((textW + g + 2.0) / g));
        int flagW = flagGridW * g;
        int flagH = g;
        double r = flagH / 2.0;

        Point2D.Double pOut = src.getOutputPinAnchorGrid(srcPinIdx);
        int baseOx = (int) Math.round(pOut.x * g);
        int oy = (int) Math.round(pOut.y * g - flagH / 2.0);
        int ox = baseOx;
        if (liveMode && !src.isIoRef()) {
            String liveStr = "(" + srcPin.getFormattedRuntimeValue(true) + ")";
            int vw = Math.max(2 * g, fm.stringWidth(liveStr) + 4);
            int liveGridW = (int) Math.ceil(vw / (double) g);
            ox = baseOx + liveGridW * g;
            if (ox > baseOx + vw) {
                g2.setColor(srcType.getWireColor());
                g2.setStroke(new BasicStroke(1.0f));
                g2.drawLine(baseOx + vw, oy + flagH / 2, ox, oy + flagH / 2);
            }
        }

        Path2D.Double ocon = new Path2D.Double();
        ocon.moveTo(ox, oy);
        ocon.lineTo(ox + flagW - r, oy);
        ocon.append(new Arc2D.Double(ox + flagW - flagH, oy, flagH, flagH, 90, -180, Arc2D.OPEN), true);
        ocon.lineTo(ox, oy + flagH);
        ocon.closePath();

        boolean srcActive = liveMode && srcType == DataType.BOOL && srcPin.getRuntimeValue() != 0.0;
        g2.setColor(srcActive ? srcType.getWireColor() : srcType.getPinBackground());
        g2.fill(ocon);
        g2.setColor(Color.BLACK);
        g2.setStroke(new BasicStroke(1.0f));
        g2.draw(ocon);
        g2.setColor(srcActive ? Color.WHITE : Color.BLACK);
        int textY = oy + (flagH + fm.getAscent() - fm.getDescent()) / 2;
        g2.drawString(netName, ox + Math.max(3, g / 3), textY);

        Point2D.Double pIn = dst.getInputPinAnchorGrid(dstPinIdx);
        int ix = (int) Math.round(pIn.x * g);
        int iy = (int) Math.round(pIn.y * g - flagH / 2.0);
        Path2D.Double icon = new Path2D.Double();
        icon.moveTo(ix, iy + flagH);
        icon.lineTo(ix - flagW + r, iy + flagH);
        icon.append(new Arc2D.Double(ix - flagW, iy, flagH, flagH, 270, -180, Arc2D.OPEN), true);
        icon.lineTo(ix, iy);
        icon.closePath();

        boolean dstActive = liveMode && dstType == DataType.BOOL && dstPin.getRuntimeValue() != 0.0;
        g2.setColor(dstActive ? dstType.getWireColor() : dstType.getPinBackground());
        g2.fill(icon);
        g2.setColor(Color.BLACK);
        g2.draw(icon);
        g2.setColor(dstActive ? Color.WHITE : Color.BLACK);
        int inTextY = iy + (flagH + fm.getAscent() - fm.getDescent()) / 2;
        g2.drawString(netName, ix - flagW + (int) Math.round(r) + 2, inTextY);
    }

    public static void paintBlocks(Graphics2D g2, Page page, int g, RenderOptions opts) {
        Font headerFont = new Font("Segoe UI", Font.BOLD, Math.max(8, g - 1));
        Font pinFont = new Font("Segoe UI", Font.PLAIN, Math.max(7, g - 2));
        Font metaFont = new Font("Segoe UI", Font.BOLD, Math.max(7, g - 2));

        boolean showLiveOutputs = opts.showLiveValues();
        boolean showExecutionInfo = opts.showExecutionInfo();
        g2.setStroke(new BasicStroke(1.0f));

        for (BlockInstance b : page.getBlocks()) {
            if (b.isIoRef()) {
                paintIoRefBlock(g2, b, g, pinFont, showLiveOutputs, opts);
                continue;
            }
            int bx = b.getGridX() * g;
            int by = b.getGridY() * g;
            int bw = b.getGridWidth() * g;
            int bh = b.getGridHeight() * g;
            int headerH = BlockInstance.HEADER_GRID_HEIGHT * g;
            boolean isXVar = b.getDefinitionId().startsWith("Visual.Vars.");
            int pinW = isXVar ? g : BlockInstance.PIN_COL_GRID_WIDTH * g;
            int bodyY = by + headerH;
            int bodyH = bh - headerH;

            g2.setColor(new Color(0xC0, 0xC0, 0xC0));
            g2.fillRect(bx, bodyY, bw, bodyH);

            g2.setColor(b.getHeaderColor().getColor());
            g2.fillRect(bx, by, bw, headerH);

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
                drawPinLabelWithOverline(g2, pinFm, sName, bx + 2, py, g, pin.isNegated());

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
                drawPinLabelWithOverline(g2, pinFm, sName, bx + bw - sw - 2, py, g, pin.isNegated());

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

            int courtyardX = bx + pinW;
            int courtyardW = bw - 2 * pinW;

            if (b.getDefinitionId().equals("Visual.Segment")) {
                paint7SegmentCourtyard(g2, b, courtyardX, bodyY, courtyardW + pinW, bodyH, g);
            } else if (b.getDefinitionId().equals("Visual.Value")) {
                paintValueCourtyard(g2, b, courtyardX, bodyY, courtyardW + pinW, bodyH, g, showLiveOutputs);
            } else if (b.getDefinitionId().startsWith("Visual.Vars.")) {
                paintXVarCourtyard(g2, b, bx, bodyY, bw - pinW, bodyH, g);
                if (showExecutionInfo) {
                    g2.setFont(metaFont);
                    FontMetrics metaFm = g2.getFontMetrics();
                    g2.setColor(Color.BLACK);
                    String tStr = "T:" + b.getThreadId();
                    String oStr = "O:" + b.getOrder();
                    int tx = bx + ((bw - pinW) - metaFm.stringWidth(tStr)) / 2;
                    int ox = bx + ((bw - pinW) - metaFm.stringWidth(oStr)) / 2;
                    int bottomY = by + bh - 2;
                    g2.drawString(tStr, tx, bottomY - g);
                    g2.drawString(oStr, ox, bottomY);
                }
            } else {
                String badge = null;
                if (b.getDefinitionId().startsWith("Main.Execution.")) {
                    badge = b.getCustomProperties().get("RegisterName");
                } else if (b.getDefinitionId().startsWith("IO.") && !b.getDefinitionId().startsWith("IO.8x")) {
                    badge = b.getCustomProperties().get("Channel");
                }
                if (badge != null && !badge.isBlank()) {
                    g2.setFont(metaFont);
                    FontMetrics metaFm = g2.getFontMetrics();
                    g2.setColor(new Color(0x00, 0x33, 0x66));
                    int bxText = courtyardX + (courtyardW - metaFm.stringWidth(badge)) / 2;
                    g2.drawString(badge, bxText, bodyY + g - 1);
                }
                if (showExecutionInfo) {
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
            }

            g2.setFont(headerFont);
            FontMetrics hFm = g2.getFontMetrics();
            g2.setColor(Color.WHITE);

            String line1 = b.getTypeName();
            String line2 = b.getName();
            int l1x = bx + (bw - hFm.stringWidth(line1)) / 2;
            int l2x = bx + (bw - hFm.stringWidth(line2)) / 2;
            g2.drawString(line1, l1x, by + g - 1);
            g2.drawString(line2, l2x, by + 2 * g - 2);

            g2.setColor(Color.BLACK);
            g2.drawRect(bx, by, bw, bh);
            g2.drawLine(bx, bodyY, bx + bw, bodyY);
            if (!b.getDefinitionId().startsWith("Visual.Vars.")) {
                g2.drawLine(bx + pinW, bodyY, bx + pinW, by + bh);
            }
            if (!b.getDefinitionId().equals("Visual.Segment") && !b.getDefinitionId().equals("Visual.Value")) {
                g2.drawLine(rightColX, bodyY, rightColX, by + bh);
            }

            if (!opts.exporting() && opts.selectedBlockIds().contains(b.getId())) {
                g2.setColor(new Color(0x00, 0x55, 0xE5));
                Stroke oldStroke = g2.getStroke();
                g2.setStroke(new BasicStroke(1.0f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[]{3f, 2f}, 0f));
                g2.drawRect(bx - 2, by - 2, bw + 4, bh + 4);
                g2.setStroke(oldStroke);
            }
        }
    }

    private static void paintIoRefBlock(Graphics2D g2, BlockInstance b, int g, Font pinFont, boolean showLiveOutputs, RenderOptions opts) {
        int bx = b.getGridX() * g;
        int by = b.getGridY() * g;
        int bw = b.getGridWidth() * g;
        int bh = g;
        int midY = by + g / 2;

        boolean isInput = b.isInputRef();
        PinInstance pin = isInput
                ? (b.getOutputs().isEmpty() ? null : b.getOutputs().getFirst())
                : (b.getInputs().isEmpty() ? null : b.getInputs().getFirst());
        DataType dt = pin != null ? pin.getDataType() : DataType.BOOL;
        Color pastel = dt.getPinBackground();
        boolean activeBool = showLiveOutputs && pin != null && dt == DataType.BOOL && pin.getRuntimeValue() != 0.0;

        Polygon outer = new Polygon();
        Polygon tip = new Polygon();
        if (isInput) {
            int splitX = bx + bw - g;
            outer.addPoint(bx, by);
            outer.addPoint(splitX, by);
            outer.addPoint(bx + bw, midY);
            outer.addPoint(splitX, by + bh);
            outer.addPoint(bx, by + bh);

            tip.addPoint(splitX, by);
            tip.addPoint(bx + bw, midY);
            tip.addPoint(splitX, by + bh);
        } else {
            int splitX = bx + g;
            outer.addPoint(bx, midY);
            outer.addPoint(splitX, by);
            outer.addPoint(bx + bw, by);
            outer.addPoint(bx + bw, by + bh);
            outer.addPoint(splitX, by + bh);

            tip.addPoint(bx, midY);
            tip.addPoint(splitX, by);
            tip.addPoint(splitX, by + bh);
        }

        if (activeBool) {
            g2.setColor(dt.getWireColor());
            g2.fillPolygon(outer);
        } else {
            g2.setColor(Color.WHITE);
            g2.fillPolygon(outer);
            g2.setColor(pastel);
            g2.fillPolygon(tip);
        }

        g2.setColor(Color.BLACK);
        g2.drawPolygon(outer);

        g2.setFont(pinFont);
        FontMetrics fm = g2.getFontMetrics();
        String label = b.getIoRefLabel();
        int textX = isInput ? (bx + Math.max(3, g / 3)) : (bx + g + Math.max(2, g / 4));
        g2.setColor(activeBool ? Color.WHITE : Color.BLACK);
        drawPinLabelWithOverline(g2, fm, label, textX, by, bh, pin != null && pin.isNegated());

        if (!opts.exporting() && opts.selectedBlockIds().contains(b.getId())) {
            g2.setColor(new Color(0x00, 0x55, 0xE5));
            Stroke oldStroke = g2.getStroke();
            g2.setStroke(new BasicStroke(1.0f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[]{3f, 2f}, 0f));
            g2.drawRect(bx - 2, by - 2, bw + 4, bh + 4);
            g2.setStroke(oldStroke);
        }
    }

    private static void drawPinLabelWithOverline(Graphics2D g2, FontMetrics fm, String text, int tx, int py, int g, boolean negated) {
        int baseTy = py + (g + fm.getAscent() - fm.getDescent()) / 2;
        if (!negated) {
            g2.drawString(text, tx, baseTy);
            return;
        }
        int ty = Math.min(py + g - 1, baseTy + Math.max(1, g / 12));
        g2.drawString(text, tx, ty);
        int capHeight = Math.max(4, (int) Math.round(fm.getAscent() * 0.72));
        int gap = Math.max(1, g / 12);
        int barY = Math.max(py + 1, Math.min(ty - capHeight - gap, py + Math.max(1, g / 5)));
        int sw = Math.max(2, fm.stringWidth(text));
        g2.drawLine(tx, barY, tx + sw - 1, barY);
    }

    private static void paint7SegmentCourtyard(Graphics2D g2, BlockInstance b, int x, int y, int w, int h, int g) {
        g2.setColor(Color.WHITE);
        g2.fillRect(x, y, w, h);

        int padX = Math.max(4, w / 6);
        int padY = Math.max(4, h / 10);
        int sx = x + padX;
        int sy = y + padY;
        int sw = w - 2 * padX - Math.max(6, g);
        int sh = h - 2 * padY;
        int thick = Math.max(3, (int) Math.round(sw * 0.22));

        boolean[] segOn = new boolean[8];
        for (int i = 0; i < Math.min(8, b.getInputs().size()); i++) {
            segOn[i] = b.getInputs().get(i).getRuntimeValue() != 0.0;
        }

        Color activeColor = new Color(0x22, 0x22, 0x22);
        Color inactiveColor = new Color(0xC0, 0xC0, 0xC0);

        int horizW = sw - thick;
        int vertH = (sh - thick) / 2;
        int gap = 1;

        drawHexSegment(g2, sx + thick / 2, sy, horizW, thick, true, segOn[0], activeColor, inactiveColor);
        drawHexSegment(g2, sx + sw - thick, sy + thick / 2 + gap, thick, vertH - gap, false, segOn[1], activeColor, inactiveColor);
        drawHexSegment(g2, sx + sw - thick, sy + sh / 2 + gap, thick, vertH - gap, false, segOn[2], activeColor, inactiveColor);
        drawHexSegment(g2, sx + thick / 2, sy + sh - thick, horizW, thick, true, segOn[3], activeColor, inactiveColor);
        drawHexSegment(g2, sx, sy + sh / 2 + gap, thick, vertH - gap, false, segOn[4], activeColor, inactiveColor);
        drawHexSegment(g2, sx, sy + thick / 2 + gap, thick, vertH - gap, false, segOn[5], activeColor, inactiveColor);
        drawHexSegment(g2, sx + thick / 2, sy + (sh - thick) / 2, horizW, thick, true, segOn[6], activeColor, inactiveColor);

        int dpDiameter = Math.max(4, thick - 1);
        int dpX = sx + sw + Math.max(1, g / 5);
        int dpY = sy + sh - dpDiameter;
        g2.setColor(segOn[7] ? activeColor : inactiveColor);
        g2.fillOval(dpX, dpY, dpDiameter, dpDiameter);
        g2.setColor(Color.BLACK);
        g2.drawOval(dpX, dpY, dpDiameter, dpDiameter);
    }

    private static void drawHexSegment(Graphics2D g2, int x, int y, int w, int h, boolean horizontal, boolean on, Color onCol, Color offCol) {
        Polygon poly = new Polygon();
        if (horizontal) {
            int cap = Math.min(w / 3, h / 2);
            int midY = y + h / 2;
            poly.addPoint(x, midY);
            poly.addPoint(x + cap, y);
            poly.addPoint(x + w - cap, y);
            poly.addPoint(x + w, midY);
            poly.addPoint(x + w - cap, y + h);
            poly.addPoint(x + cap, y + h);
        } else {
            int cap = Math.min(h / 3, w / 2);
            int midX = x + w / 2;
            poly.addPoint(midX, y);
            poly.addPoint(x + w, y + cap);
            poly.addPoint(x + w, y + h - cap);
            poly.addPoint(midX, y + h);
            poly.addPoint(x, y + h - cap);
            poly.addPoint(x, y + cap);
        }

        g2.setColor(on ? onCol : offCol);
        g2.fillPolygon(poly);
        g2.setColor(Color.BLACK);
        g2.drawPolygon(poly);
    }

    private static void paintValueCourtyard(Graphics2D g2, BlockInstance b, int x, int y, int w, int h, int g, boolean live) {
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

    private static void paintXVarCourtyard(Graphics2D g2, BlockInstance b, int x, int y, int w, int h, int g) {
        g2.setColor(new Color(0xC0, 0xC0, 0xC0));
        g2.fillRect(x, y, w, h);
    }
}
