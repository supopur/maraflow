package cz.maramat.sim;

import cz.maramat.model.BlockInstance;
import cz.maramat.model.Page;
import cz.maramat.model.Wire;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class BlockSorter {

    public enum OrderSortMode {
        LEFT_TO_RIGHT("Left-to-Right (X, Y Coordinates)"),
        DATAFLOW("Dataflow (Topological then X, Y)");

        private final String label;

        OrderSortMode(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }
    }

    private BlockSorter() {}

    /**
     * Assigns execution Order in increments of 10 (10, 20, 30...) per ThreadId.
     */
    public static void sortExecutionOrder(Page page, OrderSortMode mode) {
        if (page == null) return;
        Map<Integer, List<BlockInstance>> byThread = new HashMap<>();
        for (BlockInstance b : page.getBlocks()) {
            byThread.computeIfAbsent(b.getThreadId(), k -> new ArrayList<>()).add(b);
        }

        for (Map.Entry<Integer, List<BlockInstance>> entry : byThread.entrySet()) {
            List<BlockInstance> list = entry.getValue();
            if (mode == OrderSortMode.LEFT_TO_RIGHT) {
                list.sort(Comparator.comparingInt(BlockInstance::getGridX).thenComparingInt(BlockInstance::getGridY));
            } else {
                list = topologicalSortWithCoordTieBreaker(page, list);
            }
            int order = 10;
            for (BlockInstance b : list) {
                b.setOrder(order);
                order += 10;
            }
        }
    }

    private static List<BlockInstance> topologicalSortWithCoordTieBreaker(Page page, List<BlockInstance> blocks) {
        Set<String> blockIds = new HashSet<>();
        for (BlockInstance b : blocks) blockIds.add(b.getId());

        Map<String, Integer> inDegree = new HashMap<>();
        Map<String, List<String>> adj = new HashMap<>();
        for (BlockInstance b : blocks) {
            inDegree.put(b.getId(), 0);
            adj.put(b.getId(), new ArrayList<>());
        }
        for (Wire w : page.getWires()) {
            String u = w.getSourceBlockId();
            String v = w.getTargetBlockId();
            if (blockIds.contains(u) && blockIds.contains(v) && !u.equals(v)) {
                adj.get(u).add(v);
                inDegree.put(v, inDegree.get(v) + 1);
            }
        }

        List<BlockInstance> remaining = new ArrayList<>(blocks);
        List<BlockInstance> result = new ArrayList<>();
        Comparator<BlockInstance> xyComp = Comparator.comparingInt(BlockInstance::getGridX).thenComparingInt(BlockInstance::getGridY);

        while (!remaining.isEmpty()) {
            List<BlockInstance> zeroIn = new ArrayList<>();
            for (BlockInstance b : remaining) {
                if (inDegree.getOrDefault(b.getId(), 0) <= 0) {
                    zeroIn.add(b);
                }
            }
            if (zeroIn.isEmpty()) {
                // Feedback cycle: break cycle using leftmost block
                remaining.sort(xyComp);
                zeroIn.add(remaining.getFirst());
            } else {
                zeroIn.sort(xyComp);
            }
            BlockInstance pick = zeroIn.getFirst();
            remaining.remove(pick);
            result.add(pick);
            for (String nextId : adj.getOrDefault(pick.getId(), List.of())) {
                inDegree.put(nextId, inDegree.getOrDefault(nextId, 1) - 1);
            }
        }
        return result;
    }

    /**
     * Arranges blocks on the page into clean topological columns aligned to the integer grid.
     */
    public static void sortBlocksGraphically(Page page) {
        if (page == null || page.getBlocks().isEmpty()) return;
        List<BlockInstance> blocks = page.getBlocks();
        Map<String, Integer> rankMap = new HashMap<>();
        for (BlockInstance b : blocks) rankMap.put(b.getId(), 0);

        // Relax ranks along directed wires (bounded by N iterations to handle feedback loops safely)
        int maxIter = blocks.size();
        for (int iter = 0; iter < maxIter; iter++) {
            boolean changed = false;
            for (Wire w : page.getWires()) {
                int rSrc = rankMap.getOrDefault(w.getSourceBlockId(), 0);
                int rDst = rankMap.getOrDefault(w.getTargetBlockId(), 0);
                if (rDst <= rSrc && rSrc < maxIter) {
                    rankMap.put(w.getTargetBlockId(), rSrc + 1);
                    changed = true;
                }
            }
            if (!changed) break;
        }

        Map<Integer, List<BlockInstance>> columns = new HashMap<>();
        for (BlockInstance b : blocks) {
            int r = rankMap.getOrDefault(b.getId(), 0);
            columns.computeIfAbsent(r, k -> new ArrayList<>()).add(b);
        }

        List<Integer> sortedRanks = new ArrayList<>(columns.keySet());
        sortedRanks.sort(Integer::compareTo);

        int currentGridX = 8;
        for (int r : sortedRanks) {
            List<BlockInstance> colBlocks = columns.get(r);
            colBlocks.sort(Comparator.comparingInt(BlockInstance::getGridY).thenComparingInt(BlockInstance::getOrder));
            int currentGridY = 6;
            int maxWidthInCol = 7;
            for (BlockInstance b : colBlocks) {
                b.setGridX(currentGridX);
                b.setGridY(currentGridY);
                currentGridY += b.getGridHeight() + 3;
                maxWidthInCol = Math.max(maxWidthInCol, b.getGridWidth());
            }
            currentGridX += maxWidthInCol + 8;
        }

        // Re-route all wires cleanly
        for (Wire w : page.getWires()) {
            BlockInstance src = page.findBlockById(w.getSourceBlockId());
            BlockInstance dst = page.findBlockById(w.getTargetBlockId());
            if (src != null && dst != null
                    && w.getSourcePinIndex() < src.getOutputs().size()
                    && w.getTargetPinIndex() < dst.getInputs().size()) {
                w.routeShortest(
                        src.getOutputPinAnchorGrid(w.getSourcePinIndex()),
                        dst.getInputPinAnchorGrid(w.getTargetPinIndex())
                );
            }
        }
    }
}
