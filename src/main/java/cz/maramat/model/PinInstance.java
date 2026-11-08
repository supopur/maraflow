package cz.maramat.model;

public class PinInstance {
    private String shortName;
    private String fullName;
    private DataType dataType;
    private final boolean input;
    private String constantValue;
    private double runtimeValue;

    public PinInstance(String shortName, String fullName, DataType dataType, boolean input, String defaultConstant) {
        setShortName(shortName);
        this.fullName = (fullName == null || fullName.isBlank()) ? this.shortName : fullName;
        this.dataType = dataType;
        this.input = input;
        this.constantValue = (defaultConstant != null) ? defaultConstant : dataType.getDefaultConstant();
        this.runtimeValue = dataType.toNumericValue(this.constantValue);
    }

    public PinInstance copy() {
        PinInstance p = new PinInstance(shortName, fullName, dataType, input, constantValue);
        p.runtimeValue = this.runtimeValue;
        return p;
    }

    public String getShortName() {
        return shortName;
    }

    public void setShortName(String name) {
        if (name == null || name.isBlank()) {
            this.shortName = "P";
        } else if (name.length() > 3) {
            this.shortName = name.substring(0, 3);
        } else {
            this.shortName = name;
        }
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public DataType getDataType() {
        return dataType;
    }

    public void setDataType(DataType dataType) {
        this.dataType = dataType;
    }

    public boolean isInput() {
        return input;
    }

    public String getConstantValue() {
        return constantValue;
    }

    public void setConstantValue(String constantValue) {
        this.constantValue = dataType.validateAndNormalizeConstant(constantValue);
        this.runtimeValue = dataType.toNumericValue(this.constantValue);
    }

    public double getRuntimeValue() {
        return runtimeValue;
    }

    public void setRuntimeValue(double runtimeValue) {
        this.runtimeValue = runtimeValue;
    }

    public String getFormattedRuntimeValue(boolean truncated) {
        return dataType.formatRuntimeValue(runtimeValue, truncated);
    }
}
