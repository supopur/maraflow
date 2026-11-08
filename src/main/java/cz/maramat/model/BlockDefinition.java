package cz.maramat.model;

import java.util.ArrayList;
import java.util.List;

public class BlockDefinition {
    public record PinSpec(String shortName, String fullName, DataType dataType, String defaultConstant) {}

    private final String id;
    private final String categoryPath;
    private final String typeName;
    private final String instancePrefix;
    private final List<PinSpec> defaultInputs;
    private final List<PinSpec> defaultOutputs;
    private final int courtyardGridWidth;
    private final int minBodyGridHeight;
    private final boolean variableInputCount;
    private final boolean configurableDataType;
    private final double estimatedCostUs;

    public BlockDefinition(
            String id,
            String categoryPath,
            String typeName,
            String instancePrefix,
            List<PinSpec> defaultInputs,
            List<PinSpec> defaultOutputs,
            int courtyardGridWidth,
            int minBodyGridHeight,
            boolean variableInputCount,
            boolean configurableDataType,
            double estimatedCostUs
    ) {
        this.id = id;
        this.categoryPath = categoryPath;
        this.typeName = typeName;
        this.instancePrefix = instancePrefix;
        this.defaultInputs = new ArrayList<>(defaultInputs);
        this.defaultOutputs = new ArrayList<>(defaultOutputs);
        this.courtyardGridWidth = courtyardGridWidth;
        this.minBodyGridHeight = minBodyGridHeight;
        this.variableInputCount = variableInputCount;
        this.configurableDataType = configurableDataType;
        this.estimatedCostUs = estimatedCostUs;
    }

    public String getId() {
        return id;
    }

    public String getCategoryPath() {
        return categoryPath;
    }

    public String getTypeName() {
        return typeName;
    }

    public String getInstancePrefix() {
        return instancePrefix;
    }

    public List<PinSpec> getDefaultInputs() {
        return defaultInputs;
    }

    public List<PinSpec> getDefaultOutputs() {
        return defaultOutputs;
    }

    public int getCourtyardGridWidth() {
        return courtyardGridWidth;
    }

    public int getMinBodyGridHeight() {
        return minBodyGridHeight;
    }

    public boolean isVariableInputCount() {
        return variableInputCount;
    }

    public boolean isConfigurableDataType() {
        return configurableDataType;
    }

    public double getEstimatedCostUs() {
        return estimatedCostUs;
    }

    @Override
    public String toString() {
        return typeName;
    }
}
