package cz.maramat.sim;

import cz.maramat.comm.MaraProtClient;
import cz.maramat.model.Project;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * MaraProt v1 TCP server exposing a running Maraflow SimulationEngine
 * to external SCADA tools such as Maraview using the same binary protocol
 * and authentication/encryption as physical F407PLC hardware.
 */
public class SimulatorBridgeServer {

    public static final int DEFAULT_PORT = 18502;
    public static final int OP_OPERAND_READ_BATCH = 0x60;
    public static final int OP_OPERAND_WRITE_BATCH = 0x61;

    private final SimulationEngine simEngine;
    private final Supplier<Project> projectSupplier;
    private final Runnable onStateChanged;
    private final long startTimeMs = System.currentTimeMillis();
    private final SecureRandom rng = new SecureRandom();

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
        ServerSocket ss = new ServerSocket(port, 16);
        ss.setReuseAddress(true);
        this.serverSocket = ss;
        this.running.set(true);
        this.acceptThread = Thread.ofVirtual()
                .name("Maraflow-Sim-MaraProt-Accept")
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
        while (running.get()) {
            try {
                Socket client = ss.accept();
                client.setTcpNoDelay(true);
                client.setSoTimeout(30_000);
                activeClients.add(client);
                Thread.ofVirtual()
                        .name("Maraflow-Sim-MaraProt-Client")
                        .start(() -> handleClient(client));
            } catch (IOException ex) {
                if (running.get()) {
                    System.err.println("[SimulatorBridgeServer] Accept error: " + ex.getMessage());
                }
            }
        }
    }

    private void handleClient(Socket client) {
        ClientSession session = new ClientSession();
        try (Socket sock = client;
             InputStream in = sock.getInputStream();
             OutputStream out = sock.getOutputStream()) {
            while (running.get()) {
                byte[] frame = readNextFrame(in);
                if (frame == null) break;
                byte[] resp = processFrame(frame, session);
                if (resp != null) {
                    out.write(resp);
                    out.flush();
                }
            }
        } catch (IOException ignored) {
        } finally {
            activeClients.remove(client);
        }
    }

    private static byte[] readNextFrame(InputStream in) throws IOException {
        int state = 0;
        byte[] hdr = new byte[MaraProtClient.HEADER_BYTES];
        while (state < 2) {
            int b;
            try {
                b = in.read();
            } catch (java.net.SocketTimeoutException e) {
                continue;
            }
            if (b < 0) return null;
            if (state == 0 && b == MaraProtClient.MAGIC_0) {
                hdr[0] = (byte) b;
                state = 1;
            } else if (state == 1 && b == MaraProtClient.MAGIC_1) {
                hdr[1] = (byte) b;
                state = 2;
            } else {
                state = (b == MaraProtClient.MAGIC_0) ? 1 : 0;
            }
        }
        readExact(in, hdr, 2, MaraProtClient.HEADER_BYTES - 2);
        int payloadLen = (hdr[8] & 0xFF) | ((hdr[9] & 0xFF) << 8);
        if (payloadLen > 1024) {
            throw new IOException("Payload too large: " + payloadLen);
        }
        byte[] frame = new byte[MaraProtClient.HEADER_BYTES + payloadLen + MaraProtClient.CRC_BYTES];
        System.arraycopy(hdr, 0, frame, 0, MaraProtClient.HEADER_BYTES);
        readExact(in, frame, MaraProtClient.HEADER_BYTES, payloadLen + MaraProtClient.CRC_BYTES);
        return frame;
    }

    private static void readExact(InputStream in, byte[] buf, int off, int len) throws IOException {
        int read = 0;
        while (read < len) {
            int n = in.read(buf, off + read, len - read);
            if (n < 0) throw new IOException("EOF");
            read += n;
        }
    }

    byte[] processFrame(byte[] frame, ClientSession session) throws IOException {
        int verFlags = frame[2] & 0xFF;
        int stationId = frame[3] & 0xFF;
        int seqId = (frame[4] & 0xFF) | ((frame[5] & 0xFF) << 8);
        int opcode = frame[6] & 0xFF;
        int payloadLen = (frame[8] & 0xFF) | ((frame[9] & 0xFF) << 8);

        int crcCalc = MaraProtClient.calcCrc16(frame, MaraProtClient.HEADER_BYTES + payloadLen);
        int crcRx = (frame[MaraProtClient.HEADER_BYTES + payloadLen] & 0xFF)
                | ((frame[MaraProtClient.HEADER_BYTES + payloadLen + 1] & 0xFF) << 8);
        if (crcCalc != crcRx) {
            return buildResponse(stationId, seqId, opcode, 1, new byte[0], false, session);
        }

        byte[] payload = new byte[payloadLen];
        if (payloadLen > 0) {
            System.arraycopy(frame, MaraProtClient.HEADER_BYTES, payload, 0, payloadLen);
        }

        boolean reqEncrypted = (verFlags & MaraProtClient.FLAG_ENCRYPTED) != 0;
        if (reqEncrypted) {
            if (!session.authValid || session.sessionKey == null) {
                return buildResponse(stationId, seqId, opcode, 7, new byte[0], false, session);
            }
            if (payload.length > 0) {
                MaraProtClient.cryptPayload(session.sessionKey, seqId, 0x01, payload);
            }
        }

        Project project = (projectSupplier != null) ? projectSupplier.get() : null;
        String password = resolvePassword(project);
        boolean authRequired = !password.isEmpty();

        if (authRequired && !session.authValid
                && opcode != MaraProtClient.OP_PING
                && opcode != MaraProtClient.OP_GET_INFO
                && opcode != MaraProtClient.OP_AUTH_CHALLENGE
                && opcode != MaraProtClient.OP_AUTH_VERIFY) {
            return buildResponse(stationId, seqId, opcode, 7, new byte[0], false, session);
        }

        return switch (opcode) {
            case MaraProtClient.OP_PING -> {
                int echo = (payload.length >= 4)
                        ? ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN).getInt(0) : 0;
                int uptime = (int) (System.currentTimeMillis() - startTimeMs);
                byte[] out = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                        .putInt(echo).putInt(uptime).array();
                yield buildResponse(stationId, seqId, opcode, 0, out, reqEncrypted, session);
            }
            case MaraProtClient.OP_GET_INFO -> {
                ByteBuffer bb = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
                bb.putShort((short) 0x0001);
                bb.putShort((short) 0x0002);
                bb.putInt(0x00010000);
                bb.put((byte) 1); // TCP_IP
                bb.putShort((short) 520);
                bb.put((byte) mapVmState());
                bb.putInt(0);
                bb.putInt(0);
                byte[] name = "MaraFlow Sim".getBytes(StandardCharsets.US_ASCII);
                byte[] name24 = new byte[24];
                System.arraycopy(name, 0, name24, 0, Math.min(name.length, 23));
                bb.put(name24);
                yield buildResponse(stationId, seqId, opcode, 0, bb.array(), reqEncrypted, session);
            }
            case MaraProtClient.OP_AUTH_CHALLENGE -> {
                rng.nextBytes(session.challenge);
                session.authValid = !authRequired;
                byte[] out = new byte[17];
                out[0] = (byte) (authRequired ? 1 : 0);
                System.arraycopy(session.challenge, 0, out, 1, 16);
                yield buildResponse(stationId, seqId, opcode, 0, out, false, session);
            }
            case MaraProtClient.OP_AUTH_VERIFY -> {
                if (!authRequired) {
                    session.authValid = true;
                    yield buildResponse(stationId, seqId, opcode, 0, new byte[]{1}, false, session);
                }
                if (payload.length < 32) {
                    yield buildResponse(stationId, seqId, opcode, 3, new byte[0], false, session);
                }
                byte[] key = MaraProtClient.sha256Two(password.getBytes(StandardCharsets.UTF_8), session.challenge);
                byte[] expected = MaraProtClient.sha256Two(key, session.challenge);
                if (!java.security.MessageDigest.isEqual(expected, payload)) {
                    session.authValid = false;
                    yield buildResponse(stationId, seqId, opcode, 7, new byte[0], false, session);
                }
                session.sessionKey = key;
                session.authValid = true;
                yield buildResponse(stationId, seqId, opcode, 0, new byte[]{1}, false, session);
            }
            case MaraProtClient.OP_VM_STATUS -> {
                ByteBuffer bb = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN);
                bb.put((byte) mapVmState());
                bb.put((byte) (simEngine.isEStopActive() ? 1 : 0));
                bb.putShort((short) 0);
                bb.putInt(0);
                for (int i = 0; i < 5; i++) bb.putInt(0);
                bb.putShort((short) 0);
                bb.putShort((short) 0);
                yield buildResponse(stationId, seqId, opcode, 0, bb.array(), reqEncrypted, session);
            }
            case MaraProtClient.OP_VM_CONTROL -> {
                if (payload.length < 1) {
                    yield buildResponse(stationId, seqId, opcode, 3, new byte[0], reqEncrypted, session);
                }
                int action = payload[0] & 0xFF;
                switch (action) {
                    case MaraProtClient.CTRL_STOP -> {
                        if (project != null) simEngine.stopSimulation(project);
                    }
                    case MaraProtClient.CTRL_START -> {
                        if (project != null && simEngine.getState() != SimulationEngine.State.WATCH_MODE) {
                            simEngine.startSimulation(project);
                        }
                    }
                    case MaraProtClient.CTRL_PAUSE -> simEngine.pauseSimulation();
                    case MaraProtClient.CTRL_ESTOP_RESET -> simEngine.manualResetEStop();
                    default -> {
                    }
                }
                notifyStateChanged();
                byte[] out = new byte[]{(byte) mapVmState(), (byte) (simEngine.isEStopActive() ? 1 : 0)};
                yield buildResponse(stationId, seqId, opcode, 0, out, reqEncrypted, session);
            }
            case OP_OPERAND_READ_BATCH -> {
                if (payload.length < 2) {
                    yield buildResponse(stationId, seqId, opcode, 3, new byte[0], reqEncrypted, session);
                }
                ByteBuffer inBb = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
                int count = inBb.getShort() & 0xFFFF;
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                bos.write(count & 0xFF);
                bos.write((count >>> 8) & 0xFF);
                for (int i = 0; i < count && inBb.remaining() >= 2; i++) {
                    int len = inBb.getShort() & 0xFFFF;
                    if (len > inBb.remaining()) break;
                    byte[] nameBytes = new byte[len];
                    inBb.get(nameBytes);
                    String name = new String(nameBytes, StandardCharsets.UTF_8);
                    double val = simEngine.getOperandValue(project, name);
                    byte[] valBytes = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putDouble(val).array();
                    bos.write(valBytes, 0, 8);
                }
                yield buildResponse(stationId, seqId, opcode, 0, bos.toByteArray(), reqEncrypted, session);
            }
            case OP_OPERAND_WRITE_BATCH -> {
                if (payload.length < 2) {
                    yield buildResponse(stationId, seqId, opcode, 3, new byte[0], reqEncrypted, session);
                }
                ByteBuffer inBb = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
                int count = inBb.getShort() & 0xFFFF;
                int applied = 0;
                for (int i = 0; i < count && inBb.remaining() >= 2; i++) {
                    int len = inBb.getShort() & 0xFFFF;
                    if (len + 8 > inBb.remaining()) break;
                    byte[] nameBytes = new byte[len];
                    inBb.get(nameBytes);
                    double val = inBb.getDouble();
                    simEngine.setOperandValue(project, new String(nameBytes, StandardCharsets.UTF_8), val);
                    applied++;
                }
                byte[] out = new byte[]{(byte) (applied & 0xFF), (byte) ((applied >>> 8) & 0xFF)};
                yield buildResponse(stationId, seqId, opcode, 0, out, reqEncrypted, session);
            }
            default -> buildResponse(stationId, seqId, opcode, 2, new byte[0], reqEncrypted, session);
        };
    }

    private byte[] buildResponse(int stationId, int seqId, int opcode, int status,
                                 byte[] payload, boolean encrypt, ClientSession session) throws IOException {
        byte[] wirePayload = payload;
        int verFlags = MaraProtClient.VERSION_1 | MaraProtClient.FLAG_RESPONSE;
        if (status != 0) {
            verFlags |= MaraProtClient.FLAG_ERROR;
        }
        if (encrypt && session.authValid && session.sessionKey != null && wirePayload.length > 0) {
            wirePayload = wirePayload.clone();
            MaraProtClient.cryptPayload(session.sessionKey, seqId, 0x02, wirePayload);
            verFlags |= MaraProtClient.FLAG_ENCRYPTED;
        }
        int pLen = wirePayload.length;
        byte[] frame = new byte[MaraProtClient.HEADER_BYTES + pLen + MaraProtClient.CRC_BYTES];
        frame[0] = (byte) MaraProtClient.MAGIC_0;
        frame[1] = (byte) MaraProtClient.MAGIC_1;
        frame[2] = (byte) verFlags;
        frame[3] = (byte) (stationId & 0xFF);
        frame[4] = (byte) (seqId & 0xFF);
        frame[5] = (byte) ((seqId >>> 8) & 0xFF);
        frame[6] = (byte) (opcode & 0xFF);
        frame[7] = (byte) (status & 0xFF);
        frame[8] = (byte) (pLen & 0xFF);
        frame[9] = (byte) ((pLen >>> 8) & 0xFF);
        if (pLen > 0) {
            System.arraycopy(wirePayload, 0, frame, MaraProtClient.HEADER_BYTES, pLen);
        }
        int crc = MaraProtClient.calcCrc16(frame, MaraProtClient.HEADER_BYTES + pLen);
        frame[MaraProtClient.HEADER_BYTES + pLen] = (byte) (crc & 0xFF);
        frame[MaraProtClient.HEADER_BYTES + pLen + 1] = (byte) ((crc >>> 8) & 0xFF);
        return frame;
    }

    private int mapVmState() {
        if (simEngine.isEStopActive()) return 4;
        return switch (simEngine.getState()) {
            case STOPPED -> 0;
            case RUNNING -> 1;
            case PAUSED -> 2;
            case WATCH_MODE -> 5;
            default -> 0;
        };
    }

    private String resolvePassword(Project project) {
        if (project != null && project.getUnitConfig() != null) {
            return project.getUnitConfig().getAccessPassword();
        }
        return "admin";
    }

    private void notifyStateChanged() {
        if (onStateChanged != null) {
            onStateChanged.run();
        }
    }

    static final class ClientSession {
        final byte[] challenge = new byte[16];
        byte[] sessionKey;
        boolean authValid = false;
    }
}
