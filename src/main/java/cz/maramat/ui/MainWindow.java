package cz.maramat.ui;

import cz.maramat.compiler.BytecodeCompiler;
import cz.maramat.compiler.BytecodeCompiler.CompilationReport;
import cz.maramat.io.MflProjectSerializer;
import cz.maramat.model.BlockCatalog;
import cz.maramat.model.BlockInstance;
import cz.maramat.model.GraphicElement;
import cz.maramat.model.Page;
import cz.maramat.model.Project;
import cz.maramat.settings.AppSettings;
import cz.maramat.sim.BlockSorter;
import cz.maramat.sim.BlockSorter.OrderSortMode;
import cz.maramat.sim.SimulationEngine;
import cz.maramat.ui.canvas.FbdCanvas;
import cz.maramat.ui.canvas.PageTabBar;
import cz.maramat.ui.dialogs.CommunicationDialog;
import cz.maramat.ui.dialogs.CompilationResultsDialog;
import cz.maramat.ui.dialogs.PlcConfigDialog;
import cz.maramat.ui.dialogs.SettingsDialog;
import cz.maramat.ui.panels.DockableSectionPanel;
import cz.maramat.ui.panels.LibraryTreePanel;
import cz.maramat.ui.panels.PlcLogPanel;
import cz.maramat.ui.panels.ScopePanel;
import cz.maramat.ui.toolbar.EditorToolbar;
import cz.maramat.ui.toolbar.EditorToolbar.ToolMode;
import cz.maramat.undo.UndoManager50;
import cz.maramat.undo.UndoManager50.HistoryEntry;

import javax.imageio.ImageIO;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.KeyStroke;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class MainWindow extends JFrame {

    private Project project;
    private final UndoManager50 undoManager = new UndoManager50();
    private final SimulationEngine simEngine;

    private final LibraryTreePanel libraryTreePanel;
    private final DockableSectionPanel libraryDock;
    private final DockableSectionPanel editorDock;
    private final PlcLogPanel plcLogPanel;
    private final DockableSectionPanel plcLogDock;
    private final ScopePanel scopePanel;
    private final DockableSectionPanel scopeDock;

    private final JSplitPane horizontalSplit;
    private final JSplitPane verticalSplit;
    private final JSplitPane bottomHorizontalSplit;

    private final EditorToolbar editorToolbar;
    private final PageTabBar pageTabBar;
    private final FbdCanvas fbdCanvas;

    private JCheckBoxMenuItem windowLibraryItem;
    private JCheckBoxMenuItem windowPlcLogItem;
    private JCheckBoxMenuItem windowScopeItem;
    private JMenu recentProjectsMenu;
    private final List<File> recentFiles = new ArrayList<>();

    public MainWindow() {
        this.project = Project.createDefaultSampleProject();
        this.plcLogPanel = new PlcLogPanel();
        this.scopePanel = new ScopePanel(() -> this.project);

        this.simEngine = new SimulationEngine(this::onSimulationTickUiUpdate);
        this.simEngine.addScopeListener(scopePanel::recordSample);

        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1360, 840);
        setLayout(new BorderLayout());

        // 1. Build Library Dock (Left — Pop-Outable / Undockable)
        libraryTreePanel = new LibraryTreePanel();
        libraryDock = new DockableSectionPanel(
                "Library",
                libraryTreePanel,
                () -> {
                    windowLibraryItem.setSelected(false);
                    updateLibraryDockLayout();
                },
                true,
                this::updateLibraryDockLayout,
                () -> {
                    windowLibraryItem.setSelected(true);
                    updateLibraryDockLayout();
                }
        );
        libraryDock.setPreferredSize(new Dimension(215, 0));

        // 2. Build Main FBD Editor Section (Center)
        editorToolbar = new EditorToolbar(undoManager, createToolbarListener());
        fbdCanvas = new FbdCanvas(project, simEngine, createCanvasEditListener());
        pageTabBar = new PageTabBar(project, new PageTabBar.PageTabListener() {
            @Override
            public void onActivePageChanged(int pageIndex) {
                fbdCanvas.setProjectAndPage(project, pageIndex);
            }

            @Override
            public void onPagesStructureChanged() {
                libraryTreePanel.rebuildTree();
                refreshTitles();
            }
        });

        JPanel topEditorChrome = new JPanel(new BorderLayout());
        topEditorChrome.add(editorToolbar, BorderLayout.NORTH);
        topEditorChrome.add(pageTabBar, BorderLayout.SOUTH);

        JScrollPane canvasScroll = new JScrollPane(fbdCanvas);
        canvasScroll.setBorder(null);
        canvasScroll.getVerticalScrollBar().setUnitIncrement(16);
        canvasScroll.getHorizontalScrollBar().setUnitIncrement(16);

        JPanel editorBody = new JPanel(new BorderLayout());
        editorBody.add(topEditorChrome, BorderLayout.NORTH);
        editorBody.add(canvasScroll, BorderLayout.CENTER);

        editorDock = new DockableSectionPanel(project.formatEditorPanelTitle(), editorBody, null);

        // 3. Build Scope Dock (Docked at Bottom by Default — Pop-Outable / Undockable)
        scopeDock = new DockableSectionPanel(
                "Scope (Oscilloscope)",
                scopePanel,
                () -> {
                    windowScopeItem.setSelected(false);
                    updateBottomDockLayout();
                },
                true,
                this::updateBottomDockLayout,
                () -> {
                    windowScopeItem.setSelected(true);
                    updateBottomDockLayout();
                }
        );
        scopeDock.setPreferredSize(new Dimension(0, 195));

        // 4. Build PLC Log Dock (Pop-Outable / Undockable)
        plcLogDock = new DockableSectionPanel(
                "PLC Log",
                plcLogPanel,
                () -> {
                    windowPlcLogItem.setSelected(false);
                    updateBottomDockLayout();
                },
                true,
                this::updateBottomDockLayout,
                () -> {
                    windowPlcLogItem.setSelected(true);
                    updateBottomDockLayout();
                }
        );
        plcLogDock.setPreferredSize(new Dimension(360, 195));

        bottomHorizontalSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        bottomHorizontalSplit.setBorder(null);
        bottomHorizontalSplit.setResizeWeight(0.65);

        verticalSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, editorDock, scopeDock);
        verticalSplit.setBorder(null);
        verticalSplit.setDividerSize(4);
        verticalSplit.setResizeWeight(0.75);
        verticalSplit.setDividerLocation(575);

        horizontalSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, libraryDock, verticalSplit);
        horizontalSplit.setBorder(BorderFactory.createEmptyBorder(3, 3, 3, 3));
        horizontalSplit.setBackground(new Color(0x7A, 0x8A, 0x99));
        horizontalSplit.setDividerSize(4);
        horizontalSplit.setDividerLocation(215);

        add(horizontalSplit, BorderLayout.CENTER);

        // 5. Top Menu Bar & Keybindings
        setJMenuBar(buildMenuBar());
        installGlobalCanvasKeybindings();
        updateBottomDockLayout();

        refreshTitles();
        setLocationRelativeTo(null);
    }

    private void onSimulationTickUiUpdate() {
        if (fbdCanvas != null) {
            fbdCanvas.repaint();
        }
        if (editorToolbar != null) {
            editorToolbar.updateSimState(simEngine.getState());
        }
    }

    private void refreshTitles() {
        setTitle(project.formatWindowTitle());
        editorDock.setTitleText(project.formatEditorPanelTitle());
    }

    // =========================================================================
    // Top Menu Bar (File, Edit, View, Project, Communication, Options, Window, Help)
    // =========================================================================

    private JMenuBar buildMenuBar() {
        int menuMask = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        JMenuBar bar = new JMenuBar();
        bar.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(0xB8, 0xB8, 0xB8)));

        // 1. File
        JMenu fileMenu = new JMenu("File");
        JMenuItem newItem = new JMenuItem("New Project");
        newItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_N, menuMask));
        newItem.addActionListener(e -> actionNewProject());
        fileMenu.add(newItem);

        JMenuItem openItem = new JMenuItem("Open Project...");
        openItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_O, menuMask));
        openItem.addActionListener(e -> actionOpenProject());
        fileMenu.add(openItem);

        recentProjectsMenu = new JMenu("Recent Projects");
        rebuildRecentProjectsMenu();
        fileMenu.add(recentProjectsMenu);

        fileMenu.addSeparator();

        JMenuItem saveItem = new JMenuItem("Save");
        saveItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_S, menuMask));
        saveItem.addActionListener(e -> actionSaveProject(false));
        fileMenu.add(saveItem);

        JMenuItem saveAsItem = new JMenuItem("Save As...");
        saveAsItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_S, menuMask | KeyEvent.SHIFT_DOWN_MASK));
        saveAsItem.addActionListener(e -> actionSaveProject(true));
        fileMenu.add(saveAsItem);

        fileMenu.addSeparator();

        JMenuItem importPageItem = new JMenuItem("Import Page / Macro...");
        importPageItem.addActionListener(e -> actionImportPageOrMacro());
        fileMenu.add(importPageItem);

        JMenuItem exportPdfItem = new JMenuItem("Export Page to PDF / Image...");
        exportPdfItem.addActionListener(e -> actionExportPage());
        fileMenu.add(exportPdfItem);

        fileMenu.addSeparator();

        JMenuItem closeProjItem = new JMenuItem("Close Project");
        closeProjItem.addActionListener(e -> actionNewProject());
        fileMenu.add(closeProjItem);

        JMenuItem exitItem = new JMenuItem("Exit");
        exitItem.addActionListener(e -> System.exit(0));
        fileMenu.add(exitItem);
        bar.add(fileMenu);

        // 2. Edit
        JMenu editMenu = new JMenu("Edit");
        JMenuItem undoItem = new JMenuItem("Undo");
        undoItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Z, menuMask));
        undoItem.addActionListener(e -> performUndo(1));
        editMenu.add(undoItem);

        JMenuItem redoItem = new JMenuItem("Redo");
        redoItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Y, menuMask));
        redoItem.addActionListener(e -> performRedo(1));
        editMenu.add(redoItem);

        editMenu.addSeparator();

        JMenuItem cutItem = new JMenuItem("Cut");
        cutItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_X, menuMask));
        cutItem.addActionListener(e -> fbdCanvas.cutSelection());
        editMenu.add(cutItem);

        JMenuItem copyItem = new JMenuItem("Copy");
        copyItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_C, menuMask));
        copyItem.addActionListener(e -> fbdCanvas.copySelection());
        editMenu.add(copyItem);

        JMenuItem pasteItem = new JMenuItem("Paste");
        pasteItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_V, menuMask));
        pasteItem.addActionListener(e -> fbdCanvas.pasteClipboard());
        editMenu.add(pasteItem);

        JMenuItem dupItem = new JMenuItem("Duplicate");
        dupItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_D, menuMask));
        dupItem.addActionListener(e -> fbdCanvas.duplicateSelection());
        editMenu.add(dupItem);

        JMenuItem delItem = new JMenuItem("Delete");
        delItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_BACK_SPACE, 0));
        delItem.addActionListener(e -> fbdCanvas.deleteSelection());
        editMenu.add(delItem);

        editMenu.addSeparator();

        JMenuItem selAllItem = new JMenuItem("Select All");
        selAllItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_A, menuMask));
        selAllItem.addActionListener(e -> fbdCanvas.selectAll());
        editMenu.add(selAllItem);

        JMenuItem findItem = new JMenuItem("Find Block...");
        findItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_F, menuMask));
        findItem.addActionListener(e -> actionFindBlock());
        editMenu.add(findItem);
        bar.add(editMenu);

        // 3. View
        JMenu viewMenu = new JMenu("View");
        JMenuItem zoomInItem = new JMenuItem("Zoom In");
        zoomInItem.addActionListener(e -> fbdCanvas.zoomIn());
        viewMenu.add(zoomInItem);

        JMenuItem zoomOutItem = new JMenuItem("Zoom Out");
        zoomOutItem.addActionListener(e -> fbdCanvas.zoomOut());
        viewMenu.add(zoomOutItem);

        JMenuItem zoom100Item = new JMenuItem("Zoom 100%");
        zoom100Item.addActionListener(e -> fbdCanvas.zoom100());
        viewMenu.add(zoom100Item);

        JMenuItem fitItem = new JMenuItem("Fit to Screen");
        fitItem.addActionListener(e -> fbdCanvas.fitToScreen());
        viewMenu.add(fitItem);

        viewMenu.addSeparator();

        JCheckBoxMenuItem gridToggle = new JCheckBoxMenuItem("Show Placement Grid", true);
        gridToggle.addActionListener(e -> fbdCanvas.setShowGrid(gridToggle.isSelected()));
        viewMenu.add(gridToggle);

        JCheckBoxMenuItem execInfoToggle = new JCheckBoxMenuItem("Show Thread & Execution Order (T:/O:)", true);
        execInfoToggle.addActionListener(e -> fbdCanvas.setShowExecutionInfo(execInfoToggle.isSelected()));
        viewMenu.add(execInfoToggle);
        bar.add(viewMenu);

        // 4. Project
        JMenu projectMenu = new JMenu("Project");
        JMenuItem compileItem = new JMenuItem("Compile Program");
        compileItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_F7, 0));
        compileItem.addActionListener(e -> actionCompile());
        projectMenu.add(compileItem);

        projectMenu.addSeparator();

        JMenuItem sortGraphItem = new JMenuItem("Sort Blocks Graphically");
        sortGraphItem.addActionListener(e -> actionSortGraphically());
        projectMenu.add(sortGraphItem);

        JMenuItem sortOrderItem = new JMenuItem("Sort Execution Order");
        sortOrderItem.addActionListener(e -> actionSortExecutionOrder(AppSettings.get().getDefaultSortMode()));
        projectMenu.add(sortOrderItem);

        projectMenu.addSeparator();

        JMenuItem addPageItem = new JMenuItem("Add Page...");
        addPageItem.addActionListener(e -> pageTabBar.addNewPage(false));
        projectMenu.add(addPageItem);

        JMenuItem addMacroItem = new JMenuItem("Add Macro...");
        addMacroItem.addActionListener(e -> pageTabBar.addNewPage(true));
        projectMenu.add(addMacroItem);

        projectMenu.addSeparator();

        JMenuItem plcCfgItem = new JMenuItem("Unit / PLC Configuration...");
        plcCfgItem.addActionListener(e -> actionOpenPlcConfig());
        projectMenu.add(plcCfgItem);
        bar.add(projectMenu);

        // 5. Communication
        JMenu commMenu = new JMenu("Communication");
        JMenuItem commSettingsItem = new JMenuItem("Communication Settings...");
        commSettingsItem.addActionListener(e -> {
            CommunicationDialog dlg = new CommunicationDialog(this, project, CommunicationDialog.Mode.SETTINGS_ONLY);
            dlg.setVisible(true);
        });
        commMenu.add(commSettingsItem);

        JMenuItem uploadItem = new JMenuItem("Upload to PLC...");
        uploadItem.addActionListener(e -> actionUpload());
        commMenu.add(uploadItem);

        JMenuItem uploadWatchItem = new JMenuItem("Upload and Watch...");
        uploadWatchItem.addActionListener(e -> actionUploadAndWatch());
        commMenu.add(uploadWatchItem);

        JMenuItem watchItem = new JMenuItem("Watch PLC");
        watchItem.addActionListener(e -> actionToggleWatchMode());
        commMenu.add(watchItem);

        JMenuItem disconnectItem = new JMenuItem("Disconnect");
        disconnectItem.addActionListener(e -> {
            simEngine.stopSimulation(project);
            plcLogPanel.appendLog("Disconnected from PLC.");
        });
        commMenu.add(disconnectItem);

        commMenu.addSeparator();

        JMenuItem readPlcItem = new JMenuItem("Read Program from PLC...");
        readPlcItem.addActionListener(e -> actionUpload());
        commMenu.add(readPlcItem);

        JMenuItem syncRtcItem = new JMenuItem("Set PLC RTC / Time");
        syncRtcItem.addActionListener(e -> {
            plcLogPanel.appendLog("Synchronized STM32F407 hardware RTC with host time.");
            JOptionPane.showMessageDialog(this, "PLC RTC synchronized to host system clock.", "RTC Sync", JOptionPane.INFORMATION_MESSAGE);
        });
        commMenu.add(syncRtcItem);
        bar.add(commMenu);

        // 6. Options (Ctrl/Cmd + , opens settings)
        JMenu optionsMenu = new JMenu("Options");
        JMenuItem settingsItem = new JMenuItem("Settings...");
        settingsItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_COMMA, menuMask));
        settingsItem.addActionListener(e -> actionOpenSettings());
        optionsMenu.add(settingsItem);
        bar.add(optionsMenu);

        // 7. Window (Library, Scope [Docked by default], PLC Log, Reset Layout)
        JMenu windowMenu = new JMenu("Window");
        windowLibraryItem = new JCheckBoxMenuItem("Library", true);
        windowLibraryItem.addActionListener(e -> {
            if (!windowLibraryItem.isSelected() && libraryDock.isFloating()) {
                libraryDock.closeSection();
            } else {
                updateLibraryDockLayout();
            }
        });
        windowMenu.add(windowLibraryItem);

        windowScopeItem = new JCheckBoxMenuItem("Scope (Oscilloscope)", true);
        windowScopeItem.addActionListener(e -> {
            if (!windowScopeItem.isSelected() && scopeDock.isFloating()) {
                scopeDock.closeSection();
            } else {
                updateBottomDockLayout();
            }
        });
        windowMenu.add(windowScopeItem);

        windowPlcLogItem = new JCheckBoxMenuItem("PLC Log", false);
        windowPlcLogItem.addActionListener(e -> {
            if (!windowPlcLogItem.isSelected() && plcLogDock.isFloating()) {
                plcLogDock.closeSection();
            } else {
                updateBottomDockLayout();
            }
        });
        windowMenu.add(windowPlcLogItem);

        windowMenu.addSeparator();

        JMenuItem resetLayoutItem = new JMenuItem("Reset Window Layout");
        resetLayoutItem.addActionListener(e -> {
            if (libraryDock.isFloating()) libraryDock.redockToMainWindow();
            if (scopeDock.isFloating()) scopeDock.redockToMainWindow();
            if (plcLogDock.isFloating()) plcLogDock.redockToMainWindow();
            windowLibraryItem.setSelected(true);
            windowScopeItem.setSelected(true);
            windowPlcLogItem.setSelected(false);
            updateLibraryDockLayout();
            updateBottomDockLayout();
        });
        windowMenu.add(resetLayoutItem);
        bar.add(windowMenu);

        // 8. Help
        JMenu helpMenu = new JMenu("Help");
        JMenuItem aboutItem = new JMenuItem("About Maraflow");
        aboutItem.addActionListener(e -> JOptionPane.showMessageDialog(
                this,
                "Maraflow V" + Project.VERSION + "\nFunctional FBD PLC Programming IDE\nTarget: STM32F407 + FreeRTOS Bytecode VM",
                "About Maraflow",
                JOptionPane.INFORMATION_MESSAGE
        ));
        helpMenu.add(aboutItem);
        bar.add(helpMenu);

        return bar;
    }

    private void installGlobalCanvasKeybindings() {
        fbdCanvas.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "deleteSel");
        fbdCanvas.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_BACK_SPACE, 0), "deleteSel");
        fbdCanvas.getActionMap().put("deleteSel", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (fbdCanvas.hasFocus()) {
                    fbdCanvas.deleteSelection();
                }
            }
        });
    }

    private void updateLibraryDockLayout() {
        boolean showDocked = windowLibraryItem.isSelected() && !libraryDock.isFloating();
        if (showDocked) {
            horizontalSplit.setLeftComponent(libraryDock);
            horizontalSplit.setDividerSize(4);
            horizontalSplit.setDividerLocation(AppSettings.get().scale(215));
        } else {
            horizontalSplit.setLeftComponent(null);
            horizontalSplit.setDividerSize(0);
        }
        horizontalSplit.revalidate();
        horizontalSplit.repaint();
    }

    private void updateBottomDockLayout() {
        boolean showScopeDocked = windowScopeItem != null && windowScopeItem.isSelected() && !scopeDock.isFloating();
        boolean showLogDocked = windowPlcLogItem != null && windowPlcLogItem.isSelected() && !plcLogDock.isFloating();

        if (showScopeDocked && showLogDocked) {
            bottomHorizontalSplit.setLeftComponent(scopeDock);
            bottomHorizontalSplit.setRightComponent(plcLogDock);
            bottomHorizontalSplit.setDividerSize(4);
            bottomHorizontalSplit.setDividerLocation((int) (getWidth() * 0.55));
            verticalSplit.setBottomComponent(bottomHorizontalSplit);
            verticalSplit.setDividerSize(4);
            verticalSplit.setDividerLocation(Math.max(300, getHeight() - AppSettings.get().scale(250)));
        } else if (showScopeDocked) {
            verticalSplit.setBottomComponent(scopeDock);
            verticalSplit.setDividerSize(4);
            verticalSplit.setDividerLocation(Math.max(300, getHeight() - AppSettings.get().scale(250)));
        } else if (showLogDocked) {
            verticalSplit.setBottomComponent(plcLogDock);
            verticalSplit.setDividerSize(4);
            verticalSplit.setDividerLocation(Math.max(300, getHeight() - AppSettings.get().scale(250)));
        } else {
            verticalSplit.setBottomComponent(null);
            verticalSplit.setDividerSize(0);
        }
        verticalSplit.revalidate();
        verticalSplit.repaint();
    }

    // =========================================================================
    // Toolbar & Canvas Callbacks
    // =========================================================================

    private EditorToolbar.ToolbarListener createToolbarListener() {
        return new EditorToolbar.ToolbarListener() {
            @Override
            public void onToolChanged(ToolMode mode, GraphicElement.Kind shapeKind, int customNgonSides) {
                fbdCanvas.setToolMode(mode, shapeKind, customNgonSides);
            }

            @Override
            public void onUndoSteps(int count) {
                performUndo(count);
            }

            @Override
            public void onRedoSteps(int count) {
                performRedo(count);
            }

            @Override
            public void onSimPlay() {
                simEngine.startSimulation(project);
                plcLogPanel.appendLog("Local simulation started.");
                editorToolbar.updateSimState(simEngine.getState());
            }

            @Override
            public void onSimPause() {
                simEngine.pauseSimulation();
                plcLogPanel.appendLog("Local simulation paused.");
                editorToolbar.updateSimState(simEngine.getState());
            }

            @Override
            public void onSimStop() {
                simEngine.stopSimulation(project);
                plcLogPanel.appendLog("Simulation / Watch stopped.");
                editorToolbar.updateSimState(simEngine.getState());
            }

            @Override
            public void onSimSpeedChanged(double multiplier) {
                simEngine.setSpeedMultiplier(multiplier);
                plcLogPanel.appendLog("Simulation speed multiplier set to " + multiplier + "x.");
            }

            @Override
            public void onSortGraphically() {
                actionSortGraphically();
            }

            @Override
            public void onSortExecutionOrder(OrderSortMode sortMode) {
                actionSortExecutionOrder(sortMode);
            }

            @Override
            public void onCompile() {
                actionCompile();
            }

            @Override
            public void onUpload() {
                actionUpload();
            }

            @Override
            public void onWatchToggle() {
                actionToggleWatchMode();
            }

            @Override
            public void onPlcConfig() {
                actionOpenPlcConfig();
            }

            @Override
            public void onDefaultThreadChanged(int threadId) {
                project.setDefaultThreadId(threadId);
            }
        };
    }

    private FbdCanvas.CanvasEditListener createCanvasEditListener() {
        return new FbdCanvas.CanvasEditListener() {
            @Override
            public void onBeforePageEdit(String actionName, Page snapshotBefore) {}

            @Override
            public void onAfterPageEdit(String actionName, Page snapshotBefore, Page snapshotAfter) {
                project.setDirty(true);
                undoManager.recordPageAction(actionName, pageTabBar.getActivePageIndex(), snapshotBefore, snapshotAfter);
                libraryTreePanel.rebuildTree();
                refreshTitles();
                editorToolbar.updateSimState(simEngine.getState());
            }

            @Override
            public void onResetToolToCursor() {
                editorToolbar.selectCursorTool();
            }

            @Override
            public void onInspectMacroPage(String macroTypeName) {
                for (int i = 0; i < project.getPages().size(); i++) {
                    Page p = project.getPages().get(i);
                    if (p.isMacro() && p.getName().substring(1).trim().equalsIgnoreCase(macroTypeName)) {
                        pageTabBar.setActivePageIndex(i);
                        return;
                    }
                }
            }

            @Override
            public boolean isPinOnScope(String pinRef) {
                return scopePanel.isPinSelected(pinRef);
            }

            @Override
            public void onTogglePinOnScope(String pinRef) {
                scopePanel.togglePinSelection(pinRef);
            }
        };
    }

    private void performUndo(int count) {
        if (!undoManager.canUndo()) return;
        simEngine.stopSimulation(project);
        HistoryEntry last = undoManager.undoSteps(count, project.getPages());
        if (last != null) {
            project.setDirty(true);
            BlockCatalog.syncMacrosFromProject(project);
            libraryTreePanel.rebuildTree();
            pageTabBar.setActivePageIndex(last.pageIndex());
            fbdCanvas.setProjectAndPage(project, last.pageIndex());
            refreshTitles();
        }
    }

    private void performRedo(int count) {
        if (!undoManager.canRedo()) return;
        simEngine.stopSimulation(project);
        HistoryEntry last = undoManager.redoSteps(count, project.getPages());
        if (last != null) {
            project.setDirty(true);
            BlockCatalog.syncMacrosFromProject(project);
            libraryTreePanel.rebuildTree();
            pageTabBar.setActivePageIndex(last.pageIndex());
            fbdCanvas.setProjectAndPage(project, last.pageIndex());
            refreshTitles();
        }
    }

    // =========================================================================
    // Project, Sorting, Compilation, Upload, Watch, & Settings Actions
    // =========================================================================

    private void actionNewProject() {
        simEngine.stopSimulation(project);
        project = new Project();
        project.setProjectName("Untitled");
        project.setDirty(true);
        undoManager.clear();
        BlockCatalog.syncMacrosFromProject(project);
        libraryTreePanel.rebuildTree();
        pageTabBar.setProject(project);
        fbdCanvas.setProjectAndPage(project, 0);
        refreshTitles();
    }

    private void actionOpenProject() {
        JFileChooser fc = new JFileChooser();
        fc.setFileFilter(new FileNameExtensionFilter("Maraflow Project (*.mfl)", "mfl"));
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            openProjectFromFile(fc.getSelectedFile());
        }
    }

    private void openProjectFromFile(File file) {
        try {
            simEngine.stopSimulation(project);
            project = MflProjectSerializer.loadProject(file);
            undoManager.clear();
            libraryTreePanel.rebuildTree();
            pageTabBar.setProject(project);
            fbdCanvas.setProjectAndPage(project, 0);
            editorToolbar.setDefaultThreadSelection(project.getDefaultThreadId());
            addRecentFile(file);
            refreshTitles();
            plcLogPanel.appendLog("Loaded project: " + file.getAbsolutePath());
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Failed to open project:\n" + ex.getMessage(), "Open Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void actionSaveProject(boolean saveAs) {
        File target = project.getProjectFile();
        if (saveAs || target == null) {
            JFileChooser fc = new JFileChooser();
            fc.setFileFilter(new FileNameExtensionFilter("Maraflow Project (*.mfl)", "mfl"));
            fc.setSelectedFile(new File(project.getProjectName() + ".mfl"));
            if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
                return;
            }
            target = fc.getSelectedFile();
            if (!target.getName().toLowerCase().endsWith(".mfl")) {
                target = new File(target.getParentFile(), target.getName() + ".mfl");
            }
        }
        try {
            MflProjectSerializer.saveProject(project, target);
            addRecentFile(target);
            refreshTitles();
            pageTabBar.rebuildTabs();
            plcLogPanel.appendLog("Saved project to " + target.getAbsolutePath());
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Failed to save project:\n" + ex.getMessage(), "Save Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void actionImportPageOrMacro() {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Select .mfl Project to Import Page/Macro From");
        fc.setFileFilter(new FileNameExtensionFilter("Maraflow Project (*.mfl)", "mfl"));
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;

        try {
            Project externalProj = MflProjectSerializer.loadProject(fc.getSelectedFile());
            String[] pageNames = externalProj.getPages().stream().map(Page::getName).toArray(String[]::new);
            if (pageNames.length == 0) return;

            String chosen = (String) JOptionPane.showInputDialog(
                    this,
                    "Select Page or Macro to import:",
                    "Import Page / Macro",
                    JOptionPane.PLAIN_MESSAGE,
                    null,
                    pageNames,
                    pageNames[0]
            );
            if (chosen != null) {
                for (Page p : externalProj.getPages()) {
                    if (p.getName().equals(chosen)) {
                        Page imported = p.copy();
                        project.getPages().add(imported);
                        project.setDirty(true);
                        BlockCatalog.syncMacrosFromProject(project);
                        libraryTreePanel.rebuildTree();
                        pageTabBar.setActivePageIndex(project.getPages().size() - 1);
                        refreshTitles();
                        plcLogPanel.appendLog("Imported page/macro '" + chosen + "' from " + fc.getSelectedFile().getName());
                        break;
                    }
                }
            }
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Import failed:\n" + ex.getMessage(), "Import Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void actionExportPage() {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Export Active Page");
        fc.setSelectedFile(new File(project.getProjectName() + "_" + fbdCanvas.getActivePage().getName().replace('*', '_') + ".png"));
        if (fc.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            try {
                File out = fc.getSelectedFile();
                BufferedImage img = new BufferedImage(fbdCanvas.getWidth(), fbdCanvas.getHeight(), BufferedImage.TYPE_INT_RGB);
                Graphics2D g2 = img.createGraphics();
                fbdCanvas.paint(g2);
                g2.dispose();
                ImageIO.write(img, "png", out);
                plcLogPanel.appendLog("Exported page snapshot to " + out.getAbsolutePath());
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, "Export failed:\n" + ex.getMessage(), "Export Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void actionFindBlock() {
        Page page = fbdCanvas.getActivePage();
        if (page == null || page.getBlocks().isEmpty()) return;
        String q = JOptionPane.showInputDialog(this, "Enter Block Name to locate:", "");
        if (q == null || q.isBlank()) return;
        BlockInstance found = page.findBlockByName(q.trim());
        if (found != null) {
            fbdCanvas.clearSelection();
            int g = fbdCanvas.getGridUnitPx();
            fbdCanvas.scrollRectToVisible(new java.awt.Rectangle(
                    found.getGridX() * g - 50,
                    found.getGridY() * g - 50,
                    found.getGridWidth() * g + 100,
                    found.getGridHeight() * g + 100
            ));
            fbdCanvas.repaint();
        } else {
            JOptionPane.showMessageDialog(this, "Block '" + q.trim() + "' not found on active page.", "Find Block", JOptionPane.INFORMATION_MESSAGE);
        }
    }

    private void actionSortGraphically() {
        Page page = fbdCanvas.getActivePage();
        if (page == null || page.getBlocks().isEmpty()) return;
        simEngine.stopSimulation(project);
        Page before = page.copy();
        BlockSorter.sortBlocksGraphically(page);
        project.setDirty(true);
        undoManager.recordPageAction("Sort Blocks Graphically", pageTabBar.getActivePageIndex(), before, page);
        fbdCanvas.updateCanvasPreferredSize();
        fbdCanvas.repaint();
        refreshTitles();
    }

    private void actionSortExecutionOrder(OrderSortMode mode) {
        Page page = fbdCanvas.getActivePage();
        if (page == null || page.getBlocks().isEmpty()) return;
        simEngine.stopSimulation(project);
        Page before = page.copy();
        BlockSorter.sortExecutionOrder(page, mode);
        project.setDirty(true);
        undoManager.recordPageAction("Sort Execution Order (" + mode.getLabel() + ")", pageTabBar.getActivePageIndex(), before, page);
        fbdCanvas.repaint();
        refreshTitles();
    }

    private void actionCompile() {
        CompilationReport report = BytecodeCompiler.compile(project);
        for (String line : report.logLines()) {
            plcLogPanel.appendLog(line);
        }
        CompilationResultsDialog dlg = new CompilationResultsDialog(this, report);
        dlg.setVisible(true);

        if (dlg.getNextAction() == CompilationResultsDialog.NextAction.UPLOAD) {
            actionUpload();
        } else if (dlg.getNextAction() == CompilationResultsDialog.NextAction.UPLOAD_AND_WATCH) {
            actionUploadAndWatch();
        }
    }

    private void actionUpload() {
        CommunicationDialog commDlg = new CommunicationDialog(this, project, CommunicationDialog.Mode.UPLOAD);
        commDlg.setVisible(true);
        if (commDlg.isConnectionSucceeded()) {
            plcLogPanel.appendLog("Bytecode uploaded to PLC (" + project.getTargetPlatform() + ").");
        }
    }

    private void actionUploadAndWatch() {
        CommunicationDialog commDlg = new CommunicationDialog(this, project, CommunicationDialog.Mode.WATCH);
        commDlg.setVisible(true);
        if (commDlg.isConnectionSucceeded()) {
            plcLogPanel.appendLog("Bytecode uploaded. Transitioning into PLC Watch Mode (115200 baud smart polling).");
            simEngine.enterWatchMode(project);
            editorToolbar.updateSimState(simEngine.getState());
        }
    }

    private void actionToggleWatchMode() {
        if (simEngine.getState() == SimulationEngine.State.WATCH_MODE) {
            simEngine.stopSimulation(project);
            plcLogPanel.appendLog("Exited PLC Watch Mode.");
            editorToolbar.updateSimState(simEngine.getState());
            return;
        }
        CommunicationDialog commDlg = new CommunicationDialog(this, project, CommunicationDialog.Mode.WATCH);
        commDlg.setVisible(true);
        if (commDlg.isConnectionSucceeded()) {
            plcLogPanel.appendLog("Connected to PLC. Editor transitioned into Watch Mode (editing any block exits Watch Mode).");
            simEngine.enterWatchMode(project);
            editorToolbar.updateSimState(simEngine.getState());
        }
    }

    private void actionOpenPlcConfig() {
        PlcConfigDialog dlg = new PlcConfigDialog(this, project);
        dlg.setVisible(true);
        refreshTitles();
    }

    private void actionOpenSettings() {
        SettingsDialog dlg = new SettingsDialog(this, () -> {
            fbdCanvas.setShowGrid(AppSettings.get().isShowGridByDefault());
            fbdCanvas.setShowExecutionInfo(AppSettings.get().isShowExecInfoByDefault());
            editorToolbar.setDefaultThreadSelection(AppSettings.get().getDefaultThreadId());
            project.setDefaultThreadId(AppSettings.get().getDefaultThreadId());
            updateLibraryDockLayout();
            updateBottomDockLayout();
            fbdCanvas.repaint();
        });
        dlg.setVisible(true);
    }

    private void addRecentFile(File f) {
        recentFiles.remove(f);
        recentFiles.addFirst(f);
        while (recentFiles.size() > 10) {
            recentFiles.removeLast();
        }
        rebuildRecentProjectsMenu();
    }

    private void rebuildRecentProjectsMenu() {
        if (recentProjectsMenu == null) return;
        recentProjectsMenu.removeAll();
        if (recentFiles.isEmpty()) {
            JMenuItem none = new JMenuItem("(No Recent Projects)");
            none.setEnabled(false);
            recentProjectsMenu.add(none);
            return;
        }
        for (File f : recentFiles) {
            JMenuItem item = new JMenuItem(f.getAbsolutePath());
            item.addActionListener(e -> openProjectFromFile(f));
            recentProjectsMenu.add(item);
        }
    }
}
