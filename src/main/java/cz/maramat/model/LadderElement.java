package cz.maramat.model;

import java.util.UUID;

/**
 * Represents a single element placed on an IEC 61131-3 Ladder Diagram rung:
 * - A Contact (--| |--, --|/|--, --|P|--, --|N|--)
 * - A Boolean Coil (--( )--, --(/)--, --(S)--, --(R)--, --(P)--, --(N)--)
 * - An Analog Output / Move Coil (--[AO :=]--, transfers a numeric/analog source operand to an AO/PWM/Register)
 * - A Function Block instance with EN/ENO power flow
 */
public class LadderElement {

    public enum Kind {
        NO_CONTACT("NO Contact", "--| |--", true, false),
        NC_CONTACT("NC Contact", "--|/|--", true, false),
        POS_EDGE_CONTACT("Rising Edge Contact", "--|P|--", true, false),
        NEG_EDGE_CONTACT("Falling Edge Contact", "--|N|--", true, false),

        NORMAL_COIL("Normal Coil", "--( )--", false, true),
        NEGATED_COIL("Negated Coil", "--(/)--", false, true),
        SET_COIL("Set (Latch) Coil", "--(S)--", false, true),
        RESET_COIL("Reset (Unlatch) Coil", "--(R)--", false, true),
        POS_EDGE_COIL("Rising Edge Coil", "--(P)--", false, true),
        NEG_EDGE_COIL("Falling Edge Coil", "--(N)--", false, true),
        ANALOG_COIL("Analog Output / Move", "--[AO :=]--", false, true),

        FUNCTION_BLOCK("Function Block", "[FB]", false, false);

        private final String displayName;
        private final String symbolText;
        private final boolean contact;
        private final boolean coil;

        Kind(String displayName, String symbolText, boolean contact, boolean coil) {
            this.displayName = displayName;
            this.symbolText = symbolText;
            this.contact = contact;
            this.coil = coil;
        }

        public String getDisplayName() {
            return displayName;
        }

        public String getSymbolText() {
            return symbolText;
        }

        public boolean isContact() {
            return contact;
        }

        public boolean isCoil() {
            return coil;
        }

        public boolean isFunctionBlock() {
            return this == FUNCTION_BLOCK;
        }
    }

    private String id;
    private Kind kind;
    private String operand;
    private String sourceOperand = "AI0";
    private String comment;
    private BlockInstance blockInstance;

    // Transient runtime simulation state
    private transient boolean energizedIn = false;
    private transient boolean energizedOut = false;
    private transient boolean operandState = false;
    private transient double numericRuntimeValue = 0.0;
    private transient boolean prevEdgeInput = false;

    public LadderElement(Kind kind, String operand) {
        this.id = UUID.randomUUID().toString();
        this.kind = kind;
        this.operand = (operand == null || operand.isBlank())
                ? (kind == Kind.ANALOG_COIL ? "AO0" : (kind.isCoil() ? "DO0" : "DI0"))
                : operand.trim();
        this.sourceOperand = "AI0";
        this.comment = "";
    }

    public LadderElement(Kind kind, String sourceOperand, String targetOperand) {
        this.id = UUID.randomUUID().toString();
        this.kind = kind;
        this.sourceOperand = (sourceOperand == null || sourceOperand.isBlank()) ? "AI0" : sourceOperand.trim();
        this.operand = (targetOperand == null || targetOperand.isBlank()) ? "AO0" : targetOperand.trim();
        this.comment = "";
    }

    public LadderElement(BlockInstance blockInstance) {
        this.id = UUID.randomUUID().toString();
        this.kind = Kind.FUNCTION_BLOCK;
        this.blockInstance = blockInstance;
        this.operand = blockInstance != null ? blockInstance.getName() : "FB1";
        this.comment = "";
    }

    public LadderElement copy() {
        if (kind == Kind.FUNCTION_BLOCK && blockInstance != null) {
            LadderElement copy = new LadderElement(blockInstance.copy());
            copy.id = this.id;
            copy.operand = this.operand;
            copy.sourceOperand = this.sourceOperand;
            copy.comment = this.comment;
            return copy;
        }
        LadderElement copy = new LadderElement(this.kind, this.sourceOperand, this.operand);
        copy.id = this.id;
        copy.comment = this.comment;
        return copy;
    }

    public void resetRuntimeState() {
        energizedIn = false;
        energizedOut = false;
        operandState = false;
        numericRuntimeValue = 0.0;
        prevEdgeInput = false;
        if (blockInstance != null) {
            blockInstance.resetRuntimeState();
        }
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public Kind getKind() {
        return kind;
    }

    public void setKind(Kind kind) {
        this.kind = kind;
    }

    public String getOperand() {
        return operand;
    }

    public void setOperand(String operand) {
        this.operand = (operand == null || operand.isBlank()) ? "M0" : operand.trim();
    }

    public String getSourceOperand() {
        return sourceOperand == null ? "AI0" : sourceOperand;
    }

    public void setSourceOperand(String sourceOperand) {
        this.sourceOperand = (sourceOperand == null || sourceOperand.isBlank()) ? "0" : sourceOperand.trim();
    }

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment == null ? "" : comment;
    }

    public BlockInstance getBlockInstance() {
        return blockInstance;
    }

    public void setBlockInstance(BlockInstance blockInstance) {
        this.blockInstance = blockInstance;
    }

    public boolean isEnergizedIn() {
        return energizedIn;
    }

    public void setEnergizedIn(boolean energizedIn) {
        this.energizedIn = energizedIn;
    }

    public boolean isEnergizedOut() {
        return energizedOut;
    }

    public void setEnergizedOut(boolean energizedOut) {
        this.energizedOut = energizedOut;
    }

    public boolean isOperandState() {
        return operandState;
    }

    public void setOperandState(boolean operandState) {
        this.operandState = operandState;
    }

    public double getNumericRuntimeValue() {
        return numericRuntimeValue;
    }

    public void setNumericRuntimeValue(double numericRuntimeValue) {
        this.numericRuntimeValue = numericRuntimeValue;
    }

    public boolean isPrevEdgeInput() {
        return prevEdgeInput;
    }

    public void setPrevEdgeInput(boolean prevEdgeInput) {
        this.prevEdgeInput = prevEdgeInput;
    }
}
