package cz.maramat.ui.canvas;

import cz.maramat.model.BlockCatalog;
import cz.maramat.model.BlockDefinition;
import cz.maramat.model.BlockInstance;
import cz.maramat.model.DataType;
import cz.maramat.model.IecLanguage;
import cz.maramat.model.LadderElement;
import cz.maramat.model.LadderRung;
import cz.maramat.model.Page;
import cz.maramat.model.Project;
import cz.maramat.model.StVariable;
import cz.maramat.settings.AppSettings;

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
            if (page.isEStopPage()) {
                tab.setBackground(selected ? new Color(0xFF, 0xF5, 0xF5) : new Color(0xF6, 0xDD, 0xE0));
                tab.setForeground(new Color(0x88, 0x0E, 0x18));
            } else {
                tab.setBackground(selected ? Color.WHITE : new Color(0xE0, 0xE6, 0xEE));
                tab.setForeground(new Color(0x11, 0x11, 0x11));
            }
            tab.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

            Color topBarColor;
            if (page.isEStopPage()) {
                topBarColor = new Color(0xC6, 0x28, 0x28);
            } else if (page.getLanguage() == IecLanguage.LD) {
                topBarColor = new Color(0x1F, 0x61, 0x8D);
            } else if (page.getLanguage() == IecLanguage.ST) {
                topBarColor = new Color(0x5E, 0x35, 0xB1);
            } else {
                topBarColor = new Color(0x00, 0x80, 0x80);
            }
            tab.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(selected ? 2 : 0, 0, 0, 1,
                            selected ? topBarColor : new Color(0xB8, 0xB8, 0xB8)),
                    BorderFactory.createEmptyBorder(selected ? 3 : 5, 10, 4, 10)
            ));
            String kindDesc = page.isEStopPage() ? "Dedicated E-Stop Safety Handler - "
                    : (page.isMacro() ? "Macro Page - " : "Top-Level Page - ");
            tab.setToolTipText(kindDesc
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
                    JMenuItem nst = new JMenuItem("New Structured Text (ST) Page...");
                    nst.addActionListener(ev -> addNewPageWithLanguage(false, IecLanguage.ST));
                    m.add(nfbd);
                    m.add(nld);
                    m.add(nst);
                    m.addSeparator();
                    JMenuItem mfbd = new JMenuItem("New FBD Macro (*MacroName)...");
                    mfbd.addActionListener(ev -> addNewPageWithLanguage(true, IecLanguage.FBD));
                    JMenuItem mst = new JMenuItem("New ST Macro (*MacroName)...");
                    mst.addActionListener(ev -> addNewPageWithLanguage(true, IecLanguage.ST));
                    m.add(mfbd);
                    m.add(mst);
                    m.addSeparator();
                    JMenuItem estopItem = new JMenuItem("New Dedicated E-Stop Safety Page (!EStop)...");
                    estopItem.addActionListener(ev -> addDedicatedEStopPage());
                    m.add(estopItem);
                    m.show(addBtn, e.getX(), e.getY());
                }
            }
        });
        add(addBtn);

        revalidate();
        repaint();
    }

    public void addNewPage(boolean macro) {
        IecLanguage defaultLang = macro ? IecLanguage.FBD : ((project != null) ? project.getPrimaryLanguage() : IecLanguage.FBD);
        addNewPageWithLanguage(macro, defaultLang);
    }

    public void addDedicatedEStopPage() {
        if (project == null) return;
        IecLanguage chosen = (IecLanguage) JOptionPane.showInputDialog(
                this,
                "Select IEC 61131-3 language for Dedicated E-Stop Page (!EStop):",
                "Create Dedicated E-Stop Safety Page",
                JOptionPane.QUESTION_MESSAGE,
                null,
                IecLanguage.values(),
                project.getPrimaryLanguage()
        );
        if (chosen == null) return;
        Page p = new Page("!EStop", chosen);
        p.setEStopPage(true);
        if (chosen == IecLanguage.LD) {
            LadderRung r1 = new LadderRung(4, 10, "Dedicated E-Stop Handler: Disable DO0, Energize Fault Beacon DO7");
            r1.getMainElements().add(new LadderElement(LadderElement.Kind.NO_CONTACT, "TRUE"));
            r1.getCoils().add(new LadderElement(LadderElement.Kind.RESET_COIL, "DO0"));
            r1.getCoils().add(new LadderElement(LadderElement.Kind.NORMAL_COIL, "DO7"));
            p.getRungs().add(r1);
        } else if (chosen == IecLanguage.ST) {
            p.setStThreadId(4);
            p.setStOrder(10);
            p.setStCode("""
                    // Dedicated E-Stop Safety Handler (Executes when Mode 2 E-Stop is active)
                    DO0 := FALSE;
                    DO1 := FALSE;
                    DO7 := TRUE; // Energize E-Stop Beacon Output DO7
                    """);
        }
        project.getPages().add(p);
        project.setDirty(true);
        activePageIndex = project.getPages().size() - 1;
        rebuildTabs();
        listener.onPagesStructureChanged();
        listener.onActivePageChanged(activePageIndex);
    }

    public void addNewPageWithLanguage(boolean macro, IecLanguage initialLanguage) {
        if (project == null) return;
        int num = project.getPages().size() + 1;
        String defaultName = macro ? ("*Macro" + num) : ("Page " + num);

        JPanel panel = new JPanel(new GridLayout(2, 2, 6, 6));
        JTextField nameField = new JTextField(defaultName, 18);
        IecLanguage[] allowedLangs = macro
                ? new IecLanguage[]{IecLanguage.FBD, IecLanguage.ST}
                : IecLanguage.values();
        JComboBox<IecLanguage> langCombo = new JComboBox<>(allowedLangs);
        langCombo.setSelectedItem(initialLanguage != null ? initialLanguage : IecLanguage.FBD);

        panel.add(new JLabel(macro ? "Macro Name ('*' prefix):" : "Page Name ('!' for E-Stop):"));
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
        boolean isMacroPage = finalName.startsWith("*");
        IecLanguage chosenLang = (IecLanguage) langCombo.getSelectedItem();
        if (chosenLang == null) chosenLang = IecLanguage.FBD;

        Page p = new Page(finalName, chosenLang);
        if (isMacroPage) {
            if (chosenLang == IecLanguage.ST) {
                p.getStVariables().add(new StVariable(StVariable.Scope.VAR_INPUT, "In1", DataType.INT, "0", "Macro Input 1"));
                p.getStVariables().add(new StVariable(StVariable.Scope.VAR_OUTPUT, "Out1", DataType.INT, "0", "Macro Output 1"));
                p.setStCode("// ST Macro Function Block\nOut1 := In1;\n");
            } else {
                populateDefaultMacroBoundaryBlocks(p);
            }
        } else if (chosenLang == IecLanguage.LD) {
            LadderRung r1 = new LadderRung(project.getDefaultThreadId(), 10, "Rung 1");
            r1.getMainElements().add(new LadderElement(LadderElement.Kind.NO_CONTACT, "DI0"));
            r1.getCoils().add(new LadderElement(LadderElement.Kind.NORMAL_COIL, "DO0"));
            p.getRungs().add(r1);
        } else if (chosenLang == IecLanguage.ST) {
            p.setStThreadId(project.getDefaultThreadId());
            p.setStOrder(10);
            p.getStVariables().add(new StVariable(StVariable.Scope.VAR, "Counter", DataType.INT, "0", "Scan counter"));
            p.setStCode("// IEC 61131-3 Structured Text Page\nCounter := Counter + 1;\n");
        }
        project.getPages().add(p);
        project.setDirty(true);
        BlockCatalog.syncMacrosFromProject(project);
        activePageIndex = project.getPages().size() - 1;
        rebuildTabs();
        listener.onPagesStructureChanged();
        listener.onActivePageChanged(activePageIndex);
    }

    private void populateDefaultMacroBoundaryBlocks(Page macroPage) {
        int tid = project != null ? project.getDefaultThreadId() : 2;
        boolean hasIn = macroPage.getBlocks().stream().anyMatch(b -> "Macro.MacroIn".equals(b.getDefinitionId()));
        boolean hasOut = macroPage.getBlocks().stream().anyMatch(b -> "Macro.MacroOut".equals(b.getDefinitionId()));

        if (!hasIn) {
            BlockDefinition inDef = BlockCatalog.getById("Macro.MacroIn");
            if (inDef != null) {
                BlockInstance bIn = new BlockInstance(inDef, "MacroIn", 12, 14, tid, 10);
                bIn.setHeaderColor(AppSettings.get().getDefaultHeaderColor());
                macroPage.getBlocks().add(bIn);
            }
        }
        if (!hasOut) {
            BlockDefinition outDef = BlockCatalog.getById("Macro.MacroOut");
            if (outDef != null) {
                BlockInstance bOut = new BlockInstance(outDef, "MacroOut", 44, 14, tid, 90);
                bOut.setHeaderColor(AppSettings.get().getDefaultHeaderColor());
                macroPage.getBlocks().add(bOut);
            }
        }
    }

    public void promptRenamePage(int idx) {
        if (idx < 0 || idx >= project.getPages().size()) return;
        Page page = project.getPages().get(idx);
        String oldName = page.getName();
        boolean wasMacro = page.isMacro();
        String input = JOptionPane.showInputDialog(
                this,
                "Rename Page / Macro (prefix with '*' for Macro):",
                oldName
        );
        if (input != null && !input.isBlank()) {
            String newName = input.trim();
            page.setName(newName);
            if (page.isMacro()) {
                if (page.getLanguage() == IecLanguage.FBD) {
                    populateDefaultMacroBoundaryBlocks(page);
                }
                if (wasMacro) {
                    project.renameMacroReferences(oldName, newName);
                }
            }
            project.setDirty(true);
            BlockCatalog.syncMacrosFromProject(project);
            rebuildTabs();
            listener.onPagesStructureChanged();
            listener.onActivePageChanged(activePageIndex);
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

        JMenuItem newStPageItem = new JMenuItem("New Structured Text (ST) Page...");
        newStPageItem.addActionListener(e -> addNewPageWithLanguage(false, IecLanguage.ST));
        menu.add(newStPageItem);

        JMenuItem newMacroItem = new JMenuItem("New Macro (*Macro)...");
        newMacroItem.addActionListener(e -> addNewPage(true));
        menu.add(newMacroItem);

        JMenuItem newEStopItem = new JMenuItem("New Dedicated E-Stop Safety Page (!EStop)...");
        newEStopItem.addActionListener(e -> addDedicatedEStopPage());
        menu.add(newEStopItem);

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
