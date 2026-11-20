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

// Emits a valid MFLW v1 binary image and SlotSymbolTable for MaraVm (Java & STM32F407).
public final class MflwEmitter {

    public record EmittedProgram(
            byte[] image,
            Map<String, Integer> operandToSlot,
            Map<Integer, String> slotToOperand
    ) {}

    private final int[] nextSlotIndex = new int[4];
    private int nextStateIdx = 0;
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
                int a = inSlotAt(inSlots, firstArg);
                int bIn = inSlotAt(inSlots, firstArg + 1);
                writeWord(out, op, 0, enSlot, dst, a);
                writeU16(out, bIn);
                writeU16(out, 0);
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
            case "Main.Switches.Sel" -> {
                int dst = blockPinSlot.getOrDefault(b.getId() + ":OUT:0", 0);
                int g = inSlotAt(inSlots, firstArg);
                int x0 = inSlotAt(inSlots, firstArg + 1);
                int x1 = inSlotAt(inSlots, firstArg + 2);
                writeWord(out, NativeMaraVm.OP_SEL, 0, enSlot, dst, g);
                writeU16(out, x0);
                writeU16(out, x1);
            }
            case "Main.Timers.Timer" -> {
                int q = blockPinSlot.getOrDefault(b.getId() + ":OUT:0", 0);
                int et = blockPinSlot.getOrDefault(b.getId() + ":OUT:1", NativeMaraVm.SLOT_ALWAYS_ENABLED);
                int inS = inSlotAt(inSlots, firstArg);
                int ptS = inSlotAt(inSlots, firstArg + 1);
                int stIdx = nextStateIdx++;
                writeWord(out, NativeMaraVm.OP_TON, 0, enSlot, q, inS);
                writeU16(out, ptS);
                writeU16(out, et);
                writeU16(out, stIdx);
                writeU16(out, 0);
            }
            case "Main.Timers.SinglePulse" -> {
                int q = blockPinSlot.getOrDefault(b.getId() + ":OUT:0", 0);
                int inS = inSlotAt(inSlots, firstArg);
                int stIdx = nextStateIdx++;
                writeWord(out, NativeMaraVm.OP_ONE_SHOT, 0, enSlot, q, inS);
                writeU16(out, stIdx);
                writeU16(out, 0);
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
            default -> {
                if (id.startsWith("Macro.User.")) {
                    emitStMacroInstance(out, b, id.substring("Macro.User.".length()), enSlot, firstArg, inSlots, blockPinSlot);
                } else if ("Visual.Value".equals(id) && !b.getInputs().isEmpty()) {
                    int srcA = inSlotAt(inSlots, 0);
                    operandToSlot.put(b.getName() + ".In", srcA);
                }
            }
        }

        boolean alwaysEnOut = "Main.Timers.Timer".equals(id)
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

    private void emitStMacroInstance(ByteArrayOutputStream out, BlockInstance b, String macroName,
                                     int enSlot, int firstArg, int[] inSlots,
                                     Map<String, Integer> blockPinSlot) {
        if (currentProject == null) return;
        Page macroPage = null;
        for (Page p : currentProject.getPages()) {
            if (p.isMacro() && p.getLanguage() == IecLanguage.ST) {
                String clean = p.getName().startsWith("*") ? p.getName().substring(1).trim() : p.getName().trim();
                if (clean.equalsIgnoreCase(macroName)) {
                    macroPage = p;
                    break;
                }
            }
        }
        if (macroPage == null) return;

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
        });
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
