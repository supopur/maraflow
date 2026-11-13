package cz.maramat.help;

import cz.maramat.help.diagram.FbdDiagramRenderer;
import cz.maramat.help.diagram.LdDiagramRenderer;

import javax.swing.text.BadLocationException;
import javax.swing.text.Element;
import javax.swing.text.Position;
import javax.swing.text.View;
import javax.swing.text.ViewFactory;
import javax.swing.text.html.HTML;
import javax.swing.text.html.HTMLEditorKit;
import java.awt.Graphics;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.image.BufferedImage;

/**
 * {@link HTMLEditorKit} with a custom {@link ViewFactory} that renders
 * {@code <img src="diagram:<hash>">} elements directly from {@link FbdDiagramRenderer}.
 */
public final class HelpHtmlEditorKit extends HTMLEditorKit {

    private final int gridPx;

    public HelpHtmlEditorKit(int gridPx) {
        this.gridPx = gridPx;
    }

    @Override
    public ViewFactory getViewFactory() {
        ViewFactory defaultFactory = super.getViewFactory();
        return elem -> {
            Object nameAttr = elem.getAttributes().getAttribute(javax.swing.text.StyleConstants.NameAttribute);
            if (nameAttr == HTML.Tag.IMG) {
                Object src = elem.getAttributes().getAttribute(HTML.Attribute.SRC);
                if (src != null && src.toString().startsWith("diagram:")) {
                    String hash = src.toString().substring("diagram:".length());
                    return new DiagramImageView(elem, hash, gridPx);
                }
            }
            return defaultFactory.create(elem);
        };
    }

    private static final class DiagramImageView extends View {
        private final String hash;
        private final int gridPx;

        DiagramImageView(Element elem, String hash, int gridPx) {
            super(elem);
            this.hash = hash;
            this.gridPx = gridPx;
        }

        private BufferedImage image() {
            if (hash.startsWith("ld_")) {
                return LdDiagramRenderer.getOrRenderByHash(hash);
            }
            return FbdDiagramRenderer.getOrRenderByHash(hash, gridPx);
        }

        @Override
        public float getPreferredSpan(int axis) {
            BufferedImage img = image();
            if (img == null) {
                return axis == X_AXIS ? 120f : 60f;
            }
            return axis == X_AXIS ? img.getWidth() : img.getHeight();
        }

        @Override
        public float getMinimumSpan(int axis) {
            return getPreferredSpan(axis);
        }

        @Override
        public float getMaximumSpan(int axis) {
            return getPreferredSpan(axis);
        }

        @Override
        public void paint(Graphics g, Shape allocation) {
            BufferedImage img = image();
            if (img == null) return;
            Rectangle r = allocation.getBounds();
            g.drawImage(img, r.x, r.y, img.getWidth(), img.getHeight(), null);
        }

        @Override
        public Shape modelToView(int pos, Shape a, Position.Bias b) throws BadLocationException {
            int p0 = getStartOffset();
            int p1 = getEndOffset();
            if (pos >= p0 && pos <= p1) {
                Rectangle r = a.getBounds();
                if (pos == p1) {
                    r.x += r.width;
                }
                r.width = 0;
                return r;
            }
            throw new BadLocationException(pos + " not in range " + p0 + "," + p1, pos);
        }

        @Override
        public int viewToModel(float x, float y, Shape a, Position.Bias[] bias) {
            Rectangle r = a.getBounds();
            if (x < r.x + r.width / 2f) {
                bias[0] = Position.Bias.Forward;
                return getStartOffset();
            }
            bias[0] = Position.Bias.Backward;
            return getEndOffset();
        }
    }
}
