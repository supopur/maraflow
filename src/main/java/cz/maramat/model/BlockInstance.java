package cz.maramat.model;

import cz.maramat.model.BlockDefinition.PinSpec;

import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class BlockInstance {
    public static final int HEADER_GRID_HEIGHT = 2;
    public static final int PIN_COL_GRID_WIDTH = 2;
    private static final int BASE_GRID_PX = 10;
    private static final FontMetrics BASE_PIN_METRICS = createBasePinMetrics();

    private static FontMetrics createBasePinMetrics() {
        BufferedImage img = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = img.createGraphics();
        FontMetrics fm = g2.getFontMetrics(new Font("Segoe UI", Font.PLAIN, BASE_GRID_PX - 2));
        g2.dispose();
        return fm;
    }

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
    private final Map<String, String> customProperties = java.util.Collections.synchronizedMap(new LinkedHashMap<>());

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
        if (definitionId.equals("IO.IREF")) {
            customProperties.put("Channel", "DI0");
        } else if (definitionId.equals("IO.OREF")) {
            customProperties.put("Channel", "DO0");
        } else if (definitionId.equals("IO.PWM")) {
            customProperties.put("Channel", "PWM0");
        } else if (definitionId.equals("IO.CH455G")) {
            customProperties.put("SclPin", "DIO2");
            customProperties.put("SdaPin", "DIO3");
            customProperties.put("DisplayMode", "Number (Float / Int)");
            customProperties.put("DigitMask", "1111");
        } else if (definitionId.startsWith("IO.")) {
            customProperties.put("HardwareChannel", "CH0 (PA0)");
        } else if (definitionId.startsWith("Comm.Modbus")) {
            customProperties.put("SlaveId", "1");
            customProperties.put("RegisterType", "MHR - Modbus Holding Register");
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
            customProperties.put("OneWirePin", "DIO13");
            customProperties.put("RomAddress", "AUTO:0");
            customProperties.put("ResolutionBits", "12");
        } else if (definitionId.equals("Comm.I2C.TMP75")) {
            customProperties.put("DeviceAddressHex", "0x48");
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
            customProperties.put("SwitchMode", "Toggle");
            customProperties.put("ValA", "0");
            customProperties.put("ValB", definitionId.endsWith("BoolVar") ? "1" : "100");
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

    public boolean isIoRef() {
        return "IO.IREF".equals(definitionId) || "IO.OREF".equals(definitionId);
    }

    public boolean isInputRef() {
        return "IO.IREF".equals(definitionId);
    }

    public boolean isOutputRef() {
        return "IO.OREF".equals(definitionId);
    }

    public String getIoRefLabel() {
        String ch = customProperties.get("Channel");
        if (ch != null && !ch.isBlank()) {
            return ch.trim();
        }
        return name != null ? name : "";
    }

    public int getIoRefBodyGridWidth() {
        String label = getIoRefLabel();
        int textPx = BASE_PIN_METRICS.stringWidth(label);
        return Math.max(2, (int) Math.ceil((textPx + 8.0) / BASE_GRID_PX));
    }

    public void setIoRefChannel(String channel) {
        String ch = channel != null ? channel.trim() : "";
        customProperties.put("Channel", ch);
        String upper = ch.toUpperCase();
        DataType dt = (upper.startsWith("AI") || upper.startsWith("AO")) ? DataType.INT : DataType.BOOL;
        if (isInputRef() && !outputs.isEmpty()) {
            outputs.getFirst().setDataType(dt);
        } else if (isOutputRef() && !inputs.isEmpty()) {
            inputs.getFirst().setDataType(dt);
        }
    }

    public int getGridWidth() {
        if (isIoRef()) {
            return getIoRefBodyGridWidth() + 1;
        }
        if (definitionId.startsWith("Visual.Vars.")) {
            return courtyardGridWidth + 1;
        }
        return PIN_COL_GRID_WIDTH + courtyardGridWidth + PIN_COL_GRID_WIDTH;
    }

    public int getBodyGridHeight() {
        if (isIoRef()) {
            return 1;
        }
        int pinRows = Math.max(inputs.size(), outputs.size());
        return Math.max(minBodyGridHeight, Math.max(2, pinRows));
    }

    public int getGridHeight() {
        if (isIoRef()) {
            return 1;
        }
        return HEADER_GRID_HEIGHT + getBodyGridHeight();
    }

    /**
     * Returns the grid point where a wire connects to the center of the given input pin (offset by 0.5).
     */
    public Point2D.Double getInputPinAnchorGrid(int pinIndex) {
        if (isIoRef()) {
            return new Point2D.Double(gridX, gridY + 0.5);
        }
        return new Point2D.Double(gridX, gridY + HEADER_GRID_HEIGHT + pinIndex + 0.5);
    }

    /**
     * Returns the grid point where a wire connects to the center of the given output pin (offset by 0.5).
     */
    public Point2D.Double getOutputPinAnchorGrid(int pinIndex) {
        if (isIoRef()) {
            return new Point2D.Double(gridX + getGridWidth(), gridY + 0.5);
        }
        return new Point2D.Double(gridX + getGridWidth(), gridY + HEADER_GRID_HEIGHT + pinIndex + 0.5);
    }

    public void setVariableInputCount(int newCount) {
        int count = Math.clamp(newCount, 2, 16);
        customProperties.put("InputCount", Integer.toString(count));

        if (definitionId.equals("Main.Switches.Mux")) {
            DataType dt = DataType.valueOf(customProperties.getOrDefault("DataType", "INT"));
            List<PinInstance> oldChannels = new ArrayList<>();
            for (int i = 2; i < inputs.size(); i++) {
                oldChannels.add(inputs.get(i));
            }
            while (inputs.size() > 2) {
                inputs.removeLast();
            }
            for (int i = 0; i < count; i++) {
                PinInstance np = new PinInstance("X" + i, "Channel " + i, dt, true, dt.getDefaultConstant());
                if (i < oldChannels.size()) {
                    try {
                        np.setConstantValue(oldChannels.get(i).getConstantValue());
                    } catch (Exception ignored) {
                    }
                    np.setNegated(oldChannels.get(i).isNegated());
                }
                inputs.add(np);
            }
        } else {
            DataType dt = inputs.size() > 1 ? inputs.get(1).getDataType() : DataType.BOOL;
            List<PinInstance> oldDataIns = new ArrayList<>();
            for (int i = 1; i < inputs.size(); i++) {
                oldDataIns.add(inputs.get(i));
            }
            while (inputs.size() > 1) {
                inputs.removeLast();
            }
            for (int i = 1; i <= count; i++) {
                PinInstance np = new PinInstance("X" + i, "Input " + i, dt, true, dt.getDefaultConstant());
                if (i - 1 < oldDataIns.size()) {
                    try {
                        np.setConstantValue(oldDataIns.get(i - 1).getConstantValue());
                    } catch (Exception ignored) {
                    }
                    np.setNegated(oldDataIns.get(i - 1).isNegated());
                }
                inputs.add(np);
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

    public void setDivModuloOut(boolean enabled) {
        if (!"Math.Div".equals(definitionId)) return;
        customProperties.put("ModuloOut", Boolean.toString(enabled));
        if (enabled) {
            if (outputs.size() < 3) {
                DataType dt = outputs.isEmpty() ? DataType.INT : outputs.get(0).getDataType();
                outputs.add(new PinInstance("Mod", "Modulo Remainder", dt, false, dt.getDefaultConstant()));
            }
        } else {
            while (outputs.size() > 2) {
                outputs.remove(outputs.size() - 1);
            }
        }
    }

    public static String normalizeDigitMask(String rawMask) {
        if (rawMask == null || rawMask.isBlank()) return "1111";
        StringBuilder sb = new StringBuilder(4);
        String trimmed = rawMask.trim();
        for (int i = 0; i < 4; i++) {
            char c = (i < trimmed.length()) ? trimmed.charAt(i) : '0';
            sb.append((c == '1' || c == 'T' || c == 't' || c == 'X' || c == 'x') ? '1' : '0');
        }
        return sb.toString();
    }

    public static int parseDigitMaskBits(String rawMask) {
        String norm = normalizeDigitMask(rawMask);
        int mask = 0;
        for (int i = 0; i < 4; i++) {
            if (norm.charAt(i) == '1') {
                mask |= (1 << i);
            }
        }
        return mask;
    }

    public void configureCh455gPins(String displayMode, String rawDigitMask) {
        if (!"IO.CH455G".equals(definitionId)) return;
        String mode = (displayMode != null && displayMode.startsWith("Raw"))
                ? "Raw Digits / Segments"
                : "Number (Float / Int)";
        String normMask = normalizeDigitMask(rawDigitMask);
        customProperties.put("DisplayMode", mode);
        customProperties.put("DigitMask", normMask);

        Map<String, PinInstance> oldByShort = new LinkedHashMap<>();
        for (PinInstance p : inputs) {
            oldByShort.put(p.getShortName(), p);
        }

        List<PinInstance> nextIns = new ArrayList<>();
        nextIns.add(copyOrCreatePin(oldByShort, "En", "Enable Execution", DataType.BOOL, "1"));
        if (mode.startsWith("Raw")) {
            for (int i = 0; i < 4; i++) {
                if (normMask.charAt(i) == '1') {
                    nextIns.add(copyOrCreatePin(oldByShort, "D" + i, "Digit " + i + " (0-F / -1=Blank / -2=- / 0x100+Seg)", DataType.INT, "0"));
                }
            }
            nextIns.add(copyOrCreatePin(oldByShort, "DpMask", "Decimal Point Bitmask (0-15)", DataType.INT, "0"));
            nextIns.add(copyOrCreatePin(oldByShort, "Brt", "Brightness (1-8)", DataType.INT, "8"));
        } else {
            nextIns.add(copyOrCreatePin(oldByShort, "Val", "Display Value", DataType.FLOAT, "0"));
            nextIns.add(copyOrCreatePin(oldByShort, "Dec", "Decimal Places (0-3)", DataType.INT, "1"));
            nextIns.add(copyOrCreatePin(oldByShort, "Brt", "Brightness (1-8)", DataType.INT, "8"));
        }

        inputs.clear();
        inputs.addAll(nextIns);
    }

    private static PinInstance copyOrCreatePin(Map<String, PinInstance> oldByShort, String shortName, String fullName, DataType dt, String defVal) {
        PinInstance old = oldByShort.get(shortName);
        PinInstance np = new PinInstance(shortName, fullName, dt, true, defVal);
        if (old != null) {
            try {
                np.setConstantValue(old.getConstantValue());
            } catch (Exception ignored) {
            }
            np.setNegated(old.isNegated());
        }
        return np;
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
                np.setNegated(oldInputs.get(i).isNegated());
            }
            this.inputs.add(np);
        }

        List<PinInstance> oldOutputs = new ArrayList<>(this.outputs);
        this.outputs.clear();
        List<PinSpec> defOuts = def.getDefaultOutputs();
        for (int i = 0; i < defOuts.size(); i++) {
            PinSpec ps = defOuts.get(i);
            PinInstance np = new PinInstance(ps.shortName(), ps.fullName(), ps.dataType(), false, ps.defaultConstant());
            if (i < oldOutputs.size()) {
                np.setNegated(oldOutputs.get(i).isNegated());
            }
            this.outputs.add(np);
        }
        if ("Math.Div".equals(definitionId) && "true".equalsIgnoreCase(customProperties.get("ModuloOut"))) {
            setDivModuloOut(true);
            if (oldOutputs.size() > 2 && this.outputs.size() > 2) {
                this.outputs.get(2).setNegated(oldOutputs.get(2).isNegated());
            }
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
            double v = p.getDataType().toNumericValue(p.getConstantValue());
            if (p.isNegated() && p.getDataType() == DataType.BOOL) {
                v = (v == 0.0) ? 1.0 : 0.0;
            }
            p.setRuntimeValue(v);
        }
        for (PinInstance p : outputs) {
            if (definitionId.startsWith("Visual.Vars.")) {
                String defaultVal = customProperties.getOrDefault("ValA", "0");
                customProperties.put("CurrentValue", defaultVal);
                double v = p.getDataType().toNumericValue(defaultVal);
                if (p.isNegated() && p.getDataType() == DataType.BOOL) {
                    v = (v == 0.0) ? 1.0 : 0.0;
                }
                p.setRuntimeValue(v);
            } else {
                p.setRuntimeValue((p.isNegated() && p.getDataType() == DataType.BOOL) ? 1.0 : 0.0);
            }
        }
    }
}
