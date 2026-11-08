package cz.maramat.model;

import java.awt.Color;
import java.util.UUID;

public class GraphicElement {
    public enum Kind {
        LINE("Line"),
        RECTANGLE("Rectangle"),
        ROUNDED_RECTANGLE("Rounded Rectangle"),
        TRIANGLE("Triangle"),
        PENTAGON("Pentagon"),
        HEXAGON("Hexagon"),
        OCTAGON("Octagon"),
        CUSTOM_NGON("Custom N-Gon"),
        CIRCLE("Circle"),
        ELLIPSE("Ellipse / Oval"),
        ARC("Arc"),
        SECTOR("Pie / Sector"),
        TEXT("Text"),
        VISUAL_BUTTON("Visual Button");

        private final String displayName;

        Kind(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    private String id;
    private Kind kind;
    private int gridX;
    private int gridY;
    private int gridW;
    private int gridH;
    private Color strokeColor = new Color(0x33, 0x33, 0x33);
    private Color fillColor = new Color(0xF0, 0xF4, 0xF8);
    private boolean filled = false;
    private int strokeWidth = 1;
    private boolean dashed = false;
    private int ngonSides = 6;
    private String text = "Label";
    private int fontSize = 11;

    // Visual Button specific fields
    private String boundTargetPin = ""; // Format: BlockName.PinName
    private boolean toggleButton = false;
    private transient boolean activeState = false;

    public GraphicElement(Kind kind, int gridX, int gridY, int gridW, int gridH) {
        this.id = UUID.randomUUID().toString();
        this.kind = kind;
        this.gridX = gridX;
        this.gridY = gridY;
        this.gridW = gridW;
        this.gridH = gridH;
        if (kind == Kind.VISUAL_BUTTON) {
            this.text = "BTN";
            this.filled = true;
            this.fillColor = new Color(0xDC, 0xE2, 0xE8);
        }
    }

    public GraphicElement copy() {
        GraphicElement g = new GraphicElement(kind, gridX, gridY, gridW, gridH);
        g.id = this.id;
        g.strokeColor = this.strokeColor;
        g.fillColor = this.fillColor;
        g.filled = this.filled;
        g.strokeWidth = this.strokeWidth;
        g.dashed = this.dashed;
        g.ngonSides = this.ngonSides;
        g.text = this.text;
        g.fontSize = this.fontSize;
        g.boundTargetPin = this.boundTargetPin;
        g.toggleButton = this.toggleButton;
        g.activeState = this.activeState;
        return g;
    }

    public boolean containsGridPoint(int gx, int gy) {
        int x0 = Math.min(gridX, gridX + gridW);
        int y0 = Math.min(gridY, gridY + gridH);
        int w = Math.max(1, Math.abs(gridW));
        int h = Math.max(1, Math.abs(gridH));
        return gx >= x0 && gx <= x0 + w && gy >= y0 && gy <= y0 + h;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public Kind getKind() {
        return kind;
    }

    public void setKind(Kind kind) {
        this.kind = kind;
    }

    public int getGridX() {
        return gridX;
    }

    public void setGridX(int gridX) {
        this.gridX = gridX;
    }

    public int getGridY() {
        return gridY;
    }

    public void setGridY(int gridY) {
        this.gridY = gridY;
    }

    public int getGridW() {
        return gridW;
    }

    public void setGridW(int gridW) {
        this.gridW = gridW;
    }

    public int getGridH() {
        return gridH;
    }

    public void setGridH(int gridH) {
        this.gridH = gridH;
    }

    public Color getStrokeColor() {
        return strokeColor;
    }

    public void setStrokeColor(Color strokeColor) {
        this.strokeColor = strokeColor;
    }

    public Color getFillColor() {
        return fillColor;
    }

    public void setFillColor(Color fillColor) {
        this.fillColor = fillColor;
    }

    public boolean isFilled() {
        return filled;
    }

    public void setFilled(boolean filled) {
        this.filled = filled;
    }

    public int getStrokeWidth() {
        return strokeWidth;
    }

    public void setStrokeWidth(int strokeWidth) {
        this.strokeWidth = Math.clamp(strokeWidth, 1, 8);
    }

    public boolean isDashed() {
        return dashed;
    }

    public void setDashed(boolean dashed) {
        this.dashed = dashed;
    }

    public int getNgonSides() {
        return ngonSides;
    }

    public void setNgonSides(int ngonSides) {
        this.ngonSides = Math.clamp(ngonSides, 3, 32);
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public int getFontSize() {
        return fontSize;
    }

    public void setFontSize(int fontSize) {
        this.fontSize = Math.clamp(fontSize, 8, 48);
    }

    public String getBoundTargetPin() {
        return boundTargetPin;
    }

    public void setBoundTargetPin(String boundTargetPin) {
        this.boundTargetPin = (boundTargetPin == null) ? "" : boundTargetPin;
    }

    public boolean isToggleButton() {
        return toggleButton;
    }

    public void setToggleButton(boolean toggleButton) {
        this.toggleButton = toggleButton;
    }

    public boolean isActiveState() {
        return activeState;
    }

    public void setActiveState(boolean activeState) {
        this.activeState = activeState;
    }
}
