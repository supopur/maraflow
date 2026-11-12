package cz.maramat;

import com.formdev.flatlaf.FlatLightLaf;
import cz.maramat.ui.AppIcon;
import cz.maramat.ui.MainWindow;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Font;
import java.awt.Insets;

public class Main {

    public static void main(String[] args) {
        System.setProperty("apple.awt.application.name", "MaraFlow");
        AppIcon.applyDockIcon();
        FlatLightLaf.setup();

        // Utilitarian Win95 / Win7 crossover CAD / PLC engineering look-and-feel
        UIManager.put("Component.arrowType", "triangle");
        UIManager.put("Button.arc", 0);
        UIManager.put("Component.arc", 0);
        UIManager.put("CheckBox.arc", 0);
        UIManager.put("ProgressBar.arc", 0);
        UIManager.put("TextComponent.arc", 0);
        UIManager.put("ScrollBar.width", 14);
        UIManager.put("ScrollBar.showButtons", true);
        UIManager.put("TabbedPane.tabHeight", 22);
        UIManager.put("TabbedPane.showTabSeparators", true);
        UIManager.put("TabbedPane.tabInsets", new Insets(2, 8, 2, 8));
        UIManager.put("TabbedPane.selectedBackground", Color.WHITE);
        UIManager.put("Tree.paintLines", true);
        UIManager.put("Tree.lineTypeDashed", true);
        UIManager.put("Tree.hash", new Color(0x90, 0x90, 0x90));
        UIManager.put("Table.showHorizontalLines", true);
        UIManager.put("Table.showVerticalLines", true);

        Font uiFont = cz.maramat.ui.AppFonts.ui(Font.PLAIN, 11);
        UIManager.put("defaultFont", uiFont);

        SwingUtilities.invokeLater(() -> {
            MainWindow window = new MainWindow();
            window.setVisible(true);
        });
    }
}
