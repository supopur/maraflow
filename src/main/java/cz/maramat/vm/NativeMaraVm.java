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

    public int setState(MaraVm.RunState state) {
        return nativeSetState(handle, state.ordinal());
    }

    public MaraVm.RunState getRunState() {
        int ordinal = nativeGetState(handle);
        MaraVm.RunState[] vals = MaraVm.RunState.values();
        return (ordinal >= 0 && ordinal < vals.length) ? vals[ordinal] : MaraVm.RunState.FAULT;
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
        int type = (slotId >>> MaraVm.SLOT_TYPE_SHIFT) & 0x3;
        ByteBuffer bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        return switch (type) {
            case MaraVm.TYPE_BOOL -> (raw[0] != 0) ? 1.0 : 0.0;
            case MaraVm.TYPE_INT -> bb.getInt(0);
            case MaraVm.TYPE_LONG -> (double) bb.getLong(0);
            case MaraVm.TYPE_FLOAT -> bb.getFloat(0);
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
