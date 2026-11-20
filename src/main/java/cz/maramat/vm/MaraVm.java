package cz.maramat.vm;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

// 1:1 portable Java port of mara_vm.c (MaraVM v1).
// Executes the exact same MFLW binary bytecode image as F407PLC.
public final class MaraVm {

    public static final int MAGIC_MFLW = 0x574C464D;
    public static final int FORMAT_VERSION = 0x0001;
    public static final int HEADER_SIZE_BYTES = 72;
    public static final int THREAD_COUNT = 6;
    public static final int MAX_IMAGE_BYTES = 28672;
    public static final int MAX_BOOL_SLOTS = 2048;
    public static final int MAX_INT_SLOTS = 1024;
    public static final int MAX_LONG_SLOTS = 256;
    public static final int MAX_FLOAT_SLOTS = 1024;
    public static final int MAX_STATE_SLOTS = 512;
    public static final int MAX_LOOP_FUEL = 4096;
    public static final int SLOT_ALWAYS_ENABLED = 0xFFFF;
    public static final int SLOT_TYPE_SHIFT = 14;
    public static final int SLOT_INDEX_MASK = 0x3FFF;

    public static final int TYPE_BOOL = 0;
    public static final int TYPE_INT = 1;
    public static final int TYPE_LONG = 2;
    public static final int TYPE_FLOAT = 3;

    public static final int OP_HALT = 0x00;
    public static final int OP_NOP = 0x01;
    public static final int OP_ESTOP_CHECK = 0x02;
    public static final int OP_JMP = 0x04;
    public static final int OP_JMP_FALSE = 0x05;
    public static final int OP_MOV = 0x08;
    public static final int OP_CAST = 0x09;
    public static final int OP_AND = 0x10;
    public static final int OP_NAND = 0x11;
    public static final int OP_OR = 0x12;
    public static final int OP_NOR = 0x13;
    public static final int OP_XOR = 0x14;
    public static final int OP_XNOR = 0x15;
    public static final int OP_NOT = 0x16;
    public static final int OP_RS = 0x17;
    public static final int OP_SR = 0x18;
    public static final int OP_LD_CONTACT = 0x1A;
    public static final int OP_LD_COIL = 0x1B;
    public static final int OP_ADD = 0x20;
    public static final int OP_SUB = 0x21;
    public static final int OP_MUL = 0x22;
    public static final int OP_DIV = 0x23;
    public static final int OP_MOD = 0x24;
    public static final int OP_MIN = 0x26;
    public static final int OP_MAX = 0x27;
    public static final int OP_ABS = 0x28;
    public static final int OP_NEG = 0x29;
    public static final int OP_SQRT = 0x2A;
    public static final int OP_SIN = 0x2B;
    public static final int OP_COS = 0x2C;
    public static final int OP_CLAMP = 0x2D;
    public static final int OP_PID = 0x30;
    public static final int OP_CMP_EQ = 0x40;
    public static final int OP_CMP_NE = 0x41;
    public static final int OP_CMP_GT = 0x42;
    public static final int OP_CMP_GE = 0x43;
    public static final int OP_CMP_LT = 0x44;
    public static final int OP_CMP_LE = 0x45;
    public static final int OP_SEL = 0x48;
    public static final int OP_TON = 0x50;
    public static final int OP_ONE_SHOT = 0x53;
    public static final int OP_CTUD = 0x54;
    public static final int OP_IO_READ = 0x70;
    public static final int OP_IO_WRITE = 0x71;
    public static final int OP_IO_PWM = 0x72;

    public static final int IO_DI = 0;
    public static final int IO_DO = 1;
    public static final int IO_AI = 2;
    public static final int IO_AO = 3;

    public enum RunState {
        STOPPED, RUNNING, PAUSED, FAULT, ESTOP
    }

    public enum Status {
        OK, ERR_PARAM, ERR_BAD_MAGIC, ERR_BAD_VERSION, ERR_BAD_CRC,
        ERR_TOO_LARGE, ERR_FAULT_OPCODE, ERR_FAULT_FUEL, ERR_FAULT_BOUNDS
    }

    private final MaraHalDriver hal;
    private RunState runState = RunState.STOPPED;
    private Status lastFaultCode = Status.OK;
    private int lastFaultPc = 0;
    private long scanCount = 0;
    private boolean hasValidImage = false;
    private byte[] imageBuf = new byte[0];
    private int initTableOffset = 0;
    private int initTableCount = 0;
    private final int[] threadOffset = new int[THREAD_COUNT];

    private final byte[] boolRam = new byte[MAX_BOOL_SLOTS];
    private final int[] intRam = new int[MAX_INT_SLOTS];
    private final long[] longRam = new long[MAX_LONG_SLOTS];
    private final float[] floatRam = new float[MAX_FLOAT_SLOTS];

    private final float[] stateAcc = new float[MAX_STATE_SLOTS];
    private final float[] stateAux = new float[MAX_STATE_SLOTS];
    private final long[] stateTimerMs = new long[MAX_STATE_SLOTS];
    private final byte[] statePrevBool = new byte[MAX_STATE_SLOTS];

    public MaraVm(MaraHalDriver hal) {
        this.hal = (hal != null) ? hal : new MaraHalDriver() {};
    }

    public static int calcCrc32(byte[] data, int offset, int len) {
        int crc = 0xFFFFFFFF;
        for (int i = 0; i < len; i++) {
            crc ^= (data[offset + i] & 0xFF);
            for (int bit = 0; bit < 8; bit++) {
                if ((crc & 1) != 0) {
                    crc = (crc >>> 1) ^ 0xEDB88320;
                } else {
                    crc >>>= 1;
                }
            }
        }
        return crc ^ 0xFFFFFFFF;
    }

    public static int makeSlotId(int type, int index) {
        return ((type & 0x3) << SLOT_TYPE_SHIFT) | (index & SLOT_INDEX_MASK);
    }

    public static int getSlotSize(int slotId) {
        return switch ((slotId >>> SLOT_TYPE_SHIFT) & 0x3) {
            case TYPE_BOOL -> 1;
            case TYPE_INT, TYPE_FLOAT -> 4;
            case TYPE_LONG -> 8;
            default -> 0;
        };
    }

    public Status loadImage(byte[] image) {
        if (image == null) return Status.ERR_PARAM;
        if (image.length < HEADER_SIZE_BYTES || image.length > MAX_IMAGE_BYTES) return Status.ERR_TOO_LARGE;

        ByteBuffer bb = ByteBuffer.wrap(image).order(ByteOrder.LITTLE_ENDIAN);
        int magic = bb.getInt(0);
        int fmtVer = bb.getShort(4) & 0xFFFF;
        int imgSize = bb.getInt(8);
        int payloadCrc = bb.getInt(12);
        if (magic != MAGIC_MFLW) return Status.ERR_BAD_MAGIC;
        if (fmtVer != FORMAT_VERSION) return Status.ERR_BAD_VERSION;
        if (imgSize != image.length) return Status.ERR_BAD_CRC;

        int calc = calcCrc32(image, HEADER_SIZE_BYTES, image.length - HEADER_SIZE_BYTES);
        if (calc != payloadCrc) return Status.ERR_BAD_CRC;

        this.runState = RunState.STOPPED;
        this.imageBuf = Arrays.copyOf(image, image.length);
        this.initTableOffset = bb.getInt(36);
        this.initTableCount = bb.getInt(40);
        for (int i = 0; i < THREAD_COUNT; i++) {
            this.threadOffset[i] = bb.getInt(44 + (i * 4));
        }
        this.hasValidImage = true;
        return reset();
    }

    public Status reset() {
        Arrays.fill(boolRam, (byte) 0);
        Arrays.fill(intRam, 0);
        Arrays.fill(longRam, 0L);
        Arrays.fill(floatRam, 0.0f);
        Arrays.fill(stateAcc, 0.0f);
        Arrays.fill(stateAux, 0.0f);
        Arrays.fill(stateTimerMs, 0L);
        Arrays.fill(statePrevBool, (byte) 0);
        lastFaultCode = Status.OK;
        lastFaultPc = 0;
        scanCount = 0;

        if (hasValidImage) {
            ByteBuffer bb = ByteBuffer.wrap(imageBuf).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < initTableCount; i++) {
                int off = initTableOffset + (i * 12);
                if (off + 12 > imageBuf.length) break;
                int slotId = bb.getShort(off) & 0xFFFF;
                long rawVal = bb.getLong(off + 4);
                byte[] rawBytes = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(rawVal).array();
                writeSlotRaw(slotId, rawBytes);
            }
        }
        return Status.OK;
    }

    public Status setState(RunState newState) {
        if (newState == RunState.RUNNING && !hasValidImage) {
            return Status.ERR_FAULT_BOUNDS;
        }
        this.runState = newState;
        return Status.OK;
    }

    public RunState getRunState() {
        return runState;
    }

    public Status getLastFaultCode() {
        return lastFaultCode;
    }

    public boolean writeSlotRaw(int slotId, byte[] inBuf) {
        if (inBuf == null) return false;
        int idx = slotId & SLOT_INDEX_MASK;
        int type = (slotId >>> SLOT_TYPE_SHIFT) & 0x3;
        switch (type) {
            case TYPE_BOOL -> { if (idx < MAX_BOOL_SLOTS) { boolRam[idx] = (byte) (inBuf[0] != 0 ? 1 : 0); return true; } }
            case TYPE_INT -> { if (idx < MAX_INT_SLOTS && inBuf.length >= 4) { intRam[idx] = ByteBuffer.wrap(inBuf).order(ByteOrder.LITTLE_ENDIAN).getInt(0); return true; } }
            case TYPE_LONG -> { if (idx < MAX_LONG_SLOTS && inBuf.length >= 8) { longRam[idx] = ByteBuffer.wrap(inBuf).order(ByteOrder.LITTLE_ENDIAN).getLong(0); return true; } }
            case TYPE_FLOAT -> { if (idx < MAX_FLOAT_SLOTS && inBuf.length >= 4) { floatRam[idx] = ByteBuffer.wrap(inBuf).order(ByteOrder.LITTLE_ENDIAN).getFloat(0); return true; } }
        }
        return false;
    }

    public double readSlotAsDouble(int slotId) {
        int idx = slotId & SLOT_INDEX_MASK;
        int type = (slotId >>> SLOT_TYPE_SHIFT) & 0x3;
        return switch (type) {
            case TYPE_BOOL -> (idx < MAX_BOOL_SLOTS && boolRam[idx] != 0) ? 1.0 : 0.0;
            case TYPE_INT -> (idx < MAX_INT_SLOTS) ? intRam[idx] : 0.0;
            case TYPE_LONG -> (idx < MAX_LONG_SLOTS) ? (double) longRam[idx] : 0.0;
            case TYPE_FLOAT -> (idx < MAX_FLOAT_SLOTS) ? floatRam[idx] : 0.0;
            default -> 0.0;
        };
    }

    public void writeSlotFromDouble(int slotId, double val) {
        int type = (slotId >>> SLOT_TYPE_SHIFT) & 0x3;
        switch (type) {
            case TYPE_BOOL -> writeBool(slotId, val != 0.0);
            case TYPE_INT -> writeInt(slotId, (int) Math.round(val));
            case TYPE_LONG -> writeLong(slotId, Math.round(val));
            case TYPE_FLOAT -> writeFloat(slotId, (float) val);
        }
    }

    private boolean readBool(int slotId) {
        int idx = slotId & SLOT_INDEX_MASK;
        int type = (slotId >>> SLOT_TYPE_SHIFT) & 0x3;
        return switch (type) {
            case TYPE_BOOL -> idx < MAX_BOOL_SLOTS && boolRam[idx] != 0;
            case TYPE_INT -> idx < MAX_INT_SLOTS && intRam[idx] != 0;
            case TYPE_LONG -> idx < MAX_LONG_SLOTS && longRam[idx] != 0;
            case TYPE_FLOAT -> idx < MAX_FLOAT_SLOTS && floatRam[idx] != 0.0f;
            default -> false;
        };
    }

    private int readInt(int slotId) {
        int idx = slotId & SLOT_INDEX_MASK;
        int type = (slotId >>> SLOT_TYPE_SHIFT) & 0x3;
        return switch (type) {
            case TYPE_BOOL -> (idx < MAX_BOOL_SLOTS && boolRam[idx] != 0) ? 1 : 0;
            case TYPE_INT -> (idx < MAX_INT_SLOTS) ? intRam[idx] : 0;
            case TYPE_LONG -> (idx < MAX_LONG_SLOTS) ? (int) longRam[idx] : 0;
            case TYPE_FLOAT -> (idx < MAX_FLOAT_SLOTS) ? (int) floatRam[idx] : 0;
            default -> 0;
        };
    }

    private long readLong(int slotId) {
        int idx = slotId & SLOT_INDEX_MASK;
        int type = (slotId >>> SLOT_TYPE_SHIFT) & 0x3;
        return switch (type) {
            case TYPE_BOOL -> (idx < MAX_BOOL_SLOTS && boolRam[idx] != 0) ? 1L : 0L;
            case TYPE_INT -> (idx < MAX_INT_SLOTS) ? (long) intRam[idx] : 0L;
            case TYPE_LONG -> (idx < MAX_LONG_SLOTS) ? longRam[idx] : 0L;
            case TYPE_FLOAT -> (idx < MAX_FLOAT_SLOTS) ? (long) floatRam[idx] : 0L;
            default -> 0L;
        };
    }

    private float readFloat(int slotId) {
        int idx = slotId & SLOT_INDEX_MASK;
        int type = (slotId >>> SLOT_TYPE_SHIFT) & 0x3;
        return switch (type) {
            case TYPE_BOOL -> (idx < MAX_BOOL_SLOTS && boolRam[idx] != 0) ? 1.0f : 0.0f;
            case TYPE_INT -> (idx < MAX_INT_SLOTS) ? (float) intRam[idx] : 0.0f;
            case TYPE_LONG -> (idx < MAX_LONG_SLOTS) ? (float) longRam[idx] : 0.0f;
            case TYPE_FLOAT -> (idx < MAX_FLOAT_SLOTS) ? floatRam[idx] : 0.0f;
            default -> 0.0f;
        };
    }

    private void writeBool(int slotId, boolean val) {
        int idx = slotId & SLOT_INDEX_MASK;
        int type = (slotId >>> SLOT_TYPE_SHIFT) & 0x3;
        byte b = (byte) (val ? 1 : 0);
        switch (type) {
            case TYPE_BOOL -> { if (idx < MAX_BOOL_SLOTS) boolRam[idx] = b; }
            case TYPE_INT -> { if (idx < MAX_INT_SLOTS) intRam[idx] = b; }
            case TYPE_LONG -> { if (idx < MAX_LONG_SLOTS) longRam[idx] = b; }
            case TYPE_FLOAT -> { if (idx < MAX_FLOAT_SLOTS) floatRam[idx] = b; }
        }
    }

    private void writeInt(int slotId, int val) {
        int idx = slotId & SLOT_INDEX_MASK;
        int type = (slotId >>> SLOT_TYPE_SHIFT) & 0x3;
        switch (type) {
            case TYPE_BOOL -> { if (idx < MAX_BOOL_SLOTS) boolRam[idx] = (byte) (val != 0 ? 1 : 0); }
            case TYPE_INT -> { if (idx < MAX_INT_SLOTS) intRam[idx] = val; }
            case TYPE_LONG -> { if (idx < MAX_LONG_SLOTS) longRam[idx] = val; }
            case TYPE_FLOAT -> { if (idx < MAX_FLOAT_SLOTS) floatRam[idx] = (float) val; }
        }
    }

    private void writeLong(int slotId, long val) {
        int idx = slotId & SLOT_INDEX_MASK;
        int type = (slotId >>> SLOT_TYPE_SHIFT) & 0x3;
        switch (type) {
            case TYPE_BOOL -> { if (idx < MAX_BOOL_SLOTS) boolRam[idx] = (byte) (val != 0 ? 1 : 0); }
            case TYPE_INT -> { if (idx < MAX_INT_SLOTS) intRam[idx] = (int) val; }
            case TYPE_LONG -> { if (idx < MAX_LONG_SLOTS) longRam[idx] = val; }
            case TYPE_FLOAT -> { if (idx < MAX_FLOAT_SLOTS) floatRam[idx] = (float) val; }
        }
    }

    private void writeFloat(int slotId, float val) {
        int idx = slotId & SLOT_INDEX_MASK;
        int type = (slotId >>> SLOT_TYPE_SHIFT) & 0x3;
        switch (type) {
            case TYPE_BOOL -> { if (idx < MAX_BOOL_SLOTS) boolRam[idx] = (byte) (val != 0.0f ? 1 : 0); }
            case TYPE_INT -> { if (idx < MAX_INT_SLOTS) intRam[idx] = (int) val; }
            case TYPE_LONG -> { if (idx < MAX_LONG_SLOTS) longRam[idx] = (long) val; }
            case TYPE_FLOAT -> { if (idx < MAX_FLOAT_SLOTS) floatRam[idx] = val; }
        }
    }

    private int u16(int off) {
        return (imageBuf[off] & 0xFF) | ((imageBuf[off + 1] & 0xFF) << 8);
    }

    public Status stepThread(int threadId, long dtUs) {
        if (threadId < 0 || threadId >= THREAD_COUNT) return Status.ERR_PARAM;
        if (!hasValidImage || runState != RunState.RUNNING) return Status.OK;

        int pc = threadOffset[threadId];
        if (pc == 0 || pc >= imageBuf.length) return Status.OK;

        int fuel = MAX_LOOP_FUEL;
        long dtMs = Math.max(dtUs > 0 ? 1 : 0, dtUs / 1000L);

        while (pc + 8 <= imageBuf.length) {
            int opcode = imageBuf[pc] & 0xFF;
            int flags = imageBuf[pc + 1] & 0xFF;
            int enSlot = u16(pc + 2);
            int dst = u16(pc + 4);
            int srcA = u16(pc + 6);

            if (opcode == OP_HALT) break;
            if (opcode == OP_NOP) { pc += 8; continue; }

            int nextPc = pc + 8;
            boolean enabled = (enSlot == SLOT_ALWAYS_ENABLED) || readBool(enSlot);

            switch (opcode) {
                case OP_JMP -> {
                    int rel = (short) srcA;
                    if (rel <= 0 && fuel-- == 0) {
                        lastFaultCode = Status.ERR_FAULT_FUEL;
                        lastFaultPc = pc;
                        runState = RunState.FAULT;
                        return lastFaultCode;
                    }
                    nextPc = pc + rel;
                }
                case OP_JMP_FALSE -> {
                    int rel = (short) srcA;
                    if (!readBool(dst)) {
                        if (rel <= 0 && fuel-- == 0) {
                            lastFaultCode = Status.ERR_FAULT_FUEL;
                            lastFaultPc = pc;
                            runState = RunState.FAULT;
                            return lastFaultCode;
                        }
                        nextPc = pc + rel;
                    }
                }
                case OP_MOV, OP_CAST -> {
                    if (enabled) {
                        switch ((dst >>> SLOT_TYPE_SHIFT) & 0x3) {
                            case TYPE_BOOL -> writeBool(dst, readBool(srcA));
                            case TYPE_INT -> writeInt(dst, readInt(srcA));
                            case TYPE_LONG -> writeLong(dst, readLong(srcA));
                            case TYPE_FLOAT -> writeFloat(dst, readFloat(srcA));
                        }
                    }
                }
                case OP_NOT -> { if (enabled) writeBool(dst, !readBool(srcA)); }
                case OP_AND, OP_NAND, OP_OR, OP_NOR, OP_XOR, OP_XNOR -> {
                    int argc = Math.max(2, flags);
                    int extraWords = (argc - 1 + 1) / 2;
                    nextPc = pc + 8 + (extraWords * 4);
                    if (nextPc <= imageBuf.length && enabled) {
                        boolean acc = readBool(srcA);
                        for (int k = 1; k < argc; k++) {
                            boolean v = readBool(u16(pc + 8 + ((k - 1) * 2)));
                            if (opcode == OP_AND || opcode == OP_NAND) acc &= v;
                            else if (opcode == OP_OR || opcode == OP_NOR) acc |= v;
                            else acc ^= v;
                        }
                        if (opcode == OP_NAND || opcode == OP_NOR || opcode == OP_XNOR) acc = !acc;
                        writeBool(dst, acc);
                    }
                }
                case OP_RS, OP_SR -> {
                    nextPc = pc + 12;
                    if (nextPc <= imageBuf.length && enabled) {
                        int rSlot = u16(pc + 8);
                        int nqSlot = u16(pc + 10);
                        boolean sVal = readBool(srcA);
                        boolean rVal = readBool(rSlot);
                        boolean qVal = readBool(dst);
                        if (opcode == OP_RS) {
                            if (rVal) qVal = false;
                            else if (sVal) qVal = true;
                        } else {
                            if (sVal) qVal = true;
                            else if (rVal) qVal = false;
                        }
                        writeBool(dst, qVal);
                        if (nqSlot != SLOT_ALWAYS_ENABLED) writeBool(nqSlot, !qVal);
                    }
                }
                case OP_ADD, OP_SUB, OP_MUL, OP_MIN, OP_MAX -> {
                    nextPc = pc + 12;
                    if (nextPc <= imageBuf.length && enabled) {
                        int srcB = u16(pc + 8);
                        if (((dst >>> SLOT_TYPE_SHIFT) & 0x3) == TYPE_FLOAT) {
                            float a = readFloat(srcA), b = readFloat(srcB);
                            float r = switch (opcode) {
                                case OP_ADD -> a + b;
                                case OP_SUB -> a - b;
                                case OP_MUL -> a * b;
                                case OP_MIN -> Math.min(a, b);
                                default -> Math.max(a, b);
                            };
                            writeFloat(dst, r);
                        } else {
                            int a = readInt(srcA), b = readInt(srcB);
                            int r = switch (opcode) {
                                case OP_ADD -> a + b;
                                case OP_SUB -> a - b;
                                case OP_MUL -> a * b;
                                case OP_MIN -> Math.min(a, b);
                                default -> Math.max(a, b);
                            };
                            writeInt(dst, r);
                        }
                    }
                }
                case OP_DIV, OP_MOD -> {
                    nextPc = pc + 12;
                    if (nextPc <= imageBuf.length && enabled) {
                        int srcB = u16(pc + 8);
                        int errSlot = u16(pc + 10);
                        int b = readInt(srcB);
                        if (b == 0) {
                            writeInt(dst, 0);
                            if (errSlot != SLOT_ALWAYS_ENABLED) writeBool(errSlot, true);
                        } else {
                            int a = readInt(srcA);
                            writeInt(dst, opcode == OP_DIV ? (a / b) : (a % b));
                            if (errSlot != SLOT_ALWAYS_ENABLED) writeBool(errSlot, false);
                        }
                    }
                }
                case OP_ABS, OP_NEG, OP_SQRT, OP_SIN, OP_COS -> {
                    if (enabled) {
                        float a = readFloat(srcA);
                        float r = switch (opcode) {
                            case OP_ABS -> Math.abs(a);
                            case OP_NEG -> -a;
                            case OP_SQRT -> a >= 0.0f ? (float) Math.sqrt(a) : 0.0f;
                            case OP_SIN -> (float) Math.sin(a);
                            default -> (float) Math.cos(a);
                        };
                        writeFloat(dst, r);
                    }
                }
                case OP_CLAMP -> {
                    nextPc = pc + 12;
                    if (nextPc <= imageBuf.length && enabled) {
                        float v = readFloat(srcA);
                        float lo = readFloat(u16(pc + 8));
                        float hi = readFloat(u16(pc + 10));
                        writeFloat(dst, Math.max(lo, Math.min(hi, v)));
                    }
                }
                case OP_PID -> {
                    nextPc = pc + 20;
                    if (nextPc <= imageBuf.length && enabled) {
                        int spSlot = u16(pc + 8), kpSlot = u16(pc + 10), kiSlot = u16(pc + 12),
                            kdSlot = u16(pc + 14), rstSlot = u16(pc + 16), stateIdx = u16(pc + 18);
                        if (stateIdx < MAX_STATE_SLOTS) {
                            if (readBool(rstSlot)) {
                                stateAcc[stateIdx] = 0.0f;
                                stateAux[stateIdx] = 0.0f;
                                writeFloat(dst, 0.0f);
                            } else {
                                float dtS = dtUs > 0 ? (dtUs / 1_000_000.0f) : 0.01f;
                                float err = readFloat(spSlot) - readFloat(srcA);
                                stateAcc[stateIdx] += err * dtS;
                                float deriv = (err - stateAux[stateIdx]) / dtS;
                                stateAux[stateIdx] = err;
                                float out = (readFloat(kpSlot) * err) + (readFloat(kiSlot) * stateAcc[stateIdx]) + (readFloat(kdSlot) * deriv);
                                writeFloat(dst, out);
                            }
                        }
                    }
                }
                case OP_CMP_EQ, OP_CMP_NE, OP_CMP_GT, OP_CMP_GE, OP_CMP_LT, OP_CMP_LE -> {
                    nextPc = pc + 12;
                    if (nextPc <= imageBuf.length && enabled) {
                        float a = readFloat(srcA), b = readFloat(u16(pc + 8));
                        boolean res = switch (opcode) {
                            case OP_CMP_EQ -> a == b;
                            case OP_CMP_NE -> a != b;
                            case OP_CMP_GT -> a > b;
                            case OP_CMP_GE -> a >= b;
                            case OP_CMP_LT -> a < b;
                            default -> a <= b;
                        };
                        writeBool(dst, res);
                    }
                }
                case OP_SEL -> {
                    nextPc = pc + 12;
                    if (nextPc <= imageBuf.length && enabled) {
                        int chosen = readBool(srcA) ? u16(pc + 10) : u16(pc + 8);
                        writeFloat(dst, readFloat(chosen));
                    }
                }
                case OP_TON -> {
                    nextPc = pc + 16;
                    if (nextPc <= imageBuf.length) {
                        int ptSlot = u16(pc + 8), etSlot = u16(pc + 10), stateIdx = u16(pc + 12);
                        if (stateIdx < MAX_STATE_SLOTS) {
                            if (enabled && readBool(srcA)) {
                                long ptMs = Math.max(0, readInt(ptSlot));
                                stateTimerMs[stateIdx] = Math.min(ptMs, stateTimerMs[stateIdx] + dtMs);
                                writeBool(dst, stateTimerMs[stateIdx] >= ptMs);
                            } else {
                                stateTimerMs[stateIdx] = 0;
                                writeBool(dst, false);
                            }
                            if (etSlot != SLOT_ALWAYS_ENABLED) writeInt(etSlot, (int) stateTimerMs[stateIdx]);
                        }
                    }
                }
                case OP_ONE_SHOT -> {
                    nextPc = pc + 12;
                    if (nextPc <= imageBuf.length) {
                        int stateIdx = u16(pc + 8);
                        if (stateIdx < MAX_STATE_SLOTS) {
                            boolean cur = enabled && readBool(srcA);
                            boolean prev = statePrevBool[stateIdx] != 0;
                            statePrevBool[stateIdx] = (byte) (cur ? 1 : 0);
                            writeBool(dst, cur && !prev);
                        }
                    }
                }
                case OP_IO_READ -> {
                    if (enabled) {
                        if (flags == IO_DI) writeBool(dst, hal.diRead(srcA));
                        else if (flags == IO_AI) writeInt(dst, hal.aiRead(srcA));
                    }
                }
                case OP_IO_WRITE -> {
                    if (enabled) {
                        if (flags == IO_DO) hal.doWrite(dst, readBool(srcA));
                        else if (flags == IO_AO) hal.aoWrite(dst, readInt(srcA));
                    }
                }
                case OP_IO_PWM -> {
                    nextPc = pc + 12;
                    if (nextPc <= imageBuf.length) {
                        float dty = enabled ? readFloat(u16(pc + 10)) : 0.0f;
                        writeBool(dst, hal.pwmSet(srcA, readInt(u16(pc + 8)), dty));
                    }
                }
                default -> {
                    lastFaultCode = Status.ERR_FAULT_OPCODE;
                    lastFaultPc = pc;
                    runState = RunState.FAULT;
                    return lastFaultCode;
                }
            }
            pc = nextPc;
        }
        scanCount++;
        return Status.OK;
    }
}
