package cz.maramat.model;

import cz.maramat.model.BlockDefinition.PinSpec;

import java.awt.Point;
import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class BlockInstance {
    public static final int HEADER_GRID_HEIGHT = 2;
    public static final int PIN_COL_GRID_WIDTH = 2;

    private String id;
    private String definitionId;
    private String typeName;
    private String name;
    private int gridX;
    private int gridY;
    private int courtyardGridWidth;
    private int minBodyGridHeight;
    private int threadId;
    private int order;
    private HeaderColor headerColor = HeaderColor.CL_DEFAULT;
    private String description = "";

    private final List<PinInstance> inputs = new ArrayList<>();
    private final List<PinInstance> outputs = new ArrayList<>();
    private final Map<String, String> customProperties = new LinkedHashMap<>();

    // Transient runtime state for edge detection, flip-flops, timers, registers
    private transient boolean prevEnState = false;
    private transient boolean prevTriggerState = false;
    private transient double internalState1 = 0.0;
    private transient double internalState2 = 0.0;

    public BlockInstance(BlockDefinition def, String instanceName, int gridX, int gridY, int threadId, int order) {
        this.id = UUID.randomUUID().toString();
        this.definitionId = def.getId();
        this.typeName = def.getTypeName();
        this.name = instanceName;
        this.gridX = gridX;
        this.gridY = gridY;
        this.courtyardGridWidth = def.getCourtyardGridWidth();
        this.minBodyGridHeight = def.getMinBodyGridHeight();
        this.threadId = threadId;
        this.order = Math.max(0, order);

        for (PinSpec ps : def.getDefaultInputs()) {
            inputs.add(new PinInstance(ps.shortName(), ps.fullName(), ps.dataType(), true, ps.defaultConstant()));
        }
        for (PinSpec ps : def.getDefaultOutputs()) {
            outputs.add(new PinInstance(ps.shortName(), ps.fullName(), ps.dataType(), false, ps.defaultConstant()));
        }

        initDefaultCustomProperties(def);
    }

    private void initDefaultCustomProperties(BlockDefinition def) {
        if (def.isVariableInputCount()) {
            int dataInputs = (int) inputs.stream().filter(p -> !p.getShortName().equals("En") && !p.getShortName().equals("Adr")).count();
            customProperties.put("InputCount", Integer.toString(Math.max(2, dataInputs)));
        }
        if (def.isConfigurableDataType()) {
            DataType dt = DataType.INT;
            if (!outputs.isEmpty()) {
                dt = outputs.getFirst().getDataType();
            } else if (!inputs.isEmpty()) {
                dt = inputs.getLast().getDataType();
            }
            customProperties.put("DataType", dt.name());
        }
        if (definitionId.equals("IO.8xDI") || definitionId.equals("IO.8xDO")) {
            String prefix = definitionId.endsWith("DI") ? "PA" : "PB";
            for (int i = 0; i < 8; i++) {
                customProperties.put("Pin" + i, prefix + i);
            }
        } else if (definitionId.equals("IO.DI")) {
            customProperties.put("Channel", "DI0");
        } else if (definitionId.equals("IO.DO")) {
            customProperties.put("Channel", "DO0");
        } else if (definitionId.equals("IO.AI")) {
            customProperties.put("Channel", "AI0");
        } else if (definitionId.equals("IO.AO")) {
            customProperties.put("Channel", "AO0");
        } else if (definitionId.equals("IO.PWM")) {
            customProperties.put("Channel", "PWM0");
        } else if (definitionId.startsWith("IO.")) {
            customProperties.put("HardwareChannel", "CH0 (PA0)");
        } else if (definitionId.startsWith("Comm.Modbus")) {
            customProperties.put("SlaveId", "1");
            customProperties.put("RegisterType", "Holding Register (4x)");
            customProperties.put("RegisterAddress", "0");
            if (definitionId.contains("Tcp")) {
                customProperties.put("RemoteIP", "192.168.1.100");
                customProperties.put("RemotePort", "502");
            }
        } else if (definitionId.startsWith("Comm.REST")) {
            customProperties.put("EndpointUrl", "http://192.168.1.100/api/val");
            customProperties.put("JsonKey", "value");
            customProperties.put("TimeoutMs", "1000");
        } else if (definitionId.startsWith("Comm.DS18B20")) {
            customProperties.put("OneWirePin", "PC4");
            customProperties.put("RomAddress", "28-000000000001");
            customProperties.put("ResolutionBits", "12");
        } else if (definitionId.startsWith("Comm.I2C")) {
            customProperties.put("DeviceAddressHex", "0x48");
            customProperties.put("RegisterAddressHex", "0x00");
        } else if (definitionId.startsWith("Comm.SPI")) {
            customProperties.put("CsPin", "PB12");
            customProperties.put("BitWidth", "8");
        } else if (definitionId.equals("Main.Execution.Interrupt")) {
            customProperties.put("InterruptSource", "TIM_IC1");
            customProperties.put("TriggerEdge", "RISING");
            customProperties.put("CaptureMode", "PULSE_WIDTH_US");
            customProperties.put("LatchPolicy", "HOLD_FIRST_UNTIL_RST");
            customProperties.put("SimPulseValue", "1160");
        } else if (definitionId.startsWith("Main.Execution.")) {
            customProperties.put("RegisterName", "REG_0");
            customProperties.put("TriggerMode", "Continuous (While En=1)");
            customProperties.put("RetentiveEeprom", "false");
        } else if (definitionId.equals("Main.Timers.Timer")) {
            customProperties.put("TimerMode", "TON (On-Delay)");
        } else if (definitionId.startsWith("Visual.Vars.")) {
            customProperties.put("InputType", definitionId.endsWith("BoolVar") ? "Switch" : "Slider");
            customProperties.put("ValA", "0");
            customProperties.put("ValB", definitionId.endsWith("BoolVar") ? "1" : "100");
            customProperties.put("SliderMin", "0");
            customProperties.put("SliderMax", "100");
            customProperties.put("CurrentValue", "0");
        }
    }

    public BlockInstance copy() {
        BlockDefinition def = BlockCatalog.getById(definitionId);
        BlockInstance clone;
        if (def != null) {
            clone = new BlockInstance(def, name, gridX, gridY, threadId, order);
        } else {
            clone = new BlockInstance(
                    new BlockDefinition(definitionId, "Macro", typeName, typeName, List.of(), List.of(), courtyardGridWidth, minBodyGridHeight, false, false, 0.2),
                    name, gridX, gridY, threadId, order
            );
        }
        clone.id = this.id;
        clone.headerColor = this.headerColor;
        clone.description = this.description;
        clone.inputs.clear();
        for (PinInstance p : this.inputs) clone.inputs.add(p.copy());
        clone.outputs.clear();
        for (PinInstance p : this.outputs) clone.outputs.add(p.copy());
        clone.customProperties.clear();
        clone.customProperties.putAll(this.customProperties);
        return clone;
    }

    public int getGridWidth() {
        return PIN_COL_GRID_WIDTH + courtyardGridWidth + PIN_COL_GRID_WIDTH;
    }

    public int getBodyGridHeight() {
        int pinRows = Math.max(inputs.size(), outputs.size());
        return Math.max(minBodyGridHeight, Math.max(2, pinRows));
    }

    public int getGridHeight() {
        return HEADER_GRID_HEIGHT + getBodyGridHeight();
    }

    /**
     * Returns the grid point where a wire connects to the center of the given input pin (offset by 0.5).
     */
    public Point2D.Double getInputPinAnchorGrid(int pinIndex) {
        return new Point2D.Double(gridX, gridY + HEADER_GRID_HEIGHT + pinIndex + 0.5);
    }

    /**
     * Returns the grid point where a wire connects to the center of the given output pin (offset by 0.5).
     */
    public Point2D.Double getOutputPinAnchorGrid(int pinIndex) {
        return new Point2D.Double(gridX + getGridWidth(), gridY + HEADER_GRID_HEIGHT + pinIndex + 0.5);
    }

    public void setVariableInputCount(int newCount) {
        int count = Math.clamp(newCount, 2, 16);
        customProperties.put("InputCount", Integer.toString(count));

        if (definitionId.equals("Main.Switches.Mux")) {
            DataType dt = DataType.valueOf(customProperties.getOrDefault("DataType", "INT"));
            while (inputs.size() > 2) {
                inputs.removeLast();
            }
            for (int i = 0; i < count; i++) {
                inputs.add(new PinInstance("X" + i, "Channel " + i, dt, true, dt.getDefaultConstant()));
            }
        } else {
            DataType dt = inputs.size() > 1 ? inputs.get(1).getDataType() : DataType.BOOL;
            while (inputs.size() > 1) {
                inputs.removeLast();
            }
            for (int i = 1; i <= count; i++) {
                inputs.add(new PinInstance("X" + i, "Input " + i, dt, true, dt.getDefaultConstant()));
            }
        }
    }

    public void setPrimaryDataType(DataType newType) {
        customProperties.put("DataType", newType.name());
        for (PinInstance p : inputs) {
            String sn = p.getShortName();
            if (!sn.equals("En") && !sn.equals("Adr") && !sn.equals("G") && !sn.equals("Idx") && !sn.equals("Rst")) {
                p.setDataType(newType);
                try {
                    p.setConstantValue(p.getConstantValue());
                } catch (Exception e) {
                    p.setConstantValue(newType.getDefaultConstant());
                }
            }
        }
        for (PinInstance p : outputs) {
            String sn = p.getShortName();
            if (!sn.equals("Ok") && !sn.equals("Err") && !sn.equals("Oob") && !sn.equals("Rdy") && !sn.equals("Sts")
                    && !sn.equals("Flg") && !definitionId.startsWith("Main.Comparators.")) {
                p.setDataType(newType);
            }
        }
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getDefinitionId() {
        return definitionId;
    }

    public void setDefinitionId(String definitionId) {
        this.definitionId = definitionId;
    }

    public void syncPinsWithDefinition(BlockDefinition def) {
        if (def == null) return;
        this.courtyardGridWidth = def.getCourtyardGridWidth();
        this.minBodyGridHeight = def.getMinBodyGridHeight();

        List<PinInstance> oldInputs = new ArrayList<>(this.inputs);
        this.inputs.clear();
        List<PinSpec> defIns = def.getDefaultInputs();
        for (int i = 0; i < defIns.size(); i++) {
            PinSpec ps = defIns.get(i);
            PinInstance np = new PinInstance(ps.shortName(), ps.fullName(), ps.dataType(), true, ps.defaultConstant());
            if (i < oldInputs.size()) {
                try {
                    np.setConstantValue(oldInputs.get(i).getConstantValue());
                } catch (Exception ignored) {
                }
            }
            this.inputs.add(np);
        }

        this.outputs.clear();
        for (PinSpec ps : def.getDefaultOutputs()) {
            this.outputs.add(new PinInstance(ps.shortName(), ps.fullName(), ps.dataType(), false, ps.defaultConstant()));
        }
    }

    public String getTypeName() {
        return typeName;
    }

    public void setTypeName(String typeName) {
        this.typeName = typeName;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getGridX() {
        return gridX;
    }

    public void setGridX(int gridX) {
        this.gridX = gridX;
    }

    public int getGridY() {
        return gridY;
    }

    public void setGridY(int gridY) {
        this.gridY = gridY;
    }

    public int getCourtyardGridWidth() {
        return courtyardGridWidth;
    }

    public int getThreadId() {
        return threadId;
    }

    public void setThreadId(int threadId) {
        this.threadId = Math.clamp(threadId, 1, 5);
    }

    public int getOrder() {
        return order;
    }

    public void setOrder(int order) {
        this.order = Math.max(0, order);
    }

    public HeaderColor getHeaderColor() {
        return headerColor;
    }

    public void setHeaderColor(HeaderColor headerColor) {
        this.headerColor = headerColor;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = (description == null) ? "" : description;
    }

    public List<PinInstance> getInputs() {
        return inputs;
    }

    public List<PinInstance> getOutputs() {
        return outputs;
    }

    public Map<String, String> getCustomProperties() {
        return customProperties;
    }

    public boolean isPrevEnState() {
        return prevEnState;
    }

    public void setPrevEnState(boolean prevEnState) {
        this.prevEnState = prevEnState;
    }

    public boolean isPrevTriggerState() {
        return prevTriggerState;
    }

    public void setPrevTriggerState(boolean prevTriggerState) {
        this.prevTriggerState = prevTriggerState;
    }

    public double getInternalState1() {
        return internalState1;
    }

    public void setInternalState1(double internalState1) {
        this.internalState1 = internalState1;
    }

    public double getInternalState2() {
        return internalState2;
    }

    public void setInternalState2(double internalState2) {
        this.internalState2 = internalState2;
    }

    public void resetRuntimeState() {
        this.prevEnState = false;
        this.prevTriggerState = false;
        this.internalState1 = 0.0;
        this.internalState2 = 0.0;
        for (PinInstance p : inputs) {
            p.setRuntimeValue(p.getDataType().toNumericValue(p.getConstantValue()));
        }
        for (PinInstance p : outputs) {
            if (definitionId.startsWith("Visual.Vars.")) {
                double v = p.getDataType().toNumericValue(customProperties.getOrDefault("CurrentValue", "0"));
                p.setRuntimeValue(v);
            } else {
                p.setRuntimeValue(0.0);
            }
        }
    }
}
