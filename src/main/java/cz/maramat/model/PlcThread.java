package cz.maramat.model;

public enum PlcThread {
    THREAD_1(1, "1 (1s)", 1000, 1_000_000),
    THREAD_2(2, "2 (100ms)", 100, 100_000),
    THREAD_3(3, "3 (20ms)", 20, 20_000),
    THREAD_4(4, "4 (5ms)", 5, 5_000),
    THREAD_5(5, "5 (When Idle)", 1, 50_000);

    private final int id;
    private final String displayLabel;
    private final int periodMs;
    private final int maxAllowedUs;

    PlcThread(int id, String displayLabel, int periodMs, int maxAllowedUs) {
        this.id = id;
        this.displayLabel = displayLabel;
        this.periodMs = periodMs;
        this.maxAllowedUs = maxAllowedUs;
    }

    public int getId() {
        return id;
    }

    public String getDisplayLabel() {
        return displayLabel;
    }

    public int getPeriodMs() {
        return periodMs;
    }

    public int getMaxAllowedUs() {
        return maxAllowedUs;
    }

    public static PlcThread fromId(int id) {
        for (PlcThread t : values()) {
            if (t.id == id) return t;
        }
        return THREAD_2;
    }

    @Override
    public String toString() {
        return displayLabel;
    }
}
