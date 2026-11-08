package cz.maramat.model;

import java.awt.Point;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

        // Rung 1: Classic Start/Stop Motor Seal-In Circuit with Parallel Branch
        LadderRung r1 = new LadderRung(2, 10, "Motor Start/Stop Seal-In Latch (DI0=Start, DI1=Stop NC, M0=Seal)");
        r1.getMainElements().add(new LadderElement(LadderElement.Kind.NO_CONTACT, "DI0"));
        r1.getMainElements().add(new LadderElement(LadderElement.Kind.NC_CONTACT, "DI1"));

        LadderBranch sealBranch = new LadderBranch(0, 0);
        sealBranch.getElements().add(new LadderElement(LadderElement.Kind.NO_CONTACT, "M0"));
        r1.getParallelBranches().add(sealBranch);

        r1.getCoils().add(new LadderElement(LadderElement.Kind.NORMAL_COIL, "M0"));
        r1.getCoils().add(new LadderElement(LadderElement.Kind.NORMAL_COIL, "DO0"));
        page1.getRungs().add(r1);

        // Rung 2: On-Delay Timer Function Block inside Ladder Rung driving DO1 Cooling Fan
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

        // Rung 3: Analog Comparator Function Block in Ladder Rung for Overtemp Alarm
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

        // Rung 4: Analog Input Scaling & Analog Output Drive (AI0 -> Scale -> AO0 when Motor M0 is running)
        LadderRung r4 = new LadderRung(2, 40, "Analog Speed Reference: Scales AI0 (0..4095) -> AO0 (0..1000) when MotorRun M0 is active");
        r4.getMainElements().add(new LadderElement(LadderElement.Kind.NO_CONTACT, "M0"));
        BlockDefinition scaleDef = BlockCatalog.getById("Math.Scale");
        if (scaleDef != null) {
            BlockInstance scaleBlock = new BlockInstance(scaleDef, "Scale_AI0", 0, 0, 2, 45);
            scaleBlock.getInputs().get(1).setConstantValue("AI0");
            scaleBlock.getInputs().get(2).setConstantValue("0");
            scaleBlock.getInputs().get(3).setConstantValue("4095");
            scaleBlock.getInputs().get(4).setConstantValue("0");
            scaleBlock.getInputs().get(5).setConstantValue("1000");
            r4.getMainElements().add(new LadderElement(scaleBlock));
        }
        r4.getCoils().add(new LadderElement(LadderElement.Kind.ANALOG_COIL, "Scale_AI0.Y", "AO0"));
        page1.getRungs().add(r4);

        // Rung 5: Alarm Reset Rung
        LadderRung r5 = new LadderRung(2, 50, "Fault Reset Button (DI2 Rising Edge unlatches DO2)");
        r5.getMainElements().add(new LadderElement(LadderElement.Kind.POS_EDGE_CONTACT, "DI2"));
        r5.getCoils().add(new LadderElement(LadderElement.Kind.RESET_COIL, "DO2"));
        page1.getRungs().add(r5);

        return p;
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
