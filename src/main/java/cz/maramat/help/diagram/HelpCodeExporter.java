package cz.maramat.help.diagram;

import cz.maramat.model.BlockCatalog;
import cz.maramat.model.BlockDefinition;
import cz.maramat.model.BlockInstance;
import cz.maramat.model.GraphicElement;
import cz.maramat.model.LadderBranch;
import cz.maramat.model.LadderElement;
import cz.maramat.model.LadderRung;
import cz.maramat.model.Page;
import cz.maramat.model.PinInstance;
import cz.maramat.model.Wire;

import java.awt.Color;
import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Exports selected FBD blocks/graphics/wires, LD rungs, or ST code into fenced
 * Help Markdown blocks ({@code ```fbd}, {@code ```ld}, {@code ```st}) with preserved
 * wire routing and simulation runtime values.
 */
public final class HelpCodeExporter {

    private HelpCodeExporter() {}

    public static String exportFbd(
            Page page,
            Set<String> selectedBlockIds,
            Set<String> selectedGraphicIds,
            boolean includeRuntimeValues
    ) {
        if (page == null) return "";

        boolean useAll = (selectedBlockIds == null || selectedBlockIds.isEmpty())
                && (selectedGraphicIds == null || selectedGraphicIds.isEmpty());

        List<BlockInstance> blocks = new ArrayList<>();
        for (BlockInstance b : page.getBlocks()) {
            if (useAll || (selectedBlockIds != null && selectedBlockIds.contains(b.getId()))) {
                blocks.add(b);
            }
        }

        List<GraphicElement> graphics = new ArrayList<>();
        for (GraphicElement g : page.getGraphics()) {
            if (useAll || (selectedGraphicIds != null && selectedGraphicIds.contains(g.getId()))) {
                graphics.add(g);
            }
        }

        if (blocks.isEmpty() && graphics.isEmpty()) {
            return "";
        }

        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        for (BlockInstance b : blocks) {
            minX = Math.min(minX, b.getGridX());
            minY = Math.min(minY, b.getGridY());
        }
        for (GraphicElement g : graphics) {
            minX = Math.min(minX, g.getGridX());
            minY = Math.min(minY, g.getGridY());
        }
        int offsetX = 2 - minX;
        int offsetY = 1 - minY;

        Map<String, String> aliasByBlockId = new LinkedHashMap<>();
        Map<String, String> aliasByBlockName = new LinkedHashMap<>();
        Set<String> usedAliases = new LinkedHashSet<>();

        StringBuilder sb = new StringBuilder();
        sb.append("```fbd\n");

        for (int i = 0; i < blocks.size(); i++) {
            BlockInstance b = blocks.get(i);
            String alias = uniqueAlias(b.getName(), i + 1, usedAliases);
            aliasByBlockId.put(b.getId(), alias);
            aliasByBlockName.put(b.getName(), alias);

            int gx = b.getGridX() + offsetX;
            int gy = b.getGridY() + offsetY;
            sb.append("block ").append(alias).append(" ").append(b.getDefinitionId())
              .append(" at ").append(gx).append(",").append(gy);

            if (b.getName() != null && !b.getName().isBlank()) {
                sb.append(" name=").append(formatAttrValue(b.getName()));
            }

            BlockDefinition def = BlockCatalog.getById(b.getDefinitionId());
            if (def != null && def.isConfigurableDataType()) {
                String dt = b.getCustomProperties().get("DataType");
                if (dt != null && !dt.isBlank() && !"INT".equals(dt)) {
                    sb.append(" type=").append(dt);
                }
            }
            boolean alwaysExportPins = (def == null || b.getDefinitionId().startsWith("Macro.User."));
            if (def != null && def.isVariableInputCount()) {
                int dataInputs = (int) b.getInputs().stream()
                        .filter(p -> !"En".equals(p.getShortName()) && !"Adr".equals(p.getShortName()))
                        .count();
                if (dataInputs > 2) {
                    sb.append(" inputs=").append(dataInputs);
                }
            } else if (alwaysExportPins || pinsDifferFromDefault(b.getInputs(), def.getDefaultInputs())) {
                if (!b.getInputs().isEmpty()) {
                    sb.append(" in=").append(formatPinList(b.getInputs()));
                }
            }
            if (alwaysExportPins || pinsDifferFromDefault(b.getOutputs(), def.getDefaultOutputs())) {
                if (!b.getOutputs().isEmpty()) {
                    sb.append(" out=").append(formatPinList(b.getOutputs()));
                }
            }
            String ch = b.getCustomProperties().get("Channel");
            if (ch != null && !ch.isBlank()) {
                sb.append(" channel=").append(formatAttrValue(ch));
            }

            List<String> negPins = new ArrayList<>();
            for (PinInstance p : b.getInputs()) {
                if (p.isNegated()) negPins.add(p.getShortName());
            }
            for (PinInstance p : b.getOutputs()) {
                if (p.isNegated()) negPins.add(p.getShortName());
            }
            if (!negPins.isEmpty()) {
                sb.append(" neg=").append(String.join(",", negPins));
            }

            List<String> nonDefaults = new ArrayList<>();
            List<PinInstance> inputs = b.getInputs();
            for (int pIdx = 0; pIdx < inputs.size(); pIdx++) {
                PinInstance p = inputs.get(pIdx);
                String defConst = (def != null && pIdx < def.getDefaultInputs().size())
                        ? def.getDefaultInputs().get(pIdx).defaultConstant()
                        : p.getDataType().getDefaultConstant();
                String c = p.getConstantValue();
                if (c != null && !c.isBlank() && !c.equals(defConst)) {
                    nonDefaults.add(p.getShortName() + "=" + c.trim());
                }
            }
            if (!nonDefaults.isEmpty()) {
                sb.append(" { ").append(String.join(" ", nonDefaults)).append(" }");
            }
            sb.append("\n");
        }

        for (Wire w : page.getWires()) {
            String fromAlias = aliasByBlockId.get(w.getSourceBlockId());
            String toAlias = aliasByBlockId.get(w.getTargetBlockId());
            if (fromAlias == null || toAlias == null) continue;

            BlockInstance src = findBlock(blocks, w.getSourceBlockId());
            BlockInstance dst = findBlock(blocks, w.getTargetBlockId());
            if (src == null || dst == null) continue;
            if (w.getSourcePinIndex() >= src.getOutputs().size() || w.getTargetPinIndex() >= dst.getInputs().size()) {
                continue;
            }
            String fromPin = src.getOutputs().get(w.getSourcePinIndex()).getShortName();
            String toPin = dst.getInputs().get(w.getTargetPinIndex()).getShortName();

            sb.append("wire ").append(fromAlias).append(".").append(fromPin)
              .append(" -> ").append(toAlias).append(".").append(toPin);

            List<Point2D.Double> pts = w.getGridPoints();
            if (pts.size() >= 2 && (w.isUserEditedRoute() || pts.size() > 2)) {
                sb.append(" via");
                for (Point2D.Double p : pts) {
                    sb.append(" ").append(formatCoord(p.x + offsetX)).append(",").append(formatCoord(p.y + offsetY));
                }
            }
            sb.append("\n");
        }

        for (GraphicElement g : graphics) {
            String kindStr = switch (g.getKind()) {
                case CIRCLE -> "circle";
                case RECTANGLE, ROUNDED_RECTANGLE -> "rect";
                case TEXT -> "text";
                default -> null;
            };
            if (kindStr == null) continue;

            int gx = g.getGridX() + offsetX;
            int gy = g.getGridY() + offsetY;
            if ("text".equals(kindStr)) {
                sb.append("text \"").append(g.getText().replace("\"", "\\\"")).append("\" at ")
                  .append(gx).append(",").append(gy).append("\n");
                continue;
            }

            sb.append(kindStr).append(" at ").append(gx).append(",").append(gy)
              .append(" size ").append(g.getGridW()).append("x").append(g.getGridH());
            if (g.isFilled()) {
                sb.append(" fill=").append(toHex(g.getFillColor()));
            }
            sb.append(" stroke=").append(toHex(g.getStrokeColor()));
            if (g.isIndicatorEnabled() && g.getIndicatorPin() != null && !g.getIndicatorPin().isBlank()) {
                String[] parts = g.getIndicatorPin().split("\\.", 2);
                if (parts.length == 2) {
                    String boundAlias = aliasByBlockName.get(parts[0]);
                    if (boundAlias != null) {
                        sb.append(" bind=").append(boundAlias).append(".").append(parts[1])
                          .append(" active=").append(toHex(g.getIndicatorActiveColor()))
                          .append(" inactive=").append(toHex(g.getIndicatorInactiveColor()));
                    }
                }
            }
            sb.append("\n");
        }

        if (includeRuntimeValues) {
            List<String> liveTokens = new ArrayList<>();
            for (BlockInstance b : blocks) {
                String alias = aliasByBlockId.get(b.getId());
                for (PinInstance p : b.getOutputs()) {
                    liveTokens.add(alias + "." + p.getShortName() + "=" + formatNumber(p.getRuntimeValue()));
                }
            }
            if (!liveTokens.isEmpty()) {
                sb.append("live ").append(String.join(" ", liveTokens)).append("\n");
            }
        }

        sb.append("```");
        return sb.toString();
    }

    public static String exportLd(Page page, boolean includeRuntimeValues) {
        if (page == null || page.getRungs().isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("```ld\n");
        Set<String> activeOps = new LinkedHashSet<>();

        for (LadderRung rung : page.getRungs()) {
            if (rung.getComment() != null && !rung.getComment().isBlank()) {
                sb.append("rung \"").append(rung.getComment().replace("\"", "\\\"")).append("\"\n");
            } else {
                sb.append("rung\n");
            }

            List<String> chain = new ArrayList<>();
            for (LadderElement el : rung.getMainElements()) {
                chain.add(formatLdElement(el));
                if (includeRuntimeValues && el.isEnergizedOut() && el.getOperand() != null && !el.getOperand().isBlank()) {
                    activeOps.add(el.getOperand() + "=1");
                }
            }
            for (LadderElement coil : rung.getCoils()) {
                chain.add(formatLdElement(coil));
                if (includeRuntimeValues && coil.isEnergizedOut() && coil.getOperand() != null && !coil.getOperand().isBlank()) {
                    activeOps.add(coil.getOperand() + "=1");
                }
            }
            if (!chain.isEmpty()) {
                sb.append(String.join(" -> ", chain)).append("\n");
            }

            for (LadderBranch br : rung.getParallelBranches()) {
                List<String> brEls = new ArrayList<>();
                for (LadderElement el : br.getElements()) {
                    brEls.add(formatLdElement(el));
                    if (includeRuntimeValues && el.isEnergizedOut() && el.getOperand() != null && !el.getOperand().isBlank()) {
                        activeOps.add(el.getOperand() + "=1");
                    }
                }
                sb.append("branch ").append(br.getStartColumn()).append("..").append(br.getEndColumn())
                  .append(" : ").append(String.join(" -> ", brEls)).append("\n");
            }
        }

        if (includeRuntimeValues && !activeOps.isEmpty()) {
            sb.append("live ").append(String.join(" ", activeOps)).append("\n");
        }
        sb.append("```");
        return sb.toString();
    }

    public static String exportSt(String code) {
        if (code == null) return "```st\n```";
        return "```st\n" + code.stripTrailing() + "\n```";
    }

    private static String formatLdElement(LadderElement el) {
        String prefix = switch (el.getKind()) {
            case NO_CONTACT -> "NO";
            case NC_CONTACT -> "NC";
            case POS_EDGE_CONTACT -> "POS";
            case NEG_EDGE_CONTACT -> "NEG";
            case NORMAL_COIL -> "COIL";
            case NEGATED_COIL -> "NCOIL";
            case SET_COIL -> "SET";
            case RESET_COIL -> "RESET";
            case POS_EDGE_COIL -> "PCOIL";
            case NEG_EDGE_COIL -> "FCOIL";
            case ANALOG_COIL -> "AO";
            case FUNCTION_BLOCK -> "FB";
        };
        String op = el.getOperand() == null ? "" : el.getOperand().trim();
        return prefix + "(" + op + ")";
    }

    private static boolean pinsDifferFromDefault(List<PinInstance> actual, List<BlockDefinition.PinSpec> defaults) {
        if (actual.size() != defaults.size()) return true;
        for (int i = 0; i < actual.size(); i++) {
            PinInstance a = actual.get(i);
            BlockDefinition.PinSpec d = defaults.get(i);
            if (!a.getShortName().equals(d.shortName()) || a.getDataType() != d.dataType()) {
                return true;
            }
        }
        return false;
    }

    private static String formatPinList(List<PinInstance> pins) {
        List<String> parts = new ArrayList<>(pins.size());
        for (PinInstance p : pins) {
            parts.add(p.getShortName() + ":" + p.getDataType().name());
        }
        return String.join(",", parts);
    }

    private static BlockInstance findBlock(List<BlockInstance> blocks, String id) {
        for (BlockInstance b : blocks) {
            if (b.getId().equals(id)) return b;
        }
        return null;
    }

    private static String uniqueAlias(String rawName, int fallbackIdx, Set<String> used) {
        String base = rawName == null ? "" : rawName.replaceAll("[^A-Za-z0-9_]", "").toLowerCase(Locale.ROOT);
        if (base.isEmpty() || Character.isDigit(base.charAt(0))) {
            base = "b" + fallbackIdx;
        }
        String candidate = base;
        int n = 2;
        while (!used.add(candidate)) {
            candidate = base + n++;
        }
        return candidate;
    }

    private static String formatAttrValue(String s) {
        if (s.matches("[A-Za-z0-9_.-]+")) return s;
        return "\"" + s.replace("\"", "\\\"") + "\"";
    }

    private static String formatCoord(double v) {
        if (Math.abs(v - Math.rint(v)) < 1e-6) {
            return Long.toString(Math.round(v));
        }
        return String.format(Locale.ROOT, "%.2f", v);
    }

    private static String formatNumber(double v) {
        if (Math.abs(v - Math.rint(v)) < 1e-6) {
            return Long.toString(Math.round(v));
        }
        return Double.toString(v);
    }

    private static String toHex(Color c) {
        return String.format("#%02X%02X%02X", c.getRed(), c.getGreen(), c.getBlue());
    }
}
