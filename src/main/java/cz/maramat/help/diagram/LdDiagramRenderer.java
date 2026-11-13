package cz.maramat.help.diagram;

import cz.maramat.model.BlockCatalog;
import cz.maramat.model.BlockDefinition;
import cz.maramat.model.BlockInstance;
import cz.maramat.model.IecLanguage;
import cz.maramat.model.LadderBranch;
import cz.maramat.model.LadderElement;
import cz.maramat.model.LadderRung;
import cz.maramat.model.Page;
import cz.maramat.ui.render.LdRenderer;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.lang.ref.SoftReference;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses fenced {@code ```ld} Ladder Diagram blocks and renders them via {@link LdRenderer}.
 */
public final class LdDiagramRenderer {

    public record ParsedLdDiagram(Page page, boolean liveSim, String caption) {}

    private static final Pattern RUNG_PATTERN = Pattern.compile("^rung\\s+\"(.*)\"$");
    private static final Pattern BRANCH_PATTERN = Pattern.compile("^branch\\s+(\\d+)\\.\\.(\\d+)\\s*:\\s*(.+)$");
    private static final Pattern CAPTION_PATTERN = Pattern.compile("^caption\\s+\"(.*)\"$");

    private static final Map<String, SoftReference<BufferedImage>> IMAGE_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, String> SOURCE_BY_HASH = new ConcurrentHashMap<>();

    private LdDiagramRenderer() {}

    public static String registerSource(String source) {
        String hash = "ld_" + FbdDiagramRenderer.sha256Hex(source);
        SOURCE_BY_HASH.putIfAbsent(hash, source);
        return hash;
    }

    public static BufferedImage getOrRenderByHash(String hash) {
        String source = SOURCE_BY_HASH.get(hash);
        if (source == null) return null;
        return renderToImage(source);
    }

    public static ParsedLdDiagram parse(String source) {
        if (source == null) {
            throw new IllegalArgumentException("LD diagram source cannot be null");
        }
        Page page = new Page("HelpLdDiagram", IecLanguage.LD);
        LadderRung currentRung = null;
        Map<String, Boolean> liveStates = new HashMap<>();
        String caption = null;
        int order = 1;

        String[] lines = source.split("\\R");
        for (int i = 0; i < lines.length; i++) {
            int lineNo = i + 1;
            String line = stripComment(lines[i]).trim();
            if (line.isEmpty()) continue;

            if (line.startsWith("rung")) {
                String comment = "";
                if (!line.equals("rung")) {
                    Matcher m = RUNG_PATTERN.matcher(line);
                    if (!m.matches()) {
                        throw new IllegalArgumentException("Line " + lineNo + ": invalid rung directive: " + line);
                    }
                    comment = m.group(1);
                }
                currentRung = new LadderRung(2, order++, comment);
                page.getRungs().add(currentRung);
            } else if (line.startsWith("branch ")) {
                if (currentRung == null) {
                    throw new IllegalArgumentException("Line " + lineNo + ": branch without preceding rung");
                }
                Matcher m = BRANCH_PATTERN.matcher(line);
                if (!m.matches()) {
                    throw new IllegalArgumentException("Line " + lineNo + ": invalid branch syntax: " + line);
                }
                int sCol = Integer.parseInt(m.group(1));
                int eCol = Integer.parseInt(m.group(2));
                LadderBranch br = new LadderBranch(sCol, eCol);
                for (String part : m.group(3).split("->")) {
                    LadderElement el = parseElement(part.trim(), lineNo);
                    br.getElements().add(el);
                }
                currentRung.getParallelBranches().add(br);
            } else if (line.startsWith("live ")) {
                String expr = line.substring(5).trim();
                for (String tok : expr.split("\\s+")) {
                    int eq = tok.indexOf('=');
                    if (eq <= 0 || eq == tok.length() - 1) {
                        throw new IllegalArgumentException("Line " + lineNo + ": invalid live token: " + tok);
                    }
                    String op = tok.substring(0, eq).trim();
                    boolean val = !"0".equals(tok.substring(eq + 1).trim());
                    liveStates.put(op, val);
                }
            } else if (line.startsWith("caption ")) {
                Matcher m = CAPTION_PATTERN.matcher(line);
                if (!m.matches()) {
                    throw new IllegalArgumentException("Line " + lineNo + ": invalid caption: " + line);
                }
                caption = m.group(1);
            } else {
                if (currentRung == null) {
                    currentRung = new LadderRung(2, order++, "");
                    page.getRungs().add(currentRung);
                }
                for (String part : line.split("->")) {
                    LadderElement el = parseElement(part.trim(), lineNo);
                    if (el.getKind().isCoil()) {
                        currentRung.getCoils().add(el);
                    } else {
                        currentRung.getMainElements().add(el);
                    }
                }
            }
        }

        if (page.getRungs().isEmpty()) {
            throw new IllegalArgumentException("LD diagram has no rungs");
        }

        boolean liveSim = !liveStates.isEmpty();
        if (liveSim) {
            applyLiveStates(page, liveStates);
        }

        return new ParsedLdDiagram(page, liveSim, caption);
    }

    public static BufferedImage renderToImage(String source) {
        String key = "ld_" + FbdDiagramRenderer.sha256Hex(source);
        SoftReference<BufferedImage> ref = IMAGE_CACHE.get(key);
        BufferedImage cached = ref != null ? ref.get() : null;
        if (cached != null) return cached;

        ParsedLdDiagram parsed = parse(source);
        Rectangle bounds = LdRenderer.computeExportBounds(parsed.page());
        BufferedImage img = new BufferedImage(bounds.width, bounds.height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = img.createGraphics();
        g2.setColor(Color.WHITE);
        g2.fillRect(0, 0, bounds.width, bounds.height);
        g2.translate(-bounds.x, -bounds.y);
        LdRenderer.paintPageForExport(g2, parsed.page(), LdRenderer.RenderOptions.forDiagram(parsed.liveSim(), true));
        g2.translate(bounds.x, bounds.y);
        g2.setColor(new Color(0x88, 0x88, 0x88));
        g2.drawRect(0, 0, bounds.width - 1, bounds.height - 1);
        g2.dispose();

        IMAGE_CACHE.put(key, new SoftReference<>(img));
        return img;
    }

    public static void clearCache() {
        IMAGE_CACHE.clear();
        SOURCE_BY_HASH.clear();
    }

    private static LadderElement parseElement(String token, int lineNo) {
        if (token.isEmpty()) {
            throw new IllegalArgumentException("Line " + lineNo + ": empty LD element");
        }
        String[] parts = token.split("\\s+", 2);
        String kw = parts[0].toLowerCase();
        String arg = parts.length > 1 ? parts[1].trim() : "";
        if (arg.isEmpty()) {
            throw new IllegalArgumentException("Line " + lineNo + ": missing operand for '" + kw + "'");
        }
        return switch (kw) {
            case "no" -> new LadderElement(LadderElement.Kind.NO_CONTACT, arg);
            case "nc" -> new LadderElement(LadderElement.Kind.NC_CONTACT, arg);
            case "pos" -> new LadderElement(LadderElement.Kind.POS_EDGE_CONTACT, arg);
            case "neg" -> new LadderElement(LadderElement.Kind.NEG_EDGE_CONTACT, arg);
            case "coil" -> new LadderElement(LadderElement.Kind.NORMAL_COIL, arg);
            case "ncoil" -> new LadderElement(LadderElement.Kind.NEGATED_COIL, arg);
            case "set" -> new LadderElement(LadderElement.Kind.SET_COIL, arg);
            case "reset" -> new LadderElement(LadderElement.Kind.RESET_COIL, arg);
            case "pcoil" -> new LadderElement(LadderElement.Kind.POS_EDGE_COIL, arg);
            case "ncoil_f" -> new LadderElement(LadderElement.Kind.NEG_EDGE_COIL, arg);
            case "move" -> {
                String[] sd = arg.split(":", 2);
                if (sd.length != 2) {
                    throw new IllegalArgumentException("Line " + lineNo + ": move expects src:dst");
                }
                yield new LadderElement(LadderElement.Kind.ANALOG_COIL, sd[0].trim(), sd[1].trim());
            }
            case "fb" -> {
                String[] fbParts = arg.split("\\s+");
                BlockDefinition def = BlockCatalog.getById(fbParts[0]);
                if (def == null) {
                    throw new IllegalArgumentException("Line " + lineNo + ": unknown catalog block: " + fbParts[0]);
                }
                String name = def.getInstancePrefix() + "1";
                for (int i = 1; i < fbParts.length; i++) {
                    if (fbParts[i].startsWith("name=")) {
                        name = fbParts[i].substring(5);
                    }
                }
                yield new LadderElement(new BlockInstance(def, name, 0, 0, 2, 1));
            }
            default -> throw new IllegalArgumentException("Line " + lineNo + ": unknown LD element '" + kw + "'");
        };
    }

    private static void applyLiveStates(Page page, Map<String, Boolean> liveStates) {
        for (LadderRung rung : page.getRungs()) {
            boolean power = true;
            for (LadderElement el : rung.getMainElements()) {
                boolean opState = liveStates.getOrDefault(el.getOperand(), false);
                el.setOperandState(opState);
                el.setEnergizedIn(power);
                boolean conducts = switch (el.getKind()) {
                    case NC_CONTACT -> !opState;
                    case NO_CONTACT, POS_EDGE_CONTACT, NEG_EDGE_CONTACT -> opState;
                    default -> true;
                };
                power = power && conducts;
                el.setEnergizedOut(power);
            }
            for (LadderBranch br : rung.getParallelBranches()) {
                boolean brPower = true;
                br.setBranchEnergizedIn(true);
                for (LadderElement bel : br.getElements()) {
                    boolean opState = liveStates.getOrDefault(bel.getOperand(), false);
                    bel.setOperandState(opState);
                    bel.setEnergizedIn(brPower);
                    boolean conducts = bel.getKind() == LadderElement.Kind.NC_CONTACT ? !opState : opState;
                    brPower = brPower && conducts;
                    bel.setEnergizedOut(brPower);
                }
                br.setBranchEnergizedOut(brPower);
                power = power || brPower;
            }
            rung.setRungOutputEnergized(power);
            for (LadderElement coil : rung.getCoils()) {
                boolean opState = liveStates.getOrDefault(coil.getOperand(), power);
                coil.setOperandState(opState);
                coil.setEnergizedIn(power);
                coil.setEnergizedOut(power);
            }
        }
    }

    private static String stripComment(String line) {
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"' && (i == 0 || line.charAt(i - 1) != '\\')) {
                inQuotes = !inQuotes;
            } else if (c == '#' && !inQuotes) {
                return line.substring(0, i);
            }
        }
        return line;
    }
}
