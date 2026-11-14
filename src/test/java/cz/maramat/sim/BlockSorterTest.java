package cz.maramat.sim;

import cz.maramat.model.BlockCatalog;
import cz.maramat.model.BlockDefinition;
import cz.maramat.model.BlockInstance;
import cz.maramat.model.IecLanguage;
import cz.maramat.model.Page;
import cz.maramat.model.Wire;
import org.junit.jupiter.api.Test;

import java.awt.Point;

import static org.junit.jupiter.api.Assertions.*;

class BlockSorterTest {

    @Test
    void dataflowSortOrdersDiamondGraphTopologically() {
        Page page = new Page("P1", IecLanguage.FBD);
        BlockDefinition andDef = BlockCatalog.getById("Bool.And");

        BlockInstance a = new BlockInstance(andDef, "A", 20, 2, 2, 10);
        BlockInstance b = new BlockInstance(andDef, "B", 10, 8, 2, 10);
        BlockInstance c = new BlockInstance(andDef, "C", 10, 2, 2, 10);
        BlockInstance d = new BlockInstance(andDef, "D", 2, 5, 2, 10);

        page.getBlocks().add(a);
        page.getBlocks().add(b);
        page.getBlocks().add(c);
        page.getBlocks().add(d);

        page.getWires().add(new Wire(a.getId(), 0, b.getId(), 1, "n1", new Point(0, 0), new Point(1, 0)));
        page.getWires().add(new Wire(a.getId(), 0, c.getId(), 1, "n2", new Point(0, 0), new Point(1, 0)));
        page.getWires().add(new Wire(b.getId(), 0, d.getId(), 1, "n3", new Point(0, 0), new Point(1, 0)));
        page.getWires().add(new Wire(c.getId(), 0, d.getId(), 2, "n4", new Point(0, 0), new Point(1, 0)));

        BlockSorter.sortExecutionOrder(page, BlockSorter.OrderSortMode.DATAFLOW);

        assertTrue(a.getOrder() < b.getOrder());
        assertTrue(a.getOrder() < c.getOrder());
        assertTrue(b.getOrder() < d.getOrder());
        assertTrue(c.getOrder() < d.getOrder());
        assertTrue(c.getOrder() < b.getOrder());
    }

    @Test
    void dataflowSortHandlesFeedbackCycleWithoutHanging() {
        Page page = new Page("P1", IecLanguage.FBD);
        BlockDefinition andDef = BlockCatalog.getById("Bool.And");

        BlockInstance a = new BlockInstance(andDef, "A", 2, 2, 2, 10);
        BlockInstance b = new BlockInstance(andDef, "B", 12, 2, 2, 10);
        page.getBlocks().add(a);
        page.getBlocks().add(b);

        page.getWires().add(new Wire(a.getId(), 0, b.getId(), 1, "n1", new Point(0, 0), new Point(1, 0)));
        page.getWires().add(new Wire(b.getId(), 0, a.getId(), 1, "n2", new Point(0, 0), new Point(1, 0)));

        BlockSorter.sortExecutionOrder(page, BlockSorter.OrderSortMode.DATAFLOW);

        assertEquals(10, a.getOrder());
        assertEquals(20, b.getOrder());
    }
}
