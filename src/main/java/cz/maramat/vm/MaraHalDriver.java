package cz.maramat.vm;

// Hardware Abstraction Layer interface for MaraVm, mirroring VmHalVtable_t in mara_vm.h.
public interface MaraHalDriver {
    default boolean diRead(int channel) { return false; }
    default void doWrite(int channel, boolean value) {}
    default int aiRead(int channel) { return 0; }
    default void aoWrite(int channel, int value) {}
    default boolean pwmSet(int channel, int freqHz, float dutyPct) { return false; }
    default long timeMicros() { return System.nanoTime() / 1000L; }
    default long rtcUnixSec() { return System.currentTimeMillis() / 1000L; }
    default int rngNext() { return (int) (Math.random() * Integer.MAX_VALUE); }
    default void retentiveWrite(int paramId, long rawBits) {}
    default long retentiveRead(int paramId) { return 0L; }
}
