package cz.maramat.model;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class Bus {
    private String id;
    private String name;
    private final List<Point> gridPoints = new ArrayList<>();

    public Bus(String name, Point startGrid, Point endGrid) {
        this.id = UUID.randomUUID().toString();
        this.name = name;
        gridPoints.add(new Point(startGrid));
        if (startGrid.x != endGrid.x && startGrid.y != endGrid.y) {
            int midX = (startGrid.x + endGrid.x) / 2;
            gridPoints.add(new Point(midX, startGrid.y));
            gridPoints.add(new Point(midX, endGrid.y));
        }
        gridPoints.add(new Point(endGrid));
    }

    public Bus copy() {
        Bus b = new Bus(name, new Point(0, 0), new Point(1, 0));
        b.id = this.id;
        b.gridPoints.clear();
        for (Point p : this.gridPoints) {
            b.gridPoints.add(new Point(p));
        }
        return b;
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

    public List<Point> getGridPoints() {
        return gridPoints;
    }
}
