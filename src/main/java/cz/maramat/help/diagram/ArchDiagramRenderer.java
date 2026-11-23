package cz.maramat.help.diagram;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.lang.ref.SoftReference;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ultra-lightweight Java2D renderer for fenced {@code ```diagram} blocks in Help markdown.
 * Supports {@code layout lr}, {@code layout td}, and {@code layout bus} topologies.
 */
public final class ArchDiagramRenderer {

    public record NodeSpec(String id, String label, String sub, Color fill, Color border, Color text) {}
    public record EdgeSpec(String from, String to, boolean bidir, String label) {}
    public record ParsedDiagram(String layout, List<NodeSpec> nodes, List<EdgeSpec> edges, String caption) {}

    private static final Pattern NODE_PATTERN = Pattern.compile(
            "^node\\s+([A-Za-z0-9_.-]+)\\s+\"([^\"]*)\"(?:\\s+(.*))?$"
    );
    private static final Pattern EDGE_PATTERN = Pattern.compile(
            "^edge\\s+([A-Za-z0-9_.-]+)\\s+(<->|->)\\s+([A-Za-z0-9_.-]+)(?:\\s+\"([^\"]*)\")?$"
    );
    private static final Pattern ATTR_PATTERN = Pattern.compile("(\\w+)\\s*=\\s*(?:\"([^\"]*)\"|(\\S+))");
    private static final Pattern CAPTION_PATTERN = Pattern.compile("^caption\\s+\"([^\"]*)\"$");

    private static final Map<String, SoftReference<BufferedImage>> IMAGE_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, String> SOURCE_BY_HASH = new ConcurrentHashMap<>();

    private ArchDiagramRenderer() {}

    public static String registerSource(String source) {
        String hash = "arch_" + FbdDiagramRenderer.sha256Hex(source);
        SOURCE_BY_HASH.putIfAbsent(hash, source);
        return hash;
    }

    public static BufferedImage getOrRenderByHash(String hash) {
        SoftReference<BufferedImage> ref = IMAGE_CACHE.get(hash);
        BufferedImage cached = ref != null ? ref.get() : null;
        if (cached != null) {
            return cached;
        }
        String source = SOURCE_BY_HASH.get(hash);
        if (source == null) return null;
        BufferedImage img = renderToImage(source);
        IMAGE_CACHE.put(hash, new SoftReference<>(img));
        return img;
    }

    public static ParsedDiagram parse(String source) {
        String layout = "lr";
        Map<String, NodeSpec> nodes = new LinkedHashMap<>();
        List<EdgeSpec> edges = new ArrayList<>();
        String caption = null;

        for (String raw : source.split("\\R")) {
            String line = raw.replaceFirst("//.*$", "").trim();
            if (line.isEmpty()) continue;

            if (line.startsWith("layout ")) {
                layout = line.substring(7).trim().toLowerCase(Locale.ROOT);
            } else if (line.startsWith("caption ")) {
                Matcher m = CAPTION_PATTERN.matcher(line);
                if (!m.matches()) throw new IllegalArgumentException("Invalid caption: " + line);
                caption = m.group(1);
            } else if (line.startsWith("node ")) {
                Matcher m = NODE_PATTERN.matcher(line);
                if (!m.matches()) throw new IllegalArgumentException("Invalid node: " + line);
                String id = m.group(1);
                String label = m.group(2);
                String attrs = m.group(3) == null ? "" : m.group(3);
                String sub = "";
                String colorName = "blue";
                Matcher am = ATTR_PATTERN.matcher(attrs);
                while (am.find()) {
                    String k = am.group(1).toLowerCase(Locale.ROOT);
                    String v = am.group(2) != null ? am.group(2) : am.group(3);
                    if ("sub".equals(k)) sub = v;
                    else if ("color".equals(k)) colorName = v.toLowerCase(Locale.ROOT);
                }
                Color[] pal = palette(colorName);
                nodes.put(id, new NodeSpec(id, label, sub, pal[0], pal[1], pal[2]));
            } else if (line.startsWith("edge ")) {
                Matcher m = EDGE_PATTERN.matcher(line);
                if (!m.matches()) throw new IllegalArgumentException("Invalid edge: " + line);
                edges.add(new EdgeSpec(m.group(1), m.group(3), "<->".equals(m.group(2)), m.group(4) == null ? "" : m.group(4)));
            } else {
                throw new IllegalArgumentException("Unknown diagram directive: " + line);
            }
        }
        return new ParsedDiagram(layout, new ArrayList<>(nodes.values()), edges, caption);
    }

    public static BufferedImage renderToImage(String source) {
        ParsedDiagram d = parse(source);
        Font titleFont = new Font("SansSerif", Font.BOLD, 11);
        Font subFont = new Font("SansSerif", Font.PLAIN, 10);
        Font edgeFont = new Font("SansSerif", Font.PLAIN, 10);

        BufferedImage tmp = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D tg = tmp.createGraphics();
        FontMetrics fmT = tg.getFontMetrics(titleFont);
        FontMetrics fmS = tg.getFontMetrics(subFont);
        FontMetrics fmE = tg.getFontMetrics(edgeFont);

        int nCount = Math.max(1, d.nodes().size());
        int[] nw = new int[d.nodes().size()];
        int[] nh = new int[d.nodes().size()];
        for (int i = 0; i < d.nodes().size(); i++) {
            NodeSpec n = d.nodes().get(i);
            int w = Math.max(fmT.stringWidth(n.label()), n.sub().isEmpty() ? 0 : fmS.stringWidth(n.sub())) + 24;
            nw[i] = Math.max(90, w);
            nh[i] = n.sub().isEmpty() ? 34 : 46;
        }

        int pad = 16;
        int gapX = 56;
        int gapY = 44;
        if ("lr".equals(d.layout()) && !d.edges().isEmpty()) {
            int maxEdgeW = 0;
            for (EdgeSpec e : d.edges()) {
                maxEdgeW = Math.max(maxEdgeW, fmE.stringWidth(e.label()));
            }
            gapX = Math.max(gapX, maxEdgeW + 26);
        }

        int totalW = pad * 2;
        int totalH = pad * 2;
        int[] nx = new int[d.nodes().size()];
        int[] ny = new int[d.nodes().size()];
        Map<String, Integer> idxMap = new LinkedHashMap<>();
        for (int i = 0; i < d.nodes().size(); i++) {
            idxMap.put(d.nodes().get(i).id(), i);
        }

        if ("td".equals(d.layout())) {
            int maxW = 0;
            int y = pad;
            for (int i = 0; i < d.nodes().size(); i++) {
                maxW = Math.max(maxW, nw[i]);
            }
            totalW = Math.max(220, maxW + pad * 2 + 120);
            for (int i = 0; i < d.nodes().size(); i++) {
                nx[i] = (totalW - nw[i]) / 2;
                ny[i] = y;
                y += nh[i] + gapY;
            }
            totalH = Math.max(80, y - gapY + pad);
        } else if ("bus".equals(d.layout())) {
            int x = pad + 20;
            int maxH = 0;
            for (int i = 0; i < d.nodes().size(); i++) {
                nx[i] = x;
                ny[i] = pad;
                x += nw[i] + 24;
                maxH = Math.max(maxH, nh[i]);
            }
            totalW = Math.max(320, x + 20);
            totalH = pad + maxH + 54;
        } else {
            int x = pad;
            int maxH = 0;
            for (int i = 0; i < d.nodes().size(); i++) {
                maxH = Math.max(maxH, nh[i]);
            }
            for (int i = 0; i < d.nodes().size(); i++) {
                nx[i] = x;
                ny[i] = pad + (maxH - nh[i]) / 2;
                x += nw[i] + gapX;
            }
            totalW = Math.max(220, x - gapX + pad);
            totalH = pad * 2 + maxH + 14;
        }
        tg.dispose();

        BufferedImage img = new BufferedImage(totalW, totalH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, totalW, totalH);

        if ("bus".equals(d.layout()) && nCount > 0) {
            int busY = totalH - 24;
            int busX1 = pad;
            int busX2 = totalW - pad;
            g.setColor(new Color(0x0B, 0x4A, 0x85));
            g.setStroke(new BasicStroke(2.2f));
            g.drawLine(busX1, busY, busX2, busY);
            g.setFont(edgeFont);
            g.drawString("120 Ω", busX1, busY - 5);
            g.drawString("120 Ω", busX2 - 32, busY - 5);
            g.setStroke(new BasicStroke(1.3f));
            g.setColor(new Color(0x55, 0x66, 0x77));
            for (int i = 0; i < d.nodes().size(); i++) {
                int cx = nx[i] + nw[i] / 2;
                g.drawLine(cx, ny[i] + nh[i], cx, busY);
                g.fillOval(cx - 3, busY - 3, 6, 6);
            }
        } else {
            g.setStroke(new BasicStroke(1.4f));
            for (EdgeSpec e : d.edges()) {
                Integer fi = idxMap.get(e.from());
                Integer ti = idxMap.get(e.to());
                if (fi == null || ti == null) continue;
                int x1, y1, x2, y2;
                if ("td".equals(d.layout())) {
                    x1 = nx[fi] + nw[fi] / 2;
                    y1 = ny[fi] + nh[fi];
                    x2 = nx[ti] + nw[ti] / 2;
                    y2 = ny[ti];
                } else {
                    x1 = nx[fi] + nw[fi];
                    y1 = ny[fi] + nh[fi] / 2;
                    x2 = nx[ti];
                    y2 = ny[ti] + nh[ti] / 2;
                }
                g.setColor(new Color(0x44, 0x55, 0x66));
                g.drawLine(x1, y1, x2, y2);
                drawArrowHead(g, x1, y1, x2, y2);
                if (e.bidir()) {
                    drawArrowHead(g, x2, y2, x1, y1);
                }
                if (!e.label().isEmpty()) {
                    g.setFont(edgeFont);
                    int lw = fmE.stringWidth(e.label());
                    int mx = (x1 + x2) / 2;
                    int my = (y1 + y2) / 2;
                    if ("td".equals(d.layout())) {
                        g.setColor(new Color(0x22, 0x22, 0x22));
                        g.drawString(e.label(), mx + 8, my + 4);
                    } else {
                        g.setColor(new Color(0x22, 0x22, 0x22));
                        g.drawString(e.label(), mx - lw / 2, my - 6);
                    }
                }
            }
        }

        for (int i = 0; i < d.nodes().size(); i++) {
            NodeSpec n = d.nodes().get(i);
            RoundRectangle2D box = new RoundRectangle2D.Float(nx[i], ny[i], nw[i], nh[i], 8, 8);
            g.setColor(n.fill());
            g.fill(box);
            g.setColor(n.border());
            g.setStroke(new BasicStroke(1.4f));
            g.draw(box);

            g.setColor(n.text());
            g.setFont(titleFont);
            int tw = fmT.stringWidth(n.label());
            int ty = n.sub().isEmpty() ? ny[i] + (nh[i] + fmT.getAscent() - fmT.getDescent()) / 2
                    : ny[i] + 17;
            g.drawString(n.label(), nx[i] + (nw[i] - tw) / 2, ty);

            if (!n.sub().isEmpty()) {
                g.setFont(subFont);
                g.setColor(new Color(0x44, 0x4E, 0x58));
                int sw = fmS.stringWidth(n.sub());
                g.drawString(n.sub(), nx[i] + (nw[i] - sw) / 2, ny[i] + 34);
            }
        }

        g.dispose();
        return img;
    }

    private static void drawArrowHead(Graphics2D g, int x1, int y1, int x2, int y2) {
        double angle = Math.atan2(y2 - y1, x2 - x1);
        int len = 7;
        int ax1 = (int) Math.round(x2 - len * Math.cos(angle - Math.PI / 6));
        int ay1 = (int) Math.round(y2 - len * Math.sin(angle - Math.PI / 6));
        int ax2 = (int) Math.round(x2 - len * Math.cos(angle + Math.PI / 6));
        int ay2 = (int) Math.round(y2 - len * Math.sin(angle + Math.PI / 6));
        g.fillPolygon(new int[]{x2, ax1, ax2}, new int[]{y2, ay1, ay2}, 3);
    }

    private static Color[] palette(String name) {
        return switch (name) {
            case "teal" -> new Color[]{new Color(0xE6, 0xF5, 0xF3), new Color(0x0F, 0x76, 0x6E), new Color(0x0B, 0x4F, 0x4A)};
            case "green" -> new Color[]{new Color(0xEA, 0xF7, 0xED), new Color(0x1E, 0x84, 0x49), new Color(0x14, 0x5A, 0x32)};
            case "orange" -> new Color[]{new Color(0xFF, 0xF4, 0xE5), new Color(0xD9, 0x77, 0x06), new Color(0x92, 0x40, 0x0E)};
            case "red" -> new Color[]{new Color(0xFD, 0xEC, 0xEC), new Color(0xC9, 0x4A, 0x4A), new Color(0x8A, 0x1C, 0x1C)};
            case "gray" -> new Color[]{new Color(0xF3, 0xF4, 0xF6), new Color(0x6B, 0x72, 0x80), new Color(0x1F, 0x29, 0x37)};
            default -> new Color[]{new Color(0xEA, 0xF3, 0xFB), new Color(0x0F, 0x60, 0xAA), new Color(0x00, 0x33, 0x66)};
        };
    }
}
