package cz.maramat.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Hardware, network, status-output, boot, and security configuration for a PLC unit.
 */
public class UnitConfig {

    public enum IpMode {
        DHCP("DHCP"),
        STATIC("Static IP");

        private final String label;

        IpMode(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public enum BootMode {
        AUTO_RUN("Auto-Run saved program on boot"),
        BOOT_TO_STOP("Boot to STOP");

        private final String label;

        BootMode(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public enum Rs485Mode {
        MARAPROT("MaraProt v1 (Programming / Watch)"),
        MODBUS_MASTER("Modbus RTU Master (MbRtuRead / Write)"),
        MODBUS_SLAVE("Modbus RTU Slave");

        private final String label;

        Rs485Mode(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public static final int[] RS485_BAUD_RATES = {
            9600, 19200, 38400, 57600, 115200, 230400, 460800, 921600
    };

    public enum ModbusTable {
        COIL(0, "MCO", "MCO - Modbus Coil"),
        DISCRETE_INPUT(1, "MDI", "MDI - Modbus Discrete Input"),
        HOLDING_REGISTER(2, "MHR", "MHR - Modbus Holding Register"),
        INPUT_REGISTER(3, "MIR", "MIR - Modbus Input Register");

        private final int wireCode;
        private final String prefix;
        private final String label;

        ModbusTable(int wireCode, String prefix, String label) {
            this.wireCode = wireCode;
            this.prefix = prefix;
            this.label = label;
        }

        public int getWireCode() { return wireCode; }
        public String getPrefix() { return prefix; }
        public String formatAddress(int addr) { return prefix + addr; }

        public static ModbusTable fromWireCode(int code) {
            for (ModbusTable t : values()) {
                if (t.wireCode == code) return t;
            }
            return HOLDING_REGISTER;
        }

        @Override
        public String toString() { return label; }
    }

    public static final class ModbusSlaveEntry {
        private ModbusTable table;
        private int modbusAddr;
        private String targetRef;
        private boolean readOnly;

        public ModbusSlaveEntry(ModbusTable table, int modbusAddr, String targetRef, boolean readOnly) {
            this.table = table == null ? ModbusTable.HOLDING_REGISTER : table;
            this.modbusAddr = Math.clamp(modbusAddr, 0, 65535);
            this.targetRef = (targetRef == null || targetRef.isBlank()) ? "DI0" : targetRef.trim();
            this.readOnly = readOnly;
        }

        public ModbusSlaveEntry copy() {
            return new ModbusSlaveEntry(table, modbusAddr, targetRef, readOnly);
        }

        public ModbusTable getTable() { return table; }
        public void setTable(ModbusTable table) { this.table = table == null ? ModbusTable.HOLDING_REGISTER : table; }

        public int getModbusAddr() { return modbusAddr; }
        public void setModbusAddr(int modbusAddr) { this.modbusAddr = Math.clamp(modbusAddr, 0, 65535); }

        public String getTargetRef() { return targetRef; }
        public void setTargetRef(String targetRef) {
            this.targetRef = (targetRef == null || targetRef.isBlank()) ? "DI0" : targetRef.trim();
        }

        public boolean isReadOnly() { return readOnly; }
        public void setReadOnly(boolean readOnly) { this.readOnly = readOnly; }
    }

    private String unitName = "PLC-1";
    private int stationId = 1;

    private IpMode ipMode = IpMode.DHCP;
    private String staticIp = "192.168.1.50";
    private String subnetMask = "255.255.255.0";
    private String gateway = "192.168.1.1";
    private int tcpPort = 5020;

    private String runOutputPin = "None";
    private String faultOutputPin = "None";
    private String estopOutputPin = "None";

    private BootMode bootMode = BootMode.AUTO_RUN;
    private boolean safeOutputsOnStop = true;
    private boolean watchdogEnabled = true;
    private Rs485Mode rs485Mode = Rs485Mode.MARAPROT;
    private int rs485BaudRate = 115200;
    private String accessPassword = "admin";
    private final List<ModbusSlaveEntry> modbusSlaveMap = new ArrayList<>();

    public String getUnitName() { return unitName; }
    public void setUnitName(String unitName) {
        this.unitName = (unitName == null || unitName.isBlank()) ? "PLC-1" : unitName.trim();
    }

    public int getStationId() { return stationId; }
    public void setStationId(int stationId) { this.stationId = Math.clamp(stationId, 1, 247); }

    public IpMode getIpMode() { return ipMode; }
    public void setIpMode(IpMode ipMode) { this.ipMode = ipMode == null ? IpMode.DHCP : ipMode; }

    public String getStaticIp() { return staticIp; }
    public void setStaticIp(String staticIp) {
        this.staticIp = (staticIp == null || staticIp.isBlank()) ? "192.168.1.50" : staticIp.trim();
    }

    public String getSubnetMask() { return subnetMask; }
    public void setSubnetMask(String subnetMask) {
        this.subnetMask = (subnetMask == null || subnetMask.isBlank()) ? "255.255.255.0" : subnetMask.trim();
    }

    public String getGateway() { return gateway; }
    public void setGateway(String gateway) {
        this.gateway = (gateway == null || gateway.isBlank()) ? "192.168.1.1" : gateway.trim();
    }

    public int getTcpPort() { return tcpPort; }
    public void setTcpPort(int tcpPort) {
        if (tcpPort >= 1 && tcpPort <= 65535) this.tcpPort = tcpPort;
    }

    public String getRunOutputPin() { return runOutputPin; }
    public void setRunOutputPin(String runOutputPin) {
        this.runOutputPin = (runOutputPin == null || runOutputPin.isBlank()) ? "None" : runOutputPin.trim();
    }

    public String getFaultOutputPin() { return faultOutputPin; }
    public void setFaultOutputPin(String faultOutputPin) {
        this.faultOutputPin = (faultOutputPin == null || faultOutputPin.isBlank()) ? "None" : faultOutputPin.trim();
    }

    public String getEstopOutputPin() { return estopOutputPin; }
    public void setEstopOutputPin(String estopOutputPin) {
        this.estopOutputPin = (estopOutputPin == null || estopOutputPin.isBlank()) ? "None" : estopOutputPin.trim();
    }

    public BootMode getBootMode() { return bootMode; }
    public void setBootMode(BootMode bootMode) { this.bootMode = bootMode == null ? BootMode.AUTO_RUN : bootMode; }

    public boolean isSafeOutputsOnStop() { return safeOutputsOnStop; }
    public void setSafeOutputsOnStop(boolean safeOutputsOnStop) { this.safeOutputsOnStop = safeOutputsOnStop; }

    public boolean isWatchdogEnabled() { return watchdogEnabled; }
    public void setWatchdogEnabled(boolean watchdogEnabled) { this.watchdogEnabled = watchdogEnabled; }

    public String getAccessPassword() { return accessPassword; }
    public void setAccessPassword(String accessPassword) {
        this.accessPassword = accessPassword == null ? "" : accessPassword;
    }

    public Rs485Mode getRs485Mode() { return rs485Mode; }
    public void setRs485Mode(Rs485Mode rs485Mode) {
        this.rs485Mode = rs485Mode == null ? Rs485Mode.MARAPROT : rs485Mode;
    }

    public int getRs485BaudRate() { return rs485BaudRate; }
    public void setRs485BaudRate(int rs485BaudRate) {
        for (int b : RS485_BAUD_RATES) {
            if (b == rs485BaudRate) {
                this.rs485BaudRate = rs485BaudRate;
                return;
            }
        }
        this.rs485BaudRate = 115200;
    }

    public int getRs485BaudIndex() {
        for (int i = 0; i < RS485_BAUD_RATES.length; i++) {
            if (RS485_BAUD_RATES[i] == rs485BaudRate) return i;
        }
        return 4;
    }

    public void setRs485BaudIndex(int idx) {
        if (idx >= 0 && idx < RS485_BAUD_RATES.length) {
            this.rs485BaudRate = RS485_BAUD_RATES[idx];
        } else {
            this.rs485BaudRate = 115200;
        }
    }

    public List<ModbusSlaveEntry> getModbusSlaveMap() {
        return modbusSlaveMap;
    }
}
