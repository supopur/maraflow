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

        // First scan with Run=0: LeftRed (DO24)=true, RightRed (DO3)=true, LeftGreen (DO22)=false, RightGreen (DO2)=false
        assertEquals(MaraVm.Status.OK, vm.stepThread(2, 100_000));
        assertTrue(outputs.getOrDefault(24, false), "DO24 (LeftRed) should be ON when stopped");
        assertTrue(outputs.getOrDefault(3, false), "DO3 (RightRed) should be ON when stopped");
        assertFalse(outputs.getOrDefault(22, true), "DO22 (LeftGreen) should be OFF when stopped");
        assertFalse(outputs.getOrDefault(2, true), "DO2 (RightGreen) should be OFF when stopped");

        // Turn on BoolVar1.Y (Switch=1) -> Stav=0 (LeftGreen DO22=true, RightRed DO3=true)
        Integer boolVarSlot = emitted.operandToSlot().get("BoolVar1.Y");
        assertNotNull(boolVarSlot);
        vm.writeSlotFromDouble(boolVarSlot, 1.0);
        assertEquals(MaraVm.Status.OK, vm.stepThread(2, 100_000));
        assertTrue(outputs.getOrDefault(22, false), "DO22 (LeftGreen) should turn ON in state 0");
        assertFalse(outputs.getOrDefault(24, true), "DO24 (LeftRed) should turn OFF in state 0");
        assertTrue(outputs.getOrDefault(3, false), "DO3 (RightRed) should stay ON in state 0");

        // Advance 25 ticks -> reaches state 2 (RightGreen DO2=true)
        boolean sawRightGreen = false;
        for (int i = 0; i < 25; i++) {
            vm.stepThread(2, 100_000);
            if (outputs.getOrDefault(2, false)) sawRightGreen = true;
        }
        assertTrue(sawRightGreen, "DO2 (RightGreen) should turn ON when state machine reaches state 2");
    }
}
