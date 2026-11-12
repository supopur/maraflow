package cz.maramat.model;

import java.awt.Point;
import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class Bus {
    private String id;
    private String name;
    private boolean userEditedRoute = false;
    private final List<Point2D.Double> gridPoints = new ArrayList<>();

    public Bus(String name, Point2D.Double startGrid, Point2D.Double endGrid) {
        this.id = UUID.randomUUID().toString();
        this.name = name;
        routeShortest(startGrid, endGrid);
    }

    public Bus(String name, Point startGrid, Point endGrid) {
        this(name, new Point2D.Double(startGrid.x, startGrid.y), new Point2D.Double(endGrid.x, endGrid.y));
    }

    public Bus copy() {
        Bus b = new Bus(name, new Point2D.Double(0, 0), new Point2D.Double(1, 0));
        b.id = this.id;
        b.userEditedRoute = this.userEditedRoute;
        b.gridPoints.clear();
        for (Point2D.Double p : this.gridPoints) {
            b.gridPoints.add(new Point2D.Double(p.x, p.y));
        }
        return b;
    }

    public void routeShortest(Point2D.Double start, Point2D.Double end) {
        userEditedRoute = false;
        Wire.routeShortestPoints(gridPoints, start, end);
    }

    public void updateEndpoints(Point2D.Double newStart, Point2D.Double newEnd) {
        if (!userEditedRoute || gridPoints.size() < 2) {
            routeShortest(newStart, newEnd);
            return;
        }
        Wire.updateEndpointPoints(gridPoints, newStart, newEnd);
    }

    public void addPointOnSegment(int segmentIndex, Point2D.Double splitGrid) {
        if (Wire.addPointOnSegmentPoints(gridPoints, segmentIndex, splitGrid)) {
            userEditedRoute = true;
        }
    }

    public int moveSegmentEdge(int segmentIndex, double targetGridCoord) {
        if (segmentIndex < 0 || segmentIndex >= gridPoints.size() - 1) return segmentIndex;
        userEditedRoute = true;
        return Wire.moveSegmentEdgePoints(gridPoints, segmentIndex, targetGridCoord);
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public boolean isUserEditedRoute() {
        return userEditedRoute;
    }

    public void setUserEditedRoute(boolean userEditedRoute) {
        this.userEditedRoute = userEditedRoute;
    }

    public List<Point2D.Double> getGridPoints() {
        return gridPoints;
    }
}
