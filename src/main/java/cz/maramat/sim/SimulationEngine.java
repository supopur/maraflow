package cz.maramat.sim;

import cz.maramat.model.BlockInstance;
import cz.maramat.model.DataType;
import cz.maramat.model.GraphicElement;
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
        if (project == null) return;
        for (Page page : project.getPages()) {
            for (BlockInstance b : page.getBlocks()) {
                b.resetRuntimeState();
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
        for (Page page : project.getPages()) {
            if (page.isMacro()) continue;

            // Apply any active Visual Buttons on this page
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
            // Sort strictly by ascending Order (O:) so backward wires act as z^-1 one-cycle delay
            threadBlocks.sort(Comparator.comparingInt(BlockInstance::getOrder));

            for (BlockInstance b : threadBlocks) {
                evaluateBlock(page, b, dtMs, simTimeSeconds);
            }
        }
    }

    private void evaluateBlock(Page page, BlockInstance b, double dtMs, double simTimeSeconds) {
        // 1. Propagate inputs from wires or constants
        List<PinInstance> ins = b.getInputs();
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
                // Only reset to constant if not driven by a Visual Button
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

        // 2. Check En pin
        boolean hasEn = !ins.isEmpty() && ins.getFirst().getShortName().equals("En");
        boolean enHigh = !hasEn || (ins.getFirst().getRuntimeValue() != 0.0);
        boolean enRisingEdge = enHigh && !b.isPrevEnState();
        b.setPrevEnState(enHigh);

        String defId = b.getDefinitionId();

        // Edge-triggered Register blocks
        if (defId.equals("Main.Execution.WriteReg")) {
            String regName = b.getCustomProperties().getOrDefault("RegisterName", "REG_0");
            if (enRisingEdge && ins.size() > 1) {
                double val = ins.get(1).getRuntimeValue();
                virtualRegisters.put(regName, val);
                if (!b.getOutputs().isEmpty()) {
                    b.getOutputs().getFirst().setRuntimeValue(val);
                }
            } else if (!b.getOutputs().isEmpty()) {
                b.getOutputs().getFirst().setRuntimeValue(virtualRegisters.getOrDefault(regName, 0.0));
            }
            return;
        }
        if (defId.equals("Main.Execution.ReadReg")) {
            String regName = b.getCustomProperties().getOrDefault("RegisterName", "REG_0");
            if (enRisingEdge && !b.getOutputs().isEmpty()) {
                b.getOutputs().getFirst().setRuntimeValue(virtualRegisters.getOrDefault(regName, 0.0));
            }
            return;
        }

        // Standard execution gating
        if (!enHigh) {
            return;
        }

        // 3. Evaluate block logic by definition
        List<PinInstance> outs = b.getOutputs();
        switch (defId) {
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
                // outputs: Yr, Mon, Day, Hr, Min, Sec
                if (outs.size() > 0) outs.get(0).setRuntimeValue(dt.getYear());
                if (outs.size() > 1) outs.get(1).setRuntimeValue(dt.getMonthValue());
                if (outs.size() > 2) outs.get(2).setRuntimeValue(dt.getDayOfMonth());
                if (outs.size() > 3) outs.get(3).setRuntimeValue(dt.getHour());
                if (outs.size() > 4) outs.get(4).setRuntimeValue(dt.getMinute());
                if (outs.size() > 5) outs.get(5).setRuntimeValue(dt.getSecond());
            }
            case "Main.Conversion.PackBits" -> {
                long packed = 0;
                // ins[0] is En; bits are ins[1]..ins[size-1]
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
                // Default pass-through or keep current state
            }
        }
    }

    private void requestUiRepaint() {
        if (uiRepaintCallback != null) {
            SwingUtilities.invokeLater(uiRepaintCallback);
        }
    }
}
