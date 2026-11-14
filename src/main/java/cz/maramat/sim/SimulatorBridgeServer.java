package cz.maramat.sim;

import cz.maramat.model.Project;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Lightweight TCP bridge server exposing a running Maraflow SimulationEngine
 * to external SCADA tools such as Maraview.
 */
public class SimulatorBridgeServer {

    public static final int DEFAULT_PORT = 18502;

    private final SimulationEngine simEngine;
    private final Supplier<Project> projectSupplier;
    private final Runnable onStateChanged;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Set<Socket> activeClients = ConcurrentHashMap.newKeySet();
    private volatile ServerSocket serverSocket;
    private volatile Thread acceptThread;

    public SimulatorBridgeServer(SimulationEngine simEngine, Supplier<Project> projectSupplier) {
        this(simEngine, projectSupplier, null);
    }

    public SimulatorBridgeServer(SimulationEngine simEngine,
                                 Supplier<Project> projectSupplier,
                                 Runnable onStateChanged) {
        this.simEngine = simEngine;
        this.projectSupplier = projectSupplier;
        this.onStateChanged = onStateChanged;
    }

    public void start() throws IOException {
        start(DEFAULT_PORT);
    }

    public synchronized void start(int port) throws IOException {
        if (running.get()) {
            return;
        }
        ServerSocket ss = new ServerSocket(port, 16, InetAddress.getByName("127.0.0.1"));
        ss.setReuseAddress(true);
        this.serverSocket = ss;
        this.running.set(true);
        this.acceptThread = Thread.ofVirtual()
                .name("Maraflow-Sim-Bridge-Accept")
                .start(() -> acceptLoop(ss));
    }

    public boolean startQuietly(int port) {
        try {
            start(port);
            return true;
        } catch (IOException ex) {
            System.err.println("[SimulatorBridgeServer] Could not bind port " + port + ": " + ex.getMessage());
            return false;
        }
    }

    public synchronized void stop() {
        running.set(false);
        ServerSocket ss = this.serverSocket;
        this.serverSocket = null;
        if (ss != null) {
            try {
                ss.close();
            } catch (IOException ignored) {
            }
        }
        for (Socket client : activeClients) {
            try {
                client.close();
            } catch (IOException ignored) {
            }
        }
        activeClients.clear();
        Thread t = this.acceptThread;
        this.acceptThread = null;
        if (t != null) {
            t.interrupt();
        }
    }

    public boolean isRunning() {
        return running.get();
    }

    public int getPort() {
        ServerSocket ss = this.serverSocket;
        return (ss != null) ? ss.getLocalPort() : -1;
    }

    private void acceptLoop(ServerSocket ss) {
        while (running.get() && !ss.isClosed()) {
            try {
                Socket client = ss.accept();
                if (!running.get()) {
                    try { client.close(); } catch (IOException ignored) {}
                    break;
                }
                client.setTcpNoDelay(true);
                client.setSoTimeout(5000);
                activeClients.add(client);
                Thread.ofVirtual()
                        .name("Maraflow-Sim-Bridge-Client")
                        .start(() -> handleClient(client));
            } catch (IOException ex) {
                if (running.get()) {
                    System.err.println("[SimulatorBridgeServer] Accept error: " + ex.getMessage());
                }
            }
        }
    }

    static final class ClientSession {
        List<String> subscribedOperands = List.of();
        double[] lastSentValues = new double[0];
        boolean forceFullSnapshot = true;
        Project lastProject = null;
    }

    private void handleClient(Socket client) {
        ClientSession session = new ClientSession();
        try (Socket sock = client;
             BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8));
             PrintWriter out = new PrintWriter(new OutputStreamWriter(sock.getOutputStream(), StandardCharsets.UTF_8), true)) {
            while (running.get()) {
                String line;
                try {
                    line = in.readLine();
                } catch (java.net.SocketTimeoutException timeout) {
                    continue;
                }
                if (line == null) break;
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                String response = processCommand(trimmed, session);
                out.println(response);
                if ("OK BYE".equals(response)) {
                    break;
                }
            }
        } catch (IOException ignored) {
        } finally {
            activeClients.remove(client);
        }
    }

    String processCommand(String line) {
        return processCommand(line, new ClientSession());
    }

    String processCommand(String line, ClientSession session) {
        String[] parts = line.split("\\s+");
        String cmd = parts[0].toUpperCase();
        Project project = (projectSupplier != null) ? projectSupplier.get() : null;

        try {
            return switch (cmd) {
                case "PING" -> "OK PONG";
                case "QUIT" -> "OK BYE";
                case "STATUS" -> {
                    String projName = (project != null && project.getProjectName() != null) ? project.getProjectName() : "";
                    yield "OK state=" + simEngine.getState().name()
                            + " estop=" + (simEngine.isEStopActive() ? "1" : "0")
                            + " speed=" + simEngine.getSpeedMultiplier()
                            + " project=" + encode(projName);
                }
                case "START" -> {
                    if (project == null) {
                        yield "ERR No active project";
                    }
                    simEngine.startSimulation(project);
                    notifyStateChanged();
                    yield "OK state=" + simEngine.getState().name();
                }
                case "PAUSE" -> {
                    simEngine.pauseSimulation();
                    notifyStateChanged();
                    yield "OK state=" + simEngine.getState().name();
                }
                case "STOP" -> {
                    if (project != null) {
                        simEngine.stopSimulation(project);
                    }
                    notifyStateChanged();
                    yield "OK state=" + simEngine.getState().name();
                }
                case "ESTOP" -> {
                    if (parts.length < 2) {
                        yield "ERR Usage: ESTOP <0|1>";
                    }
                    boolean pressed = parseDouble(parts[1]) != 0.0;
                    simEngine.setManualEStopButtonPressed(pressed);
                    notifyStateChanged();
                    yield "OK estop=" + (simEngine.isEStopActive() ? "1" : "0");
                }
                case "ESTOP_RESET" -> {
                    simEngine.manualResetEStop();
                    notifyStateChanged();
                    yield "OK estop=" + (simEngine.isEStopActive() ? "1" : "0");
                }
                case "SPEED" -> {
                    if (parts.length < 2) {
                        yield "ERR Usage: SPEED <multiplier>";
                    }
                    double mult = parseDouble(parts[1]);
                    simEngine.setSpeedMultiplier(mult);
                    notifyStateChanged();
                    yield "OK speed=" + simEngine.getSpeedMultiplier();
                }
                case "GET" -> {
                    if (parts.length < 2) {
                        yield "ERR Usage: GET <operand>";
                    }
                    String op = decode(parts[1]);
                    double val = simEngine.getOperandValue(project, op);
                    yield "OK " + val;
                }
                case "SET" -> {
                    if (parts.length < 3) {
                        yield "ERR Usage: SET <operand> <value>";
                    }
                    String op = decode(parts[1]);
                    double val = parseDouble(parts[2]);
                    simEngine.setOperandValue(project, op, val);
                    yield "OK";
                }
                case "MGET" -> {
                    StringBuilder sb = new StringBuilder("OK");
                    for (int i = 1; i < parts.length; i++) {
                        String op = decode(parts[i]);
                        double val = simEngine.getOperandValue(project, op);
                        sb.append(' ').append(val);
                    }
                    yield sb.toString();
                }
                case "SUB" -> {
                    List<String> ops = new ArrayList<>(Math.max(0, parts.length - 1));
                    for (int i = 1; i < parts.length; i++) {
                        ops.add(decode(parts[i]));
                    }
                    session.subscribedOperands = ops;
                    session.lastSentValues = new double[ops.size()];
                    Arrays.fill(session.lastSentValues, Double.NaN);
                    session.forceFullSnapshot = true;
                    session.lastProject = project;
                    yield "OK " + ops.size();
                }
                case "POLL" -> {
                    if (session.lastProject != project) {
                        session.lastProject = project;
                        session.forceFullSnapshot = true;
                    }
                    String projName = (project != null && project.getProjectName() != null) ? project.getProjectName() : "";
                    StringBuilder sb = new StringBuilder(128);
                    sb.append("OK state=").append(simEngine.getState().name())
                            .append(" estop=").append(simEngine.isEStopActive() ? "1" : "0")
                            .append(" speed=").append(simEngine.getSpeedMultiplier())
                            .append(" project=").append(encode(projName))
                            .append(" |");
                    List<String> ops = session.subscribedOperands;
                    for (int i = 0; i < ops.size(); i++) {
                        double v = simEngine.getOperandValue(project, ops.get(i));
                        if (session.forceFullSnapshot || Double.compare(v, session.lastSentValues[i]) != 0) {
                            session.lastSentValues[i] = v;
                            sb.append(' ').append(i).append('=').append(v);
                        }
                    }
                    session.forceFullSnapshot = false;
                    yield sb.toString();
                }
                case "MSET" -> {
                    java.util.List<String> ops = new java.util.ArrayList<>(parts.length);
                    double[] vals = new double[parts.length];
                    int count = 0;
                    for (int i = 1; i < parts.length; i++) {
                        int eq = parts[i].indexOf('=');
                        if (eq <= 0) {
                            yield "ERR Invalid MSET pair: " + parts[i];
                        }
                        ops.add(decode(parts[i].substring(0, eq)));
                        vals[count++] = parseDouble(parts[i].substring(eq + 1));
                    }
                    for (int i = 0; i < count; i++) {
                        simEngine.setOperandValue(project, ops.get(i), vals[i]);
                    }
                    yield "OK";
                }
                default -> "ERR Unknown command: " + cmd;
            };
        } catch (Exception ex) {
            return "ERR " + ex.getMessage();
        }
    }

    private void notifyStateChanged() {
        if (onStateChanged != null) {
            javax.swing.SwingUtilities.invokeLater(onStateChanged);
        }
    }

    private static double parseDouble(String s) {
        if ("true".equalsIgnoreCase(s)) return 1.0;
        if ("false".equalsIgnoreCase(s)) return 0.0;
        return Double.parseDouble(s);
    }

    private static String encode(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }

    private static String decode(String s) {
        return URLDecoder.decode(s == null ? "" : s, StandardCharsets.UTF_8);
    }
}
