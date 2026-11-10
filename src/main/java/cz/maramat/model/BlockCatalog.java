package cz.maramat.model;

import cz.maramat.model.BlockDefinition.PinSpec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class BlockCatalog {
    private static final Map<String, BlockDefinition> DEFINITIONS = new LinkedHashMap<>();

    static {
        initCatalog();
    }

    private BlockCatalog() {}

    private static PinSpec pin(String shortName, String fullName, DataType type, String defVal) {
        return new PinSpec(shortName, fullName, type, defVal);
    }

    private static PinSpec enPin() {
        return new PinSpec("En", "Enable Execution", DataType.BOOL, "1");
    }

    private static void reg(BlockDefinition def) {
        DEFINITIONS.put(def.getId(), def);
    }

    private static void initCatalog() {
        // 1. Bool
        String[] boolMulti = {"And", "Nand", "Or", "Nor", "Xor", "Xnor"};
        for (String b : boolMulti) {
            reg(new BlockDefinition(
                    "Bool." + b, "Bool", b, b,
                    List.of(enPin(), pin("X1", "Input 1", DataType.BOOL, "0"), pin("X2", "Input 2", DataType.BOOL, "0")),
                    List.of(pin("Y", "Logical Output", DataType.BOOL, "0")),
                    3, 3, true, false, 0.08
            ));
        }
        reg(new BlockDefinition(
                "Bool.Not", "Bool", "Not", "Not",
                List.of(enPin(), pin("X", "Input", DataType.BOOL, "0")),
                List.of(pin("Y", "Inverted Output", DataType.BOOL, "0")),
                3, 2, false, false, 0.06
        ));
        reg(new BlockDefinition(
                "Bool.RS", "Bool", "RS", "RS",
                List.of(enPin(), pin("S", "Set Input", DataType.BOOL, "0"), pin("R", "Reset Input (Dominant)", DataType.BOOL, "0")),
                List.of(pin("Q", "Latch Output", DataType.BOOL, "0"), pin("nQ", "Inverted Latch Output", DataType.BOOL, "1")),
                3, 3, false, false, 0.12
        ));
        reg(new BlockDefinition(
                "Bool.SR", "Bool", "SR", "SR",
                List.of(enPin(), pin("S", "Set Input (Dominant)", DataType.BOOL, "0"), pin("R", "Reset Input", DataType.BOOL, "0")),
                List.of(pin("Q", "Latch Output", DataType.BOOL, "0"), pin("nQ", "Inverted Latch Output", DataType.BOOL, "1")),
                3, 3, false, false, 0.12
        ));

        // 2. Math
        String[] mathMulti = {"Add", "Sub", "Mul", "Min", "Max"};
        for (String m : mathMulti) {
            reg(new BlockDefinition(
                    "Math." + m, "Math", m, m,
                    List.of(enPin(), pin("X1", "Operand 1", DataType.INT, "0"), pin("X2", "Operand 2", DataType.INT, "0")),
                    List.of(pin("Y", "Result", DataType.INT, "0")),
                    3, 3, true, true, 0.18
            ));
        }
        String[] mathBin = {"Div", "Mod", "Pow"};
        for (String m : mathBin) {
            reg(new BlockDefinition(
                    "Math." + m, "Math", m, m,
                    List.of(enPin(), pin("A", "Dividend / Base", DataType.INT, "0"), pin("B", "Divisor / Exponent", DataType.INT, "1")),
                    List.of(pin("Y", "Result", DataType.INT, "0"), pin("Err", "Division By Zero Error", DataType.BOOL, "0")),
                    3, 3, false, true, 0.35
            ));
        }
        String[] mathUnary = {"Abs", "Neg", "Sqrt", "Sin", "Cos"};
        for (String m : mathUnary) {
            DataType dt = (m.equals("Sqrt") || m.equals("Sin") || m.equals("Cos")) ? DataType.FLOAT : DataType.INT;
            reg(new BlockDefinition(
                    "Math." + m, "Math", m, m,
                    List.of(enPin(), pin("X", "Input Value", dt, "0")),
                    List.of(pin("Y", "Result", dt, "0")),
                    3, 2, false, true, 0.45
            ));
        }
        reg(new BlockDefinition(
                "Math.Clamp", "Math", "Clamp", "Clamp",
                List.of(enPin(), pin("In", "Input Value", DataType.INT, "0"), pin("Min", "Minimum Limit", DataType.INT, "0"), pin("Max", "Maximum Limit", DataType.INT, "100")),
                List.of(pin("Y", "Clamped Output", DataType.INT, "0")),
                3, 4, false, true, 0.20
        ));
        reg(new BlockDefinition(
                "Math.Scale", "Math", "Scale", "Scale",
                List.of(
                        enPin(),
                        pin("In", "Input Value", DataType.FLOAT, "0"),
                        pin("Ilo", "Input Low", DataType.FLOAT, "0"),
                        pin("Ihi", "Input High", DataType.FLOAT, "100"),
                        pin("Olo", "Output Low", DataType.FLOAT, "0"),
                        pin("Ohi", "Output High", DataType.FLOAT, "10")
                ),
                List.of(pin("Y", "Scaled Output", DataType.FLOAT, "0")),
                3, 6, false, true, 0.55
        ));
        reg(new BlockDefinition(
                "Math.PID", "Math", "PID", "PID",
                List.of(
                        enPin(),
                        pin("PV", "Process Variable", DataType.FLOAT, "0"),
                        pin("SP", "Setpoint", DataType.FLOAT, "0"),
                        pin("Kp", "Proportional Gain", DataType.FLOAT, "1"),
                        pin("Ki", "Integral Gain", DataType.FLOAT, "0"),
                        pin("Kd", "Derivative Gain", DataType.FLOAT, "0"),
                        pin("Rst", "Reset Integrator", DataType.BOOL, "0")
                ),
                List.of(pin("Out", "Controller Output", DataType.FLOAT, "0")),
                4, 7, false, false, 1.80
        ));

        // 3. I/O (Hardware Target Aware)
        reg(new BlockDefinition(
                "IO.DI", "I/O", "DI", "DI",
                List.of(enPin()),
                List.of(pin("Y", "Digital Input State", DataType.BOOL, "0")),
                3, 2, false, false, 0.15
        ));
        reg(new BlockDefinition(
                "IO.DO", "I/O", "DO", "DO",
                List.of(enPin(), pin("X", "Output Drive State", DataType.BOOL, "0")),
                List.of(pin("Y", "Confirmed Output State", DataType.BOOL, "0")),
                3, 2, false, false, 0.15
        ));
        reg(new BlockDefinition(
                "IO.8xDI", "I/O", "8x DI", "8xDI",
                List.of(enPin()),
                List.of(
                        pin("DI0", "Digital Input 0", DataType.BOOL, "0"),
                        pin("DI1", "Digital Input 1", DataType.BOOL, "0"),
                        pin("DI2", "Digital Input 2", DataType.BOOL, "0"),
                        pin("DI3", "Digital Input 3", DataType.BOOL, "0"),
                        pin("DI4", "Digital Input 4", DataType.BOOL, "0"),
                        pin("DI5", "Digital Input 5", DataType.BOOL, "0"),
                        pin("DI6", "Digital Input 6", DataType.BOOL, "0"),
                        pin("DI7", "Digital Input 7", DataType.BOOL, "0")
                ),
                3, 8, false, false, 0.40
        ));
        reg(new BlockDefinition(
                "IO.8xDO", "I/O", "8x DO", "8xDO",
                List.of(
                        enPin(),
                        pin("DO0", "Digital Output 0", DataType.BOOL, "0"),
                        pin("DO1", "Digital Output 1", DataType.BOOL, "0"),
                        pin("DO2", "Digital Output 2", DataType.BOOL, "0"),
                        pin("DO3", "Digital Output 3", DataType.BOOL, "0"),
                        pin("DO4", "Digital Output 4", DataType.BOOL, "0"),
                        pin("DO5", "Digital Output 5", DataType.BOOL, "0"),
                        pin("DO6", "Digital Output 6", DataType.BOOL, "0"),
                        pin("DO7", "Digital Output 7", DataType.BOOL, "0")
                ),
                List.of(pin("Ok", "Port Write Status", DataType.BOOL, "1")),
                3, 9, false, false, 0.40
        ));
        reg(new BlockDefinition(
                "IO.AI", "I/O", "AI", "AI",
                List.of(enPin()),
                List.of(pin("Val", "Analog Input Raw/Scaled", DataType.INT, "0")),
                3, 2, false, true, 0.50
        ));
        reg(new BlockDefinition(
                "IO.AO", "I/O", "AO", "AO",
                List.of(enPin(), pin("Val", "Analog Output Value", DataType.INT, "0")),
                List.of(pin("Ok", "DAC Status", DataType.BOOL, "1")),
                3, 2, false, true, 0.50
        ));
        reg(new BlockDefinition(
                "IO.PWM", "I/O", "PWM", "PWM",
                List.of(enPin(), pin("Frq", "Frequency (Hz)", DataType.INT, "1000"), pin("Dty", "Duty Cycle (0-100%)", DataType.FLOAT, "50")),
                List.of(pin("Act", "PWM Active", DataType.BOOL, "0")),
                3, 3, false, false, 0.45
        ));
        reg(new BlockDefinition(
                "IO.Encoder", "I/O", "Encoder", "Enc",
                List.of(enPin(), pin("Rst", "Reset Counter", DataType.BOOL, "0")),
                List.of(pin("Cnt", "Quadrature Count", DataType.LONG, "0"), pin("Vel", "Pulse Velocity", DataType.INT, "0")),
                3, 2, false, false, 0.60
        ));

        // 4. Communication
        // Modbus RTU
        reg(new BlockDefinition(
                "Comm.ModbusRTU.MbRtuRead", "Communication/Modbus RTU", "MbRtuRead", "MbRtuRd",
                List.of(enPin()),
                List.of(pin("Val", "Register Value", DataType.INT, "0"), pin("Ok", "Transaction OK", DataType.BOOL, "0"), pin("Err", "Modbus Error Code", DataType.INT, "0")),
                4, 3, false, true, 2.50
        ));
        reg(new BlockDefinition(
                "Comm.ModbusRTU.MbRtuWrite", "Communication/Modbus RTU", "MbRtuWrite", "MbRtuWr",
                List.of(enPin(), pin("Val", "Value To Write", DataType.INT, "0")),
                List.of(pin("Ok", "Transaction OK", DataType.BOOL, "0"), pin("Err", "Modbus Error Code", DataType.INT, "0")),
                4, 2, false, true, 2.50
        ));
        // Modbus TCP
        reg(new BlockDefinition(
                "Comm.ModbusTCP.MbTcpRead", "Communication/Modbus TCP", "MbTcpRead", "MbTcpRd",
                List.of(enPin()),
                List.of(pin("Val", "Register Value", DataType.INT, "0"), pin("Ok", "Transaction OK", DataType.BOOL, "0"), pin("Err", "Modbus Error Code", DataType.INT, "0")),
                4, 3, false, true, 3.00
        ));
        reg(new BlockDefinition(
                "Comm.ModbusTCP.MbTcpWrite", "Communication/Modbus TCP", "MbTcpWrite", "MbTcpWr",
                List.of(enPin(), pin("Val", "Value To Write", DataType.INT, "0")),
                List.of(pin("Ok", "Transaction OK", DataType.BOOL, "0"), pin("Err", "Modbus Error Code", DataType.INT, "0")),
                4, 2, false, true, 3.00
        ));
        // REST
        reg(new BlockDefinition(
                "Comm.REST.RestGet", "Communication/REST", "RestGet", "RestGet",
                List.of(enPin()),
                List.of(pin("Out", "Parsed Response Value", DataType.FLOAT, "0"), pin("Sts", "HTTP Status Code", DataType.INT, "200"), pin("Rdy", "Response Ready", DataType.BOOL, "0")),
                4, 3, false, true, 4.00
        ));
        reg(new BlockDefinition(
                "Comm.REST.RestPost", "Communication/REST", "RestPost", "RestPost",
                List.of(enPin(), pin("Val", "Payload Value", DataType.FLOAT, "0")),
                List.of(pin("Sts", "HTTP Status Code", DataType.INT, "200"), pin("Rdy", "Request Completed", DataType.BOOL, "0")),
                4, 2, false, true, 4.00
        ));
        // DS18B20
        reg(new BlockDefinition(
                "Comm.DS18B20.DS18B20Read", "Communication/DS18B20", "DS18B20", "DS18B20",
                List.of(enPin()),
                List.of(pin("Tmp", "Temperature (deg C)", DataType.FLOAT, "20"), pin("Ok", "Sensor CRC OK", DataType.BOOL, "1")),
                4, 2, false, false, 1.50
        ));
        // I2C
        reg(new BlockDefinition(
                "Comm.I2C.I2CRead", "Communication/I2C", "I2CRead", "I2CRd",
                List.of(enPin()),
                List.of(pin("Val", "Read Value", DataType.INT, "0"), pin("Ok", "I2C ACK OK", DataType.BOOL, "1")),
                3, 2, false, true, 1.80
        ));
        reg(new BlockDefinition(
                "Comm.I2C.I2CWrite", "Communication/I2C", "I2CWrite", "I2CWr",
                List.of(enPin(), pin("Val", "Byte/Word To Write", DataType.INT, "0")),
                List.of(pin("Ok", "I2C ACK OK", DataType.BOOL, "1")),
                3, 2, false, true, 1.80
        ));
        // SPI
        reg(new BlockDefinition(
                "Comm.SPI.SPITransfer", "Communication/SPI", "SPITransfer", "SPITrx",
                List.of(enPin(), pin("Tx", "Transmit Word", DataType.INT, "0")),
                List.of(pin("Rx", "Received Word", DataType.INT, "0"), pin("Ok", "Transfer Complete", DataType.BOOL, "1")),
                4, 2, false, false, 1.40
        ));

        // 5. Main
        // Arrays
        reg(new BlockDefinition(
                "Main.Arrays.ArrayRead", "Main/Arrays", "ArrayRead", "ArrRd",
                List.of(enPin(), pin("Idx", "Array Element Index", DataType.INT, "0")),
                List.of(pin("Val", "Element Value", DataType.INT, "0"), pin("Oob", "Out Of Bounds", DataType.BOOL, "0")),
                3, 2, false, true, 0.20
        ));
        reg(new BlockDefinition(
                "Main.Arrays.ArrayWrite", "Main/Arrays", "ArrayWrite", "ArrWr",
                List.of(enPin(), pin("Idx", "Array Element Index", DataType.INT, "0"), pin("Val", "Element Value", DataType.INT, "0")),
                List.of(pin("Oob", "Out Of Bounds", DataType.BOOL, "0")),
                3, 3, false, true, 0.25
        ));
        // Comparators
        String[] comps = {"EQ", "NE", "GT", "GE", "LT", "LE"};
        for (String c : comps) {
            reg(new BlockDefinition(
                    "Main.Comparators." + c, "Main/Comparators", c, c,
                    List.of(enPin(), pin("X1", "Left Operand", DataType.INT, "0"), pin("X2", "Right Operand", DataType.INT, "0")),
                    List.of(pin("Y", "Comparison Result", DataType.BOOL, "0")),
                    3, 3, false, true, 0.12
            ));
        }
        reg(new BlockDefinition(
                "Main.Comparators.InRange", "Main/Comparators", "InRange", "InRng",
                List.of(enPin(), pin("In", "Input Value", DataType.INT, "0"), pin("Min", "Lower Bound", DataType.INT, "0"), pin("Max", "Upper Bound", DataType.INT, "100")),
                List.of(pin("Y", "Within Range", DataType.BOOL, "0")),
                3, 4, false, true, 0.18
        ));
        reg(new BlockDefinition(
                "Main.Comparators.Hysteresis", "Main/Comparators", "Hysteresis", "Hyst",
                List.of(enPin(), pin("In", "Input Signal", DataType.FLOAT, "0"), pin("Lo", "Low Threshold", DataType.FLOAT, "10"), pin("Hi", "High Threshold", DataType.FLOAT, "20")),
                List.of(pin("Y", "Schmitt Trigger Output", DataType.BOOL, "0")),
                3, 4, false, true, 0.20
        ));
        // Conversion
        reg(new BlockDefinition(
                "Main.Conversion.PackBits", "Main/Conversion", "PackBits", "Pack8",
                List.of(
                        enPin(),
                        pin("B0", "Bit 0", DataType.BOOL, "0"),
                        pin("B1", "Bit 1", DataType.BOOL, "0"),
                        pin("B2", "Bit 2", DataType.BOOL, "0"),
                        pin("B3", "Bit 3", DataType.BOOL, "0"),
                        pin("B4", "Bit 4", DataType.BOOL, "0"),
                        pin("B5", "Bit 5", DataType.BOOL, "0"),
                        pin("B6", "Bit 6", DataType.BOOL, "0"),
                        pin("B7", "Bit 7", DataType.BOOL, "0")
                ),
                List.of(pin("Y", "Packed Integer", DataType.INT, "0")),
                3, 9, false, false, 0.22
        ));
        reg(new BlockDefinition(
                "Main.Conversion.UnpackBits", "Main/Conversion", "UnpackBits", "Unpk8",
                List.of(enPin(), pin("In", "Packed Integer", DataType.INT, "0")),
                List.of(
                        pin("B0", "Bit 0", DataType.BOOL, "0"),
                        pin("B1", "Bit 1", DataType.BOOL, "0"),
                        pin("B2", "Bit 2", DataType.BOOL, "0"),
                        pin("B3", "Bit 3", DataType.BOOL, "0"),
                        pin("B4", "Bit 4", DataType.BOOL, "0"),
                        pin("B5", "Bit 5", DataType.BOOL, "0"),
                        pin("B6", "Bit 6", DataType.BOOL, "0"),
                        pin("B7", "Bit 7", DataType.BOOL, "0")
                ),
                3, 8, false, false, 0.22
        ));
        // Execution (Virtual Registers & Hardware Interrupts)
        reg(new BlockDefinition(
                "Main.Execution.ReadReg", "Main/Execution", "ReadReg", "RdReg",
                List.of(enPin()),
                List.of(pin("Out", "Register Value", DataType.INT, "0")),
                3, 2, false, true, 0.15
        ));
        reg(new BlockDefinition(
                "Main.Execution.WriteReg", "Main/Execution", "WriteReg", "WrReg",
                List.of(enPin(), pin("In", "Value To Store", DataType.INT, "0")),
                List.of(pin("Out", "Stored Register Value", DataType.INT, "0")),
                3, 2, false, true, 0.18
        ));
        reg(new BlockDefinition(
                "Main.Execution.Interrupt", "Main/Execution", "Interrupt", "IntCap",
                List.of(
                        enPin(),
                        pin("Rst", "Reset Latched Flag (End of Scan)", DataType.BOOL, "0")
                ),
                List.of(
                        pin("Val", "Captured Event Value (GPIO / Pulse us / Count)", DataType.INT, "0"),
                        pin("Flg", "Interrupt Occurred Since Last Rst", DataType.BOOL, "0")
                ),
                4, 3, false, true, 0.25
        ));
        // Safety PLC / Emergency Stop Block
        reg(new BlockDefinition(
                "Main.Safety.EStop", "Main/Safety", "EStop", "EStop",
                List.of(
                        enPin(),
                        pin("Rst", "Manual Safety Reset (Rising Edge)", DataType.BOOL, "0")
                ),
                List.of(
                        pin("Safe", "Safety OK (1=Run, 0=Tripped)", DataType.BOOL, "1"),
                        pin("Trip", "E-Stop Tripped Active", DataType.BOOL, "0"),
                        pin("Src", "Tripped Channel Bitmask", DataType.INT, "0")
                ),
                4, 3, false, false, 0.20
        ));
        // Filters
        reg(new BlockDefinition(
                "Main.Filters.MovingAvg", "Main/Filters", "MovingAvg", "MovAvg",
                List.of(enPin(), pin("In", "Sample Input", DataType.FLOAT, "0"), pin("Rst", "Reset Window", DataType.BOOL, "0")),
                List.of(pin("Y", "Filtered Average", DataType.FLOAT, "0")),
                3, 3, false, false, 0.65
        ));
        reg(new BlockDefinition(
                "Main.Filters.LowPass", "Main/Filters", "LowPass", "LPF",
                List.of(enPin(), pin("In", "Input Signal", DataType.FLOAT, "0"), pin("Alp", "Smoothing Factor Alpha (0..1)", DataType.FLOAT, "0.1")),
                List.of(pin("Y", "Filtered Output", DataType.FLOAT, "0")),
                3, 3, false, false, 0.40
        ));
        reg(new BlockDefinition(
                "Main.Filters.Debounce", "Main/Filters", "Debounce", "Debnc",
                List.of(enPin(), pin("In", "Raw Boolean Input", DataType.BOOL, "0"), pin("Tms", "Debounce Time (ms)", DataType.INT, "50")),
                List.of(pin("Y", "Debounced Output", DataType.BOOL, "0")),
                3, 3, false, false, 0.25
        ));
        // Switches
        reg(new BlockDefinition(
                "Main.Switches.Sel", "Main/Switches", "Sel", "Sel",
                List.of(enPin(), pin("G", "Select Gate (0=X0, 1=X1)", DataType.BOOL, "0"), pin("X0", "Input 0", DataType.INT, "0"), pin("X1", "Input 1", DataType.INT, "0")),
                List.of(pin("Y", "Selected Output", DataType.INT, "0")),
                3, 4, false, true, 0.14
        ));
        reg(new BlockDefinition(
                "Main.Switches.Mux", "Main/Switches", "Mux", "Mux",
                List.of(enPin(), pin("Adr", "Input Address Index (0..N-1)", DataType.INT, "0"), pin("X0", "Channel 0", DataType.INT, "0"), pin("X1", "Channel 1", DataType.INT, "0")),
                List.of(pin("Y", "Multiplexed Output", DataType.INT, "0")),
                3, 4, true, true, 0.22
        ));
        reg(new BlockDefinition(
                "Main.Switches.Demux", "Main/Switches", "Demux", "Demux",
                List.of(enPin(), pin("Adr", "Output Address Index", DataType.INT, "0"), pin("In", "Input Signal", DataType.INT, "0")),
                List.of(pin("Y0", "Output 0", DataType.INT, "0"), pin("Y1", "Output 1", DataType.INT, "0")),
                3, 3, false, true, 0.22
        ));
        // Timers
        reg(new BlockDefinition(
                "Main.Timers.DateTime", "Main/Timers", "DateTime", "DateTime",
                List.of(enPin()),
                List.of(pin("Ts", "Unix Timestamp (s)", DataType.LONG, "0")),
                3, 2, false, false, 0.30
        ));
        reg(new BlockDefinition(
                "Main.Timers.DecodeDate", "Main/Timers", "DecodeDate", "DecDate",
                List.of(enPin(), pin("Ts", "Unix Timestamp (s)", DataType.LONG, "0")),
                List.of(
                        pin("Yr", "Year", DataType.INT, "1970"),
                        pin("Mon", "Month (1-12)", DataType.INT, "1"),
                        pin("Day", "Day (1-31)", DataType.INT, "1"),
                        pin("Hr", "Hour (0-23)", DataType.INT, "0"),
                        pin("Min", "Minute (0-59)", DataType.INT, "0"),
                        pin("Sec", "Second (0-59)", DataType.INT, "0")
                ),
                4, 6, false, false, 0.85
        ));
        reg(new BlockDefinition(
                "Main.Timers.EncodeDate", "Main/Timers", "EncodeDate", "EncDate",
                List.of(
                        enPin(),
                        pin("Yr", "Year", DataType.INT, "2026"),
                        pin("Mon", "Month (1-12)", DataType.INT, "1"),
                        pin("Day", "Day (1-31)", DataType.INT, "1"),
                        pin("Hr", "Hour (0-23)", DataType.INT, "0"),
                        pin("Min", "Minute (0-59)", DataType.INT, "0"),
                        pin("Sec", "Second (0-59)", DataType.INT, "0")
                ),
                List.of(pin("Ts", "Unix Timestamp (s)", DataType.LONG, "0")),
                4, 7, false, false, 0.85
        ));
        reg(new BlockDefinition(
                "Main.Timers.PulseGen", "Main/Timers", "PulseGen", "PlsGen",
                List.of(enPin(), pin("THi", "High Duration (ms)", DataType.INT, "500"), pin("TLo", "Low Duration (ms)", DataType.INT, "500")),
                List.of(pin("Q", "Pulse Output", DataType.BOOL, "0")),
                3, 3, false, false, 0.30
        ));
        reg(new BlockDefinition(
                "Main.Timers.PulseLen", "Main/Timers", "PulseLen", "PlsLen",
                List.of(enPin(), pin("In", "Trigger Input", DataType.BOOL, "0"), pin("Len", "Pulse Length (ms)", DataType.INT, "1000")),
                List.of(pin("Q", "Stretched Pulse", DataType.BOOL, "0")),
                3, 3, false, false, 0.30
        ));
        reg(new BlockDefinition(
                "Main.Timers.SinglePulse", "Main/Timers", "SinglePulse", "OneShot",
                List.of(enPin(), pin("In", "Edge Trigger Input", DataType.BOOL, "0")),
                List.of(pin("Q", "Single-Cycle Pulse Output", DataType.BOOL, "0")),
                3, 2, false, false, 0.14
        ));
        reg(new BlockDefinition(
                "Main.Timers.Timer", "Main/Timers", "Timer", "Timer",
                List.of(enPin(), pin("In", "Timer Start Input", DataType.BOOL, "0"), pin("PT", "Preset Time (ms)", DataType.INT, "1000")),
                List.of(pin("Q", "Timer Output", DataType.BOOL, "0"), pin("ET", "Elapsed Time (ms)", DataType.INT, "0")),
                3, 3, false, false, 0.35
        ));
        reg(new BlockDefinition(
                "Main.Timers.Counter", "Main/Timers", "Counter", "Cntr",
                List.of(enPin(), pin("CU", "Count Up Edge", DataType.BOOL, "0"), pin("CD", "Count Down Edge", DataType.BOOL, "0"), pin("R", "Reset", DataType.BOOL, "0"), pin("PV", "Preset Value", DataType.INT, "10")),
                List.of(pin("Q", "Preset Reached", DataType.BOOL, "0"), pin("CV", "Current Count Value", DataType.INT, "0")),
                3, 5, false, false, 0.30
        ));

        // 6. Macro Boundary Blocks
        reg(new BlockDefinition(
                "Macro.MacroIn", "Macro", "MacroIn", "MacroIn",
                List.of(),
                List.of(pin("X1", "Macro Input 1", DataType.INT, "0")),
                3, 2, false, false, 0.02
        ));
        reg(new BlockDefinition(
                "Macro.MacroOut", "Macro", "MacroOut", "MacroOut",
                List.of(pin("Y1", "Macro Output 1", DataType.INT, "0")),
                List.of(),
                3, 2, false, false, 0.02
        ));

        // 7. Visual
        reg(new BlockDefinition(
                "Visual.Segment", "Visual", "Segment", "Segment ",
                List.of(
                        pin("A", "Segment A (Top)", DataType.BOOL, "0"),
                        pin("B", "Segment B (Top-Right)", DataType.BOOL, "0"),
                        pin("C", "Segment C (Bottom-Right)", DataType.BOOL, "0"),
                        pin("D", "Segment D (Bottom)", DataType.BOOL, "0"),
                        pin("E", "Segment E (Bottom-Left)", DataType.BOOL, "0"),
                        pin("F", "Segment F (Top-Left)", DataType.BOOL, "0"),
                        pin("G", "Segment G (Middle)", DataType.BOOL, "0"),
                        pin("DP", "Decimal Point", DataType.BOOL, "0")
                ),
                List.of(),
                5, 8, false, false, 0.10
        ));
        reg(new BlockDefinition(
                "Visual.Value", "Visual", "Value", "Value",
                List.of(pin("In", "Monitored Value", DataType.INT, "0")),
                List.of(),
                5, 2, false, true, 0.05
        ));
        // Visual -> Vars (0 input, 1 output runtime injectable variables)
        reg(new BlockDefinition(
                "Visual.Vars.BoolVar", "Visual/Vars", "BoolVar", "BoolVar",
                List.of(),
                List.of(pin("Y", "Injected Boolean Value", DataType.BOOL, "0")),
                4, 2, false, false, 0.05
        ));
        reg(new BlockDefinition(
                "Visual.Vars.IntVar", "Visual/Vars", "IntVar", "IntVar",
                List.of(),
                List.of(pin("Y", "Injected 32-bit Integer Value", DataType.INT, "0")),
                4, 2, false, false, 0.05
        ));
        reg(new BlockDefinition(
                "Visual.Vars.LongVar", "Visual/Vars", "LongVar", "LongVar",
                List.of(),
                List.of(pin("Y", "Injected 64-bit Long Value", DataType.LONG, "0")),
                4, 2, false, false, 0.05
        ));
        // 8. Ladder (LD) Contacts & Coils
        reg(new BlockDefinition(
                "Ladder.Contact.NO", "Ladder (LD)/Contacts", "NO Contact (--| |--)", "NO",
                List.of(enPin()), List.of(pin("Q", "Contact Output", DataType.BOOL, "0")),
                3, 2, false, false, 0.06
        ));
        reg(new BlockDefinition(
                "Ladder.Contact.NC", "Ladder (LD)/Contacts", "NC Contact (--|/|--)", "NC",
                List.of(enPin()), List.of(pin("Q", "Contact Output", DataType.BOOL, "0")),
                3, 2, false, false, 0.06
        ));
        reg(new BlockDefinition(
                "Ladder.Contact.POS", "Ladder (LD)/Contacts", "Rising Contact (--|P|--)", "POS",
                List.of(enPin()), List.of(pin("Q", "Contact Output", DataType.BOOL, "0")),
                3, 2, false, false, 0.06
        ));
        reg(new BlockDefinition(
                "Ladder.Contact.NEG", "Ladder (LD)/Contacts", "Falling Contact (--|N|--)", "NEG",
                List.of(enPin()), List.of(pin("Q", "Contact Output", DataType.BOOL, "0")),
                3, 2, false, false, 0.06
        ));
        reg(new BlockDefinition(
                "Ladder.Coil.NORMAL", "Ladder (LD)/Coils", "Normal Coil (--( )--)", "Coil",
                List.of(enPin()), List.of(),
                3, 2, false, false, 0.06
        ));
        reg(new BlockDefinition(
                "Ladder.Coil.NEGATED", "Ladder (LD)/Coils", "Negated Coil (--(/)--)", "NCoil",
                List.of(enPin()), List.of(),
                3, 2, false, false, 0.06
        ));
        reg(new BlockDefinition(
                "Ladder.Coil.SET", "Ladder (LD)/Coils", "Set Coil (--(S)--)", "SCoil",
                List.of(enPin()), List.of(),
                3, 2, false, false, 0.06
        ));
        reg(new BlockDefinition(
                "Ladder.Coil.RESET", "Ladder (LD)/Coils", "Reset Coil (--(R)--)", "RCoil",
                List.of(enPin()), List.of(),
                3, 2, false, false, 0.06
        ));
        reg(new BlockDefinition(
                "Ladder.Coil.POS", "Ladder (LD)/Coils", "Rising Coil (--(P)--)", "PCoil",
                List.of(enPin()), List.of(),
                3, 2, false, false, 0.06
        ));
        reg(new BlockDefinition(
                "Ladder.Coil.NEG", "Ladder (LD)/Coils", "Falling Coil (--(N)--)", "FCoil",
                List.of(enPin()), List.of(),
                3, 2, false, false, 0.06
        ));
        reg(new BlockDefinition(
                "Ladder.Coil.WRITE_REG", "Ladder (LD)/Registers & Analog", "Write Register (--[REG :=]--)", "RegCoil",
                List.of(enPin(), pin("In", "Register Value", DataType.INT, "1")), List.of(),
                4, 2, false, true, 0.12
        ));
        reg(new BlockDefinition(
                "Ladder.Coil.ANALOG", "Ladder (LD)/Registers & Analog", "Analog Output / Move (--[AO :=]--)", "AOCoil",
                List.of(enPin(), pin("In", "Analog Source", DataType.INT, "AI0")), List.of(),
                4, 2, false, true, 0.12
        ));
    }

    public static Map<String, BlockDefinition> getAllDefinitions() {
        return Collections.unmodifiableMap(DEFINITIONS);
    }

    public static BlockDefinition getById(String id) {
        return DEFINITIONS.get(id);
    }

    public static void registerMacroDefinition(Page macroPage) {
        if (macroPage == null || !macroPage.isMacro()) return;
        String cleanName = macroPage.getName().startsWith("*")
                ? macroPage.getName().substring(1).trim()
                : macroPage.getName().trim();
        if (cleanName.isEmpty()) cleanName = "Macro";

        List<PinSpec> ins = new ArrayList<>();
        List<PinSpec> outs = new ArrayList<>();
        ins.add(enPin());
        if (macroPage.getLanguage() == IecLanguage.ST) {
            for (StVariable v : macroPage.getStVariables()) {
                if (v.getScope() == StVariable.Scope.VAR_INPUT) {
                    ins.add(pin(v.getName(), v.getDescription().isEmpty() ? v.getName() : v.getDescription(), v.getDataType(), v.getInitialValue()));
                } else if (v.getScope() == StVariable.Scope.VAR_OUTPUT) {
                    outs.add(pin(v.getName(), v.getDescription().isEmpty() ? v.getName() : v.getDescription(), v.getDataType(), v.getInitialValue()));
                }
            }
        } else {
            for (BlockInstance b : macroPage.getBlocks()) {
                if ("Macro.MacroIn".equals(b.getDefinitionId())) {
                    for (PinInstance outPin : b.getOutputs()) {
                        ins.add(pin(outPin.getShortName(), outPin.getFullName(), outPin.getDataType(), outPin.getConstantValue()));
                    }
                } else if ("Macro.MacroOut".equals(b.getDefinitionId())) {
                    for (PinInstance inPin : b.getInputs()) {
                        outs.add(pin(inPin.getShortName(), inPin.getFullName(), inPin.getDataType(), inPin.getConstantValue()));
                    }
                }
            }
        }
        String id = "Macro.User." + cleanName;
        DEFINITIONS.put(id, new BlockDefinition(
                id, "Macro", cleanName, cleanName,
                ins, outs, 4, Math.max(2, Math.max(ins.size(), outs.size())),
                false, false, 0.50
        ));
    }

    public static void syncMacrosFromProject(Project project) {
        DEFINITIONS.keySet().removeIf(k -> k.startsWith("Macro.User."));
        if (project == null) return;
        for (Page p : project.getPages()) {
            if (p.isMacro()) {
                registerMacroDefinition(p);
            }
        }
        project.syncMacroInstances();
    }
}
