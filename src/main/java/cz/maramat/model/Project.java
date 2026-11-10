package cz.maramat.model;

import java.awt.Point;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class Project {
    public static final String VERSION = "1.0.0";

    private String projectName = "test";
    private String author = "";
    private String description = "";
    private File projectFile = null;
    private boolean dirty = true;
    private String targetPlatform = "STM32F407-Maraflow-Std";
    private int defaultThreadId = 2;
    private IecLanguage primaryLanguage = IecLanguage.FBD;
    private final EStopConfig eStopConfig = new EStopConfig();
    private final List<Page> pages = new ArrayList<>();
    private final Map<String, DataType> symbolicTags = new LinkedHashMap<>();
    private final Map<String, String> symbolicTagDescriptions = new LinkedHashMap<>();

    public Project() {
        pages.add(new Page("Page 1", IecLanguage.FBD));
        initDefaultSymbolicTags();
    }

    public Project(String projectName, String targetPlatform, IecLanguage initialLanguage, int defaultThreadId) {
        this.projectName = (projectName == null || projectName.isBlank()) ? "Untitled" : projectName.trim();
        this.targetPlatform = (targetPlatform == null || targetPlatform.isBlank()) ? "STM32F407-Maraflow-Std" : targetPlatform;
        this.primaryLanguage = (initialLanguage == null) ? IecLanguage.FBD : initialLanguage;
        this.defaultThreadId = Math.clamp(defaultThreadId, 1, 5);
        initDefaultSymbolicTags();

        Page p1 = new Page("Page 1", this.primaryLanguage);
        if (this.primaryLanguage == IecLanguage.LD) {
            LadderRung r1 = new LadderRung(this.defaultThreadId, 10, "Main Rung 1");
            r1.getMainElements().add(new LadderElement(LadderElement.Kind.NO_CONTACT, "DI0"));
            r1.getCoils().add(new LadderElement(LadderElement.Kind.NORMAL_COIL, "DO0"));
            p1.getRungs().add(r1);
        } else if (this.primaryLanguage == IecLanguage.ST) {
            p1.setStThreadId(this.defaultThreadId);
            p1.setStOrder(10);
            p1.getStVariables().add(new StVariable(StVariable.Scope.VAR, "Counter", DataType.INT, "0", "Cycle scan counter"));
            p1.getStVariables().add(new StVariable(StVariable.Scope.VAR, "DistanceCm", DataType.FLOAT, "0", "Calculated distance (cm)"));
            p1.setStCode("""
                    VAR
                        Counter : INT := 0; // Cycle scan counter
                        DistanceCm : FLOAT := 0; // Calculated distance (cm)
                    END_VAR

                    // IEC 61131-3 Structured Text (ST) Page
                    Counter := Counter + 1;
                    IF DI0 THEN
                        DO0 := TRUE;
                    ELSE
                        DO0 := FALSE;
                    END_IF;
                    """);
        }
        pages.add(p1);
    }

    private void initDefaultSymbolicTags() {
        symbolicTags.put("MotorRun", DataType.BOOL);
        symbolicTagDescriptions.put("MotorRun", "Main drive contactor latch state");
        symbolicTags.put("SafetyOk", DataType.BOOL);
        symbolicTagDescriptions.put("SafetyOk", "E-Stop and guard interlock healthy");
        symbolicTags.put("HighTempAlarm", DataType.BOOL);
        symbolicTagDescriptions.put("HighTempAlarm", "Over-temperature alarm coil");
        symbolicTags.put("SetpointTemp", DataType.INT);
        symbolicTagDescriptions.put("SetpointTemp", "Process temperature setpoint (deg C x10)");
    }

    public static Project createDefaultSampleProject() {
        Project p = new Project();
        p.setProjectName("test_fbd");
        p.setPrimaryLanguage(IecLanguage.FBD);
        p.setDirty(true);
        Page page1 = p.getPages().getFirst();

        BlockDefinition di8Def = BlockCatalog.getById("IO.8xDI");
        BlockDefinition andDef = BlockCatalog.getById("Bool.And");
        BlockDefinition valDef = BlockCatalog.getById("Visual.Value");
        BlockDefinition segDef = BlockCatalog.getById("Visual.Segment");

        BlockInstance bDi = new BlockInstance(di8Def, "8xDI1", 25, 30, 2, 20);
        BlockInstance bAnd1 = new BlockInstance(andDef, "And1", 39, 30, 2, 30);
        BlockInstance bVal1 = new BlockInstance(valDef, "Value1", 58, 31, 2, 40);
        BlockInstance bAnd2 = new BlockInstance(andDef, "And2", 51, 17, 2, 10);
        BlockInstance bSeg1 = new BlockInstance(segDef, "Segment 1", 75, 11, 2, 50);

        page1.getBlocks().add(bDi);
        page1.getBlocks().add(bAnd1);
        page1.getBlocks().add(bVal1);
        page1.getBlocks().add(bAnd2);
        page1.getBlocks().add(bSeg1);

        Point s1 = bDi.getOutputPinAnchorGrid(0);
        Point e1 = bAnd1.getInputPinAnchorGrid(1);
        page1.getWires().add(new Wire(bDi.getId(), 0, bAnd1.getId(), 1, "8xDI1.DI0", s1, e1));

        Point s2 = bDi.getOutputPinAnchorGrid(1);
        Point e2 = bAnd1.getInputPinAnchorGrid(2);
        page1.getWires().add(new Wire(bDi.getId(), 1, bAnd1.getId(), 2, "8xDI1.DI1", s2, e2));

        Point s3 = bAnd1.getOutputPinAnchorGrid(0);
        Point e3 = bVal1.getInputPinAnchorGrid(0);
        page1.getWires().add(new Wire(bAnd1.getId(), 0, bVal1.getId(), 0, "And1.Y", s3, e3));

        return p;
    }

    public static Project createDefaultLadderSampleProject() {
        Project p = new Project("motor_control_ld", "STM32F407-Maraflow-Std", IecLanguage.LD, 2);
        p.setDirty(true);
        Page page1 = p.getPages().getFirst();
        page1.setName("Main_LD");
        page1.getRungs().clear();

        LadderRung r1 = new LadderRung(2, 10, "Motor Start/Stop Seal-In Latch (DI0=Start, DI1=Stop NC, M0=Seal)");
        r1.getMainElements().add(new LadderElement(LadderElement.Kind.NO_CONTACT, "DI0"));
        r1.getMainElements().add(new LadderElement(LadderElement.Kind.NC_CONTACT, "DI1"));

        LadderBranch sealBranch = new LadderBranch(0, 0);
        sealBranch.getElements().add(new LadderElement(LadderElement.Kind.NO_CONTACT, "M0"));
        r1.getParallelBranches().add(sealBranch);

        r1.getCoils().add(new LadderElement(LadderElement.Kind.NORMAL_COIL, "M0"));
        r1.getCoils().add(new LadderElement(LadderElement.Kind.NORMAL_COIL, "DO0"));
        page1.getRungs().add(r1);

        LadderRung r2 = new LadderRung(2, 20, "Cooling Fan Delay Timer (Starts 2000ms after MotorRun M0)");
        r2.getMainElements().add(new LadderElement(LadderElement.Kind.NO_CONTACT, "M0"));

        BlockDefinition tonDef = BlockCatalog.getById("Main.Timers.Timer");
        if (tonDef != null) {
            BlockInstance tonBlock = new BlockInstance(tonDef, "TON_Fan1", 0, 0, 2, 25);
            tonBlock.getInputs().get(1).setConstantValue("M0");
            tonBlock.getInputs().get(2).setConstantValue("2000");
            r2.getMainElements().add(new LadderElement(tonBlock));
        }
        r2.getCoils().add(new LadderElement(LadderElement.Kind.NORMAL_COIL, "DO1"));
        page1.getRungs().add(r2);

        LadderRung r3 = new LadderRung(2, 30, "Over-Temperature Fault Latch (AI0 > 500 sets HighTempAlarm / DO2, DI2 resets)");
        BlockDefinition gtDef = BlockCatalog.getById("Main.Comparators.GT");
        if (gtDef != null) {
            BlockInstance gtBlock = new BlockInstance(gtDef, "GT_Temp1", 0, 0, 2, 35);
            gtBlock.getInputs().get(1).setConstantValue("AI0");
            gtBlock.getInputs().get(2).setConstantValue("500");
            r3.getMainElements().add(new LadderElement(gtBlock));
        }
        r3.getCoils().add(new LadderElement(LadderElement.Kind.SET_COIL, "DO2"));
        page1.getRungs().add(r3);

        LadderRung r4 = new LadderRung(2, 40, "Fault Reset Button (DI2 Rising Edge unlatches DO2)");
        r4.getMainElements().add(new LadderElement(LadderElement.Kind.POS_EDGE_CONTACT, "DI2"));
        r4.getCoils().add(new LadderElement(LadderElement.Kind.RESET_COIL, "DO2"));
        page1.getRungs().add(r4);

        // Also add a dedicated !EStop page in Ladder so Mode 2 E-Stop can be tested right away
        Page estopPage = new Page("!EStop", IecLanguage.LD);
        estopPage.setEStopPage(true);
        LadderRung er1 = new LadderRung(4, 10, "Dedicated E-Stop Handler: Disable Drive DO0, Turn On Fault Beacon DO7");
        er1.getMainElements().add(new LadderElement(LadderElement.Kind.NO_CONTACT, "TRUE"));
        er1.getCoils().add(new LadderElement(LadderElement.Kind.RESET_COIL, "DO0"));
        er1.getCoils().add(new LadderElement(LadderElement.Kind.RESET_COIL, "M0"));
        er1.getCoils().add(new LadderElement(LadderElement.Kind.NORMAL_COIL, "DO7"));
        estopPage.getRungs().add(er1);
        p.getPages().add(estopPage);

        return p;
    }

    public static Project createDefaultStSampleProject() {
        Project p = new Project("ultrasonic_st_control", "STM32F407-Maraflow-Std", IecLanguage.ST, 2);
        p.setDirty(true);
        Page page1 = p.getPages().getFirst();
        page1.setName("Main_ST");
        page1.getStVariables().clear();
        page1.getStVariables().add(new StVariable(StVariable.Scope.VAR, "ScanCount", DataType.INT, "0", "Total scan cycles executed"));
        page1.getStVariables().add(new StVariable(StVariable.Scope.VAR, "EchoUs", DataType.INT, "1160", "Captured ultrasonic echo pulse width (us)"));
        page1.getStVariables().add(new StVariable(StVariable.Scope.VAR, "DistanceCm", DataType.FLOAT, "0", "Computed obstacle distance (cm)"));
        page1.getStVariables().add(new StVariable(StVariable.Scope.VAR, "ObstacleClose", DataType.BOOL, "0", "True when obstacle < 25 cm"));
        page1.setStCode("""
                VAR
                    ScanCount : INT := 0; // Total scan cycles executed
                    EchoUs : INT := 1160; // Captured ultrasonic echo pulse width (us)
                    DistanceCm : FLOAT := 0; // Computed obstacle distance (cm)
                    ObstacleClose : BOOL := 0; // True when obstacle < 25 cm
                END_VAR

                // IEC 61131-3 Structured Text (ST) Ultrasonic & Pump Control
                ScanCount := ScanCount + 1;
                
                // Convert ultrasonic echo pulse width (us) to centimeters
                IF EchoUs > 100 AND EchoUs < 25000 THEN
                    DistanceCm := INT_TO_FLOAT(EchoUs) / 58.0;
                ELSE
                    DistanceCm := 999.0;
                END_IF;
                
                IF DistanceCm < 25.0 THEN
                    ObstacleClose := TRUE;
                    DO0 := FALSE;
                    DO1 := TRUE;
                ELSE
                    ObstacleClose := FALSE;
                    DO0 := DI0;
                    DO1 := FALSE;
                END_IF;
                """);

        // Add an ST Macro (*UltrasonicCm) that can be dropped onto FBD or Ladder pages
        Page macroSt = new Page("*UltrasonicCm", IecLanguage.ST);
        macroSt.getStVariables().add(new StVariable(StVariable.Scope.VAR_INPUT, "PulseUs", DataType.INT, "1160", "Echo pulse width in microseconds"));
        macroSt.getStVariables().add(new StVariable(StVariable.Scope.VAR_INPUT, "Valid", DataType.BOOL, "1", "Interrupt Flag valid signal"));
        macroSt.getStVariables().add(new StVariable(StVariable.Scope.VAR_OUTPUT, "DistCm", DataType.FLOAT, "20", "Calculated distance in cm"));
        macroSt.getStVariables().add(new StVariable(StVariable.Scope.VAR_OUTPUT, "InRange", DataType.BOOL, "1", "Sensor reading within valid range"));
        macroSt.setStCode("""
                VAR_INPUT
                    PulseUs : INT := 1160; // Echo pulse width in microseconds
                    Valid : BOOL := 1; // Interrupt Flag valid signal
                END_VAR

                VAR_OUTPUT
                    DistCm : FLOAT := 20; // Calculated distance in cm
                    InRange : BOOL := 1; // Sensor reading within valid range
                END_VAR

                // ST Macro Function Block: Converts HC-SR04 echo pulse (us) to cm
                IF Valid THEN
                    IF PulseUs >= 116 AND PulseUs <= 23200 THEN
                        DistCm := INT_TO_FLOAT(PulseUs) / 58.0;
                        InRange := TRUE;
                    ELSE
                        DistCm := 0.0;
                        InRange := FALSE;
                    END_IF;
                END_IF;
                """);
        p.getPages().add(macroSt);

        // Add a dedicated !EStop page in ST
        Page estopSt = new Page("!EStop", IecLanguage.ST);
        estopSt.setEStopPage(true);
        estopSt.getStVariables().add(new StVariable(StVariable.Scope.VAR, "TripCount", DataType.INT, "0", "Safety scan cycles while E-Stop active"));
        estopSt.setStCode("""
                VAR
                    TripCount : INT := 0; // Safety scan cycles while E-Stop active
                END_VAR

                // Dedicated E-Stop Safety Handler (Executes when Mode 2 E-Stop is active)
                TripCount := TripCount + 1;
                DO0 := FALSE;
                DO1 := FALSE;
                DO7 := TRUE; // Energize E-Stop Beacon Output DO7
                """);
        p.getPages().add(estopSt);

        BlockCatalog.syncMacrosFromProject(p);
        return p;
    }

    public Page findEStopPage() {
        String target = eStopConfig.getDedicatedEStopPageName();
        for (Page p : pages) {
            if (p.getName().equalsIgnoreCase(target) || p.isEStopPage()) {
                return p;
            }
        }
        return null;
    }

    /**
     * Synchronizes all placed Macro BlockInstances across all pages with their latest Macro Page definitions.
     */
    public void syncMacroInstances() {
        for (Page page : pages) {
            for (BlockInstance b : page.getAllBlockInstances()) {
                if (b.getDefinitionId().startsWith("Macro.User.")) {
                    BlockDefinition def = BlockCatalog.getById(b.getDefinitionId());
                    if (def != null) {
                        b.setTypeName(def.getTypeName());
                        b.syncPinsWithDefinition(def);
                        page.getWires().removeIf(w ->
                                (w.getSourceBlockId().equals(b.getId()) && w.getSourcePinIndex() >= b.getOutputs().size())
                                || (w.getTargetBlockId().equals(b.getId()) && w.getTargetPinIndex() >= b.getInputs().size())
                        );
                        page.updateWiresForBlock(b);
                    }
                }
            }
        }
    }

    /**
     * Updates placed Macro instances when a Macro page is renamed.
     */
    public void renameMacroReferences(String oldMacroName, String newMacroName) {
        if (oldMacroName == null || newMacroName == null) return;
        String oldClean = oldMacroName.startsWith("*") ? oldMacroName.substring(1).trim() : oldMacroName.trim();
        String newClean = newMacroName.startsWith("*") ? newMacroName.substring(1).trim() : newMacroName.trim();
        if (oldClean.isEmpty() || newClean.isEmpty() || oldClean.equalsIgnoreCase(newClean)) return;

        String oldDefId = "Macro.User." + oldClean;
        String newDefId = "Macro.User." + newClean;
        for (Page page : pages) {
            for (BlockInstance b : page.getAllBlockInstances()) {
                if (b.getDefinitionId().equalsIgnoreCase(oldDefId)) {
                    b.setDefinitionId(newDefId);
                    b.setTypeName(newClean);
                }
            }
        }
    }

    /**
     * Collects all virtual registers, memory bits, and symbolic tags known across all FBD, LD, and ST pages.
     */
    public List<String> getAllKnownRegisterAndTagNames() {
        Set<String> set = new LinkedHashSet<>();
        for (int i = 0; i < 16; i++) {
            set.add("REG_" + i);
        }
        for (Page page : pages) {
            for (BlockInstance b : page.getAllBlockInstances()) {
                String reg = b.getCustomProperties().get("RegisterName");
                if (reg != null && !reg.isBlank()) {
                    set.add(reg.trim());
                }
            }
            for (LadderRung r : page.getRungs()) {
                for (LadderElement c : r.getCoils()) {
                    if (c.getOperand() != null && !c.getOperand().isBlank()) {
                        set.add(c.getOperand().trim());
                    }
                }
            }
        }
        set.addAll(symbolicTags.keySet());
        for (int i = 0; i < 16; i++) {
            set.add("M" + i);
        }
        return new ArrayList<>(set);
    }

    public String formatWindowTitle() {
        if (projectFile == null) {
            return "Unsaved* (" + projectName + ") - Maraflow V" + VERSION;
        }
        String star = dirty ? "*" : "";
        return projectName + star + " (" + projectFile.getParent() + ") - Maraflow V" + VERSION;
    }

    public String formatEditorPanelTitle() {
        String filePart = (projectFile != null) ? projectFile.getName() : (projectName + ".mfl");
        return "Project - " + filePart + (dirty ? " *" : "");
    }

    public String getProjectName() {
        return projectName;
    }

    public void setProjectName(String projectName) {
        this.projectName = (projectName == null || projectName.isBlank()) ? "Untitled" : projectName.trim();
    }

    public String getAuthor() {
        return author;
    }

    public void setAuthor(String author) {
        this.author = author == null ? "" : author.trim();
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description == null ? "" : description.trim();
    }

    public File getProjectFile() {
        return projectFile;
    }

    public void setProjectFile(File projectFile) {
        this.projectFile = projectFile;
        if (projectFile != null) {
            String n = projectFile.getName();
            int dot = n.lastIndexOf('.');
            this.projectName = (dot > 0) ? n.substring(0, dot) : n;
        }
    }

    public boolean isDirty() {
        return dirty;
    }

    public void setDirty(boolean dirty) {
        this.dirty = dirty;
    }

    public String getTargetPlatform() {
        return targetPlatform;
    }

    public void setTargetPlatform(String targetPlatform) {
        this.targetPlatform = targetPlatform;
    }

    public int getDefaultThreadId() {
        return defaultThreadId;
    }

    public void setDefaultThreadId(int defaultThreadId) {
        this.defaultThreadId = Math.clamp(defaultThreadId, 1, 5);
    }

    public IecLanguage getPrimaryLanguage() {
        return primaryLanguage;
    }

    public void setPrimaryLanguage(IecLanguage primaryLanguage) {
        this.primaryLanguage = (primaryLanguage == null) ? IecLanguage.FBD : primaryLanguage;
    }

    public EStopConfig getEStopConfig() {
        return eStopConfig;
    }

    public List<Page> getPages() {
        return pages;
    }

    public Map<String, DataType> getSymbolicTags() {
        return symbolicTags;
    }

    public Map<String, String> getSymbolicTagDescriptions() {
        return symbolicTagDescriptions;
    }

    public void putSymbolicTag(String name, DataType type, String description) {
        if (name == null || name.isBlank()) return;
        String clean = name.trim();
        symbolicTags.put(clean, type == null ? DataType.BOOL : type);
        symbolicTagDescriptions.put(clean, description == null ? "" : description.trim());
    }
}
