package cz.maramat.io;

import java.awt.*;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.awt.image.BufferedImageOp;
import java.awt.image.ImageObserver;
import java.awt.image.RenderedImage;
import java.awt.image.renderable.RenderableImage;
import java.text.AttributedCharacterIterator;
import java.util.Locale;
import java.util.Map;

/**
 * Lightweight {@link Graphics2D} implementation that emits clean, standards-compliant SVG
 * with real selectable {@code <text>} elements, crisp vector strokes, and clip paths.
 */
public class SvgGraphics2D extends Graphics2D {

    private static final class SvgBuffer {
        final StringBuilder defs = new StringBuilder();
        final StringBuilder body = new StringBuilder();
        int clipCounter = 0;
        final Graphics2D metricsDelegate;

        SvgBuffer() {
            BufferedImage img = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
            metricsDelegate = img.createGraphics();
        }
    }

    private final SvgBuffer buf;
    private AffineTransform transform;
    private Color color = Color.BLACK;
    private Color background = Color.WHITE;
    private Paint paint = Color.BLACK;
    private Stroke stroke = new BasicStroke(1.0f);
    private Font font = new Font("SansSerif", Font.PLAIN, 12);
    private Shape clip = null;
    private Composite composite = AlphaComposite.SrcOver;
    private final RenderingHints hints = new RenderingHints(null);

    public SvgGraphics2D() {
        this.buf = new SvgBuffer();
        this.transform = new AffineTransform();
    }

    private SvgGraphics2D(SvgGraphics2D parent) {
        this.buf = parent.buf;
        this.transform = new AffineTransform(parent.transform);
        this.color = parent.color;
        this.background = parent.background;
        this.paint = parent.paint;
        this.stroke = parent.stroke;
        this.font = parent.font;
        this.clip = parent.clip != null ? new Area(parent.clip) : null;
        this.composite = parent.composite;
        this.hints.putAll(parent.hints);
    }

    /**
     * Returns the complete SVG XML document string sized to {@code widthPt x heightPt} (in points/px).
     */
    public String toSvgString(double widthPt, double heightPt) {
        String w = fmt(widthPt);
        String h = fmt(heightPt);
        StringBuilder out = new StringBuilder(buf.body.length() + buf.defs.length() + 512);
        out.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        out.append("<svg xmlns=\"http://www.w3.org/2000/svg\" ");
        out.append("width=\"").append(w).append("pt\" height=\"").append(h).append("pt\" ");
        out.append("viewBox=\"0 0 ").append(w).append(' ').append(h).append("\">\n");
        if (!buf.defs.isEmpty()) {
            out.append("  <defs>\n").append(buf.defs).append("  </defs>\n");
        }
        out.append(buf.body);
        out.append("</svg>\n");
        return out.toString();
    }

    private static String fmt(double v) {
        if (Math.abs(v - Math.rint(v)) < 1e-4) {
            return Long.toString(Math.round(v));
        }
        return String.format(Locale.US, "%.3f", v);
    }

    private static String colorHex(Color c) {
        return String.format(Locale.US, "#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
    }

    private double currentOpacity() {
        double a = color.getAlpha() / 255.0;
        if (composite instanceof AlphaComposite ac) {
            a *= ac.getAlpha();
        }
        return Math.max(0.0, Math.min(1.0, a));
    }

    private String transformAttr() {
        if (transform.isIdentity()) return "";
        double[] m = new double[6];
        transform.getMatrix(m);
        return String.format(Locale.US, " transform=\"matrix(%.5f,%.5f,%.5f,%.5f,%.3f,%.3f)\"",
                m[0], m[1], m[2], m[3], m[4], m[5]);
    }

    private String clipAttr() {
        if (clip == null) return "";
        String id = "clip" + (++buf.clipCounter);
        buf.defs.append("    <clipPath id=\"").append(id).append("\">");
        if (clip instanceof Rectangle2D r) {
            buf.defs.append("<rect x=\"").append(fmt(r.getX()))
                    .append("\" y=\"").append(fmt(r.getY()))
                    .append("\" width=\"").append(fmt(Math.max(0, r.getWidth())))
                    .append("\" height=\"").append(fmt(Math.max(0, r.getHeight())))
                    .append("\"/>");
        } else {
            buf.defs.append("<path d=\"").append(shapeToPathData(clip)).append("\"/>");
        }
        buf.defs.append("</clipPath>\n");
        return " clip-path=\"url(#" + id + ")\"";
    }

    private String fillAttrs() {
        StringBuilder sb = new StringBuilder();
        sb.append(" fill=\"").append(colorHex(color)).append('"');
        double op = currentOpacity();
        if (op < 0.999) {
            sb.append(" fill-opacity=\"").append(fmt(op)).append('"');
        }
        return sb.toString();
    }

    private String strokeAttrs() {
        StringBuilder sb = new StringBuilder();
        sb.append(" fill=\"none\" stroke=\"").append(colorHex(color)).append('"');
        double op = currentOpacity();
        if (op < 0.999) {
            sb.append(" stroke-opacity=\"").append(fmt(op)).append('"');
        }
        if (stroke instanceof BasicStroke bs) {
            sb.append(" stroke-width=\"").append(fmt(Math.max(0.25f, bs.getLineWidth()))).append('"');
            switch (bs.getEndCap()) {
                case BasicStroke.CAP_ROUND -> sb.append(" stroke-linecap=\"round\"");
                case BasicStroke.CAP_SQUARE -> sb.append(" stroke-linecap=\"square\"");
                default -> sb.append(" stroke-linecap=\"butt\"");
            }
            switch (bs.getLineJoin()) {
                case BasicStroke.JOIN_ROUND -> sb.append(" stroke-linejoin=\"round\"");
                case BasicStroke.JOIN_BEVEL -> sb.append(" stroke-linejoin=\"bevel\"");
                default -> sb.append(" stroke-linejoin=\"miter\"");
            }
            float[] dash = bs.getDashArray();
            if (dash != null && dash.length > 0) {
                sb.append(" stroke-dasharray=\"");
                for (int i = 0; i < dash.length; i++) {
                    if (i > 0) sb.append(',');
                    sb.append(fmt(dash[i]));
                }
                sb.append('"');
                if (bs.getDashPhase() != 0f) {
                    sb.append(" stroke-dashoffset=\"").append(fmt(bs.getDashPhase())).append('"');
                }
            }
        } else {
            sb.append(" stroke-width=\"1\"");
        }
        return sb.toString();
    }

    private static String escapeXml(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                default -> {
                    if (c >= 0x20 || c == '\t') {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    private static String shapeToPathData(Shape s) {
        PathIterator it = s.getPathIterator(null);
        double[] c = new double[6];
        StringBuilder d = new StringBuilder();
        while (!it.isDone()) {
            int seg = it.currentSegment(c);
            switch (seg) {
                case PathIterator.SEG_MOVETO -> d.append('M').append(fmt(c[0])).append(',').append(fmt(c[1])).append(' ');
                case PathIterator.SEG_LINETO -> d.append('L').append(fmt(c[0])).append(',').append(fmt(c[1])).append(' ');
                case PathIterator.SEG_QUADTO -> d.append('Q').append(fmt(c[0])).append(',').append(fmt(c[1]))
                        .append(' ').append(fmt(c[2])).append(',').append(fmt(c[3])).append(' ');
                case PathIterator.SEG_CUBICTO -> d.append('C').append(fmt(c[0])).append(',').append(fmt(c[1]))
                        .append(' ').append(fmt(c[2])).append(',').append(fmt(c[3]))
                        .append(' ').append(fmt(c[4])).append(',').append(fmt(c[5])).append(' ');
                case PathIterator.SEG_CLOSE -> d.append("Z ");
                default -> {}
            }
            it.next();
        }
        return d.toString().trim();
    }

    private String fontFamilyCss(Font f) {
        String name = f.getFamily(Locale.US);
        if (name == null || name.isBlank()) name = f.getName();
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.contains("mono") || lower.contains("consolas") || lower.contains("courier") || lower.contains("menlo")) {
            return "'Courier New', Consolas, Menlo, monospace";
        }
        return "'" + escapeXml(name) + "', Arial, Helvetica, sans-serif";
    }

    @Override
    public Graphics create() {
        return new SvgGraphics2D(this);
    }

    @Override
    public void dispose() {
        // Nothing to release for child contexts
    }

    @Override
    public void draw(Shape s) {
        if (s == null) return;
        if (s instanceof Line2D l) {
            buf.body.append("  <line x1=\"").append(fmt(l.getX1())).append("\" y1=\"").append(fmt(l.getY1()))
                    .append("\" x2=\"").append(fmt(l.getX2())).append("\" y2=\"").append(fmt(l.getY2()))
                    .append('"').append(strokeAttrs()).append(transformAttr()).append(clipAttr()).append("/>\n");
            return;
        }
        if (s instanceof Rectangle2D r) {
            buf.body.append("  <rect x=\"").append(fmt(r.getX())).append("\" y=\"").append(fmt(r.getY()))
                    .append("\" width=\"").append(fmt(Math.max(0, r.getWidth())))
                    .append("\" height=\"").append(fmt(Math.max(0, r.getHeight())))
                    .append('"').append(strokeAttrs()).append(transformAttr()).append(clipAttr()).append("/>\n");
            return;
        }
        if (s instanceof Ellipse2D e) {
            buf.body.append("  <ellipse cx=\"").append(fmt(e.getCenterX())).append("\" cy=\"").append(fmt(e.getCenterY()))
                    .append("\" rx=\"").append(fmt(Math.max(0, e.getWidth() / 2.0)))
                    .append("\" ry=\"").append(fmt(Math.max(0, e.getHeight() / 2.0)))
                    .append('"').append(strokeAttrs()).append(transformAttr()).append(clipAttr()).append("/>\n");
            return;
        }
        buf.body.append("  <path d=\"").append(shapeToPathData(s)).append('"')
                .append(strokeAttrs()).append(transformAttr()).append(clipAttr()).append("/>\n");
    }

    @Override
    public void fill(Shape s) {
        if (s == null) return;
        if (s instanceof Rectangle2D r) {
            buf.body.append("  <rect x=\"").append(fmt(r.getX())).append("\" y=\"").append(fmt(r.getY()))
                    .append("\" width=\"").append(fmt(Math.max(0, r.getWidth())))
                    .append("\" height=\"").append(fmt(Math.max(0, r.getHeight())))
                    .append('"').append(fillAttrs()).append(transformAttr()).append(clipAttr()).append("/>\n");
            return;
        }
        if (s instanceof Ellipse2D e) {
            buf.body.append("  <ellipse cx=\"").append(fmt(e.getCenterX())).append("\" cy=\"").append(fmt(e.getCenterY()))
                    .append("\" rx=\"").append(fmt(Math.max(0, e.getWidth() / 2.0)))
                    .append("\" ry=\"").append(fmt(Math.max(0, e.getHeight() / 2.0)))
                    .append('"').append(fillAttrs()).append(transformAttr()).append(clipAttr()).append("/>\n");
            return;
        }
        buf.body.append("  <path d=\"").append(shapeToPathData(s)).append('"')
                .append(fillAttrs()).append(transformAttr()).append(clipAttr()).append("/>\n");
    }

    @Override
    public void drawString(String str, int x, int y) {
        drawString(str, (float) x, (float) y);
    }

    @Override
    public void drawString(String str, float x, float y) {
        if (str == null || str.isEmpty()) return;
        buf.body.append("  <text x=\"").append(fmt(x)).append("\" y=\"").append(fmt(y)).append('"')
                .append(" font-family=\"").append(fontFamilyCss(font)).append('"')
                .append(" font-size=\"").append(fmt(font.getSize2D())).append('"');
        if (font.isBold()) buf.body.append(" font-weight=\"bold\"");
        if (font.isItalic()) buf.body.append(" font-style=\"italic\"");
        buf.body.append(" xml:space=\"preserve\"")
                .append(fillAttrs()).append(transformAttr()).append(clipAttr())
                .append('>').append(escapeXml(str)).append("</text>\n");
    }

    @Override
    public void drawString(AttributedCharacterIterator iterator, int x, int y) {
        drawString(iterator, (float) x, (float) y);
    }

    @Override
    public void drawString(AttributedCharacterIterator iterator, float x, float y) {
        if (iterator == null) return;
        StringBuilder sb = new StringBuilder();
        for (char c = iterator.first(); c != AttributedCharacterIterator.DONE; c = iterator.next()) {
            sb.append(c);
        }
        drawString(sb.toString(), x, y);
    }

    @Override
    public void drawGlyphVector(GlyphVector g, float x, float y) {
        if (g != null) fill(g.getOutline(x, y));
    }

    @Override public void drawLine(int x1, int y1, int x2, int y2) { draw(new Line2D.Double(x1, y1, x2, y2)); }
    @Override public void drawRect(int x, int y, int width, int height) { draw(new Rectangle2D.Double(x, y, width, height)); }
    @Override public void fillRect(int x, int y, int width, int height) { fill(new Rectangle2D.Double(x, y, width, height)); }
    @Override public void clearRect(int x, int y, int width, int height) {
        Color prev = color;
        color = background;
        fillRect(x, y, width, height);
        color = prev;
    }
    @Override public void drawRoundRect(int x, int y, int width, int height, int arcWidth, int arcHeight) {
        draw(new RoundRectangle2D.Double(x, y, width, height, arcWidth, arcHeight));
    }
    @Override public void fillRoundRect(int x, int y, int width, int height, int arcWidth, int arcHeight) {
        fill(new RoundRectangle2D.Double(x, y, width, height, arcWidth, arcHeight));
    }
    @Override public void drawOval(int x, int y, int width, int height) { draw(new Ellipse2D.Double(x, y, width, height)); }
    @Override public void fillOval(int x, int y, int width, int height) { fill(new Ellipse2D.Double(x, y, width, height)); }
    @Override public void drawArc(int x, int y, int width, int height, int startAngle, int arcAngle) {
        draw(new Arc2D.Double(x, y, width, height, startAngle, arcAngle, Arc2D.OPEN));
    }
    @Override public void fillArc(int x, int y, int width, int height, int startAngle, int arcAngle) {
        fill(new Arc2D.Double(x, y, width, height, startAngle, arcAngle, Arc2D.PIE));
    }
    @Override public void drawPolyline(int[] xPoints, int[] yPoints, int nPoints) {
        if (nPoints < 2) return;
        Path2D p = new Path2D.Double();
        p.moveTo(xPoints[0], yPoints[0]);
        for (int i = 1; i < nPoints; i++) p.lineTo(xPoints[i], yPoints[i]);
        draw(p);
    }
    @Override public void drawPolygon(int[] xPoints, int[] yPoints, int nPoints) {
        if (nPoints < 2) return;
        draw(new Polygon(xPoints, yPoints, nPoints));
    }
    @Override public void fillPolygon(int[] xPoints, int[] yPoints, int nPoints) {
        if (nPoints < 2) return;
        fill(new Polygon(xPoints, yPoints, nPoints));
    }

    @Override public Color getColor() { return color; }
    @Override public void setColor(Color c) { if (c != null) { this.color = c; this.paint = c; } }
    @Override public void setPaintMode() { this.composite = AlphaComposite.SrcOver; }
    @Override public void setXORMode(Color c1) {}
    @Override public Font getFont() { return font; }
    @Override public void setFont(Font font) { if (font != null) this.font = font; }
    @Override public FontMetrics getFontMetrics(Font f) { return buf.metricsDelegate.getFontMetrics(f != null ? f : font); }

    @Override public Rectangle getClipBounds() { return clip != null ? clip.getBounds() : null; }
    @Override public void clipRect(int x, int y, int width, int height) { clip(new Rectangle2D.Double(x, y, width, height)); }
    @Override public void setClip(int x, int y, int width, int height) { setClip(new Rectangle2D.Double(x, y, width, height)); }
    @Override public Shape getClip() { return clip; }
    @Override public void setClip(Shape clip) { this.clip = clip; }
    @Override public void clip(Shape s) {
        if (s == null) return;
        if (clip == null) {
            clip = s;
        } else {
            Area a = new Area(clip);
            a.intersect(new Area(s));
            clip = a;
        }
    }

    @Override public void translate(int x, int y) { transform.translate(x, y); }
    @Override public void translate(double tx, double ty) { transform.translate(tx, ty); }
    @Override public void rotate(double theta) { transform.rotate(theta); }
    @Override public void rotate(double theta, double x, double y) { transform.rotate(theta, x, y); }
    @Override public void scale(double sx, double sy) { transform.scale(sx, sy); }
    @Override public void shear(double shx, double shy) { transform.shear(shx, shy); }
    @Override public void transform(AffineTransform Tx) { if (Tx != null) transform.concatenate(Tx); }
    @Override public void setTransform(AffineTransform Tx) { this.transform = Tx != null ? new AffineTransform(Tx) : new AffineTransform(); }
    @Override public AffineTransform getTransform() { return new AffineTransform(transform); }

    @Override public Paint getPaint() { return paint; }
    @Override public void setPaint(Paint paint) {
        if (paint instanceof Color c) setColor(c);
        else if (paint != null) this.paint = paint;
    }
    @Override public Stroke getStroke() { return stroke; }
    @Override public void setStroke(Stroke s) { if (s != null) this.stroke = s; }
    @Override public Composite getComposite() { return composite; }
    @Override public void setComposite(Composite comp) { if (comp != null) this.composite = comp; }
    @Override public void setBackground(Color color) { if (color != null) this.background = color; }
    @Override public Color getBackground() { return background; }
    @Override public void setRenderingHint(RenderingHints.Key hintKey, Object hintValue) { if (hintKey != null && hintValue != null) hints.put(hintKey, hintValue); }
    @Override public Object getRenderingHint(RenderingHints.Key hintKey) { return hints.get(hintKey); }
    @Override public void setRenderingHints(Map<?, ?> hints) { this.hints.clear(); if (hints != null) this.hints.putAll(hints); }
    @Override public void addRenderingHints(Map<?, ?> hints) { if (hints != null) this.hints.putAll(hints); }
    @Override public RenderingHints getRenderingHints() { return (RenderingHints) hints.clone(); }
    @Override public FontRenderContext getFontRenderContext() { return buf.metricsDelegate.getFontRenderContext(); }
    @Override public GraphicsConfiguration getDeviceConfiguration() { return buf.metricsDelegate.getDeviceConfiguration(); }
    @Override public boolean hit(Rectangle rect, Shape s, boolean onStroke) { return s != null && rect != null && s.intersects(rect); }

    @Override public boolean drawImage(Image img, AffineTransform xform, ImageObserver obs) { return true; }
    @Override public void drawImage(BufferedImage img, BufferedImageOp op, int x, int y) {}
    @Override public void drawRenderedImage(RenderedImage img, AffineTransform xform) {}
    @Override public void drawRenderableImage(RenderableImage img, AffineTransform xform) {}
    @Override public boolean drawImage(Image img, int x, int y, ImageObserver observer) { return true; }
    @Override public boolean drawImage(Image img, int x, int y, int width, int height, ImageObserver observer) { return true; }
    @Override public boolean drawImage(Image img, int x, int y, Color bgcolor, ImageObserver observer) { return true; }
    @Override public boolean drawImage(Image img, int x, int y, int width, int height, Color bgcolor, ImageObserver observer) { return true; }
    @Override public boolean drawImage(Image img, int dx1, int dy1, int dx2, int dy2, int sx1, int sy1, int sx2, int sy2, ImageObserver observer) { return true; }
    @Override public boolean drawImage(Image img, int dx1, int dy1, int dx2, int dy2, int sx1, int sy1, int sx2, int sy2, Color bgcolor, ImageObserver observer) { return true; }
    @Override public void copyArea(int x, int y, int width, int height, int dx, int dy) {}
}
