package cz.maramat.compiler;

import cz.maramat.model.BlockCatalog;
import cz.maramat.model.BlockDefinition;
import cz.maramat.model.BlockInstance;
import cz.maramat.model.DataType;
import cz.maramat.model.Page;
import cz.maramat.model.PinInstance;
import cz.maramat.model.PlcThread;
import cz.maramat.model.Project;
import cz.maramat.model.Wire;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public final class BytecodeCompiler {

    public record CompilationReport(
            int ramUsedBytes,
            int ramMaxBytes,
            int flashUsedBytes,
            int flashMaxBytes,
            int eepromUsedBytes,
            int eepromMaxBytes,
            int[] threadEstUs,
            int[] threadMaxUs,
            int totalCpuPercent,
            Map<DataType, Integer> ramBytesByDataType,
            int vmOverheadRamBytes,
            byte[] compiledBytecode,
            List<String> logLines
    ) {}

    private BytecodeCompiler() {}

    public static CompilationReport compile(Project project) {
        List<String> logs = new ArrayList<>();
        logs.add("Info: Target platform: " + project.getTargetPlatform() + " (STM32F407 @ 168MHz + FreeRTOS VM).");
        logs.add("Info: Building program definition.");

        Map<DataType, Integer> ramByType = new EnumMap<>(DataType.class);
        for (DataType dt : DataType.values()) {
            ramByType.put(dt, 0);
        }

        int totalModules = 0;
        int expandedModules = 0;
        int eepromBytes = 32; // Header in 64KiB EEPROM
        int flashBytes = 64;  // 32B VM image header + 32B thread vector table
        double[] threadUs = new double[6];

        int typeCastCount = 0;

        for (Page page : project.getPages()) {
            if (page.isMacro()) continue;
            for (BlockInstance b : page.getBlocks()) {
                totalModules++;
                expandedModules++;
                if (b.getDefinitionId().startsWith("Macro.User.")) {
                    expandedModules += 2;
                }

                // Allocate output slots in RAM by data type
                for (PinInstance out : b.getOutputs()) {
                    DataType dt = out.getDataType();
                    int bytes = switch (dt) {
                        case BOOL -> 1;
                        case INT -> 4;
                        case LONG -> 8;
                        case FLOAT -> 4;
                    };
                    ramByType.put(dt, ramByType.get(dt) + bytes);
                }

                // Allocate input constants & state slots
                for (PinInstance in : b.getInputs()) {
                    DataType dt = in.getDataType();
                    int bytes = switch (dt) {
                        case BOOL -> 1;
                        case INT -> 4;
                        case LONG -> 8;
                        case FLOAT -> 4;
                    };
                    ramByType.put(dt, ramByType.get(dt) + bytes);
                }

                // Retentive EEPROM registers or xVar parameters
                if (b.getDefinitionId().startsWith("Visual.Vars.")) {
                    eepromBytes += 8;
                } else if ("true".equalsIgnoreCase(b.getCustomProperties().get("RetentiveEeprom"))) {
                    eepromBytes += 8;
                }

                // Bytecode size and execution time cost
                BlockDefinition def = BlockCatalog.getById(b.getDefinitionId());
                double costUs = (def != null) ? def.getEstimatedCostUs() : 0.25;
                int tid = Math.clamp(b.getThreadId(), 1, 5);
                threadUs[tid] += costUs;
                flashBytes += 12 + (b.getInputs().size() * 4) + (b.getOutputs().size() * 2);
            }

            // Account for implicit type conversion instructions on split-color wires
            for (Wire w : page.getWires()) {
                BlockInstance src = page.findBlockById(w.getSourceBlockId());
                BlockInstance dst = page.findBlockById(w.getTargetBlockId());
                if (src != null && dst != null
                        && w.getSourcePinIndex() < src.getOutputs().size()
                        && w.getTargetPinIndex() < dst.getInputs().size()) {
                    DataType st = src.getOutputs().get(w.getSourcePinIndex()).getDataType();
                    DataType dt = dst.getInputs().get(w.getTargetPinIndex()).getDataType();
                    if (st != dt) {
                        typeCastCount++;
                        flashBytes += 8;
                        int tid = Math.clamp(dst.getThreadId(), 1, 5);
                        threadUs[tid] += 0.05;
                    }
                }
            }
        }

        int vmOverhead = 64 + (expandedModules * 4);
        int totalDataRam = ramByType.values().stream().mapToInt(Integer::intValue).sum();
        int totalRam = totalDataRam + vmOverhead;

        logs.add("Info: Building constant arrays in rom memory (" + flashBytes + " bytes).");
        logs.add("Info: Building parameters array in eeprom memory (" + eepromBytes + " / 65536 bytes).");
        if (typeCastCount > 0) {
            logs.add("Info: Inserted " + typeCastCount + " implicit data-type conversion opcode(s).");
        }
        logs.add("Info: Total " + totalModules + " modules, " + expandedModules + " expanded modules.");

        int[] threadEstUsInt = new int[6];
        int[] threadMaxUsInt = new int[6];
        double totalCpuPct = 0.0;

        for (PlcThread pt : PlcThread.values()) {
            int tid = pt.getId();
            threadEstUsInt[tid] = (int) Math.ceil(threadUs[tid]);
            threadMaxUsInt[tid] = pt.getMaxAllowedUs();
            if (tid <= 4) {
                totalCpuPct += (threadUs[tid] / pt.getMaxAllowedUs()) * 100.0;
            }
        }
        int cpuPctInt = Math.clamp((int) Math.ceil(totalCpuPct), (totalModules > 0 ? 1 : 0), 100);
        logs.add("Info: Compilation succeeded. Estimated CPU load: " + String.format(java.util.Locale.US, "%.2f%%", totalCpuPct));

        byte[] dummyImage = new byte[Math.min(flashBytes, 65536)];
        dummyImage[0] = 'M';
        dummyImage[1] = 'F';
        dummyImage[2] = 'L';
        dummyImage[3] = 'W';

        return new CompilationReport(
                totalRam, 32768,
                flashBytes, 65536,
                eepromBytes, 65536,
                threadEstUsInt, threadMaxUsInt,
                cpuPctInt,
                ramByType,
                vmOverhead,
                dummyImage,
                logs
        );
    }
}
