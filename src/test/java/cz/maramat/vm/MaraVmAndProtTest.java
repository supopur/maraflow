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
}
