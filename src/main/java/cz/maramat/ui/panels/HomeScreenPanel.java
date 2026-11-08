package cz.maramat.ui.panels;

import cz.maramat.model.IecLanguage;
import cz.maramat.model.Project;
import cz.maramat.ui.AppFonts;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/**
 * Homescreen displayed inside MainWindow when no project is open (on startup and after File -> Close Project).
 * Provides quick access to the New Project Wizard, Open Project dialog, Recent Projects table, and Demo Starters.
 */
public class HomeScreenPanel extends JPanel {

    public interface HomeScreenListener {
        void onNewProjectWizard(IecLanguage preselectedLanguage);
        void onOpenProjectDialog();
        void onOpenRecentFile(File file);
        void onClearRecentFiles();
        void onLoadSampleProject(IecLanguage language);
    }

    private final HomeScreenListener listener;
    private DefaultTableModel recentTableModel;
    private JTable recentTable;
    private List<File> currentRecentFiles = List.of();

    public HomeScreenPanel(HomeScreenListener listener) {
        super(new BorderLayout());
        this.listener = listener;
        setBackground(new Color(0x7A, 0x8A, 0x99));
        setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));
        buildUi();
    }

    private void buildUi() {
        JPanel mainCard = new JPanel(new BorderLayout(0, 0));
        mainCard.setBackground(new Color(0xF0, 0xF3, 0xF7));
        mainCard.setBorder(BorderFactory.createLineBorder(new Color(0x3A, 0x4A, 0x5A), 1));

        // Top Header Bar
        JPanel headerBar = new JPanel(new BorderLayout());
        headerBar.setBackground(new Color(0x1F, 0x4E, 0x79));
        headerBar.setBorder(BorderFactory.createEmptyBorder(12, 16, 12, 16));

        JLabel titleLbl = new JLabel("Maraflow V" + Project.VERSION + " — Industrial PLC Programming Environment");
        titleLbl.setFont(AppFonts.ui(Font.BOLD, 15));
        titleLbl.setForeground(Color.WHITE);

        JLabel subLbl = new JLabel("IEC 61131-3 Function Block Diagram (FBD) & Ladder Diagram (LD)  |  Target: STM32F407 + FreeRTOS Bytecode VM");
        subLbl.setFont(AppFonts.ui(Font.PLAIN, 11));
        subLbl.setForeground(new Color(0xD6, 0xEA, 0xF8));

        headerBar.add(titleLbl, BorderLayout.NORTH);
        headerBar.add(subLbl, BorderLayout.SOUTH);
        mainCard.add(headerBar, BorderLayout.NORTH);

        // Center Content: Left Actions + Right Recent Projects & Platform Overview
        JPanel centerSplit = new JPanel(new GridBagLayout());
        centerSplit.setOpaque(false);
        centerSplit.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 6, 4, 6);
        gbc.fill = GridBagConstraints.BOTH;

        // Left Actions Column
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.weightx = 0.32;
        gbc.weighty = 1.0;
        centerSplit.add(buildLeftActionsPanel(), gbc);

        // Right Column (Recent Projects + Reference Info)
        gbc.gridx = 1;
        gbc.gridy = 0;
        gbc.weightx = 0.68;
        gbc.weighty = 1.0;
        centerSplit.add(buildRightRecentPanel(), gbc);

        mainCard.add(centerSplit, BorderLayout.CENTER);
        add(mainCard, BorderLayout.CENTER);
    }

    private JPanel buildLeftActionsPanel() {
        JPanel container = new JPanel(new BorderLayout(0, 10));
        container.setOpaque(false);

        JPanel startBox = new JPanel(new GridLayout(4, 1, 0, 8));
        startBox.setBackground(Color.WHITE);
        startBox.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder("Project Actions"),
                BorderFactory.createEmptyBorder(8, 10, 8, 10)
        ));

        JButton newWizardBtn = createActionButton("New Project Wizard... (Ctrl+N)", "Launch 3-step wizard to configure target PLC & language");
        newWizardBtn.addActionListener(e -> listener.onNewProjectWizard(IecLanguage.FBD));

        JButton newLdBtn = createActionButton("New Ladder (LD) Project...", "Pre-select IEC 61131-3 Ladder Diagram in New Project Wizard");
        newLdBtn.addActionListener(e -> listener.onNewProjectWizard(IecLanguage.LD));

        JButton newFbdBtn = createActionButton("New Function Block (FBD) Project...", "Pre-select IEC 61131-3 Function Block Diagram in New Project Wizard");
        newFbdBtn.addActionListener(e -> listener.onNewProjectWizard(IecLanguage.FBD));

        JButton openBtn = createActionButton("Open Project... (Ctrl+O)", "Open an existing Maraflow (.mfl) project file from disk");
        openBtn.addActionListener(e -> listener.onOpenProjectDialog());

        startBox.add(newWizardBtn);
        startBox.add(newLdBtn);
        startBox.add(newFbdBtn);
        startBox.add(openBtn);

        JPanel demoBox = new JPanel(new GridLayout(2, 1, 0, 8));
        demoBox.setBackground(Color.WHITE);
        demoBox.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder("Quick Demo Starters"),
                BorderFactory.createEmptyBorder(8, 10, 8, 10)
        ));

        JButton demoLdBtn = createActionButton("Load Sample Ladder (LD) Project", "Motor Start/Stop seal-in latch, TON fan timer, and analog fault comparator");
        demoLdBtn.addActionListener(e -> listener.onLoadSampleProject(IecLanguage.LD));

        JButton demoFbdBtn = createActionButton("Load Sample FBD Project", "8xDI digital inputs, Boolean logic, Value monitor, and 7-Segment display");
        demoFbdBtn.addActionListener(e -> listener.onLoadSampleProject(IecLanguage.FBD));

        demoBox.add(demoLdBtn);
        demoBox.add(demoFbdBtn);

        container.add(startBox, BorderLayout.NORTH);
        container.add(demoBox, BorderLayout.CENTER);
        return container;
    }

    private JButton createActionButton(String title, String subtitle) {
        JButton b = new JButton("<html><b>" + title + "</b><br><span style='font-size:9px;color:#445566;'>" + subtitle + "</span></html>");
        b.setHorizontalAlignment(SwingConstants.LEFT);
        b.setFocusPainted(false);
        b.setPreferredSize(new Dimension(250, 44));
        return b;
    }

    private JPanel buildRightRecentPanel() {
        JPanel right = new JPanel(new BorderLayout(0, 10));
        right.setOpaque(false);

        JPanel recentBox = new JPanel(new BorderLayout(0, 6));
        recentBox.setBackground(Color.WHITE);
        recentBox.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder("Recent Projects (.mfl)"),
                BorderFactory.createEmptyBorder(6, 8, 6, 8)
        ));

        recentTableModel = new DefaultTableModel(new Object[]{"Project Name", "File Path", "Last Modified"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        recentTable = new JTable(recentTableModel);
        recentTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        recentTable.setRowHeight(AppFonts.scaled(22));
        recentTable.getColumnModel().getColumn(0).setPreferredWidth(140);
        recentTable.getColumnModel().getColumn(1).setPreferredWidth(310);
        recentTable.getColumnModel().getColumn(2).setPreferredWidth(130);

        recentTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    openSelectedRecent();
                }
            }
        });

        recentBox.add(new JScrollPane(recentTable), BorderLayout.CENTER);

        JPanel recentBtns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        recentBtns.setOpaque(false);
        JButton openSelBtn = new JButton("Open Selected");
        openSelBtn.addActionListener(e -> openSelectedRecent());
        JButton clearBtn = new JButton("Clear Recent List");
        clearBtn.addActionListener(e -> listener.onClearRecentFiles());
        recentBtns.add(openSelBtn);
        recentBtns.add(clearBtn);
        recentBox.add(recentBtns, BorderLayout.SOUTH);

        right.add(recentBox, BorderLayout.CENTER);

        // Bottom Architecture Summary Box
        JPanel infoBox = new JPanel(new GridLayout(3, 2, 10, 4));
        infoBox.setBackground(new Color(0xEE, 0xF2, 0xF7));
        infoBox.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder("Target Runtime Architecture"),
                BorderFactory.createEmptyBorder(6, 10, 6, 10)
        ));
        infoBox.add(new JLabel("IEC 61131-3 Languages: FBD & Ladder (LD) per-page"));
        infoBox.add(new JLabel("Target MCU: STM32F407 @ 168 MHz (32K RAM / 64K Flash)"));
        infoBox.add(new JLabel("Ladder Elements: Contacts, Coils, Parallel Branches, FBs"));
        infoBox.add(new JLabel("Retentive Memory: 64 KiB External EEPROM"));
        infoBox.add(new JLabel("Diagnostics: Live Simulation, Watch Mode & 2D Scope"));
        infoBox.add(new JLabel("RTOS Threads: T1 (1s), T2 (100ms), T3 (20ms), T4 (10ms), T5 (Idle)"));

        right.add(infoBox, BorderLayout.SOUTH);
        return right;
    }

    private void openSelectedRecent() {
        int r = recentTable.getSelectedRow();
        if (r >= 0 && r < currentRecentFiles.size()) {
            listener.onOpenRecentFile(currentRecentFiles.get(r));
        }
    }

    public void setRecentFiles(List<File> files) {
        this.currentRecentFiles = List.copyOf(files);
        recentTableModel.setRowCount(0);
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm");
        for (File f : currentRecentFiles) {
            String name = f.getName();
            String mod = f.exists() ? sdf.format(new Date(f.lastModified())) : "Missing";
            recentTableModel.addRow(new Object[]{name, f.getAbsolutePath(), mod});
        }
    }
}
