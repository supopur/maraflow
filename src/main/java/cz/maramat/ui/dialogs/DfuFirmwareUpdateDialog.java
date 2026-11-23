package cz.maramat.ui.dialogs;

import cz.maramat.comm.MaraProtClient;
import cz.maramat.comm.MaraTransport;
import cz.maramat.ui.AppFonts;
import cz.maramat.ui.icons.VectorIcons;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

public class DfuFirmwareUpdateDialog extends JDialog {

    private final Consumer<String> logSink;
    private JComboBox<String> portBox;
    private JPasswordField passwordField;
    private JTextField fwFileField;
    private JTextArea outputArea;
    private JButton enterDfuBtn;
    private JButton flashBtn;

    public DfuFirmwareUpdateDialog(Frame owner, Consumer<String> logSink) {
        super(owner, "Duino - S Firmware Update (USB DFU)", true);
        this.logSink = logSink;
        buildUi();
        pack();
        setMinimumSize(new Dimension(AppFonts.scaled(600), AppFonts.scaled(420)));
        setLocationRelativeTo(owner);
    }

    private void buildUi() {
        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JPanel top = new JPanel(new GridBagLayout());
        top.setBorder(BorderFactory.createTitledBorder("USB DFU Bootloader & Firmware Flasher (macOS / Windows / Linux)"));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 6, 4, 6);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        List<String> ports = MaraTransport.listSerialPorts();
        portBox = new JComboBox<>(ports.isEmpty() ? new String[]{"/dev/cu.usbmodem1", "COM3"} : ports.toArray(new String[0]));
        portBox.setEditable(true);

        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0;
        top.add(new JLabel("PLC USB Port:"), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0;
        top.add(portBox, gbc);

        enterDfuBtn = new JButton("1. Reboot PLC into DFU");
        enterDfuBtn.addActionListener(e -> actionEnterDfu());
        gbc.gridx = 2; gbc.weightx = 0;
        top.add(enterDfuBtn, gbc);

        fwFileField = new JTextField(findDefaultFirmwarePath(), 28);
        JButton browseBtn = new JButton("Browse...");
        browseBtn.addActionListener(e -> actionBrowseFile());

        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0;
        top.add(new JLabel("Access Password:"), gbc);
        passwordField = new JPasswordField("admin", 16);
        gbc.gridx = 1; gbc.weightx = 1.0;
        top.add(passwordField, gbc);

        gbc.gridx = 0; gbc.gridy = 2; gbc.weightx = 0;
        top.add(new JLabel("Firmware (.bin/.elf):"), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0;
        top.add(fwFileField, gbc);
        gbc.gridx = 2; gbc.weightx = 0;
        top.add(browseBtn, gbc);

        root.add(top, BorderLayout.NORTH);

        outputArea = new JTextArea(12, 55);
        outputArea.setEditable(false);
        outputArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, AppFonts.scaled(12)));
        appendLine("Ready. Use Step 1 to reboot Duino - S into STM32 ROM DFU (0483:df11), or connect BOOT0=1, then click Flash.");
        String tool = detectDfuTool();
        appendLine(tool != null ? "Detected DFU tool: " + tool : "Warning: Neither dfu-util nor STM32_Programmer_CLI found in PATH.");
        root.add(new JScrollPane(outputArea), BorderLayout.CENTER);

        JPanel btnBar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 2));
        flashBtn = new JButton("2. Flash Firmware (USB DFU)", VectorIcons.greenCheckIcon());
        flashBtn.addActionListener(e -> actionFlash());
        JButton closeBtn = new JButton("Close", VectorIcons.redCrossIcon());
        closeBtn.addActionListener(e -> dispose());
        btnBar.add(flashBtn);
        btnBar.add(closeBtn);
        root.add(btnBar, BorderLayout.SOUTH);

        setContentPane(root);
    }

    private void actionEnterDfu() {
        String port = String.valueOf(portBox.getSelectedItem()).trim();
        if (port.isEmpty()) return;
        String password = new String(passwordField.getPassword());
        enterDfuBtn.setEnabled(false);
        appendLine("Sending ROM DFU reboot command to " + port + "...");
        Thread.ofVirtual().start(() -> {
            try (MaraProtClient client = new MaraProtClient(new MaraTransport.SerialCdcTransport(port, 115200), 0)) {
                client.connect();
                client.authenticate(password);
                client.enterRomDfu();
                appendLine("PLC accepted DFU command and is rebooting into STM32 ROM USB DFU (0483:df11)...");
            } catch (Exception ex) {
                appendLine("DFU reboot request error: " + ex.getMessage());
            } finally {
                SwingUtilities.invokeLater(() -> enterDfuBtn.setEnabled(true));
            }
        });
    }

    private void actionBrowseFile() {
        JFileChooser fc = new JFileChooser();
        fc.setFileFilter(new FileNameExtensionFilter("STM32 Firmware (*.bin, *.hex, *.elf)", "bin", "hex", "elf"));
        File cur = new File(fwFileField.getText().trim());
        if (cur.getParentFile() != null && cur.getParentFile().exists()) {
            fc.setCurrentDirectory(cur.getParentFile());
        }
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            fwFileField.setText(fc.getSelectedFile().getAbsolutePath());
        }
    }

    private void actionFlash() {
        String fwPath = fwFileField.getText().trim();
        if (fwPath.isEmpty() || !Files.isRegularFile(Path.of(fwPath))) {
            JOptionPane.showMessageDialog(this, "Select a valid firmware file (.bin, .hex, or .elf).", "Firmware Update", JOptionPane.WARNING_MESSAGE);
            return;
        }
        String tool = detectDfuTool();
        if (tool == null) {
            JOptionPane.showMessageDialog(this,
                    "Neither dfu-util nor STM32_Programmer_CLI was found.\n"
                            + "macOS: brew install dfu-util\n"
                            + "Windows: Install STM32CubeProgrammer or dfu-util\n"
                            + "Linux: sudo apt install dfu-util",
                    "DFU Tool Not Found", JOptionPane.ERROR_MESSAGE);
            return;
        }

        List<String> cmd = new ArrayList<>();
        if (tool.toLowerCase(Locale.ROOT).contains("stm32_programmer_cli")) {
            cmd.add(tool);
            cmd.add("-c");
            cmd.add("port=USB1");
            cmd.add("-w");
            cmd.add(fwPath);
            if (fwPath.toLowerCase(Locale.ROOT).endsWith(".bin")) {
                cmd.add("0x08000000");
            }
            cmd.add("-v");
            cmd.add("-s");
        } else {
            if (!fwPath.toLowerCase(Locale.ROOT).endsWith(".bin")) {
                JOptionPane.showMessageDialog(this, "dfu-util requires a .bin file (e.g. F407PLC.bin).", "Firmware Update", JOptionPane.WARNING_MESSAGE);
                return;
            }
            cmd.add(tool);
            cmd.add("-d");
            cmd.add("0483:df11");
            cmd.add("-a");
            cmd.add("0");
            cmd.add("-s");
            cmd.add("0x08000000:leave");
            cmd.add("-D");
            cmd.add(fwPath);
        }

        flashBtn.setEnabled(false);
        enterDfuBtn.setEnabled(false);
        appendLine("Running: " + String.join(" ", cmd));
        Thread.ofVirtual().start(() -> {
            try {
                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.redirectErrorStream(true);
                Process p = pb.start();
                try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        appendLine(line);
                    }
                }
                int code = p.waitFor();
                appendLine(code == 0 ? "Firmware flash succeeded! Duino - S is booting." : "DFU flash exited with code " + code);
            } catch (Exception ex) {
                appendLine("DFU execution failed: " + ex.getMessage());
            } finally {
                SwingUtilities.invokeLater(() -> {
                    flashBtn.setEnabled(true);
                    enterDfuBtn.setEnabled(true);
                });
            }
        });
    }

    private void appendLine(String line) {
        SwingUtilities.invokeLater(() -> {
            outputArea.append(line + "\n");
            outputArea.setCaretPosition(outputArea.getDocument().getLength());
            if (logSink != null) {
                logSink.accept("[DFU] " + line);
            }
        });
    }

    private static String findDefaultFirmwarePath() {
        String home = System.getProperty("user.home", "");
        Path p = Path.of(home, "CLionProjects", "F407PLC", "build", "Debug", "F407PLC.bin");
        return Files.isRegularFile(p) ? p.toString() : "";
    }

    private static String detectDfuTool() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        boolean win = os.contains("win");
        String dfuName = win ? "dfu-util.exe" : "dfu-util";
        String stmName = win ? "STM32_Programmer_CLI.exe" : "STM32_Programmer_CLI";

        String pathEnv = System.getenv("PATH");
        if (pathEnv != null) {
            for (String dir : pathEnv.split(File.pathSeparator)) {
                Path d = Path.of(dir.trim(), dfuName);
                if (Files.isExecutable(d)) return d.toString();
                Path s = Path.of(dir.trim(), stmName);
                if (Files.isExecutable(s)) return s.toString();
            }
        }

        List<String> candidates = win
                ? List.of(
                        "C:\\Program Files\\STMicroelectronics\\STM32Cube\\STM32CubeProgrammer\\bin\\STM32_Programmer_CLI.exe",
                        "C:\\Program Files (x86)\\STMicroelectronics\\STM32Cube\\STM32CubeProgrammer\\bin\\STM32_Programmer_CLI.exe")
                : List.of(
                        "/opt/homebrew/bin/dfu-util",
                        "/usr/local/bin/dfu-util",
                        "/usr/bin/dfu-util",
                        "/Applications/STMicroelectronics/STM32Cube/STM32CubeProgrammer/STM32CubeProgrammer.app/Contents/MacOs/bin/STM32_Programmer_CLI");

        for (String c : candidates) {
            if (Files.isExecutable(Path.of(c))) return c;
        }
        return null;
    }
}
