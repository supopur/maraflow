package cz.maramat.ui.icons;

import cz.maramat.model.GraphicElement;
import cz.maramat.settings.AppSettings;

import javax.swing.Icon;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;

public final class VectorIcons {

    private VectorIcons() {}

    private abstract static class ScaledIcon implements Icon {
        private final int baseW;
        private final int baseH;

        ScaledIcon(int baseW, int baseH) {
            this.baseW = baseW;
            this.baseH = baseH;
        }

        @Override
        public int getIconWidth() {
            return AppSettings.get().scale(baseW);
        }

        @Override
        public int getIconHeight() {
            return AppSettings.get().scale(baseH);
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.translate(x, y);
            float s = AppSettings.get().getScaleFactor();
            g2.scale(s, s);
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            paintLogical(c, g2);
            g2.dispose();
        }

        protected abstract void paintLogical(Component c, Graphics2D g2);
    }

    private static void drawFlyoutCorner(Graphics2D g2, int w, int h) {
        g2.setColor(new Color(0x11, 0x11, 0x11));
        Polygon tri = new Polygon(
                new int[]{w - 1, w - 5, w - 1},
                new int[]{h - 5, h - 1, h - 1},
                3
        );
        g2.fillPolygon(tri);
    }

    public static Icon greenCheckIcon() {
        return new ScaledIcon(16, 16) {
            @Override
            protected void paintLogical(Component c, Graphics2D g2) {
                g2.setColor(new Color(0x14, 0x94, 0x14));
                g2.setStroke(new BasicStroke(2.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g2.drawLine(2, 8, 6, 12);
                g2.drawLine(6, 12, 13, 3);
            }
        };
    }

    public static Icon redCrossIcon() {
        return new ScaledIcon(16, 16) {
            @Override
            protected void paintLogical(Component c, Graphics2D g2) {
                g2.setColor(new Color(0xB8, 0x14, 0x14));
                g2.setStroke(new BasicStroke(2.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g2.drawLine(3, 3, 12, 12);
                g2.drawLine(12, 3, 3, 12);
            }
        };
    }

    public static Icon cursorToolIcon() {
        return new ScaledIcon(16, 16) {
            @Override
            protected void paintLogical(Component c, Graphics2D g2) {
                Polygon arrow = new Polygon(
                        new int[]{3, 3, 6, 8, 10, 8, 12},
                        new int[]{2, 13, 10, 14, 13, 9, 9},
                        7
                );
                g2.setColor(Color.WHITE);
                g2.fillPolygon(arrow);
                g2.setColor(new Color(0x1A, 0x1A, 0x1A));
                g2.setStroke(new BasicStroke(1.2f));
                g2.drawPolygon(arrow);
            }
        };
    }

    public static Icon lineToolIcon() {
        return new ScaledIcon(16, 16) {
            @Override
            protected void paintLogical(Component c, Graphics2D g2) {
                g2.setColor(new Color(0x1A, 0x1A, 0x1A));
                g2.setStroke(new BasicStroke(1.5f));
                g2.drawLine(2, 13, 13, 2);
                g2.fillRect(1, 12, 3, 3);
                g2.fillRect(12, 1, 3, 3);
            }
        };
    }

    public static Icon shapeToolIcon(GraphicElement.Kind kind, boolean showFlyoutCorner) {
        return new ScaledIcon(16, 16) {
            @Override
            protected void paintLogical(Component c, Graphics2D g2) {
                g2.setColor(new Color(0x1A, 0x1A, 0x1A));
                g2.setStroke(new BasicStroke(1.3f));
                switch (kind) {
                    case RECTANGLE -> g2.drawRect(2, 3, 10, 8);
                    case ROUNDED_RECTANGLE -> g2.drawRoundRect(2, 3, 10, 8, 4, 4);
                    case TRIANGLE -> {
                        Polygon p = new Polygon(new int[]{7, 2, 12}, new int[]{2, 12, 12}, 3);
                        g2.drawPolygon(p);
                    }
                    case PENTAGON -> drawRegularPolygon(g2, 7, 7, 5, 5);
                    case HEXAGON -> drawRegularPolygon(g2, 7, 7, 5, 6);
                    case OCTAGON -> drawRegularPolygon(g2, 7, 7, 5, 8);
                    case CUSTOM_NGON -> {
                        drawRegularPolygon(g2, 7, 7, 5, 7);
                        g2.setFont(new Font("SansSerif", Font.BOLD, 7));
                        g2.drawString("N", 5, 10);
                    }
                    case CIRCLE -> g2.drawOval(2, 2, 10, 10);
                    case ELLIPSE -> g2.drawOval(1, 4, 12, 7);
                    case ARC -> g2.drawArc(2, 2, 10, 10, 30, 220);
                    case SECTOR -> {
                        g2.drawArc(2, 2, 10, 10, 30, 240);
                        g2.drawLine(7, 7, 12, 4);
                        g2.drawLine(7, 7, 7, 12);
                    }
                    default -> g2.drawRect(2, 3, 10, 8);
                }
                if (showFlyoutCorner) {
                    drawFlyoutCorner(g2, 16, 16);
                }
            }
        };
    }

    private static void drawRegularPolygon(Graphics2D g2, int cx, int cy, int r, int sides) {
        int[] xs = new int[sides];
        int[] ys = new int[sides];
        for (int i = 0; i < sides; i++) {
            double angle = -Math.PI / 2 + 2 * Math.PI * i / sides;
            xs[i] = cx + (int) Math.round(r * Math.cos(angle));
            ys[i] = cy + (int) Math.round(r * Math.sin(angle));
        }
        g2.drawPolygon(xs, ys, sides);
    }

    public static Icon textToolIcon() {
        return new ScaledIcon(16, 16) {
            @Override
            protected void paintLogical(Component c, Graphics2D g2) {
                g2.setColor(new Color(0x11, 0x11, 0x11));
                g2.setFont(new Font("Serif", Font.BOLD, 14));
                g2.drawString("T", 3, 13);
            }
        };
    }

    public static Icon visualButtonToolIcon() {
        return new ScaledIcon(16, 16) {
            @Override
            protected void paintLogical(Component c, Graphics2D g2) {
                g2.setColor(new Color(0xDC, 0xE2, 0xE8));
                g2.fillRect(1, 3, 13, 9);
                g2.setColor(new Color(0x22, 0x22, 0x22));
                g2.drawRect(1, 3, 13, 9);
                g2.setColor(new Color(0x00, 0x80, 0x80));
                g2.fillRect(4, 6, 7, 3);
            }
        };
    }

    public static Icon undoIcon() {
        return new ScaledIcon(16, 16) {
            @Override
            protected void paintLogical(Component c, Graphics2D g2) {
                g2.setColor(c.isEnabled() ? new Color(0x1F, 0x4E, 0x79) : new Color(0x99, 0x99, 0x99));
                g2.setStroke(new BasicStroke(1.7f));
                g2.drawArc(4, 4, 9, 8, 0, 180);
                Polygon head = new Polygon(new int[]{2, 6, 6}, new int[]{7, 4, 10}, 3);
                g2.fillPolygon(head);
            }
        };
    }

    public static Icon redoIcon() {
        return new ScaledIcon(16, 16) {
            @Override
            protected void paintLogical(Component c, Graphics2D g2) {
                g2.setColor(c.isEnabled() ? new Color(0x1F, 0x4E, 0x79) : new Color(0x99, 0x99, 0x99));
                g2.setStroke(new BasicStroke(1.7f));
                g2.drawArc(2, 4, 9, 8, 0, 180);
                Polygon head = new Polygon(new int[]{13, 9, 9}, new int[]{7, 4, 10}, 3);
                g2.fillPolygon(head);
            }
        };
    }

    public static Icon smallDownArrowIcon() {
        return new ScaledIcon(8, 16) {
            @Override
            protected void paintLogical(Component c, Graphics2D g2) {
                g2.setColor(c.isEnabled() ? new Color(0x22, 0x22, 0x22) : new Color(0x99, 0x99, 0x99));
                Polygon tri = new Polygon(new int[]{1, 7, 4}, new int[]{6, 6, 10}, 3);
                g2.fillPolygon(tri);
            }
        };
    }

    public static Icon playIcon() {
        return new ScaledIcon(16, 16) {
            @Override
            protected void paintLogical(Component c, Graphics2D g2) {
                g2.setColor(new Color(0x1E, 0x84, 0x49));
                Polygon p = new Polygon(new int[]{3, 3, 13}, new int[]{2, 13, 7}, 3);
                g2.fillPolygon(p);
                g2.setColor(new Color(0x0E, 0x4B, 0x27));
                g2.drawPolygon(p);
            }
        };
    }

    public static Icon pauseIcon() {
        return new ScaledIcon(16, 16) {
            @Override
            protected void paintLogical(Component c, Graphics2D g2) {
                g2.setColor(new Color(0xD4, 0x88, 0x06));
                g2.fillRect(3, 3, 3, 10);
                g2.fillRect(9, 3, 3, 10);
                g2.setColor(new Color(0x7A, 0x4E, 0x00));
                g2.drawRect(3, 3, 3, 10);
                g2.drawRect(9, 3, 3, 10);
            }
        };
    }

    public static Icon stopIcon() {
        return new ScaledIcon(16, 16) {
            @Override
            protected void paintLogical(Component c, Graphics2D g2) {
                g2.setColor(new Color(0xC0, 0x2B, 0x2B));
                g2.fillRect(3, 3, 10, 10);
                g2.setColor(new Color(0x6E, 0x14, 0x14));
                g2.drawRect(3, 3, 10, 10);
            }
        };
    }

    public static Icon sortGraphicallyIcon() {
        return new ScaledIcon(16, 16) {
            @Override
            protected void paintLogical(Component c, Graphics2D g2) {
                g2.setColor(new Color(0x00, 0x80, 0x80));
                g2.fillRect(1, 2, 5, 4);
                g2.fillRect(1, 9, 5, 4);
                g2.fillRect(9, 5, 5, 5);
                g2.setColor(new Color(0x22, 0x22, 0x22));
                g2.drawRect(1, 2, 5, 4);
                g2.drawRect(1, 9, 5, 4);
                g2.drawRect(9, 5, 5, 5);
                g2.drawLine(6, 4, 9, 7);
                g2.drawLine(6, 11, 9, 8);
            }
        };
    }

    public static Icon sortExecutionOrderIcon() {
        return new ScaledIcon(16, 16) {
            @Override
            protected void paintLogical(Component c, Graphics2D g2) {
                g2.setColor(new Color(0x1F, 0x4E, 0x79));
                g2.setFont(new Font("SansSerif", Font.BOLD, 8));
                g2.drawString("1", 1, 7);
                g2.drawString("2", 1, 14);
                g2.setColor(new Color(0x1E, 0x84, 0x49));
                g2.drawLine(7, 4, 14, 4);
                g2.drawLine(11, 2, 14, 4);
                g2.drawLine(11, 6, 14, 4);
                g2.drawLine(7, 11, 14, 11);
                g2.drawLine(11, 9, 14, 11);
                g2.drawLine(11, 13, 14, 11);
            }
        };
    }

    public static Icon compileIcon() {
        return new ScaledIcon(16, 16) {
            @Override
            protected void paintLogical(Component c, Graphics2D g2) {
                g2.setColor(new Color(0x1E, 0x84, 0x49));
                g2.setStroke(new BasicStroke(2.0f));
                g2.drawLine(2, 8, 6, 12);
                g2.drawLine(6, 12, 13, 3);
            }
        };
    }

    public static Icon uploadIcon() {
        return new ScaledIcon(16, 16) {
            @Override
            protected void paintLogical(Component c, Graphics2D g2) {
                g2.setColor(new Color(0x2E, 0x40, 0x53));
                g2.fillRect(2, 1, 11, 6);
                g2.setColor(new Color(0x85, 0xC1, 0xE9));
                g2.drawRect(2, 1, 11, 6);
                g2.setColor(new Color(0x1E, 0x84, 0x49));
                Polygon arrow = new Polygon(new int[]{7, 3, 6, 6, 9, 9, 12}, new int[]{7, 11, 11, 15, 15, 11, 11}, 7);
                g2.fillPolygon(arrow);
            }
        };
    }

    public static Icon watchGlassesIcon() {
        return new ScaledIcon(16, 16) {
            @Override
            protected void paintLogical(Component c, Graphics2D g2) {
                g2.setColor(new Color(0x1A, 0x1A, 0x1A));
                g2.setStroke(new BasicStroke(1.4f));
                g2.drawOval(1, 6, 6, 6);
                g2.drawOval(9, 6, 6, 6);
                g2.drawLine(7, 8, 9, 8);
                g2.drawLine(1, 8, 3, 3);
                g2.drawLine(15, 8, 13, 3);
            }
        };
    }

    public static Icon plcConfigGearIcon() {
        return new ScaledIcon(16, 16) {
            @Override
            protected void paintLogical(Component c, Graphics2D g2) {
                g2.setColor(new Color(0x4A, 0x55, 0x68));
                g2.setStroke(new BasicStroke(1.8f));
                g2.drawOval(4, 4, 7, 7);
                for (int i = 0; i < 8; i++) {
                    double a = i * Math.PI / 4;
                    int x1 = (int) Math.round(7.5 + 3.5 * Math.cos(a));
                    int y1 = (int) Math.round(7.5 + 3.5 * Math.sin(a));
                    int x2 = (int) Math.round(7.5 + 6.0 * Math.cos(a));
                    int y2 = (int) Math.round(7.5 + 6.0 * Math.sin(a));
                    g2.drawLine(x1, y1, x2, y2);
                }
            }
        };
    }

    public static Icon closePanelCrossIcon() {
        return new ScaledIcon(10, 10) {
            @Override
            protected void paintLogical(Component c, Graphics2D g2) {
                g2.setColor(new Color(0x33, 0x33, 0x33));
                g2.setStroke(new BasicStroke(1.5f));
                g2.drawLine(2, 2, 8, 8);
                g2.drawLine(8, 2, 2, 8);
            }
        };
    }

    public static Icon minimizeWindowIcon() {
        return new ScaledIcon(10, 10) {
            @Override
            protected void paintLogical(Component c, Graphics2D g2) {
                g2.setColor(new Color(0x33, 0x33, 0x33));
                g2.setStroke(new BasicStroke(1.8f));
                g2.drawLine(1, 8, 8, 8);
            }
        };
    }

    public static Icon maximizeWindowIcon() {
        return new ScaledIcon(10, 10) {
            @Override
            protected void paintLogical(Component c, Graphics2D g2) {
                g2.setColor(new Color(0x33, 0x33, 0x33));
                g2.setStroke(new BasicStroke(1.2f));
                g2.drawRect(1, 1, 7, 7);
            }
        };
    }

    public static Icon restoreWindowIcon() {
        return new ScaledIcon(10, 10) {
            @Override
            protected void paintLogical(Component c, Graphics2D g2) {
                g2.setColor(new Color(0x33, 0x33, 0x33));
                g2.setStroke(new BasicStroke(1.0f));
                g2.drawRect(3, 1, 5, 5);
                g2.drawRect(1, 3, 5, 5);
            }
        };
    }

    public static Icon ladderAddRungIcon() {
        return new ScaledIcon(18, 16) {
            @Override
            protected void paintLogical(Component c, Graphics2D g2) {
                g2.setColor(new Color(0x1E, 0x29, 0x3B));
                g2.setStroke(new BasicStroke(1.8f));
                g2.drawLine(2, 2, 2, 14);
                g2.drawLine(15, 2, 15, 14);
                g2.setStroke(new BasicStroke(1.2f));
                g2.drawLine(2, 8, 15, 8);
                g2.setColor(new Color(0x1E, 0x84, 0x49));
                g2.setStroke(new BasicStroke(1.8f));
                g2.drawLine(8, 4, 8, 12);
                g2.drawLine(4, 8, 12, 8);
            }
        };
    }

    public static Icon ladderContactIcon(boolean nc, String innerLetter) {
        return new ScaledIcon(20, 16) {
            @Override
            protected void paintLogical(Component c, Graphics2D g2) {
                g2.setColor(new Color(0x1A, 0x1A, 0x1A));
                g2.setStroke(new BasicStroke(1.4f));
                g2.drawLine(1, 8, 6, 8);
                g2.drawLine(13, 8, 18, 8);
                g2.setStroke(new BasicStroke(1.8f));
                g2.drawLine(6, 3, 6, 13);
                g2.drawLine(13, 3, 13, 13);
                if (nc) {
                    g2.setStroke(new BasicStroke(1.4f));
                    g2.drawLine(5, 12, 14, 4);
                } else if (innerLetter != null && !innerLetter.isEmpty()) {
                    g2.setFont(new Font("SansSerif", Font.BOLD, 8));
                    g2.drawString(innerLetter, 7, 11);
                }
            }
        };
    }

    public static Icon ladderCoilIcon(String innerLetter) {
        return new ScaledIcon(20, 16) {
            @Override
            protected void paintLogical(Component c, Graphics2D g2) {
                g2.setColor(new Color(0x1A, 0x1A, 0x1A));
                g2.setStroke(new BasicStroke(1.4f));
                g2.drawLine(1, 8, 5, 8);
                g2.drawLine(14, 8, 18, 8);
                g2.setStroke(new BasicStroke(1.6f));
                g2.drawArc(5, 3, 5, 10, 90, 180);
                g2.drawArc(9, 3, 5, 10, -90, 180);
                if (innerLetter != null && !innerLetter.isEmpty()) {
                    g2.setFont(new Font("SansSerif", Font.BOLD, 8));
                    g2.drawString(innerLetter, 7, 11);
                }
            }
        };
    }

    public static Icon ladderParallelBranchIcon() {
        return new ScaledIcon(20, 16) {
            @Override
            protected void paintLogical(Component c, Graphics2D g2) {
                g2.setColor(new Color(0x1F, 0x61, 0x8D));
                g2.setStroke(new BasicStroke(1.4f));
                g2.drawLine(1, 4, 18, 4);
                g2.drawLine(4, 4, 4, 12);
                g2.drawLine(15, 4, 15, 12);
                g2.drawLine(4, 12, 8, 12);
                g2.drawLine(11, 12, 15, 12);
                g2.drawLine(8, 9, 8, 15);
                g2.drawLine(11, 9, 11, 15);
            }
        };
    }

    public static Icon ladderAnalogCoilIcon() {
        return new ScaledIcon(22, 16) {
            @Override
            protected void paintLogical(Component c, Graphics2D g2) {
                g2.setColor(new Color(0x1F, 0x61, 0x8D));
                g2.setStroke(new BasicStroke(1.3f));
                g2.drawLine(0, 8, 3, 8);
                g2.drawLine(18, 8, 21, 8);
                g2.drawRect(3, 2, 15, 11);
                g2.setFont(new Font("SansSerif", Font.BOLD, 7));
                g2.drawString("AO", 5, 10);
            }
        };
    }

    public static Icon eStopIcon() {
        return new ScaledIcon(16, 16) {
            @Override
            protected void paintLogical(Component c, Graphics2D g2) {
                // Yellow safety collar + Red mushroom head
                g2.setColor(new Color(0xF1, 0xC4, 0x0F));
                g2.fillRect(2, 11, 12, 4);
                g2.setColor(new Color(0x7D, 0x66, 0x08));
                g2.drawRect(2, 11, 12, 4);

                g2.setColor(new Color(0x55, 0x55, 0x55));
                g2.fillRect(6, 8, 4, 3);

                g2.setColor(new Color(0xD3, 0x2F, 0x2F));
                g2.fillArc(1, 1, 14, 12, 0, 180);
                g2.setColor(new Color(0x7F, 0x00, 0x00));
                g2.drawArc(1, 1, 14, 12, 0, 180);
                g2.drawLine(1, 7, 15, 7);
            }
        };
    }

    public static Icon interruptPulseIcon() {
        return new ScaledIcon(16, 16) {
            @Override
            protected void paintLogical(Component c, Graphics2D g2) {
                g2.setColor(new Color(0x8E, 0x24, 0xAA));
                g2.setStroke(new BasicStroke(1.5f));
                g2.drawLine(1, 12, 5, 12);
                g2.drawLine(5, 12, 5, 3);
                g2.drawLine(5, 3, 11, 3);
                g2.drawLine(11, 3, 11, 12);
                g2.drawLine(11, 12, 15, 12);
                // Rising edge arrow on x=5
                g2.drawLine(3, 6, 5, 3);
                g2.drawLine(7, 6, 5, 3);
            }
        };
    }
}
