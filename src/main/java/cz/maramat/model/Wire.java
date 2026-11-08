package cz.maramat.model;

import java.awt.Point;
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
    private final List<Point> gridPoints = new ArrayList<>();

    public Wire(
            String sourceBlockId,
            int sourcePinIndex,
            String targetBlockId,
            int targetPinIndex,
            String defaultNetName,
            Point startGrid,
            Point endGrid
    ) {
        this.id = UUID.randomUUID().toString();
        this.sourceBlockId = sourceBlockId;
        this.sourcePinIndex = sourcePinIndex;
        this.targetBlockId = targetBlockId;
        this.targetPinIndex = targetPinIndex;
        this.netName = defaultNetName;
        routeShortest(startGrid, endGrid);
    }

    public Wire copy() {
        Wire w = new Wire(sourceBlockId, sourcePinIndex, targetBlockId, targetPinIndex, netName, new Point(0, 0), new Point(1, 0));
        w.id = this.id;
        w.customNetName = this.customNetName;
        w.hidden = this.hidden;
        w.busId = this.busId;
        w.userEditedRoute = this.userEditedRoute;
        w.gridPoints.clear();
        for (Point p : this.gridPoints) {
            w.gridPoints.add(new Point(p.x, p.y));
        }
        return w;
    }

    /**
     * Computes collision-agnostic orthogonal shortest route on integer grid.
     */
    public void routeShortest(Point start, Point end) {
        gridPoints.clear();
        userEditedRoute = false;
        int x1 = start.x;
        int y1 = start.y;
        int x2 = end.x;
        int y2 = end.y;

        if (x2 > x1) {
            if (y1 == y2) {
                gridPoints.add(new Point(x1, y1));
                gridPoints.add(new Point(x2, y2));
            } else {
                int midX = (x1 + x2) / 2;
                gridPoints.add(new Point(x1, y1));
                gridPoints.add(new Point(midX, y1));
                gridPoints.add(new Point(midX, y2));
                gridPoints.add(new Point(x2, y2));
            }
        } else {
            int stepOut = 1;
            int midY = (y1 + y2) / 2;
            if (midY == y1) midY = y1 - 3;
            gridPoints.add(new Point(x1, y1));
            gridPoints.add(new Point(x1 + stepOut, y1));
            gridPoints.add(new Point(x1 + stepOut, midY));
            gridPoints.add(new Point(x2 - stepOut, midY));
            gridPoints.add(new Point(x2 - stepOut, y2));
            gridPoints.add(new Point(x2, y2));
        }
    }

    public void updateEndpoints(Point newStart, Point newEnd) {
        if (!userEditedRoute || gridPoints.size() < 2) {
            routeShortest(newStart, newEnd);
            return;
        }
        // Preserve intermediate user-edited edges while anchoring start and end
        Point p0 = gridPoints.get(0);
        Point p1 = gridPoints.get(1);
        boolean firstHorizontal = (p0.y == p1.y);
        p0.setLocation(newStart);
        if (firstHorizontal) {
            p1.y = newStart.y;
        } else {
            p1.x = newStart.x;
        }

        int n = gridPoints.size();
        Point pLast = gridPoints.get(n - 1);
        Point pPrev = gridPoints.get(n - 2);
        boolean lastHorizontal = (pLast.y == pPrev.y);
        pLast.setLocation(newEnd);
        if (lastHorizontal) {
            pPrev.y = newEnd.y;
        } else {
            pPrev.x = newEnd.x;
        }
    }

    /**
     * Splits segment [segmentIndex -> segmentIndex+1] at splitGrid so the user can drag a new edge (Blender style).
     */
    public void addPointOnSegment(int segmentIndex, Point splitGrid) {
        if (segmentIndex < 0 || segmentIndex >= gridPoints.size() - 1) return;
        Point a = gridPoints.get(segmentIndex);
        Point b = gridPoints.get(segmentIndex + 1);
        boolean horizontal = (a.y == b.y);
        if (horizontal) {
            int sx = Math.clamp(splitGrid.x, Math.min(a.x, b.x), Math.max(a.x, b.x));
            gridPoints.add(segmentIndex + 1, new Point(sx, a.y));
            gridPoints.add(segmentIndex + 2, new Point(sx, a.y));
        } else {
            int sy = Math.clamp(splitGrid.y, Math.min(a.y, b.y), Math.max(a.y, b.y));
            gridPoints.add(segmentIndex + 1, new Point(a.x, sy));
            gridPoints.add(segmentIndex + 2, new Point(a.x, sy));
        }
        userEditedRoute = true;
    }

    /**
     * Moves an edge (segment [segmentIndex -> segmentIndex+1]) perpendicular to its orientation on the grid.
     * Returns the updated segment index in case stub vertices were inserted at the pin ends.
     */
    public int moveSegmentEdge(int segmentIndex, int targetGridCoord) {
        if (segmentIndex < 0 || segmentIndex >= gridPoints.size() - 1) return segmentIndex;
        userEditedRoute = true;

        Point a = gridPoints.get(segmentIndex);
        Point b = gridPoints.get(segmentIndex + 1);
        boolean horizontal = (a.y == b.y && a.x != b.x);
        boolean vertical = (a.x == b.x && a.y != b.y);
        if (!horizontal && !vertical) {
            // Zero-length bridge: infer orientation from neighbors
            if (segmentIndex > 0) {
                Point prev = gridPoints.get(segmentIndex - 1);
                horizontal = (prev.x == a.x);
                vertical = !horizontal;
            } else {
                horizontal = true;
            }
        }

        if (horizontal) {
            // If dragging the first segment attached directly to start pin, insert an orthogonal stub
            if (segmentIndex == 0) {
                Point pin = new Point(a.x, a.y);
                int stubX = a.x + 1;
                a.x = stubX;
                gridPoints.add(0, new Point(stubX, pin.y));
                gridPoints.add(0, pin);
                segmentIndex += 2;
                a = gridPoints.get(segmentIndex);
                b = gridPoints.get(segmentIndex + 1);
            }
            // If dragging the last segment attached directly to end pin, insert an orthogonal stub
            if (segmentIndex == gridPoints.size() - 2) {
                Point pin = new Point(b.x, b.y);
                int stubX = b.x - 1;
                b.x = stubX;
                gridPoints.add(new Point(stubX, pin.y));
                gridPoints.add(pin);
            }
            a.y = targetGridCoord;
            b.y = targetGridCoord;
        } else if (vertical) {
            if (segmentIndex == 0) {
                Point pin = new Point(a.x, a.y);
                gridPoints.add(0, new Point(a.x, a.y));
                gridPoints.add(0, pin);
                segmentIndex += 2;
                a = gridPoints.get(segmentIndex);
                b = gridPoints.get(segmentIndex + 1);
            }
            if (segmentIndex == gridPoints.size() - 2) {
                Point pin = new Point(b.x, b.y);
                gridPoints.add(new Point(b.x, b.y));
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

    public String getTargetBlockId() {
        return targetBlockId;
    }

    public void setTargetBlockId(String targetBlockId) {
        this.targetBlockId = targetBlockId;
    }

    public int getTargetPinIndex() {
        return targetPinIndex;
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

    public List<Point> getGridPoints() {
        return gridPoints;
    }
}
