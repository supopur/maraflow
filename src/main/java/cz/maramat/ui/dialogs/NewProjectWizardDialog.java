package cz.maramat.ui.dialogs;

import cz.maramat.io.MflProjectSerializer;
import cz.maramat.model.BlockCatalog;
import cz.maramat.model.BlockDefinition;
import cz.maramat.model.BlockInstance;
import cz.maramat.model.IecLanguage;
import cz.maramat.model.LadderBranch;
import cz.maramat.model.LadderElement;
import cz.maramat.model.LadderRung;
import cz.maramat.model.Page;
import cz.maramat.model.PlcThread;
import cz.maramat.model.Project;
import cz.maramat.model.Wire;
import cz.maramat.ui.AppFonts;
import cz.maramat.ui.icons.VectorIcons;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.Point;
import java.io.File;

/**
 * 3-Step New Project Wizard for Maraflow:
 * Step 1: Project Identity & File Location
 * Step 2: Target PLC Hardware Platform & Capabilities
 * Step 3: IEC 61131-3 Language (FBD / Ladder LD) & Starter Template
 */
public class NewProjectWizardDialog extends JDialog {

    private int currentStep = 0;
    private Project createdProject = null;

    private final JLabel[] stepLabels = new JLabel[3];
    private final CardLayout stepCards = new CardLayout();
    private final JPanel centerCardPanel = new JPanel(stepCards);

    // Step 1 controls
    private JTextField projectNameField;
    private JTextField locationDirField;
    private JCheckBox saveImmediatelyCheck;
    private JTextField authorField;
    private JTextArea descriptionArea;

    // Step 2 controls
    private JComboBox<String> platformCombo;
    private DefaultTableModel hwSpecTableModel;

    // Step 3 controls
    private JRadioButton fbdRadio;
    private JRadioButton ldRadio;
    private JRadioButton stRadio;
    private JTextField initialPageNameField;
    private JComboBox<PlcThread> defaultThreadCombo;

    // Navigation buttons
    private JButton backBtn;
    private JButton nextBtn;
    private JButton finishBtn;

    private static final String[] PLATFORMS = {
            "STM32F407-Maraflow-Std",
            "STM32F407-Maraflow-IO64",
            "STM32F407-Maraflow-Mini"
    };

    public NewProjectWizardDialog(Frame owner) {
        this(owner, IecLanguage.FBD);
    }

    public NewProjectWizardDialog(Frame owner, IecLanguage preselectedLanguage) {
        super(owner, "New Project Wizard - Maraflow PLC IDE", true);
        buildUi(preselectedLanguage);
        updateStepAppearance();
        pack();
        setMinimumSize(new Dimension(680, 440));
        setLocationRelativeTo(owner);
    }

    private void buildUi(IecLanguage preselectedLanguage) {
        JPanel root = new JPanel(new BorderLayout(0, 0));

        // Top Banner (Win7/95 engineering dialog header)
        JPanel banner = new JPanel(new BorderLayout());
        banner.setBackground(new Color(0x1F, 0x4E, 0x79));
        banner.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
        JLabel bannerTitle = new JLabel("Create New Maraflow PLC Project");
        bannerTitle.setFont(AppFonts.ui(Font.BOLD, 13));
        bannerTitle.setForeground(Color.WHITE);
        JLabel bannerSub = new JLabel("Configure project metadata, target STM32F407 PLC hardware, and IEC 61131-3 language (FBD / Ladder).");
        bannerSub.setFont(AppFonts.ui(Font.PLAIN, 11));
        bannerSub.setForeground(new Color(0xD6, 0xEA, 0xF8));
        banner.add(bannerTitle, BorderLayout.NORTH);
        banner.add(bannerSub, BorderLayout.SOUTH);
        root.add(banner, BorderLayout.NORTH);

        // Left Step Sidebar
        JPanel sidebar = new JPanel(new GridLayout(6, 1, 0, 4));
        sidebar.setBackground(new Color(0xE6, 0xEC, 0xF2));
        sidebar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 0, 1, new Color(0xB8, 0xB8, 0xB8)),
                BorderFactory.createEmptyBorder(12, 10, 12, 10)
        ));
        sidebar.setPreferredSize(new Dimension(185, 0));

        String[] stepTitles = {
                "1. Project Identity",
                "2. Target Hardware",
                "3. IEC 61131-3 Language"
        };
        for (int i = 0; i < 3; i++) {
            JLabel lbl = new JLabel("  " + stepTitles[i]);
            lbl.setOpaque(true);
            lbl.setFont(AppFonts.ui(Font.BOLD, 11));
            lbl.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
            stepLabels[i] = lbl;
            sidebar.add(lbl);
        }
        root.add(sidebar, BorderLayout.WEST);

        // Center Cards
        centerCardPanel.setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));
        centerCardPanel.add(buildStep1Panel(), "STEP_0");
        centerCardPanel.add(buildStep2Panel(), "STEP_1");
        centerCardPanel.add(buildStep3Panel(preselectedLanguage), "STEP_2");
        root.add(centerCardPanel, BorderLayout.CENTER);

        // Bottom Navigation Bar
        JPanel bottomBar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
        bottomBar.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(0xB8, 0xB8, 0xB8)));

        backBtn = new JButton("< Back");
        backBtn.setPreferredSize(new Dimension(90, 28));
        backBtn.addActionListener(e -> {
            if (currentStep > 0) {
                currentStep--;
                updateStepAppearance();
            }
        });

        nextBtn = new JButton("Next >");
        nextBtn.setPreferredSize(new Dimension(90, 28));
        nextBtn.addActionListener(e -> {
            if (validateCurrentStep() && currentStep < 2) {
                currentStep++;
                updateStepAppearance();
            }
        });

        finishBtn = new JButton("Finish", VectorIcons.greenCheckIcon());
        finishBtn.setPreferredSize(new Dimension(100, 28));
        finishBtn.addActionListener(e -> onFinish());

        JButton cancelBtn = new JButton("Cancel", VectorIcons.redCrossIcon());
        cancelBtn.setPreferredSize(new Dimension(95, 28));
        cancelBtn.addActionListener(e -> dispose());

        bottomBar.add(backBtn);
        bottomBar.add(nextBtn);
        bottomBar.add(finishBtn);
        bottomBar.add(cancelBtn);
        root.add(bottomBar, BorderLayout.SOUTH);

        setContentPane(root);
    }

    private JPanel buildStep1Panel() {
        JPanel p = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 6, 5, 6);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.anchor = GridBagConstraints.WEST;

        projectNameField = new JTextField("plc_project_1", 24);
        locationDirField = new JTextField(System.getProperty("user.home"), 20);
        JButton browseBtn = new JButton("Browse...");
        browseBtn.addActionListener(e -> {
            JFileChooser fc = new JFileChooser(locationDirField.getText());
            fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                locationDirField.setText(fc.getSelectedFile().getAbsolutePath());
            }
        });

        saveImmediatelyCheck = new JCheckBox("Create .mfl project file on disk immediately", false);
        authorField = new JTextField(System.getProperty("user.name", "Engineer"), 24);
        descriptionArea = new JTextArea(4, 24);
        descriptionArea.setLineWrap(true);
        descriptionArea.setWrapStyleWord(true);

        int row = 0;
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0;
        p.add(new JLabel("Project Name:"), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0;
        p.add(projectNameField, gbc);

        row++;
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0;
        p.add(new JLabel("Project Directory:"), gbc);
        JPanel dirPanel = new JPanel(new BorderLayout(4, 0));
        dirPanel.add(locationDirField, BorderLayout.CENTER);
        dirPanel.add(browseBtn, BorderLayout.EAST);
        gbc.gridx = 1; gbc.weightx = 1.0;
        p.add(dirPanel, gbc);

        row++;
        gbc.gridx = 1; gbc.gridy = row;
        p.add(saveImmediatelyCheck, gbc);

        row++;
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0;
        p.add(new JLabel("Author / Engineer:"), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0;
        p.add(authorField, gbc);

        row++;
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0;
        gbc.anchor = GridBagConstraints.NORTHWEST;
        p.add(new JLabel("Description:"), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0; gbc.weighty = 1.0;
        gbc.fill = GridBagConstraints.BOTH;
        p.add(new JScrollPane(descriptionArea), gbc);

        return p;
    }

    private JPanel buildStep2Panel() {
        JPanel p = new JPanel(new BorderLayout(8, 8));

        JPanel topRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        topRow.setBorder(BorderFactory.createTitledBorder("Select Target PLC Hardware Model"));
        topRow.add(new JLabel("Target PLC Model:"));
        platformCombo = new JComboBox<>(PLATFORMS);
        platformCombo.addActionListener(e -> updateHardwareSpecTable());
        topRow.add(platformCombo);
        p.add(topRow, BorderLayout.NORTH);

        hwSpecTableModel = new DefaultTableModel(new Object[]{"Subsystem / Capability", "Specification"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        JTable specTable = new JTable(hwSpecTableModel);
        specTable.setRowHeight(AppFonts.scaled(20));
        specTable.getColumnModel().getColumn(0).setPreferredWidth(180);
        specTable.getColumnModel().getColumn(1).setPreferredWidth(280);
        updateHardwareSpecTable();

        JScrollPane sp = new JScrollPane(specTable);
        sp.setBorder(BorderFactory.createTitledBorder("Hardware Capabilities & Memory Limits"));
        p.add(sp, BorderLayout.CENTER);

        return p;
    }

    private void updateHardwareSpecTable() {
        if (hwSpecTableModel == null) return;
        hwSpecTableModel.setRowCount(0);
        String plat = (String) platformCombo.getSelectedItem();
        if (plat == null) plat = PLATFORMS[0];

        boolean isIo64 = plat.contains("IO64");
        boolean isMini = plat.contains("Mini");

        hwSpecTableModel.addRow(new Object[]{"Microcontroller & Core", "STM32F407VGT6 ARM Cortex-M4F @ 168 MHz"});
        hwSpecTableModel.addRow(new Object[]{"RTOS & Runtime", "FreeRTOS + Maraflow Deterministic Bytecode VM"});
        hwSpecTableModel.addRow(new Object[]{"Digital Inputs (24V Opto)", isIo64 ? "32x DI (DI0..DI31)" : (isMini ? "8x DI (DI0..DI7)" : "16x DI (DI0..DI15)")});
        hwSpecTableModel.addRow(new Object[]{"Digital Outputs (Relay/SSR)", isIo64 ? "32x DO (DO0..DO31)" : (isMini ? "8x DO (DO0..DO7)" : "16x DO (DO0..DO15)")});
        hwSpecTableModel.addRow(new Object[]{"Analog Inputs (0-10V / 4-20mA)", isMini ? "4x 12-bit ADC (AI0..AI3)" : "8x 12-bit ADC (AI0..AI7)"});
        hwSpecTableModel.addRow(new Object[]{"Analog Outputs & PWM", isMini ? "2x DAC (AO0..1), 2x PWM" : "4x DAC (AO0..3), 4x PWM"});
        hwSpecTableModel.addRow(new Object[]{"Bytecode Flash Partition", "64 KiB (65,536 Bytes)"});
        hwSpecTableModel.addRow(new Object[]{"VM Data RAM Arena", "32 KiB (32,768 Bytes)"});
        hwSpecTableModel.addRow(new Object[]{"External Retentive EEPROM", "64 KiB I2C FRAM/EEPROM (65,536 Bytes)"});
        hwSpecTableModel.addRow(new Object[]{"Cyclic Execution Threads", "T1 (1000ms), T2 (100ms), T3 (20ms), T4 (10ms), T5 (Idle)"});
    }

    private JPanel buildStep3Panel(IecLanguage preselectedLanguage) {
        JPanel p = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(6, 6, 6, 6);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.anchor = GridBagConstraints.WEST;

        JPanel langBox = new JPanel(new GridLayout(3, 1, 4, 6));
        langBox.setBorder(BorderFactory.createTitledBorder("Initial Page IEC 61131-3 Programming Language"));

        fbdRadio = new JRadioButton("FBD - Function Block Diagram (2D wired function blocks, buses, SCADA visuals)");
        ldRadio = new JRadioButton("LD - Ladder Diagram (Structured rungs, power rails, contacts, coils, and function blocks)");
        stRadio = new JRadioButton("ST - Structured Text (Split variable/pin table + high-level IEC 61131-3 structured code)");
        fbdRadio.setFont(AppFonts.ui(Font.BOLD, 11));
        ldRadio.setFont(AppFonts.ui(Font.BOLD, 11));
        stRadio.setFont(AppFonts.ui(Font.BOLD, 11));

        ButtonGroup bg = new ButtonGroup();
        bg.add(fbdRadio);
        bg.add(ldRadio);
        bg.add(stRadio);

        if (preselectedLanguage == IecLanguage.LD) {
            ldRadio.setSelected(true);
        } else if (preselectedLanguage == IecLanguage.ST) {
            stRadio.setSelected(true);
        } else {
            fbdRadio.setSelected(true);
        }

        langBox.add(fbdRadio);
        langBox.add(ldRadio);
        langBox.add(stRadio);

        gbc.gridx = 0; gbc.gridy = 0; gbc.gridwidth = 2; gbc.weightx = 1.0;
        p.add(langBox, gbc);

        gbc.gridwidth = 1;
        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0;
        p.add(new JLabel("Initial Page Name:"), gbc);
        String defaultPageName = (preselectedLanguage == IecLanguage.LD) ? "Main_LD"
                : (preselectedLanguage == IecLanguage.ST ? "Main_ST" : "Page 1");
        initialPageNameField = new JTextField(defaultPageName, 20);
        gbc.gridx = 1; gbc.weightx = 1.0;
        p.add(initialPageNameField, gbc);

        fbdRadio.addActionListener(e -> {
            String cur = initialPageNameField.getText().trim();
            if ("Main_LD".equals(cur) || "Main_ST".equals(cur)) {
                initialPageNameField.setText("Page 1");
            }
        });
        ldRadio.addActionListener(e -> {
            String cur = initialPageNameField.getText().trim();
            if ("Page 1".equals(cur) || "Main_ST".equals(cur)) {
                initialPageNameField.setText("Main_LD");
            }
        });
        stRadio.addActionListener(e -> {
            String cur = initialPageNameField.getText().trim();
            if ("Page 1".equals(cur) || "Main_LD".equals(cur)) {
                initialPageNameField.setText("Main_ST");
            }
        });

        gbc.gridx = 0; gbc.gridy = 2; gbc.weightx = 0;
        p.add(new JLabel("Default Cyclic Thread:"), gbc);
        defaultThreadCombo = new JComboBox<>(PlcThread.values());
        defaultThreadCombo.setSelectedItem(PlcThread.THREAD_2);
        gbc.gridx = 1; gbc.weightx = 1.0;
        p.add(defaultThreadCombo, gbc);

        JLabel noteLbl = new JLabel("Note: You can freely mix FBD, Ladder (LD), Structured Text (ST) pages, *Macros, and !EStop pages in one project.");
        noteLbl.setFont(AppFonts.ui(Font.ITALIC, 11));
        noteLbl.setForeground(new Color(0x44, 0x55, 0x66));
        gbc.gridx = 0; gbc.gridy = 3; gbc.gridwidth = 2;
        p.add(noteLbl, gbc);

        return p;
    }

    private void updateStepAppearance() {
        stepCards.show(centerCardPanel, "STEP_" + currentStep);
        for (int i = 0; i < 3; i++) {
            boolean active = (i == currentStep);
            stepLabels[i].setBackground(active ? new Color(0x00, 0x80, 0x80) : new Color(0xE6, 0xEC, 0xF2));
            stepLabels[i].setForeground(active ? Color.WHITE : new Color(0x22, 0x22, 0x22));
        }
        backBtn.setEnabled(currentStep > 0);
        nextBtn.setEnabled(currentStep < 2);
        getRootPane().setDefaultButton(currentStep < 2 ? nextBtn : finishBtn);
    }

    private boolean validateCurrentStep() {
        if (currentStep == 0) {
            if (projectNameField.getText().trim().isEmpty()) {
                JOptionPane.showMessageDialog(this, "Please enter a project name.", "Validation", JOptionPane.WARNING_MESSAGE);
                return false;
            }
        }
        return true;
    }

    private void onFinish() {
        if (!validateCurrentStep()) return;

        String projName = projectNameField.getText().trim();
        String platform = (String) platformCombo.getSelectedItem();
        IecLanguage lang = ldRadio.isSelected() ? IecLanguage.LD
                : (stRadio.isSelected() ? IecLanguage.ST : IecLanguage.FBD);
        PlcThread defThread = (PlcThread) defaultThreadCombo.getSelectedItem();
        int tid = defThread != null ? defThread.getId() : 2;

        Project proj = new Project(projName, platform, lang, tid);

        String pageName = initialPageNameField.getText().trim();
        if (!pageName.isEmpty() && !proj.getPages().isEmpty()) {
            proj.getPages().getFirst().setName(pageName);
        }
        proj.setAuthor(authorField.getText().trim());
        proj.setDescription(descriptionArea.getText().trim());

        if (saveImmediatelyCheck.isSelected()) {
            File dir = new File(locationDirField.getText().trim());
            if (!dir.exists()) {
                dir.mkdirs();
            }
            String fileName = projName.toLowerCase().endsWith(".mfl") ? projName : (projName + ".mfl");
            File targetFile = new File(dir, fileName);
            try {
                MflProjectSerializer.saveProject(proj, targetFile);
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this,
                        "Could not save initial file:\n" + ex.getMessage(),
                        "Save Warning",
                        JOptionPane.WARNING_MESSAGE);
            }
        }

        this.createdProject = proj;
        dispose();
    }

    public Project getCreatedProject() {
        return createdProject;
    }
}
