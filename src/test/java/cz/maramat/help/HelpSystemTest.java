package cz.maramat.help;

import cz.maramat.help.HelpMarkdownRenderer.RenderedHelpHtml;
import cz.maramat.help.HelpToc.TocNode;
import cz.maramat.help.diagram.FbdDiagramParser;
import cz.maramat.help.diagram.FbdDiagramRenderer;
import cz.maramat.help.diagram.LdDiagramRenderer;
import cz.maramat.model.BlockCatalog;
import cz.maramat.model.BlockDefinition;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class HelpSystemTest {

    private static final Pattern FBD_BLOCK = Pattern.compile("```fbd\\R([\\s\\S]*?)```");

    @Test
    void allCatalogBlocksGeneratePagesAndRenderDiagrams() {
        HelpContentManager mgr = new HelpContentManager();
        HelpMarkdownRenderer renderer = new HelpMarkdownRenderer();

        int checked = 0;
        for (BlockDefinition def : BlockCatalog.getAllDefinitions().values()) {
            if (def.getCategoryPath() != null && def.getCategoryPath().startsWith("Macros")) continue;
            for (String lang : new String[]{"en", "cs"}) {
                HelpPage page = mgr.loadPage("blocks/" + def.getId(), lang);
                assertNotNull(page, "Missing generated page for " + def.getId() + " (" + lang + ")");
                RenderedHelpHtml html = renderer.render(page, lang, FbdDiagramRenderer.DEFAULT_GRID_PX);
                assertFalse(html.html().contains("class=\"diagram-error\""),
                        "Diagram render error on " + def.getId() + ": " + html.html());
                assertTrue(html.html().contains("src=\"diagram:"),
                        "Missing diagram img on " + def.getId());
            }
            checked++;
        }
        assertTrue(checked > 10, "Expected catalog blocks to be checked");
        mgr.releaseCaches();
        FbdDiagramRenderer.clearCache();
    }

    @Test
    void customBlockOverrideMergesPlaceholdersAndRendersWiredExample() {
        HelpContentManager mgr = new HelpContentManager();
        HelpMarkdownRenderer renderer = new HelpMarkdownRenderer();

        HelpPage rsPage = mgr.loadPage("blocks/Bool.RS", "en");
        assertNotNull(rsPage);
        assertEquals("RS (Reset-Dominant Latch)", rsPage.title());
        assertTrue(rsPage.markdown().contains("Truth Table"));
        assertTrue(rsPage.markdown().contains("## Inputs"));
        assertTrue(rsPage.markdown().contains("## Properties"));

        RenderedHelpHtml html = renderer.render(rsPage, "en", FbdDiagramRenderer.DEFAULT_GRID_PX);
        assertFalse(html.html().contains("class=\"diagram-error\""));
        assertFalse(html.html().contains("{{auto:"));

        Matcher m = FBD_BLOCK.matcher(rsPage.markdown());
        int count = 0;
        while (m.find()) {
            BufferedImage img = FbdDiagramRenderer.renderToImage(m.group(1), FbdDiagramRenderer.DEFAULT_GRID_PX);
            assertNotNull(img);
            assertTrue(img.getWidth() > 40 && img.getHeight() > 30);
            count++;
        }
        assertEquals(2, count, "Bool.RS override should contain auto block image + wired example");
    }

    @Test
    void tocLoadsAndFallbackWorksForCzech() {
        HelpContentManager mgr = new HelpContentManager();

        TocNode tocEn = HelpToc.load("en", mgr);
        assertFalse(tocEn.getChildren().isEmpty());

        HelpPage scanEn = mgr.loadPage("getting-started/scan-cycle", "en");
        HelpPage scanCs = mgr.loadPage("getting-started/scan-cycle", "cs");
        assertNotNull(scanEn);
        assertNotNull(scanCs);
        assertFalse(scanCs.fallback());

        // concepts/fbd-ld-st is only in en/, so cs should fall back to en with fallback=true
        HelpPage conceptsCs = mgr.loadPage("concepts/fbd-ld-st", "cs");
        assertNotNull(conceptsCs);
        assertTrue(conceptsCs.fallback());

        HelpMarkdownRenderer renderer = new HelpMarkdownRenderer();
        RenderedHelpHtml rendered = renderer.render(conceptsCs, "cs", FbdDiagramRenderer.DEFAULT_GRID_PX);
        assertTrue(rendered.html().contains("fallback-banner"));
        assertTrue(rendered.html().contains("<span class=\"kw\">VAR</span>"));
    }

    @Test
    void invalidDiagramRejectsUnknownBlockAndPin() {
        assertThrows(IllegalArgumentException.class, () ->
                FbdDiagramRenderer.renderToImage("block a NoSuch.Block at 1,1", 16));
        assertThrows(IllegalArgumentException.class, () ->
                FbdDiagramRenderer.renderToImage(
                        "block a Bool.And at 1,1\nblock b Bool.Not at 8,1\nwire a.NOPE -> b.X", 16));
        assertDoesNotThrow(() -> FbdDiagramParser.parse(
                "size 16x6\nblock a Bool.And at 1,1 { X1=1 X2=0 }\ncaption \"Test\""));
    }

    @Test
    void memoryFootprintWithinBudget() {
        Runtime rt = Runtime.getRuntime();
        System.gc();
        long before = rt.totalMemory() - rt.freeMemory();

        HelpContentManager mgr = new HelpContentManager();
        HelpMarkdownRenderer renderer = new HelpMarkdownRenderer();
        HelpToc.load("en", mgr);
        for (BlockDefinition def : BlockCatalog.getAllDefinitions().values()) {
            if (def.getCategoryPath() != null && def.getCategoryPath().startsWith("Macros")) continue;
            HelpPage p = mgr.loadPage("blocks/" + def.getId(), "en");
            if (p != null) {
                renderer.render(p, "en", FbdDiagramRenderer.DEFAULT_GRID_PX);
            }
        }
        System.gc();
        long during = rt.totalMemory() - rt.freeMemory();
        long deltaMb = Math.max(0, (during - before) / (1024 * 1024));
        assertTrue(deltaMb < 20, "Help system heap delta exceeded 20 MB: " + deltaMb + " MB");

        mgr.releaseCaches();
        FbdDiagramRenderer.clearCache();
    }

    @Test
    void ldDiagramRendersAndRejectsInvalidSyntax() {
        HelpContentManager mgr = new HelpContentManager();
        HelpMarkdownRenderer renderer = new HelpMarkdownRenderer();
        HelpPage ldPage = mgr.loadPage("ld/basics", "en");
        assertNotNull(ldPage);
        RenderedHelpHtml html = renderer.render(ldPage, "en", FbdDiagramRenderer.DEFAULT_GRID_PX);
        assertFalse(html.html().contains("class=\"diagram-error\""));
        assertTrue(html.html().contains("src=\"diagram:ld_"));

        assertThrows(IllegalArgumentException.class, () ->
                LdDiagramRenderer.renderToImage("rung \"Bad\"\n  bogus DI0 -> coil DO0"));
        LdDiagramRenderer.clearCache();
    }

    @Test
    void helpCodeExporterRoundTripsFbdWithRoutingAndGraphics() {
        cz.maramat.model.Page page = new cz.maramat.model.Page("ExportTest");
        cz.maramat.model.BlockInstance and = new cz.maramat.model.BlockInstance(
                BlockCatalog.getById("Bool.And"), "And1", 10, 5, 2, 1);
        and.getInputs().get(1).setConstantValue("1");
        and.getInputs().get(2).setConstantValue("1");
        and.getOutputs().get(0).setRuntimeValue(1.0);

        cz.maramat.model.BlockInstance not = new cz.maramat.model.BlockInstance(
                BlockCatalog.getById("Bool.Not"), "Not1", 22, 5, 2, 2);
        not.getOutputs().get(0).setRuntimeValue(0.0);

        page.getBlocks().add(and);
        page.getBlocks().add(not);

        cz.maramat.model.Wire w = new cz.maramat.model.Wire(
                and.getId(), 0, not.getId(), 0, "N1",
                and.getOutputPinAnchorGrid(0), not.getInputPinAnchorGrid(0));
        w.setManualRoute(java.util.List.of(
                new java.awt.geom.Point2D.Double(13.0, 6.5),
                new java.awt.geom.Point2D.Double(17.5, 6.5),
                new java.awt.geom.Point2D.Double(17.5, 6.5),
                new java.awt.geom.Point2D.Double(22.0, 6.5)
        ));
        page.getWires().add(w);

        cz.maramat.model.GraphicElement led = new cz.maramat.model.GraphicElement(
                cz.maramat.model.GraphicElement.Kind.CIRCLE, 28, 5, 2, 2);
        led.setIndicatorEnabled(true);
        led.setIndicatorPin("And1.Y");
        page.getGraphics().add(led);

        String md = cz.maramat.help.diagram.HelpCodeExporter.exportFbd(
                page, java.util.Set.of(and.getId(), not.getId()), java.util.Set.of(led.getId()), true);
        assertTrue(md.startsWith("```fbd\n"));
        assertTrue(md.contains(" via "));
        assertTrue(md.contains("circle "));
        assertTrue(md.contains("live "));

        String inner = md.replaceFirst("^```fbd\\n", "").replaceFirst("\\n```$", "");
        assertNotNull(FbdDiagramRenderer.renderToImage(inner, 16));
        FbdDiagramRenderer.clearCache();
    }

    @Test
    void helpCodeExporterPreservesMacroCustomPinsAndChannelProps() {
        cz.maramat.model.Page page = new cz.maramat.model.Page("MacroTest");
        cz.maramat.model.BlockInstance macroIn = new cz.maramat.model.BlockInstance(
                BlockCatalog.getById("Macro.MacroIn"), "MacroIn", 2, 2, 2, 1);
        macroIn.getOutputs().clear();
        macroIn.getOutputs().add(new cz.maramat.model.PinInstance("A", "A", cz.maramat.model.DataType.BOOL, false, "0"));
        macroIn.getOutputs().add(new cz.maramat.model.PinInstance("B", "B", cz.maramat.model.DataType.BOOL, false, "0"));

        cz.maramat.model.BlockInstance xor1 = new cz.maramat.model.BlockInstance(
                BlockCatalog.getById("Bool.Xor"), "Xor1", 18, 1, 2, 2);
        cz.maramat.model.BlockInstance macroOut = new cz.maramat.model.BlockInstance(
                BlockCatalog.getById("Macro.MacroOut"), "MacroOut", 34, 1, 2, 3);
        macroOut.getInputs().clear();
        macroOut.getInputs().add(new cz.maramat.model.PinInstance("Sum", "Sum", cz.maramat.model.DataType.BOOL, true, "0"));
        macroOut.getInputs().add(new cz.maramat.model.PinInstance("Car", "Car", cz.maramat.model.DataType.BOOL, true, "0"));

        page.getBlocks().addAll(java.util.List.of(macroIn, xor1, macroOut));
        page.getWires().add(new cz.maramat.model.Wire(
                macroIn.getId(), 0, xor1.getId(), 1, "N1",
                macroIn.getOutputPinAnchorGrid(0), xor1.getInputPinAnchorGrid(1)));
        page.getWires().add(new cz.maramat.model.Wire(
                xor1.getId(), 0, macroOut.getId(), 0, "N2",
                xor1.getOutputPinAnchorGrid(0), macroOut.getInputPinAnchorGrid(0)));

        String md = cz.maramat.help.diagram.HelpCodeExporter.exportFbd(page, null, null, false);
        assertTrue(md.contains("out=A:BOOL,B:BOOL"));
        assertTrue(md.contains("in=Sum:BOOL,Car:BOOL"));

        String inner = md.replaceFirst("^```fbd\\n", "").replaceFirst("\\n```$", "");
        assertNotNull(FbdDiagramRenderer.renderToImage(inner, 16));
        FbdDiagramRenderer.clearCache();
    }

    @Test
    void syntheticMacroUserBlocksRenderAndExport() {
        String diagram = """
                block macroin Macro.MacroIn at 2,2 name=MacroIn out=A:BOOL,B:BOOL,C:BOOL
                block macroout Macro.MacroOut at 48,4 name=MacroOut in=C:BOOL,Sum:BOOL
                block halfadder1 Macro.User.HalfAdder at 12,1 name=HalfAdder1
                block halfadder2 Macro.User.HalfAdder at 25,4 name=HalfAdder2
                block or1 Bool.Or at 37,1 name=Or1
                wire macroin.A -> halfadder1.A
                wire macroin.B -> halfadder1.B
                wire halfadder1.C -> halfadder2.A via 20,4.50 27,4.50 27,7.50 25,7.50
                wire macroin.C -> halfadder2.B via 9,6.50 26,6.50 26,8.50 25,8.50
                wire halfadder2.Sum -> or1.X2 via 33,6.50 39,6.50 39,5.50 37,5.50
                wire halfadder1.Sum -> or1.X1 via 20,3.50 39,3.50 39,4.50 37,4.50
                wire or1.Y -> macroout.C via 44,3.50 46,3.50 46,6.50 48,6.50
                wire halfadder2.C -> macroout.Sum
                """;
        assertNotNull(FbdDiagramRenderer.renderToImage(diagram, 16));

        cz.maramat.model.Page page = new cz.maramat.model.Page("UserMacroExport");
        cz.maramat.model.BlockDefinition haDef = new cz.maramat.model.BlockDefinition(
                "Macro.User.HalfAdder", "Macros", "HalfAdder", "HalfAdder",
                java.util.List.of(
                        new cz.maramat.model.BlockDefinition.PinSpec("A", "A", cz.maramat.model.DataType.BOOL, "0"),
                        new cz.maramat.model.BlockDefinition.PinSpec("B", "B", cz.maramat.model.DataType.BOOL, "0")
                ),
                java.util.List.of(
                        new cz.maramat.model.BlockDefinition.PinSpec("Sum", "Sum", cz.maramat.model.DataType.BOOL, "0"),
                        new cz.maramat.model.BlockDefinition.PinSpec("C", "C", cz.maramat.model.DataType.BOOL, "0")
                ),
                3, 2, false, false, 0.05);
        cz.maramat.model.BlockInstance ha1 = new cz.maramat.model.BlockInstance(haDef, "HalfAdder1", 12, 1, 2, 1);
        page.getBlocks().add(ha1);
        String exported = cz.maramat.help.diagram.HelpCodeExporter.exportFbd(page, null, null, false);
        assertTrue(exported.contains("in=A:BOOL,B:BOOL"));
        assertTrue(exported.contains("out=Sum:BOOL,C:BOOL"));
        FbdDiagramRenderer.clearCache();
    }
}
