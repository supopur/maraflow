package cz.maramat.ui;

import javax.swing.JRootPane;
import javax.swing.RepaintManager;
import javax.swing.RootPaneContainer;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.AWTEvent;
import java.awt.Desktop;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.desktop.AppForegroundEvent;
import java.awt.desktop.AppForegroundListener;
import java.awt.desktop.ScreenSleepEvent;
import java.awt.desktop.ScreenSleepListener;
import java.awt.desktop.SystemSleepEvent;
import java.awt.desktop.SystemSleepListener;
import java.awt.desktop.UserSessionEvent;
import java.awt.desktop.UserSessionListener;
import java.awt.event.WindowEvent;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Forces a full Swing repaint of all visible windows after macOS sleep/wake,
 * display wake, session unlock, or window focus regain.
 *
 * <p>On macOS, the Java2D Metal pipeline can lose or clear its backing surface
 * across lid-close sleep without invalidating Swing's {@link RepaintManager},
 * leaving windows black until individual components are hovered.
 */
public final class DisplayWakeRepaintFix {

    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);

    private DisplayWakeRepaintFix() {}

    public static void install() {
        if (!INSTALLED.compareAndSet(false, true)) {
            return;
        }

        if (Desktop.isDesktopSupported()) {
            Desktop desktop = Desktop.getDesktop();
            tryAddListener(desktop, new SystemSleepListener() {
                @Override
                public void systemAboutToSleep(SystemSleepEvent e) {}

                @Override
                public void systemAwoke(SystemSleepEvent e) {
                    scheduleWakeRepaint();
                }
            });
            tryAddListener(desktop, new ScreenSleepListener() {
                @Override
                public void screenAboutToSleep(ScreenSleepEvent e) {}

                @Override
                public void screenAwoke(ScreenSleepEvent e) {
                    scheduleWakeRepaint();
                }
            });
            tryAddListener(desktop, new UserSessionListener() {
                @Override
                public void userSessionDeactivated(UserSessionEvent e) {}

                @Override
                public void userSessionActivated(UserSessionEvent e) {
                    scheduleWakeRepaint();
                }
            });
            tryAddListener(desktop, new AppForegroundListener() {
                @Override
                public void appRaisedToForeground(AppForegroundEvent e) {
                    scheduleWakeRepaint();
                }

                @Override
                public void appMovedToBackground(AppForegroundEvent e) {}
            });
        }

        try {
            Toolkit.getDefaultToolkit().addAWTEventListener(event -> {
                if (event instanceof WindowEvent we && we.getID() == WindowEvent.WINDOW_GAINED_FOCUS) {
                    Window w = we.getWindow();
                    if (w != null) {
                        SwingUtilities.invokeLater(() -> repaintWindow(w));
                    }
                }
            }, AWTEvent.WINDOW_EVENT_MASK);
        } catch (SecurityException ignored) {
            // Restricted toolkit access
        }
    }

    private static void tryAddListener(Desktop desktop, java.awt.desktop.SystemEventListener listener) {
        try {
            desktop.addAppEventListener(listener);
        } catch (UnsupportedOperationException | SecurityException ignored) {
            // Listener not supported on this platform
        }
    }

    private static void scheduleWakeRepaint() {
        SwingUtilities.invokeLater(() -> {
            repaintAllWindows();
            // Follow-up repaints in case the OS display surface finishes waking slightly after the event
            scheduleDelayedRepaint(250);
            scheduleDelayedRepaint(800);
        });
    }

    private static void scheduleDelayedRepaint(int delayMs) {
        Timer timer = new Timer(delayMs, e -> repaintAllWindows());
        timer.setRepeats(false);
        timer.start();
    }

    public static void repaintAllWindows() {
        for (Window window : Window.getWindows()) {
            repaintWindow(window);
        }
        Toolkit.getDefaultToolkit().sync();
    }

    private static void repaintWindow(Window window) {
        if (window == null || !window.isDisplayable() || !window.isShowing()) {
            return;
        }
        if (window instanceof RootPaneContainer rpc) {
            JRootPane root = rpc.getRootPane();
            if (root != null) {
                RepaintManager.currentManager(root).markCompletelyDirty(root);
                root.repaint();
            }
        }
        window.repaint();
    }
}
