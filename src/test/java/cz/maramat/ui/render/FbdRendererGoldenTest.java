package cz.maramat.ui.render;

import cz.maramat.model.BlockCatalog;
import cz.maramat.model.BlockDefinition;
import cz.maramat.model.BlockInstance;
import cz.maramat.model.GraphicElement;
import cz.maramat.model.Page;
import cz.maramat.model.Project;
import cz.maramat.model.Wire;
import cz.maramat.sim.SimulationEngine;
import cz.maramat.ui.canvas.FbdCanvas;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FbdRendererGoldenTest {

    private static final int GRID_PX = 16;

    @Test
    void goldenPagesMatchPixelForPixel() throws Exception {
        Project project = new Project();
        SimulationEngine sim = new SimulationEngine(() -> {});
        FbdCanvas canvas = new FbdCanvas(project, sim, new FbdCanvas.CanvasEditListener() {
            @Override public void onBeforePageEdit(String actionName, Page snapshotBefore) {}
            @Override public void onAfterPageEdit(String actionName, Page snapshotBefore, Page snapshotAfter) {}
            @Override public void onResetToolToCursor() {}
            @Override public void onInspectMacroPage(String macroTypeName) {}
            @Override public boolean isPinOnScope(String pinRef) { return false; }
            @Override public void onTogglePinOnScope(String pinRef) {}
        });

        Page p1 = buildBoolAndWirePage();
        Page p2 = buildMathAndVisualPage();
        Page p3 = buildGraphicsAndHiddenWirePage();

        verifyOrRecordGolden(canvas, p1, "bool_wires.png");
        verifyOrRecordGolden(canvas, p2, "math_visual.png");
        verifyOrRecordGolden(canvas, p3, "graphics_hidden.png");
    }

    private void verifyOrRecordGolden(FbdCanvas canvas, Page page, String name) throws Exception {
        Rectangle bounds = canvas.computeExportBounds(page, GRID_PX);
        BufferedImage img = new BufferedImage(bounds.width, bounds.height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = img.createGraphics();
        g2.setColor(Color.WHITE);
        g2.fillRect(0, 0, bounds.width, bounds.height);
        g2.translate(-bounds.x, -bounds.y);
        canvas.paintPageForExport(g2, page, GRID_PX);
        g2.dispose();

        Path goldenDir = Path.of("src/test/resources/golden");
        Path goldenFile = goldenDir.resolve(name);
        if (!Files.exists(goldenFile)) {
            Files.createDirectories(goldenDir);
            ImageIO.write(img, "PNG", goldenFile.toFile());
            assertTrue(Files.exists(goldenFile));
            return;
        }

        BufferedImage expected = ImageIO.read(goldenFile.toFile());
        assertNotNull(expected, "Golden image should load: " + name);
        assertEquals(expected.getWidth(), img.getWidth(), "Width mismatch for " + name);
        assertEquals(expected.getHeight(), img.getHeight(), "Height mismatch for " + name);

        for (int y = 0; y < expected.getHeight(); y++) {
            for (int x = 0; x < expected.getWidth(); x++) {
                if (expected.getRGB(x, y) != img.getRGB(x, y)) {
                    File diffOut = Path.of("target", "diff_" + name).toFile();
                    ImageIO.write(img, "PNG", diffOut);
                    assertEquals(expected.getRGB(x, y), img.getRGB(x, y),
                            "Pixel mismatch in " + name + " at (" + x + "," + y + ")");
                }
            }
        }
    }

    private static Page buildBoolAndWirePage() {
        Page page = new Page("Main");
        BlockDefinition andDef = BlockCatalog.getById("Bool.And");
        BlockDefinition rsDef = BlockCatalog.getById("Bool.RS");
        BlockInstance b1 = new BlockInstance(andDef, "And1", 4, 2, 2, 1);
        b1.getInputs().get(1).setConstantValue("1");
        BlockInstance b2 = new BlockInstance(rsDef, "Latch", 12, 2, 2, 2);
        page.getBlocks().add(b1);
        page.getBlocks().add(b2);

        Wire w = new Wire(b1.getId(), 0, b2.getId(), 1, "N_SET",
                b1.getOutputPinAnchorGrid(0), b2.getInputPinAnchorGrid(1));
        page.getWires().add(w);
        return page;
    }

    private static Page buildMathAndVisualPage() {
        Page page = new Page("MathVisual");
        BlockInstance add = new BlockInstance(BlockCatalog.getById("Math.Add"), "Add1", 4, 2, 2, 1);
        BlockInstance seg = new BlockInstance(BlockCatalog.getById("Visual.Segment"), "Seg1", 12, 2, 2, 2);
        BlockInstance val = new BlockInstance(BlockCatalog.getById("Visual.Value"), "Val1", 12, 9, 2, 3);
        page.getBlocks().add(add);
        page.getBlocks().add(seg);
        page.getBlocks().add(val);

        Wire w1 = new Wire(add.getId(), 0, seg.getId(), 1, "N_SEG",
                add.getOutputPinAnchorGrid(0), seg.getInputPinAnchorGrid(1));
        Wire w2 = new Wire(add.getId(), 0, val.getId(), 1, "N_VAL",
                add.getOutputPinAnchorGrid(0), val.getInputPinAnchorGrid(1));
        page.getWires().add(w1);
        page.getWires().add(w2);
        return page;
    }

    private static Page buildGraphicsAndHiddenWirePage() {
        Page page = new Page("GraphicsHidden");
        BlockInstance or1 = new BlockInstance(BlockCatalog.getById("Bool.Or"), "Or1", 3, 3, 2, 1);
        BlockInstance not1 = new BlockInstance(BlockCatalog.getById("Bool.Not"), "Not1", 14, 3, 2, 2);
        page.getBlocks().add(or1);
        page.getBlocks().add(not1);

        Wire hiddenWire = new Wire(or1.getId(), 0, not1.getId(), 1, "SIG_A",
                or1.getOutputPinAnchorGrid(0), not1.getInputPinAnchorGrid(1));
        hiddenWire.setHidden(true);
        page.getWires().add(hiddenWire);

        GraphicElement rect = new GraphicElement(GraphicElement.Kind.RECTANGLE, 2, 1, 18, 9);
        rect.setText("Latch Group");
        page.getGraphics().add(rect);
        return page;
    }
}
