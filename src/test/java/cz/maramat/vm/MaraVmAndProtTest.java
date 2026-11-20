package cz.maramat.vm;

import cz.maramat.comm.MaraProtClient;
import cz.maramat.compiler.BytecodeCompiler;
import cz.maramat.compiler.MflwEmitter;
import cz.maramat.io.MflProjectSerializer;
import cz.maramat.model.BlockCatalog;
import cz.maramat.model.BlockInstance;
import cz.maramat.model.Page;
import cz.maramat.model.Project;
import cz.maramat.model.Wire;
import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

class MaraVmAndProtTest {

    @Test
    void compileAndExecuteFbdOnNativeCMaraVm() {
        Project proj = new Project();
        Page page = proj.getPages().getFirst();

        BlockInstance andBlock = new BlockInstance(BlockCatalog.getById("Bool.And"), "And1", 10, 10, 2, 10);
        andBlock.getInputs().get(1).setConstantValue("1");
        andBlock.getInputs().get(2).setConstantValue("1");

        BlockInstance oref = new BlockInstance(BlockCatalog.getById("IO.OREF"), "DoOut", 20, 10, 2, 20);
        oref.setIoRefChannel("DO3");

        page.getBlocks().add(andBlock);
        page.getBlocks().add(oref);
        page.getWires().add(new Wire(andBlock.getId(), 0, oref.getId(), 0, "w1", new Point(10, 10), new Point(20, 10)));

        MflwEmitter.EmittedProgram emitted = BytecodeCompiler.compileProgram(proj);
        assertTrue(emitted.image().length > NativeMaraVm.HEADER_SIZE_BYTES);
        assertEquals('M', (char) emitted.image()[0]);
        assertEquals('F', (char) emitted.image()[1]);
        assertEquals('L', (char) emitted.image()[2]);
        assertEquals('W', (char) emitted.image()[3]);

        Integer slotId = emitted.operandToSlot().get("And1.Y");
        assertNotNull(slotId);

        try (NativeMaraVm nativeVm = new NativeMaraVm()) {
            assertEquals(0, nativeVm.loadImage(emitted.image()));
            assertEquals(0, nativeVm.setState(NativeMaraVm.RunState.RUNNING));
            assertEquals(0, nativeVm.stepThread(2, 100_000));
            assertTrue(nativeVm.getVirtualDo(3));
            assertEquals(1.0, nativeVm.readSlotAsDouble(slotId));
        }
    }

    @Test
    void maraProtFrameCrc16RoundTrip() {
        byte[] frame = MaraProtClient.encodeFrame(0x00, 42, MaraProtClient.OP_PING, new byte[]{1, 2, 3, 4});
        assertEquals(MaraProtClient.MAGIC_0, frame[0] & 0xFF);
        assertEquals(MaraProtClient.MAGIC_1, frame[1] & 0xFF);
        assertEquals(16, frame.length);

        int calc = MaraProtClient.calcCrc16(frame, frame.length - 2);
        int rx = (frame[frame.length - 2] & 0xFF) | ((frame[frame.length - 1] & 0xFF) << 8);
        assertEquals(calc, rx);
    }

    @Test
    void kyvadlovyProvozStMacroCompilesAndRunsOnNativeCMaraVm() throws Exception {
        File mflFile = new File("/Users/matoussmekal/Downloads/Kyvadlovy provoz.mfl");
        if (!mflFile.exists()) return;

        Project proj = MflProjectSerializer.loadProject(mflFile);
        MflwEmitter.EmittedProgram emitted = BytecodeCompiler.compileProgram(proj);

        try (NativeMaraVm vm = new NativeMaraVm()) {
            assertEquals(0, vm.loadImage(emitted.image()));
            assertEquals(0, vm.setState(NativeMaraVm.RunState.RUNNING));
            for (int i = 0; i < 30; i++) {
                assertEquals(0, vm.stepThread(2, 100_000));
            }
        }
    }

    @Test
    void negatedFbdPinsCompileAndExecuteOnNativeCMaraVm() {
        Project proj = new Project();
        Page page = proj.getPages().getFirst();

        BlockInstance andBlock = new BlockInstance(BlockCatalog.getById("Bool.And"), "And1", 10, 10, 2, 10);
        andBlock.getInputs().get(1).setConstantValue("1");
        andBlock.getInputs().get(2).setConstantValue("0");
        andBlock.getInputs().get(2).setNegated(true); // 0 negated -> 1, so And1 raw = 1
        andBlock.getOutputs().get(0).setNegated(true); // And1.Y negated -> 0

        BlockInstance oref = new BlockInstance(BlockCatalog.getById("IO.OREF"), "DoOut", 20, 10, 2, 20);
        oref.setIoRefChannel("DO4");
        oref.getInputs().get(0).setNegated(true); // wired 0 negated -> 1 written to DO4

        page.getBlocks().add(andBlock);
        page.getBlocks().add(oref);
        page.getWires().add(new Wire(andBlock.getId(), 0, oref.getId(), 0, "w1", new Point(10, 10), new Point(20, 10)));

        MflwEmitter.EmittedProgram emitted = BytecodeCompiler.compileProgram(proj);
        Integer andOutSlot = emitted.operandToSlot().get("And1.Y");
        assertNotNull(andOutSlot);

        try (NativeMaraVm vm = new NativeMaraVm()) {
            assertEquals(0, vm.loadImage(emitted.image()));
            assertEquals(0, vm.setState(NativeMaraVm.RunState.RUNNING));
            assertEquals(0, vm.stepThread(2, 100_000));

            assertEquals(0.0, vm.readSlotAsDouble(andOutSlot), "Negated And1.Y slot should be 0.0");
            assertTrue(vm.getVirtualDo(4), "Negated OREF input pin should invert 0.0 to true on DO4");
        }
    }

    @Test
    void testShieldPeripheralsCompileAndRunInNativeVm() {
        Project proj = new Project("shield_test", "Duino - S", cz.maramat.model.IecLanguage.FBD, 2);
        assertEquals("Duino - S", proj.getTargetPlatform());
        Page page = proj.getPages().getFirst();

        BlockInstance tmp75 = new BlockInstance(BlockCatalog.getById("Comm.I2C.TMP75"), "TMP1", 10, 10, 2, 10);
        tmp75.getCustomProperties().put("DeviceAddressHex", "0x4A");

        BlockInstance ds = new BlockInstance(BlockCatalog.getById("Comm.DS18B20.DS18B20Read"), "DS1", 10, 20, 2, 20);
        ds.getCustomProperties().put("OneWirePin", "DIO13");
        ds.getCustomProperties().put("RomAddress", "28-AABBCCDDEEFF01");

        BlockInstance chRaw = new BlockInstance(BlockCatalog.getById("IO.CH455G"), "SEG1", 10, 30, 2, 30);
        chRaw.configureCh455gPins("Raw Digits / Segments", "1011");
        assertEquals(6, chRaw.getInputs().size(), "1011 mask should expose En, D0, D2, D3, DpMask, Brt");
        assertEquals("D0", chRaw.getInputs().get(1).getShortName());
        assertEquals("D2", chRaw.getInputs().get(2).getShortName());
        assertEquals("D3", chRaw.getInputs().get(3).getShortName());

        BlockInstance chNum = new BlockInstance(BlockCatalog.getById("IO.CH455G"), "SEG2", 10, 45, 2, 40);
        chNum.configureCh455gPins("Number (Float / Int)", "0111");
        assertEquals(4, chNum.getInputs().size(), "Number mode should expose En, Val, Dec, Brt");

        page.getBlocks().addAll(java.util.List.of(tmp75, ds, chRaw, chNum));

        MflwEmitter.EmittedProgram emitted = BytecodeCompiler.compileProgram(proj);
        Integer tmpSlot = emitted.operandToSlot().get("TMP1.Tmp");
        Integer tmpOkSlot = emitted.operandToSlot().get("TMP1.Ok");
        Integer dsSlot = emitted.operandToSlot().get("DS1.Tmp");
        Integer seg1ActSlot = emitted.operandToSlot().get("SEG1.Act");
        Integer seg2ActSlot = emitted.operandToSlot().get("SEG2.Act");
        assertNotNull(tmpSlot);
        assertNotNull(dsSlot);
        assertNotNull(seg1ActSlot);
        assertNotNull(seg2ActSlot);

        try (NativeMaraVm vm = new NativeMaraVm()) {
            assertEquals(0, vm.loadImage(emitted.image()));
            assertEquals(0, vm.setState(NativeMaraVm.RunState.RUNNING));
            assertEquals(0, vm.stepThread(2, 100_000));

            assertEquals(25.0, vm.readSlotAsDouble(tmpSlot), 0.01);
            assertEquals(1.0, vm.readSlotAsDouble(tmpOkSlot));
            assertEquals(22.5, vm.readSlotAsDouble(dsSlot), 0.01);
            assertEquals(1.0, vm.readSlotAsDouble(seg1ActSlot));
            assertEquals(1.0, vm.readSlotAsDouble(seg2ActSlot));
        }
    }

    @Test
    void pulseGenCompilesAndExecutesOnNativeCMaraVm() {
        Project proj = new Project();
        Page page = proj.getPages().getFirst();

        BlockInstance enIn = new BlockInstance(BlockCatalog.getById("IO.IREF"), "EnIn", 5, 10, 2, 10);
        enIn.setIoRefChannel("DI0");

        BlockInstance pg = new BlockInstance(BlockCatalog.getById("Main.Timers.PulseGen"), "Pg1", 15, 10, 2, 20);
        pg.getInputs().get(1).setConstantValue("300"); // THi = 300 ms
        pg.getInputs().get(2).setConstantValue("200"); // TLo = 200 ms

        BlockInstance oref = new BlockInstance(BlockCatalog.getById("IO.OREF"), "DoOut", 25, 10, 2, 30);
        oref.setIoRefChannel("DO0");

        page.getBlocks().addAll(java.util.List.of(enIn, pg, oref));
        page.getWires().add(new Wire(enIn.getId(), 0, pg.getId(), 0, "w1", new Point(5, 10), new Point(15, 10)));
        page.getWires().add(new Wire(pg.getId(), 0, oref.getId(), 0, "w2", new Point(15, 10), new Point(25, 10)));

        MflwEmitter.EmittedProgram emitted = BytecodeCompiler.compileProgram(proj);
        Integer qSlot = emitted.operandToSlot().get("Pg1.Q");
        assertNotNull(qSlot);

        try (NativeMaraVm vm = new NativeMaraVm()) {
            assertEquals(0, vm.loadImage(emitted.image()));
            assertEquals(0, vm.setState(NativeMaraVm.RunState.RUNNING));

            vm.setVirtualDi(0, true);
            // Step 1: pos=100 < 300 -> 1
            assertEquals(0, vm.stepThread(2, 100_000));
            assertEquals(1.0, vm.readSlotAsDouble(qSlot));
            assertTrue(vm.getVirtualDo(0));

            // Step 2: pos=200 < 300 -> 1
            assertEquals(0, vm.stepThread(2, 100_000));
            assertEquals(1.0, vm.readSlotAsDouble(qSlot));

            // Step 3: pos=300 >= 300 -> 0
            assertEquals(0, vm.stepThread(2, 100_000));
            assertEquals(0.0, vm.readSlotAsDouble(qSlot));
            assertFalse(vm.getVirtualDo(0));

            // Step 4: pos=400 >= 300 -> 0
            assertEquals(0, vm.stepThread(2, 100_000));
            assertEquals(0.0, vm.readSlotAsDouble(qSlot));

            // Step 5: pos=(400+100)%500=0 < 300 -> 1
            assertEquals(0, vm.stepThread(2, 100_000));
            assertEquals(1.0, vm.readSlotAsDouble(qSlot));

            // Disable En -> Q=0 and phase resets
            vm.setVirtualDi(0, false);
            assertEquals(0, vm.stepThread(2, 100_000));
            assertEquals(0.0, vm.readSlotAsDouble(qSlot));
            assertFalse(vm.getVirtualDo(0));

            // Re-enable En -> starts from pos=100 < 300 -> 1
            vm.setVirtualDi(0, true);
            assertEquals(0, vm.stepThread(2, 100_000));
            assertEquals(1.0, vm.readSlotAsDouble(qSlot));
        }
    }

    @Test
    void mathFloatDivScaleRandomPidAndMultiInputAdd() {
        Project proj = new Project();
        Page page = proj.getPages().getFirst();

        BlockInstance div = new BlockInstance(BlockCatalog.getById("Math.Div"), "Div1", 5, 5, 2, 10);
        div.getInputs().get(1).setDataType(cz.maramat.model.DataType.FLOAT);
        div.getInputs().get(2).setDataType(cz.maramat.model.DataType.FLOAT);
        div.getOutputs().get(0).setDataType(cz.maramat.model.DataType.FLOAT);
        div.getInputs().get(1).setConstantValue("5");
        div.getInputs().get(2).setConstantValue("10");

        BlockInstance add3 = new BlockInstance(BlockCatalog.getById("Math.Add"), "Add3", 5, 15, 2, 20);
        add3.getInputs().add(new cz.maramat.model.PinInstance("X3", "Input 3", cz.maramat.model.DataType.INT, true, "0"));
        add3.getInputs().get(1).setConstantValue("10");
        add3.getInputs().get(2).setConstantValue("20");
        add3.getInputs().get(3).setConstantValue("30");

        BlockInstance scale = new BlockInstance(BlockCatalog.getById("Math.Scale"), "Scale1", 5, 25, 2, 30);
        scale.getInputs().get(1).setConstantValue("50");
        scale.getInputs().get(2).setConstantValue("0");
        scale.getInputs().get(3).setConstantValue("100");
        scale.getInputs().get(4).setConstantValue("0");
        scale.getInputs().get(5).setConstantValue("10");

        BlockInstance rnd = new BlockInstance(BlockCatalog.getById("Math.Random"), "Rnd1", 5, 35, 2, 40);
        rnd.getInputs().get(2).setConstantValue("10");
        rnd.getInputs().get(3).setConstantValue("20");

        BlockInstance pid = new BlockInstance(BlockCatalog.getById("Math.PID"), "Pid1", 5, 45, 2, 50);
        pid.getInputs().get(1).setConstantValue("20"); // PV
        pid.getInputs().get(2).setConstantValue("30"); // SP
        pid.getInputs().get(3).setConstantValue("2");  // Kp

        page.getBlocks().addAll(java.util.List.of(div, add3, scale, rnd, pid));

        MflwEmitter.EmittedProgram emitted = BytecodeCompiler.compileProgram(proj);
        try (NativeMaraVm vm = new NativeMaraVm()) {
            assertEquals(0, vm.loadImage(emitted.image()));
            assertEquals(0, vm.setState(NativeMaraVm.RunState.RUNNING));
            assertEquals(0, vm.stepThread(2, 100_000));

            assertEquals(0.5, vm.readSlotAsDouble(emitted.operandToSlot().get("Div1.Y")), 1e-4);
            assertEquals(0.0, vm.readSlotAsDouble(emitted.operandToSlot().get("Div1.Err")));
            assertEquals(60.0, vm.readSlotAsDouble(emitted.operandToSlot().get("Add3.Y")));
            assertEquals(5.0, vm.readSlotAsDouble(emitted.operandToSlot().get("Scale1.Y")), 1e-4);
            double rVal = vm.readSlotAsDouble(emitted.operandToSlot().get("Rnd1.Y"));
            assertTrue(rVal >= 10.0 && rVal <= 20.0, "Random out of range: " + rVal);
            assertEquals(20.0, vm.readSlotAsDouble(emitted.operandToSlot().get("Pid1.Out")), 1e-3);
        }
    }

    @Test
    void timersTofTpPulseLenAndCounter() {
        Project proj = new Project();
        Page page = proj.getPages().getFirst();

        BlockInstance di = new BlockInstance(BlockCatalog.getById("IO.IREF"), "In1", 2, 5, 2, 5);
        di.setIoRefChannel("DI0");

        BlockInstance tof = new BlockInstance(BlockCatalog.getById("Main.Timers.Timer"), "Tof1", 10, 5, 2, 10);
        tof.getCustomProperties().put("TimerMode", "TOF");
        tof.getInputs().get(2).setConstantValue("250");

        BlockInstance plsLen = new BlockInstance(BlockCatalog.getById("Main.Timers.PulseLen"), "Pls1", 10, 15, 2, 20);
        plsLen.getInputs().get(2).setConstantValue("250");

        BlockInstance cntr = new BlockInstance(BlockCatalog.getById("Main.Timers.Counter"), "Cnt1", 10, 25, 2, 30);
        cntr.getInputs().get(4).setConstantValue("2"); // PV = 2

        page.getBlocks().addAll(java.util.List.of(di, tof, plsLen, cntr));
        page.getWires().add(new Wire(di.getId(), 0, tof.getId(), 1, "w1", new Point(2, 5), new Point(10, 5)));
        page.getWires().add(new Wire(di.getId(), 0, plsLen.getId(), 1, "w2", new Point(2, 5), new Point(10, 15)));
        page.getWires().add(new Wire(di.getId(), 0, cntr.getId(), 1, "w3", new Point(2, 5), new Point(10, 25)));

        MflwEmitter.EmittedProgram emitted = BytecodeCompiler.compileProgram(proj);
        Integer tofQ = emitted.operandToSlot().get("Tof1.Q");
        Integer plsQ = emitted.operandToSlot().get("Pls1.Q");
        Integer cntQ = emitted.operandToSlot().get("Cnt1.Q");
        Integer cntCv = emitted.operandToSlot().get("Cnt1.CV");

        try (NativeMaraVm vm = new NativeMaraVm()) {
            assertEquals(0, vm.loadImage(emitted.image()));
            assertEquals(0, vm.setState(NativeMaraVm.RunState.RUNNING));

            // DI0 = 1 -> TOF Q=1, PulseLen Q=1, Counter CV=1
            vm.setVirtualDi(0, true);
            assertEquals(0, vm.stepThread(2, 100_000));
            assertEquals(1.0, vm.readSlotAsDouble(tofQ));
            assertEquals(1.0, vm.readSlotAsDouble(plsQ));
            assertEquals(1.0, vm.readSlotAsDouble(cntCv));
            assertEquals(0.0, vm.readSlotAsDouble(cntQ));

            // DI0 stays 1 -> Counter stays CV=1 (edge-triggered)
            assertEquals(0, vm.stepThread(2, 100_000));
            assertEquals(1.0, vm.readSlotAsDouble(cntCv));

            // DI0 = 0 -> TOF Q stays 1 (ET=100 < 250), PulseLen Q stays 1 (ET=300? wait: 100+100+100=300 >= 250 -> 0)
            vm.setVirtualDi(0, false);
            assertEquals(0, vm.stepThread(2, 100_000));
            assertEquals(1.0, vm.readSlotAsDouble(tofQ));
            assertEquals(0.0, vm.readSlotAsDouble(plsQ));

            // Wait another 200ms with DI0=0 -> TOF expires (ET=300 >= 250) -> Q=0
            assertEquals(0, vm.stepThread(2, 200_000));
            assertEquals(0.0, vm.readSlotAsDouble(tofQ));

            // DI0 = 1 again -> Counter CV=2 >= PV=2 -> Q=1
            vm.setVirtualDi(0, true);
            assertEquals(0, vm.stepThread(2, 100_000));
            assertEquals(2.0, vm.readSlotAsDouble(cntCv));
            assertEquals(1.0, vm.readSlotAsDouble(cntQ));
        }
    }

    @Test
    void comparatorsFiltersSwitchesArraysAndRegs() {
        Project proj = new Project();
        Page page = proj.getPages().getFirst();

        BlockInstance inRng = new BlockInstance(BlockCatalog.getById("Main.Comparators.InRange"), "Rng1", 2, 5, 2, 10);
        inRng.getInputs().get(1).setConstantValue("50");
        inRng.getInputs().get(2).setConstantValue("10");
        inRng.getInputs().get(3).setConstantValue("100");

        BlockInstance hyst = new BlockInstance(BlockCatalog.getById("Main.Comparators.Hysteresis"), "Hys1", 2, 10, 2, 20);
        hyst.getInputs().get(1).setConstantValue("25");
        hyst.getInputs().get(2).setConstantValue("10");
        hyst.getInputs().get(3).setConstantValue("20");

        BlockInstance movAvg = new BlockInstance(BlockCatalog.getById("Main.Filters.MovingAvg"), "Avg1", 2, 15, 2, 30);
        movAvg.getCustomProperties().put("WindowSize", "2");
        movAvg.getInputs().get(1).setConstantValue("10");

        BlockInstance demux = new BlockInstance(BlockCatalog.getById("Main.Switches.Demux"), "Dmx1", 2, 20, 2, 40);
        demux.getInputs().get(1).setConstantValue("1");
        demux.getInputs().get(2).setConstantValue("77");

        BlockInstance wrArr = new BlockInstance(BlockCatalog.getById("Main.Arrays.ArrayWrite"), "Aw1", 2, 25, 2, 50);
        wrArr.getInputs().get(1).setConstantValue("3");
        wrArr.getInputs().get(2).setConstantValue("42");

        BlockInstance rdArr = new BlockInstance(BlockCatalog.getById("Main.Arrays.ArrayRead"), "Ar1", 10, 25, 2, 60);
        rdArr.getInputs().get(1).setConstantValue("3");

        BlockInstance wrReg = new BlockInstance(BlockCatalog.getById("Main.Execution.WriteReg"), "Wr1", 2, 30, 2, 70);
        wrReg.getInputs().get(1).setConstantValue("99");

        BlockInstance rdReg = new BlockInstance(BlockCatalog.getById("Main.Execution.ReadReg"), "Rd1", 10, 30, 2, 80);

        BlockInstance estop = new BlockInstance(BlockCatalog.getById("Main.Safety.EStop"), "Es1", 2, 35, 2, 90);

        page.getBlocks().addAll(java.util.List.of(inRng, hyst, movAvg, demux, wrArr, rdArr, wrReg, rdReg, estop));

        MflwEmitter.EmittedProgram emitted = BytecodeCompiler.compileProgram(proj);
        try (NativeMaraVm vm = new NativeMaraVm()) {
            assertEquals(0, vm.loadImage(emitted.image()));
            assertEquals(0, vm.setState(NativeMaraVm.RunState.RUNNING));
            assertEquals(0, vm.stepThread(2, 100_000));

            assertEquals(1.0, vm.readSlotAsDouble(emitted.operandToSlot().get("Rng1.Y")));
            assertEquals(1.0, vm.readSlotAsDouble(emitted.operandToSlot().get("Hys1.Y")));
            assertEquals(10.0, vm.readSlotAsDouble(emitted.operandToSlot().get("Avg1.Y")), 1e-4);
            assertEquals(0.0, vm.readSlotAsDouble(emitted.operandToSlot().get("Dmx1.Y0")));
            assertEquals(77.0, vm.readSlotAsDouble(emitted.operandToSlot().get("Dmx1.Y1")));
            assertEquals(0.0, vm.readSlotAsDouble(emitted.operandToSlot().get("Aw1.Oob")));
            assertEquals(42.0, vm.readSlotAsDouble(emitted.operandToSlot().get("Ar1.Val")));
            assertEquals(99.0, vm.readSlotAsDouble(emitted.operandToSlot().get("Rd1.Out")));
            assertEquals(1.0, vm.readSlotAsDouble(emitted.operandToSlot().get("Es1.Safe")));
        }
    }

    @Test
    void ladderRungAndStCompileAndExecuteOnNativeCMaraVm() {
        Project proj = new Project();
        proj.getPages().clear();

        // 1. LD page: DI0 OR DI1 -> DO0
        Page ldPage = new Page("MainLD", cz.maramat.model.IecLanguage.LD);
        cz.maramat.model.LadderRung rung = new cz.maramat.model.LadderRung(2, 0, "OR rung");
        rung.getMainElements().add(new cz.maramat.model.LadderElement(cz.maramat.model.LadderElement.Kind.NO_CONTACT, "DI0"));
        cz.maramat.model.LadderBranch br = new cz.maramat.model.LadderBranch(0, 0);
        br.getElements().add(new cz.maramat.model.LadderElement(cz.maramat.model.LadderElement.Kind.NO_CONTACT, "DI1"));
        rung.getParallelBranches().add(br);
        rung.getCoils().add(new cz.maramat.model.LadderElement(cz.maramat.model.LadderElement.Kind.NORMAL_COIL, "DO0"));
        ldPage.getRungs().add(rung);

        // 2. ST page: FOR loop + ABS + TON
        Page stPage = new Page("MainST", cz.maramat.model.IecLanguage.ST);
        stPage.setStThreadId(2);
        stPage.getStVariables().add(new cz.maramat.model.StVariable(cz.maramat.model.StVariable.Scope.VAR, "i", cz.maramat.model.DataType.INT, "0", ""));
        stPage.getStVariables().add(new cz.maramat.model.StVariable(cz.maramat.model.StVariable.Scope.VAR, "sum", cz.maramat.model.DataType.INT, "0", ""));
        stPage.getStVariables().add(new cz.maramat.model.StVariable(cz.maramat.model.StVariable.Scope.VAR, "absVal", cz.maramat.model.DataType.INT, "0", ""));
        stPage.setStCode("""
                sum := 0;
                FOR i := 1 TO 4 DO
                    sum := sum + i;
                END_FOR;
                absVal := ABS(-7);
                """);

        proj.getPages().add(ldPage);
        proj.getPages().add(stPage);

        MflwEmitter.EmittedProgram emitted = BytecodeCompiler.compileProgram(proj);
        try (NativeMaraVm vm = new NativeMaraVm()) {
            assertEquals(0, vm.loadImage(emitted.image()));
            assertEquals(0, vm.setState(NativeMaraVm.RunState.RUNNING));

            vm.setVirtualDi(0, false);
            vm.setVirtualDi(1, true);
            assertEquals(0, vm.stepThread(2, 100_000));

            assertTrue(vm.getVirtualDo(0), "Parallel branch DI1 should energize DO0");
            assertEquals(10.0, vm.readSlotAsDouble(emitted.operandToSlot().get("MainST.sum")));
            assertEquals(7.0, vm.readSlotAsDouble(emitted.operandToSlot().get("MainST.absVal")));
        }
    }
}
