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

    private static final int MAX_LOG_CHARS = 150_000;
    private static final int TRIM_TO_CHARS = 100_000;

    public void appendLog(String message) {
        String ts = LocalTime.now().format(TIME_FMT);
        logArea.append("[" + ts + "] " + message + "\n");
        javax.swing.text.Document doc = logArea.getDocument();
        int len = doc.getLength();
        if (len > MAX_LOG_CHARS) {
            try {
                int cut = len - TRIM_TO_CHARS;
                int nl = logArea.getText(cut, Math.min(256, len - cut)).indexOf('\n');
                if (nl >= 0) cut += nl + 1;
                doc.remove(0, cut);
            } catch (javax.swing.text.BadLocationException ignored) {
            }
        }
        logArea.setCaretPosition(doc.getLength());
    }
}
