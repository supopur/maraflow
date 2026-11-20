package cz.maramat.help.diagram;

import cz.maramat.help.diagram.FbdDiagramSpec.BlockNode;
import cz.maramat.help.diagram.FbdDiagramSpec.BusEdge;
import cz.maramat.help.diagram.FbdDiagramSpec.GraphicNode;
import cz.maramat.help.diagram.FbdDiagramSpec.LiveValue;
import cz.maramat.help.diagram.FbdDiagramSpec.PinRef;
import cz.maramat.help.diagram.FbdDiagramSpec.PinSpec;
import cz.maramat.help.diagram.FbdDiagramSpec.WireEdge;
import cz.maramat.model.BlockCatalog;
import cz.maramat.model.BlockDefinition;
import cz.maramat.model.BlockInstance;
import cz.maramat.model.DataType;
import cz.maramat.model.GraphicElement;
import cz.maramat.model.Page;
import cz.maramat.model.PinInstance;
import cz.maramat.model.Wire;
import cz.maramat.ui.render.FbdRenderer;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.lang.ref.SoftReference;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Builds a throwaway {@link Page} from an {@link FbdDiagramSpec} and renders it
 * using {@link FbdRenderer} with {@link SoftReference} image caching.
 */
public final class FbdDiagramRenderer {

    public static final int DEFAULT_GRID_PX = 16;

    private static final Map<String, SoftReference<BufferedImage>> IMAGE_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, String> SOURCE_BY_HASH = new ConcurrentHashMap<>();

    private FbdDiagramRenderer() {}

    public static String registerSource(String source) {
        String hash = sha256Hex(source);
        SOURCE_BY_HASH.putIfAbsent(hash, source);
        return hash;
    }

    public static BufferedImage getOrRenderByHash(String hash, int gridPx) {
        String source = SOURCE_BY_HASH.get(hash);
        if (source == null) return null;
        return renderToImage(source, gridPx);
    }

    public static BufferedImage renderToImage(String source, int gridPx) {
        String cacheKey = sha256Hex(source) + "@" + gridPx;
        SoftReference<BufferedImage> ref = IMAGE_CACHE.get(cacheKey);
        BufferedImage cached = ref != null ? ref.get() : null;
        if (cached != null) {
            return cached;
        }

        FbdDiagramSpec spec = FbdDiagramParser.parse(source);
        BufferedImage rendered = renderSpec(spec, gridPx);
        IMAGE_CACHE.put(cacheKey, new SoftReference<>(rendered));
        return rendered;
    }

    public static BufferedImage renderSpec(FbdDiagramSpec spec, int gridPx) {
        Page page = new Page("HelpDiagram");
        Map<String, BlockInstance> byAlias = new HashMap<>();

        int idx = 1;
        for (BlockNode node : spec.blocks()) {
            BlockDefinition def = BlockCatalog.getById(node.catalogId());
            if (def == null) {
                if (node.catalogId().startsWith("Macro.") || !node.customInputs().isEmpty() || !node.customOutputs().isEmpty()) {
                    def = createSyntheticMacroDef(node, spec);
                } else {
                    throw new IllegalArgumentException("Unknown block catalog id: " + node.catalogId());
                }
            }
            if (byAlias.containsKey(node.alias())) {
                throw new IllegalArgumentException("Duplicate block alias: " + node.alias());
            }
            String instanceName = node.displayName() != null
                    ? node.displayName()
                    : def.getInstancePrefix() + idx;
            BlockInstance inst = new BlockInstance(def, instanceName, node.gridX(), node.gridY(), 2, idx);
            idx++;

            if (node.primaryDataType() != null && def.isConfigurableDataType()) {
                inst.setPrimaryDataType(DataType.valueOf(node.primaryDataType()));
            }
            if (node.variableInputCount() != null && def.isVariableInputCount()) {
                inst.setVariableInputCount(node.variableInputCount());
            } else if (def.isVariableInputCount()) {
                int maxIdx = maxReferencedXIndex(node, spec);
                int needed = "Main.Switches.Mux".equals(def.getId()) ? maxIdx + 1 : maxIdx;
                if (needed > 2) {
                    inst.setVariableInputCount(needed);
                }
            }
            if (node.customInputs() != null && !node.customInputs().isEmpty()) {
                inst.getInputs().clear();
                for (PinSpec ps : node.customInputs()) {
                    DataType dt = DataType.valueOf(ps.dataType());
                    inst.getInputs().add(new PinInstance(ps.name(), ps.name(), dt, true, dt.getDefaultConstant()));
                }
            }
            if (node.customOutputs() != null && !node.customOutputs().isEmpty()) {
                inst.getOutputs().clear();
                for (PinSpec ps : node.customOutputs()) {
                    DataType dt = DataType.valueOf(ps.dataType());
                    inst.getOutputs().add(new PinInstance(ps.name(), ps.name(), dt, false, dt.getDefaultConstant()));
                }
            }
            if (node.customProperties() != null && !node.customProperties().isEmpty()) {
                inst.getCustomProperties().putAll(node.customProperties());
            }

            for (Map.Entry<String, String> e : node.pinConstants().entrySet()) {
                int pinIdx = findPinIndex(inst.getInputs(), e.getKey(), node.alias());
                inst.getInputs().get(pinIdx).setConstantValue(e.getValue());
            }
            if (node.negatedPins() != null) {
                for (String negPinName : node.negatedPins()) {
                    PinInstance pin = findOutputOrInputPin(inst, negPinName, node.alias());
                    pin.setNegated(true);
                }
            }

            page.getBlocks().add(inst);
            byAlias.put(node.alias(), inst);
        }

        int netCounter = 1;
        for (WireEdge edge : spec.wires()) {
            Wire w = buildWire(edge.from(), edge.to(), byAlias, "N" + (netCounter++));
            if (edge.viaPoints() != null && !edge.viaPoints().isEmpty()) {
                w.setManualRoute(edge.viaPoints());
            }
            page.getWires().add(w);
        }

        for (BusEdge bus : spec.busses()) {
            for (PinRef target : bus.targets()) {
                Wire w = buildWire(bus.from(), target, byAlias, "B" + (netCounter++));
                w.setBusId(w.getSourceBlockId() + "->" + w.getTargetBlockId());
                page.getWires().add(w);
            }
        }

        boolean showLive = !spec.liveValues().isEmpty();
        for (LiveValue lv : spec.liveValues()) {
            BlockInstance inst = requireAlias(byAlias, lv.pin().alias());
            PinInstance pin = findOutputOrInputPin(inst, lv.pin().pinName(), lv.pin().alias());
            pin.setRuntimeValue(lv.value());
        }

        for (GraphicNode gn : spec.graphics()) {
            GraphicElement.Kind kind = switch (gn.kind()) {
                case "circle" -> GraphicElement.Kind.CIRCLE;
                case "rect" -> GraphicElement.Kind.RECTANGLE;
                default -> GraphicElement.Kind.TEXT;
            };
            GraphicElement elem = new GraphicElement(kind, gn.gridX(), gn.gridY(), gn.gridW(), gn.gridH());
            if (gn.text() != null) {
                elem.setText(gn.text());
            }
            if (gn.fillHex() != null) {
                elem.setFilled(true);
                elem.setFillColor(parseColor(gn.fillHex()));
            }
            if (gn.strokeHex() != null) {
                elem.setStrokeColor(parseColor(gn.strokeHex()));
            }
            if (gn.bindPin() != null) {
                BlockInstance boundInst = requireAlias(byAlias, gn.bindPin().alias());
                PinInstance boundPin = findOutputOrInputPin(boundInst, gn.bindPin().pinName(), gn.bindPin().alias());
                elem.setIndicatorEnabled(true);
                elem.setIndicatorPin(boundInst.getName() + "." + boundPin.getShortName());
                if (gn.activeHex() != null) {
                    elem.setIndicatorActiveColor(parseColor(gn.activeHex()));
                }
                if (gn.inactiveHex() != null) {
                    elem.setIndicatorInactiveColor(parseColor(gn.inactiveHex()));
                }
            }
            page.getGraphics().add(elem);
        }

        Rectangle bounds;
        if (spec.widthGrid() != null && spec.heightGrid() != null) {
            bounds = new Rectangle(0, 0, Math.max(60, spec.widthGrid() * gridPx), Math.max(40, spec.heightGrid() * gridPx));
        } else {
            bounds = FbdRenderer.computeExportBounds(page, gridPx);
        }

        BufferedImage img = new BufferedImage(bounds.width, bounds.height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = img.createGraphics();
        g2.setColor(Color.WHITE);
        g2.fillRect(0, 0, bounds.width, bounds.height);
        g2.translate(-bounds.x, -bounds.y);
        FbdRenderer.paintPage(g2, page, gridPx, FbdRenderer.RenderOptions.forDiagram(showLive, true));
        g2.translate(bounds.x, bounds.y);

        // Crisp 1px utilitarian border around diagram
        g2.setColor(new Color(0x88, 0x88, 0x88));
        g2.drawRect(0, 0, bounds.width - 1, bounds.height - 1);
        g2.dispose();

        return img;
    }

    public static void clearCache() {
        IMAGE_CACHE.clear();
        SOURCE_BY_HASH.clear();
    }

    private static int maxReferencedXIndex(BlockNode node, FbdDiagramSpec spec) {
        int max = 1;
        for (String key : node.pinConstants().keySet()) {
            max = Math.max(max, parseXIndex(key));
        }
        for (WireEdge w : spec.wires()) {
            if (w.to().alias().equals(node.alias())) {
                max = Math.max(max, parseXIndex(w.to().pinName()));
            }
        }
        for (BusEdge b : spec.busses()) {
            for (PinRef t : b.targets()) {
                if (t.alias().equals(node.alias())) {
                    max = Math.max(max, parseXIndex(t.pinName()));
                }
            }
        }
        return max;
    }

    private static int parseXIndex(String pinName) {
        if (pinName != null && pinName.length() >= 2 && (pinName.charAt(0) == 'X' || pinName.charAt(0) == 'x')) {
            try {
                return Integer.parseInt(pinName.substring(1));
            } catch (NumberFormatException ignored) {
            }
        }
        return -1;
    }

    private static Color parseColor(String hex) {
        String clean = hex.startsWith("#") ? hex.substring(1) : hex;
        return new Color(Integer.parseInt(clean, 16));
    }

    private static BlockDefinition createSyntheticMacroDef(BlockNode node, FbdDiagramSpec spec) {
        String id = node.catalogId();
        int lastDot = id.lastIndexOf('.');
        String typeName = (lastDot >= 0 && lastDot < id.length() - 1) ? id.substring(lastDot + 1) : id;

        List<BlockDefinition.PinSpec> inSpecs = new ArrayList<>();
        if (!node.customInputs().isEmpty()) {
            for (PinSpec ps : node.customInputs()) {
                DataType dt = DataType.valueOf(ps.dataType());
                inSpecs.add(new BlockDefinition.PinSpec(ps.name(), ps.name(), dt, dt.getDefaultConstant()));
            }
        } else {
            java.util.Set<String> seen = new java.util.LinkedHashSet<>();
            if (id.startsWith("Macro.User.")) {
                inSpecs.add(new BlockDefinition.PinSpec("En", "Enable Execution", DataType.BOOL, "1"));
                seen.add("En");
            }
            for (String k : node.pinConstants().keySet()) {
                if (seen.add(k)) inSpecs.add(new BlockDefinition.PinSpec(k, k, DataType.INT, "0"));
            }
            for (WireEdge w : spec.wires()) {
                if (w.to().alias().equals(node.alias()) && seen.add(w.to().pinName())) {
                    inSpecs.add(new BlockDefinition.PinSpec(w.to().pinName(), w.to().pinName(), DataType.INT, "0"));
                }
            }
        }

        List<BlockDefinition.PinSpec> outSpecs = new ArrayList<>();
        if (!node.customOutputs().isEmpty()) {
            for (PinSpec ps : node.customOutputs()) {
                DataType dt = DataType.valueOf(ps.dataType());
                outSpecs.add(new BlockDefinition.PinSpec(ps.name(), ps.name(), dt, dt.getDefaultConstant()));
            }
        } else {
            java.util.Set<String> seen = new java.util.LinkedHashSet<>();
            for (WireEdge w : spec.wires()) {
                if (w.from().alias().equals(node.alias()) && seen.add(w.from().pinName())) {
                    outSpecs.add(new BlockDefinition.PinSpec(w.from().pinName(), w.from().pinName(), DataType.INT, "0"));
                }
            }
        }

        int minH = Math.max(2, Math.max(inSpecs.size(), outSpecs.size()));
        int width = id.startsWith("Macro.User.") ? 4 : 3;
        return new BlockDefinition(id, "Macros", typeName, typeName, inSpecs, outSpecs, width, minH, false, false, 0.05);
    }

    private static Wire buildWire(PinRef from, PinRef to, Map<String, BlockInstance> byAlias, String netName) {
        BlockInstance src = requireAlias(byAlias, from.alias());
        BlockInstance dst = requireAlias(byAlias, to.alias());
        int srcIdx = findPinIndex(src.getOutputs(), from.pinName(), from.alias());
        int dstIdx = findPinIndex(dst.getInputs(), to.pinName(), to.alias());
        return new Wire(
                src.getId(), srcIdx,
                dst.getId(), dstIdx,
                netName,
                src.getOutputPinAnchorGrid(srcIdx),
                dst.getInputPinAnchorGrid(dstIdx)
        );
    }

    private static BlockInstance requireAlias(Map<String, BlockInstance> byAlias, String alias) {
        BlockInstance inst = byAlias.get(alias);
        if (inst == null) {
            throw new IllegalArgumentException("Unknown block alias in diagram: " + alias);
        }
        return inst;
    }

    private static int findPinIndex(List<PinInstance> pins, String pinName, String alias) {
        for (int i = 0; i < pins.size(); i++) {
            PinInstance p = pins.get(i);
            if (p.getShortName().equalsIgnoreCase(pinName) || p.getFullName().equalsIgnoreCase(pinName)) {
                return i;
            }
        }
        throw new IllegalArgumentException("Unknown pin '" + pinName + "' on block '" + alias + "'");
    }

    private static PinInstance findOutputOrInputPin(BlockInstance inst, String pinName, String alias) {
        for (PinInstance p : inst.getOutputs()) {
            if (p.getShortName().equalsIgnoreCase(pinName) || p.getFullName().equalsIgnoreCase(pinName)) {
                return p;
            }
        }
        for (PinInstance p : inst.getInputs()) {
            if (p.getShortName().equalsIgnoreCase(pinName) || p.getFullName().equalsIgnoreCase(pinName)) {
                return p;
            }
        }
        throw new IllegalArgumentException("Unknown pin '" + pinName + "' on block '" + alias + "'");
    }

    public static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(input.hashCode());
        }
    }
}
