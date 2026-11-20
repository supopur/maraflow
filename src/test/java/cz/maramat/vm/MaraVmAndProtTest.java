package cz.maramat.vm;

import cz.maramat.comm.MaraProtClient;
import cz.maramat.compiler.BytecodeCompiler;
import cz.maramat.compiler.MflwEmitter;
import cz.maramat.model.BlockCatalog;
import cz.maramat.model.BlockInstance;
import cz.maramat.model.Page;
import cz.maramat.model.Project;
import cz.maramat.model.Wire;
import org.junit.jupiter.api.Test;

import java.awt.Point;
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
        oref.getCustomProperties().put("IoRef", "DO3");

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
}
