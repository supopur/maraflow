package cz.maramat.ui.canvas;

import cz.maramat.model.IecLanguage;
import cz.maramat.model.Page;
import cz.maramat.model.Project;
import cz.maramat.settings.AppSettings;
import cz.maramat.sim.SimulationEngine;
import cz.maramat.ui.icons.VectorIcons;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * Popped-out floating window hosting an active PLC Page editor (FBD, LD, or ST),
 * matching the undecorated, gradient-header docking window style of the Oscilloscope
 * and DockableSectionPanel, with 8-way border resizing and Minimize, Maximize/Restore,
 * and Close buttons.
 */
public class FloatingPageWindow {

    private final JDialog dialog;
    private final Page page;
    private final FbdCanvas fbdCanvas;
    private final LadderCanvas ladderCanvas;
    private final StEditorPanel stEditorPanel;
    private final Runnable onCloseCallback;

    private boolean minimized = false;
    private boolean maximized = false;
    private Rectangle normalBounds = null;
    private int restoredHeight = 600;

    private Point dragPressScreenPt = null;
    private Point dragStartWindowLoc = null;

    private final JButton maxBtn;

    public FloatingPageWindow(
            JFrame owner,
            Project project,
            Page page,
            SimulationEngine simEngine,
            FbdCanvas.CanvasEditListener editListener,
            Runnable onCloseCallback
    ) {
        this.page = page;
        this.onCloseCallback = onCloseCallback;
        this.dialog = new JDialog(owner, false);
        this.dialog.setUndecorated(true);

        JPanel root = new JPanel(new BorderLayout());
        root.setBorder(BorderFactory.createLineBorder(new Color(0x33, 0x33, 0x33), 1));

        // 1. Header with Oscilloscope / DockableSectionPanel gradient style
        JPanel header = new JPanel(new BorderLayout()) {
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
        header.setPreferredSize(new Dimension(0, AppSettings.get().scale(24)));
        header.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(0x9A, 0xA6, 0xB4)),
                BorderFactory.createEmptyBorder(1, 8, 1, 4)
        ));

        String langBadge = "[" + page.getLanguage().getDisplayName() + "] ";
        JLabel titleLabel = new JLabel(langBadge + page.getName());
        titleLabel.setFont(new Font("Segoe UI", Font.BOLD, AppSettings.get().scale(11)));
        titleLabel.setForeground(new Color(0x22, 0x2A, 0x35));
        header.add(titleLabel, BorderLayout.WEST);

        // Header Action Buttons: Minimize, Maximize, Close
        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 3, 1));
        btnPanel.setOpaque(false);

        JButton minBtn = new JButton(VectorIcons.minimizeWindowIcon());
        minBtn.setFocusable(false);
        minBtn.setMargin(new Insets(1, 2, 1, 2));
        minBtn.setPreferredSize(new Dimension(AppSettings.get().scale(18), AppSettings.get().scale(18)));
        minBtn.setToolTipText("Minimize / Restore Height");
        minBtn.addActionListener(e -> toggleMinimize());

        maxBtn = new JButton(VectorIcons.maximizeWindowIcon());
        maxBtn.setFocusable(false);
        maxBtn.setMargin(new Insets(1, 2, 1, 2));
        maxBtn.setPreferredSize(new Dimension(AppSettings.get().scale(18), AppSettings.get().scale(18)));
        maxBtn.setToolTipText("Maximize / Restore");
        maxBtn.addActionListener(e -> toggleMaximize());

        JButton closeBtn = new JButton(VectorIcons.closePanelCrossIcon());
        closeBtn.setFocusable(false);
        closeBtn.setMargin(new Insets(1, 2, 1, 2));
        closeBtn.setPreferredSize(new Dimension(AppSettings.get().scale(18), AppSettings.get().scale(18)));
        closeBtn.setToolTipText("Close Window (Return to Dock)");
        closeBtn.addActionListener(e -> closeWindow());

        btnPanel.add(minBtn);
        btnPanel.add(maxBtn);
        btnPanel.add(Box.createHorizontalStrut(2));
        btnPanel.add(closeBtn);
        header.add(btnPanel, BorderLayout.EAST);

        installHeaderDragHandler(header);
        root.add(header, BorderLayout.NORTH);

        // 2. Editor Body based on page language
        JComponent editorComponent;
        if (page.getLanguage() == IecLanguage.LD) {
            ladderCanvas = new LadderCanvas(project, simEngine, editListener);
            ladderCanvas.setForcedPage(page);
            fbdCanvas = null;
            stEditorPanel = null;
            JScrollPane scroll = new JScrollPane(ladderCanvas);
            scroll.setBorder(null);
            scroll.getVerticalScrollBar().setUnitIncrement(16);
            scroll.getHorizontalScrollBar().setUnitIncrement(16);
            editorComponent = scroll;
        } else if (page.getLanguage() == IecLanguage.ST) {
            stEditorPanel = new StEditorPanel(project, page, () -> {});
            stEditorPanel.setMacroNavigator(editListener::onInspectMacroPage);
            fbdCanvas = null;
            ladderCanvas = null;
            editorComponent = stEditorPanel;
        } else {
            fbdCanvas = new FbdCanvas(project, simEngine, editListener);
            fbdCanvas.setForcedPage(page);
            ladderCanvas = null;
            stEditorPanel = null;
            JScrollPane scroll = new JScrollPane(fbdCanvas);
            scroll.setBorder(null);
            scroll.getVerticalScrollBar().setUnitIncrement(16);
            scroll.getHorizontalScrollBar().setUnitIncrement(16);
            editorComponent = scroll;
        }

        root.add(editorComponent, BorderLayout.CENTER);
        installBorderResizeHandler(dialog, root);

        dialog.setContentPane(root);
        dialog.setSize(AppSettings.get().scale(780), AppSettings.get().scale(540));
    }

    public Page getPage() {
        return page;
    }

    public JDialog getDialog() {
        return dialog;
    }

    public void setLocation(int x, int y) {
        dialog.setLocation(x, y);
    }

    public void setVisible(boolean visible) {
        dialog.setVisible(visible);
        if (visible) {
            dialog.toFront();
            dialog.requestFocus();
        }
    }

    public void refreshSimulationState() {
        if (fbdCanvas != null) {
            fbdCanvas.repaint();
        }
        if (ladderCanvas != null) {
            ladderCanvas.repaint();
        }
        if (stEditorPanel != null) {
            stEditorPanel.refreshRuntimeValues();
        }
    }

    public void closeWindow() {
        dialog.setVisible(false);
        dialog.dispose();
        if (onCloseCallback != null) {
            onCloseCallback.run();
        }
    }

    private void toggleMinimize() {
        if (minimized) {
            dialog.setSize(dialog.getWidth(), Math.max(AppSettings.get().scale(150), restoredHeight));
            minimized = false;
        } else {
            restoredHeight = dialog.getHeight();
            dialog.setSize(dialog.getWidth(), AppSettings.get().scale(26));
            minimized = true;
        }
        dialog.revalidate();
    }

    private void toggleMaximize() {
        if (maximized) {
            if (normalBounds != null) {
                dialog.setBounds(normalBounds);
            } else {
                dialog.setSize(AppSettings.get().scale(780), AppSettings.get().scale(540));
            }
            maximized = false;
            maxBtn.setIcon(VectorIcons.maximizeWindowIcon());
        } else {
            normalBounds = dialog.getBounds();
            Rectangle maxBounds = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
            dialog.setBounds(maxBounds);
            maximized = true;
            minimized = false;
            maxBtn.setIcon(VectorIcons.restoreWindowIcon());
        }
        dialog.revalidate();
    }

    private void installHeaderDragHandler(JPanel header) {
        header.setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
        MouseAdapter ma = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (!SwingUtilities.isLeftMouseButton(e)) return;
                dragPressScreenPt = e.getLocationOnScreen();
                dragStartWindowLoc = dialog.getLocation();
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (dragPressScreenPt == null || dragStartWindowLoc == null) return;
                Point cur = e.getLocationOnScreen();
                int nx = dragStartWindowLoc.x + (cur.x - dragPressScreenPt.x);
                int ny = dragStartWindowLoc.y + (cur.y - dragPressScreenPt.y);
                dialog.setLocation(nx, ny);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                dragPressScreenPt = null;
                dragStartWindowLoc = null;
            }
        };
        header.addMouseListener(ma);
        header.addMouseMotionListener(ma);
    }

    private void installBorderResizeHandler(JDialog dlg, JComponent wrapper) {
        final int BORDER = 6;
        MouseAdapter resizer = new MouseAdapter() {
            private int resizeDir = Cursor.DEFAULT_CURSOR;
            private Point startPt = null;
            private Rectangle startBounds = null;

            @Override
            public void mouseMoved(MouseEvent e) {
                if (maximized) {
                    wrapper.setCursor(Cursor.getDefaultCursor());
                    return;
                }
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
                if (maximized) return;
                if (resizeDir != Cursor.DEFAULT_CURSOR) {
                    startPt = e.getLocationOnScreen();
                    startBounds = dlg.getBounds();
                }
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (startPt == null || startBounds == null || resizeDir == Cursor.DEFAULT_CURSOR || maximized) return;
                Point cur = e.getLocationOnScreen();
                int dx = cur.x - startPt.x;
                int dy = cur.y - startPt.y;

                int x = startBounds.x;
                int y = startBounds.y;
                int w = startBounds.width;
                int h = startBounds.height;
                int minW = 280;
                int minH = 180;

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
                    case Cursor.NW_RESIZE_CURSOR -> {
                        int nw = Math.max(minW, w - dx);
                        int nh = Math.max(minH, h - dy);
                        x += (w - nw);
                        y += (h - nh);
                        w = nw;
                        h = nh;
                    }
                    case Cursor.NE_RESIZE_CURSOR -> {
                        int nh = Math.max(minH, h - dy);
                        y += (h - nh);
                        w = Math.max(minW, w + dx);
                        h = nh;
                    }
                    case Cursor.SW_RESIZE_CURSOR -> {
                        int nw = Math.max(minW, w - dx);
                        x += (w - nw);
                        w = nw;
                        h = Math.max(minH, h + dy);
                    }
                    default -> {}
                }
                dlg.setBounds(x, y, w, h);
                dlg.revalidate();
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
}
