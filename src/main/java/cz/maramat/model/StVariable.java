package cz.maramat.model;

/**
 * Represents a variable declaration row in the top declaration table of an IEC 61131-3 Structured Text (ST) page or macro.
 */
public class StVariable {

    public enum Scope {
        VAR_INPUT("VAR_INPUT"),
        VAR_OUTPUT("VAR_OUTPUT"),
        VAR("VAR"),
        VAR_TEMP("VAR_TEMP");

        private final String keyword;

        Scope(String keyword) {
            this.keyword = keyword;
        }

        public String getKeyword() {
            return keyword;
        }

        @Override
        public String toString() {
            return keyword;
        }
    }

    private Scope scope;
    private String name;
    private DataType dataType;
    private String initialValue;
    private String description;
    private transient double runtimeValue = 0.0;

    public StVariable(Scope scope, String name, DataType dataType, String initialValue, String description) {
        this.scope = scope == null ? Scope.VAR : scope;
        this.name = (name == null || name.isBlank()) ? "Var1" : name.trim();
        this.dataType = dataType == null ? DataType.INT : dataType;
        this.initialValue = (initialValue == null || initialValue.isBlank()) ? this.dataType.getDefaultConstant() : initialValue.trim();
        this.description = description == null ? "" : description.trim();
        this.runtimeValue = this.dataType.toNumericValue(this.initialValue);
    }

    public StVariable copy() {
        StVariable v = new StVariable(scope, name, dataType, initialValue, description);
        v.runtimeValue = this.runtimeValue;
        return v;
    }

    public void resetRuntimeState() {
        this.runtimeValue = this.dataType.toNumericValue(this.initialValue);
    }

    public Scope getScope() {
        return scope;
    }

    public void setScope(Scope scope) {
        this.scope = scope == null ? Scope.VAR : scope;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = (name == null || name.isBlank()) ? "Var1" : name.trim();
    }

    public DataType getDataType() {
        return dataType;
    }

    public void setDataType(DataType dataType) {
        this.dataType = dataType == null ? DataType.INT : dataType;
    }

    public String getInitialValue() {
        return initialValue;
    }

    public void setInitialValue(String initialValue) {
        this.initialValue = (initialValue == null || initialValue.isBlank()) ? "0" : initialValue.trim();
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description == null ? "" : description.trim();
    }

    public double getRuntimeValue() {
        return runtimeValue;
    }

    public void setRuntimeValue(double runtimeValue) {
        this.runtimeValue = runtimeValue;
    }
}
