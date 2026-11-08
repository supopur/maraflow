package cz.maramat.ui.canvas;

import cz.maramat.model.BlockCatalog;
import cz.maramat.model.IecLanguage;
import cz.maramat.model.LadderElement;
import cz.maramat.model.LadderRung;
import cz.maramat.model.Page;
import cz.maramat.model.Project;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

public class PageTabBar extends JPanel {

    public interface PageTabListener {
        void onActivePageChanged(int pageIndex);
        void onPagesStructureChanged();
    }

    private Project project;
    private int activePageIndex = 0;
    private final PageTabListener listener;

    public PageTabBar(Project project, PageTabListener listener) {
        super(new FlowLayout(FlowLayout.LEFT, 0, 0));
        this.project = project;
        this.listener = listener;
        setBackground(new Color(0xEC, 0xF0, 0xF5));
        setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(0xB8, 0xB8, 0xB8)));
        rebuildTabs();
    }

    public void setProject(Project project) {
        this.project = project;
        this.activePageIndex = 0;
        rebuildTabs();
    }

    public int getActivePageIndex() {
        return activePageIndex;
    }

    public void setActivePageIndex(int index) {
        if (project == null || project.getPages().isEmpty()) return;
        this.activePageIndex = Math.clamp(index, 0, project.getPages().size() - 1);
        rebuildTabs();
        listener.onActivePageChanged(this.activePageIndex);
    }

    public void rebuildTabs() {
        removeAll();
        if (project == null) return;

        Font normalFont = cz.maramat.ui.AppFonts.ui(Font.PLAIN, 11);
        Font italicFont = cz.maramat.ui.AppFonts.ui(Font.ITALIC, 11);

        for (int i = 0; i < project.getPages().size(); i++) {
            final int idx = i;
            Page page = project.getPages().get(i);
            boolean selected = (i == activePageIndex);

            String badge = "[" + page.getLanguage().getShortCode() + "] ";
            JLabel tab = new JLabel(badge + page.getName());
            tab.setOpaque(true);
            tab.setFont(page.isMacro() ? italicFont : normalFont);
            tab.setBackground(selected ? Color.WHITE : new Color(0xE0, 0xE6, 0xEE));
            tab.setForeground(new Color(0x11, 0x11, 0x11));
            tab.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

            Color topBarColor = (page.getLanguage() == IecLanguage.LD)
                    ? new Color(0x1F, 0x61, 0x8D)
                    : new Color(0x00, 0x80, 0x80);
            tab.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(selected ? 2 : 0, 0, 0, 1,
                            selected ? topBarColor : new Color(0xB8, 0xB8, 0xB8)),
                    BorderFactory.createEmptyBorder(selected ? 3 : 5, 10, 4, 10)
            ));
            tab.setToolTipText((page.isMacro() ? "Macro Page - " : "Top-Level Page - ")
                    + page.getLanguage().getDisplayName()
                    + " (Double-click to rename, Right-click for options)");

            tab.addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    if (SwingUtilities.isRightMouseButton(e)) {
                        setActivePageIndex(idx);
                        showTabContextMenu(tab, idx, e.getX(), e.getY());
                    } else if (SwingUtilities.isLeftMouseButton(e)) {
                        if (e.getClickCount() == 2) {
                            promptRenamePage(idx);
                        } else if (idx != activePageIndex) {
                            setActivePageIndex(idx);
                        }
                    }
                }
            });
            add(tab);
        }

        // Quick '+' button to add Page / Macro
        JButton addBtn = new JButton("+");
        addBtn.setFocusable(false);
        addBtn.setMargin(new Insets(1, 6, 1, 6));
        addBtn.setPreferredSize(new Dimension(24, 22));
        addBtn.setToolTipText("Add Page or Macro (Left-click for dialog, Right-click for quick FBD/LD options)");
        addBtn.addActionListener(e -> addNewPage(false));
        addBtn.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (SwingUtilities.isRightMouseButton(e)) {
                    JPopupMenu m = new JPopupMenu();
                    JMenuItem nfbd = new JMenuItem("New FBD Page...");
                    nfbd.addActionListener(ev -> addNewPageWithLanguage(false, IecLanguage.FBD));
                    JMenuItem nld = new JMenuItem("New Ladder (LD) Page...");
                    nld.addActionListener(ev -> addNewPageWithLanguage(false, IecLanguage.LD));
                    m.add(nfbd);
                    m.add(nld);
                    m.addSeparator();
                    JMenuItem mfbd = new JMenuItem("New FBD Macro (*MacroName)...");
                    mfbd.addActionListener(ev -> addNewPageWithLanguage(true, IecLanguage.FBD));
                    JMenuItem mld = new JMenuItem("New Ladder (LD) Macro (*MacroName)...");
                    mld.addActionListener(ev -> addNewPageWithLanguage(true, IecLanguage.LD));
                    m.add(mfbd);
                    m.add(mld);
                    m.show(addBtn, e.getX(), e.getY());
                }
            }
        });
        add(addBtn);

        revalidate();
        repaint();
    }

    public void addNewPage(boolean macro) {
        IecLanguage defaultLang = (project != null) ? project.getPrimaryLanguage() : IecLanguage.FBD;
        addNewPageWithLanguage(macro, defaultLang);
    }

    public void addNewPageWithLanguage(boolean macro, IecLanguage initialLanguage) {
        if (project == null) return;
        int num = project.getPages().size() + 1;
        String defaultName = macro ? ("*Macro" + num) : ("Page " + num);

        JPanel panel = new JPanel(new GridLayout(2, 2, 6, 6));
        JTextField nameField = new JTextField(defaultName, 18);
        JComboBox<IecLanguage> langCombo = new JComboBox<>(IecLanguage.values());
        langCombo.setSelectedItem(initialLanguage != null ? initialLanguage : IecLanguage.FBD);

        panel.add(new JLabel(macro ? "Macro Name ('*' prefix):" : "Page Name:"));
        panel.add(nameField);
        panel.add(new JLabel("IEC 61131-3 Language:"));
        panel.add(langCombo);

        int res = JOptionPane.showConfirmDialog(
                this,
                panel,
                macro ? "Create New Macro" : "Create New Page",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE
        );
        if (res != JOptionPane.OK_OPTION) return;

        String input = nameField.getText();
        if (input == null || input.isBlank()) return;
        String finalName = input.trim();
        if (macro && !finalName.startsWith("*")) {
            finalName = "*" + finalName;
        }
        IecLanguage chosenLang = (IecLanguage) langCombo.getSelectedItem();
        if (chosenLang == null) chosenLang = IecLanguage.FBD;

        Page p = new Page(finalName, chosenLang);
        if (chosenLang == IecLanguage.LD) {
            LadderRung r1 = new LadderRung(project.getDefaultThreadId(), 10, "Rung 1");
            r1.getMainElements().add(new LadderElement(LadderElement.Kind.NO_CONTACT, "DI0"));
            r1.getCoils().add(new LadderElement(LadderElement.Kind.NORMAL_COIL, "DO0"));
            p.getRungs().add(r1);
        }
        project.getPages().add(p);
        project.setDirty(true);
        BlockCatalog.syncMacrosFromProject(project);
        activePageIndex = project.getPages().size() - 1;
        rebuildTabs();
        listener.onPagesStructureChanged();
        listener.onActivePageChanged(activePageIndex);
    }

    public void promptRenamePage(int idx) {
        if (idx < 0 || idx >= project.getPages().size()) return;
        Page page = project.getPages().get(idx);
        String input = JOptionPane.showInputDialog(
                this,
                "Rename Page / Macro (prefix with '*' for Macro):",
                page.getName()
        );
        if (input != null && !input.isBlank()) {
            page.setName(input.trim());
            project.setDirty(true);
            BlockCatalog.syncMacrosFromProject(project);
            rebuildTabs();
            listener.onPagesStructureChanged();
        }
    }

    private void showTabContextMenu(JLabel tabComp, int idx, int x, int y) {
        JPopupMenu menu = new JPopupMenu();

        JMenuItem newFbdPageItem = new JMenuItem("New FBD Page...");
        newFbdPageItem.addActionListener(e -> addNewPageWithLanguage(false, IecLanguage.FBD));
        menu.add(newFbdPageItem);

        JMenuItem newLdPageItem = new JMenuItem("New Ladder (LD) Page...");
        newLdPageItem.addActionListener(e -> addNewPageWithLanguage(false, IecLanguage.LD));
        menu.add(newLdPageItem);

        JMenuItem newMacroItem = new JMenuItem("New Macro (*Macro)...");
        newMacroItem.addActionListener(e -> addNewPage(true));
        menu.add(newMacroItem);

        menu.addSeparator();

        JMenuItem renameItem = new JMenuItem("Rename...");
        renameItem.addActionListener(e -> promptRenamePage(idx));
        menu.add(renameItem);

        JMenuItem dupItem = new JMenuItem("Duplicate");
        dupItem.addActionListener(e -> {
            Page src = project.getPages().get(idx);
            Page copy = src.copy();
            copy.setName(src.getName() + "_Copy");
            project.getPages().add(idx + 1, copy);
            project.setDirty(true);
            BlockCatalog.syncMacrosFromProject(project);
            setActivePageIndex(idx + 1);
            listener.onPagesStructureChanged();
        });
        menu.add(dupItem);

        JMenuItem moveLeftItem = new JMenuItem("Move Left");
        moveLeftItem.setEnabled(idx > 0);
        moveLeftItem.addActionListener(e -> {
            Page p = project.getPages().remove(idx);
            project.getPages().add(idx - 1, p);
            project.setDirty(true);
            setActivePageIndex(idx - 1);
        });
        menu.add(moveLeftItem);

        JMenuItem moveRightItem = new JMenuItem("Move Right");
        moveRightItem.setEnabled(idx < project.getPages().size() - 1);
        moveRightItem.addActionListener(e -> {
            Page p = project.getPages().remove(idx);
            project.getPages().add(idx + 1, p);
            project.setDirty(true);
            setActivePageIndex(idx + 1);
        });
        menu.add(moveRightItem);

        menu.addSeparator();

        JMenuItem delItem = new JMenuItem("Delete Page");
        delItem.setEnabled(project.getPages().size() > 1);
        delItem.addActionListener(e -> {
            if (project.getPages().size() <= 1) return;
            project.getPages().remove(idx);
            project.setDirty(true);
            BlockCatalog.syncMacrosFromProject(project);
            setActivePageIndex(Math.min(activePageIndex, project.getPages().size() - 1));
            listener.onPagesStructureChanged();
        });
        menu.add(delItem);

        menu.show(tabComp, x, y);
    }
}
