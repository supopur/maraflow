package cz.maramat.sim;

import cz.maramat.model.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class SimulationEngineTest {

    @Test
    void testCounterBlockSimulation() {
        Project project = new Project();
        Page page = new Page("MainPage", IecLanguage.FBD);
        project.getPages().add(page);

        BlockDefinition counterDef = BlockCatalog.getAllDefinitions().get("Main.Timers.Counter");
        assertNotNull(counterDef);

        BlockInstance counter = new BlockInstance(counterDef, "Cntr1", 5, 5, 0, 1);
        page.getBlocks().add(counter);

        SimulationEngine sim = new SimulationEngine(() -> {});

        // En = 1, PV = 3
        counter.getInputs().get(0).setConstantValue("1"); // En
        counter.getInputs().get(1).setConstantValue("0"); // CU
        counter.getInputs().get(2).setConstantValue("0"); // CD
        counter.getInputs().get(3).setConstantValue("0"); // R
        counter.getInputs().get(4).setConstantValue("3"); // PV

        sim.stepOnceForTesting(project, 10.0);
        assertEquals(0.0, counter.getOutputs().get(1).getRuntimeValue(), "Initial CV should be 0");
        assertEquals(0.0, counter.getOutputs().get(0).getRuntimeValue(), "Initial Q should be 0");

        // 1st rising edge on CU
        counter.getInputs().get(1).setConstantValue("1");
        sim.stepOnceForTesting(project, 10.0);
        assertEquals(1.0, counter.getOutputs().get(1).getRuntimeValue(), "CV should be 1 after 1st CU edge");
        assertEquals(0.0, counter.getOutputs().get(0).getRuntimeValue(), "Q should be 0");

        // CU stays high (no edge)
        sim.stepOnceForTesting(project, 10.0);
        assertEquals(1.0, counter.getOutputs().get(1).getRuntimeValue(), "CV should remain 1 when CU stays high");

        // CU falling edge
        counter.getInputs().get(1).setConstantValue("0");
        sim.stepOnceForTesting(project, 10.0);
        assertEquals(1.0, counter.getOutputs().get(1).getRuntimeValue(), "CV should remain 1 on CU falling edge");

        // 2nd rising edge on CU
        counter.getInputs().get(1).setConstantValue("1");
        sim.stepOnceForTesting(project, 10.0);
        assertEquals(2.0, counter.getOutputs().get(1).getRuntimeValue(), "CV should be 2 after 2nd CU edge");
        assertEquals(0.0, counter.getOutputs().get(0).getRuntimeValue(), "Q should be 0");

        // 3rd rising edge on CU
        counter.getInputs().get(1).setConstantValue("0");
        sim.stepOnceForTesting(project, 10.0);
        counter.getInputs().get(1).setConstantValue("1");
        sim.stepOnceForTesting(project, 10.0);
        assertEquals(3.0, counter.getOutputs().get(1).getRuntimeValue(), "CV should be 3 after 3rd CU edge");
        assertEquals(1.0, counter.getOutputs().get(0).getRuntimeValue(), "Q should be 1 when CV >= PV");

        // Rising edge on CD (count down)
        counter.getInputs().get(1).setConstantValue("0"); // CU down
        counter.getInputs().get(2).setConstantValue("1"); // CD up
        sim.stepOnceForTesting(project, 10.0);
        assertEquals(2.0, counter.getOutputs().get(1).getRuntimeValue(), "CV should decrement to 2 after CD edge");
        assertEquals(0.0, counter.getOutputs().get(0).getRuntimeValue(), "Q should be 0 when CV < PV");

        // Reset
        counter.getInputs().get(3).setConstantValue("1"); // R up
        sim.stepOnceForTesting(project, 10.0);
        assertEquals(0.0, counter.getOutputs().get(1).getRuntimeValue(), "CV should be 0 after reset");
        assertEquals(0.0, counter.getOutputs().get(0).getRuntimeValue(), "Q should be 0 after reset");
    }

    @Test
    void testSinglePulseBlockSimulation() {
        Project project = new Project();
        Page page = new Page("MainPage", IecLanguage.FBD);
        project.getPages().add(page);

        BlockDefinition pulseDef = BlockCatalog.getAllDefinitions().get("Main.Timers.SinglePulse");
        assertNotNull(pulseDef);

        BlockInstance pulse = new BlockInstance(pulseDef, "Pulse1", 5, 5, 0, 1);
        page.getBlocks().add(pulse);

        SimulationEngine sim = new SimulationEngine(() -> {});

        // En = 1, In = 0
        pulse.getInputs().get(0).setConstantValue("1"); // En
        pulse.getInputs().get(1).setConstantValue("0"); // In
        sim.stepOnceForTesting(project, 10.0);
        assertEquals(0.0, pulse.getOutputs().getFirst().getRuntimeValue(), "Q should be 0 when In is 0");

        // Rising edge on In
        pulse.getInputs().get(1).setConstantValue("1");
        sim.stepOnceForTesting(project, 10.0);
        assertEquals(1.0, pulse.getOutputs().getFirst().getRuntimeValue(), "Q should be 1 on rising edge of In");

        // In stays high -> Q should return to 0 (single-cycle pulse)
        sim.stepOnceForTesting(project, 10.0);
        assertEquals(0.0, pulse.getOutputs().getFirst().getRuntimeValue(), "Q must return to 0 on subsequent scan cycle");

        // In goes low
        pulse.getInputs().get(1).setConstantValue("0");
        sim.stepOnceForTesting(project, 10.0);
        assertEquals(0.0, pulse.getOutputs().getFirst().getRuntimeValue(), "Q should remain 0");

        // In goes high again -> new pulse
        pulse.getInputs().get(1).setConstantValue("1");
        sim.stepOnceForTesting(project, 10.0);
        assertEquals(1.0, pulse.getOutputs().getFirst().getRuntimeValue(), "Q should be 1 on new rising edge of In");
    }

    @Test
    void testFbdPinNegationOnInputsAndOutputs() throws Exception {
        Project project = new Project();
        Page page = new Page("MainPage", IecLanguage.FBD);
        project.getPages().clear();
        project.getPages().add(page);

        BlockDefinition andDef = BlockCatalog.getById("Bool.And");
        BlockDefinition orDef = BlockCatalog.getById("Bool.Or");
        assertNotNull(andDef);
        assertNotNull(orDef);

        BlockInstance and1 = new BlockInstance(andDef, "And1", 4, 4, 2, 10);
        BlockInstance or1 = new BlockInstance(orDef, "Or1", 16, 4, 2, 20);
        page.getBlocks().add(and1);
        page.getBlocks().add(or1);

        Wire w = new Wire(and1.getId(), 0, or1.getId(), 1, "And1.Y",
                and1.getOutputPinAnchorGrid(0), or1.getInputPinAnchorGrid(1));
        page.getWires().add(w);

        // And1 has X1 = 1, X2 = 0 (unconnected constant), but X2 is negated -> effective X2 = 1 -> And1 raw Y = 1
        and1.getInputs().get(1).setConstantValue("1");
        and1.getInputs().get(2).setConstantValue("0");
        and1.getInputs().get(2).setNegated(true);
        assertTrue(and1.getInputs().get(2).isNegated());

        SimulationEngine sim = new SimulationEngine(() -> {});
        sim.stepOnceForTesting(project, 10.0);
        assertEquals(1.0, and1.getInputs().get(2).getRuntimeValue(), "Negated X2(=0) should evaluate to 1.0");
        assertEquals(1.0, and1.getOutputs().get(0).getRuntimeValue(), "And1.Y should be 1.0");
        assertEquals(1.0, or1.getInputs().get(1).getRuntimeValue(), "Or1.X1 should receive 1.0 from And1.Y");
        assertEquals(1.0, or1.getOutputs().get(0).getRuntimeValue(), "Or1.Y should be 1.0");

        // Now negate And1.Y output pin -> And1.Y becomes 0.0 -> Or1.X1 receives 0.0 -> Or1.Y becomes 0.0
        and1.getOutputs().get(0).setNegated(true);
        sim.stepOnceForTesting(project, 10.0);
        assertEquals(0.0, and1.getOutputs().get(0).getRuntimeValue(), "Negated And1.Y should be 0.0");
        assertEquals(0.0, or1.getInputs().get(1).getRuntimeValue(), "Or1.X1 should receive 0.0");
        assertEquals(0.0, or1.getOutputs().get(0).getRuntimeValue(), "Or1.Y should be 0.0");

        // Now also negate Or1.X1 (wired input) -> Or1.X1 inverts 0.0 back to 1.0 -> Or1.Y becomes 1.0
        or1.getInputs().get(1).setNegated(true);
        sim.stepOnceForTesting(project, 10.0);
        assertEquals(1.0, or1.getInputs().get(1).getRuntimeValue(), "Negated Or1.X1 should invert wired 0.0 to 1.0");
        assertEquals(1.0, or1.getOutputs().get(0).getRuntimeValue(), "Or1.Y should be 1.0");

        // Verify non-BOOL pins cannot be negated
        BlockInstance add1 = new BlockInstance(BlockCatalog.getById("Math.Add"), "Add1", 4, 12, 2, 30);
        add1.getInputs().get(1).setNegated(true);
        assertFalse(add1.getInputs().get(1).isNegated(), "INT pin should not allow negation");

        // Verify .mfl serialization round-trip preserves pin negation
        java.io.File tempFile = java.io.File.createTempFile("maraflow_neg_test_", ".mfl");
        try {
            cz.maramat.io.MflProjectSerializer.saveProject(project, tempFile);
            Project loaded = cz.maramat.io.MflProjectSerializer.loadProject(tempFile);
            Page loadedPage = loaded.getPages().getFirst();
            BlockInstance loadedAnd = loadedPage.findBlockByName("And1");
            BlockInstance loadedOr = loadedPage.findBlockByName("Or1");
            assertNotNull(loadedAnd);
            assertNotNull(loadedOr);
            assertFalse(loadedAnd.getInputs().get(1).isNegated());
            assertTrue(loadedAnd.getInputs().get(2).isNegated());
            assertTrue(loadedAnd.getOutputs().get(0).isNegated());
            assertTrue(loadedOr.getInputs().get(1).isNegated());
        } finally {
            tempFile.delete();
        }
    }

    @Test
    void testWatchModeStopResetsVariableTable() {
        Project project = new Project();
        Page stPage = new Page("ControlST", IecLanguage.ST);
        StVariable vCount = new StVariable(StVariable.Scope.VAR, "Count", DataType.INT, "10", "");
        StVariable vFlag = new StVariable(StVariable.Scope.VAR, "Active", DataType.BOOL, "0", "");
        stPage.getStVariables().add(vCount);
        stPage.getStVariables().add(vFlag);
        project.getPages().clear();
        project.getPages().add(stPage);

        java.util.concurrent.atomic.AtomicBoolean stopHookCalled = new java.util.concurrent.atomic.AtomicBoolean(false);
        SimulationEngine sim = new SimulationEngine(() -> {});
        sim.setOnStopHook(() -> stopHookCalled.set(true));

        sim.setExternalWatchActive(true);
        sim.enterWatchMode(project);
        sim.setExternalWatchActive(true);

        sim.setOperandValueFromPlc(project, "ControlST.Count", 99.0);
        sim.setOperandValueFromPlc(project, "ControlST.Active", 1.0);
        assertEquals(99.0, vCount.getRuntimeValue());
        assertEquals(1.0, vFlag.getRuntimeValue());

        // Stop watch mode -> variables must reset to initial values and stop hook must fire
        stopHookCalled.set(false);
        sim.stopSimulation(project);
        assertTrue(stopHookCalled.get(), "onStopHook must run when watch mode is stopped");
        assertEquals(10.0, vCount.getRuntimeValue(), "Count must reset to initial value 10");
        assertEquals(0.0, vFlag.getRuntimeValue(), "Active must reset to initial value 0");

        // Late PLC watch packet arriving after stop must be ignored
        sim.setOperandValueFromPlc(project, "ControlST.Count", 123.0);
        assertEquals(10.0, vCount.getRuntimeValue(), "Late PLC update after watch stop must be ignored");
    }
}
