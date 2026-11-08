package cz.maramat.ui.dialogs;

import cz.maramat.model.HeaderColor;
import cz.maramat.model.PlcThread;
import cz.maramat.settings.AppSettings;
import cz.maramat.sim.BlockSorter.OrderSortMode;
import cz.maramat.ui.icons.VectorIcons;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTextField;
import javax.swing.JTree;
import javax.swing.SpinnerNumberModel;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.TreeSelectionModel;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.Hashtable;

public class SettingsDialog extends JDialog {

    private final AppSettings settings = AppSettings.get();
    private final Runnable onSettingsApplied;

    private JSlider uiScaleSlider;
    private JLabel uiScaleValLabel;
    private JCheckBox antialiasTextChk;
    private JCheckBox showGridChk;
    private JCheckBox showExecInfoChk;
    private JComboBox<HeaderColor> defaultHeaderColorCombo;

    private JCheckBox autoBundleBusChk;
    private JComboBox<PlcThread> defaultThreadCombo;
    private JComboBox<OrderSortMode> defaultSortModeCombo;

    private JSpinner scopeSamplesSpinner;

    private JComboBox<String> defaultCommMethodCombo;
    private JComboBox<String> defaultBaudCombo;
    private JTextField defaultPortField;

    public SettingsDialog(Frame owner, Runnable onSettingsApplied) {
        super(owner, "Settings", true);
        this.onSettingsApplied = onSettingsApplied;
        buildUi();
        pack();
        int sw = settings.scale(660);
        int sh = settings.scale(430);
        setMinimumSize(new Dimension(sw, sh));
        setSize(sw, sh);
        setLocationRelativeTo(owner);
    }

    private void buildUi() {
        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        // 1. Left Tree View of Categories & Items
        DefaultMutableTreeNode treeRoot = new DefaultMutableTreeNode("Settings");

        DefaultMutableTreeNode catVisual = new DefaultMutableTreeNode("Visual");
        DefaultMutableTreeNode nodeScale = new DefaultMutableTreeNode("UI Scale & Text");
        DefaultMutableTreeNode nodeBlockVis = new DefaultMutableTreeNode("Blocks & Canvas");
        catVisual.add(nodeScale);
        catVisual.add(nodeBlockVis);

        DefaultMutableTreeNode catDefaults = new DefaultMutableTreeNode("Defaults");
        DefaultMutableTreeNode nodeWiring = new DefaultMutableTreeNode("Wiring & Busses");
        DefaultMutableTreeNode nodeExecution = new DefaultMutableTreeNode("Threads & Sorting");
        DefaultMutableTreeNode nodeComm = new DefaultMutableTreeNode("Communication");
        DefaultMutableTreeNode nodeScope = new DefaultMutableTreeNode("Oscilloscope");
        catDefaults.add(nodeWiring);
        catDefaults.add(nodeExecution);
        catDefaults.add(nodeComm);
        catDefaults.add(nodeScope);

        treeRoot.add(catVisual);
        treeRoot.add(catDefaults);

        JTree navTree = new JTree(treeRoot);
        navTree.setRootVisible(false);
        navTree.setShowsRootHandles(true);
        navTree.putClientProperty("JTree.lineStyle", "Angled");
        navTree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        if (navTree.getCellRenderer() instanceof DefaultTreeCellRenderer r) {
            r.setOpenIcon(null);
            r.setClosedIcon(null);
            r.setLeafIcon(null);
        }
        for (int i = 0; i < navTree.getRowCount(); i++) {
            navTree.expandRow(i);
        }

        // 2. Right Card Panel
        CardLayout cardLayout = new CardLayout();
        JPanel cardContainer = new JPanel(cardLayout);
        cardContainer.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(0xB8, 0xB8, 0xB8)),
                BorderFactory.createEmptyBorder(10, 12, 10, 12)
        ));

        cardContainer.add(buildScaleAndTextPanel(), "UI Scale & Text");
        cardContainer.add(buildBlockAndCanvasPanel(), "Blocks & Canvas");
        cardContainer.add(buildWiringAndBusPanel(), "Wiring & Busses");
        cardContainer.add(buildThreadsAndSortingPanel(), "Threads & Sorting");
        cardContainer.add(buildCommunicationDefaultsPanel(), "Communication");
        cardContainer.add(buildScopeDefaultsPanel(), "Oscilloscope");

        navTree.addTreeSelectionListener(e -> {
            DefaultMutableTreeNode sel = (DefaultMutableTreeNode) navTree.getLastSelectedPathComponent();
            if (sel == null) return;
            String key = sel.getUserObject().toString();
            if (key.equals("Visual")) {
                cardLayout.show(cardContainer, "UI Scale & Text");
            } else if (key.equals("Defaults")) {
                cardLayout.show(cardContainer, "Wiring & Busses");
            } else {
                cardLayout.show(cardContainer, key);
            }
        });
        navTree.setSelectionRow(1);

        JScrollPane treeScroll = new JScrollPane(navTree);
        treeScroll.setPreferredSize(new Dimension(settings.scale(190), 0));

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, treeScroll, cardContainer);
        split.setDividerSize(4);
        split.setDividerLocation(settings.scale(190));
        root.add(split, BorderLayout.CENTER);

        // 3. Bottom Buttons: OK, Apply, Cancel
        JPanel btnBar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 2));
        JButton okBtn = new JButton("OK", VectorIcons.greenCheckIcon());
        okBtn.addActionListener(e -> {
            applyValues();
            dispose();
        });

        JButton applyBtn = new JButton("Apply");
        applyBtn.addActionListener(e -> applyValues());

        JButton cancelBtn = new JButton("Cancel", VectorIcons.redCrossIcon());
        cancelBtn.addActionListener(e -> dispose());

        btnBar.add(okBtn);
        btnBar.add(applyBtn);
        btnBar.add(cancelBtn);
        root.add(btnBar, BorderLayout.SOUTH);

        setContentPane(root);
    }

    private JPanel buildScaleAndTextPanel() {
        JPanel p = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = createGbc();

        uiScaleSlider = new JSlider(75, 225, settings.getUiScalePercent());
        uiScaleSlider.setMajorTickSpacing(25);
        uiScaleSlider.setMinorTickSpacing(25);
        uiScaleSlider.setSnapToTicks(true);
        uiScaleSlider.setPaintTicks(true);
        uiScaleSlider.setPaintLabels(true);

        Hashtable<Integer, JLabel> labelTable = new Hashtable<>();
        for (int v = 75; v <= 225; v += 25) {
            labelTable.put(v, new JLabel(v + "%"));
        }
        uiScaleSlider.setLabelTable(labelTable);

        uiScaleValLabel = new JLabel("UI Scale (" + settings.getUiScalePercent() + "%):");
        uiScaleSlider.addChangeListener(e -> {
            int snapped = Math.round(uiScaleSlider.getValue() / 25.0f) * 25;
            uiScaleValLabel.setText("UI Scale (" + snapped + "%):");
        });

        antialiasTextChk = new JCheckBox("Enable Text Antialiasing (Strictly on Text Only)", settings.isAntialiasText());

        int r = 0;
        gbc.gridx = 0; gbc.gridy = r; gbc.weightx = 0.0;
        p.add(uiScaleValLabel, gbc);
        gbc.gridx = 1; gbc.weightx = 1.0;
        p.add(uiScaleSlider, gbc);
        r++;

        gbc.gridx = 0; gbc.gridy = r; gbc.gridwidth = 2;
        p.add(antialiasTextChk, gbc);
        r++;

        pushToTop(p, gbc, r);
        return p;
    }

    private JPanel buildBlockAndCanvasPanel() {
        JPanel p = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = createGbc();

        defaultHeaderColorCombo = new JComboBox<>(HeaderColor.values());
        defaultHeaderColorCombo.setSelectedItem(settings.getDefaultHeaderColor());

        showGridChk = new JCheckBox("Show Dotted Placement Grid by Default", settings.isShowGridByDefault());
        showExecInfoChk = new JCheckBox("Show Centered T: x / O: x Execution Info on Blocks", settings.isShowExecInfoByDefault());

        int r = 0;
        gbc.gridx = 0; gbc.gridy = r; gbc.weightx = 0.0; gbc.gridwidth = 1;
        p.add(new JLabel("Default Block Header Color:"), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0;
        p.add(defaultHeaderColorCombo, gbc);
        r++;

        gbc.gridx = 0; gbc.gridy = r; gbc.gridwidth = 2;
        p.add(showGridChk, gbc);
        r++;

        gbc.gridx = 0; gbc.gridy = r; gbc.gridwidth = 2;
        p.add(showExecInfoChk, gbc);
        r++;

        pushToTop(p, gbc, r);
        return p;
    }

    private JPanel buildWiringAndBusPanel() {
        JPanel p = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = createGbc();

        autoBundleBusChk = new JCheckBox(
                "Auto-Bundle into Bus when multiple wires connect the same two blocks",
                settings.isAutoBundleBus()
        );

        int r = 0;
        gbc.gridx = 0; gbc.gridy = r; gbc.gridwidth = 2; gbc.weightx = 1.0;
        p.add(autoBundleBusChk, gbc);
        r++;

        pushToTop(p, gbc, r);
        return p;
    }

    private JPanel buildThreadsAndSortingPanel() {
        JPanel p = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = createGbc();

        defaultThreadCombo = new JComboBox<>(PlcThread.values());
        defaultThreadCombo.setSelectedItem(PlcThread.fromId(settings.getDefaultThreadId()));

        defaultSortModeCombo = new JComboBox<>(OrderSortMode.values());
        defaultSortModeCombo.setSelectedItem(settings.getDefaultSortMode());

        int r = 0;
        gbc.gridx = 0; gbc.gridy = r; gbc.weightx = 0.0;
        p.add(new JLabel("Default Block Thread:"), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0;
        p.add(defaultThreadCombo, gbc);
        r++;

        gbc.gridx = 0; gbc.gridy = r; gbc.weightx = 0.0;
        p.add(new JLabel("Default Execution Order Sort:"), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0;
        p.add(defaultSortModeCombo, gbc);
        r++;

        pushToTop(p, gbc, r);
        return p;
    }

    private JPanel buildCommunicationDefaultsPanel() {
        JPanel p = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = createGbc();

        defaultCommMethodCombo = new JComboBox<>(new String[]{"USB (CDC)", "RS485 (Serial)", "Ethernet (TCP)"});
        defaultCommMethodCombo.setSelectedItem(settings.getDefaultCommMethod());

        defaultBaudCombo = new JComboBox<>(new String[]{"9600", "19200", "38400", "57600", "115200", "230400", "460800", "921600"});
        defaultBaudCombo.setSelectedItem(settings.getDefaultBaudRate());

        defaultPortField = new JTextField(settings.getDefaultSerialPort(), 14);

        int r = 0;
        gbc.gridx = 0; gbc.gridy = r; gbc.weightx = 0.0;
        p.add(new JLabel("Default Upload Method:"), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0;
        p.add(defaultCommMethodCombo, gbc);
        r++;

        gbc.gridx = 0; gbc.gridy = r; gbc.weightx = 0.0;
        p.add(new JLabel("Default Serial Baud Rate:"), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0;
        p.add(defaultBaudCombo, gbc);
        r++;

        gbc.gridx = 0; gbc.gridy = r; gbc.weightx = 0.0;
        p.add(new JLabel("Default Serial Port:"), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0;
        p.add(defaultPortField, gbc);
        r++;

        pushToTop(p, gbc, r);
        return p;
    }

    private JPanel buildScopeDefaultsPanel() {
        JPanel p = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = createGbc();

        scopeSamplesSpinner = new JSpinner(new SpinnerNumberModel(settings.getScopeMaxSamples(), 60, 2000, 60));

        int r = 0;
        gbc.gridx = 0; gbc.gridy = r; gbc.weightx = 0.0;
        p.add(new JLabel("Scope Sample Buffer Depth:"), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0;
        p.add(scopeSamplesSpinner, gbc);
        r++;

        pushToTop(p, gbc, r);
        return p;
    }

    private GridBagConstraints createGbc() {
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 6, 5, 6);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.anchor = GridBagConstraints.NORTHWEST;
        return gbc;
    }

    private void pushToTop(JPanel p, GridBagConstraints gbc, int row) {
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.weighty = 1.0;
        p.add(new JPanel(), gbc);
    }

    private void applyValues() {
        int snappedScale = Math.round(uiScaleSlider.getValue() / 25.0f) * 25;
        settings.setUiScalePercent(snappedScale);
        settings.setAntialiasText(antialiasTextChk.isSelected());
        settings.setDefaultHeaderColor((HeaderColor) defaultHeaderColorCombo.getSelectedItem());
        settings.setShowGridByDefault(showGridChk.isSelected());
        settings.setShowExecInfoByDefault(showExecInfoChk.isSelected());
        settings.setAutoBundleBus(autoBundleBusChk.isSelected());

        PlcThread defT = (PlcThread) defaultThreadCombo.getSelectedItem();
        if (defT != null) settings.setDefaultThreadId(defT.getId());

        OrderSortMode defSort = (OrderSortMode) defaultSortModeCombo.getSelectedItem();
        if (defSort != null) settings.setDefaultSortMode(defSort);

        settings.setScopeMaxSamples((Integer) scopeSamplesSpinner.getValue());
        settings.setDefaultCommMethod((String) defaultCommMethodCombo.getSelectedItem());
        settings.setDefaultBaudRate((String) defaultBaudCombo.getSelectedItem());
        settings.setDefaultSerialPort(defaultPortField.getText().trim());

        settings.applySettingsToUi();
        if (onSettingsApplied != null) {
            onSettingsApplied.run();
        }
    }
}
