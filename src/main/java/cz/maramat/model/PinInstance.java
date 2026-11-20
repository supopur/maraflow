package cz.maramat.model;

public class PinInstance {
    private String shortName;
    private String fullName;
    private DataType dataType;
    private final boolean input;
    private String constantValue;
    private double runtimeValue;
    private boolean negated;

    public PinInstance(String shortName, String fullName, DataType dataType, boolean input, String defaultConstant) {
        setShortName(shortName);
        this.fullName = (fullName == null || fullName.isBlank()) ? this.shortName : fullName;
        this.dataType = dataType;
        this.input = input;
        this.constantValue = (defaultConstant != null) ? defaultConstant : dataType.getDefaultConstant();
        this.runtimeValue = dataType.toNumericValue(this.constantValue);
        this.negated = false;
    }

    public PinInstance copy() {
        PinInstance p = new PinInstance(shortName, fullName, dataType, input, constantValue);
        p.negated = this.negated;
        p.runtimeValue = this.runtimeValue;
        return p;
    }

    public String getShortName() {
        return shortName;
    }

    public void setShortName(String name) {
        if (name == null || name.isBlank()) {
            this.shortName = "P";
        } else {
            this.shortName = name.trim();
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
        if (dataType != DataType.BOOL) {
            this.negated = false;
        }
    }

    public boolean isInput() {
        return input;
    }

    public boolean isNegated() {
        return negated;
    }

    public void setNegated(boolean negated) {
        boolean next = (this.dataType == DataType.BOOL) && negated;
        if (this.negated != next) {
            this.negated = next;
            if (this.input) {
                double v = dataType.toNumericValue(this.constantValue);
                this.runtimeValue = this.negated ? ((v == 0.0) ? 1.0 : 0.0) : v;
            } else {
                this.runtimeValue = (this.runtimeValue == 0.0) ? 1.0 : 0.0;
            }
        }
    }

    public String getConstantValue() {
        return constantValue;
    }

    public void setConstantValue(String constantValue) {
        this.constantValue = dataType.validateAndNormalizeConstant(constantValue);
        double v = dataType.toNumericValue(this.constantValue);
        if (this.negated && this.dataType == DataType.BOOL) {
            v = (v == 0.0) ? 1.0 : 0.0;
        }
        this.runtimeValue = v;
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
