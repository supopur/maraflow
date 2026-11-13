package cz.maramat.help.diagram;

import cz.maramat.help.diagram.FbdDiagramSpec.BlockNode;
import cz.maramat.help.diagram.FbdDiagramSpec.BusEdge;
import cz.maramat.help.diagram.FbdDiagramSpec.GraphicNode;
import cz.maramat.help.diagram.FbdDiagramSpec.LiveValue;
import cz.maramat.help.diagram.FbdDiagramSpec.PinRef;
import cz.maramat.help.diagram.FbdDiagramSpec.PinSpec;
import cz.maramat.help.diagram.FbdDiagramSpec.WireEdge;

import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses fenced {@code ```fbd} diagram blocks used in Help markdown pages.
 */
public final class FbdDiagramParser {

    private static final Pattern SIZE_PATTERN = Pattern.compile("^size\\s+(\\d+)x(\\d+)$");
    private static final Pattern BLOCK_PATTERN = Pattern.compile(
            "^block\\s+(\\S+)\\s+(\\S+)\\s+at\\s+(\\d+),(\\d+)(?:\\s+(.*))?$"
    );
    private static final Pattern NAME_ATTR = Pattern.compile("name=(\"([^\"]*)\"|(\\S+))");
    private static final Pattern CONST_BLOCK = Pattern.compile("\\{([^}]*)\\}");
    private static final Pattern CAPTION_PATTERN = Pattern.compile("^caption\\s+\"(.*)\"$");

    private FbdDiagramParser() {}

    public static FbdDiagramSpec parse(String source) {
        if (source == null) {
            throw new IllegalArgumentException("Diagram source cannot be null");
        }
        Integer widthGrid = null;
        Integer heightGrid = null;
        List<BlockNode> blocks = new ArrayList<>();
        List<WireEdge> wires = new ArrayList<>();
        List<BusEdge> busses = new ArrayList<>();
        List<LiveValue> liveValues = new ArrayList<>();
        List<GraphicNode> graphics = new ArrayList<>();
        String caption = null;

        String[] lines = source.split("\\R");
        for (int i = 0; i < lines.length; i++) {
            int lineNo = i + 1;
            String line = stripComment(lines[i]).trim();
            if (line.isEmpty()) continue;

            if (line.startsWith("size ")) {
                Matcher m = SIZE_PATTERN.matcher(line);
                if (!m.matches()) {
                    throw new IllegalArgumentException("Line " + lineNo + ": invalid size directive: " + line);
                }
                widthGrid = Integer.parseInt(m.group(1));
                heightGrid = Integer.parseInt(m.group(2));
            } else if (line.startsWith("block ")) {
                blocks.add(parseBlock(line, lineNo));
            } else if (line.startsWith("wire ")) {
                wires.add(parseWire(line.substring(5).trim(), lineNo));
            } else if (line.startsWith("bus ")) {
                busses.add(parseBus(line.substring(4).trim(), lineNo));
            } else if (line.startsWith("live ")) {
                parseLive(line.substring(5).trim(), lineNo, liveValues);
            } else if (line.startsWith("circle ") || line.startsWith("rect ") || line.startsWith("text ")) {
                graphics.add(parseGraphic(line, lineNo));
            } else if (line.startsWith("caption ")) {
                Matcher m = CAPTION_PATTERN.matcher(line);
                if (!m.matches()) {
                    throw new IllegalArgumentException("Line " + lineNo + ": invalid caption directive: " + line);
                }
                caption = m.group(1);
            } else {
                throw new IllegalArgumentException("Line " + lineNo + ": unknown directive: " + line);
            }
        }

        return new FbdDiagramSpec(widthGrid, heightGrid, blocks, wires, busses, liveValues, graphics, caption);
    }

    private static String stripComment(String line) {
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"' && (i == 0 || line.charAt(i - 1) != '\\')) {
                inQuotes = !inQuotes;
            } else if (c == '#' && !inQuotes && (i == 0 || Character.isWhitespace(line.charAt(i - 1)))) {
                return line.substring(0, i);
            }
        }
        return line;
    }

    private static BlockNode parseBlock(String line, int lineNo) {
        Matcher m = BLOCK_PATTERN.matcher(line);
        if (!m.matches()) {
            throw new IllegalArgumentException("Line " + lineNo + ": invalid block directive: " + line);
        }
        String alias = m.group(1);
        String catalogId = m.group(2);
        int gx = Integer.parseInt(m.group(3));
        int gy = Integer.parseInt(m.group(4));
        String rest = m.group(5) == null ? "" : m.group(5).trim();

        String displayName = null;
        Map<String, String> constants = new LinkedHashMap<>();
        List<PinSpec> customInputs = new ArrayList<>();
        List<PinSpec> customOutputs = new ArrayList<>();
        Integer variableInputCount = null;
        String primaryDataType = null;
        Map<String, String> customProperties = new LinkedHashMap<>();

        if (!rest.isEmpty()) {
            Matcher cm = CONST_BLOCK.matcher(rest);
            if (cm.find()) {
                String inside = cm.group(1).trim();
                if (!inside.isEmpty()) {
                    for (String token : inside.split("\\s+")) {
                        int eq = token.indexOf('=');
                        if (eq <= 0 || eq == token.length() - 1) {
                            throw new IllegalArgumentException("Line " + lineNo + ": invalid pin constant: " + token);
                        }
                        constants.put(token.substring(0, eq).trim(), token.substring(eq + 1).trim());
                    }
                }
                rest = (rest.substring(0, cm.start()) + " " + rest.substring(cm.end())).trim();
            }
            if (!rest.isEmpty()) {
                for (String tok : splitAttrs(rest)) {
                    int eq = tok.indexOf('=');
                    if (eq <= 0 || eq == tok.length() - 1) {
                        throw new IllegalArgumentException("Line " + lineNo + ": unexpected block attribute: " + tok);
                    }
                    String k = tok.substring(0, eq).trim();
                    String v = stripQuotes(tok.substring(eq + 1).trim());
                    switch (k) {
                        case "name" -> displayName = v;
                        case "in" -> parsePinSpecs(v, customInputs);
                        case "out" -> parsePinSpecs(v, customOutputs);
                        case "inputs" -> variableInputCount = Integer.parseInt(v);
                        case "type" -> primaryDataType = v.toUpperCase(java.util.Locale.ROOT);
                        case "channel" -> customProperties.put("Channel", v);
                        default -> throw new IllegalArgumentException("Line " + lineNo + ": unexpected block attribute: " + k);
                    }
                }
            }
        }

        return new BlockNode(alias, catalogId, gx, gy, displayName, constants,
                customInputs, customOutputs, variableInputCount, primaryDataType, customProperties);
    }

    private static List<String> splitAttrs(String rest) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (c == '"' && (i == 0 || rest.charAt(i - 1) != '\\')) {
                inQuotes = !inQuotes;
                cur.append(c);
            } else if (Character.isWhitespace(c) && !inQuotes) {
                if (!cur.isEmpty()) {
                    out.add(cur.toString());
                    cur.setLength(0);
                }
            } else {
                cur.append(c);
            }
        }
        if (!cur.isEmpty()) out.add(cur.toString());
        return out;
    }

    private static String stripQuotes(String s) {
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            return s.substring(1, s.length() - 1).replace("\\\"", "\"");
        }
        return s;
    }

    private static void parsePinSpecs(String raw, List<PinSpec> out) {
        for (String item : raw.split(",")) {
            String trimmed = item.trim();
            if (trimmed.isEmpty()) continue;
            int colon = trimmed.indexOf(':');
            if (colon > 0 && colon < trimmed.length() - 1) {
                out.add(new PinSpec(trimmed.substring(0, colon).trim(), trimmed.substring(colon + 1).trim().toUpperCase(java.util.Locale.ROOT)));
            } else {
                out.add(new PinSpec(trimmed, "INT"));
            }
        }
    }

    private static WireEdge parseWire(String expr, int lineNo) {
        String routePart = null;
        int viaIdx = expr.indexOf(" via ");
        if (viaIdx >= 0) {
            routePart = expr.substring(viaIdx + 5).trim();
            expr = expr.substring(0, viaIdx).trim();
        }
        String[] parts = expr.split("->", 2);
        if (parts.length != 2) {
            throw new IllegalArgumentException("Line " + lineNo + ": expected 'from -> to' in wire: " + expr);
        }
        List<Point2D.Double> via = new ArrayList<>();
        if (routePart != null && !routePart.isEmpty()) {
            for (String pt : routePart.split("\\s+")) {
                String[] xy = pt.split(",");
                if (xy.length != 2) {
                    throw new IllegalArgumentException("Line " + lineNo + ": invalid via point: " + pt);
                }
                via.add(new Point2D.Double(Double.parseDouble(xy[0]), Double.parseDouble(xy[1])));
            }
        }
        return new WireEdge(parsePinRef(parts[0].trim(), lineNo), parsePinRef(parts[1].trim(), lineNo), via);
    }

    private static BusEdge parseBus(String expr, int lineNo) {
        String[] parts = expr.split("->", 2);
        if (parts.length != 2) {
            throw new IllegalArgumentException("Line " + lineNo + ": expected 'from -> to1, to2' in bus: " + expr);
        }
        PinRef from = parsePinRef(parts[0].trim(), lineNo);
        String[] rawTargets = parts[1].split(",");
        List<PinRef> targets = new ArrayList<>();
        for (String t : rawTargets) {
            String trimmed = t.trim();
            if (!trimmed.isEmpty()) {
                targets.add(parsePinRef(trimmed, lineNo));
            }
        }
        if (targets.isEmpty()) {
            throw new IllegalArgumentException("Line " + lineNo + ": bus has no target pins");
        }
        return new BusEdge(from, targets);
    }

    private static void parseLive(String expr, int lineNo, List<LiveValue> out) {
        if (expr.isEmpty()) return;
        for (String token : expr.split("\\s+")) {
            int eq = token.indexOf('=');
            if (eq <= 0 || eq == token.length() - 1) {
                throw new IllegalArgumentException("Line " + lineNo + ": invalid live value: " + token);
            }
            PinRef ref = parsePinRef(token.substring(0, eq).trim(), lineNo);
            double val = Double.parseDouble(token.substring(eq + 1).trim());
            out.add(new LiveValue(ref, val));
        }
    }

    private static PinRef parsePinRef(String token, int lineNo) {
        int dot = token.indexOf('.');
        if (dot <= 0 || dot == token.length() - 1) {
            throw new IllegalArgumentException("Line " + lineNo + ": expected alias.pin, got: " + token);
        }
        return new PinRef(token.substring(0, dot).trim(), token.substring(dot + 1).trim());
    }

    private static final Pattern SHAPE_PATTERN = Pattern.compile(
            "^(circle|rect)\\s+at\\s+(\\d+),(\\d+)\\s+size\\s+(\\d+)x(\\d+)(?:\\s+(.*))?$"
    );
    private static final Pattern TEXT_PATTERN = Pattern.compile(
            "^text\\s+\"([^\"]*)\"\\s+at\\s+(\\d+),(\\d+)(?:\\s+(.*))?$"
    );

    private static GraphicNode parseGraphic(String line, int lineNo) {
        if (line.startsWith("text ")) {
            Matcher m = TEXT_PATTERN.matcher(line);
            if (!m.matches()) {
                throw new IllegalArgumentException("Line " + lineNo + ": invalid text directive: " + line);
            }
            return new GraphicNode(
                    "text",
                    Integer.parseInt(m.group(2)),
                    Integer.parseInt(m.group(3)),
                    4, 1,
                    m.group(1),
                    null, null, null, null, null
            );
        }
        Matcher m = SHAPE_PATTERN.matcher(line);
        if (!m.matches()) {
            throw new IllegalArgumentException("Line " + lineNo + ": invalid graphic directive: " + line);
        }
        String kind = m.group(1);
        int gx = Integer.parseInt(m.group(2));
        int gy = Integer.parseInt(m.group(3));
        int gw = Integer.parseInt(m.group(4));
        int gh = Integer.parseInt(m.group(5));
        String rest = m.group(6) == null ? "" : m.group(6).trim();
        String fill = null;
        String stroke = null;
        PinRef bindPin = null;
        String active = null;
        String inactive = null;
        if (!rest.isEmpty()) {
            for (String tok : rest.split("\\s+")) {
                int eq = tok.indexOf('=');
                if (eq <= 0 || eq == tok.length() - 1) {
                    throw new IllegalArgumentException("Line " + lineNo + ": invalid graphic attribute: " + tok);
                }
                String k = tok.substring(0, eq).trim();
                String v = tok.substring(eq + 1).trim();
                switch (k) {
                    case "fill" -> fill = v;
                    case "stroke" -> stroke = v;
                    case "bind" -> bindPin = parsePinRef(v, lineNo);
                    case "active" -> active = v;
                    case "inactive" -> inactive = v;
                    default -> throw new IllegalArgumentException("Line " + lineNo + ": unknown graphic attribute: " + k);
                }
            }
        }
        return new GraphicNode(kind, gx, gy, gw, gh, null, fill, stroke, bindPin, active, inactive);
    }
}
