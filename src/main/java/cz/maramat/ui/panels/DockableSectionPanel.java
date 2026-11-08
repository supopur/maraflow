package cz.maramat.ui.panels;

import cz.maramat.settings.AppSettings;
import cz.maramat.ui.icons.VectorIcons;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

public class DockableSectionPanel extends JPanel {
    private final JLabel titleLabel;
    private final JPanel header;
    private final boolean popOutable;
    private Runnable onCloseClicked;
    private Runnable onUndockCallback;
    private Runnable onRedockCallback;

    private JDialog floatingDialog = null;
    private Point dragPressScreenPt = null;
    private Point floatingStartLoc = null;
    private boolean poppedOutDuringThisDrag = false;

    public DockableSectionPanel(String title, JComponent content, Runnable onCloseClicked) {
        this(title, content, onCloseClicked, false, null, null);
    }

    public DockableSectionPanel(
            String title,
            JComponent content,
            Runnable onCloseClicked,
            boolean popOutable,
            Runnable onUndockCallback,
            Runnable onRedockCallback
    ) {
        super(new BorderLayout());
        this.onCloseClicked = onCloseClicked;
        this.popOutable = popOutable;
        this.onUndockCallback = onUndockCallback;
        this.onRedockCallback = onRedockCallback;

        setBorder(BorderFactory.createLineBorder(new Color(0x9A, 0xA6, 0xB4), 1));

        header = new JPanel(new BorderLayout()) {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2 = (Graphics2D) g;
                GradientPaint gp = new GradientPaint(
                        0, 0, new Color(0xE8, 0xEE, 0xF5),
                        0, getHeight(), new Color(0xCD, 0xD7, 0xE4)
                );
                g2.setPaint(gp);
                g2.fillRect(0, 0, getWidth(), getHeight());
            }
        };
        header.setPreferredSize(new Dimension(0, AppSettings.get().scale(22)));
        header.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(0x9A, 0xA6, 0xB4)),
                BorderFactory.createEmptyBorder(1, 6, 1, 3)
        ));

        titleLabel = new JLabel(title);
        titleLabel.setFont(new Font("Segoe UI", Font.BOLD, AppSettings.get().scale(11)));
        titleLabel.setForeground(new Color(0x22, 0x2A, 0x35));
        header.add(titleLabel, BorderLayout.WEST);

        if (onCloseClicked != null) {
            JButton closeBtn = new JButton(VectorIcons.closePanelCrossIcon());
            closeBtn.setFocusable(false);
            closeBtn.setMargin(new Insets(1, 3, 1, 3));
            closeBtn.setPreferredSize(new Dimension(AppSettings.get().scale(18), AppSettings.get().scale(16)));
            closeBtn.setToolTipText("Close Panel (Reopen from Window menu)");
            closeBtn.addActionListener(e -> closeSection());
            header.add(closeBtn, BorderLayout.EAST);
        }

        if (popOutable) {
            header.setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
            header.setToolTipText("Drag header out to pop out window; drag back into main window to re-dock");
            installHeaderDragHandlers();
        }

        add(header, BorderLayout.NORTH);
        add(content, BorderLayout.CENTER);
    }

    public void setDockingCallbacks(Runnable onUndock, Runnable onRedock) {
        this.onUndockCallback = onUndock;
        this.onRedockCallback = onRedock;
    }

    public boolean isFloating() {
        return floatingDialog != null && floatingDialog.isVisible();
    }

    public void closeSection() {
        if (floatingDialog != null) {
            floatingDialog.setVisible(false);
            floatingDialog.getContentPane().removeAll();
            floatingDialog.dispose();
            floatingDialog = null;
        }
        if (onCloseClicked != null) {
            onCloseClicked.run();
        }
    }

    public void redockToMainWindow() {
        if (floatingDialog != null) {
            floatingDialog.setVisible(false);
            floatingDialog.getContentPane().remove(this);
            floatingDialog.dispose();
            floatingDialog = null;
        }
        if (onRedockCallback != null) {
            onRedockCallback.run();
        }
    }

    private void installHeaderDragHandlers() {
        MouseAdapter ma = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (!SwingUtilities.isLeftMouseButton(e)) return;
                dragPressScreenPt = e.getLocationOnScreen();
                poppedOutDuringThisDrag = false;
                if (floatingDialog != null) {
                    floatingStartLoc = floatingDialog.getLocation();
                }
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (dragPressScreenPt == null) return;
                Point curScreen = e.getLocationOnScreen();

                if (floatingDialog == null) {
                    double dist = dragPressScreenPt.distance(curScreen);
                    if (dist > 28) {
                        popOutIntoBorderlessWindow(curScreen);
                        poppedOutDuringThisDrag = true;
                    }
                } else {
                    int nx = floatingStartLoc.x + (curScreen.x - dragPressScreenPt.x);
                    int ny = floatingStartLoc.y + (curScreen.y - dragPressScreenPt.y);
                    floatingDialog.setLocation(nx, ny);
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (floatingDialog != null && !poppedOutDuringThisDrag) {
                    // If user dragged an already-floating window back over the main window's edge/interior, re-dock it!
                    Window owner = floatingDialog.getOwner();
                    if (owner != null && owner.isShowing()) {
                        Rectangle mainBounds = owner.getBounds();
                        Point releasePt = e.getLocationOnScreen();
                        if (mainBounds.contains(releasePt)) {
                            redockToMainWindow();
                        }
                    }
                }
                dragPressScreenPt = null;
                poppedOutDuringThisDrag = false;
            }
        };
        header.addMouseListener(ma);
        header.addMouseMotionListener(ma);
    }

    private void popOutIntoBorderlessWindow(Point cursorScreenPt) {
        Window ancestor = SwingUtilities.getWindowAncestor(this);
        JFrame ownerFrame = (ancestor instanceof JFrame jf) ? jf : null;

        int w = Math.max(260, getWidth());
        int h = Math.max(220, getHeight());

        if (onUndockCallback != null) {
            onUndockCallback.run();
        }

        floatingDialog = new JDialog(ownerFrame, false);
        floatingDialog.setUndecorated(true);

        JPanel resizeWrapper = new JPanel(new BorderLayout());
        resizeWrapper.setBorder(BorderFactory.createLineBorder(new Color(0x33, 0x33, 0x33), 1));
        resizeWrapper.add(this, BorderLayout.CENTER);
        installBorderResizeHandler(floatingDialog, resizeWrapper);

        floatingDialog.setContentPane(resizeWrapper);
        floatingDialog.setSize(w, h);

        int startX = cursorScreenPt.x - Math.min(100, w / 3);
        int startY = cursorScreenPt.y - 10;
        floatingDialog.setLocation(startX, startY);
        floatingStartLoc = new Point(startX, startY);
        dragPressScreenPt = new Point(cursorScreenPt);

        floatingDialog.setVisible(true);
    }

    private void installBorderResizeHandler(JDialog dialog, JComponent wrapper) {
        final int BORDER = 6;
        MouseAdapter resizer = new MouseAdapter() {
            private int resizeDir = Cursor.DEFAULT_CURSOR;
            private Point startPt = null;
            private Rectangle startBounds = null;

            @Override
            public void mouseMoved(MouseEvent e) {
                int x = e.getX();
                int y = e.getY();
                int w = wrapper.getWidth();
                int h = wrapper.getHeight();

                boolean left = x < BORDER;
                boolean right = x > w - BORDER;
                boolean top = y < BORDER;
                boolean bottom = y > h - BORDER;

                if (top && left) resizeDir = Cursor.NW_RESIZE_CURSOR;
                else if (top && right) resizeDir = Cursor.NE_RESIZE_CURSOR;
                else if (bottom && left) resizeDir = Cursor.SW_RESIZE_CURSOR;
                else if (bottom && right) resizeDir = Cursor.SE_RESIZE_CURSOR;
                else if (left) resizeDir = Cursor.W_RESIZE_CURSOR;
                else if (right) resizeDir = Cursor.E_RESIZE_CURSOR;
                else if (top) resizeDir = Cursor.N_RESIZE_CURSOR;
                else if (bottom) resizeDir = Cursor.S_RESIZE_CURSOR;
                else resizeDir = Cursor.DEFAULT_CURSOR;

                wrapper.setCursor(Cursor.getPredefinedCursor(resizeDir));
            }

            @Override
            public void mousePressed(MouseEvent e) {
                if (resizeDir != Cursor.DEFAULT_CURSOR) {
                    startPt = e.getLocationOnScreen();
                    startBounds = dialog.getBounds();
                }
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (startPt == null || startBounds == null || resizeDir == Cursor.DEFAULT_CURSOR) return;
                Point cur = e.getLocationOnScreen();
                int dx = cur.x - startPt.x;
                int dy = cur.y - startPt.y;

                int x = startBounds.x;
                int y = startBounds.y;
                int w = startBounds.width;
                int h = startBounds.height;
                int minW = 200;
                int minH = 150;

                switch (resizeDir) {
                    case Cursor.E_RESIZE_CURSOR -> w = Math.max(minW, w + dx);
                    case Cursor.S_RESIZE_CURSOR -> h = Math.max(minH, h + dy);
                    case Cursor.SE_RESIZE_CURSOR -> {
                        w = Math.max(minW, w + dx);
                        h = Math.max(minH, h + dy);
                    }
                    case Cursor.W_RESIZE_CURSOR -> {
                        int nw = Math.max(minW, w - dx);
                        x += (w - nw);
                        w = nw;
                    }
                    case Cursor.N_RESIZE_CURSOR -> {
                        int nh = Math.max(minH, h - dy);
                        y += (h - nh);
                        h = nh;
                    }
                    default -> {}
                }
                dialog.setBounds(x, y, w, h);
                dialog.revalidate();
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                startPt = null;
                startBounds = null;
            }
        };
        wrapper.addMouseListener(resizer);
        wrapper.addMouseMotionListener(resizer);
    }

    public void setTitleText(String title) {
        titleLabel.setText(title);
    }
}
