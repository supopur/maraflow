package cz.maramat.help;

import cz.maramat.help.diagram.ArchDiagramRenderer;
import cz.maramat.help.diagram.FbdDiagramParser;
import cz.maramat.help.diagram.FbdDiagramRenderer;
import cz.maramat.help.diagram.FbdDiagramSpec;
import cz.maramat.help.diagram.LdDiagramRenderer;
import org.commonmark.Extension;
import org.commonmark.ext.gfm.tables.TableBlock;
import org.commonmark.ext.gfm.tables.TableBody;
import org.commonmark.ext.gfm.tables.TableRow;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.ext.heading.anchor.HeadingAnchorExtension;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.Heading;
import org.commonmark.node.Node;
import org.commonmark.node.Text;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.NodeRenderer;
import org.commonmark.renderer.html.AttributeProvider;
import org.commonmark.renderer.html.HtmlNodeRendererContext;
import org.commonmark.renderer.html.HtmlRenderer;
import org.commonmark.renderer.html.HtmlWriter;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders {@link HelpPage} markdown into flat utilitarian HTML suitable for {@link javax.swing.JEditorPane},
 * with embedded {@code ```fbd} diagrams and syntax-colored {@code ```st} code blocks.
 */
public final class HelpMarkdownRenderer {

    public record AnchorEntry(int level, String text, String id) {}

    public record RenderedHelpHtml(String html, List<AnchorEntry> anchors) {}

    private static final Set<String> ST_KEYWORDS = Set.of(
            "VAR", "VAR_INPUT", "VAR_OUTPUT", "VAR_TEMP", "END_VAR",
            "IF", "THEN", "ELSIF", "ELSEIF", "ELSE", "END_IF", "ENDIF",
            "CASE", "OF", "END_CASE", "ENDCASE",
            "FOR", "TO", "BY", "DO", "END_FOR", "ENDFOR",
            "WHILE", "END_WHILE", "ENDWHILE",
            "REPEAT", "UNTIL", "END_REPEAT", "ENDREPEAT",
            "RETURN", "EXIT", "TRUE", "FALSE", "AND", "OR", "XOR", "NOT", "MOD",
            "BOOL", "INT", "LONG", "LINT", "DINT", "FLOAT", "REAL", "LREAL"
    );

    private static final Pattern ST_TOKEN = Pattern.compile(
            "(//[^\\n]*|\\(\\*(?:.|\\R)*?\\*\\)|/\\*(?:.|\\R)*?\\*/|\\b(?:16#[0-9A-Fa-f_]+|2#[01_]+|8#[0-7_]+|\\d+(?:\\.\\d+)?)\\b|\\b[A-Za-z_][A-Za-z0-9_]*\\b)"
    );

    private final Parser parser;
    private final HtmlRenderer renderer;

    public HelpMarkdownRenderer() {
        List<Extension> extensions = List.of(
                TablesExtension.create(),
                HeadingAnchorExtension.create()
        );
        this.parser = Parser.builder().extensions(extensions).build();
        this.renderer = HtmlRenderer.builder()
                .extensions(extensions)
                .attributeProviderFactory(ctx -> new TableAttributeProvider())
                .nodeRendererFactory(CustomCodeRenderer::new)
                .build();
    }

    public RenderedHelpHtml render(HelpPage page, String requestedLocale, int gridPx) {
        Node document = parser.parse(page.markdown());
        List<AnchorEntry> anchors = collectHeadings(document);
        String bodyHtml = renderer.render(document);

        StringBuilder html = new StringBuilder();
        html.append("<html><head><style>");
        html.append(buildCss());
        html.append("</style></head><body>");

        if (page.fallback()) {
            String msg = "cs".equals(requestedLocale)
                    ? "Tato stránka zatím není přeložena do češtiny. Zobrazuje se anglická verze."
                    : "Showing fallback language.";
            html.append("<div class=\"fallback-banner\">").append(escapeHtml(msg)).append("</div>");
        }

        html.append(bodyHtml);

        if (page.seeAlso() != null && !page.seeAlso().isEmpty()) {
            String seeTitle = "cs".equals(requestedLocale) ? "Viz také" : "See Also";
            html.append("<h2>").append(seeTitle).append("</h2><ul>");
            for (String target : page.seeAlso()) {
                html.append("<li><a href=\"help:").append(escapeHtml(target)).append("\">")
                        .append(escapeHtml(target)).append("</a></li>");
            }
            html.append("</ul>");
        }

        html.append("</body></html>");
        return new RenderedHelpHtml(html.toString(), anchors);
    }

    private static List<AnchorEntry> collectHeadings(Node document) {
        List<AnchorEntry> out = new ArrayList<>();
        for (Node child = document.getFirstChild(); child != null; child = child.getNext()) {
            if (child instanceof Heading h && (h.getLevel() == 2 || h.getLevel() == 3)) {
                String text = extractPlainText(h);
                String id = text.toLowerCase(Locale.ROOT)
                        .replaceAll("[^a-z0-9\\s-]", "")
                        .trim()
                        .replaceAll("\\s+", "-");
                if (!text.isEmpty()) {
                    out.add(new AnchorEntry(h.getLevel(), text, id));
                }
            }
        }
        return out;
    }

    private static String extractPlainText(Node node) {
        StringBuilder sb = new StringBuilder();
        for (Node c = node.getFirstChild(); c != null; c = c.getNext()) {
            if (c instanceof Text t) {
                sb.append(t.getLiteral());
            } else {
                sb.append(extractPlainText(c));
            }
        }
        return sb.toString();
    }

    private static String buildCss() {
        return """
                body { font-family: 'Segoe UI', SansSerif; font-size: 11px; color: #111111; background-color: #FFFFFF; margin: 12px 16px; }
                h1 { font-size: 17px; font-weight: bold; color: #003366; border-bottom: 1px solid #B0B0B0; padding-bottom: 4px; margin-top: 0; margin-bottom: 10px; }
                h2 { font-size: 13px; font-weight: bold; color: #1A1A1A; border-bottom: 1px solid #DCDCDC; padding-bottom: 2px; margin-top: 14px; margin-bottom: 6px; }
                h3 { font-size: 11px; font-weight: bold; color: #333333; margin-top: 10px; margin-bottom: 4px; }
                p { margin-top: 4px; margin-bottom: 8px; }
                ul, ol { margin-top: 4px; margin-bottom: 8px; margin-left: 20px; }
                li { margin-bottom: 2px; }
                a { color: #0055AA; text-decoration: underline; }
                code { font-family: Monospaced; font-size: 10px; background-color: #F2F2F2; padding: 1px 3px; }
                pre { font-family: Monospaced; font-size: 10px; background-color: #F6F6F6; border: 1px solid #C8C8C8; padding: 6px 8px; margin: 6px 0; }
                table { border-collapse: collapse; margin: 6px 0 10px 0; border-top: 1px solid #0B4A85; border-left: 1px solid #C8CFD6; }
                th { background-color: #0F60AA; color: #FFFFFF; font-weight: bold; text-align: center; border-right: 1px solid #0B4A85; border-bottom: 1px solid #0B4A85; padding-top: 6px; padding-bottom: 6px; padding-left: 12px; padding-right: 12px; }
                th code { background-color: #0F60AA; color: #FFFFFF; padding: 0; }
                td { background-color: #FFFFFF; border-right: 1px solid #D0D7DE; border-bottom: 1px solid #D0D7DE; padding-top: 5px; padding-bottom: 5px; padding-left: 12px; padding-right: 12px; vertical-align: top; }
                td code { background-color: #FFFFFF; padding: 0; }
                tr.alt-row td { background-color: #F2F5F8; }
                tr.alt-row td code { background-color: #F2F5F8; padding: 0; }
                blockquote { border-left: 3px solid #888888; background-color: #F8F8F8; padding: 4px 8px; margin: 6px 0; color: #333333; }
                .fallback-banner { background-color: #FFF8DC; border: 1px solid #D4C27A; color: #333333; padding: 5px 8px; margin-bottom: 10px; font-size: 10px; }
                .diagram { margin: 8px 0; padding: 4px; background-color: #FFFFFF; }
                .diagram-caption { font-size: 10px; color: #444444; margin-top: 4px; }
                .diagram-error { background-color: #FDECEC; border: 1px solid #C94A4A; color: #8A1C1C; padding: 6px; font-family: Monospaced; font-size: 10px; }
                .st-actions { font-size: 10px; margin-top: -3px; margin-bottom: 8px; }
                .kw { color: #0000AA; font-weight: bold; }
                .num { color: #007700; }
                .cmt { color: #666666; font-style: italic; }
                """;
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static String highlightSt(String code) {
        Matcher m = ST_TOKEN.matcher(code);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (m.find()) {
            out.append(escapeHtml(code.substring(last, m.start())));
            String tok = m.group(1);
            if (tok.startsWith("//") || tok.startsWith("(*")) {
                out.append("<span class=\"cmt\">").append(escapeHtml(tok)).append("</span>");
            } else if (tok.startsWith("'")) {
                out.append("<span class=\"num\">").append(escapeHtml(tok)).append("</span>");
            } else if (Character.isDigit(tok.charAt(0))) {
                out.append("<span class=\"num\">").append(escapeHtml(tok)).append("</span>");
            } else if (ST_KEYWORDS.contains(tok.toUpperCase(Locale.ROOT))) {
                out.append("<span class=\"kw\">").append(escapeHtml(tok)).append("</span>");
            } else {
                out.append(escapeHtml(tok));
            }
            last = m.end();
        }
        out.append(escapeHtml(code.substring(last)));
        return out.toString();
    }

    private static final class TableAttributeProvider implements AttributeProvider {
        @Override
        public void setAttributes(Node node, String tagName, Map<String, String> attributes) {
            if (node instanceof TableBlock) {
                attributes.put("cellspacing", "0");
                attributes.put("cellpadding", "6");
            } else if ("th".equals(tagName)) {
                attributes.put("align", "center");
            } else if (node instanceof TableRow && node.getParent() instanceof TableBody) {
                int zeroIdx = 0;
                for (Node prev = node.getPrevious(); prev != null; prev = prev.getPrevious()) {
                    zeroIdx++;
                }
                if (zeroIdx % 2 == 0) {
                    attributes.put("class", "alt-row");
                }
            }
        }
    }

    private static final class CustomCodeRenderer implements NodeRenderer {
        private final HtmlWriter html;

        CustomCodeRenderer(HtmlNodeRendererContext context) {
            this.html = context.getWriter();
        }

        @Override
        public Set<Class<? extends Node>> getNodeTypes() {
            return Set.of(FencedCodeBlock.class);
        }

        @Override
        public void render(Node node) {
            FencedCodeBlock fcb = (FencedCodeBlock) node;
            String info = fcb.getInfo() == null ? "" : fcb.getInfo().trim().toLowerCase(Locale.ROOT);
            String literal = fcb.getLiteral();

            if ("fbd".equals(info)) {
                try {
                    FbdDiagramSpec spec = FbdDiagramParser.parse(literal);
                    String hash = FbdDiagramRenderer.registerSource(literal);
                    BufferedImage img = FbdDiagramRenderer.getOrRenderByHash(hash, FbdDiagramRenderer.DEFAULT_GRID_PX);
                    int w = img != null ? img.getWidth() : 200;
                    int h = img != null ? img.getHeight() : 100;
                    html.line();
                    html.raw("<div class=\"diagram\">");
                    html.raw("<img src=\"diagram:" + hash + "\" width=\"" + w + "\" height=\"" + h + "\"/>");
                    if (spec.caption() != null && !spec.caption().isBlank()) {
                        html.raw("<div class=\"diagram-caption\">" + escapeHtml(spec.caption()) + "</div>");
                    }
                    html.raw("</div>");
                    html.line();
                } catch (Exception ex) {
                    html.line();
                    html.raw("<div class=\"diagram-error\">Diagram error: " + escapeHtml(ex.getMessage()) + "</div>");
                    html.line();
                }
                return;
            }

            if ("ld".equals(info)) {
                try {
                    LdDiagramRenderer.ParsedLdDiagram parsed = LdDiagramRenderer.parse(literal);
                    String hash = LdDiagramRenderer.registerSource(literal);
                    BufferedImage img = LdDiagramRenderer.getOrRenderByHash(hash);
                    int w = img != null ? img.getWidth() : 320;
                    int h = img != null ? img.getHeight() : 120;
                    html.line();
                    html.raw("<div class=\"diagram\">");
                    html.raw("<img src=\"diagram:" + hash + "\" width=\"" + w + "\" height=\"" + h + "\"/>");
                    if (parsed.caption() != null && !parsed.caption().isBlank()) {
                        html.raw("<div class=\"diagram-caption\">" + escapeHtml(parsed.caption()) + "</div>");
                    }
                    html.raw("</div>");
                    html.line();
                } catch (Exception ex) {
                    html.line();
                    html.raw("<div class=\"diagram-error\">Diagram error: " + escapeHtml(ex.getMessage()) + "</div>");
                    html.line();
                }
                return;
            }

            if ("diagram".equals(info)) {
                try {
                    ArchDiagramRenderer.ParsedDiagram parsed = ArchDiagramRenderer.parse(literal);
                    String hash = ArchDiagramRenderer.registerSource(literal);
                    BufferedImage img = ArchDiagramRenderer.getOrRenderByHash(hash);
                    int w = img != null ? img.getWidth() : 320;
                    int h = img != null ? img.getHeight() : 100;
                    html.line();
                    html.raw("<div class=\"diagram\">");
                    html.raw("<img src=\"diagram:" + hash + "\" width=\"" + w + "\" height=\"" + h + "\"/>");
                    if (parsed.caption() != null && !parsed.caption().isBlank()) {
                        html.raw("<div class=\"diagram-caption\">" + escapeHtml(parsed.caption()) + "</div>");
                    }
                    html.raw("</div>");
                    html.line();
                } catch (Exception ex) {
                    html.line();
                    html.raw("<div class=\"diagram-error\">Diagram error: " + escapeHtml(ex.getMessage()) + "</div>");
                    html.line();
                }
                return;
            }

            if ("st".equals(info)) {
                String encoded = java.util.Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(literal.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                html.line();
                html.raw("<pre class=\"st\"><code>");
                html.raw(highlightSt(literal));
                html.raw("</code></pre>");
                html.raw("<div class=\"st-actions\"><a href=\"open-st:" + encoded + "\">Open in ST Editor</a></div>");
                html.line();
                return;
            }

            html.line();
            html.raw("<pre><code>");
            html.raw(escapeHtml(literal));
            html.raw("</code></pre>");
            html.line();
        }
    }
}
