package cz.maramat.model;

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
    private String accessPassword = "admin";

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
}
