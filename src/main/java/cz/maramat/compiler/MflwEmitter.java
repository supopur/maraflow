package cz.maramat.compiler;

import cz.maramat.model.BlockInstance;
import cz.maramat.model.DataType;
import cz.maramat.model.IecLanguage;
import cz.maramat.model.Page;
import cz.maramat.model.PinInstance;
import cz.maramat.model.Project;
import cz.maramat.model.StVariable;
import cz.maramat.model.Wire;
import cz.maramat.vm.NativeMaraVm;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import cz.maramat.model.LadderBranch;
import cz.maramat.model.LadderElement;
import cz.maramat.model.LadderRung;

// Emits a valid MFLW v1 binary image and SlotSymbolTable for MaraVm (Java & STM32F407).
public final class MflwEmitter {

    public record EmittedProgram(
            byte[] image,
            Map<String, Integer> operandToSlot,
            Map<Integer, String> slotToOperand
    ) {}

    private final int[] nextSlotIndex = new int[4];
    private int nextStateIdx = 0;
    private int nextMovAvgIdx = 0;
    private final Map<String, Integer> arrayNameToIdx = new LinkedHashMap<>();
    private final Map<String, Integer> regNameToSlot = new LinkedHashMap<>();
    private final Map<String, Integer> operandToSlot = new LinkedHashMap<>();
    private final Map<Integer, String> slotToOperand = new LinkedHashMap<>();
    private final List<InitEntry> initEntries = new ArrayList<>();
    private Project currentProject;

    private record InitEntry(int slotId, long rawBits) {}

    private MflwEmitter() {}

    public static EmittedProgram emit(Project project) {
        return new MflwEmitter().build(project);
    }

    private EmittedProgram build(Project project) {
        this.currentProject = project;
        // 1. Allocate output slots for all FBD blocks and record symbol mappings
        Map<String, Integer> blockPinSlot = new HashMap<>();
        for (Page page : project.getPages()) {
            if (page.isMacro() || page.getLanguage() != IecLanguage.FBD) continue;
            for (BlockInstance b : page.getAllBlockInstances()) {
                for (int i = 0; i < b.getOutputs().size(); i++) {
                    PinInstance out = b.getOutputs().get(i);
                    int slotId = allocSlot(out.getDataType());
                    String key = b.getName() + "." + out.getShortName();
                    blockPinSlot.put(b.getId() + ":OUT:" + i, slotId);
                    operandToSlot.put(key, slotId);
                    slotToOperand.put(slotId, key);
                    if (b.isInputRef()) {
                        String ioCh = b.getIoRefLabel();
                        if (!ioCh.isBlank()) {
                            operandToSlot.putIfAbsent(ioCh, slotId);
                        }
                    }

                    // Visual.Vars initial value
                    if (b.getDefinitionId().startsWith("Visual.Vars.")) {
                        String valStr = b.getCustomProperties().getOrDefault("CurrentValue", out.getConstantValue());
                        if (out.isNegated() && out.getDataType() == DataType.BOOL) {
                            double v = out.getDataType().toNumericValue(valStr);
                            valStr = (v == 0.0) ? "1" : "0";
                        }
                        addInitEntry(slotId, out.getDataType(), valStr);
                    } else if (out.isNegated() && out.getDataType() == DataType.BOOL) {
                        addInitEntry(slotId, DataType.BOOL, "1");
                    }
                }
            }
        }

        // 2. Index incoming wires per (targetBlockId, targetPinIndex)
        Map<String, Integer> wiredSourceSlot = new HashMap<>();
        for (Page page : project.getPages()) {
            if (page.isMacro() || page.getLanguage() != IecLanguage.FBD) continue;
            for (Wire w : page.getWires()) {
                Integer srcSlot = blockPinSlot.get(w.getSourceBlockId() + ":OUT:" + w.getSourcePinIndex());
                if (srcSlot != null) {
                    wiredSourceSlot.put(w.getTargetBlockId() + ":IN:" + w.getTargetPinIndex(), srcSlot);
                }
            }
        }

        // 3. Emit bytecode per thread (1..5)
        ByteArrayOutputStream[] threadStreams = new ByteArrayOutputStream[NativeMaraVm.THREAD_COUNT];
        for (int t = 0; t < NativeMaraVm.THREAD_COUNT; t++) {
            threadStreams[t] = new ByteArrayOutputStream();
        }

        for (Page page : project.getPages()) {
            if (page.isMacro()) continue;
            if (page.getLanguage() == IecLanguage.ST) {
                emitStPage(threadStreams[Math.clamp(page.getStThreadId(), 1, 5)], page);
                continue;
            }
            if (page.getLanguage() == IecLanguage.LD) {
                emitLdPage(threadStreams, page);
                continue;
            }
            if (page.getLanguage() != IecLanguage.FBD) continue;
            List<BlockInstance> sorted = new ArrayList<>(page.getAllBlockInstances());
            sorted.sort(Comparator.comparingInt(BlockInstance::getOrder));
            for (BlockInstance b : sorted) {
                int tid = Math.clamp(b.getThreadId(), 1, 5);
                emitFbdBlock(threadStreams[tid], b, blockPinSlot, wiredSourceSlot);
            }
        }

        for (int t = 1; t < NativeMaraVm.THREAD_COUNT; t++) {
            writeWord(threadStreams[t], NativeMaraVm.OP_HALT, 0, NativeMaraVm.SLOT_ALWAYS_ENABLED, 0, 0);
        }

        // 4. Assemble MFLW image: [64B Header] [InitTable] [Thread 1..5 Bytecode]
        int initTableOffset = NativeMaraVm.HEADER_SIZE_BYTES;
        int initTableBytes = initEntries.size() * 12;
        int cursor = initTableOffset + initTableBytes;
        int[] threadOffsets = new int[NativeMaraVm.THREAD_COUNT];
        for (int t = 1; t < NativeMaraVm.THREAD_COUNT; t++) {
            threadOffsets[t] = cursor;
            cursor += threadStreams[t].size();
        }

        ByteBuffer img = ByteBuffer.allocate(cursor).order(ByteOrder.LITTLE_ENDIAN);
        img.putInt(0, NativeMaraVm.MAGIC_MFLW);
        img.putShort(4, (short) NativeMaraVm.FORMAT_VERSION);
        img.putShort(6, (short) 0x0407);
        img.putInt(8, cursor);
        // CRC32 filled after payload is written
        img.putInt(16, project.getProjectName() != null ? project.getProjectName().hashCode() : 0);
        img.putShort(20, (short) nextSlotIndex[NativeMaraVm.TYPE_BOOL]);
        img.putShort(22, (short) nextSlotIndex[NativeMaraVm.TYPE_INT]);
        img.putShort(24, (short) nextSlotIndex[NativeMaraVm.TYPE_LONG]);
        img.putShort(26, (short) nextSlotIndex[NativeMaraVm.TYPE_FLOAT]);
        img.putShort(28, (short) nextStateIdx);
        img.putInt(36, initTableOffset);
        img.putInt(40, initEntries.size());
        for (int t = 0; t < NativeMaraVm.THREAD_COUNT; t++) {
            img.putInt(44 + (t * 4), threadOffsets[t]);
        }

        int pos = initTableOffset;
        for (InitEntry e : initEntries) {
            img.putShort(pos, (short) e.slotId);
            img.putShort(pos + 2, (short) 0);
            img.putLong(pos + 4, e.rawBits);
            pos += 12;
        }
        for (int t = 1; t < NativeMaraVm.THREAD_COUNT; t++) {
            byte[] bc = threadStreams[t].toByteArray();
            System.arraycopy(bc, 0, img.array(), threadOffsets[t], bc.length);
        }

        byte[] raw = img.array();
        int crc32 = NativeMaraVm.calcCrc32(raw, NativeMaraVm.HEADER_SIZE_BYTES, raw.length - NativeMaraVm.HEADER_SIZE_BYTES);
        img.putInt(12, crc32);

        return new EmittedProgram(raw, Map.copyOf(operandToSlot), Map.copyOf(slotToOperand));
    }

    private void emitFbdBlock(ByteArrayOutputStream out, BlockInstance b,
                              Map<String, Integer> blockPinSlot, Map<String, Integer> wiredSourceSlot) {
        String id = b.getDefinitionId();
        if (id == null || id.startsWith("Visual.Vars.")) return;

        int[] inSlots = resolveAllInputs(out, b, wiredSourceSlot);
        emitFbdBlockWithInputs(out, b, inSlots, blockPinSlot);
    }

    private void emitFbdBlockWithInputs(ByteArrayOutputStream out, BlockInstance b,
                                        int[] inSlots, Map<String, Integer> blockPinSlot) {
        String id = b.getDefinitionId();
        if (id == null || id.startsWith("Visual.Vars.")) return;

        boolean hasEn = !b.getInputs().isEmpty() && "En".equals(b.getInputs().get(0).getShortName());
        int enSlot = hasEn ? inSlots[0] : NativeMaraVm.SLOT_ALWAYS_ENABLED;
        int firstArg = hasEn ? 1 : 0;

        switch (id) {
            case "Bool.And", "Bool.Nand", "Bool.Or", "Bool.Nor", "Bool.Xor", "Bool.Xnor" -> {
                int op = switch (id) {
                    case "Bool.And" -> NativeMaraVm.OP_AND;
                    case "Bool.Nand" -> NativeMaraVm.OP_NAND;
                    case "Bool.Or" -> NativeMaraVm.OP_OR;
                    case "Bool.Nor" -> NativeMaraVm.OP_NOR;
                    case "Bool.Xor" -> NativeMaraVm.OP_XOR;
                    default -> NativeMaraVm.OP_XNOR;
                };
                int dst = blockPinSlot.getOrDefault(b.getId() + ":OUT:0", 0);
                int argc = Math.max(2, b.getInputs().size() - firstArg);
                int srcA = inSlotAt(inSlots, firstArg);
                writeWord(out, op, argc, enSlot, dst, srcA);
                int extraSlots = argc - 1;
                int extraWords = (extraSlots + 1) / 2;
                for (int w = 0; w < extraWords; w++) {
                    int s1 = (1 + (w * 2) < argc) ? inSlotAt(inSlots, firstArg + 1 + (w * 2)) : 0;
                    int s2 = (2 + (w * 2) < argc) ? inSlotAt(inSlots, firstArg + 2 + (w * 2)) : 0;
                    writeU16(out, s1);
                    writeU16(out, s2);
                }
            }
            case "Bool.Not" -> {
                int dst = blockPinSlot.getOrDefault(b.getId() + ":OUT:0", 0);
                int srcA = inSlotAt(inSlots, firstArg);
                writeWord(out, NativeMaraVm.OP_NOT, 0, enSlot, dst, srcA);
            }
            case "Bool.RS", "Bool.SR" -> {
                int op = id.equals("Bool.RS") ? NativeMaraVm.OP_RS : NativeMaraVm.OP_SR;
                int q = blockPinSlot.getOrDefault(b.getId() + ":OUT:0", 0);
                int nq = blockPinSlot.getOrDefault(b.getId() + ":OUT:1", NativeMaraVm.SLOT_ALWAYS_ENABLED);
                int s = inSlotAt(inSlots, firstArg);
                int r = inSlotAt(inSlots, firstArg + 1);
                boolean qNeg = !b.getOutputs().isEmpty() && b.getOutputs().get(0).isNegated();
                boolean nqNeg = b.getOutputs().size() > 1 && b.getOutputs().get(1).isNegated();
                int latchQ = qNeg ? allocSlot(DataType.BOOL) : q;
                writeWord(out, op, 0, enSlot, latchQ, s);
                writeU16(out, r);
                writeU16(out, nq);
                if (qNeg) {
                    writeWord(out, NativeMaraVm.OP_NOT, 0, enSlot, q, latchQ);
                }
                if (nqNeg && nq != NativeMaraVm.SLOT_ALWAYS_ENABLED) {
                    writeWord(out, NativeMaraVm.OP_NOT, 0, enSlot, nq, nq);
                }
                return;
            }
            case "Math.Add", "Math.Sub", "Math.Mul", "Math.Min", "Math.Max" -> {
                int op = switch (id) {
                    case "Math.Add" -> NativeMaraVm.OP_ADD;
                    case "Math.Sub" -> NativeMaraVm.OP_SUB;
                    case "Math.Mul" -> NativeMaraVm.OP_MUL;
                    case "Math.Min" -> NativeMaraVm.OP_MIN;
                    default -> NativeMaraVm.OP_MAX;
                };
                int dst = blockPinSlot.getOrDefault(b.getId() + ":OUT:0", 0);
                DataType outDt = b.getOutputs().isEmpty() ? DataType.INT : b.getOutputs().getFirst().getDataType();
                int argc = Math.max(2, b.getInputs().size() - firstArg);
                int acc = inSlotAt(inSlots, firstArg);
                for (int i = 1; i < argc; i++) {
                    int rhs = inSlotAt(inSlots, firstArg + i);
                    int target = (i == argc - 1) ? dst : allocSlot(outDt);
                    writeWord(out, op, 0, enSlot, target, acc);
                    writeU16(out, rhs);
                    writeU16(out, 0);
                    acc = target;
                }
            }
            case "Math.Div", "Math.Mod" -> {
                int op = id.equals("Math.Div") ? NativeMaraVm.OP_DIV : NativeMaraVm.OP_MOD;
                int dst = blockPinSlot.getOrDefault(b.getId() + ":OUT:0", 0);
                int err = blockPinSlot.getOrDefault(b.getId() + ":OUT:1", NativeMaraVm.SLOT_ALWAYS_ENABLED);
                int a = inSlotAt(inSlots, firstArg);
                int bIn = inSlotAt(inSlots, firstArg + 1);
                writeWord(out, op, 0, enSlot, dst, a);
                writeU16(out, bIn);
                writeU16(out, err);
                if ("Math.Div".equals(id)) {
                    Integer modSlot = blockPinSlot.get(b.getId() + ":OUT:2");
                    if (modSlot != null) {
                        writeWord(out, NativeMaraVm.OP_MOD, 0, enSlot, modSlot, a);
                        writeU16(out, bIn);
                        writeU16(out, NativeMaraVm.SLOT_ALWAYS_ENABLED);
                    }
                }
            }
            case "Math.Abs", "Math.Neg", "Math.Sqrt", "Math.Sin", "Math.Cos" -> {
                int op = switch (id) {
                    case "Math.Abs" -> NativeMaraVm.OP_ABS;
                    case "Math.Neg" -> NativeMaraVm.OP_NEG;
                    case "Math.Sqrt" -> NativeMaraVm.OP_SQRT;
                    case "Math.Sin" -> NativeMaraVm.OP_SIN;
                    default -> NativeMaraVm.OP_COS;
                };
                int dst = blockPinSlot.getOrDefault(b.getId() + ":OUT:0", 0);
                int a = inSlotAt(inSlots, firstArg);
                writeWord(out, op, 0, enSlot, dst, a);
            }
            case "Math.Clamp" -> {
                int dst = blockPinSlot.getOrDefault(b.getId() + ":OUT:0", 0);
                int inS = inSlotAt(inSlots, firstArg);
                int minS = inSlotAt(inSlots, firstArg + 1);
                int maxS = inSlotAt(inSlots, firstArg + 2);
                writeWord(out, NativeMaraVm.OP_CLAMP, 0, enSlot, dst, inS);
                writeU16(out, minS);
                writeU16(out, maxS);
            }
            case "Math.Scale" -> {
                int dst = blockPinSlot.getOrDefault(b.getId() + ":OUT:0", 0);
                int inS = inSlotAt(inSlots, firstArg);
                int iLo = inSlotAt(inSlots, firstArg + 1);
                int iHi = inSlotAt(inSlots, firstArg + 2);
                int oLo = inSlotAt(inSlots, firstArg + 3);
                int oHi = inSlotAt(inSlots, firstArg + 4);
                writeWord(out, NativeMaraVm.OP_SCALE, 0, enSlot, dst, inS);
                writeU16(out, iLo);
                writeU16(out, iHi);
                writeU16(out, oLo);
                writeU16(out, oHi);
            }
            case "Math.Random" -> {
                int dst = blockPinSlot.getOrDefault(b.getId() + ":OUT:0", 0);
                int trigS = inSlotAt(inSlots, firstArg);
                int minS = inSlotAt(inSlots, firstArg + 1);
                int maxS = inSlotAt(inSlots, firstArg + 2);
                int stIdx = nextStateIdx++;
                writeWord(out, NativeMaraVm.OP_RANDOM, 0, enSlot, dst, trigS);
                writeU16(out, minS);
                writeU16(out, maxS);
                writeU16(out, stIdx);
                writeU16(out, 0);
            }
            case "Math.PID" -> {
                int dst = blockPinSlot.getOrDefault(b.getId() + ":OUT:0", 0);
                int pv = inSlotAt(inSlots, firstArg);
                int sp = inSlotAt(inSlots, firstArg + 1);
                int kp = inSlotAt(inSlots, firstArg + 2);
                int ki = inSlotAt(inSlots, firstArg + 3);
                int kd = inSlotAt(inSlots, firstArg + 4);
                int rst = inSlotAt(inSlots, firstArg + 5);
                int stIdx = nextStateIdx++;
                writeWord(out, NativeMaraVm.OP_PID, 0, enSlot, dst, pv);
                writeU16(out, sp);
                writeU16(out, kp);
                writeU16(out, ki);
                writeU16(out, kd);
                writeU16(out, rst);
                writeU16(out, stIdx);
            }
            case "Main.Comparators.EQ", "Main.Comparators.NE", "Main.Comparators.GT",
                 "Main.Comparators.GE", "Main.Comparators.LT", "Main.Comparators.LE" -> {
                int op = switch (id) {
                    case "Main.Comparators.EQ" -> NativeMaraVm.OP_CMP_EQ;
                    case "Main.Comparators.NE" -> NativeMaraVm.OP_CMP_NE;
                    case "Main.Comparators.GT" -> NativeMaraVm.OP_CMP_GT;
                    case "Main.Comparators.GE" -> NativeMaraVm.OP_CMP_GE;
                    case "Main.Comparators.LT" -> NativeMaraVm.OP_CMP_LT;
                    default -> NativeMaraVm.OP_CMP_LE;
                };
                int dst = blockPinSlot.getOrDefault(b.getId() + ":OUT:0", 0);
                int a = inSlotAt(inSlots, firstArg);
                int bIn = inSlotAt(inSlots, firstArg + 1);
                writeWord(out, op, 0, enSlot, dst, a);
                writeU16(out, bIn);
                writeU16(out, 0);
            }
            case "Main.Comparators.InRange" -> {
                int dst = blockPinSlot.getOrDefault(b.getId() + ":OUT:0", 0);
                int inS = inSlotAt(inSlots, firstArg);
                int minS = inSlotAt(inSlots, firstArg + 1);
                int maxS = inSlotAt(inSlots, firstArg + 2);
                writeWord(out, NativeMaraVm.OP_IN_RANGE, 0, enSlot, dst, inS);
                writeU16(out, minS);
                writeU16(out, maxS);
            }
            case "Main.Comparators.Hysteresis" -> {
                int dst = blockPinSlot.getOrDefault(b.getId() + ":OUT:0", 0);
                int inS = inSlotAt(inSlots, firstArg);
                int loS = inSlotAt(inSlots, firstArg + 1);
                int hiS = inSlotAt(inSlots, firstArg + 2);
                int stIdx = nextStateIdx++;
                writeWord(out, NativeMaraVm.OP_HYSTERESIS, 0, enSlot, dst, inS);
                writeU16(out, loS);
                writeU16(out, hiS);
                writeU16(out, stIdx);
                writeU16(out, 0);
            }
            case "Main.Filters.LowPass" -> {
                int dst = blockPinSlot.getOrDefault(b.getId() + ":OUT:0", 0);
                int inS = inSlotAt(inSlots, firstArg);
                int alpS = inSlotAt(inSlots, firstArg + 1);
                int stIdx = nextStateIdx++;
                writeWord(out, NativeMaraVm.OP_LOWPASS, 0, enSlot, dst, inS);
                writeU16(out, alpS);
                writeU16(out, stIdx);
            }
            case "Main.Filters.MovingAvg" -> {
                int dst = blockPinSlot.getOrDefault(b.getId() + ":OUT:0", 0);
                int inS = inSlotAt(inSlots, firstArg);
                int rstS = inSlotAt(inSlots, firstArg + 1);
                int winSize = 8;
                try {
                    winSize = Math.clamp(Integer.parseInt(b.getCustomProperties().getOrDefault("WindowSize", "8").trim()), 1, 64);
                } catch (NumberFormatException ignored) {}
                int maIdx = nextMovAvgIdx++;
                writeWord(out, NativeMaraVm.OP_MOVING_AVG, winSize, enSlot, dst, inS);
                writeU16(out, rstS);
                writeU16(out, maIdx);
            }
            case "Main.Filters.Debounce" -> {
                int dst = blockPinSlot.getOrDefault(b.getId() + ":OUT:0", 0);
                int inS = inSlotAt(inSlots, firstArg);
                int tmsS = inSlotAt(inSlots, firstArg + 1);
                int stIdx = nextStateIdx++;
                writeWord(out, NativeMaraVm.OP_DEBOUNCE, 0, enSlot, dst, inS);
                writeU16(out, tmsS);
                writeU16(out, stIdx);
            }
            case "Main.Switches.Sel" -> {
                int dst = blockPinSlot.getOrDefault(b.getId() + ":OUT:0", 0);
                int g = inSlotAt(inSlots, firstArg);
                int x0 = inSlotAt(inSlots, firstArg + 1);
                int x1 = inSlotAt(inSlots, firstArg + 2);
                writeWord(out, NativeMaraVm.OP_SEL, 0, enSlot, dst, g);
                writeU16(out, x0);
                writeU16(out, x1);
            }
            case "Main.Switches.Mux" -> {
                int dst = blockPinSlot.getOrDefault(b.getId() + ":OUT:0", 0);
                int selSlot = inSlotAt(inSlots, firstArg);
                int dataCount = Math.max(0, inSlots.length - (firstArg + 1));
                if (dataCount > 0) {
                    int loSlot = allocSlot(DataType.INT);
                    addInitEntry(loSlot, DataType.INT, "0");
                    int hiSlot = allocSlot(DataType.INT);
                    addInitEntry(hiSlot, DataType.INT, Integer.toString(dataCount - 1));
                    int clampedSel = allocSlot(DataType.INT);
                    writeWord(out, NativeMaraVm.OP_CLAMP, 0, enSlot, clampedSel, selSlot);
                    writeU16(out, loSlot);
                    writeU16(out, hiSlot);
                    for (int idx = 0; idx < dataCount; idx++) {
                        int idxConstSlot = allocSlot(DataType.INT);
                        addInitEntry(idxConstSlot, DataType.INT, Integer.toString(idx));
                        int condSlot = allocSlot(DataType.BOOL);
                        writeWord(out, NativeMaraVm.OP_CMP_EQ, 0, enSlot, condSlot, clampedSel);
                        writeU16(out, idxConstSlot);
                        writeU16(out, 0);
                        writeWord(out, NativeMaraVm.OP_MOV, 0, condSlot, dst, inSlotAt(inSlots, firstArg + 1 + idx));
                    }
                }
            }
            case "Main.Switches.Demux" -> {
                int adrSlot = inSlotAt(inSlots, firstArg);
                int inS = inSlotAt(inSlots, firstArg + 1);
                int zeroSlot = allocSlot(DataType.INT);
                addInitEntry(zeroSlot, DataType.INT, "0");
                for (int idx = 0; idx < b.getOutputs().size(); idx++) {
                    int outSlot = blockPinSlot.getOrDefault(b.getId() + ":OUT:" + idx, 0);
                    int idxConstSlot = allocSlot(DataType.INT);
                    addInitEntry(idxConstSlot, DataType.INT, Integer.toString(idx));
                    int eqSlot = allocSlot(DataType.BOOL);
                    writeWord(out, NativeMaraVm.OP_CMP_EQ, 0, enSlot, eqSlot, adrSlot);
                    writeU16(out, idxConstSlot);
                    writeU16(out, 0);
                    writeWord(out, NativeMaraVm.OP_MOV, 0, enSlot, outSlot, zeroSlot);
                    writeWord(out, NativeMaraVm.OP_MOV, 0, eqSlot, outSlot, inS);
                }
            }
            case "Main.Timers.Timer" -> {
                int q = blockPinSlot.getOrDefault(b.getId() + ":OUT:0", 0);
                int et = blockPinSlot.getOrDefault(b.getId() + ":OUT:1", NativeMaraVm.SLOT_ALWAYS_ENABLED);
                int inS = inSlotAt(inSlots, firstArg);
                int ptS = inSlotAt(inSlots, firstArg + 1);
                String modeStr = b.getCustomProperties().getOrDefault("TimerMode", "TON").trim().toUpperCase();
                int modeFlag = "TOF".equals(modeStr) ? 1 : ("TP".equals(modeStr) ? 2 : 0);
                int stIdx = nextStateIdx++;
                writeWord(out, NativeMaraVm.OP_TON, modeFlag, enSlot, q, inS);
                writeU16(out, ptS);
                writeU16(out, et);
                writeU16(out, stIdx);
                writeU16(out, 0);
            }
            case "Main.Timers.PulseGen" -> {
                int q = blockPinSlot.getOrDefault(b.getId() + ":OUT:0", 0);
                int tHiS = inSlotAt(inSlots, firstArg);
                int tLoS = inSlotAt(inSlots, firstArg + 1);
                int stIdx = nextStateIdx++;
                writeWord(out, NativeMaraVm.OP_PULSE_GEN, 0, enSlot, q, tHiS);
                writeU16(out, tLoS);
                writeU16(out, stIdx);
            }
            case "Main.Timers.PulseLen" -> {
                int q = blockPinSlot.getOrDefault(b.getId() + ":OUT:0", 0);
                int inS = inSlotAt(inSlots, firstArg);
                int lenS = inSlotAt(inSlots, firstArg + 1);
                int stIdx = nextStateIdx++;
                writeWord(out, NativeMaraVm.OP_PULSE_LEN, 0, enSlot, q, inS);
                writeU16(out, lenS);
                writeU16(out, stIdx);
            }
            case "Main.Timers.SinglePulse" -> {
                int q = blockPinSlot.getOrDefault(b.getId() + ":OUT:0", 0);
                int inS = inSlotAt(inSlots, firstArg);
                int stIdx = nextStateIdx++;
                writeWord(out, NativeMaraVm.OP_ONE_SHOT, 0, enSlot, q, inS);
                writeU16(out, stIdx);
                writeU16(out, 0);
            }
            case "Main.Timers.Counter" -> {
                int q = blockPinSlot.getOrDefault(b.getId() + ":OUT:0", 0);
                int cv = blockPinSlot.getOrDefault(b.getId() + ":OUT:1", 0);
                int cu = inSlotAt(inSlots, firstArg);
                int cd = inSlotAt(inSlots, firstArg + 1);
                int r = inSlotAt(inSlots, firstArg + 2);
                int pv = inSlotAt(inSlots, firstArg + 3);
                int stIdx = nextStateIdx++;
                writeWord(out, NativeMaraVm.OP_CTUD, 0, enSlot, q, cu);
                writeU16(out, cd);
                writeU16(out, r);
                writeU16(out, pv);
                writeU16(out, cv);
                writeU16(out, stIdx);
                writeU16(out, 0);
            }
            case "Main.Conversion.PackBits" -> {
                int dst = blockPinSlot.getOrDefault(b.getId() + ":OUT:0", 0);
                int msbFlag = "MSB".equalsIgnoreCase(b.getCustomProperties().getOrDefault("BitOrder", "LSB")) ? 1 : 0;
                writeWord(out, NativeMaraVm.OP_PACK8, msbFlag, enSlot, dst, inSlotAt(inSlots, firstArg));
                for (int i = 1; i < 8; i++) {
                    writeU16(out, inSlotAt(inSlots, firstArg + i));
                }
                writeU16(out, 0);
            }
            case "Main.Conversion.UnpackBits" -> {
                int src = inSlotAt(inSlots, firstArg);
                int msbFlag = "MSB".equalsIgnoreCase(b.getCustomProperties().getOrDefault("BitOrder", "LSB")) ? 1 : 0;
                int b0 = blockPinSlot.getOrDefault(b.getId() + ":OUT:0", 0);
                writeWord(out, NativeMaraVm.OP_UNPACK8, msbFlag, enSlot, b0, src);
                for (int i = 1; i < 8; i++) {
                    writeU16(out, blockPinSlot.getOrDefault(b.getId() + ":OUT:" + i, 0));
                }
                writeU16(out, 0);
            }
            case "Main.Timers.DateTime" -> {
                int ts = blockPinSlot.getOrDefault(b.getId() + ":OUT:0", 0);
                writeWord(out, NativeMaraVm.OP_RTC_GET, 0, enSlot, ts, 0);
            }
            case "Main.Timers.DecodeDate" -> {
                int ts  = inSlotAt(inSlots, firstArg);
                int yr  = blockPinSlot.getOrDefault(b.getId() + ":OUT:0", 0);
                int mon = blockPinSlot.getOrDefault(b.getId() + ":OUT:1", 0);
                int day = blockPinSlot.getOrDefault(b.getId() + ":OUT:2", 0);
                int hr  = blockPinSlot.getOrDefault(b.getId() + ":OUT:3", 0);
                int min = blockPinSlot.getOrDefault(b.getId() + ":OUT:4", 0);
                int sec = blockPinSlot.getOrDefault(b.getId() + ":OUT:5", 0);
                writeWord(out, NativeMaraVm.OP_DECODE_DATE, 0, enSlot, yr, ts);
                writeU16(out, mon);
                writeU16(out, day);
                writeU16(out, hr);
                writeU16(out, min);
                writeU16(out, sec);
                emitTimeZoneRule(out, cz.maramat.model.TimeZoneRule.fromBlock(b));
            }
            case "Main.Timers.EncodeDate" -> {
                int yr  = inSlotAt(inSlots, firstArg);
                int mon = inSlotAt(inSlots, firstArg + 1);
                int day = inSlotAt(inSlots, firstArg + 2);
                int hr  = inSlotAt(inSlots, firstArg + 3);
                int min = inSlotAt(inSlots, firstArg + 4);
                int sec = inSlotAt(inSlots, firstArg + 5);
                int ts  = blockPinSlot.getOrDefault(b.getId() + ":OUT:0", 0);
                writeWord(out, NativeMaraVm.OP_ENCODE_DATE, 0, enSlot, ts, yr);
                writeU16(out, mon);
                writeU16(out, day);
                writeU16(out, hr);
                writeU16(out, min);
                writeU16(out, sec);
                emitTimeZoneRule(out, cz.maramat.model.TimeZoneRule.fromBlock(b));
            }
            case "IO.IREF" -> {
                int dst = blockPinSlot.getOrDefault(b.getId() + ":OUT:0", 0);
                String ref = b.getIoRefLabel();
                int ch = parseIoChannel(ref);
                int kind = isAnalogRef(ref) ? NativeMaraVm.IO_AI : NativeMaraVm.IO_DI;
                writeWord(out, NativeMaraVm.OP_IO_READ, kind, NativeMaraVm.SLOT_ALWAYS_ENABLED, dst, ch);
            }
            case "IO.OREF" -> {
                int srcA = inSlotAt(inSlots, 0);
                String ref = b.getIoRefLabel();
                if (!ref.isBlank()) {
                    operandToSlot.putIfAbsent(ref, srcA);
                }
                int ch = parseIoChannel(ref);
                int kind = isAnalogRef(ref) ? NativeMaraVm.IO_AO : NativeMaraVm.IO_DO;
                writeWord(out, NativeMaraVm.OP_IO_WRITE, kind, NativeMaraVm.SLOT_ALWAYS_ENABLED, ch, srcA);
            }
            case "IO.PWM" -> {
                int act = blockPinSlot.getOrDefault(b.getId() + ":OUT:0", 0);
                int ch = parseIoChannel(b.getIoRefLabel());
                int frq = inSlotAt(inSlots, firstArg);
                int dty = inSlotAt(inSlots, firstArg + 1);
                writeWord(out, NativeMaraVm.OP_IO_PWM, 0, enSlot, act, ch);
                writeU16(out, frq);
                writeU16(out, dty);
            }
            case "Comm.I2C.TMP75" -> {
                int tmp = blockPinSlot.getOrDefault(b.getId() + ":OUT:0", 0);
                int ok = blockPinSlot.getOrDefault(b.getId() + ":OUT:1", NativeMaraVm.SLOT_ALWAYS_ENABLED);
                int addr7 = parseHexByteOrDefault(b.getCustomProperties().get("DeviceAddressHex"), 0x48);
                writeWord(out, NativeMaraVm.OP_COMM_EXEC, NativeMaraVm.COMM_TMP75, enSlot, tmp, addr7);
                writeU16(out, ok);
                writeU16(out, 0);
            }
            case "Comm.DS18B20.DS18B20Read" -> {
                int tmp = blockPinSlot.getOrDefault(b.getId() + ":OUT:0", 0);
                int ok = blockPinSlot.getOrDefault(b.getId() + ":OUT:1", NativeMaraVm.SLOT_ALWAYS_ENABLED);
                int dioPin = parseDioOrDefault(b.getCustomProperties().get("OneWirePin"), 13);
                String romProp = b.getCustomProperties().getOrDefault("RomAddress", "AUTO:0").trim();
                int busIdx = 0;
                long rom64 = 0L;
                if (romProp.toUpperCase().startsWith("AUTO:")) {
                    try {
                        busIdx = Math.clamp(Integer.parseInt(romProp.substring(5).replaceAll("[^0-9]", "")), 0, 7);
                    } catch (Exception ignored) {
                        busIdx = 0;
                    }
                } else {
                    rom64 = parseOneWireRom64(romProp);
                }
                int resBits = 12;
                try {
                    resBits = Math.clamp(Integer.parseInt(b.getCustomProperties().getOrDefault("ResolutionBits", "12").trim()), 9, 12);
                } catch (Exception ignored) {}
                int srcA = (dioPin & 0xFF) | ((busIdx & 0xFF) << 8);
                writeWord(out, NativeMaraVm.OP_COMM_EXEC, NativeMaraVm.COMM_DS18B20, enSlot, tmp, srcA);
                writeU16(out, ok);
                writeU16(out, resBits);
                writeU64(out, rom64);
            }
            case "IO.CH455G" -> {
                int act = blockPinSlot.getOrDefault(b.getId() + ":OUT:0", 0);
                int sclDio = parseDioOrDefault(b.getCustomProperties().get("SclPin"), 2);
                int sdaDio = parseDioOrDefault(b.getCustomProperties().get("SdaPin"), 3);
                String modeStr = b.getCustomProperties().getOrDefault("DisplayMode", "Number (Float / Int)");
                boolean isRaw = modeStr.startsWith("Raw");
                int digitMask = BlockInstance.parseDigitMaskBits(b.getCustomProperties().get("DigitMask"));
                int modeAndMask = (isRaw ? 1 : 0) | ((digitMask & 0xF) << 4);
                int srcA = (sclDio & 0xFF) | ((sdaDio & 0xFF) << 8);

                int s0 = 0, s1 = 0, s2 = 0, s3 = 0, s4 = 0, s5 = 0;
                if (isRaw) {
                    s0 = findInputSlotByShortName(b, inSlots, "D0");
                    s1 = findInputSlotByShortName(b, inSlots, "D1");
                    s2 = findInputSlotByShortName(b, inSlots, "D2");
                    s3 = findInputSlotByShortName(b, inSlots, "D3");
                    s4 = findInputSlotByShortName(b, inSlots, "DpMask");
                    s5 = findInputSlotByShortName(b, inSlots, "Brt");
                } else {
                    s0 = findInputSlotByShortName(b, inSlots, "Val");
                    s1 = findInputSlotByShortName(b, inSlots, "Dec");
                    s2 = findInputSlotByShortName(b, inSlots, "Brt");
                }
                writeWord(out, NativeMaraVm.OP_COMM_EXEC, NativeMaraVm.COMM_CH455G, enSlot, act, srcA);
                writeU16(out, modeAndMask);
                writeU16(out, s0);
                writeU16(out, s1);
                writeU16(out, s2);
                writeU16(out, s3);
                writeU16(out, s4);
                writeU16(out, s5);
                writeU16(out, 0);
            }
            case "Main.Execution.ReadReg" -> {
                String regName = b.getCustomProperties().getOrDefault("RegisterName", "REG_0").trim().toUpperCase();
                DataType outDt = b.getOutputs().isEmpty() ? DataType.INT : b.getOutputs().getFirst().getDataType();
                int regSlot = getOrAllocRegSlot(regName, outDt);
                int dst = blockPinSlot.getOrDefault(b.getId() + ":OUT:0", 0);
                String trigMode = b.getCustomProperties().getOrDefault("TriggerMode", "Continuous (While En=1)");
                int effEn = enSlot;
                if (trigMode.startsWith("Rising") && enSlot != NativeMaraVm.SLOT_ALWAYS_ENABLED) {
                    int pulseEn = allocSlot(DataType.BOOL);
                    int stIdx = nextStateIdx++;
                    writeWord(out, NativeMaraVm.OP_ONE_SHOT, 0, NativeMaraVm.SLOT_ALWAYS_ENABLED, pulseEn, enSlot);
                    writeU16(out, stIdx);
                    writeU16(out, 0);
                    effEn = pulseEn;
                }
                if ("true".equalsIgnoreCase(b.getCustomProperties().get("RetentiveEeprom"))) {
                    int paramId = Math.clamp(parseIoChannel(regName), 0, 255);
                    writeWord(out, NativeMaraVm.OP_RET_READ, 0, effEn, regSlot, paramId);
                }
                writeWord(out, NativeMaraVm.OP_MOV, 0, effEn, dst, regSlot);
            }
            case "Main.Execution.WriteReg" -> {
                String regName = b.getCustomProperties().getOrDefault("RegisterName", "REG_0").trim().toUpperCase();
                DataType inDt = b.getInputs().size() > 1 ? b.getInputs().get(1).getDataType() : DataType.INT;
                int regSlot = getOrAllocRegSlot(regName, inDt);
                int inS = inSlotAt(inSlots, firstArg);
                int dst = blockPinSlot.getOrDefault(b.getId() + ":OUT:0", 0);
                String trigMode = b.getCustomProperties().getOrDefault("TriggerMode", "Continuous (While En=1)");
                int effEn = enSlot;
                if (trigMode.startsWith("Rising") && enSlot != NativeMaraVm.SLOT_ALWAYS_ENABLED) {
                    int pulseEn = allocSlot(DataType.BOOL);
                    int stIdx = nextStateIdx++;
                    writeWord(out, NativeMaraVm.OP_ONE_SHOT, 0, NativeMaraVm.SLOT_ALWAYS_ENABLED, pulseEn, enSlot);
                    writeU16(out, stIdx);
                    writeU16(out, 0);
                    effEn = pulseEn;
                }
                writeWord(out, NativeMaraVm.OP_MOV, 0, effEn, regSlot, inS);
                if ("true".equalsIgnoreCase(b.getCustomProperties().get("RetentiveEeprom"))) {
                    int paramId = Math.clamp(parseIoChannel(regName), 0, 255);
                    writeWord(out, NativeMaraVm.OP_RET_WRITE, 0, effEn, regSlot, paramId);
                }
                if (dst != 0) {
                    writeWord(out, NativeMaraVm.OP_MOV, 0, NativeMaraVm.SLOT_ALWAYS_ENABLED, dst, regSlot);
                }
            }
            case "Main.Arrays.ArrayRead" -> {
                String arrName = b.getCustomProperties().getOrDefault("ArrayName", "ARR_0").trim().toUpperCase();
                int arrIdx = arrayNameToIdx.computeIfAbsent(arrName, k -> Math.min(arrayNameToIdx.size(), 7));
                int idxS = inSlotAt(inSlots, firstArg);
                int valS = blockPinSlot.getOrDefault(b.getId() + ":OUT:0", 0);
                int oobS = blockPinSlot.getOrDefault(b.getId() + ":OUT:1", NativeMaraVm.SLOT_ALWAYS_ENABLED);
                writeWord(out, NativeMaraVm.OP_ARRAY_READ, arrIdx, enSlot, valS, idxS);
                writeU16(out, oobS);
                writeU16(out, 0);
            }
            case "Main.Arrays.ArrayWrite" -> {
                String arrName = b.getCustomProperties().getOrDefault("ArrayName", "ARR_0").trim().toUpperCase();
                int arrIdx = arrayNameToIdx.computeIfAbsent(arrName, k -> Math.min(arrayNameToIdx.size(), 7));
                int idxS = inSlotAt(inSlots, firstArg);
                int valS = inSlotAt(inSlots, firstArg + 1);
                int oobS = blockPinSlot.getOrDefault(b.getId() + ":OUT:0", NativeMaraVm.SLOT_ALWAYS_ENABLED);
                writeWord(out, NativeMaraVm.OP_ARRAY_WRITE, arrIdx, enSlot, oobS, idxS);
                writeU16(out, valS);
                writeU16(out, 0);
            }
            case "Main.Safety.EStop" -> {
                int safeS = blockPinSlot.getOrDefault(b.getId() + ":OUT:0", 0);
                int tripS = blockPinSlot.getOrDefault(b.getId() + ":OUT:1", NativeMaraVm.SLOT_ALWAYS_ENABLED);
                int srcS = blockPinSlot.getOrDefault(b.getId() + ":OUT:2", NativeMaraVm.SLOT_ALWAYS_ENABLED);
                int rstS = inSlotAt(inSlots, firstArg);
                int stIdx = nextStateIdx++;
                writeWord(out, NativeMaraVm.OP_ESTOP_CHECK, 0, enSlot, safeS, rstS);
                writeU16(out, tripS);
                writeU16(out, srcS);
                writeU16(out, stIdx);
                writeU16(out, 0);
            }
            case "Main.Execution.Interrupt" -> {
                String source = b.getCustomProperties().getOrDefault("InterruptSource", "TIM_IC1").trim().toUpperCase();
                String edgeMode = b.getCustomProperties().getOrDefault("TriggerEdge", "RISING").trim().toUpperCase();
                String capMode = b.getCustomProperties().getOrDefault("CaptureMode", "PULSE_WIDTH_US").trim().toUpperCase();
                String latchPolicy = b.getCustomProperties().getOrDefault("LatchPolicy", "HOLD_FIRST_UNTIL_RST").trim().toUpperCase();
                int capBits = "EDGE_COUNT".equals(capMode) ? 1 : (capMode.startsWith("GPIO_STATE") ? 2 : 0);
                int edgeBits = "FALLING".equals(edgeMode) ? 1 : ("BOTH".equals(edgeMode) ? 2 : 0);
                int latchBit = "OVERWRITE_LATEST".equals(latchPolicy) ? 1 : 0;
                int flags = (capBits & 0x03) | ((edgeBits & 0x03) << 2) | ((latchBit & 0x01) << 4);
                int diChan = source.startsWith("EXTI_DI") ? parseIoChannel(source) : 0xFFFF;
                int valS = blockPinSlot.getOrDefault(b.getId() + ":OUT:0", 0);
                int flgS = blockPinSlot.getOrDefault(b.getId() + ":OUT:1", NativeMaraVm.SLOT_ALWAYS_ENABLED);
                int rstS = inSlotAt(inSlots, firstArg);
                int stIdx = nextStateIdx++;
                writeWord(out, NativeMaraVm.OP_INT_CAPTURE, flags, enSlot, valS, rstS);
                writeU16(out, flgS);
                writeU16(out, diChan);
                writeU16(out, stIdx);
                writeU16(out, 0);
            }
            default -> {
                if (id.startsWith("Macro.User.")) {
                    emitMacroInstance(out, b, id.substring("Macro.User.".length()), enSlot, firstArg, inSlots, blockPinSlot);
                } else if ("Visual.Value".equals(id) && !b.getInputs().isEmpty()) {
                    int srcA = inSlotAt(inSlots, 0);
                    operandToSlot.put(b.getName() + ".In", srcA);
                } else if ("Visual.Segment".equals(id)) {
                    for (int i = 0; i < b.getInputs().size(); i++) {
                        operandToSlot.put(b.getName() + "." + b.getInputs().get(i).getShortName(), inSlotAt(inSlots, i));
                    }
                }
            }
        }

        boolean alwaysEnOut = "Main.Timers.Timer".equals(id)
                || "Main.Timers.PulseGen".equals(id)
                || "Main.Timers.PulseLen".equals(id)
                || "Main.Timers.SinglePulse".equals(id)
                || "IO.PWM".equals(id)
                || id.startsWith("Macro.User.");
        int outNegEnSlot = alwaysEnOut ? NativeMaraVm.SLOT_ALWAYS_ENABLED : enSlot;
        for (int i = 0; i < b.getOutputs().size(); i++) {
            PinInstance outPin = b.getOutputs().get(i);
            if (outPin.isNegated() && outPin.getDataType() == DataType.BOOL) {
                Integer outSlot = blockPinSlot.get(b.getId() + ":OUT:" + i);
                if (outSlot != null) {
                    writeWord(out, NativeMaraVm.OP_NOT, 0, outNegEnSlot, outSlot, outSlot);
                }
            }
        }
    }

    private void emitMacroInstance(ByteArrayOutputStream out, BlockInstance b, String macroName,
                                   int enSlot, int firstArg, int[] inSlots,
                                   Map<String, Integer> blockPinSlot) {
        if (currentProject == null) return;
        Page macroPage = null;
        for (Page p : currentProject.getPages()) {
            if (p.isMacro()) {
                String clean = p.getName().startsWith("*") ? p.getName().substring(1).trim() : p.getName().trim();
                if (clean.equalsIgnoreCase(macroName)) {
                    macroPage = p;
                    break;
                }
            }
        }
        if (macroPage == null) return;
        if (macroPage.getLanguage() == IecLanguage.ST) {
            emitStMacroInstance(out, b, macroPage, enSlot, firstArg, inSlots, blockPinSlot);
        } else if (macroPage.getLanguage() == IecLanguage.FBD) {
            emitFbdMacroInstance(out, b, macroPage, enSlot, firstArg, inSlots, blockPinSlot);
        }
    }

    private void emitFbdMacroInstance(ByteArrayOutputStream out, BlockInstance callerBlock, Page macroPage,
                                      int enSlot, int firstArg, int[] inSlots,
                                      Map<String, Integer> callerPinSlot) {
        Map<String, Integer> macroPinSlot = new HashMap<>();
        for (BlockInstance mb : macroPage.getAllBlockInstances()) {
            for (int i = 0; i < mb.getOutputs().size(); i++) {
                PinInstance outPin = mb.getOutputs().get(i);
                int slotId = allocSlot(outPin.getDataType());
                macroPinSlot.put(mb.getId() + ":OUT:" + i, slotId);
                if (outPin.isNegated() && outPin.getDataType() == DataType.BOOL) {
                    addInitEntry(slotId, DataType.BOOL, "1");
                }
            }
        }

        // Copy caller inputs -> MacroIn output pin slots
        for (BlockInstance mb : macroPage.getAllBlockInstances()) {
            if ("Macro.MacroIn".equals(mb.getDefinitionId())) {
                for (int i = 0; i < mb.getOutputs().size(); i++) {
                    Integer dstSlot = macroPinSlot.get(mb.getId() + ":OUT:" + i);
                    int callerIdx = firstArg + i;
                    if (dstSlot != null && callerIdx < inSlots.length) {
                        writeWord(out, NativeMaraVm.OP_MOV, 0, enSlot, dstSlot, inSlotAt(inSlots, callerIdx));
                    }
                }
            }
        }

        Map<String, Integer> macroWiredSlot = new HashMap<>();
        for (Wire w : macroPage.getWires()) {
            Integer srcSlot = macroPinSlot.get(w.getSourceBlockId() + ":OUT:" + w.getSourcePinIndex());
            if (srcSlot != null) {
                macroWiredSlot.put(w.getTargetBlockId() + ":IN:" + w.getTargetPinIndex(), srcSlot);
            }
        }

        List<BlockInstance> sorted = new ArrayList<>(macroPage.getAllBlockInstances());
        sorted.sort(Comparator.comparingInt(BlockInstance::getOrder));
        for (BlockInstance mb : sorted) {
            String mDef = mb.getDefinitionId();
            if ("Macro.MacroIn".equals(mDef)) continue;
            if ("Macro.MacroOut".equals(mDef)) {
                int[] mIns = resolveAllInputs(out, mb, macroWiredSlot);
                for (int i = 0; i < mb.getInputs().size(); i++) {
                    Integer dstSlot = callerPinSlot.get(callerBlock.getId() + ":OUT:" + i);
                    if (dstSlot != null && i < mIns.length) {
                        writeWord(out, NativeMaraVm.OP_MOV, 0, enSlot, dstSlot, mIns[i]);
                    }
                }
                continue;
            }
            emitFbdBlock(out, mb, macroPinSlot, macroWiredSlot);
        }
    }

    private void emitStMacroInstance(ByteArrayOutputStream out, BlockInstance b, Page macroPage,
                                     int enSlot, int firstArg, int[] inSlots,
                                     Map<String, Integer> blockPinSlot) {

        Map<String, Integer> varSlots = new HashMap<>();
        Map<String, DataType> varTypes = new HashMap<>();
        for (StVariable v : macroPage.getStVariables()) {
            int slotId = allocSlot(v.getDataType());
            varSlots.put(v.getName().toUpperCase(), slotId);
            varTypes.put(v.getName().toUpperCase(), v.getDataType());
            addInitEntry(slotId, v.getDataType(), v.getInitialValue());
        }

        // Copy FBD block inputs -> VAR_INPUT slots
        for (int i = firstArg; i < b.getInputs().size(); i++) {
            PinInstance inPin = b.getInputs().get(i);
            Integer dstSlot = varSlots.get(inPin.getShortName().toUpperCase());
            if (dstSlot != null) {
                int srcSlot = inSlotAt(inSlots, i);
                writeWord(out, NativeMaraVm.OP_MOV, 0, enSlot, dstSlot, srcSlot);
            }
        }

        // Compile ST body
        StBytecodeCompiler.compile(macroPage.getStCode(), out, varSlots, varTypes, new StBytecodeCompiler.SlotAllocator() {
            @Override
            public int allocTempSlot(DataType dt) {
                return allocSlot(dt);
            }

            @Override
            public void addConstInit(int slotId, DataType dt, String valStr) {
                addInitEntry(slotId, dt, valStr);
            }

            @Override
            public int allocStateIdx() {
                return nextStateIdx++;
            }

            @Override
            public void emitIoReadPrefix(String name, int slotId) {}

            @Override
            public void emitIoWriteSuffix(String name, int slotId) {}

            @Override
            public int getOrAllocGlobalSlot(String name, DataType dt) {
                return getOrAllocRegSlot(name, dt);
            }
        });

        // Copy VAR_OUTPUT slots -> FBD block output pin slots
        for (int i = 0; i < b.getOutputs().size(); i++) {
            PinInstance outPin = b.getOutputs().get(i);
            Integer srcSlot = varSlots.get(outPin.getShortName().toUpperCase());
            Integer dstSlot = blockPinSlot.get(b.getId() + ":OUT:" + i);
            if (srcSlot != null && dstSlot != null) {
                writeWord(out, NativeMaraVm.OP_MOV, 0, NativeMaraVm.SLOT_ALWAYS_ENABLED, dstSlot, srcSlot);
            }
        }
    }

    private void emitStPage(ByteArrayOutputStream out, Page page) {
        Map<String, Integer> varSlots = new HashMap<>();
        Map<String, DataType> varTypes = new HashMap<>();
        for (StVariable v : page.getStVariables()) {
            int slotId = allocSlot(v.getDataType());
            varSlots.put(v.getName().toUpperCase(), slotId);
            varTypes.put(v.getName().toUpperCase(), v.getDataType());
            addInitEntry(slotId, v.getDataType(), v.getInitialValue());
            operandToSlot.put(page.getName() + "." + v.getName(), slotId);
            operandToSlot.putIfAbsent(v.getName(), slotId);
            slotToOperand.put(slotId, page.getName() + "." + v.getName());
        }
        StBytecodeCompiler.compile(page.getStCode(), out, varSlots, varTypes, new StBytecodeCompiler.SlotAllocator() {
            @Override
            public int allocTempSlot(DataType dt) {
                return allocSlot(dt);
            }

            @Override
            public void addConstInit(int slotId, DataType dt, String valStr) {
                addInitEntry(slotId, dt, valStr);
            }

            @Override
            public int allocStateIdx() {
                return nextStateIdx++;
            }

            @Override
            public void emitIoReadPrefix(String name, int slotId) {
                String u = name.toUpperCase();
                if (u.startsWith("DI")) {
                    writeWord(out, NativeMaraVm.OP_IO_READ, NativeMaraVm.IO_DI, NativeMaraVm.SLOT_ALWAYS_ENABLED, slotId, parseIoChannel(u));
                } else if (u.startsWith("AI")) {
                    writeWord(out, NativeMaraVm.OP_IO_READ, NativeMaraVm.IO_AI, NativeMaraVm.SLOT_ALWAYS_ENABLED, slotId, parseIoChannel(u));
                }
            }

            @Override
            public void emitIoWriteSuffix(String name, int slotId) {
                String u = name.toUpperCase();
                if (u.startsWith("DO") || u.startsWith("DIO")) {
                    writeWord(out, NativeMaraVm.OP_IO_WRITE, NativeMaraVm.IO_DO, NativeMaraVm.SLOT_ALWAYS_ENABLED, parseIoChannel(u), slotId);
                } else if (u.startsWith("AO")) {
                    writeWord(out, NativeMaraVm.OP_IO_WRITE, NativeMaraVm.IO_AO, NativeMaraVm.SLOT_ALWAYS_ENABLED, parseIoChannel(u), slotId);
                }
            }

            @Override
            public int getOrAllocGlobalSlot(String name, DataType dt) {
                return getOrAllocRegSlot(name, dt);
            }
        });
    }

    private int getOrAllocRegSlot(String name, DataType dt) {
        String key = name.trim().toUpperCase();
        Integer existing = regNameToSlot.get(key);
        if (existing != null) return existing;
        int slot = allocSlot(dt);
        regNameToSlot.put(key, slot);
        operandToSlot.putIfAbsent(name.trim(), slot);
        operandToSlot.putIfAbsent(key, slot);
        slotToOperand.putIfAbsent(slot, key);
        return slot;
    }

    private void emitLdPage(ByteArrayOutputStream[] threadStreams, Page page) {
        List<LadderRung> rungs = new ArrayList<>(page.getRungs());
        rungs.sort(Comparator.comparingInt(LadderRung::getOrder));

        for (LadderRung rung : rungs) {
            int tid = Math.clamp(rung.getThreadId(), 1, 5);
            ByteArrayOutputStream out = threadStreams[tid];
            List<LadderElement> mainEls = rung.getMainElements();
            int n = mainEls.size();

            int[] nodePowerSlots = new int[n + 1];
            int railSlot = allocSlot(DataType.BOOL);
            addInitEntry(railSlot, DataType.BOOL, "1");
            nodePowerSlots[0] = railSlot;

            Map<LadderBranch, Integer> branchOutSlots = new HashMap<>();

            for (int col = 0; col < n; col++) {
                for (LadderBranch br : rung.getParallelBranches()) {
                    if (br.getEndColumn() == col - 1 && br.getStartColumn() <= col - 1) {
                        Integer brOut = branchOutSlots.get(br);
                        if (brOut != null) {
                            int merged = allocSlot(DataType.BOOL);
                            writeWord(out, NativeMaraVm.OP_OR, 2, NativeMaraVm.SLOT_ALWAYS_ENABLED, merged, nodePowerSlots[col]);
                            writeU16(out, brOut);
                            writeU16(out, 0);
                            nodePowerSlots[col] = merged;
                        }
                    }
                }
                for (LadderBranch br : rung.getParallelBranches()) {
                    if (br.getStartColumn() == col) {
                        int brPwr = nodePowerSlots[col];
                        for (LadderElement bel : br.getElements()) {
                            brPwr = emitLdSeriesElement(out, bel, brPwr);
                        }
                        branchOutSlots.put(br, brPwr);
                    }
                }
                nodePowerSlots[col + 1] = emitLdSeriesElement(out, mainEls.get(col), nodePowerSlots[col]);
            }

            int finalPower = nodePowerSlots[n];
            for (LadderBranch br : rung.getParallelBranches()) {
                if (br.getEndColumn() >= n - 1 && (n == 0 || br.getStartColumn() < n)) {
                    if (n == 0) {
                        int brPwr = railSlot;
                        for (LadderElement bel : br.getElements()) {
                            brPwr = emitLdSeriesElement(out, bel, brPwr);
                        }
                        branchOutSlots.put(br, brPwr);
                    }
                    Integer brOut = branchOutSlots.get(br);
                    if (brOut != null) {
                        int merged = allocSlot(DataType.BOOL);
                        writeWord(out, NativeMaraVm.OP_OR, 2, NativeMaraVm.SLOT_ALWAYS_ENABLED, merged, finalPower);
                        writeU16(out, brOut);
                        writeU16(out, 0);
                        finalPower = merged;
                    }
                }
            }

            for (LadderElement coil : rung.getCoils()) {
                emitLdCoil(out, coil, finalPower);
            }
        }
    }

    private int emitLdSeriesElement(ByteArrayOutputStream out, LadderElement el, int powerInSlot) {
        LadderElement.Kind kind = el.getKind();
        if (kind.isContact()) {
            int opSlot = resolveLdOperandOrConst(out, el.getOperand(), DataType.BOOL);
            int flag = switch (kind) {
                case NO_CONTACT -> 0;
                case NC_CONTACT -> 1;
                case POS_EDGE_CONTACT -> 2;
                case NEG_EDGE_CONTACT -> 3;
                default -> 0;
            };
            int outSlot = allocSlot(DataType.BOOL);
            int stIdx = (flag >= 2) ? nextStateIdx++ : 0;
            writeWord(out, NativeMaraVm.OP_LD_CONTACT, flag, powerInSlot, outSlot, opSlot);
            writeU16(out, stIdx);
            writeU16(out, 0);
            return outSlot;
        }
        if (kind == LadderElement.Kind.FUNCTION_BLOCK && el.getBlockInstance() != null) {
            BlockInstance b = el.getBlockInstance();
            Map<String, Integer> fbPinSlot = new HashMap<>();
            for (int i = 0; i < b.getOutputs().size(); i++) {
                PinInstance outPin = b.getOutputs().get(i);
                int slotId = allocSlot(outPin.getDataType());
                fbPinSlot.put(b.getId() + ":OUT:" + i, slotId);
                operandToSlot.putIfAbsent(b.getName() + "." + outPin.getShortName(), slotId);
            }
            List<PinInstance> ins = b.getInputs();
            int[] inSlots = new int[ins.size()];
            for (int i = 0; i < ins.size(); i++) {
                PinInstance pin = ins.get(i);
                if (i == 0 && "En".equalsIgnoreCase(pin.getShortName())) {
                    inSlots[i] = powerInSlot;
                } else {
                    inSlots[i] = resolveLdOperandOrConst(out, pin.getConstantValue(), pin.getDataType());
                }
            }
            emitFbdBlockWithInputs(out, b, inSlots, fbPinSlot);

            for (int i = 0; i < b.getOutputs().size(); i++) {
                PinInstance outPin = b.getOutputs().get(i);
                String targetOp = outPin.getConstantValue();
                if (targetOp != null && !targetOp.isBlank() && !targetOp.equals("0")) {
                    Integer outSlot = fbPinSlot.get(b.getId() + ":OUT:" + i);
                    if (outSlot != null) {
                        emitLdWriteOperand(out, targetOp, outPin.getDataType(), NativeMaraVm.SLOT_ALWAYS_ENABLED, outSlot);
                    }
                }
            }
            if (!b.getOutputs().isEmpty() && b.getOutputs().getFirst().getDataType() == DataType.BOOL) {
                Integer qSlot = fbPinSlot.get(b.getId() + ":OUT:0");
                if (qSlot != null) {
                    int outPower = allocSlot(DataType.BOOL);
                    writeWord(out, NativeMaraVm.OP_AND, 2, NativeMaraVm.SLOT_ALWAYS_ENABLED, outPower, powerInSlot);
                    writeU16(out, qSlot);
                    writeU16(out, 0);
                    return outPower;
                }
            }
            return powerInSlot;
        }
        return powerInSlot;
    }

    private void emitLdCoil(ByteArrayOutputStream out, LadderElement coil, int finalPower) {
        if (coil.getKind() == LadderElement.Kind.ANALOG_COIL) {
            int srcSlot = resolveLdOperandOrConst(out, coil.getSourceOperand(), DataType.INT);
            emitLdWriteOperand(out, coil.getOperand(), DataType.INT, finalPower, srcSlot);
            return;
        }
        String op = coil.getOperand();
        int dstSlot = getOrAllocRegSlot(op, DataType.BOOL);
        int flag = switch (coil.getKind()) {
            case NORMAL_COIL -> 0;
            case NEGATED_COIL -> 1;
            case SET_COIL -> 2;
            case RESET_COIL -> 3;
            case POS_EDGE_COIL -> 4;
            case NEG_EDGE_COIL -> 5;
            default -> 0;
        };
        int stIdx = (flag >= 4) ? nextStateIdx++ : 0;
        writeWord(out, NativeMaraVm.OP_LD_COIL, flag, finalPower, dstSlot, 0);
        writeU16(out, stIdx);
        writeU16(out, 0);

        String u = op.trim().toUpperCase();
        if (u.startsWith("DO") || u.startsWith("DIO")) {
            writeWord(out, NativeMaraVm.OP_IO_WRITE, NativeMaraVm.IO_DO, NativeMaraVm.SLOT_ALWAYS_ENABLED, parseIoChannel(u), dstSlot);
        }
    }

    private int resolveLdOperandOrConst(ByteArrayOutputStream out, String raw, DataType dt) {
        if (raw == null || raw.isBlank()) {
            int s = allocSlot(dt);
            addInitEntry(s, dt, "0");
            return s;
        }
        String t = raw.trim();
        String u = t.toUpperCase();
        if ("TRUE".equals(u) || "FALSE".equals(u)) {
            int s = allocSlot(DataType.BOOL);
            addInitEntry(s, DataType.BOOL, u);
            return s;
        }
        try {
            Double.parseDouble(t);
            int s = allocSlot(dt);
            addInitEntry(s, dt, t);
            return s;
        } catch (NumberFormatException ignored) {}

        if (u.startsWith("DI")) {
            int s = getOrAllocRegSlot(u, DataType.BOOL);
            writeWord(out, NativeMaraVm.OP_IO_READ, NativeMaraVm.IO_DI, NativeMaraVm.SLOT_ALWAYS_ENABLED, s, parseIoChannel(u));
            return s;
        }
        if (u.startsWith("AI")) {
            int s = getOrAllocRegSlot(u, DataType.INT);
            writeWord(out, NativeMaraVm.OP_IO_READ, NativeMaraVm.IO_AI, NativeMaraVm.SLOT_ALWAYS_ENABLED, s, parseIoChannel(u));
            return s;
        }
        return getOrAllocRegSlot(t, dt);
    }

    private void emitLdWriteOperand(ByteArrayOutputStream out, String targetOp, DataType dt, int enSlot, int srcSlot) {
        String u = targetOp.trim().toUpperCase();
        int dstSlot = getOrAllocRegSlot(targetOp, dt);
        writeWord(out, NativeMaraVm.OP_MOV, 0, enSlot, dstSlot, srcSlot);
        if (u.startsWith("DO") || u.startsWith("DIO")) {
            writeWord(out, NativeMaraVm.OP_IO_WRITE, NativeMaraVm.IO_DO, enSlot, parseIoChannel(u), srcSlot);
        } else if (u.startsWith("AO")) {
            writeWord(out, NativeMaraVm.OP_IO_WRITE, NativeMaraVm.IO_AO, enSlot, parseIoChannel(u), srcSlot);
        }
    }

    private static int inSlotAt(int[] inSlots, int idx) {
        return (idx >= 0 && idx < inSlots.length) ? inSlots[idx] : 0;
    }

    private int[] resolveAllInputs(ByteArrayOutputStream out, BlockInstance b, Map<String, Integer> wiredSourceSlot) {
        int n = b.getInputs().size();
        int[] slots = new int[n];
        for (int i = 0; i < n; i++) {
            slots[i] = resolveInput(out, b, i, wiredSourceSlot);
        }
        return slots;
    }

    private int resolveInput(ByteArrayOutputStream out, BlockInstance b, int pinIndex, Map<String, Integer> wiredSourceSlot) {
        PinInstance pin = (pinIndex < b.getInputs().size()) ? b.getInputs().get(pinIndex) : null;
        boolean negate = pin != null && pin.isNegated() && pin.getDataType() == DataType.BOOL;
        Integer wired = wiredSourceSlot.get(b.getId() + ":IN:" + pinIndex);
        if (wired != null) {
            int resultSlot = wired;
            if (negate) {
                int negSlot = allocSlot(DataType.BOOL);
                writeWord(out, NativeMaraVm.OP_NOT, 0, NativeMaraVm.SLOT_ALWAYS_ENABLED, negSlot, wired);
                resultSlot = negSlot;
            }
            if (pin != null) {
                String key = b.getName() + "." + pin.getShortName();
                operandToSlot.putIfAbsent(key, resultSlot);
            }
            return resultSlot;
        }
        if (pin == null) return 0;
        int constSlot = allocSlot(pin.getDataType());
        String constVal = pin.getConstantValue();
        if (negate) {
            double v = pin.getDataType().toNumericValue(constVal);
            constVal = (v == 0.0) ? "1" : "0";
        }
        addInitEntry(constSlot, pin.getDataType(), constVal);
        String key = b.getName() + "." + pin.getShortName();
        operandToSlot.putIfAbsent(key, constSlot);
        return constSlot;
    }

    private int allocSlot(DataType dt) {
        int type = switch (dt) {
            case BOOL -> NativeMaraVm.TYPE_BOOL;
            case INT -> NativeMaraVm.TYPE_INT;
            case LONG -> NativeMaraVm.TYPE_LONG;
            case FLOAT -> NativeMaraVm.TYPE_FLOAT;
        };
        int idx = nextSlotIndex[type]++;
        return NativeMaraVm.makeSlotId(type, idx);
    }

    private void addInitEntry(int slotId, DataType dt, String valStr) {
        double parsed = 0.0;
        if (valStr != null && !valStr.isBlank()) {
            try {
                if ("true".equalsIgnoreCase(valStr.trim())) parsed = 1.0;
                else if ("false".equalsIgnoreCase(valStr.trim())) parsed = 0.0;
                else parsed = Double.parseDouble(valStr.trim());
            } catch (NumberFormatException ignored) {}
        }
        long rawBits = switch (dt) {
            case BOOL -> (parsed != 0.0) ? 1L : 0L;
            case INT -> Integer.toUnsignedLong((int) Math.round(parsed));
            case LONG -> Math.round(parsed);
            case FLOAT -> Integer.toUnsignedLong(Float.floatToRawIntBits((float) parsed));
        };
        initEntries.add(new InitEntry(slotId, rawBits));
    }

    private static int findInputSlotByShortName(BlockInstance b, int[] inSlots, String shortName) {
        for (int i = 0; i < b.getInputs().size() && i < inSlots.length; i++) {
            if (shortName.equalsIgnoreCase(b.getInputs().get(i).getShortName())) {
                return inSlots[i];
            }
        }
        return 0;
    }

    private static int parseDioOrDefault(String raw, int defVal) {
        if (raw == null || raw.isBlank()) return defVal;
        String digits = raw.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) return defVal;
        try {
            return Math.clamp(Integer.parseInt(digits), 2, 27);
        } catch (NumberFormatException e) {
            return defVal;
        }
    }

    private static int parseHexByteOrDefault(String raw, int defVal) {
        if (raw == null || raw.isBlank()) return defVal;
        String token = raw.trim().split("\\s+")[0];
        if (token.startsWith("0x") || token.startsWith("0X")) {
            token = token.substring(2);
        }
        try {
            return Integer.parseInt(token, 16) & 0x7F;
        } catch (NumberFormatException e) {
            return defVal;
        }
    }

    private static long parseOneWireRom64(String raw) {
        if (raw == null || raw.isBlank()) return 0L;
        String hex = raw.trim().replace("-", "").replace(":", "").replace(" ", "");
        if (hex.length() < 16) return 0L;
        long rom64 = 0L;
        try {
            for (int i = 0; i < 8; i++) {
                int b = Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16) & 0xFF;
                rom64 |= ((long) b) << (i * 8);
            }
        } catch (NumberFormatException e) {
            return 0L;
        }
        return rom64;
    }

    private static void writeU64(ByteArrayOutputStream out, long val) {
        for (int i = 0; i < 8; i++) {
            out.write((int) ((val >>> (i * 8)) & 0xFFL));
        }
    }

    private static int parseIoChannel(String ref) {
        if (ref == null) return 0;
        String digits = ref.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) return 0;
        try { return Integer.parseInt(digits); } catch (NumberFormatException e) { return 0; }
    }

    private static boolean isAnalogRef(String ref) {
        if (ref == null) return false;
        String u = ref.toUpperCase();
        return u.startsWith("AI") || u.startsWith("AO");
    }

    private static void emitTimeZoneRule(ByteArrayOutputStream out, cz.maramat.model.TimeZoneRule tz) {
        writeU16(out, tz.stdOffsetMin() & 0xFFFF);
        writeU16(out, tz.dstOffsetMin() & 0xFFFF);
        out.write(tz.startMonth() & 0xFF);
        out.write(tz.startWeek() & 0xFF);
        out.write(tz.startDow() & 0xFF);
        out.write(tz.startHour() & 0xFF);
        out.write(tz.endMonth() & 0xFF);
        out.write(tz.endWeek() & 0xFF);
        out.write(tz.endDow() & 0xFF);
        out.write(tz.endHour() & 0xFF);
        writeU16(out, 0); // reserved
    }

    private static void writeWord(ByteArrayOutputStream out, int op, int flags, int enSlot, int dst, int srcA) {
        out.write(op & 0xFF);
        out.write(flags & 0xFF);
        writeU16(out, enSlot);
        writeU16(out, dst);
        writeU16(out, srcA);
    }

    private static void writeU16(ByteArrayOutputStream out, int val) {
        out.write(val & 0xFF);
        out.write((val >>> 8) & 0xFF);
    }
}
