package cz.maramat.comm;

import cz.maramat.vm.MaraVm;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.IntConsumer;

// Java client for MaraProt v1 (USB CDC, TCP/IP, RS-485).
public final class MaraProtClient implements Closeable {

    public static final int MAGIC_0 = 0x4D;
    public static final int MAGIC_1 = 0x50;
    public static final int VERSION_1 = 0x10;
    public static final int HEADER_BYTES = 10;
    public static final int CRC_BYTES = 2;

    public static final int OP_PING = 0x01;
    public static final int OP_GET_INFO = 0x02;
    public static final int OP_VM_STATUS = 0x10;
    public static final int OP_VM_CONTROL = 0x11;
    public static final int OP_VM_UPLOAD_BEGIN = 0x20;
    public static final int OP_VM_UPLOAD_CHUNK = 0x21;
    public static final int OP_VM_UPLOAD_COMMIT = 0x22;
    public static final int OP_WATCH_SUBSCRIBE = 0x30;
    public static final int OP_WATCH_POLL = 0x31;
    public static final int OP_SLOT_READ_BATCH = 0x32;
    public static final int OP_SLOT_WRITE_BATCH = 0x33;
    public static final int OP_FW_ENTER_ROM_DFU = 0xF3;

    public static final int CTRL_STOP = 0;
    public static final int CTRL_START = 1;
    public static final int CTRL_PAUSE = 2;
    public static final int CTRL_ESTOP_RESET = 5;
    public static final int CTRL_CLEAR_FAULT = 6;

    public record PlcInfo(
            int protoVersion, int modelId, int fwVersion, int transportKind,
            int maxMtuBytes, int vmState, int activeProgCrc32, int projectHash, String modelName
    ) {}

    public record WatchUpdate(
            int newEpoch, int vmState, boolean estopActive, Map<Integer, byte[]> changedSlots
    ) {}

    private final MaraTransport transport;
    private final int stationId;
    private int nextSeqId = 1;

    public MaraProtClient(MaraTransport transport, int stationId) {
        this.transport = transport;
        this.stationId = stationId & 0xFF;
    }

    public void connect() throws IOException {
        transport.open();
    }

    @Override
    public void close() throws IOException {
        transport.close();
    }

    public synchronized long ping(int echoVal) throws IOException {
        byte[] req = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(echoVal).array();
        byte[] resp = transceive(OP_PING, req, 1000);
        if (resp.length < 8) throw new IOException("Invalid PING response");
        return Integer.toUnsignedLong(ByteBuffer.wrap(resp).order(ByteOrder.LITTLE_ENDIAN).getInt(4));
    }

    public synchronized PlcInfo getInfo() throws IOException {
        byte[] resp = transceive(OP_GET_INFO, new byte[0], 1000);
        if (resp.length < 44) throw new IOException("Invalid GET_INFO response");
        ByteBuffer bb = ByteBuffer.wrap(resp).order(ByteOrder.LITTLE_ENDIAN);
        int protoVer = bb.getShort(0) & 0xFFFF;
        int modelId = bb.getShort(2) & 0xFFFF;
        int fwVer = bb.getInt(4);
        int trKind = bb.get(8) & 0xFF;
        int maxMtu = bb.getShort(9) & 0xFFFF;
        int vmState = bb.get(11) & 0xFF;
        int progCrc = bb.getInt(12);
        int projHash = bb.getInt(16);
        int nameLen = 0;
        while (nameLen < 24 && resp[20 + nameLen] != 0) nameLen++;
        String modelName = new String(resp, 20, nameLen, StandardCharsets.US_ASCII);
        return new PlcInfo(protoVer, modelId, fwVer, trKind, maxMtu, vmState, progCrc, projHash, modelName);
    }

    public synchronized void controlVm(int action) throws IOException {
        transceive(OP_VM_CONTROL, new byte[]{(byte) action}, 1000);
    }

    public synchronized void uploadImage(byte[] image, boolean autoStart, IntConsumer progressPct) throws IOException {
        int fullCrc = MaraVm.calcCrc32(image, 0, image.length);
        ByteBuffer beginReq = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(image.length)
                .putInt(fullCrc)
                .putInt(0);
        byte[] beginResp = transceive(OP_VM_UPLOAD_BEGIN, beginReq.array(), 2000);
        int maxChunk = Math.max(64, ByteBuffer.wrap(beginResp).order(ByteOrder.LITTLE_ENDIAN).getShort(0) & 0xFFFF);

        int offset = 0;
        while (offset < image.length) {
            int chunkLen = Math.min(maxChunk, image.length - offset);
            ByteBuffer chunkReq = ByteBuffer.allocate(4 + chunkLen).order(ByteOrder.LITTLE_ENDIAN);
            chunkReq.putInt(offset);
            chunkReq.put(image, offset, chunkLen);
            transceive(OP_VM_UPLOAD_CHUNK, chunkReq.array(), 3000);
            offset += chunkLen;
            if (progressPct != null) {
                progressPct.accept((int) ((offset * 100L) / image.length));
            }
        }

        transceive(OP_VM_UPLOAD_COMMIT, new byte[]{(byte) (autoStart ? 1 : 0)}, 3000);
    }

    public synchronized int watchSubscribe(int[] slotIds) throws IOException {
        ByteBuffer req = ByteBuffer.allocate(3 + (slotIds.length * 2)).order(ByteOrder.LITTLE_ENDIAN);
        req.put((byte) 0);
        req.putShort((short) slotIds.length);
        for (int s : slotIds) {
            req.putShort((short) s);
        }
        byte[] resp = transceive(OP_WATCH_SUBSCRIBE, req.array(), 1000);
        return ByteBuffer.wrap(resp).order(ByteOrder.LITTLE_ENDIAN).getShort(2) & 0xFFFF;
    }

    public synchronized WatchUpdate watchPoll(int ackEpoch) throws IOException {
        ByteBuffer req = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
        req.putShort((short) ackEpoch);
        req.putShort((short) 512);
        byte[] resp = transceive(OP_WATCH_POLL, req.array(), 1000);
        ByteBuffer bb = ByteBuffer.wrap(resp).order(ByteOrder.LITTLE_ENDIAN);
        int newEpoch = bb.getShort(0) & 0xFFFF;
        int vmState = bb.get(2) & 0xFF;
        boolean estop = bb.get(3) != 0;
        int count = bb.getShort(4) & 0xFFFF;

        Map<Integer, byte[]> changed = new LinkedHashMap<>();
        int pos = 6;
        for (int i = 0; i < count && pos + 2 <= resp.length; i++) {
            int slotId = bb.getShort(pos) & 0xFFFF;
            pos += 2;
            int sz = MaraVm.getSlotSize(slotId);
            if (sz <= 0 || pos + sz > resp.length) break;
            byte[] val = new byte[sz];
            System.arraycopy(resp, pos, val, 0, sz);
            changed.put(slotId, val);
            pos += sz;
        }
        return new WatchUpdate(newEpoch, vmState, estop, changed);
    }

    public synchronized void enterRomDfu() throws IOException {
        byte[] req = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(0xDF00B007).array();
        transceive(OP_FW_ENTER_ROM_DFU, req, 1000);
    }

    public static int calcCrc16(byte[] data, int len) {
        int crc = 0xFFFF;
        for (int i = 0; i < len; i++) {
            crc ^= (data[i] & 0xFF) << 8;
            for (int bit = 0; bit < 8; bit++) {
                if ((crc & 0x8000) != 0) crc = ((crc << 1) ^ 0x1021) & 0xFFFF;
                else crc = (crc << 1) & 0xFFFF;
            }
        }
        return crc;
    }

    public static byte[] encodeFrame(int stationId, int seqId, int opcode, byte[] payload) {
        int pLen = (payload != null) ? payload.length : 0;
        byte[] frame = new byte[HEADER_BYTES + pLen + CRC_BYTES];
        frame[0] = (byte) MAGIC_0;
        frame[1] = (byte) MAGIC_1;
        frame[2] = (byte) VERSION_1;
        frame[3] = (byte) (stationId & 0xFF);
        frame[4] = (byte) (seqId & 0xFF);
        frame[5] = (byte) ((seqId >>> 8) & 0xFF);
        frame[6] = (byte) (opcode & 0xFF);
        frame[7] = 0;
        frame[8] = (byte) (pLen & 0xFF);
        frame[9] = (byte) ((pLen >>> 8) & 0xFF);
        if (pLen > 0) {
            System.arraycopy(payload, 0, frame, HEADER_BYTES, pLen);
        }
        int crc = calcCrc16(frame, HEADER_BYTES + pLen);
        frame[HEADER_BYTES + pLen] = (byte) (crc & 0xFF);
        frame[HEADER_BYTES + pLen + 1] = (byte) ((crc >>> 8) & 0xFF);
        return frame;
    }

    private byte[] transceive(int opcode, byte[] payload, int timeoutMs) throws IOException {
        int seqId = nextSeqId++;
        if (nextSeqId > 0xFFFF) nextSeqId = 1;
        transport.write(encodeFrame(stationId, seqId, opcode, payload));

        long deadline = System.currentTimeMillis() + timeoutMs;
        byte[] hdr = new byte[HEADER_BYTES];
        byte[] tmp = new byte[1];

        // 1. Sync on SOF 0x4D 0x50
        int state = 0;
        while (state < 2) {
            if (System.currentTimeMillis() > deadline) throw new IOException("Timeout waiting for MaraProt SOF");
            int n = transport.read(tmp, 0, 1, 100);
            if (n == 0) continue;
            int b = tmp[0] & 0xFF;
            if (state == 0 && b == MAGIC_0) {
                hdr[0] = (byte) b;
                state = 1;
            } else if (state == 1 && b == MAGIC_1) {
                hdr[1] = (byte) b;
                state = 2;
            } else {
                state = (b == MAGIC_0) ? 1 : 0;
            }
        }

        // 2. Read remaining header + payload + CRC16
        readExact(hdr, 2, HEADER_BYTES - 2, deadline);
        int rxStatus = hdr[7] & 0xFF;
        int rxLen = (hdr[8] & 0xFF) | ((hdr[9] & 0xFF) << 8);
        if (rxLen > 1024) throw new IOException("Invalid payload length: " + rxLen);

        byte[] fullFrame = new byte[HEADER_BYTES + rxLen + CRC_BYTES];
        System.arraycopy(hdr, 0, fullFrame, 0, HEADER_BYTES);
        readExact(fullFrame, HEADER_BYTES, rxLen + CRC_BYTES, deadline);

        int crcCalc = calcCrc16(fullFrame, HEADER_BYTES + rxLen);
        int crcRx = (fullFrame[HEADER_BYTES + rxLen] & 0xFF) | ((fullFrame[HEADER_BYTES + rxLen + 1] & 0xFF) << 8);
        if (crcCalc != crcRx) throw new IOException("CRC mismatch on MaraProt response");
        if (rxStatus != 0) {
            String errName = switch (rxStatus) {
                case 1 -> "ERR_CRC";
                case 2 -> "ERR_OPCODE";
                case 3 -> "ERR_PARAM";
                case 4 -> "ERR_BUSY";
                case 5 -> "ERR_STATE";
                case 6 -> "ERR_STORAGE (EEPROM write failed)";
                default -> "ERR_UNKNOWN";
            };
            throw new IOException("PLC returned MaraProt " + errName + " (status=" + rxStatus + ", op=0x" + Integer.toHexString(opcode) + ")");
        }

        byte[] respPayload = new byte[rxLen];
        if (rxLen > 0) System.arraycopy(fullFrame, HEADER_BYTES, respPayload, 0, rxLen);
        return respPayload;
    }

    private void readExact(byte[] buf, int off, int len, long deadline) throws IOException {
        int read = 0;
        while (read < len) {
            long rem = deadline - System.currentTimeMillis();
            if (rem <= 0) throw new IOException("Timeout reading MaraProt frame");
            int n = transport.read(buf, off + read, len - read, (int) Math.min(250, rem));
            read += n;
        }
    }
}
