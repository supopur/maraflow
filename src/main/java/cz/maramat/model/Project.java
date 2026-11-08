package cz.maramat.model;

import java.awt.Point;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class Project {
    public static final String VERSION = "1.0.0";

    private String projectName = "test";
    private File projectFile = null;
    private boolean dirty = true;
    private String targetPlatform = "STM32F407-Maraflow-Std";
    private int defaultThreadId = 2;
    private final List<Page> pages = new ArrayList<>();

    public Project() {
        pages.add(new Page("Page 1"));
    }

    public static Project createDefaultSampleProject() {
        Project p = new Project();
        p.setProjectName("test");
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

        // Wire 8xDI1.DI0 -> And1.X1 (BOOL -> BOOL, red wire)
        Point s1 = bDi.getOutputPinAnchorGrid(0);
        Point e1 = bAnd1.getInputPinAnchorGrid(1);
        page1.getWires().add(new Wire(bDi.getId(), 0, bAnd1.getId(), 1, "8xDI1.DI0", s1, e1));

        // Wire 8xDI1.DI1 -> And1.X2 (BOOL -> BOOL, red wire)
        Point s2 = bDi.getOutputPinAnchorGrid(1);
        Point e2 = bAnd1.getInputPinAnchorGrid(2);
        page1.getWires().add(new Wire(bDi.getId(), 1, bAnd1.getId(), 2, "8xDI1.DI1", s2, e2));

        // Wire And1.Y (BOOL) -> Value1.In (INT) (BOOL -> INT, split-color wire!)
        Point s3 = bAnd1.getOutputPinAnchorGrid(0);
        Point e3 = bVal1.getInputPinAnchorGrid(0);
        page1.getWires().add(new Wire(bAnd1.getId(), 0, bVal1.getId(), 0, "And1.Y", s3, e3));

        return p;
    }

    public String formatWindowTitle() {
        if (projectFile == null) {
            return "Unsaved* - Maraflow V" + VERSION;
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
        this.projectName = projectName;
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

    public List<Page> getPages() {
        return pages;
    }
}
