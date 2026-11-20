package cz.maramat.sim;

import cz.maramat.model.BlockCatalog;
import cz.maramat.model.BlockDefinition;
import cz.maramat.model.BlockInstance;
import cz.maramat.model.DataType;
import cz.maramat.model.Page;
import cz.maramat.model.Project;
import cz.maramat.model.Wire;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IoRefTest {

    @Test
    void catalogReplacesLegacyIoBlocksWithIrefAndOref() {
        assertNotNull(BlockCatalog.getById("IO.IREF"));
        assertNotNull(BlockCatalog.getById("IO.OREF"));
        assertNull(BlockCatalog.getById("IO.DI"));
        assertNull(BlockCatalog.getById("IO.DO"));
        assertNull(BlockCatalog.getById("IO.8xDI"));
        assertNull(BlockCatalog.getById("IO.8xDO"));
        assertNull(BlockCatalog.getById("IO.AI"));
        assertNull(BlockCatalog.getById("IO.AO"));
    }

    @Test
    void dynamicGridWidthAndHalfGridPinAnchors() {
        BlockDefinition irefDef = BlockCatalog.getById("IO.IREF");
        BlockDefinition orefDef = BlockCatalog.getById("IO.OREF");

        BlockInstance iref = new BlockInstance(irefDef, "IREF1", 10, 5, 1, 10);
        iref.setIoRefChannel("DI0");
        int shortWidth = iref.getGridWidth();
        assertEquals(1, iref.getGridHeight());
        assertTrue(shortWidth >= 3);
        assertEquals(10 + shortWidth, iref.getOutputPinAnchorGrid(0).x);
        assertEquals(5.5, iref.getOutputPinAnchorGrid(0).y);

        iref.setIoRefChannel("MOTOR_START_CMD");
        int longWidth = iref.getGridWidth();
        assertTrue(longWidth > shortWidth);
        assertEquals(10 + longWidth, iref.getOutputPinAnchorGrid(0).x);

        BlockInstance oref = new BlockInstance(orefDef, "OREF1", 20, 7, 1, 20);
        assertEquals(1, oref.getGridHeight());
        assertEquals(20.0, oref.getInputPinAnchorGrid(0).x);
        assertEquals(7.5, oref.getInputPinAnchorGrid(0).y);
    }

    @Test
    void channelSelectionSyncsDataTypeAndSimulates() {
        Project project = new Project();
        Page page = project.getPages().getFirst();
        page.getBlocks().clear();
        page.getWires().clear();

        BlockInstance irefBool = new BlockInstance(BlockCatalog.getById("IO.IREF"), "IREF1", 2, 2, 2, 10);
        irefBool.setIoRefChannel("DI0");
        assertEquals(DataType.BOOL, irefBool.getOutputs().getFirst().getDataType());

        BlockInstance orefBool = new BlockInstance(BlockCatalog.getById("IO.OREF"), "OREF1", 10, 2, 2, 20);
        orefBool.setIoRefChannel("DO0");
        assertEquals(DataType.BOOL, orefBool.getInputs().getFirst().getDataType());

        BlockInstance irefInt = new BlockInstance(BlockCatalog.getById("IO.IREF"), "IREF2", 2, 4, 2, 30);
        irefInt.setIoRefChannel("AI0");
        assertEquals(DataType.INT, irefInt.getOutputs().getFirst().getDataType());

        BlockInstance orefInt = new BlockInstance(BlockCatalog.getById("IO.OREF"), "OREF2", 10, 4, 2, 40);
        orefInt.setIoRefChannel("AO0");
        assertEquals(DataType.INT, orefInt.getInputs().getFirst().getDataType());

        page.getBlocks().add(irefBool);
        page.getBlocks().add(orefBool);
        page.getBlocks().add(irefInt);
        page.getBlocks().add(orefInt);

        page.getWires().add(new Wire(irefBool.getId(), 0, orefBool.getId(), 0, "IREF1.Y",
                irefBool.getOutputPinAnchorGrid(0), orefBool.getInputPinAnchorGrid(0)));
        page.getWires().add(new Wire(irefInt.getId(), 0, orefInt.getId(), 0, "IREF2.Y",
                irefInt.getOutputPinAnchorGrid(0), orefInt.getInputPinAnchorGrid(0)));

        SimulationEngine sim = new SimulationEngine(() -> {});
        sim.setOperandValue("DI0", 1.0);
        sim.setOperandValue("AI0", 1234.0);
        sim.stepOnceForTesting(project, 10.0);

        assertEquals(1.0, sim.getOperandValue(project, "DO0"));
        assertEquals(1234.0, sim.getOperandValue(project, "AO0"));
    }

    @Test
    void irefDoesNotOverrideVisualButtonOrMaraviewPinWrites() {
        Project project = new Project();
        Page page = project.getPages().getFirst();
        page.getBlocks().clear();
        page.getWires().clear();

        BlockInstance iref1 = new BlockInstance(BlockCatalog.getById("IO.IREF"), "IREF1", 2, 2, 2, 10);
        iref1.setIoRefChannel("DI2");
        BlockInstance iref2 = new BlockInstance(BlockCatalog.getById("IO.IREF"), "IREF2", 2, 4, 2, 20);
        iref2.setIoRefChannel("DI3");
        BlockInstance and1 = new BlockInstance(BlockCatalog.getById("Bool.And"), "And1", 10, 2, 2, 30);

        page.getBlocks().add(iref1);
        page.getBlocks().add(iref2);
        page.getBlocks().add(and1);

        page.getWires().add(new Wire(iref1.getId(), 0, and1.getId(), 1, "IREF1.Y",
                iref1.getOutputPinAnchorGrid(0), and1.getInputPinAnchorGrid(1)));
        page.getWires().add(new Wire(iref2.getId(), 0, and1.getId(), 2, "IREF2.Y",
                iref2.getOutputPinAnchorGrid(0), and1.getInputPinAnchorGrid(2)));

        // Bind a Visual Button to And1.X1 (which is also wired to IREF1)
        cz.maramat.model.GraphicElement vBtn = new cz.maramat.model.GraphicElement(
                cz.maramat.model.GraphicElement.Kind.VISUAL_BUTTON, 4, 8, 5, 2);
        vBtn.setBoundTargetPin("And1.X1");
        vBtn.setActiveState(true);
        page.getGraphics().add(vBtn);

        SimulationEngine sim = new SimulationEngine(() -> {});
        // Drive And1.X2 from Maraview / external setOperandValue (which is also wired to IREF2)
        sim.setOperandValue(project, "And1.X2", 1.0);

        sim.stepOnceForTesting(project, 10.0);

        assertEquals(1.0, and1.getInputs().get(1).getRuntimeValue(), "Visual Button on And1.X1 must not be overwritten by IREF1");
        assertEquals(1.0, and1.getInputs().get(2).getRuntimeValue(), "Maraview write on And1.X2 must not be overwritten by IREF2");
        assertEquals(1.0, and1.getOutputs().getFirst().getRuntimeValue(), "And1.Y should evaluate to 1.0");
    }
}
