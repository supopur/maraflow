package cz.maramat.comm;

import cz.maramat.vm.NativeMaraVm;

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
    public static final int FLAG_RESPONSE = 0x01;
    public static final int FLAG_ERROR = 0x02;
    public static final int FLAG_ENCRYPTED = 0x04;
    public static final int HEADER_BYTES = 10;
    public static final int CRC_BYTES = 2;

    public static final int OP_PING = 0x01;
    public static final int OP_GET_INFO = 0x02;
    public static final int OP_AUTH_CHALLENGE = 0x03;
    public static final int OP_AUTH_VERIFY = 0x04;
    public static final int OP_VM_STATUS = 0x10;
    public static final int OP_VM_CONTROL = 0x11;
    public static final int OP_VM_UPLOAD_BEGIN = 0x20;
    public static final int OP_VM_UPLOAD_CHUNK = 0x21;
    public static final int OP_VM_UPLOAD_COMMIT = 0x22;
    public static final int OP_WATCH_SUBSCRIBE = 0x30;
    public static final int OP_WATCH_POLL = 0x31;
    public static final int OP_SLOT_READ_BATCH = 0x32;
    public static final int OP_SLOT_WRITE_BATCH = 0x33;
    public static final int OP_RTC_GET = 0x40;
    public static final int OP_RTC_SET = 0x41;
    public static final int OP_ONEWIRE_SCAN = 0x50;
    public static final int OP_UNIT_CONFIG_GET = 0x51;
    public static final int OP_UNIT_CONFIG_SET = 0x52;
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
    private byte[] sessionKey;
    private boolean encryptEnabled = false;

    public MaraProtClient(MaraTransport transport, int stationId) {
        this.transport = transport;
        this.stationId = stationId & 0xFF;
    }

    public void connect() throws IOException {
        transport.open();
        this.sessionKey = null;
        this.encryptEnabled = false;
    }

    public synchronized void authenticate(String password) throws IOException {
        byte[] chalResp = transceive(OP_AUTH_CHALLENGE, new byte[0], 1000, false);
        if (chalResp.length < 17) {
            throw new IOException("Invalid AUTH_CHALLENGE response");
        }
        boolean authRequired = chalResp[0] != 0;
        byte[] challenge = new byte[16];
        System.arraycopy(chalResp, 1, challenge, 0, 16);
        if (!authRequired) {
            this.sessionKey = null;
            this.encryptEnabled = false;
            return;
        }
        String pw = (password == null) ? "" : password;
        byte[] pwBytes = pw.getBytes(StandardCharsets.UTF_8);
        byte[] key = sha256Two(pwBytes, challenge);
        byte[] proof = sha256Two(key, challenge);
        byte[] verifyResp = transceive(OP_AUTH_VERIFY, proof, 1000, false);
        if (verifyResp.length < 1 || verifyResp[0] == 0) {
            throw new IOException("Authentication rejected by PLC");
        }
        this.sessionKey = key;
        this.encryptEnabled = true;
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
        int fullCrc = NativeMaraVm.calcCrc32(image, 0, image.length);
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
        final int maxSlotsPerChunk = 250; // 3 + 250*2 = 503 B <= 520 B PROT_MAX_PAYLOAD_BYTES
        int epoch = 1;
        int offset = 0;
        do {
            int count = Math.min(maxSlotsPerChunk, slotIds.length - offset);
            ByteBuffer req = ByteBuffer.allocate(3 + (count * 2)).order(ByteOrder.LITTLE_ENDIAN);
            req.put((byte) (offset == 0 ? 0 : 1));
            req.putShort((short) count);
            for (int i = 0; i < count; i++) {
                req.putShort((short) slotIds[offset + i]);
            }
            byte[] resp = transceive(OP_WATCH_SUBSCRIBE, req.array(), 1000);
            epoch = ByteBuffer.wrap(resp).order(ByteOrder.LITTLE_ENDIAN).getShort(2) & 0xFFFF;
            offset += count;
        } while (offset < slotIds.length);
        return epoch;
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
            int sz = NativeMaraVm.getSlotSize(slotId);
            if (sz <= 0 || pos + sz > resp.length) break;
            byte[] val = new byte[sz];
            System.arraycopy(resp, pos, val, 0, sz);
            changed.put(slotId, val);
            pos += sz;
        }
        return new WatchUpdate(newEpoch, vmState, estop, changed);
    }

    public synchronized long getRtcUnixSec() throws IOException {
        byte[] resp = transceive(OP_RTC_GET, new byte[0], 1000);
        return ByteBuffer.wrap(resp).order(ByteOrder.LITTLE_ENDIAN).getLong(0);
    }

    public synchronized void setRtcUnixSec(long unixSec) throws IOException {
        ByteBuffer req = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(unixSec);
        transceive(OP_RTC_SET, req.array(), 1000);
    }

    public synchronized void enterRomDfu() throws IOException {
        byte[] req = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(0xDF00B007).array();
        transceive(OP_FW_ENTER_ROM_DFU, req, 1000);
    }

    public synchronized java.util.List<String> scanOneWireBus(int dioPin) throws IOException {
        byte[] resp = transceive(OP_ONEWIRE_SCAN, new byte[]{(byte) (dioPin & 0xFF)}, 2500);
        java.util.List<String> roms = new java.util.ArrayList<>();
        if (resp.length < 1) return roms;
        int count = resp[0] & 0xFF;
        for (int i = 0; i < count && (1 + (i * 8) + 8) <= resp.length; i++) {
            int off = 1 + (i * 8);
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("%02X-", resp[off] & 0xFF));
            for (int b = 1; b < 8; b++) {
                sb.append(String.format("%02X", resp[off + b] & 0xFF));
            }
            roms.add(sb.toString());
        }
        return roms;
    }

    public synchronized void readUnitConfig(cz.maramat.model.UnitConfig cfg) throws IOException {
        byte[] resp = transceive(OP_UNIT_CONFIG_GET, new byte[0], 1500);
        if (resp.length < 48) throw new IOException("Invalid UNIT_CONFIG_GET response");
        int nLen = 0;
        while (nLen < 16 && resp[nLen] != 0) nLen++;
        cfg.setUnitName(new String(resp, 0, nLen, StandardCharsets.UTF_8));
        cfg.setStationId(resp[16] & 0xFF);
        cfg.setIpMode(resp[17] == 1 ? cz.maramat.model.UnitConfig.IpMode.STATIC : cz.maramat.model.UnitConfig.IpMode.DHCP);
        cfg.setStaticIp(formatIpv4(resp, 18));
        cfg.setSubnetMask(formatIpv4(resp, 22));
        cfg.setGateway(formatIpv4(resp, 26));
        int port = (resp[30] & 0xFF) | ((resp[31] & 0xFF) << 8);
        cfg.setTcpPort(port);
        cfg.setRunOutputPin(decodeDoPin(resp[32] & 0xFF));
        cfg.setFaultOutputPin(decodeDoPin(resp[33] & 0xFF));
        cfg.setEstopOutputPin(decodeDoPin(resp[34] & 0xFF));
        cfg.setBootMode(resp[35] == 1 ? cz.maramat.model.UnitConfig.BootMode.BOOT_TO_STOP : cz.maramat.model.UnitConfig.BootMode.AUTO_RUN);
        cfg.setSafeOutputsOnStop(resp[36] != 0);
        cfg.setWatchdogEnabled(resp[37] != 0);
        if (resp.length >= 50) {
            int modeOrd = resp[48] & 0xFF;
            cz.maramat.model.UnitConfig.Rs485Mode[] modes = cz.maramat.model.UnitConfig.Rs485Mode.values();
            cfg.setRs485Mode((modeOrd >= 0 && modeOrd < modes.length) ? modes[modeOrd] : cz.maramat.model.UnitConfig.Rs485Mode.MARAPROT);
            cfg.setRs485BaudIndex(resp[49] & 0xFF);
        }
    }

    public synchronized void writeUnitConfig(cz.maramat.model.UnitConfig cfg) throws IOException {
        byte[] req = new byte[50];
        byte[] nameBytes = cfg.getUnitName().getBytes(StandardCharsets.UTF_8);
        System.arraycopy(nameBytes, 0, req, 0, Math.min(nameBytes.length, 15));
        req[16] = (byte) (cfg.getStationId() & 0xFF);
        req[17] = (byte) (cfg.getIpMode() == cz.maramat.model.UnitConfig.IpMode.STATIC ? 1 : 0);
        writeIpv4(cfg.getStaticIp(), req, 18);
        writeIpv4(cfg.getSubnetMask(), req, 22);
        writeIpv4(cfg.getGateway(), req, 26);
        req[30] = (byte) (cfg.getTcpPort() & 0xFF);
        req[31] = (byte) ((cfg.getTcpPort() >>> 8) & 0xFF);
        req[32] = (byte) encodeDoPin(cfg.getRunOutputPin());
        req[33] = (byte) encodeDoPin(cfg.getFaultOutputPin());
        req[34] = (byte) encodeDoPin(cfg.getEstopOutputPin());
        req[35] = (byte) (cfg.getBootMode() == cz.maramat.model.UnitConfig.BootMode.BOOT_TO_STOP ? 1 : 0);
        req[36] = (byte) (cfg.isSafeOutputsOnStop() ? 1 : 0);
        req[37] = (byte) (cfg.isWatchdogEnabled() ? 1 : 0);
        String pw = cfg.getAccessPassword();
        if (pw != null && !pw.isEmpty()) {
            byte[] pwBytes = pw.getBytes(StandardCharsets.UTF_8);
            System.arraycopy(pwBytes, 0, req, 38, Math.min(pwBytes.length, 10));
        }
        req[48] = (byte) (cfg.getRs485Mode().ordinal() & 0xFF);
        req[49] = (byte) (cfg.getRs485BaudIndex() & 0xFF);
        transceive(OP_UNIT_CONFIG_SET, req, 1500);
    }

    private static String formatIpv4(byte[] buf, int off) {
        return (buf[off] & 0xFF) + "." + (buf[off + 1] & 0xFF) + "." + (buf[off + 2] & 0xFF) + "." + (buf[off + 3] & 0xFF);
    }

    private static void writeIpv4(String ip, byte[] buf, int off) {
        String[] parts = (ip == null ? "" : ip.trim()).split("\\.");
        for (int i = 0; i < 4; i++) {
            int val = 0;
            if (i < parts.length) {
                try { val = Math.clamp(Integer.parseInt(parts[i]), 0, 255); } catch (NumberFormatException ignored) {}
            }
            buf[off + i] = (byte) val;
        }
    }

    private static String decodeDoPin(int val) {
        return (val >= 0 && val <= 15) ? ("DO" + val) : "None";
    }

    private static int encodeDoPin(String pin) {
        if (pin != null && pin.startsWith("DO")) {
            try { return Math.clamp(Integer.parseInt(pin.substring(2)), 0, 15); } catch (NumberFormatException ignored) {}
        }
        return 0xFF;
    }

    public synchronized void writeSlot(int slotId, double value) throws IOException {
        int type = (slotId >>> NativeMaraVm.SLOT_TYPE_SHIFT) & 0x3;
        int valSize = NativeMaraVm.getSlotSize(slotId);
        if (valSize <= 0) return;
        ByteBuffer req = ByteBuffer.allocate(4 + valSize).order(ByteOrder.LITTLE_ENDIAN);
        req.putShort((short) 1);
        req.putShort((short) slotId);
        switch (type) {
            case NativeMaraVm.TYPE_BOOL -> req.put((byte) (value != 0.0 ? 1 : 0));
            case NativeMaraVm.TYPE_INT -> req.putInt((int) Math.round(value));
            case NativeMaraVm.TYPE_LONG -> req.putLong(Math.round(value));
            case NativeMaraVm.TYPE_FLOAT -> req.putFloat((float) value);
        }
        transceive(OP_SLOT_WRITE_BATCH, req.array(), 1000);
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
        return encodeFrame(stationId, seqId, VERSION_1, opcode, payload);
    }

    public static byte[] encodeFrame(int stationId, int seqId, int verFlags, int opcode, byte[] payload) {
        int pLen = (payload != null) ? payload.length : 0;
        byte[] frame = new byte[HEADER_BYTES + pLen + CRC_BYTES];
        frame[0] = (byte) MAGIC_0;
        frame[1] = (byte) MAGIC_1;
        frame[2] = (byte) (verFlags & 0xFF);
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
        return transceive(opcode, payload, timeoutMs, encryptEnabled);
    }

    private byte[] transceive(int opcode, byte[] payload, int timeoutMs, boolean useEncrypt) throws IOException {
        int seqId = nextSeqId++;
        if (nextSeqId > 0xFFFF) nextSeqId = 1;
        byte[] wirePayload = payload;
        int verFlags = VERSION_1;
        if (useEncrypt && sessionKey != null) {
            wirePayload = (payload == null) ? new byte[0] : payload.clone();
            if (wirePayload.length > 0) {
                cryptPayload(sessionKey, seqId, 0x01, wirePayload);
            }
            verFlags |= FLAG_ENCRYPTED;
        }
        transport.write(encodeFrame(stationId, seqId, verFlags, opcode, wirePayload));

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
                case 7 -> "ERR_AUTH (Authentication failed or required)";
                default -> "ERR_UNKNOWN";
            };
            throw new IOException("PLC returned MaraProt " + errName + " (status=" + rxStatus + ", op=0x" + Integer.toHexString(opcode) + ")");
        }

        byte[] respPayload = new byte[rxLen];
        if (rxLen > 0) System.arraycopy(fullFrame, HEADER_BYTES, respPayload, 0, rxLen);
        int rxVerFlags = fullFrame[2] & 0xFF;
        if ((rxVerFlags & FLAG_ENCRYPTED) != 0 && sessionKey != null && rxLen > 0) {
            int rxSeq = (fullFrame[4] & 0xFF) | ((fullFrame[5] & 0xFF) << 8);
            cryptPayload(sessionKey, rxSeq, 0x02, respPayload);
        }
        return respPayload;
    }

    public static byte[] sha256Two(byte[] a, byte[] b) throws IOException {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            if (a != null && a.length > 0) md.update(a);
            if (b != null && b.length > 0) md.update(b);
            return md.digest();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 unavailable", e);
        }
    }

    public static void cryptPayload(byte[] key, int seqId, int dir, byte[] data) throws IOException {
        byte[] iv = new byte[]{(byte) (seqId & 0xFF), (byte) ((seqId >>> 8) & 0xFF), (byte) (dir & 0xFF), 0};
        int offset = 0;
        int blk = 0;
        while (offset < data.length) {
            iv[3] = (byte) (blk++ & 0xFF);
            byte[] stream = sha256Two(key, iv);
            for (int i = 0; i < 32 && offset < data.length; i++, offset++) {
                data[offset] ^= stream[i];
            }
        }
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
