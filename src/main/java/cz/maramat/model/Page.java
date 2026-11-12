package cz.maramat.model;

import java.awt.Point;
import java.awt.geom.Point2D;
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
                    Point2D.Double pStart = src.getOutputPinAnchorGrid(w.getSourcePinIndex());
                    Point2D.Double pEnd = dst.getInputPinAnchorGrid(w.getTargetPinIndex());
                    w.updateEndpoints(pStart, pEnd);
                    if (!w.isCustomNetName()) {
                        w.setNetName(src.getName() + "." + src.getOutputs().get(w.getSourcePinIndex()).getShortName(), false);
                    }
                }
            }
        }
        updateBussesForBlock(block);
    }

    public Bus findBusByName(String busName) {
        if (busName == null) return null;
        for (Bus b : busses) {
            if (busName.equals(b.getName())) {
                return b;
            }
        }
        return null;
    }

    public Bus getOrCreateBusForBundle(String busId, Point2D.Double startGrid, Point2D.Double endGrid) {
        Bus existing = findBusByName(busId);
        if (existing != null) {
            if (!existing.isUserEditedRoute()) {
                existing.routeShortest(startGrid, endGrid);
            }
            return existing;
        }
        Bus created = new Bus(busId, startGrid, endGrid);
        busses.add(created);
        return created;
    }

    public Point2D.Double[] computeBusTrunkEndpoints(List<Wire> bundle) {
        if (bundle == null || bundle.isEmpty()) return null;
        Wire first = bundle.getFirst();
        BlockInstance src = findBlockById(first.getSourceBlockId());
        BlockInstance dst = findBlockById(first.getTargetBlockId());
        if (src == null || dst == null) return null;

        double srcCollectorX = src.getGridX() + src.getGridWidth() + 2.0;
        double dstDistributorX = dst.getGridX() - 2.0;

        double minSrcJoinY = Double.MAX_VALUE;
        double maxSrcJoinY = -Double.MAX_VALUE;
        double minDstJoinY = Double.MAX_VALUE;
        double maxDstJoinY = -Double.MAX_VALUE;

        for (Wire w : bundle) {
            if (w.getSourcePinIndex() >= src.getOutputs().size() || w.getTargetPinIndex() >= dst.getInputs().size()) {
                continue;
            }
            double srcJoinY = src.getOutputPinAnchorGrid(w.getSourcePinIndex()).y + 1.0;
            double dstJoinY = dst.getInputPinAnchorGrid(w.getTargetPinIndex()).y + 1.0;
            minSrcJoinY = Math.min(minSrcJoinY, srcJoinY);
            maxSrcJoinY = Math.max(maxSrcJoinY, srcJoinY);
            minDstJoinY = Math.min(minDstJoinY, dstJoinY);
            maxDstJoinY = Math.max(maxDstJoinY, dstJoinY);
        }
        if (minSrcJoinY == Double.MAX_VALUE) return null;

        double trunkY = Math.floor((minSrcJoinY + maxSrcJoinY) / 2.0);
        double dstTrunkY = Math.floor((minDstJoinY + maxDstJoinY) / 2.0);
        return new Point2D.Double[]{
                new Point2D.Double(srcCollectorX, trunkY),
                new Point2D.Double(dstDistributorX, dstTrunkY)
        };
    }

    private void updateBussesForBlock(BlockInstance block) {
        for (Bus b : busses) {
            List<Wire> bundle = new ArrayList<>();
            boolean touchesBlock = false;
            for (Wire w : wires) {
                if (!w.isHidden() && b.getName().equals(w.getBusId())) {
                    bundle.add(w);
                    if (w.getSourceBlockId().equals(block.getId()) || w.getTargetBlockId().equals(block.getId())) {
                        touchesBlock = true;
                    }
                }
            }
            if (touchesBlock && !bundle.isEmpty()) {
                Point2D.Double[] ends = computeBusTrunkEndpoints(bundle);
                if (ends != null) {
                    b.updateEndpoints(ends[0], ends[1]);
                }
            }
        }
    }
}
