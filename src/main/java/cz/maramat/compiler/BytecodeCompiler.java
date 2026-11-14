package cz.maramat.compiler;

import cz.maramat.model.BlockCatalog;
import cz.maramat.model.BlockDefinition;
import cz.maramat.model.BlockInstance;
import cz.maramat.model.DataType;
import cz.maramat.model.EStopConfig;
import cz.maramat.model.IecLanguage;
import cz.maramat.model.LadderBranch;
import cz.maramat.model.LadderElement;
import cz.maramat.model.LadderRung;
import cz.maramat.model.Page;
import cz.maramat.model.PinInstance;
import cz.maramat.model.PlcThread;
import cz.maramat.model.Project;
import cz.maramat.model.StVariable;
import cz.maramat.model.Wire;
import cz.maramat.sim.StInterpreter;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public final class BytecodeCompiler {

    public static final int TARGET_SRAM_MAX_BYTES = 131_072;      // STM32F407 128 KiB Main SRAM
    public static final int TARGET_SRAM_FW_BYTES = 82_096;        // Static firmware .data+.bss+heap/stack (-Os)
    public static final int TARGET_CCMRAM_MAX_BYTES = 65_536;     // STM32F407 64 KiB CCMRAM
    public static final int TARGET_CCMRAM_FW_BYTES = 59_824;      // Static g_plc_hal_vm + g_plc_hal_prot
    public static final int TARGET_FLASH_MAX_BYTES = 524_288;     // STM32F407 512 KiB Flash
    public static final int TARGET_FLASH_FW_BYTES = 196_256;      // Static firmware .text+.rodata+.data (-Os)
    public static final int TARGET_VM_IMAGE_MAX_BYTES = 28_672;   // VM_MAX_IMAGE_BYTES (28 KiB)
    public static final int TARGET_EEPROM_MAX_BYTES = 32_768;     // BL24C256A 32 KiB I2C EEPROM
    public static final int TARGET_VM_SLOT_RAM_MAX_BYTES = 20_480; // 2048B+4096B+2048B+4096B+8192B state

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
        int ladderRungCount = 0;
        int ladderContactCoilCount = 0;
        int stPageCount = 0;
        int eepromBytes = 32; // Header in 64KiB EEPROM
        int flashBytes = 64;  // 32B VM image header + 32B thread vector table
        double[] threadUs = new double[6];

        int typeCastCount = 0;

        EStopConfig eStopCfg = project.getEStopConfig();
        if (eStopCfg.isEnabled()) {
            flashBytes += 24;
            logs.add("Info: Safety PLC E-Stop supervision enabled (" + eStopCfg.getMode().getDisplayName() + ").");
        }

        for (Page page : project.getPages()) {
            if (page.getLanguage() == IecLanguage.ST) {
                stPageCount++;
                List<StInterpreter.Diagnostic> diags = StInterpreter.validate(page.getStCode(), page.getStVariables());
                for (StInterpreter.Diagnostic d : diags) {
                    logs.add("Warning [" + page.getName() + "]: " + d);
                }
                int tid = Math.clamp(page.getStThreadId(), 1, 5);
                for (StVariable v : page.getStVariables()) {
                    DataType dt = v.getDataType();
                    int bytes = switch (dt) {
                        case BOOL -> 1;
                        case INT -> 4;
                        case LONG -> 8;
                        case FLOAT -> 4;
                    };
                    ramByType.put(dt, ramByType.get(dt) + bytes);
                }
                int stLines = Math.max(1, page.getStCode().split("\n").length);
                totalModules++;
                expandedModules += Math.max(1, stLines / 2);
                flashBytes += 16 + (stLines * 8);
                if (!page.isMacro()) {
                    threadUs[tid] += 0.15 + (stLines * 0.04);
                }
            }

            if (page.isMacro()) continue;

            if (page.getLanguage() == IecLanguage.LD) {
                for (LadderRung rung : page.getRungs()) {
                    ladderRungCount++;
                    int tid = Math.clamp(rung.getThreadId(), 1, 5);
                    flashBytes += 8; // Rung header opcode
                    threadUs[tid] += 0.05;

                    for (LadderElement el : rung.getMainElements()) {
                        if (el.getKind().isContact() || el.getKind().isCoil()) {
                            ladderContactCoilCount++;
                            totalModules++;
                            expandedModules++;
                            DataType ldType = el.getKind() == LadderElement.Kind.ANALOG_COIL ? DataType.INT : DataType.BOOL;
                            ramByType.put(ldType, ramByType.get(ldType) + (ldType == DataType.INT ? 4 : 1));
                            flashBytes += 6;
                            threadUs[tid] += 0.06;
                        }
                    }
                    for (LadderBranch br : rung.getParallelBranches()) {
                        flashBytes += 4;
                        for (LadderElement el : br.getElements()) {
                            if (el.getKind().isContact() || el.getKind().isCoil()) {
                                ladderContactCoilCount++;
                                totalModules++;
                                expandedModules++;
                                DataType ldType = el.getKind() == LadderElement.Kind.ANALOG_COIL ? DataType.INT : DataType.BOOL;
                                ramByType.put(ldType, ramByType.get(ldType) + (ldType == DataType.INT ? 4 : 1));
                                flashBytes += 6;
                                threadUs[tid] += 0.06;
                            }
                        }
                    }
                    for (LadderElement coil : rung.getCoils()) {
                        ladderContactCoilCount++;
                        totalModules++;
                        expandedModules++;
                        DataType ldType = coil.getKind() == LadderElement.Kind.ANALOG_COIL ? DataType.INT : DataType.BOOL;
                        ramByType.put(ldType, ramByType.get(ldType) + (ldType == DataType.INT ? 4 : 1));
                        flashBytes += 6;
                        threadUs[tid] += 0.06;
                    }
                }
            }

            for (BlockInstance b : page.getAllBlockInstances()) {
                totalModules++;
                expandedModules++;
                if (b.getDefinitionId().startsWith("Macro.User.")) {
                    expandedModules += 2;
                }

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

                if (b.getDefinitionId().startsWith("Visual.Vars.")) {
                    eepromBytes += 8;
                } else if ("true".equalsIgnoreCase(b.getCustomProperties().get("RetentiveEeprom"))) {
                    eepromBytes += 8;
                }

                BlockDefinition def = BlockCatalog.getById(b.getDefinitionId());
                double costUs = (def != null) ? def.getEstimatedCostUs() : 0.25;
                int tid = Math.clamp(b.getThreadId(), 1, 5);
                threadUs[tid] += costUs;
                flashBytes += 12 + (b.getInputs().size() * 4) + (b.getOutputs().size() * 2);
            }

            for (Wire w : page.getWires()) {
                BlockInstance src = page.findBlockById(w.getSourceBlockId());
                BlockInstance dst = page.findBlockById(w.getTargetBlockId());
                if (src != null && dst != null
                        && w.getSourcePinIndex() >= 0 && w.getSourcePinIndex() < src.getOutputs().size()
                        && w.getTargetPinIndex() >= 0 && w.getTargetPinIndex() < dst.getInputs().size()) {
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

        MflwEmitter.EmittedProgram emitted = MflwEmitter.emit(project);
        byte[] image = emitted.image();

        if (ladderRungCount > 0) {
            logs.add("Info: Compiled " + ladderRungCount + " IEC 61131-3 Ladder rung(s) (" + ladderContactCoilCount + " contacts/coils).");
        }
        if (stPageCount > 0) {
            logs.add("Info: Compiled " + stPageCount + " IEC 61131-3 Structured Text (ST) unit(s).");
        }
        logs.add("Info: Building bytecode image (" + image.length + " / " + TARGET_VM_IMAGE_MAX_BYTES + " bytes max).");
        logs.add("Info: Target STM32F407 SRAM: " + TARGET_SRAM_FW_BYTES + " / " + TARGET_SRAM_MAX_BYTES
                + " B, CCMRAM: " + TARGET_CCMRAM_FW_BYTES + " / " + TARGET_CCMRAM_MAX_BYTES
                + " B, Flash: " + TARGET_FLASH_FW_BYTES + " / " + TARGET_FLASH_MAX_BYTES + " B.");
        logs.add("Info: Building parameters array in 32KiB EEPROM (" + eepromBytes + " / " + TARGET_EEPROM_MAX_BYTES + " bytes).");
        if (image.length > TARGET_VM_IMAGE_MAX_BYTES) {
            logs.add("Error: Bytecode image (" + image.length + " B) exceeds Duino - S limit (" + TARGET_VM_IMAGE_MAX_BYTES + " B)!");
        }
        if (eepromBytes > TARGET_EEPROM_MAX_BYTES) {
            logs.add("Error: EEPROM usage (" + eepromBytes + " B) exceeds BL24C256A capacity (" + TARGET_EEPROM_MAX_BYTES + " B)!");
        }
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

        return new CompilationReport(
                totalRam, TARGET_VM_SLOT_RAM_MAX_BYTES,
                image.length, TARGET_VM_IMAGE_MAX_BYTES,
                eepromBytes, TARGET_EEPROM_MAX_BYTES,
                threadEstUsInt, threadMaxUsInt,
                cpuPctInt,
                ramByType,
                vmOverhead,
                image,
                logs
        );
    }

    public static MflwEmitter.EmittedProgram compileProgram(Project project) {
        return MflwEmitter.emit(project);
    }
}
