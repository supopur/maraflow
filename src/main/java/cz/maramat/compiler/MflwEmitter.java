package cz.maramat.compiler;

import cz.maramat.model.BlockInstance;
import cz.maramat.model.DataType;
import cz.maramat.model.IecLanguage;
import cz.maramat.model.Page;
import cz.maramat.model.PinInstance;
import cz.maramat.model.Project;
import cz.maramat.model.Wire;
import cz.maramat.vm.MaraVm;

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

    private record InitEntry(int slotId, long rawBits) {}

    private MflwEmitter() {}

    public static EmittedProgram emit(Project project) {
        return new MflwEmitter().build(project);
    }

    private EmittedProgram build(Project project) {
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

                    // Visual.Vars initial value
                    if (b.getDefinitionId().startsWith("Visual.Vars.")) {
                        String valStr = b.getCustomProperties().getOrDefault("CurrentValue", out.getConstantValue());
                        addInitEntry(slotId, out.getDataType(), valStr);
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
        ByteArrayOutputStream[] threadStreams = new ByteArrayOutputStream[MaraVm.THREAD_COUNT];
        for (int t = 0; t < MaraVm.THREAD_COUNT; t++) {
            threadStreams[t] = new ByteArrayOutputStream();
        }

        for (Page page : project.getPages()) {
            if (page.isMacro() || page.getLanguage() != IecLanguage.FBD) continue;
            List<BlockInstance> sorted = new ArrayList<>(page.getAllBlockInstances());
            sorted.sort(Comparator.comparingInt(BlockInstance::getOrder));
            for (BlockInstance b : sorted) {
                int tid = Math.clamp(b.getThreadId(), 1, 5);
                emitFbdBlock(threadStreams[tid], b, blockPinSlot, wiredSourceSlot);
            }
        }

        for (int t = 1; t < MaraVm.THREAD_COUNT; t++) {
            writeWord(threadStreams[t], MaraVm.OP_HALT, 0, MaraVm.SLOT_ALWAYS_ENABLED, 0, 0);
        }

        // 4. Assemble MFLW image: [64B Header] [InitTable] [Thread 1..5 Bytecode]
        int initTableOffset = MaraVm.HEADER_SIZE_BYTES;
        int initTableBytes = initEntries.size() * 12;
        int cursor = initTableOffset + initTableBytes;
        int[] threadOffsets = new int[MaraVm.THREAD_COUNT];
        for (int t = 1; t < MaraVm.THREAD_COUNT; t++) {
            threadOffsets[t] = cursor;
            cursor += threadStreams[t].size();
        }

        ByteBuffer img = ByteBuffer.allocate(cursor).order(ByteOrder.LITTLE_ENDIAN);
        img.putInt(0, MaraVm.MAGIC_MFLW);
        img.putShort(4, (short) MaraVm.FORMAT_VERSION);
        img.putShort(6, (short) 0x0407);
        img.putInt(8, cursor);
        // CRC32 filled after payload is written
        img.putInt(16, project.getProjectName() != null ? project.getProjectName().hashCode() : 0);
        img.putShort(20, (short) nextSlotIndex[MaraVm.TYPE_BOOL]);
        img.putShort(22, (short) nextSlotIndex[MaraVm.TYPE_INT]);
        img.putShort(24, (short) nextSlotIndex[MaraVm.TYPE_LONG]);
        img.putShort(26, (short) nextSlotIndex[MaraVm.TYPE_FLOAT]);
        img.putShort(28, (short) nextStateIdx);
        img.putInt(36, initTableOffset);
        img.putInt(40, initEntries.size());
        for (int t = 0; t < MaraVm.THREAD_COUNT; t++) {
            img.putInt(44 + (t * 4), threadOffsets[t]);
        }

        int pos = initTableOffset;
        for (InitEntry e : initEntries) {
            img.putShort(pos, (short) e.slotId);
            img.putShort(pos + 2, (short) 0);
            img.putLong(pos + 4, e.rawBits);
            pos += 12;
        }
        for (int t = 1; t < MaraVm.THREAD_COUNT; t++) {
            byte[] bc = threadStreams[t].toByteArray();
            System.arraycopy(bc, 0, img.array(), threadOffsets[t], bc.length);
        }

        byte[] raw = img.array();
        int crc32 = MaraVm.calcCrc32(raw, MaraVm.HEADER_SIZE_BYTES, raw.length - MaraVm.HEADER_SIZE_BYTES);
        img.putInt(12, crc32);

        return new EmittedProgram(raw, Map.copyOf(operandToSlot), Map.copyOf(slotToOperand));
    }

    private void emitFbdBlock(ByteArrayOutputStream out, BlockInstance b,
                              Map<String, Integer> blockPinSlot, Map<String, Integer> wiredSourceSlot) {
        String id = b.getDefinitionId();
        if (id == null || id.startsWith("Visual.Vars.")) return;

        boolean hasEn = !b.getInputs().isEmpty() && "En".equals(b.getInputs().get(0).getShortName());
        int enSlot = hasEn ? resolveInput(b, 0, wiredSourceSlot) : MaraVm.SLOT_ALWAYS_ENABLED;
        int firstArg = hasEn ? 1 : 0;

        switch (id) {
            case "Bool.And", "Bool.Nand", "Bool.Or", "Bool.Nor", "Bool.Xor", "Bool.Xnor" -> {
                int op = switch (id) {
                    case "Bool.And" -> MaraVm.OP_AND;
                    case "Bool.Nand" -> MaraVm.OP_NAND;
                    case "Bool.Or" -> MaraVm.OP_OR;
                    case "Bool.Nor" -> MaraVm.OP_NOR;
                    case "Bool.Xor" -> MaraVm.OP_XOR;
                    default -> MaraVm.OP_XNOR;
                };
                int dst = blockPinSlot.getOrDefault(b.getId() + ":OUT:0", 0);
                int argc = Math.max(2, b.getInputs().size() - firstArg);
                int srcA = resolveInput(b, firstArg, wiredSourceSlot);
                writeWord(out, op, argc, enSlot, dst, srcA);
                int extraSlots = argc - 1;
                int extraWords = (extraSlots + 1) / 2;
                for (int w = 0; w < extraWords; w++) {
                    int s1 = (1 + (w * 2) < argc) ? resolveInput(b, firstArg + 1 + (w * 2), wiredSourceSlot) : 0;
                    int s2 = (2 + (w * 2) < argc) ? resolveInput(b, firstArg + 2 + (w * 2), wiredSourceSlot) : 0;
                    writeU16(out, s1);
                    writeU16(out, s2);
                }
            }
            case "Bool.Not" -> {
                int dst = blockPinSlot.getOrDefault(b.getId() + ":OUT:0", 0);
                int srcA = resolveInput(b, firstArg, wiredSourceSlot);
                writeWord(out, MaraVm.OP_NOT, 0, enSlot, dst, srcA);
            }
            case "Bool.RS", "Bool.SR" -> {
                int op = id.equals("Bool.RS") ? MaraVm.OP_RS : MaraVm.OP_SR;
                int q = blockPinSlot.getOrDefault(b.getId() + ":OUT:0", 0);
                int nq = blockPinSlot.getOrDefault(b.getId() + ":OUT:1", MaraVm.SLOT_ALWAYS_ENABLED);
                int s = resolveInput(b, firstArg, wiredSourceSlot);
                int r = resolveInput(b, firstArg + 1, wiredSourceSlot);
                writeWord(out, op, 0, enSlot, q, s);
                writeU16(out, r);
                writeU16(out, nq);
            }
            case "Math.Add", "Math.Sub", "Math.Mul", "Math.Min", "Math.Max" -> {
                int op = switch (id) {
                    case "Math.Add" -> MaraVm.OP_ADD;
                    case "Math.Sub" -> MaraVm.OP_SUB;
                    case "Math.Mul" -> MaraVm.OP_MUL;
                    case "Math.Min" -> MaraVm.OP_MIN;
                    default -> MaraVm.OP_MAX;
                };
                int dst = blockPinSlot.getOrDefault(b.getId() + ":OUT:0", 0);
                int a = resolveInput(b, firstArg, wiredSourceSlot);
                int bIn = resolveInput(b, firstArg + 1, wiredSourceSlot);
                writeWord(out, op, 0, enSlot, dst, a);
                writeU16(out, bIn);
                writeU16(out, 0);
            }
            case "Math.Div", "Math.Mod" -> {
                int op = id.equals("Math.Div") ? MaraVm.OP_DIV : MaraVm.OP_MOD;
                int dst = blockPinSlot.getOrDefault(b.getId() + ":OUT:0", 0);
                int err = blockPinSlot.getOrDefault(b.getId() + ":OUT:1", MaraVm.SLOT_ALWAYS_ENABLED);
                int a = resolveInput(b, firstArg, wiredSourceSlot);
                int bIn = resolveInput(b, firstArg + 1, wiredSourceSlot);
                writeWord(out, op, 0, enSlot, dst, a);
                writeU16(out, bIn);
                writeU16(out, err);
            }
            case "Math.Abs", "Math.Neg", "Math.Sqrt", "Math.Sin", "Math.Cos" -> {
                int op = switch (id) {
                    case "Math.Abs" -> MaraVm.OP_ABS;
                    case "Math.Neg" -> MaraVm.OP_NEG;
                    case "Math.Sqrt" -> MaraVm.OP_SQRT;
                    case "Math.Sin" -> MaraVm.OP_SIN;
                    default -> MaraVm.OP_COS;
                };
                int dst = blockPinSlot.getOrDefault(b.getId() + ":OUT:0", 0);
                int a = resolveInput(b, firstArg, wiredSourceSlot);
                writeWord(out, op, 0, enSlot, dst, a);
            }
            case "Math.Clamp" -> {
                int dst = blockPinSlot.getOrDefault(b.getId() + ":OUT:0", 0);
                int inS = resolveInput(b, firstArg, wiredSourceSlot);
                int minS = resolveInput(b, firstArg + 1, wiredSourceSlot);
                int maxS = resolveInput(b, firstArg + 2, wiredSourceSlot);
                writeWord(out, MaraVm.OP_CLAMP, 0, enSlot, dst, inS);
                writeU16(out, minS);
                writeU16(out, maxS);
            }
            case "Main.Comparators.EQ", "Main.Comparators.NE", "Main.Comparators.GT",
                 "Main.Comparators.GE", "Main.Comparators.LT", "Main.Comparators.LE" -> {
                int op = switch (id) {
                    case "Main.Comparators.EQ" -> MaraVm.OP_CMP_EQ;
                    case "Main.Comparators.NE" -> MaraVm.OP_CMP_NE;
                    case "Main.Comparators.GT" -> MaraVm.OP_CMP_GT;
                    case "Main.Comparators.GE" -> MaraVm.OP_CMP_GE;
                    case "Main.Comparators.LT" -> MaraVm.OP_CMP_LT;
                    default -> MaraVm.OP_CMP_LE;
                };
                int dst = blockPinSlot.getOrDefault(b.getId() + ":OUT:0", 0);
                int a = resolveInput(b, firstArg, wiredSourceSlot);
                int bIn = resolveInput(b, firstArg + 1, wiredSourceSlot);
                writeWord(out, op, 0, enSlot, dst, a);
                writeU16(out, bIn);
                writeU16(out, 0);
            }
            case "Main.Switches.Sel" -> {
                int dst = blockPinSlot.getOrDefault(b.getId() + ":OUT:0", 0);
                int g = resolveInput(b, firstArg, wiredSourceSlot);
                int x0 = resolveInput(b, firstArg + 1, wiredSourceSlot);
                int x1 = resolveInput(b, firstArg + 2, wiredSourceSlot);
                writeWord(out, MaraVm.OP_SEL, 0, enSlot, dst, g);
                writeU16(out, x0);
                writeU16(out, x1);
            }
            case "Main.Timers.Timer" -> {
                int q = blockPinSlot.getOrDefault(b.getId() + ":OUT:0", 0);
                int et = blockPinSlot.getOrDefault(b.getId() + ":OUT:1", MaraVm.SLOT_ALWAYS_ENABLED);
                int inS = resolveInput(b, firstArg, wiredSourceSlot);
                int ptS = resolveInput(b, firstArg + 1, wiredSourceSlot);
                int stIdx = nextStateIdx++;
                writeWord(out, MaraVm.OP_TON, 0, enSlot, q, inS);
                writeU16(out, ptS);
                writeU16(out, et);
                writeU16(out, stIdx);
                writeU16(out, 0);
            }
            case "Main.Timers.SinglePulse" -> {
                int q = blockPinSlot.getOrDefault(b.getId() + ":OUT:0", 0);
                int inS = resolveInput(b, firstArg, wiredSourceSlot);
                int stIdx = nextStateIdx++;
                writeWord(out, MaraVm.OP_ONE_SHOT, 0, enSlot, q, inS);
                writeU16(out, stIdx);
                writeU16(out, 0);
            }
            case "IO.IREF" -> {
                int dst = blockPinSlot.getOrDefault(b.getId() + ":OUT:0", 0);
                int ch = parseIoChannel(b.getCustomProperties().getOrDefault("IoRef", "DI0"));
                int kind = isAnalogRef(b.getCustomProperties().getOrDefault("IoRef", "DI0")) ? MaraVm.IO_AI : MaraVm.IO_DI;
                writeWord(out, MaraVm.OP_IO_READ, kind, MaraVm.SLOT_ALWAYS_ENABLED, dst, ch);
            }
            case "IO.OREF" -> {
                int srcA = resolveInput(b, 0, wiredSourceSlot);
                int ch = parseIoChannel(b.getCustomProperties().getOrDefault("IoRef", "DO0"));
                int kind = isAnalogRef(b.getCustomProperties().getOrDefault("IoRef", "DO0")) ? MaraVm.IO_AO : MaraVm.IO_DO;
                writeWord(out, MaraVm.OP_IO_WRITE, kind, MaraVm.SLOT_ALWAYS_ENABLED, ch, srcA);
            }
            case "IO.PWM" -> {
                int act = blockPinSlot.getOrDefault(b.getId() + ":OUT:0", 0);
                int ch = parseIoChannel(b.getCustomProperties().getOrDefault("IoRef", "PWM0"));
                int frq = resolveInput(b, firstArg, wiredSourceSlot);
                int dty = resolveInput(b, firstArg + 1, wiredSourceSlot);
                writeWord(out, MaraVm.OP_IO_PWM, 0, enSlot, act, ch);
                writeU16(out, frq);
                writeU16(out, dty);
            }
            default -> {
                // Other specialized blocks (e.g. Visual.Value, Visual.Segment) need no VM instruction or map to MOV
                if ("Visual.Value".equals(id) && !b.getInputs().isEmpty()) {
                    int srcA = resolveInput(b, 0, wiredSourceSlot);
                    operandToSlot.put(b.getName() + ".In", srcA);
                }
            }
        }
    }

    private int resolveInput(BlockInstance b, int pinIndex, Map<String, Integer> wiredSourceSlot) {
        Integer wired = wiredSourceSlot.get(b.getId() + ":IN:" + pinIndex);
        if (wired != null) return wired;
        if (pinIndex >= b.getInputs().size()) return 0;
        PinInstance pin = b.getInputs().get(pinIndex);
        int constSlot = allocSlot(pin.getDataType());
        addInitEntry(constSlot, pin.getDataType(), pin.getConstantValue());
        return constSlot;
    }

    private int allocSlot(DataType dt) {
        int type = switch (dt) {
            case BOOL -> MaraVm.TYPE_BOOL;
            case INT -> MaraVm.TYPE_INT;
            case LONG -> MaraVm.TYPE_LONG;
            case FLOAT -> MaraVm.TYPE_FLOAT;
        };
        int idx = nextSlotIndex[type]++;
        return MaraVm.makeSlotId(type, idx);
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
