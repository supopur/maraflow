package cz.maramat.model;

public enum IecLanguage {
    FBD("FBD", "Function Block Diagram"),
    LD("LD", "Ladder Diagram"),
    ST("ST", "Structured Text");

    private final String shortCode;
    private final String displayName;

    IecLanguage(String shortCode, String displayName) {
        this.shortCode = shortCode;
        this.displayName = displayName;
    }

    public String getShortCode() {
        return shortCode;
    }

    public String getDisplayName() {
        return displayName;
    }

    public static IecLanguage fromCode(String code) {
        if (code == null) return FBD;
        for (IecLanguage lang : values()) {
            if (lang.name().equalsIgnoreCase(code.trim()) || lang.shortCode.equalsIgnoreCase(code.trim())) {
                return lang;
            }
        }
        return FBD;
    }

    @Override
    public String toString() {
        return shortCode + " - " + displayName;
    }
}
