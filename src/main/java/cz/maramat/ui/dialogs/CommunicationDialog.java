package cz.maramat.ui.dialogs;

import cz.maramat.comm.MaraProtClient;
import cz.maramat.comm.MaraTransport;
import cz.maramat.compiler.BytecodeCompiler;
import cz.maramat.compiler.MflwEmitter;
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
import javax.swing.JProgressBar;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
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
        WATCH,
        SYNC_RTC,
        UNIT_CONFIG
    }

    private boolean connectionSucceeded = false;
    private MaraProtClient connectedClient;
    private MflwEmitter.EmittedProgram compiledProgram;
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
    private JTextField ethPortField;
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
            "Duino - S"
    };

    public CommunicationDialog(Frame owner, Project project, Mode mode) {
        super(owner, switch (mode) {
            case UPLOAD -> "Communication - Upload Bytecode to PLC";
            case WATCH -> "Communication - Connect Watch Mode";
            case SYNC_RTC -> "Communication - Set PLC RTC / Time";
            case UNIT_CONFIG -> "Communication - Connect to Unit";
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
            case SYNC_RTC -> "Set RTC Time";
            case UNIT_CONFIG -> "Connect & Read";
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
        getRootPane().getInputMap(javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_F1, 0), "helpF1");
        getRootPane().getActionMap().put("helpF1", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                int idx = methodTabs.getSelectedIndex();
                String pageId = (idx == 1) ? "getting-started/rs485-and-modbus" : "getting-started/upload";
                cz.maramat.ui.help.HelpWindow.open(null, pageId);
            }
        });
        setContentPane(root);
    }

    private JPanel buildUsbPanel() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        GridBagConstraints gbc = createGbc();

        java.util.List<String> detectedPorts = MaraTransport.listSerialPorts();
        String[] portItems = detectedPorts.isEmpty() ? SERIAL_PORTS : detectedPorts.toArray(new String[0]);
        usbPortBox = new JComboBox<>(portItems);
        usbPortBox.setEditable(true);
        String savedUsbPort = AppSettings.get().getDefaultSerialPort();
        if (savedUsbPort != null && !savedUsbPort.isBlank()) {
            usbPortBox.setSelectedItem(savedUsbPort);
        } else if (!detectedPorts.isEmpty()) {
            usbPortBox.setSelectedIndex(0);
        }
        usbBaudBox = new JComboBox<>(BAUD_RATES);
        usbBaudBox.setSelectedItem(AppSettings.get().getDefaultBaudRate());
        usbAddrSpinner = new JSpinner(new SpinnerNumberModel(AppSettings.get().getLastUsbStationId(), 0, 254, 1));
        usbPassField = new JPasswordField(AppSettings.get().loadCommPassword("usb", "admin"), 14);

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
        rs485PortBox.setSelectedItem(AppSettings.get().getLastRs485Port());
        rs485BaudBox = new JComboBox<>(BAUD_RATES);
        rs485BaudBox.setSelectedItem(AppSettings.get().getLastRs485Baud());
        rs485ParityBox = new JComboBox<>(new String[]{"None (8N1)", "Even (8E1)", "Odd (8O1)"});
        rs485ParityBox.setSelectedItem(AppSettings.get().getLastRs485Parity());
        rs485StationSpinner = new JSpinner(new SpinnerNumberModel(AppSettings.get().getLastRs485StationId(), 1, 247, 1));
        rs485PassField = new JPasswordField(AppSettings.get().loadCommPassword("rs485", "admin"), 14);

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

        ethIpField = new JTextField(AppSettings.get().getLastTcpIp(), 15);
        ethPortField = PlcConfigDialog.createDigitsOnlyField(String.valueOf(AppSettings.get().getLastTcpPort()), 8);
        ethUnitSpinner = new JSpinner(new SpinnerNumberModel(AppSettings.get().getLastTcpUnitId(), 0, 254, 1));
        ethPassField = new JPasswordField(AppSettings.get().loadCommPassword("tcp", "admin"), 14);

        addRow(p, gbc, 0, "PLC IP Address:", ethIpField);
        addRow(p, gbc, 1, "TCP Port:", ethPortField);
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
        saveInputsToSettings();
        if (mode == Mode.SETTINGS_ONLY) {
            connectionSucceeded = true;
            dispose();
            return;
        }

        MaraTransport transport = createSelectedTransport();
        int stationId = getSelectedStationId();
        String password = getSelectedPassword();
        MaraProtClient client = new MaraProtClient(transport, stationId);

        if (mode == Mode.UPLOAD || mode == Mode.WATCH) {
            executeUploadWithProgress(client, password);
            return;
        }

        try {
            client.connect();
            client.authenticate(password);
            if (mode == Mode.SYNC_RTC) {
                long epochSec = java.time.Instant.now().getEpochSecond();
                client.setRtcUnixSec(epochSec);
                client.close();
            } else if (mode == Mode.UNIT_CONFIG) {
                client.getInfo();
                client.readUnitConfig(project.getUnitConfig());
                try { client.readModbusMap(project.getUnitConfig()); } catch (Exception ignored) {}
                this.connectedClient = client;
            } else {
                client.getInfo();
                this.compiledProgram = BytecodeCompiler.compileProgram(project);
                this.connectedClient = client;
            }
            connectionSucceeded = true;
            dispose();
        } catch (Exception ex) {
            try { client.close(); } catch (Exception ignored) {}
            JOptionPane.showMessageDialog(
                    this,
                    "MaraProt communication failed:\n" + ex.getMessage(),
                    "Communication Error",
                    JOptionPane.ERROR_MESSAGE
            );
        }
    }

    private void executeUploadWithProgress(MaraProtClient client, String password) {
        this.compiledProgram = BytecodeCompiler.compileProgram(project);
        byte[] image = compiledProgram.image();

        JDialog progressDlg = new JDialog(this, "Uploading Program to PLC", true);
        JPanel p = new JPanel(new BorderLayout(8, 8));
        p.setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));
        JLabel statusLbl = new JLabel("Connecting to PLC...");
        JProgressBar bar = new JProgressBar(0, 100);
        bar.setStringPainted(true);
        bar.setPreferredSize(new Dimension(340, 22));
        JLabel bytesLbl = new JLabel("0 / " + image.length + " bytes");
        p.add(statusLbl, BorderLayout.NORTH);
        p.add(bar, BorderLayout.CENTER);
        p.add(bytesLbl, BorderLayout.SOUTH);
        progressDlg.setContentPane(p);
        progressDlg.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
        progressDlg.pack();
        progressDlg.setLocationRelativeTo(this);

        SwingWorker<Void, int[]> worker = new SwingWorker<>() {
            @Override
            protected Void doInBackground() throws Exception {
                client.connect();
                client.authenticate(password);
                SwingUtilities.invokeLater(() -> statusLbl.setText("Uploading bytecode (" + image.length + " B)..."));
                client.getInfo();
                client.uploadImage(image, true, pct -> {
                    int sent = (int) ((pct / 100.0) * image.length);
                    publish(new int[]{pct, sent});
                });
                if (!project.getUnitConfig().getModbusSlaveMap().isEmpty()) {
                    try { client.writeModbusMap(project.getUnitConfig(), project); } catch (Exception ignored) {}
                }
                if (mode == Mode.UPLOAD) {
                    client.close();
                }
                return null;
            }

            @Override
            protected void process(java.util.List<int[]> chunks) {
                int[] latest = chunks.get(chunks.size() - 1);
                bar.setValue(latest[0]);
                bytesLbl.setText(latest[1] + " / " + image.length + " bytes (" + latest[0] + "%)");
                if (latest[0] >= 100) {
                    statusLbl.setText("Verifying CRC & starting VM...");
                }
            }

            @Override
            protected void done() {
                progressDlg.dispose();
                try {
                    get();
                    if (mode == Mode.WATCH) {
                        connectedClient = client;
                    }
                    connectionSucceeded = true;
                    dispose();
                } catch (Exception ex) {
                    try { client.close(); } catch (Exception ignored) {}
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    JOptionPane.showMessageDialog(
                            CommunicationDialog.this,
                            "MaraProt upload failed:\n" + cause.getMessage(),
                            "Upload Error",
                            JOptionPane.ERROR_MESSAGE
                    );
                }
            }
        };
        worker.execute();
        progressDlg.setVisible(true);
    }

    private void saveInputsToSettings() {
        AppSettings s = AppSettings.get();
        s.setDefaultCommMethod(methodTabs.getTitleAt(methodTabs.getSelectedIndex()));
        s.setDefaultSerialPort(String.valueOf(usbPortBox.getSelectedItem()).trim());
        s.setDefaultBaudRate(String.valueOf(usbBaudBox.getSelectedItem()).trim());
        s.setLastUsbStationId(((Number) usbAddrSpinner.getValue()).intValue());
        s.saveCommPassword("usb", new String(usbPassField.getPassword()));

        s.setLastRs485Port(String.valueOf(rs485PortBox.getSelectedItem()).trim());
        s.setLastRs485Baud(String.valueOf(rs485BaudBox.getSelectedItem()).trim());
        s.setLastRs485Parity(String.valueOf(rs485ParityBox.getSelectedItem()).trim());
        s.setLastRs485StationId(((Number) rs485StationSpinner.getValue()).intValue());
        s.saveCommPassword("rs485", new String(rs485PassField.getPassword()));

        s.setLastTcpIp(ethIpField.getText().trim());
        s.setLastTcpPort(parseTcpPort());
        s.setLastTcpUnitId(((Number) ethUnitSpinner.getValue()).intValue());
        s.saveCommPassword("tcp", new String(ethPassField.getPassword()));
        s.save();
    }

    private int parseTcpPort() {
        try {
            int p = Integer.parseInt(ethPortField.getText().trim());
            return (p >= 1 && p <= 65535) ? p : 5020;
        } catch (NumberFormatException e) {
            return 5020;
        }
    }

    private String getSelectedPassword() {
        int tab = methodTabs.getSelectedIndex();
        if (tab == 1) return new String(rs485PassField.getPassword());
        if (tab == 2) return new String(ethPassField.getPassword());
        return new String(usbPassField.getPassword());
    }

    private MaraTransport createSelectedTransport() {
        int tab = methodTabs.getSelectedIndex();
        if (tab == 2) {
            String host = ethIpField.getText().trim();
            int port = parseTcpPort();
            return new MaraTransport.TcpTransport(host, port);
        }
        if (tab == 1) {
            String port = String.valueOf(rs485PortBox.getSelectedItem()).trim();
            int baud = Integer.parseInt(String.valueOf(rs485BaudBox.getSelectedItem()).trim());
            return new MaraTransport.SerialCdcTransport(port, baud);
        }
        String port = String.valueOf(usbPortBox.getSelectedItem()).trim();
        int baud = Integer.parseInt(String.valueOf(usbBaudBox.getSelectedItem()).trim());
        return new MaraTransport.SerialCdcTransport(port, baud);
    }

    private int getSelectedStationId() {
        int tab = methodTabs.getSelectedIndex();
        if (tab == 1) return ((Number) rs485StationSpinner.getValue()).intValue();
        if (tab == 2) return ((Number) ethUnitSpinner.getValue()).intValue();
        return 0; // Point-to-point default for USB CDC
    }

    public boolean isConnectionSucceeded() {
        return connectionSucceeded;
    }

    public MaraProtClient getConnectedClient() {
        return connectedClient;
    }

    public MflwEmitter.EmittedProgram getCompiledProgram() {
        return compiledProgram;
    }
}
