package cz.maramat.model;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Represents a single horizontal rung in an IEC 61131-3 Ladder Diagram (LD) page.
 * Contains:
 * - Main series elements (Contacts and/or Function Blocks) from left power rail to right
 * - Parallel OR branches spanning across columns of the main series path
 * - Output Coils on the right side connected to the right return rail
 */
public class LadderRung {
    private String id;
    private String comment;
    private int threadId;
    private int order;
    private final List<LadderElement> mainElements = new ArrayList<>();
    private final List<LadderBranch> parallelBranches = new ArrayList<>();
    private final List<LadderElement> coils = new ArrayList<>();

    private transient boolean rungOutputEnergized = false;

    public LadderRung(int threadId, int order, String comment) {
        this.id = UUID.randomUUID().toString();
        this.threadId = Math.clamp(threadId, 1, 5);
        this.order = order;
        this.comment = comment == null ? "" : comment;
    }

    public LadderRung copy() {
        LadderRung r = new LadderRung(threadId, order, comment);
        r.id = this.id;
        for (LadderElement el : mainElements) {
            r.mainElements.add(el.copy());
        }
        for (LadderBranch br : parallelBranches) {
            r.parallelBranches.add(br.copy());
        }
        for (LadderElement c : coils) {
            r.coils.add(c.copy());
        }
        return r;
    }

    public void resetRuntimeState() {
        rungOutputEnergized = false;
        for (LadderElement el : mainElements) {
            el.resetRuntimeState();
        }
        for (LadderBranch br : parallelBranches) {
            br.resetRuntimeState();
        }
        for (LadderElement c : coils) {
            c.resetRuntimeState();
        }
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment == null ? "" : comment;
    }

    public int getThreadId() {
        return threadId;
    }

    public void setThreadId(int threadId) {
        this.threadId = Math.clamp(threadId, 1, 5);
    }

    public int getOrder() {
        return order;
    }

    public void setOrder(int order) {
        this.order = order;
    }

    public List<LadderElement> getMainElements() {
        return mainElements;
    }

    public List<LadderBranch> getParallelBranches() {
        return parallelBranches;
    }

    public List<LadderElement> getCoils() {
        return coils;
    }

    public boolean isRungOutputEnergized() {
        return rungOutputEnergized;
    }

    public void setRungOutputEnergized(boolean rungOutputEnergized) {
        this.rungOutputEnergized = rungOutputEnergized;
    }
}
