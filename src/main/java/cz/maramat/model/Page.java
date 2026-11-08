package cz.maramat.model;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;

public class Page {
    private String name;
    private final List<BlockInstance> blocks = new ArrayList<>();
    private final List<Wire> wires = new ArrayList<>();
    private final List<Bus> busses = new ArrayList<>();
    private final List<GraphicElement> graphics = new ArrayList<>();

    public Page(String name) {
        this.name = name;
    }

    public Page copy() {
        Page p = new Page(name);
        for (BlockInstance b : blocks) p.blocks.add(b.copy());
        for (Wire w : wires) p.wires.add(w.copy());
        for (Bus b : busses) p.busses.add(b.copy());
        for (GraphicElement g : graphics) p.graphics.add(g.copy());
        return p;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = (name == null || name.isBlank()) ? "Page" : name.trim();
    }

    /**
     * A page is a Macro/Function if its name begins with '*'.
     */
    public boolean isMacro() {
        return name != null && name.startsWith("*");
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

    public BlockInstance findBlockById(String id) {
        for (BlockInstance b : blocks) {
            if (b.getId().equals(id)) return b;
        }
        return null;
    }

    public BlockInstance findBlockByName(String blockName) {
        for (BlockInstance b : blocks) {
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
        int idx = 1;
        while (true) {
            String candidate = prefix + idx;
            if (findBlockByName(candidate) == null) {
                return candidate;
            }
            idx++;
        }
    }

    public int nextOrderForThread(int threadId) {
        int maxOrder = 0;
        for (BlockInstance b : blocks) {
            if (b.getThreadId() == threadId && b.getOrder() > maxOrder) {
                maxOrder = b.getOrder();
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
