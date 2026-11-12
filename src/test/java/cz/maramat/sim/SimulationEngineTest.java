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
}
