package cz.maramat;

import cz.maramat.settings.AppSettings;
import cz.maramat.ui.AppIcon;
import cz.maramat.ui.DisplayWakeRepaintFix;
import cz.maramat.ui.MainWindow;

import javax.swing.SwingUtilities;

public class Main {

    public static void main(String[] args) {
        System.setProperty("apple.awt.application.name", "MaraFlow");
        AppIcon.applyDockIcon();
        AppSettings.get().applyLookAndFeel();

        SwingUtilities.invokeLater(() -> {
            DisplayWakeRepaintFix.install();
            MainWindow window = new MainWindow();
            window.setVisible(true);
        });
    }
}
