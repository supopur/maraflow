package cz.maramat.model;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Represents a parallel OR branch beneath a segment of the main series path on a LadderRung.
 * Spans from startColumn (0-based index before main element at startColumn)
 * to endColumn (inclusive index of the main element after which the branch rejoins).
 */
public class LadderBranch {
    private String id;
    private int startColumn;
    private int endColumn;
    private final List<LadderElement> elements = new ArrayList<>();

    private transient boolean branchEnergizedIn = false;
    private transient boolean branchEnergizedOut = false;

    public LadderBranch(int startColumn, int endColumn) {
        this.id = UUID.randomUUID().toString();
        this.startColumn = Math.max(0, startColumn);
        this.endColumn = Math.max(this.startColumn, endColumn);
    }

    public LadderBranch copy() {
        LadderBranch b = new LadderBranch(startColumn, endColumn);
        b.id = this.id;
        for (LadderElement el : elements) {
            b.elements.add(el.copy());
        }
        return b;
    }

    public void resetRuntimeState() {
        branchEnergizedIn = false;
        branchEnergizedOut = false;
        for (LadderElement el : elements) {
            el.resetRuntimeState();
        }
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public int getStartColumn() {
        return startColumn;
    }

    public void setStartColumn(int startColumn) {
        this.startColumn = Math.max(0, startColumn);
        if (this.endColumn < this.startColumn) {
            this.endColumn = this.startColumn;
        }
    }

    public int getEndColumn() {
        return endColumn;
    }

    public void setEndColumn(int endColumn) {
        this.endColumn = Math.max(this.startColumn, endColumn);
    }

    public List<LadderElement> getElements() {
        return elements;
    }

    public boolean isBranchEnergizedIn() {
        return branchEnergizedIn;
    }

    public void setBranchEnergizedIn(boolean branchEnergizedIn) {
        this.branchEnergizedIn = branchEnergizedIn;
    }

    public boolean isBranchEnergizedOut() {
        return branchEnergizedOut;
    }

    public void setBranchEnergizedOut(boolean branchEnergizedOut) {
        this.branchEnergizedOut = branchEnergizedOut;
    }
}
