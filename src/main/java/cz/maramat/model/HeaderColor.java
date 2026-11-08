package cz.maramat.model;

import java.awt.Color;

public enum HeaderColor {
    CL_DEFAULT("clDefault", new Color(0x00, 0x80, 0x80)),
    CL_NAVY("clNavy", new Color(0x00, 0x33, 0x66)),
    CL_OLIVE("clOlive", new Color(0x55, 0x6B, 0x2F)),
    CL_MAROON("clMaroon", new Color(0x80, 0x00, 0x00)),
    CL_PURPLE("clPurple", new Color(0x4B, 0x00, 0x82)),
    CL_SLATE("clSlate", new Color(0x4A, 0x55, 0x68)),
    CL_GOLD("clGold", new Color(0xB7, 0x79, 0x1F));

    private final String label;
    private final Color color;

    HeaderColor(String label, Color color) {
        this.label = label;
        this.color = color;
    }

    public String getLabel() {
        return label;
    }

    public Color getColor() {
        return color;
    }

    public static HeaderColor fromLabel(String label) {
        for (HeaderColor hc : values()) {
            if (hc.label.equalsIgnoreCase(label)) {
                return hc;
            }
        }
        return CL_DEFAULT;
    }

    @Override
    public String toString() {
        return label;
    }
}
