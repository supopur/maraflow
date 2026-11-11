package cz.maramat.model;

import java.awt.Point;
import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class Wire {
    private String id;
    private String netName;
    private boolean customNetName = false;
    private String sourceBlockId;
    private int sourcePinIndex;
    private String targetBlockId;
    private int targetPinIndex;
    private boolean hidden = false;
    private String busId = null;
    private boolean userEditedRoute = false;
    private final List<Point2D.Double> gridPoints = new ArrayList<>();

    public Wire(
            String sourceBlockId,
            int sourcePinIndex,
            String targetBlockId,
            int targetPinIndex,
            String defaultNetName,
            Point2D.Double startGrid,
            Point2D.Double endGrid
    ) {
        this.id = UUID.randomUUID().toString();
        this.sourceBlockId = sourceBlockId;
        this.sourcePinIndex = sourcePinIndex;
        this.targetBlockId = targetBlockId;
        this.targetPinIndex = targetPinIndex;
        this.netName = defaultNetName;
        routeShortest(startGrid, endGrid);
    }

    public Wire(
            String sourceBlockId,
            int sourcePinIndex,
            String targetBlockId,
            int targetPinIndex,
            String defaultNetName,
            Point startGrid,
            Point endGrid
    ) {
        this(sourceBlockId, sourcePinIndex, targetBlockId, targetPinIndex, defaultNetName,
                new Point2D.Double(startGrid.x, startGrid.y), new Point2D.Double(endGrid.x, endGrid.y));
    }

    public Wire copy() {
        Wire w = new Wire(sourceBlockId, sourcePinIndex, targetBlockId, targetPinIndex, netName, new Point2D.Double(0, 0), new Point2D.Double(1, 0));
        w.id = this.id;
        w.customNetName = this.customNetName;
        w.hidden = this.hidden;
        w.busId = this.busId;
        w.userEditedRoute = this.userEditedRoute;
        w.gridPoints.clear();
        for (Point2D.Double p : this.gridPoints) {
            w.gridPoints.add(new Point2D.Double(p.x, p.y));
        }
        return w;
    }

    /**
     * Computes collision-agnostic orthogonal shortest route on grid.
     * Starts and ends at pin vertical centers (offset by 0.5 from grid dots).
     */
    public void routeShortest(Point2D.Double start, Point2D.Double end) {
        gridPoints.clear();
        userEditedRoute = false;
        double x1 = start.x;
        double y1 = start.y;
        double x2 = end.x;
        double y2 = end.y;

        if (x2 > x1) {
            if (Math.abs(y1 - y2) < 0.001) {
                gridPoints.add(new Point2D.Double(x1, y1));
                gridPoints.add(new Point2D.Double(x2, y2));
            } else {
                double midX = Math.round((x1 + x2) / 2.0);
                gridPoints.add(new Point2D.Double(x1, y1));
                gridPoints.add(new Point2D.Double(midX, y1));
                gridPoints.add(new Point2D.Double(midX, y2));
                gridPoints.add(new Point2D.Double(x2, y2));
            }
        } else {
            double stepOut = 1.0;
            double midY = Math.round((y1 + y2) / 2.0);
            if (Math.abs(midY - y1) < 0.001) midY = y1 - 3.0;
            gridPoints.add(new Point2D.Double(x1, y1));
            gridPoints.add(new Point2D.Double(x1 + stepOut, y1));
            gridPoints.add(new Point2D.Double(x1 + stepOut, midY));
            gridPoints.add(new Point2D.Double(x2 - stepOut, midY));
            gridPoints.add(new Point2D.Double(x2 - stepOut, y2));
            gridPoints.add(new Point2D.Double(x2, y2));
        }
    }

    public void routeShortest(Point start, Point end) {
        routeShortest(new Point2D.Double(start.x, start.y), new Point2D.Double(end.x, end.y));
    }

    public void updateEndpoints(Point2D.Double newStart, Point2D.Double newEnd) {
        if (!userEditedRoute || gridPoints.size() < 2) {
            routeShortest(newStart, newEnd);
            return;
        }
        // Preserve intermediate user-edited edges while anchoring start and end
        Point2D.Double p0 = gridPoints.get(0);
        Point2D.Double p1 = gridPoints.get(1);
        boolean firstHorizontal = (Math.abs(p0.y - p1.y) < 0.001);
        p0.setLocation(newStart);
        if (firstHorizontal) {
            p1.y = newStart.y;
        } else {
            p1.x = newStart.x;
        }

        int n = gridPoints.size();
        Point2D.Double pLast = gridPoints.get(n - 1);
        Point2D.Double pPrev = gridPoints.get(n - 2);
        boolean lastHorizontal = (Math.abs(pLast.y - pPrev.y) < 0.001);
        pLast.setLocation(newEnd);
        if (lastHorizontal) {
            pPrev.y = newEnd.y;
        } else {
            pPrev.x = newEnd.x;
        }
    }

    public void updateEndpoints(Point newStart, Point newEnd) {
        updateEndpoints(new Point2D.Double(newStart.x, newStart.y), new Point2D.Double(newEnd.x, newEnd.y));
    }

    /**
     * Splits segment [segmentIndex -> segmentIndex+1] at splitGrid so the user can drag a new edge (Blender style).
     */
    public void addPointOnSegment(int segmentIndex, Point2D.Double splitGrid) {
        if (segmentIndex < 0 || segmentIndex >= gridPoints.size() - 1) return;
        Point2D.Double a = gridPoints.get(segmentIndex);
        Point2D.Double b = gridPoints.get(segmentIndex + 1);
        boolean horizontal = (Math.abs(a.y - b.y) < 0.001);
        if (horizontal) {
            double sx = Math.clamp(splitGrid.x, Math.min(a.x, b.x), Math.max(a.x, b.x));
            gridPoints.add(segmentIndex + 1, new Point2D.Double(sx, a.y));
            gridPoints.add(segmentIndex + 2, new Point2D.Double(sx, a.y));
        } else {
            double sy = Math.clamp(splitGrid.y, Math.min(a.y, b.y), Math.max(a.y, b.y));
            gridPoints.add(segmentIndex + 1, new Point2D.Double(a.x, sy));
            gridPoints.add(segmentIndex + 2, new Point2D.Double(a.x, sy));
        }
        userEditedRoute = true;
    }

    public void addPointOnSegment(int segmentIndex, Point splitGrid) {
        addPointOnSegment(segmentIndex, new Point2D.Double(splitGrid.x, splitGrid.y));
    }

    /**
     * Moves an edge (segment [segmentIndex -> segmentIndex+1]) perpendicular to its orientation on the grid.
     * Returns the updated segment index in case stub vertices were inserted at the pin ends.
     */
    public int moveSegmentEdge(int segmentIndex, double targetGridCoord) {
        if (segmentIndex < 0 || segmentIndex >= gridPoints.size() - 1) return segmentIndex;
        userEditedRoute = true;

        Point2D.Double a = gridPoints.get(segmentIndex);
        Point2D.Double b = gridPoints.get(segmentIndex + 1);
        boolean horizontal = (Math.abs(a.y - b.y) < 0.001 && Math.abs(a.x - b.x) >= 0.001);
        boolean vertical = (Math.abs(a.x - b.x) < 0.001 && Math.abs(a.y - b.y) >= 0.001);
        if (!horizontal && !vertical) {
            // Zero-length bridge: infer orientation from neighbors
            if (segmentIndex > 0) {
                Point2D.Double prev = gridPoints.get(segmentIndex - 1);
                horizontal = (Math.abs(prev.x - a.x) < 0.001);
                vertical = !horizontal;
            } else {
                horizontal = true;
            }
        }

        if (horizontal) {
            // If dragging the first segment attached directly to start pin, insert an orthogonal stub
            if (segmentIndex == 0) {
                Point2D.Double pin = new Point2D.Double(a.x, a.y);
                double stubX = a.x + 1.0;
                a.x = stubX;
                gridPoints.add(0, new Point2D.Double(stubX, pin.y));
                gridPoints.add(0, pin);
                segmentIndex += 2;
                a = gridPoints.get(segmentIndex);
                b = gridPoints.get(segmentIndex + 1);
            }
            // If dragging the last segment attached directly to end pin, insert an orthogonal stub
            if (segmentIndex == gridPoints.size() - 2) {
                Point2D.Double pin = new Point2D.Double(b.x, b.y);
                double stubX = b.x - 1.0;
                b.x = stubX;
                gridPoints.add(new Point2D.Double(stubX, pin.y));
                gridPoints.add(pin);
            }
            a.y = targetGridCoord;
            b.y = targetGridCoord;
        } else if (vertical) {
            if (segmentIndex == 0) {
                Point2D.Double pin = new Point2D.Double(a.x, a.y);
                gridPoints.add(0, new Point2D.Double(a.x, a.y));
                gridPoints.add(0, pin);
                segmentIndex += 2;
                a = gridPoints.get(segmentIndex);
                b = gridPoints.get(segmentIndex + 1);
            }
            if (segmentIndex == gridPoints.size() - 2) {
                Point2D.Double pin = new Point2D.Double(b.x, b.y);
                gridPoints.add(new Point2D.Double(b.x, b.y));
                gridPoints.add(pin);
            }
            a.x = targetGridCoord;
            b.x = targetGridCoord;
        }
        return segmentIndex;
    }

    public String getId() {
        return id;
    }

    public String getNetName() {
        return netName;
    }

    public void setNetName(String netName, boolean custom) {
        this.netName = netName;
        this.customNetName = custom;
    }

    public boolean isCustomNetName() {
        return customNetName;
    }

    public String getSourceBlockId() {
        return sourceBlockId;
    }

    public void setSourceBlockId(String sourceBlockId) {
        this.sourceBlockId = sourceBlockId;
    }

    public int getSourcePinIndex() {
        return sourcePinIndex;
    }

    public void setSourcePinIndex(int sourcePinIndex) {
        this.sourcePinIndex = sourcePinIndex;
    }

    public String getTargetBlockId() {
        return targetBlockId;
    }

    public void setTargetBlockId(String targetBlockId) {
        this.targetBlockId = targetBlockId;
    }

    public int getTargetPinIndex() {
        return targetPinIndex;
    }

    public void setTargetPinIndex(int targetPinIndex) {
        this.targetPinIndex = targetPinIndex;
    }

    public boolean isHidden() {
        return hidden;
    }

    public void setHidden(boolean hidden) {
        this.hidden = hidden;
    }

    public String getBusId() {
        return busId;
    }

    public void setBusId(String busId) {
        this.busId = busId;
    }

    public List<Point2D.Double> getGridPoints() {
        return gridPoints;
    }
}
