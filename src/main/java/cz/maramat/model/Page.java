package cz.maramat.model;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;

public class Page {
    private String name;
    private IecLanguage language = IecLanguage.FBD;
    private boolean eStopPage = false;
    private int stThreadId = 2;
    private int stOrder = 10;
    private String stCode = "";

    private final List<BlockInstance> blocks = new ArrayList<>();
    private final List<Wire> wires = new ArrayList<>();
    private final List<Bus> busses = new ArrayList<>();
    private final List<GraphicElement> graphics = new ArrayList<>();
    private final List<LadderRung> rungs = new ArrayList<>();
    private final List<StVariable> stVariables = new ArrayList<>();

    public Page(String name) {
        this(name, IecLanguage.FBD);
    }

    public Page(String name, IecLanguage language) {
        this.name = name;
        this.language = (language == null) ? IecLanguage.FBD : language;
        this.eStopPage = (name != null && name.startsWith("!"));
    }

    public Page copy() {
        Page p = new Page(name, language);
        p.eStopPage = this.eStopPage;
        p.stThreadId = this.stThreadId;
        p.stOrder = this.stOrder;
        p.stCode = this.stCode;
        for (BlockInstance b : blocks) p.blocks.add(b.copy());
        for (Wire w : wires) p.wires.add(w.copy());
        for (Bus b : busses) p.busses.add(b.copy());
        for (GraphicElement g : graphics) p.graphics.add(g.copy());
        for (LadderRung r : rungs) p.rungs.add(r.copy());
        for (StVariable v : stVariables) p.stVariables.add(v.copy());
        return p;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = (name == null || name.isBlank()) ? "Page" : name.trim();
        if (this.name.startsWith("!")) {
            this.eStopPage = true;
        }
    }

    public IecLanguage getLanguage() {
        return language;
    }

    public void setLanguage(IecLanguage language) {
        this.language = (language == null) ? IecLanguage.FBD : language;
    }

    /**
     * A page is a Macro/Function if its name begins with '*'.
     */
    public boolean isMacro() {
        return name != null && name.startsWith("*");
    }

    public String getCleanName() {
        if (name == null) return "";
        if (name.startsWith("*") || name.startsWith("!")) {
            return name.substring(1).trim();
        }
        return name.trim();
    }

    /**
     * A page is a dedicated E-Stop Handler Page if marked or if its name begins with '!'.
     */
    public boolean isEStopPage() {
        return eStopPage || (name != null && name.startsWith("!"));
    }

    public void setEStopPage(boolean eStopPage) {
        this.eStopPage = eStopPage;
    }

    public int getStThreadId() {
        return stThreadId;
    }

    public void setStThreadId(int stThreadId) {
        this.stThreadId = Math.clamp(stThreadId, 1, 5);
    }

    public int getStOrder() {
        return stOrder;
    }

    public void setStOrder(int stOrder) {
        this.stOrder = Math.max(0, stOrder);
    }

    public String getStCode() {
        return stCode;
    }

    public void setStCode(String stCode) {
        this.stCode = stCode == null ? "" : stCode;
    }

    public List<StVariable> getStVariables() {
        return stVariables;
    }

    public List<BlockInstance> getBlocks() {
        return blocks;
    }

    public List<Wire> getWires() {
        return wires;
    }

    public List<Bus> getBusses() {
        return busses;
    }

    public List<GraphicElement> getGraphics() {
        return graphics;
    }

    public List<LadderRung> getRungs() {
        return rungs;
    }

    /**
     * Returns all BlockInstances on this page, including Function Blocks embedded inside Ladder rungs.
     */
    public List<BlockInstance> getAllBlockInstances() {
        if (language == IecLanguage.FBD) {
            return blocks;
        }
        List<BlockInstance> all = new ArrayList<>(blocks);
        for (LadderRung r : rungs) {
            for (LadderElement el : r.getMainElements()) {
                if (el.getKind() == LadderElement.Kind.FUNCTION_BLOCK && el.getBlockInstance() != null) {
                    all.add(el.getBlockInstance());
                }
            }
            for (LadderBranch br : r.getParallelBranches()) {
                for (LadderElement el : br.getElements()) {
                    if (el.getKind() == LadderElement.Kind.FUNCTION_BLOCK && el.getBlockInstance() != null) {
                        all.add(el.getBlockInstance());
                    }
                }
            }
        }
        return all;
    }

    public BlockInstance findBlockById(String id) {
        for (BlockInstance b : getAllBlockInstances()) {
            if (b.getId().equals(id)) return b;
        }
        return null;
    }

    public BlockInstance findBlockByName(String blockName) {
        for (BlockInstance b : getAllBlockInstances()) {
            if (b.getName().equalsIgnoreCase(blockName)) return b;
        }
        return null;
    }

    public boolean isInputPinConnected(String blockId, int pinIndex) {
        for (Wire w : wires) {
            if (w.getTargetBlockId().equals(blockId) && w.getTargetPinIndex() == pinIndex) {
                return true;
            }
        }
        return false;
    }

    public Wire findWireToInputPin(String blockId, int pinIndex) {
        for (Wire w : wires) {
            if (w.getTargetBlockId().equals(blockId) && w.getTargetPinIndex() == pinIndex) {
                return w;
            }
        }
        return null;
    }

    public String nextUniqueBlockName(String prefix) {
        String cleanPrefix = prefix.replaceAll("\\s+", "");
        int idx = 1;
        while (true) {
            String candidate = cleanPrefix + idx;
            if (findBlockByName(candidate) == null) {
                return candidate;
            }
            idx++;
        }
    }

    public int nextOrderForThread(int threadId) {
        int maxOrder = 0;
        for (BlockInstance b : getAllBlockInstances()) {
            if (b.getThreadId() == threadId && b.getOrder() > maxOrder) {
                maxOrder = b.getOrder();
            }
        }
        for (LadderRung r : rungs) {
            if (r.getThreadId() == threadId && r.getOrder() > maxOrder) {
                maxOrder = r.getOrder();
            }
        }
        return ((maxOrder / 10) + 1) * 10;
    }

    public void updateWiresForBlock(BlockInstance block) {
        for (Wire w : wires) {
            if (w.getSourceBlockId().equals(block.getId()) || w.getTargetBlockId().equals(block.getId())) {
                BlockInstance src = findBlockById(w.getSourceBlockId());
                BlockInstance dst = findBlockById(w.getTargetBlockId());
                if (src != null && dst != null
                        && w.getSourcePinIndex() < src.getOutputs().size()
                        && w.getTargetPinIndex() < dst.getInputs().size()) {
                    Point pStart = src.getOutputPinAnchorGrid(w.getSourcePinIndex());
                    Point pEnd = dst.getInputPinAnchorGrid(w.getTargetPinIndex());
                    w.updateEndpoints(pStart, pEnd);
                    if (!w.isCustomNetName()) {
                        w.setNetName(src.getName() + "." + src.getOutputs().get(w.getSourcePinIndex()).getShortName(), false);
                    }
                }
            }
        }
    }
}
