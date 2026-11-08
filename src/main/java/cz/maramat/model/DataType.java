package cz.maramat.model;

import java.awt.Color;

public enum DataType {
    BOOL("BOOL", new Color(0x1E, 0x84, 0x49), new Color(0xD4, 0xEF, 0xDF), "0"),
    INT("INT", new Color(0x1A, 0x1A, 0x1A), new Color(0xEA, 0xEC, 0xEE), "0"),
    LONG("LONG", new Color(0x7D, 0x3C, 0x98), new Color(0xEB, 0xDE, 0xF0), "0"),
    FLOAT("FLOAT", new Color(0x1F, 0x61, 0x8D), new Color(0xD6, 0xEA, 0xF8), "0");

    private final String displayName;
    private final Color wireColor;
    private final Color pinBackground;
    private final String defaultConstant;

    DataType(String displayName, Color wireColor, Color pinBackground, String defaultConstant) {
        this.displayName = displayName;
        this.wireColor = wireColor;
        this.pinBackground = pinBackground;
        this.defaultConstant = defaultConstant;
    }

    public String getDisplayName() {
        return displayName;
    }

    public Color getWireColor() {
        return wireColor;
    }

    public Color getPinBackground() {
        return pinBackground;
    }

    public String getDefaultConstant() {
        return defaultConstant;
    }

    /**
     * Validates and normalizes a compile-time constant string for this data type.
     * Supports decimal, hex (0x...), and binary (0b...) prefixes.
     * For BOOL, numeric values are clamped to [0, 1].
     * Throws IllegalArgumentException if invalid.
     */
    public String validateAndNormalizeConstant(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("Constant value cannot be empty.");
        }
        String s = raw.trim();
        if (s.isEmpty()) {
            throw new IllegalArgumentException("Constant value cannot be empty.");
        }
        switch (this) {
            case BOOL -> {
                if (s.equalsIgnoreCase("true")) return "1";
                if (s.equalsIgnoreCase("false")) return "0";
                long v = parseIntegerLiteral(s);
                return (v <= 0) ? "0" : "1";
            }
            case INT -> {
                long v = parseIntegerLiteral(s);
                if (v < Integer.MIN_VALUE || v > Integer.MAX_VALUE) {
                    throw new IllegalArgumentException("INT constant out of 32-bit signed range: " + s);
                }
                return Integer.toString((int) v);
            }
            case LONG -> {
                long v = parseIntegerLiteral(s);
                return Long.toString(v);
            }
            case FLOAT -> {
                if (s.startsWith("0x") || s.startsWith("0X") || s.startsWith("0b") || s.startsWith("0B")) {
                    long bits = parseIntegerLiteral(s);
                    return Float.toString((float) bits);
                }
                try {
                    float f = Float.parseFloat(s);
                    if (Float.isNaN(f) || Float.isInfinite(f)) {
                        throw new IllegalArgumentException("FLOAT constant must be finite.");
                    }
                    if (f == (long) f) {
                        return Long.toString((long) f);
                    }
                    return Float.toString(f);
                } catch (NumberFormatException ex) {
                    throw new IllegalArgumentException("Invalid FLOAT constant: '" + s + "'");
                }
            }
        }
        throw new IllegalArgumentException("Unsupported data type: " + this);
    }

    public static long parseIntegerLiteral(String s) {
        try {
            if (s.startsWith("0x") || s.startsWith("0X")) {
                return Long.parseUnsignedLong(s.substring(2), 16);
            } else if (s.startsWith("-0x") || s.startsWith("-0X")) {
                return -Long.parseUnsignedLong(s.substring(3), 16);
            } else if (s.startsWith("0b") || s.startsWith("0B")) {
                return Long.parseUnsignedLong(s.substring(2), 2);
            } else if (s.startsWith("-0b") || s.startsWith("-0B")) {
                return -Long.parseUnsignedLong(s.substring(3), 2);
            }
            return Long.parseLong(s);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Invalid numeric constant: '" + s + "'");
        }
    }

    public double toNumericValue(String valStr) {
        if (valStr == null || valStr.isBlank()) return 0.0;
        try {
            if (this == FLOAT) {
                return Double.parseDouble(valStr.trim());
            }
            return (double) parseIntegerLiteral(valStr.trim());
        } catch (Exception e) {
            return 0.0;
        }
    }

    public String formatRuntimeValue(double rawVal, boolean truncatedForBox) {
        return switch (this) {
            case BOOL -> (rawVal != 0.0 && !Double.isNaN(rawVal)) ? "1" : "0";
            case INT -> Integer.toString((int) rawVal);
            case LONG -> Long.toString((long) rawVal);
            case FLOAT -> {
                float f = (float) rawVal;
                if (Float.isNaN(f) || Float.isInfinite(f)) yield "0.00";
                if (!truncatedForBox) yield Float.toString(f);
                if (Math.abs(f - Math.round(f)) < 1e-5f) {
                    yield Integer.toString(Math.round(f));
                }
                yield String.format(java.util.Locale.US, "%.2f", f);
            }
        };
    }

    public static double convertValue(double srcValue, DataType srcType, DataType dstType) {
        if (srcType == dstType) return srcValue;
        return switch (dstType) {
            case BOOL -> (srcValue != 0.0 && !Double.isNaN(srcValue)) ? 1.0 : 0.0;
            case INT -> (double) ((int) srcValue);
            case LONG -> (double) ((long) srcValue);
            case FLOAT -> (double) ((float) srcValue);
        };
    }
}
