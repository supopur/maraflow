package cz.maramat.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Safety PLC / Emergency Stop (E-Stop) hardware input mapping and reaction mode configuration.
 */
public class EStopConfig {

    public enum Mode {
        DISABLE_OUTPUTS_AND_STOP("1. Disable All Outputs & Put PLC into STOP"),
        DEDICATED_ESTOP_PAGE("2. Execute Dedicated E-Stop Page (Halt Normal Threads Immediately)");

        private final String label;

        Mode(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }

        public String getDisplayName() {
            return label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public static class InputRule {
        private boolean enabled;
        private String inputChannel; // e.g., "DI0", "DI1"
        private boolean activeLow;   // true = Active Low (NC, trips on 0), false = Active High (NO, trips on 1)
        private String description;

        public InputRule(boolean enabled, String inputChannel, boolean activeLow, String description) {
            this.enabled = enabled;
            this.inputChannel = inputChannel == null ? "DI0" : inputChannel.trim();
            this.activeLow = activeLow;
            this.description = description == null ? "" : description.trim();
        }

        public InputRule(String inputChannel, boolean activeLow, boolean enabled, String description) {
            this(enabled, inputChannel, activeLow, description);
        }

        public InputRule copy() {
            return new InputRule(enabled, inputChannel, activeLow, description);
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getInputChannel() {
            return inputChannel;
        }

        public void setInputChannel(String inputChannel) {
            this.inputChannel = inputChannel == null ? "DI0" : inputChannel.trim();
        }

        public boolean isActiveLow() {
            return activeLow;
        }

        public void setActiveLow(boolean activeLow) {
            this.activeLow = activeLow;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description == null ? "" : description.trim();
        }
    }

    private boolean enabled = false;
    private Mode mode = Mode.DISABLE_OUTPUTS_AND_STOP;
    private boolean manualResetRequired = true;
    private String dedicatedEStopPageName = "!EStop";
    private final List<InputRule> inputRules = new ArrayList<>();

    public EStopConfig() {
        inputRules.add(new InputRule(false, "DI7", true, "Main Panel Mushroom E-Stop (NC Active Low)"));
        inputRules.add(new InputRule(false, "DI6", false, "Safety Light Curtain Trip (NO Active High)"));
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Mode getMode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode == null ? Mode.DISABLE_OUTPUTS_AND_STOP : mode;
    }

    public boolean isManualResetRequired() {
        return manualResetRequired;
    }

    public void setManualResetRequired(boolean manualResetRequired) {
        this.manualResetRequired = manualResetRequired;
    }

    public boolean isRequireManualReset() {
        return manualResetRequired;
    }

    public void setRequireManualReset(boolean requireManualReset) {
        this.manualResetRequired = requireManualReset;
    }

    public String getDedicatedEStopPageName() {
        return dedicatedEStopPageName;
    }

    public void setDedicatedEStopPageName(String dedicatedEStopPageName) {
        this.dedicatedEStopPageName = (dedicatedEStopPageName == null || dedicatedEStopPageName.isBlank())
                ? "!EStop"
                : dedicatedEStopPageName.trim();
    }

    public List<InputRule> getInputRules() {
        return inputRules;
    }
}
