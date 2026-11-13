package cz.maramat.ui.render;

import cz.maramat.model.IecLanguage;
import cz.maramat.model.LadderBranch;
import cz.maramat.model.LadderElement;
import cz.maramat.model.LadderRung;
import cz.maramat.model.Page;
import cz.maramat.model.Project;
import cz.maramat.sim.SimulationEngine;
import cz.maramat.ui.canvas.FbdCanvas;
import cz.maramat.ui.canvas.LadderCanvas;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class LdRendererGoldenTest {

    @Test
    void goldenLadderPageMatchesPixelForPixel() throws Exception {
        Project project = new Project();
        SimulationEngine sim = new SimulationEngine(() -> {});
        LadderCanvas canvas = new LadderCanvas(project, sim, new FbdCanvas.CanvasEditListener() {
            @Override public void onBeforePageEdit(String actionName, Page snapshotBefore) {}
            @Override public void onAfterPageEdit(String actionName, Page snapshotBefore, Page snapshotAfter) {}
            @Override public void onResetToolToCursor() {}
            @Override public void onInspectMacroPage(String macroTypeName) {}
            @Override public boolean isPinOnScope(String pinRef) { return false; }
            @Override public void onTogglePinOnScope(String pinRef) {}
        });

        Page page = new Page("LadderGolden", IecLanguage.LD);
        LadderRung r1 = new LadderRung(2, 1, "Start / Stop Motor Latch");
        r1.getMainElements().add(new LadderElement(LadderElement.Kind.NO_CONTACT, "DI0"));
        r1.getMainElements().add(new LadderElement(LadderElement.Kind.NC_CONTACT, "DI1"));
        r1.getCoils().add(new LadderElement(LadderElement.Kind.NORMAL_COIL, "DO0"));

        LadderBranch br = new LadderBranch(0, 1);
        br.getElements().add(new LadderElement(LadderElement.Kind.NO_CONTACT, "DO0"));
        r1.getParallelBranches().add(br);

        LadderRung r2 = new LadderRung(2, 2, "Fault Reset");
        r2.getMainElements().add(new LadderElement(LadderElement.Kind.POS_EDGE_CONTACT, "DI2"));
        r2.getCoils().add(new LadderElement(LadderElement.Kind.RESET_COIL, "M0"));

        page.getRungs().add(r1);
        page.getRungs().add(r2);

        Rectangle bounds = canvas.computeExportBounds(page);
        BufferedImage img = new BufferedImage(bounds.width, bounds.height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = img.createGraphics();
        g2.setColor(Color.WHITE);
        g2.fillRect(0, 0, bounds.width, bounds.height);
        g2.translate(-bounds.x, -bounds.y);
        canvas.paintPageForExport(g2, page);
        g2.dispose();

        Path goldenFile = Path.of("src/test/resources/golden/ld_rungs.png");
        if (!Files.exists(goldenFile)) {
            Files.createDirectories(goldenFile.getParent());
            ImageIO.write(img, "PNG", goldenFile.toFile());
            assertTrue(Files.exists(goldenFile));
            return;
        }

        BufferedImage expected = ImageIO.read(goldenFile.toFile());
        assertNotNull(expected);
        assertEquals(expected.getWidth(), img.getWidth());
        assertEquals(expected.getHeight(), img.getHeight());
        for (int y = 0; y < expected.getHeight(); y++) {
            for (int x = 0; x < expected.getWidth(); x++) {
                if (expected.getRGB(x, y) != img.getRGB(x, y)) {
                    File diffOut = Path.of("target", "diff_ld_rungs.png").toFile();
                    ImageIO.write(img, "PNG", diffOut);
                    assertEquals(expected.getRGB(x, y), img.getRGB(x, y),
                            "Pixel mismatch in ld_rungs.png at (" + x + "," + y + ")");
                }
            }
        }
    }
}
