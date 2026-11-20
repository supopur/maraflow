package cz.maramat.help.diagram;

import java.awt.geom.Point2D;
import java.util.List;
import java.util.Map;

public record FbdDiagramSpec(
        Integer widthGrid,
        Integer heightGrid,
        List<BlockNode> blocks,
        List<WireEdge> wires,
        List<BusEdge> busses,
        List<LiveValue> liveValues,
        List<GraphicNode> graphics,
        String caption
) {
    public FbdDiagramSpec(
            Integer widthGrid,
            Integer heightGrid,
            List<BlockNode> blocks,
            List<WireEdge> wires,
            List<BusEdge> busses,
            List<LiveValue> liveValues,
            String caption
    ) {
        this(widthGrid, heightGrid, blocks, wires, busses, liveValues, List.of(), caption);
    }

    public record PinSpec(String name, String dataType) {}

    public record BlockNode(
            String alias,
            String catalogId,
            int gridX,
            int gridY,
            String displayName,
            Map<String, String> pinConstants,
            List<PinSpec> customInputs,
            List<PinSpec> customOutputs,
            Integer variableInputCount,
            String primaryDataType,
            Map<String, String> customProperties,
            List<String> negatedPins
    ) {
        public BlockNode(
                String alias,
                String catalogId,
                int gridX,
                int gridY,
                String displayName,
                Map<String, String> pinConstants,
                List<PinSpec> customInputs,
                List<PinSpec> customOutputs,
                Integer variableInputCount,
                String primaryDataType,
                Map<String, String> customProperties
        ) {
            this(alias, catalogId, gridX, gridY, displayName, pinConstants, customInputs, customOutputs, variableInputCount, primaryDataType, customProperties, List.of());
        }

        public BlockNode(
                String alias,
                String catalogId,
                int gridX,
                int gridY,
                String displayName,
                Map<String, String> pinConstants
        ) {
            this(alias, catalogId, gridX, gridY, displayName, pinConstants, List.of(), List.of(), null, null, Map.of(), List.of());
        }
    }

    public record GraphicNode(
            String kind,
            int gridX,
            int gridY,
            int gridW,
            int gridH,
            String text,
            String fillHex,
            String strokeHex,
            PinRef bindPin,
            String activeHex,
            String inactiveHex
    ) {}

    public record PinRef(String alias, String pinName) {}

    public record WireEdge(PinRef from, PinRef to, List<Point2D.Double> viaPoints) {
        public WireEdge(PinRef from, PinRef to) {
            this(from, to, List.of());
        }
    }

    public record BusEdge(PinRef from, List<PinRef> targets) {}

    public record LiveValue(PinRef pin, double value) {}
}
