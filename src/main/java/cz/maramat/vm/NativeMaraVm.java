package cz.maramat.vm;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

// Loads and executes the compiled C99 MaraVM firmware (mara_vm.c) via JNI
// for exact 1:1 native-speed simulation in MaraFlow.
public final class NativeMaraVm implements AutoCloseable {

    public static final int MAGIC_MFLW = 0x574C464D;
    public static final int FORMAT_VERSION = 0x0001;
    public static final int HEADER_SIZE_BYTES = 72;
    public static final int THREAD_COUNT = 6;
    public static final int MAX_IMAGE_BYTES = 28672;
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
    public static final int OP_PACK8 = 0x0A;
    public static final int OP_UNPACK8 = 0x0B;
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
    public static final int OP_SCALE = 0x2E;
    public static final int OP_RANDOM = 0x2F;
    public static final int OP_PID = 0x30;
    public static final int OP_LOWPASS = 0x31;
    public static final int OP_MOVING_AVG = 0x32;
    public static final int OP_DEBOUNCE = 0x33;
    public static final int OP_RET_READ = 0x36;
    public static final int OP_RET_WRITE = 0x37;
    public static final int OP_ARRAY_READ = 0x38;
    public static final int OP_ARRAY_WRITE = 0x39;
    public static final int OP_INT_CAPTURE = 0x3A;
    public static final int OP_CMP_EQ = 0x40;
    public static final int OP_CMP_NE = 0x41;
    public static final int OP_CMP_GT = 0x42;
    public static final int OP_CMP_GE = 0x43;
    public static final int OP_CMP_LT = 0x44;
    public static final int OP_CMP_LE = 0x45;
    public static final int OP_IN_RANGE = 0x46;
    public static final int OP_HYSTERESIS = 0x47;
    public static final int OP_SEL = 0x48;
    public static final int OP_TON = 0x50;
    public static final int OP_PULSE_GEN = 0x51;
    public static final int OP_PULSE_LEN = 0x52;
    public static final int OP_ONE_SHOT = 0x53;
    public static final int OP_CTUD = 0x54;
    public static final int OP_RTC_GET = 0x60;
    public static final int OP_DECODE_DATE = 0x61;
    public static final int OP_ENCODE_DATE = 0x62;
    public static final int OP_IO_READ = 0x70;
    public static final int OP_IO_WRITE = 0x71;
    public static final int OP_IO_PWM = 0x72;
    public static final int OP_COMM_EXEC = 0x75;

    public static final int IO_DI = 0;
    public static final int IO_DO = 1;
    public static final int IO_AI = 2;
    public static final int IO_AO = 3;

    public static final int COMM_TMP75 = 1;
    public static final int COMM_DS18B20 = 2;
    public static final int COMM_CH455G = 3;
    public static final int COMM_MODBUS_RTU = 4;
    public static final int COMM_REST_GET = 5;
    public static final int COMM_REST_POST = 6;
    public static final int COMM_BME280 = 7;
    public static final int COMM_SHT40 = 8;

    public enum RunState {
        STOPPED, RUNNING, PAUSED, FAULT, ESTOP
    }

    public static int calcCrc32(byte[] data, int offset, int len) {
        int crc = 0xFFFFFFFF;
        for (int i = 0; i < len; i++) {
            crc ^= (data[offset + i] & 0xFF);
            for (int bit = 0; bit < 8; bit++) {
                if ((crc & 1) != 0) crc = (crc >>> 1) ^ 0xEDB88320;
                else crc >>>= 1;
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

    private static volatile boolean loaded = false;
    private long handle;

    public static synchronized void ensureLoaded() {
        if (loaded) return;
        String os = System.getProperty("os.name", "").toLowerCase();
        String arch = System.getProperty("os.arch", "").toLowerCase();
        if ("arm64".equals(arch)) arch = "aarch64";

        String osDir = os.contains("mac") ? "darwin" : (os.contains("win") ? "windows" : "linux");
        String libFile = os.contains("mac") ? "libmaravm.dylib" : (os.contains("win") ? "maravm.dll" : "libmaravm.so");
        String resourcePath = "/native/" + osDir + "-" + arch + "/" + libFile;

        try (InputStream in = NativeMaraVm.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new UnsatisfiedLinkError("Native MaraVM library not found on classpath: " + resourcePath);
            }
            Path tmp = Files.createTempFile("maravm-", "-" + libFile);
            tmp.toFile().deleteOnExit();
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            System.load(tmp.toAbsolutePath().toString());
            loaded = true;
        } catch (IOException ex) {
            throw new UnsatisfiedLinkError("Failed to extract native MaraVM library: " + ex.getMessage());
        }
    }

    public NativeMaraVm() {
        ensureLoaded();
        this.handle = nativeCreate();
        if (this.handle == 0) {
            throw new IllegalStateException("Failed to allocate native VmInstance_t");
        }
    }

    public int loadImage(byte[] image) {
        return nativeLoadImage(handle, image);
    }

    public int reset() {
        return nativeReset(handle);
    }

    public int setState(RunState state) {
        return nativeSetState(handle, state.ordinal());
    }

    public RunState getRunState() {
        int ordinal = nativeGetState(handle);
        RunState[] vals = RunState.values();
        return (ordinal >= 0 && ordinal < vals.length) ? vals[ordinal] : RunState.FAULT;
    }

    public int stepThread(int threadId, long dtUs) {
        return nativeStepThread(handle, threadId, dtUs);
    }

    public byte[] readSlotRaw(int slotId) {
        return nativeReadSlotRaw(handle, slotId);
    }

    public double readSlotAsDouble(int slotId) {
        byte[] raw = readSlotRaw(slotId);
        if (raw == null || raw.length == 0) return 0.0;
        int type = (slotId >>> SLOT_TYPE_SHIFT) & 0x3;
        ByteBuffer bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        return switch (type) {
            case TYPE_BOOL -> (raw[0] != 0) ? 1.0 : 0.0;
            case TYPE_INT -> bb.getInt(0);
            case TYPE_LONG -> (double) bb.getLong(0);
            case TYPE_FLOAT -> bb.getFloat(0);
            default -> 0.0;
        };
    }

    public void setVirtualDi(int channel, boolean val) { nativeSetDi(handle, channel, val); }
    public boolean getVirtualDo(int channel) { return nativeGetDo(handle, channel); }
    public void setVirtualAi(int channel, int val) { nativeSetAi(handle, channel, val); }
    public int getVirtualAo(int channel) { return nativeGetAo(handle, channel); }
    public float getVirtualPwmDuty(int channel) { return nativeGetPwmDuty(handle, channel); }

    @Override
    public void close() {
        if (handle != 0) {
            nativeDestroy(handle);
            handle = 0;
        }
    }

    private static native long nativeCreate();
    private static native void nativeDestroy(long handle);
    private static native int nativeLoadImage(long handle, byte[] image);
    private static native int nativeReset(long handle);
    private static native int nativeSetState(long handle, int state);
    private static native int nativeGetState(long handle);
    private static native int nativeStepThread(long handle, int threadId, long dtUs);
    private static native byte[] nativeReadSlotRaw(long handle, int slotId);
    private static native int nativeWriteSlotRaw(long handle, int slotId, byte[] data);
    private static native void nativeSetDi(long handle, int channel, boolean val);
    private static native boolean nativeGetDo(long handle, int channel);
    private static native void nativeSetAi(long handle, int channel, int val);
    private static native int nativeGetAo(long handle, int channel);
    private static native float nativeGetPwmDuty(long handle, int channel);
}
