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
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class MaraVmAndProtTest {

    @Test
    void compileAndExecuteFbdOnPortableMaraVm() {
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
        assertTrue(emitted.image().length > MaraVm.HEADER_SIZE_BYTES);
        assertEquals('M', (char) emitted.image()[0]);
        assertEquals('F', (char) emitted.image()[1]);
        assertEquals('L', (char) emitted.image()[2]);
        assertEquals('W', (char) emitted.image()[3]);

        AtomicBoolean do3State = new AtomicBoolean(false);
        MaraVm vm = new MaraVm(new MaraHalDriver() {
            @Override
            public void doWrite(int channel, boolean value) {
                if (channel == 3) do3State.set(value);
            }
        });

        assertEquals(MaraVm.Status.OK, vm.loadImage(emitted.image()));
        assertEquals(MaraVm.Status.OK, vm.setState(MaraVm.RunState.RUNNING));
        assertEquals(MaraVm.Status.OK, vm.stepThread(2, 100_000));

        assertTrue(do3State.get());
        Integer slotId = emitted.operandToSlot().get("And1.Y");
        assertNotNull(slotId);
        assertEquals(1.0, vm.readSlotAsDouble(slotId));

        // Now execute the exact same MFLW binary using the compiled C99 mara_vm.c via JNI
        try (NativeMaraVm nativeVm = new NativeMaraVm()) {
            assertEquals(0, nativeVm.loadImage(emitted.image()));
            assertEquals(0, nativeVm.setState(MaraVm.RunState.RUNNING));
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
    void kyvadlovyProvozStMacroCompilesAndRunsOnMaraVm() throws Exception {
        File mflFile = new File("/Users/matoussmekal/Downloads/Kyvadlovy provoz.mfl");
        if (!mflFile.exists()) return;

        Project proj = MflProjectSerializer.loadProject(mflFile);
        MflwEmitter.EmittedProgram emitted = BytecodeCompiler.compileProgram(proj);

        Map<Integer, Boolean> outputs = new HashMap<>();
        MaraVm vm = new MaraVm(new MaraHalDriver() {
            @Override
            public void doWrite(int channel, boolean value) {
                outputs.put(channel, value);
            }
        });

        assertEquals(MaraVm.Status.OK, vm.loadImage(emitted.image()));
        assertEquals(MaraVm.Status.OK, vm.setState(MaraVm.RunState.RUNNING));

        for (int i = 0; i < 30; i++) {
            assertEquals(MaraVm.Status.OK, vm.stepThread(2, 100_000));
        }
        assertFalse(outputs.isEmpty(), "OREF outputs should be driven");
    }

    @Test
    void negatedFbdPinsCompileAndExecuteOnMaraVm() {
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
        AtomicBoolean do4State = new AtomicBoolean(false);
        MaraVm vm = new MaraVm(new MaraHalDriver() {
            @Override
            public void doWrite(int channel, boolean value) {
                if (channel == 4) do4State.set(value);
            }
        });

        assertEquals(MaraVm.Status.OK, vm.loadImage(emitted.image()));
        assertEquals(MaraVm.Status.OK, vm.setState(MaraVm.RunState.RUNNING));
        assertEquals(MaraVm.Status.OK, vm.stepThread(2, 100_000));

        Integer andOutSlot = emitted.operandToSlot().get("And1.Y");
        assertNotNull(andOutSlot);
        assertEquals(0.0, vm.readSlotAsDouble(andOutSlot), "Negated And1.Y slot should be 0.0");
        assertTrue(do4State.get(), "Negated OREF input pin should invert 0.0 to true on DO4");
    }
}
