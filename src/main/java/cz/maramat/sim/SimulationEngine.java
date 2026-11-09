package cz.maramat.sim;

import cz.maramat.model.BlockInstance;
import cz.maramat.model.DataType;
import cz.maramat.model.GraphicElement;
import cz.maramat.model.IecLanguage;
import cz.maramat.model.LadderBranch;
import cz.maramat.model.LadderElement;
import cz.maramat.model.LadderRung;
import cz.maramat.model.Page;
import cz.maramat.model.PinInstance;
import cz.maramat.model.PlcThread;
import cz.maramat.model.Project;
import cz.maramat.model.Wire;

import javax.swing.SwingUtilities;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class SimulationEngine {
    public enum State {
        STOPPED,
        RUNNING,
        PAUSED,
        WATCH_MODE
    }

    private volatile State state = State.STOPPED;
    private volatile double speedMultiplier = 1.0;
    private final AtomicBoolean workerRunning = new AtomicBoolean(false);
    private Thread virtualSimThread;
    private final Map<String, Double> virtualRegisters = new ConcurrentHashMap<>();
    private final Map<String, Page> macroRuntimePages = new ConcurrentHashMap<>();
    private final Runnable uiRepaintCallback;
    private final List<ScopeSampleListener> scopeListeners = new ArrayList<>();

    public interface ScopeSampleListener {
        void onScanCompleted(Project project, double simTimeSeconds);
    }

    public SimulationEngine(Runnable uiRepaintCallback) {
        this.uiRepaintCallback = uiRepaintCallback;
    }

    public void addScopeListener(ScopeSampleListener l) {
        scopeListeners.add(l);
    }

    public State getState() {
        return state;
    }

    public boolean isShowingLiveOutputs() {
        return state == State.RUNNING || state == State.PAUSED || state == State.WATCH_MODE;
    }

    public double getSpeedMultiplier() {
        return speedMultiplier;
    }

    public void setSpeedMultiplier(double speedMultiplier) {
        this.speedMultiplier = Math.max(0.001, speedMultiplier);
    }

    public double getOperandValue(Project project, String operand) {
        if (operand == null || operand.isBlank()) return 0.0;
        String key = operand.trim();

        // Check if operand is a BlockName.PinName reference
        int dot = key.indexOf('.');
        if (dot > 0 && project != null) {
            String blockName = key.substring(0, dot);
            String pinName = key.substring(dot + 1);
            for (Page page : project.getPages()) {
                BlockInstance b = page.findBlockByName(blockName);
                if (b != null) {
                    for (PinInstance out : b.getOutputs()) {
                        if (out.getShortName().equalsIgnoreCase(pinName)) {
                            return out.getRuntimeValue();
                        }
                    }
                    for (PinInstance in : b.getInputs()) {
                        if (in.getShortName().equalsIgnoreCase(pinName)) {
                            return in.getRuntimeValue();
                        }
                    }
                }
            }
        }

        // Check virtual registers / hardware I/O / memory bits
        Double regVal = virtualRegisters.get(key.toUpperCase());
        if (regVal != null) return regVal;
        regVal = virtualRegisters.get(key);
        if (regVal != null) return regVal;

        // Otherwise try numeric literal
        try {
            if ("TRUE".equalsIgnoreCase(key)) return 1.0;
            if ("FALSE".equalsIgnoreCase(key)) return 0.0;
            return Double.parseDouble(key);
        } catch (NumberFormatException ignored) {
            return 0.0;
        }
    }

    public void setOperandValue(String operand, double value) {
        if (operand == null || operand.isBlank()) return;
        String key = operand.trim();
        virtualRegisters.put(key, value);
        virtualRegisters.put(key.toUpperCase(), value);
        requestUiRepaint();
    }

    public void toggleBooleanOperand(Project project, String operand) {
        if (operand == null || operand.isBlank()) return;
        double cur = getOperandValue(project, operand);
        setOperandValue(operand, (cur != 0.0) ? 0.0 : 1.0);
    }

    public void startSimulation(Project project) {
        if (state == State.RUNNING) return;
        if (state == State.STOPPED || state == State.WATCH_MODE) {
            resetAllBlocks(project);
        }
        state = State.RUNNING;
        if (workerRunning.compareAndSet(false, true)) {
            virtualSimThread = Thread.ofVirtual().name("Maraflow-Sim-Engine").start(() -> runLoop(project));
        }
        requestUiRepaint();
    }

    public void pauseSimulation() {
        if (state == State.RUNNING) {
            state = State.PAUSED;
            requestUiRepaint();
        }
    }

    public void stopSimulation(Project project) {
        state = State.STOPPED;
        workerRunning.set(false);
        if (virtualSimThread != null) {
            virtualSimThread.interrupt();
        }
        resetAllBlocks(project);
        requestUiRepaint();
    }

    public void enterWatchMode(Project project) {
        stopSimulation(project);
        state = State.WATCH_MODE;
        if (workerRunning.compareAndSet(false, true)) {
            virtualSimThread = Thread.ofVirtual().name("Maraflow-Watch-Poller").start(() -> runLoop(project));
        }
        requestUiRepaint();
    }

    private void resetAllBlocks(Project project) {
        virtualRegisters.clear();
        macroRuntimePages.clear();
        if (project == null) return;
        for (Page page : project.getPages()) {
            for (BlockInstance b : page.getBlocks()) {
                b.resetRuntimeState();
            }
            for (LadderRung r : page.getRungs()) {
                r.resetRuntimeState();
            }
        }
    }

    private void runLoop(Project project) {
        double[] threadAccumMs = new double[6];
        double simTimeSeconds = 0.0;
        long lastWallNs = System.nanoTime();
        long lastUiRepaintMs = 0L;

        while (workerRunning.get()) {
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                break;
            }
            long nowNs = System.nanoTime();
            double wallDeltaMs = (nowNs - lastWallNs) / 1_000_000.0;
            lastWallNs = nowNs;

            if (state != State.RUNNING && state != State.WATCH_MODE) {
                continue;
            }

            double effectiveMult = (state == State.WATCH_MODE) ? 1.0 : speedMultiplier;
            double simDeltaMs = wallDeltaMs * effectiveMult;
            simTimeSeconds += simDeltaMs / 1000.0;

            for (PlcThread pt : PlcThread.values()) {
                int tid = pt.getId();
                threadAccumMs[tid] += simDeltaMs;
                double period = pt.getPeriodMs();
                if (threadAccumMs[tid] >= period) {
                    int cycles = (int) Math.min(20, Math.floor(threadAccumMs[tid] / period));
                    threadAccumMs[tid] -= cycles * period;
                    double dtMs = cycles * period;
                    executeThreadScan(project, tid, dtMs, simTimeSeconds);
                }
            }

            long nowMs = System.currentTimeMillis();
            if (nowMs - lastUiRepaintMs >= 33) {
                lastUiRepaintMs = nowMs;
                for (ScopeSampleListener l : scopeListeners) {
                    l.onScanCompleted(project, simTimeSeconds);
                }
                requestUiRepaint();
            }
        }
    }

    private void executeThreadScan(Project project, int threadId, double dtMs, double simTimeSeconds) {
        // 1. Execute top-level LD pages first so any registers/coils written in LD are immediately visible to FBD
        for (Page page : project.getPages()) {
            if (page.isMacro()) continue;
            if (page.getLanguage() == IecLanguage.LD) {
                executeLadderPageScan(project, page, threadId, dtMs, simTimeSeconds);
            }
        }

        // 2. Execute top-level FBD pages
        for (Page page : project.getPages()) {
            if (page.isMacro() || page.getLanguage() == IecLanguage.LD) continue;

            // Apply any active Visual Buttons on this FBD page
            for (GraphicElement ge : page.getGraphics()) {
                if (ge.getKind() == GraphicElement.Kind.VISUAL_BUTTON && !ge.getBoundTargetPin().isBlank()) {
                    String[] parts = ge.getBoundTargetPin().split("\\.");
                    if (parts.length == 2) {
                        BlockInstance targetBlock = page.findBlockByName(parts[0]);
                        if (targetBlock != null) {
                            for (int i = 0; i < targetBlock.getInputs().size(); i++) {
                                PinInstance pin = targetBlock.getInputs().get(i);
                                if (pin.getShortName().equalsIgnoreCase(parts[1])) {
                                    pin.setRuntimeValue(ge.isActiveState() ? 1.0 : 0.0);
                                }
                            }
                        }
                    }
                }
            }

            List<BlockInstance> threadBlocks = new ArrayList<>();
            for (BlockInstance b : page.getBlocks()) {
                if (b.getThreadId() == threadId) {
                    threadBlocks.add(b);
                }
            }
            threadBlocks.sort(Comparator.comparingInt(BlockInstance::getOrder));

            for (BlockInstance b : threadBlocks) {
                evaluateBlock(project, page, b, dtMs, simTimeSeconds, true, 0);
            }
        }
    }

    private void executeLadderPageScan(Project project, Page page, int threadId, double dtMs, double simTimeSeconds) {
        List<LadderRung> threadRungs = new ArrayList<>();
        for (LadderRung rung : page.getRungs()) {
            if (rung.getThreadId() == threadId) {
                threadRungs.add(rung);
            }
        }
        threadRungs.sort(Comparator.comparingInt(LadderRung::getOrder));

        for (LadderRung rung : threadRungs) {
            List<LadderElement> mainEls = rung.getMainElements();
            int n = mainEls.size();
            // nodePower[col] represents power state before mainElements[col]; nodePower[n] is after all main elements
            boolean[] nodePower = new boolean[n + 1];
            nodePower[0] = true; // Left power rail is always energized

            for (int col = 0; col < n; col++) {
                // First check if any parallel branch starts at an earlier column and ends at col - 1
                for (LadderBranch br : rung.getParallelBranches()) {
                    if (br.getEndColumn() == col - 1 && br.getStartColumn() <= col - 1) {
                        nodePower[col] |= br.isBranchEnergizedOut();
                    }
                }

                // Evaluate any parallel branch starting at col
                for (LadderBranch br : rung.getParallelBranches()) {
                    if (br.getStartColumn() == col) {
                        boolean brPower = nodePower[col];
                        br.setBranchEnergizedIn(brPower);
                        for (LadderElement bel : br.getElements()) {
                            brPower = evaluateLadderSeriesElement(project, page, bel, brPower, dtMs, simTimeSeconds);
                        }
                        br.setBranchEnergizedOut(brPower);
                    }
                }

                // Evaluate main series element at col
                LadderElement mainEl = mainEls.get(col);
                nodePower[col + 1] = evaluateLadderSeriesElement(project, page, mainEl, nodePower[col], dtMs, simTimeSeconds);
            }

            // Rejoin any parallel branches that end at the last column (>= n - 1)
            boolean finalPower = nodePower[n];
            for (LadderBranch br : rung.getParallelBranches()) {
                if (br.getEndColumn() >= n - 1) {
                    if (n == 0) {
                        boolean brPower = true;
                        br.setBranchEnergizedIn(true);
                        for (LadderElement bel : br.getElements()) {
                            brPower = evaluateLadderSeriesElement(project, page, bel, brPower, dtMs, simTimeSeconds);
                        }
                        br.setBranchEnergizedOut(brPower);
                    }
                    finalPower |= br.isBranchEnergizedOut();
                }
            }

            rung.setRungOutputEnergized(finalPower);

            // Evaluate all output coils on the right of the rung
            for (LadderElement coil : rung.getCoils()) {
                evaluateLadderCoil(project, coil, finalPower);
            }
        }
    }

    private boolean evaluateLadderSeriesElement(Project project, Page page, LadderElement el, boolean powerIn, double dtMs, double simTimeSeconds) {
        el.setEnergizedIn(powerIn);
        LadderElement.Kind kind = el.getKind();

        if (kind.isContact()) {
            boolean rawVal = getOperandValue(project, el.getOperand()) != 0.0;
            el.setOperandState(rawVal);
            boolean prev = el.isPrevEdgeInput();
            el.setPrevEdgeInput(rawVal);

            boolean conducts = switch (kind) {
                case NO_CONTACT -> rawVal;
                case NC_CONTACT -> !rawVal;
                case POS_EDGE_CONTACT -> rawVal && !prev;
                case NEG_EDGE_CONTACT -> !rawVal && prev;
                default -> false;
            };
            boolean out = powerIn && conducts;
            el.setEnergizedOut(out);
            return out;
        }

        if (kind == LadderElement.Kind.FUNCTION_BLOCK && el.getBlockInstance() != null) {
            BlockInstance b = el.getBlockInstance();
            List<PinInstance> ins = b.getInputs();
            if (!ins.isEmpty() && ins.getFirst().getShortName().equalsIgnoreCase("En")) {
                ins.getFirst().setRuntimeValue(powerIn ? 1.0 : 0.0);
                ins.getFirst().setConstantValue(powerIn ? "1" : "0");
            }
            // Populate other input pins from their operand/constant strings
            for (int i = 0; i < ins.size(); i++) {
                PinInstance pin = ins.get(i);
                if (i == 0 && pin.getShortName().equalsIgnoreCase("En")) continue;
                double val = getOperandValue(project, pin.getConstantValue());
                pin.setRuntimeValue(val);
            }
            evaluateBlock(project, page, b, dtMs, simTimeSeconds, false, 0);

            // Write any bound output pin operands (e.g., AO0, PWM0, REG_0)
            for (PinInstance outPin : b.getOutputs()) {
                String targetOp = outPin.getConstantValue();
                if (targetOp != null && !targetOp.isBlank() && !targetOp.equals("0")) {
                    setOperandValue(targetOp, outPin.getRuntimeValue());
                }
            }

            // Determine outgoing power flow: if primary output is BOOL (e.g., Timer Q, Comparator Y, Bool gate), use it when En is high; otherwise pass powerIn (ENO)
            boolean outPower = powerIn;
            if (!b.getOutputs().isEmpty()) {
                PinInstance firstOut = b.getOutputs().getFirst();
                if (firstOut.getDataType() == DataType.BOOL) {
                    outPower = powerIn && (firstOut.getRuntimeValue() != 0.0);
                }
            }
            el.setOperandState(outPower);
            el.setEnergizedOut(outPower);
            return outPower;
        }

        el.setEnergizedOut(powerIn);
        return powerIn;
    }

    private void evaluateLadderCoil(Project project, LadderElement coil, boolean rungPower) {
        coil.setEnergizedIn(rungPower);
        boolean prev = coil.isPrevEdgeInput();
        coil.setPrevEdgeInput(rungPower);

        String op = coil.getOperand();
        switch (coil.getKind()) {
            case NORMAL_COIL -> {
                setOperandValue(op, rungPower ? 1.0 : 0.0);
                coil.setOperandState(rungPower);
                coil.setEnergizedOut(rungPower);
            }
            case NEGATED_COIL -> {
                boolean v = !rungPower;
                setOperandValue(op, v ? 1.0 : 0.0);
                coil.setOperandState(v);
                coil.setEnergizedOut(v);
            }
            case SET_COIL -> {
                if (rungPower) {
                    setOperandValue(op, 1.0);
                }
                boolean cur = getOperandValue(project, op) != 0.0;
                coil.setOperandState(cur);
                coil.setEnergizedOut(rungPower);
            }
            case RESET_COIL -> {
                if (rungPower) {
                    setOperandValue(op, 0.0);
                }
                boolean cur = getOperandValue(project, op) != 0.0;
                coil.setOperandState(cur);
                coil.setEnergizedOut(rungPower);
            }
            case POS_EDGE_COIL -> {
                boolean pulse = rungPower && !prev;
                setOperandValue(op, pulse ? 1.0 : 0.0);
                coil.setOperandState(pulse);
                coil.setEnergizedOut(pulse);
            }
            case NEG_EDGE_COIL -> {
                boolean pulse = !rungPower && prev;
                setOperandValue(op, pulse ? 1.0 : 0.0);
                coil.setOperandState(pulse);
                coil.setEnergizedOut(pulse);
            }
            case ANALOG_COIL -> {
                double srcVal = getOperandValue(project, coil.getSourceOperand());
                if (rungPower) {
                    setOperandValue(op, srcVal);
                }
                double curOut = getOperandValue(project, op);
                coil.setNumericRuntimeValue(curOut);
                coil.setOperandState(rungPower);
                coil.setEnergizedOut(rungPower);
            }
            default -> {}
        }
    }

    private void evaluateBlock(Project project, Page page, BlockInstance b, double dtMs, double simTimeSeconds, boolean propagateFbdWires, int depth) {
        if (depth > 8) return;
        List<PinInstance> ins = b.getInputs();
        if (propagateFbdWires) {
            for (int i = 0; i < ins.size(); i++) {
                PinInstance inPin = ins.get(i);
                Wire w = page.findWireToInputPin(b.getId(), i);
                if (w != null) {
                    BlockInstance srcBlock = page.findBlockById(w.getSourceBlockId());
                    if (srcBlock != null && w.getSourcePinIndex() < srcBlock.getOutputs().size()) {
                        PinInstance outPin = srcBlock.getOutputs().get(w.getSourcePinIndex());
                        double converted = DataType.convertValue(outPin.getRuntimeValue(), outPin.getDataType(), inPin.getDataType());
                        inPin.setRuntimeValue(converted);
                    }
                } else {
                    boolean drivenByVisualButton = false;
                    String targetRef = b.getName() + "." + inPin.getShortName();
                    for (GraphicElement ge : page.getGraphics()) {
                        if (ge.getKind() == GraphicElement.Kind.VISUAL_BUTTON
                                && ge.getBoundTargetPin().equalsIgnoreCase(targetRef)) {
                            drivenByVisualButton = true;
                            break;
                        }
                    }
                    if (!drivenByVisualButton) {
                        inPin.setRuntimeValue(inPin.getDataType().toNumericValue(inPin.getConstantValue()));
                    }
                }
            }
        }

        boolean hasEn = !ins.isEmpty() && ins.getFirst().getShortName().equals("En");
        boolean enHigh = !hasEn || (ins.getFirst().getRuntimeValue() != 0.0);
        boolean enRisingEdge = enHigh && !b.isPrevEnState();
        b.setPrevEnState(enHigh);

        String defId = b.getDefinitionId();

        if (defId.equals("Main.Execution.WriteReg")) {
            String regName = b.getCustomProperties().getOrDefault("RegisterName", "REG_0");
            String trigMode = b.getCustomProperties().getOrDefault("TriggerMode", "Continuous (While En=1)");
            boolean shouldWrite = trigMode.startsWith("Rising") ? enRisingEdge : enHigh;
            if (shouldWrite && ins.size() > 1) {
                double val = ins.get(1).getRuntimeValue();
                setOperandValue(regName, val);
                if (!b.getOutputs().isEmpty()) {
                    PinInstance outPin = b.getOutputs().getFirst();
                    outPin.setRuntimeValue(DataType.convertValue(val, ins.get(1).getDataType(), outPin.getDataType()));
                }
            } else if (!b.getOutputs().isEmpty()) {
                PinInstance outPin = b.getOutputs().getFirst();
                double cur = getOperandValue(project, regName);
                outPin.setRuntimeValue(DataType.convertValue(cur, DataType.FLOAT, outPin.getDataType()));
            }
            return;
        }
        if (defId.equals("Main.Execution.ReadReg")) {
            String regName = b.getCustomProperties().getOrDefault("RegisterName", "REG_0");
            String trigMode = b.getCustomProperties().getOrDefault("TriggerMode", "Continuous (While En=1)");
            boolean shouldRead = trigMode.startsWith("Rising") ? enRisingEdge : enHigh;
            if (shouldRead && !b.getOutputs().isEmpty()) {
                PinInstance outPin = b.getOutputs().getFirst();
                double raw = getOperandValue(project, regName);
                outPin.setRuntimeValue(DataType.convertValue(raw, DataType.FLOAT, outPin.getDataType()));
            }
            return;
        }

        if (!enHigh) {
            return;
        }

        if (defId.startsWith("Macro.User.")) {
            evaluateMacroBlock(project, b, dtMs, simTimeSeconds, depth);
            return;
        }

        List<PinInstance> outs = b.getOutputs();
        switch (defId) {
            case "IO.DI" -> {
                String ch = b.getCustomProperties().getOrDefault("Channel", "DI0");
                if (!outs.isEmpty()) {
                    outs.getFirst().setRuntimeValue(getOperandValue(project, ch) != 0.0 ? 1.0 : 0.0);
                }
            }
            case "IO.DO" -> {
                String ch = b.getCustomProperties().getOrDefault("Channel", "DO0");
                double v = (ins.size() > 1 && ins.get(1).getRuntimeValue() != 0.0) ? 1.0 : 0.0;
                setOperandValue(ch, v);
                if (!outs.isEmpty()) {
                    outs.getFirst().setRuntimeValue(v);
                }
            }
            case "IO.AI" -> {
                String ch = b.getCustomProperties().getOrDefault("Channel", "AI0");
                if (!outs.isEmpty()) {
                    outs.getFirst().setRuntimeValue(getOperandValue(project, ch));
                }
            }
            case "IO.AO" -> {
                String ch = b.getCustomProperties().getOrDefault("Channel", "AO0");
                double v = ins.size() > 1 ? ins.get(1).getRuntimeValue() : 0.0;
                setOperandValue(ch, v);
                if (!outs.isEmpty()) {
                    outs.getFirst().setRuntimeValue(1.0);
                }
            }
            case "IO.PWM" -> {
                String ch = b.getCustomProperties().getOrDefault("Channel", "PWM0");
                double duty = ins.size() > 2 ? ins.get(2).getRuntimeValue() : 0.0;
                setOperandValue(ch, duty);
                if (!outs.isEmpty()) {
                    outs.getFirst().setRuntimeValue(duty > 0.0 ? 1.0 : 0.0);
                }
            }
            case "IO.8xDI" -> {
                for (int i = 0; i < outs.size(); i++) {
                    String diName = "DI" + i;
                    Double ov = virtualRegisters.get(diName);
                    if (ov != null) {
                        outs.get(i).setRuntimeValue(ov);
                    }
                }
            }
            case "IO.8xDO" -> {
                for (int i = 1; i < ins.size(); i++) {
                    String doName = "DO" + (i - 1);
                    setOperandValue(doName, ins.get(i).getRuntimeValue());
                }
            }
            case "Bool.And", "Bool.Nand" -> {
                boolean res = true;
                for (int i = 1; i < ins.size(); i++) {
                    res &= (ins.get(i).getRuntimeValue() != 0.0);
                }
                if (defId.endsWith("Nand")) res = !res;
                outs.getFirst().setRuntimeValue(res ? 1.0 : 0.0);
            }
            case "Bool.Or", "Bool.Nor" -> {
                boolean res = false;
                for (int i = 1; i < ins.size(); i++) {
                    res |= (ins.get(i).getRuntimeValue() != 0.0);
                }
                if (defId.endsWith("Nor")) res = !res;
                outs.getFirst().setRuntimeValue(res ? 1.0 : 0.0);
            }
            case "Bool.Xor", "Bool.Xnor" -> {
                boolean res = false;
                for (int i = 1; i < ins.size(); i++) {
                    res ^= (ins.get(i).getRuntimeValue() != 0.0);
                }
                if (defId.endsWith("Xnor")) res = !res;
                outs.getFirst().setRuntimeValue(res ? 1.0 : 0.0);
            }
            case "Bool.Not" -> {
                boolean x = ins.get(1).getRuntimeValue() != 0.0;
                outs.getFirst().setRuntimeValue(x ? 0.0 : 1.0);
            }
            case "Bool.RS" -> {
                boolean s = ins.get(1).getRuntimeValue() != 0.0;
                boolean r = ins.get(2).getRuntimeValue() != 0.0;
                boolean q = b.getInternalState1() != 0.0;
                if (r) q = false;
                else if (s) q = true;
                b.setInternalState1(q ? 1.0 : 0.0);
                outs.get(0).setRuntimeValue(q ? 1.0 : 0.0);
                outs.get(1).setRuntimeValue(q ? 0.0 : 1.0);
            }
            case "Bool.SR" -> {
                boolean s = ins.get(1).getRuntimeValue() != 0.0;
                boolean r = ins.get(2).getRuntimeValue() != 0.0;
                boolean q = b.getInternalState1() != 0.0;
                if (s) q = true;
                else if (r) q = false;
                b.setInternalState1(q ? 1.0 : 0.0);
                outs.get(0).setRuntimeValue(q ? 1.0 : 0.0);
                outs.get(1).setRuntimeValue(q ? 0.0 : 1.0);
            }
            case "Math.Add" -> {
                double sum = 0.0;
                for (int i = 1; i < ins.size(); i++) sum += ins.get(i).getRuntimeValue();
                outs.getFirst().setRuntimeValue(DataType.convertValue(sum, DataType.FLOAT, outs.getFirst().getDataType()));
            }
            case "Math.Sub" -> {
                double res = ins.size() > 1 ? ins.get(1).getRuntimeValue() : 0.0;
                for (int i = 2; i < ins.size(); i++) res -= ins.get(i).getRuntimeValue();
                outs.getFirst().setRuntimeValue(DataType.convertValue(res, DataType.FLOAT, outs.getFirst().getDataType()));
            }
            case "Math.Mul" -> {
                double prod = 1.0;
                for (int i = 1; i < ins.size(); i++) prod *= ins.get(i).getRuntimeValue();
                outs.getFirst().setRuntimeValue(DataType.convertValue(prod, DataType.FLOAT, outs.getFirst().getDataType()));
            }
            case "Math.Div" -> {
                double a = ins.get(1).getRuntimeValue();
                double d = ins.get(2).getRuntimeValue();
                if (Math.abs(d) < 1e-12) {
                    outs.get(0).setRuntimeValue(0.0);
                    outs.get(1).setRuntimeValue(1.0);
                } else {
                    outs.get(0).setRuntimeValue(DataType.convertValue(a / d, DataType.FLOAT, outs.get(0).getDataType()));
                    outs.get(1).setRuntimeValue(0.0);
                }
            }
            case "Math.Mod" -> {
                long a = (long) ins.get(1).getRuntimeValue();
                long d = (long) ins.get(2).getRuntimeValue();
                if (d == 0) {
                    outs.get(0).setRuntimeValue(0.0);
                    outs.get(1).setRuntimeValue(1.0);
                } else {
                    outs.get(0).setRuntimeValue((double) (a % d));
                    outs.get(1).setRuntimeValue(0.0);
                }
            }
            case "Math.Min" -> {
                double m = ins.get(1).getRuntimeValue();
                for (int i = 2; i < ins.size(); i++) m = Math.min(m, ins.get(i).getRuntimeValue());
                outs.getFirst().setRuntimeValue(m);
            }
            case "Math.Max" -> {
                double m = ins.get(1).getRuntimeValue();
                for (int i = 2; i < ins.size(); i++) m = Math.max(m, ins.get(i).getRuntimeValue());
                outs.getFirst().setRuntimeValue(m);
            }
            case "Math.Abs" -> outs.getFirst().setRuntimeValue(Math.abs(ins.get(1).getRuntimeValue()));
            case "Math.Neg" -> outs.getFirst().setRuntimeValue(-ins.get(1).getRuntimeValue());
            case "Math.Sqrt" -> outs.getFirst().setRuntimeValue(Math.sqrt(Math.max(0.0, ins.get(1).getRuntimeValue())));
            case "Math.Sin" -> outs.getFirst().setRuntimeValue(Math.sin(ins.get(1).getRuntimeValue()));
            case "Math.Cos" -> outs.getFirst().setRuntimeValue(Math.cos(ins.get(1).getRuntimeValue()));
            case "Math.Clamp" -> {
                double v = ins.get(1).getRuntimeValue();
                double lo = ins.get(2).getRuntimeValue();
                double hi = ins.get(3).getRuntimeValue();
                outs.getFirst().setRuntimeValue(Math.clamp(v, Math.min(lo, hi), Math.max(lo, hi)));
            }
            case "Math.Scale" -> {
                double inVal = ins.get(1).getRuntimeValue();
                double iLo = ins.get(2).getRuntimeValue();
                double iHi = ins.get(3).getRuntimeValue();
                double oLo = ins.get(4).getRuntimeValue();
                double oHi = ins.get(5).getRuntimeValue();
                double span = iHi - iLo;
                double scaled = (Math.abs(span) < 1e-12) ? oLo : (oLo + ((inVal - iLo) / span) * (oHi - oLo));
                outs.getFirst().setRuntimeValue(DataType.convertValue(scaled, DataType.FLOAT, outs.getFirst().getDataType()));
            }
            case "Main.Comparators.EQ" -> outs.getFirst().setRuntimeValue(Math.abs(ins.get(1).getRuntimeValue() - ins.get(2).getRuntimeValue()) < 1e-6 ? 1.0 : 0.0);
            case "Main.Comparators.NE" -> outs.getFirst().setRuntimeValue(Math.abs(ins.get(1).getRuntimeValue() - ins.get(2).getRuntimeValue()) >= 1e-6 ? 1.0 : 0.0);
            case "Main.Comparators.GT" -> outs.getFirst().setRuntimeValue(ins.get(1).getRuntimeValue() > ins.get(2).getRuntimeValue() ? 1.0 : 0.0);
            case "Main.Comparators.GE" -> outs.getFirst().setRuntimeValue(ins.get(1).getRuntimeValue() >= ins.get(2).getRuntimeValue() ? 1.0 : 0.0);
            case "Main.Comparators.LT" -> outs.getFirst().setRuntimeValue(ins.get(1).getRuntimeValue() < ins.get(2).getRuntimeValue() ? 1.0 : 0.0);
            case "Main.Comparators.LE" -> outs.getFirst().setRuntimeValue(ins.get(1).getRuntimeValue() <= ins.get(2).getRuntimeValue() ? 1.0 : 0.0);
            case "Main.Switches.Sel" -> {
                boolean g = ins.get(1).getRuntimeValue() != 0.0;
                outs.getFirst().setRuntimeValue(g ? ins.get(3).getRuntimeValue() : ins.get(2).getRuntimeValue());
            }
            case "Main.Switches.Mux" -> {
                int adr = (int) ins.get(1).getRuntimeValue();
                int channelCount = ins.size() - 2;
                if (channelCount > 0) {
                    int idx = Math.clamp(adr, 0, channelCount - 1);
                    outs.getFirst().setRuntimeValue(ins.get(2 + idx).getRuntimeValue());
                }
            }
            case "Main.Timers.Timer" -> {
                boolean in = ins.get(1).getRuntimeValue() != 0.0;
                double pt = Math.max(0.0, ins.get(2).getRuntimeValue());
                double et = b.getInternalState1();
                if (in) {
                    et = Math.min(pt, et + dtMs);
                } else {
                    et = 0.0;
                }
                b.setInternalState1(et);
                outs.get(0).setRuntimeValue((in && et >= pt) ? 1.0 : 0.0);
                outs.get(1).setRuntimeValue((int) et);
            }
            case "Main.Timers.PulseGen" -> {
                double tHi = Math.max(1.0, ins.get(1).getRuntimeValue());
                double tLo = Math.max(1.0, ins.get(2).getRuntimeValue());
                double cycle = tHi + tLo;
                double pos = (b.getInternalState1() + dtMs) % cycle;
                b.setInternalState1(pos);
                outs.getFirst().setRuntimeValue(pos < tHi ? 1.0 : 0.0);
            }
            case "Main.Timers.SinglePulse" -> {
                boolean in = ins.get(1).getRuntimeValue() != 0.0;
                boolean prev = b.isPrevTriggerState();
                b.setPrevTriggerState(in);
                outs.getFirst().setRuntimeValue((in && !prev) ? 1.0 : 0.0);
            }
            case "Main.Timers.DateTime" -> {
                outs.getFirst().setRuntimeValue((double) (System.currentTimeMillis() / 1000L));
            }
            case "Main.Timers.DecodeDate" -> {
                long ts = (long) (ins.size() > 1 ? ins.get(1).getRuntimeValue() : 0.0);
                ZonedDateTime dt = Instant.ofEpochSecond(ts).atZone(ZoneOffset.UTC);
                if (outs.size() > 0) outs.get(0).setRuntimeValue(dt.getYear());
                if (outs.size() > 1) outs.get(1).setRuntimeValue(dt.getMonthValue());
                if (outs.size() > 2) outs.get(2).setRuntimeValue(dt.getDayOfMonth());
                if (outs.size() > 3) outs.get(3).setRuntimeValue(dt.getHour());
                if (outs.size() > 4) outs.get(4).setRuntimeValue(dt.getMinute());
                if (outs.size() > 5) outs.get(5).setRuntimeValue(dt.getSecond());
            }
            case "Main.Conversion.PackBits" -> {
                long packed = 0;
                for (int i = 1; i < ins.size(); i++) {
                    if (ins.get(i).getRuntimeValue() != 0.0) {
                        packed |= (1L << (i - 1));
                    }
                }
                outs.getFirst().setRuntimeValue(DataType.convertValue(packed, DataType.LONG, outs.getFirst().getDataType()));
            }
            case "Main.Conversion.UnpackBits" -> {
                long val = (long) (ins.size() > 1 ? ins.get(1).getRuntimeValue() : 0.0);
                for (int i = 0; i < outs.size(); i++) {
                    boolean bitSet = ((val >> i) & 1L) != 0L;
                    outs.get(i).setRuntimeValue(bitSet ? 1.0 : 0.0);
                }
            }
            case "Visual.Vars.BoolVar", "Visual.Vars.IntVar", "Visual.Vars.LongVar", "Visual.Vars.FloatVar" -> {
                PinInstance out = outs.getFirst();
                double v = out.getDataType().toNumericValue(b.getCustomProperties().getOrDefault("CurrentValue", "0"));
                out.setRuntimeValue(v);
            }
            default -> {
            }
        }
    }

    private void evaluateMacroBlock(Project project, BlockInstance macroBlock, double dtMs, double simTimeSeconds, int depth) {
        if (project == null) return;
        String macroCleanName = macroBlock.getDefinitionId().substring("Macro.User.".length());
        Page macroDefPage = null;
        for (Page p : project.getPages()) {
            if (p.isMacro() && p.getCleanName().equalsIgnoreCase(macroCleanName)) {
                macroDefPage = p;
                break;
            }
        }
        if (macroDefPage == null) return;

        final Page defRef = macroDefPage;
        Page rtPage = macroRuntimePages.compute(macroBlock.getId(), (k, existing) -> {
            if (existing == null || existing.getBlocks().size() != defRef.getBlocks().size()) {
                return defRef.copy();
            }
            return existing;
        });

        // 1. Copy macro instance input pins (after En at index 0) into MacroIn block outputs on rtPage
        List<BlockInstance> macroInBlocks = new ArrayList<>();
        List<BlockInstance> macroOutBlocks = new ArrayList<>();
        List<BlockInstance> innerExecBlocks = new ArrayList<>();

        for (BlockInstance ib : rtPage.getBlocks()) {
            if ("Macro.MacroIn".equals(ib.getDefinitionId())) {
                macroInBlocks.add(ib);
            } else if ("Macro.MacroOut".equals(ib.getDefinitionId())) {
                macroOutBlocks.add(ib);
            } else {
                innerExecBlocks.add(ib);
            }
        }
        macroInBlocks.sort(Comparator.comparingInt(BlockInstance::getOrder).thenComparingInt(BlockInstance::getGridY));
        macroOutBlocks.sort(Comparator.comparingInt(BlockInstance::getOrder).thenComparingInt(BlockInstance::getGridY));
        innerExecBlocks.sort(Comparator.comparingInt(BlockInstance::getOrder));

        int macroInputPinIdx = 1; // index 0 is En
        for (BlockInstance inBlock : macroInBlocks) {
            for (PinInstance outPin : inBlock.getOutputs()) {
                if (macroInputPinIdx < macroBlock.getInputs().size()) {
                    PinInstance srcIn = macroBlock.getInputs().get(macroInputPinIdx++);
                    double converted = DataType.convertValue(srcIn.getRuntimeValue(), srcIn.getDataType(), outPin.getDataType());
                    outPin.setRuntimeValue(converted);
                }
            }
        }

        // 2. Evaluate all inner blocks in execution order
        for (BlockInstance inner : innerExecBlocks) {
            evaluateBlock(project, rtPage, inner, dtMs, simTimeSeconds, true, depth + 1);
        }

        // 3. Propagate wires into MacroOut blocks and copy their input values to the macro instance's output pins
        int macroOutputPinIdx = 0;
        for (BlockInstance outBlock : macroOutBlocks) {
            List<PinInstance> mIns = outBlock.getInputs();
            for (int i = 0; i < mIns.size(); i++) {
                PinInstance inPin = mIns.get(i);
                Wire w = rtPage.findWireToInputPin(outBlock.getId(), i);
                if (w != null) {
                    BlockInstance srcBlock = rtPage.findBlockById(w.getSourceBlockId());
                    if (srcBlock != null && w.getSourcePinIndex() < srcBlock.getOutputs().size()) {
                        PinInstance srcOut = srcBlock.getOutputs().get(w.getSourcePinIndex());
                        double converted = DataType.convertValue(srcOut.getRuntimeValue(), srcOut.getDataType(), inPin.getDataType());
                        inPin.setRuntimeValue(converted);
                    }
                } else {
                    inPin.setRuntimeValue(inPin.getDataType().toNumericValue(inPin.getConstantValue()));
                }

                if (macroOutputPinIdx < macroBlock.getOutputs().size()) {
                    PinInstance dstOut = macroBlock.getOutputs().get(macroOutputPinIdx++);
                    double converted = DataType.convertValue(inPin.getRuntimeValue(), inPin.getDataType(), dstOut.getDataType());
                    dstOut.setRuntimeValue(converted);
                }
            }
        }

        // 4. Mirror runtime pin states onto macroDefPage so live inspection of the *Macro tab displays live values
        List<BlockInstance> defBlocks = macroDefPage.getBlocks();
        List<BlockInstance> rtBlocks = rtPage.getBlocks();
        int count = Math.min(defBlocks.size(), rtBlocks.size());
        for (int i = 0; i < count; i++) {
            BlockInstance db = defBlocks.get(i);
            BlockInstance rb = rtBlocks.get(i);
            for (int p = 0; p < Math.min(db.getInputs().size(), rb.getInputs().size()); p++) {
                db.getInputs().get(p).setRuntimeValue(rb.getInputs().get(p).getRuntimeValue());
            }
            for (int p = 0; p < Math.min(db.getOutputs().size(), rb.getOutputs().size()); p++) {
                db.getOutputs().get(p).setRuntimeValue(rb.getOutputs().get(p).getRuntimeValue());
            }
        }
    }

    private void requestUiRepaint() {
        if (uiRepaintCallback != null) {
            SwingUtilities.invokeLater(uiRepaintCallback);
        }
    }
}
