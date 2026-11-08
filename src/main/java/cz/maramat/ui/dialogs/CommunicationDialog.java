package cz.maramat.ui.dialogs;

import cz.maramat.model.Project;
import cz.maramat.settings.AppSettings;
import cz.maramat.ui.icons.VectorIcons;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;

public class CommunicationDialog extends JDialog {
    public enum Mode {
        SETTINGS_ONLY,
        UPLOAD,
        WATCH
    }

    private boolean connectionSucceeded = false;
    private final Mode mode;
    private final Project project;

    private JTabbedPane methodTabs;
    private JComboBox<String> usbPortBox;
    private JComboBox<String> usbBaudBox;
    private JSpinner usbAddrSpinner;
    private JPasswordField usbPassField;

    private JComboBox<String> rs485PortBox;
    private JComboBox<String> rs485BaudBox;
    private JComboBox<String> rs485ParityBox;
    private JSpinner rs485StationSpinner;
    private JPasswordField rs485PassField;

    private JTextField ethIpField;
    private JSpinner ethPortSpinner;
    private JSpinner ethUnitSpinner;
    private JPasswordField ethPassField;
    private JComboBox<String> detectedPlcTargetBox;

    private static final String[] BAUD_RATES = {
            "9600", "19200", "38400", "57600", "115200", "230400", "460800", "921600"
    };
    private static final String[] SERIAL_PORTS = {
            "/dev/ttyACM0", "/dev/ttyUSB0", "/dev/ttyUSB1", "COM1", "COM3", "COM4"
    };
    private static final String[] TARGET_PLATFORMS = {
            "STM32F407-Maraflow-Std", "STM32F407-Maraflow-IO64", "STM32F407-Maraflow-Mini"
    };

    public CommunicationDialog(Frame owner, Project project, Mode mode) {
        super(owner, switch (mode) {
            case UPLOAD -> "Communication - Upload Bytecode to PLC";
            case WATCH -> "Communication - Connect Watch Mode";
            case SETTINGS_ONLY -> "Communication Settings";
        }, true);
        this.project = project;
        this.mode = mode;
        buildUi();
        pack();
        setMinimumSize(new Dimension(450, 320));
        setLocationRelativeTo(owner);
    }

    private void buildUi() {
        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        methodTabs = new JTabbedPane();
        methodTabs.addTab("USB (CDC)", buildUsbPanel());
        methodTabs.addTab("RS485 (Serial)", buildRs485Panel());
        methodTabs.addTab("Ethernet (TCP)", buildEthernetPanel());

        String defMethod = AppSettings.get().getDefaultCommMethod();
        if ("RS485 (Serial)".equals(defMethod)) {
            methodTabs.setSelectedIndex(1);
        } else if ("Ethernet (TCP)".equals(defMethod)) {
            methodTabs.setSelectedIndex(2);
        } else {
            methodTabs.setSelectedIndex(0);
        }

        root.add(methodTabs, BorderLayout.CENTER);

        JPanel targetRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        targetRow.setBorder(BorderFactory.createTitledBorder("Target PLC Verification"));
        targetRow.add(new JLabel("Detected PLC Model:"));
        detectedPlcTargetBox = new JComboBox<>(TARGET_PLATFORMS);
        detectedPlcTargetBox.setSelectedItem(project.getTargetPlatform());
        targetRow.add(detectedPlcTargetBox);

        JPanel southContainer = new JPanel(new BorderLayout(0, 6));
        southContainer.add(targetRow, BorderLayout.NORTH);

        JPanel btnBar = new JPanel(new FlowLayout(FlowLayout.CENTER, 14, 2));
        String okLabel = switch (mode) {
            case UPLOAD -> "Upload";
            case WATCH -> "Connect & Watch";
            case SETTINGS_ONLY -> "OK";
        };
        JButton okBtn = new JButton(okLabel, VectorIcons.greenCheckIcon());
        okBtn.setPreferredSize(new Dimension(150, 28));
        okBtn.addActionListener(e -> onExecute());

        JButton cancelBtn = new JButton("Cancel", VectorIcons.redCrossIcon());
        cancelBtn.setPreferredSize(new Dimension(105, 28));
        cancelBtn.addActionListener(e -> dispose());

        btnBar.add(okBtn);
        btnBar.add(cancelBtn);
        southContainer.add(btnBar, BorderLayout.SOUTH);

        root.add(southContainer, BorderLayout.SOUTH);
        getRootPane().setDefaultButton(okBtn);
        setContentPane(root);
    }

    private JPanel buildUsbPanel() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        GridBagConstraints gbc = createGbc();

        usbPortBox = new JComboBox<>(SERIAL_PORTS);
        usbPortBox.setEditable(true);
        usbPortBox.setSelectedItem(AppSettings.get().getDefaultSerialPort());
        usbBaudBox = new JComboBox<>(BAUD_RATES);
        usbBaudBox.setSelectedItem(AppSettings.get().getDefaultBaudRate());
        usbAddrSpinner = new JSpinner(new SpinnerNumberModel(1, 1, 254, 1));
        usbPassField = new JPasswordField("admin", 14);

        addRow(p, gbc, 0, "USB Port:", usbPortBox);
        addRow(p, gbc, 1, "Baud Rate:", usbBaudBox);
        addRow(p, gbc, 2, "PLC Address:", usbAddrSpinner);
        addRow(p, gbc, 3, "PLC Password:", usbPassField);
        return p;
    }

    private JPanel buildRs485Panel() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        GridBagConstraints gbc = createGbc();

        rs485PortBox = new JComboBox<>(SERIAL_PORTS);
        rs485PortBox.setEditable(true);
        rs485PortBox.setSelectedItem(AppSettings.get().getDefaultSerialPort());
        rs485BaudBox = new JComboBox<>(BAUD_RATES);
        rs485BaudBox.setSelectedItem(AppSettings.get().getDefaultBaudRate());
        rs485ParityBox = new JComboBox<>(new String[]{"None (8N1)", "Even (8E1)", "Odd (8O1)"});
        rs485StationSpinner = new JSpinner(new SpinnerNumberModel(1, 1, 247, 1));
        rs485PassField = new JPasswordField("admin", 14);

        addRow(p, gbc, 0, "RS485 Port:", rs485PortBox);
        addRow(p, gbc, 1, "Baud Rate:", rs485BaudBox);
        addRow(p, gbc, 2, "Framing / Parity:", rs485ParityBox);
        addRow(p, gbc, 3, "Station Address:", rs485StationSpinner);
        addRow(p, gbc, 4, "PLC Password:", rs485PassField);
        return p;
    }

    private JPanel buildEthernetPanel() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        GridBagConstraints gbc = createGbc();

        ethIpField = new JTextField("192.168.1.50", 15);
        ethPortSpinner = new JSpinner(new SpinnerNumberModel(5020, 1, 65535, 1));
        ethUnitSpinner = new JSpinner(new SpinnerNumberModel(1, 1, 254, 1));
        ethPassField = new JPasswordField("admin", 14);

        addRow(p, gbc, 0, "PLC IP Address:", ethIpField);
        addRow(p, gbc, 1, "TCP Port:", ethPortSpinner);
        addRow(p, gbc, 2, "Unit / Station ID:", ethUnitSpinner);
        addRow(p, gbc, 3, "PLC Password:", ethPassField);
        return p;
    }

    private GridBagConstraints createGbc() {
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 6, 4, 6);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        return gbc;
    }

    private void addRow(JPanel p, GridBagConstraints gbc, int row, String label, java.awt.Component comp) {
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.weightx = 0.0;
        p.add(new JLabel(label), gbc);
        gbc.gridx = 1;
        gbc.weightx = 1.0;
        p.add(comp, gbc);
    }

    private void onExecute() {
        String detected = (String) detectedPlcTargetBox.getSelectedItem();
        if (detected != null && !detected.equals(project.getTargetPlatform())) {
            int choice = JOptionPane.showConfirmDialog(
                    this,
                    "Warning: Project target platform (" + project.getTargetPlatform()
                            + ") does not match connected PLC hardware (" + detected + ").\n"
                            + "Proceed anyway?",
                    "Target Platform Mismatch Warning",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE
            );
            if (choice != JOptionPane.YES_OPTION) {
                return;
            }
        }
        connectionSucceeded = true;
        dispose();
    }

    public boolean isConnectionSucceeded() {
        return connectionSucceeded;
    }
}
