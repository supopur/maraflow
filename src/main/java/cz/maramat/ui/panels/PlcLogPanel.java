package cz.maramat.ui.panels;

import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import java.awt.BorderLayout;
import java.awt.Font;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

public class PlcLogPanel extends JPanel {
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private final JTextArea logArea;

    public PlcLogPanel() {
        super(new BorderLayout());
        logArea = new JTextArea();
        logArea.setEditable(false);
        logArea.setFont(new Font("Monospaced", Font.PLAIN, 11));
        JScrollPane scroll = new JScrollPane(logArea);
        scroll.setBorder(null);
        add(scroll, BorderLayout.CENTER);
        appendLog("Maraflow PLC IDE initialized. Ready.");
    }

    public void appendLog(String message) {
        String ts = LocalTime.now().format(TIME_FMT);
        logArea.append("[" + ts + "] " + message + "\n");
        logArea.setCaretPosition(logArea.getDocument().getLength());
    }
}
