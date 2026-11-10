package cz.maramat.io;

import cz.maramat.model.BlockCatalog;
import cz.maramat.model.BlockDefinition;
import cz.maramat.model.BlockInstance;
import cz.maramat.model.DataType;
import cz.maramat.model.EStopConfig;
import cz.maramat.model.GraphicElement;
import cz.maramat.model.HeaderColor;
import cz.maramat.model.IecLanguage;
import cz.maramat.model.LadderBranch;
import cz.maramat.model.LadderElement;
import cz.maramat.model.LadderRung;
import cz.maramat.model.Page;
import cz.maramat.model.PinInstance;
import cz.maramat.model.Project;
import cz.maramat.model.StVariable;
import cz.maramat.model.Wire;

import java.awt.Color;
import java.awt.Point;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Properties;

public final class MflProjectSerializer {

    private MflProjectSerializer() {}

    public static void saveProject(Project project, File file) throws IOException {
        Properties props = new Properties();
        props.setProperty("maraflow.version", Project.VERSION);
        props.setProperty("project.name", project.getProjectName());
        props.setProperty("project.author", project.getAuthor());
        props.setProperty("project.description", project.getDescription());
        props.setProperty("project.targetPlatform", project.getTargetPlatform());
        props.setProperty("project.defaultThreadId", Integer.toString(project.getDefaultThreadId()));
        props.setProperty("project.primaryLanguage", project.getPrimaryLanguage().name());

        EStopConfig ecfg = project.getEStopConfig();
        props.setProperty("project.estop.enabled", Boolean.toString(ecfg.isEnabled()));
        props.setProperty("project.estop.mode", ecfg.getMode().name());
        props.setProperty("project.estop.pageName", ecfg.getDedicatedEStopPageName());
        props.setProperty("project.estop.manualReset", Boolean.toString(ecfg.isRequireManualReset()));
        props.setProperty("project.estop.ruleCount", Integer.toString(ecfg.getInputRules().size()));
        for (int ri = 0; ri < ecfg.getInputRules().size(); ri++) {
            EStopConfig.InputRule r = ecfg.getInputRules().get(ri);
            String rp = "project.estop.rule." + ri + ".";
            props.setProperty(rp + "channel", r.getInputChannel());
            props.setProperty(rp + "activeLow", Boolean.toString(r.isActiveLow()));
            props.setProperty(rp + "enabled", Boolean.toString(r.isEnabled()));
            props.setProperty(rp + "desc", r.getDescription());
        }

        int tagIdx = 0;
        for (Map.Entry<String, DataType> tag : project.getSymbolicTags().entrySet()) {
            props.setProperty("project.tag." + tagIdx + ".name", tag.getKey());
            props.setProperty("project.tag." + tagIdx + ".type", tag.getValue().name());
            props.setProperty("project.tag." + tagIdx + ".desc",
                    project.getSymbolicTagDescriptions().getOrDefault(tag.getKey(), ""));
            tagIdx++;
        }
        props.setProperty("project.tagCount", Integer.toString(tagIdx));

        props.setProperty("project.pageCount", Integer.toString(project.getPages().size()));

        for (int pi = 0; pi < project.getPages().size(); pi++) {
            Page page = project.getPages().get(pi);
            String pfx = "page." + pi + ".";
            props.setProperty(pfx + "name", page.getName());
            props.setProperty(pfx + "language", page.getLanguage().name());
            props.setProperty(pfx + "eStopPage", Boolean.toString(page.isEStopPage()));
            props.setProperty(pfx + "stThreadId", Integer.toString(page.getStThreadId()));
            props.setProperty(pfx + "stOrder", Integer.toString(page.getStOrder()));
            props.setProperty(pfx + "stCode", page.getStCode());
            props.setProperty(pfx + "stVarCount", Integer.toString(page.getStVariables().size()));
            for (int vi = 0; vi < page.getStVariables().size(); vi++) {
                StVariable sv = page.getStVariables().get(vi);
                String vp = pfx + "stVar." + vi + ".";
                props.setProperty(vp + "scope", sv.getScope().name());
                props.setProperty(vp + "name", sv.getName());
                props.setProperty(vp + "type", sv.getDataType().name());
                props.setProperty(vp + "init", sv.getInitialValue());
                props.setProperty(vp + "desc", sv.getDescription());
            }
            props.setProperty(pfx + "blockCount", Integer.toString(page.getBlocks().size()));

            for (int bi = 0; bi < page.getBlocks().size(); bi++) {
                saveBlockInstance(props, pfx + "block." + bi + ".", page.getBlocks().get(bi));
            }

            props.setProperty(pfx + "wireCount", Integer.toString(page.getWires().size()));
            for (int wi = 0; wi < page.getWires().size(); wi++) {
                Wire w = page.getWires().get(wi);
                String wp = pfx + "wire." + wi + ".";
                props.setProperty(wp + "netName", w.getNetName());
                props.setProperty(wp + "customNetName", Boolean.toString(w.isCustomNetName()));
                props.setProperty(wp + "srcBlock", w.getSourceBlockId());
                props.setProperty(wp + "srcPin", Integer.toString(w.getSourcePinIndex()));
                props.setProperty(wp + "dstBlock", w.getTargetBlockId());
                props.setProperty(wp + "dstPin", Integer.toString(w.getTargetPinIndex()));
                props.setProperty(wp + "hidden", Boolean.toString(w.isHidden()));
                props.setProperty(wp + "busId", w.getBusId() == null ? "" : w.getBusId());
                StringBuilder pts = new StringBuilder();
                for (int k = 0; k < w.getGridPoints().size(); k++) {
                    if (k > 0) pts.append(";");
                    Point pt = w.getGridPoints().get(k);
                    pts.append(pt.x).append(",").append(pt.y);
                }
                props.setProperty(wp + "points", pts.toString());
            }

            props.setProperty(pfx + "graphicCount", Integer.toString(page.getGraphics().size()));
            for (int gi = 0; gi < page.getGraphics().size(); gi++) {
                GraphicElement g = page.getGraphics().get(gi);
                String gp = pfx + "graphic." + gi + ".";
                props.setProperty(gp + "kind", g.getKind().name());
                props.setProperty(gp + "gridX", Integer.toString(g.getGridX()));
                props.setProperty(gp + "gridY", Integer.toString(g.getGridY()));
                props.setProperty(gp + "gridW", Integer.toString(g.getGridW()));
                props.setProperty(gp + "gridH", Integer.toString(g.getGridH()));
                props.setProperty(gp + "strokeRgb", Integer.toString(g.getStrokeColor().getRGB()));
                props.setProperty(gp + "fillRgb", Integer.toString(g.getFillColor().getRGB()));
                props.setProperty(gp + "filled", Boolean.toString(g.isFilled()));
                props.setProperty(gp + "strokeWidth", Integer.toString(g.getStrokeWidth()));
                props.setProperty(gp + "dashed", Boolean.toString(g.isDashed()));
                props.setProperty(gp + "ngonSides", Integer.toString(g.getNgonSides()));
                props.setProperty(gp + "text", g.getText());
                props.setProperty(gp + "fontSize", Integer.toString(g.getFontSize()));
                props.setProperty(gp + "boundTargetPin", g.getBoundTargetPin());
                props.setProperty(gp + "toggleButton", Boolean.toString(g.isToggleButton()));
            }

            // Save Ladder Rungs
            props.setProperty(pfx + "rungCount", Integer.toString(page.getRungs().size()));
            for (int ri = 0; ri < page.getRungs().size(); ri++) {
                LadderRung rung = page.getRungs().get(ri);
                String rp = pfx + "rung." + ri + ".";
                props.setProperty(rp + "id", rung.getId());
                props.setProperty(rp + "comment", rung.getComment());
                props.setProperty(rp + "threadId", Integer.toString(rung.getThreadId()));
                props.setProperty(rp + "order", Integer.toString(rung.getOrder()));

                props.setProperty(rp + "mainCount", Integer.toString(rung.getMainElements().size()));
                for (int mi = 0; mi < rung.getMainElements().size(); mi++) {
                    saveLadderElement(props, rp + "main." + mi + ".", rung.getMainElements().get(mi));
                }

                props.setProperty(rp + "branchCount", Integer.toString(rung.getParallelBranches().size()));
                for (int bri = 0; bri < rung.getParallelBranches().size(); bri++) {
                    LadderBranch br = rung.getParallelBranches().get(bri);
                    String brp = rp + "branch." + bri + ".";
                    props.setProperty(brp + "id", br.getId());
                    props.setProperty(brp + "startCol", Integer.toString(br.getStartColumn()));
                    props.setProperty(brp + "endCol", Integer.toString(br.getEndColumn()));
                    props.setProperty(brp + "elCount", Integer.toString(br.getElements().size()));
                    for (int eli = 0; eli < br.getElements().size(); eli++) {
                        saveLadderElement(props, brp + "el." + eli + ".", br.getElements().get(eli));
                    }
                }

                props.setProperty(rp + "coilCount", Integer.toString(rung.getCoils().size()));
                for (int ci = 0; ci < rung.getCoils().size(); ci++) {
                    saveLadderElement(props, rp + "coil." + ci + ".", rung.getCoils().get(ci));
                }
            }
        }

        try (FileOutputStream out = new FileOutputStream(file)) {
            props.storeToXML(out, "Maraflow PLC Project (.mfl)", "UTF-8");
        }
        project.setProjectFile(file);
        project.setDirty(false);
    }

    private static void saveLadderElement(Properties props, String ep, LadderElement el) {
        props.setProperty(ep + "id", el.getId());
        props.setProperty(ep + "kind", el.getKind().name());
        props.setProperty(ep + "operand", el.getOperand());
        props.setProperty(ep + "srcOperand", el.getSourceOperand());
        props.setProperty(ep + "comment", el.getComment());
        if (el.getKind() == LadderElement.Kind.FUNCTION_BLOCK && el.getBlockInstance() != null) {
            saveBlockInstance(props, ep + "fb.", el.getBlockInstance());
        }
    }

    private static void saveBlockInstance(Properties props, String bp, BlockInstance b) {
        props.setProperty(bp + "id", b.getId());
        props.setProperty(bp + "defId", b.getDefinitionId());
        props.setProperty(bp + "typeName", b.getTypeName());
        props.setProperty(bp + "name", b.getName());
        props.setProperty(bp + "gridX", Integer.toString(b.getGridX()));
        props.setProperty(bp + "gridY", Integer.toString(b.getGridY()));
        props.setProperty(bp + "threadId", Integer.toString(b.getThreadId()));
        props.setProperty(bp + "order", Integer.toString(b.getOrder()));
        props.setProperty(bp + "color", b.getHeaderColor().getLabel());
        props.setProperty(bp + "description", b.getDescription());

        int propIdx = 0;
        for (Map.Entry<String, String> entry : b.getCustomProperties().entrySet()) {
            props.setProperty(bp + "prop." + propIdx + ".k", entry.getKey());
            props.setProperty(bp + "prop." + propIdx + ".v", entry.getValue());
            propIdx++;
        }
        props.setProperty(bp + "propCount", Integer.toString(propIdx));

        props.setProperty(bp + "inCount", Integer.toString(b.getInputs().size()));
        for (int ii = 0; ii < b.getInputs().size(); ii++) {
            PinInstance pin = b.getInputs().get(ii);
            props.setProperty(bp + "in." + ii + ".short", pin.getShortName());
            props.setProperty(bp + "in." + ii + ".full", pin.getFullName());
            props.setProperty(bp + "in." + ii + ".type", pin.getDataType().name());
            props.setProperty(bp + "in." + ii + ".const", pin.getConstantValue());
        }

        props.setProperty(bp + "outCount", Integer.toString(b.getOutputs().size()));
        for (int oi = 0; oi < b.getOutputs().size(); oi++) {
            PinInstance pin = b.getOutputs().get(oi);
            props.setProperty(bp + "out." + oi + ".short", pin.getShortName());
            props.setProperty(bp + "out." + oi + ".full", pin.getFullName());
            props.setProperty(bp + "out." + oi + ".type", pin.getDataType().name());
            props.setProperty(bp + "out." + oi + ".const", pin.getConstantValue());
        }
    }

    public static Project loadProject(File file) throws IOException {
        Properties props = new Properties();
        try (FileInputStream in = new FileInputStream(file)) {
            props.loadFromXML(in);
        }
        Project project = new Project();
        project.getPages().clear();
        project.setProjectFile(file);
        project.setProjectName(props.getProperty("project.name", "test"));
        project.setAuthor(props.getProperty("project.author", ""));
        project.setDescription(props.getProperty("project.description", ""));
        project.setTargetPlatform(props.getProperty("project.targetPlatform", "STM32F407-Maraflow-Std"));
        project.setDefaultThreadId(Integer.parseInt(props.getProperty("project.defaultThreadId", "2")));
        project.setPrimaryLanguage(IecLanguage.fromCode(props.getProperty("project.primaryLanguage", "FBD")));

        EStopConfig ecfg = project.getEStopConfig();
        ecfg.setEnabled(Boolean.parseBoolean(props.getProperty("project.estop.enabled", "true")));
        try {
            ecfg.setMode(EStopConfig.Mode.valueOf(props.getProperty("project.estop.mode", EStopConfig.Mode.DISABLE_OUTPUTS_AND_STOP.name())));
        } catch (IllegalArgumentException ignored) {}
        ecfg.setDedicatedEStopPageName(props.getProperty("project.estop.pageName", "!EStop"));
        ecfg.setRequireManualReset(Boolean.parseBoolean(props.getProperty("project.estop.manualReset", "true")));
        String ruleCountStr = props.getProperty("project.estop.ruleCount");
        if (ruleCountStr != null) {
            int rCount = Integer.parseInt(ruleCountStr);
            ecfg.getInputRules().clear();
            for (int ri = 0; ri < rCount; ri++) {
                String rp = "project.estop.rule." + ri + ".";
                ecfg.getInputRules().add(new EStopConfig.InputRule(
                        props.getProperty(rp + "channel", "DI0"),
                        Boolean.parseBoolean(props.getProperty(rp + "activeLow", "true")),
                        Boolean.parseBoolean(props.getProperty(rp + "enabled", "true")),
                        props.getProperty(rp + "desc", "")
                ));
            }
        }

        int tagCount = Integer.parseInt(props.getProperty("project.tagCount", "0"));
        if (tagCount > 0) {
            project.getSymbolicTags().clear();
            project.getSymbolicTagDescriptions().clear();
            for (int ti = 0; ti < tagCount; ti++) {
                String tName = props.getProperty("project.tag." + ti + ".name");
                String tType = props.getProperty("project.tag." + ti + ".type", "BOOL");
                String tDesc = props.getProperty("project.tag." + ti + ".desc", "");
                if (tName != null && !tName.isBlank()) {
                    project.putSymbolicTag(tName, DataType.valueOf(tType), tDesc);
                }
            }
        }

        int pageCount = Integer.parseInt(props.getProperty("project.pageCount", "1"));
        for (int pi = 0; pi < pageCount; pi++) {
            String pfx = "page." + pi + ".";
            IecLanguage lang = IecLanguage.fromCode(props.getProperty(pfx + "language", "FBD"));
            Page page = new Page(props.getProperty(pfx + "name", "Page " + (pi + 1)), lang);
            page.setEStopPage(Boolean.parseBoolean(props.getProperty(pfx + "eStopPage", Boolean.toString(page.isEStopPage()))));
            page.setStThreadId(Integer.parseInt(props.getProperty(pfx + "stThreadId", "2")));
            page.setStOrder(Integer.parseInt(props.getProperty(pfx + "stOrder", "10")));
            page.setStCode(props.getProperty(pfx + "stCode", ""));
            int stVarCount = Integer.parseInt(props.getProperty(pfx + "stVarCount", "0"));
            for (int vi = 0; vi < stVarCount; vi++) {
                String vp = pfx + "stVar." + vi + ".";
                StVariable.Scope scope = StVariable.Scope.VAR;
                try {
                    scope = StVariable.Scope.valueOf(props.getProperty(vp + "scope", "VAR"));
                } catch (IllegalArgumentException ignored) {}
                DataType dt = DataType.INT;
                try {
                    dt = DataType.valueOf(props.getProperty(vp + "type", "INT"));
                } catch (IllegalArgumentException ignored) {}
                page.getStVariables().add(new StVariable(
                        scope,
                        props.getProperty(vp + "name", "Var" + (vi + 1)),
                        dt,
                        props.getProperty(vp + "init", "0"),
                        props.getProperty(vp + "desc", "")
                ));
            }

            int blockCount = Integer.parseInt(props.getProperty(pfx + "blockCount", "0"));
            for (int bi = 0; bi < blockCount; bi++) {
                page.getBlocks().add(loadBlockInstance(props, pfx + "block." + bi + ".", bi));
            }

            int wireCount = Integer.parseInt(props.getProperty(pfx + "wireCount", "0"));
            for (int wi = 0; wi < wireCount; wi++) {
                String wp = pfx + "wire." + wi + ".";
                String srcB = props.getProperty(wp + "srcBlock", "");
                int srcP = Integer.parseInt(props.getProperty(wp + "srcPin", "0"));
                String dstB = props.getProperty(wp + "dstBlock", "");
                int dstP = Integer.parseInt(props.getProperty(wp + "dstPin", "0"));
                String netName = props.getProperty(wp + "netName", "Net");
                Wire w = new Wire(srcB, srcP, dstB, dstP, netName, new Point(0, 0), new Point(1, 0));
                w.setNetName(netName, Boolean.parseBoolean(props.getProperty(wp + "customNetName", "false")));
                w.setHidden(Boolean.parseBoolean(props.getProperty(wp + "hidden", "false")));
                String busId = props.getProperty(wp + "busId", "");
                w.setBusId(busId.isEmpty() ? null : busId);

                String ptsStr = props.getProperty(wp + "points", "");
                if (!ptsStr.isEmpty()) {
                    w.getGridPoints().clear();
                    for (String tok : ptsStr.split(";")) {
                        String[] xy = tok.split(",");
                        if (xy.length == 2) {
                            w.getGridPoints().add(new Point(Integer.parseInt(xy[0]), Integer.parseInt(xy[1])));
                        }
                    }
                }
                page.getWires().add(w);
            }

            int graphicCount = Integer.parseInt(props.getProperty(pfx + "graphicCount", "0"));
            for (int gi = 0; gi < graphicCount; gi++) {
                String gp = pfx + "graphic." + gi + ".";
                GraphicElement.Kind kind = GraphicElement.Kind.valueOf(props.getProperty(gp + "kind", "RECTANGLE"));
                int gx = Integer.parseInt(props.getProperty(gp + "gridX", "5"));
                int gy = Integer.parseInt(props.getProperty(gp + "gridY", "5"));
                int gw = Integer.parseInt(props.getProperty(gp + "gridW", "6"));
                int gh = Integer.parseInt(props.getProperty(gp + "gridH", "4"));
                GraphicElement g = new GraphicElement(kind, gx, gy, gw, gh);
                g.setStrokeColor(new Color(Integer.parseInt(props.getProperty(gp + "strokeRgb", "-13421773"))));
                g.setFillColor(new Color(Integer.parseInt(props.getProperty(gp + "fillRgb", "-984840"))));
                g.setFilled(Boolean.parseBoolean(props.getProperty(gp + "filled", "false")));
                g.setStrokeWidth(Integer.parseInt(props.getProperty(gp + "strokeWidth", "1")));
                g.setDashed(Boolean.parseBoolean(props.getProperty(gp + "dashed", "false")));
                g.setNgonSides(Integer.parseInt(props.getProperty(gp + "ngonSides", "6")));
                g.setText(props.getProperty(gp + "text", "Label"));
                g.setFontSize(Integer.parseInt(props.getProperty(gp + "fontSize", "11")));
                g.setBoundTargetPin(props.getProperty(gp + "boundTargetPin", ""));
                g.setToggleButton(Boolean.parseBoolean(props.getProperty(gp + "toggleButton", "false")));
                page.getGraphics().add(g);
            }

            int rungCount = Integer.parseInt(props.getProperty(pfx + "rungCount", "0"));
            for (int ri = 0; ri < rungCount; ri++) {
                String rp = pfx + "rung." + ri + ".";
                int tid = Integer.parseInt(props.getProperty(rp + "threadId", "2"));
                int ord = Integer.parseInt(props.getProperty(rp + "order", Integer.toString((ri + 1) * 10)));
                String comment = props.getProperty(rp + "comment", "");
                LadderRung rung = new LadderRung(tid, ord, comment);
                rung.setId(props.getProperty(rp + "id", rung.getId()));

                int mainCount = Integer.parseInt(props.getProperty(rp + "mainCount", "0"));
                for (int mi = 0; mi < mainCount; mi++) {
                    rung.getMainElements().add(loadLadderElement(props, rp + "main." + mi + ".", mi));
                }

                int branchCount = Integer.parseInt(props.getProperty(rp + "branchCount", "0"));
                for (int bri = 0; bri < branchCount; bri++) {
                    String brp = rp + "branch." + bri + ".";
                    int sCol = Integer.parseInt(props.getProperty(brp + "startCol", "0"));
                    int eCol = Integer.parseInt(props.getProperty(brp + "endCol", "0"));
                    LadderBranch br = new LadderBranch(sCol, eCol);
                    br.setId(props.getProperty(brp + "id", br.getId()));
                    int elCount = Integer.parseInt(props.getProperty(brp + "elCount", "0"));
                    for (int eli = 0; eli < elCount; eli++) {
                        br.getElements().add(loadLadderElement(props, brp + "el." + eli + ".", eli));
                    }
                    rung.getParallelBranches().add(br);
                }

                int coilCount = Integer.parseInt(props.getProperty(rp + "coilCount", "0"));
                for (int ci = 0; ci < coilCount; ci++) {
                    rung.getCoils().add(loadLadderElement(props, rp + "coil." + ci + ".", ci));
                }

                page.getRungs().add(rung);
            }

            project.getPages().add(page);
        }
        if (project.getPages().isEmpty()) {
            project.getPages().add(new Page("Page 1", IecLanguage.FBD));
        }
        project.setDirty(false);
        BlockCatalog.syncMacrosFromProject(project);
        return project;
    }

    private static LadderElement loadLadderElement(Properties props, String ep, int indexHint) {
        LadderElement.Kind kind = LadderElement.Kind.valueOf(
                props.getProperty(ep + "kind", LadderElement.Kind.NO_CONTACT.name())
        );
        String operand = props.getProperty(ep + "operand", kind.isCoil() ? "DO0" : "DI0");
        String srcOperand = props.getProperty(ep + "srcOperand", "AI0");
        LadderElement el;
        if (kind == LadderElement.Kind.FUNCTION_BLOCK) {
            BlockInstance fb = loadBlockInstance(props, ep + "fb.", indexHint);
            el = new LadderElement(fb);
        } else {
            el = new LadderElement(kind, srcOperand, operand);
        }
        el.setId(props.getProperty(ep + "id", el.getId()));
        el.setComment(props.getProperty(ep + "comment", ""));
        return el;
    }

    private static BlockInstance loadBlockInstance(Properties props, String bp, int indexHint) {
        String defId = props.getProperty(bp + "defId", "Bool.And");
        String savedTypeName = props.getProperty(bp + "typeName", "Block");
        BlockDefinition def = BlockCatalog.getById(defId);
        if (def == null) {
            if (defId.startsWith("Macro.User.")) {
                def = new BlockDefinition(defId, "Macro", savedTypeName, savedTypeName, List.of(), List.of(), 4, 2, false, false, 15.0);
            } else {
                def = BlockCatalog.getById("Bool.And");
            }
        }
        String name = props.getProperty(bp + "name", "Block" + (indexHint + 1));
        int gx = Integer.parseInt(props.getProperty(bp + "gridX", "10"));
        int gy = Integer.parseInt(props.getProperty(bp + "gridY", "10"));
        int tid = Integer.parseInt(props.getProperty(bp + "threadId", "2"));
        int ord = Integer.parseInt(props.getProperty(bp + "order", "10"));

        BlockInstance b = new BlockInstance(def, name, gx, gy, tid, ord);
        b.setId(props.getProperty(bp + "id", b.getId()));
        b.setDefinitionId(defId);
        b.setTypeName(props.getProperty(bp + "typeName", def.getTypeName()));
        b.setHeaderColor(HeaderColor.fromLabel(props.getProperty(bp + "color", "clDefault")));
        b.setDescription(props.getProperty(bp + "description", ""));

        int propCount = Integer.parseInt(props.getProperty(bp + "propCount", "0"));
        for (int k = 0; k < propCount; k++) {
            String key = props.getProperty(bp + "prop." + k + ".k");
            String val = props.getProperty(bp + "prop." + k + ".v");
            if (key != null && val != null) {
                b.getCustomProperties().put(key, val);
            }
        }

        int inCount = Integer.parseInt(props.getProperty(bp + "inCount", "0"));
        if (inCount > 0) {
            b.getInputs().clear();
            for (int ii = 0; ii < inCount; ii++) {
                String sn = props.getProperty(bp + "in." + ii + ".short", "X");
                String fn = props.getProperty(bp + "in." + ii + ".full", sn);
                DataType dt = DataType.valueOf(props.getProperty(bp + "in." + ii + ".type", "BOOL"));
                String cv = props.getProperty(bp + "in." + ii + ".const", dt.getDefaultConstant());
                b.getInputs().add(new PinInstance(sn, fn, dt, true, cv));
            }
        }

        int outCount = Integer.parseInt(props.getProperty(bp + "outCount", "0"));
        if (outCount > 0) {
            b.getOutputs().clear();
            for (int oi = 0; oi < outCount; oi++) {
                String sn = props.getProperty(bp + "out." + oi + ".short", "Y");
                String fn = props.getProperty(bp + "out." + oi + ".full", sn);
                DataType dt = DataType.valueOf(props.getProperty(bp + "out." + oi + ".type", "BOOL"));
                String cv = props.getProperty(bp + "out." + oi + ".const", dt.getDefaultConstant());
                b.getOutputs().add(new PinInstance(sn, fn, dt, false, cv));
            }
        }
        return b;
    }
}
