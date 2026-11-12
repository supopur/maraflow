package cz.maramat.io;

import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import cz.maramat.compiler.BytecodeCompiler;
import cz.maramat.model.BlockCatalog;
import cz.maramat.model.BlockInstance;
import cz.maramat.model.DataType;
import cz.maramat.model.IecLanguage;
import cz.maramat.model.LadderElement;
import cz.maramat.model.LadderRung;
import cz.maramat.model.Page;
import cz.maramat.model.Project;
import cz.maramat.model.StVariable;
import cz.maramat.sim.SimulationEngine;
import cz.maramat.ui.canvas.FbdCanvas;
import cz.maramat.ui.canvas.LadderCanvas;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ProjectExporterTest {

    private static FbdCanvas.CanvasEditListener noopListener() {
        return new FbdCanvas.CanvasEditListener() {
            @Override public void onBeforePageEdit(String actionName, Page snapshotBefore) {}
            @Override public void onAfterPageEdit(String actionName, Page snapshotBefore, Page snapshotAfter) {}
            @Override public void onResetToolToCursor() {}
            @Override public void onInspectMacroPage(String macroTypeName) {}
            @Override public boolean isPinOnScope(String pinRef) { return false; }
            @Override public void onTogglePinOnScope(String pinRef) {}
        };
    }

    private static Project buildMixedProject() {
        Project project = new Project("ExportTestProj", "Tester", IecLanguage.FBD, 1);
        project.getPages().clear();

        // 1. FBD page with blocks placed at a huge offset (100, 100) to verify tight bounds ignore empty space
        Page fbdPage = new Page("MainFBD", IecLanguage.FBD);
        BlockInstance andBlk = new BlockInstance(BlockCatalog.getById("Main.Timers.Counter"), "CNT_1", 100, 100, 1, 1);
        fbdPage.getBlocks().add(andBlk);
        project.getPages().add(fbdPage);

        // 2. Ladder page with contact and analog coil
        Page ldPage = new Page("MotorLD", IecLanguage.LD);
        LadderRung rung = new LadderRung(1, 1, "Start Motor");
        rung.getMainElements().add(new LadderElement(LadderElement.Kind.NO_CONTACT, "DI0"));
        rung.getCoils().add(new LadderElement(LadderElement.Kind.ANALOG_COIL, "100", "AQ0"));
        ldPage.getRungs().add(rung);
        project.getPages().add(ldPage);

        // 3. ST page with variables and code
        Page stPage = new Page("ControlST", IecLanguage.ST);
        stPage.getStVariables().add(new StVariable(StVariable.Scope.VAR, "CounterVal", DataType.INT, "0", ""));
        stPage.setStCode("IF DI0 THEN\n  CounterVal := CounterVal + 1;\nEND_IF;");
        project.getPages().add(stPage);

        return project;
    }

    @Test
    void tightBoundsIgnoreLargeCanvasOffsetAndPickA4() {
        Project project = buildMixedProject();
        SimulationEngine sim = new SimulationEngine(() -> {});
        FbdCanvas fbd = new FbdCanvas(project, sim, noopListener());
        LadderCanvas ld = new LadderCanvas(project, sim, noopListener());

        java.awt.Rectangle bounds = ProjectExporter.computePageBounds(project.getPages().get(0), fbd, ld);
        ProjectExporter.PaperFormat fmt = ProjectExporter.selectPaperForContent(bounds.width * 0.75, bounds.height * 0.75);
        assertEquals(ProjectExporter.PaperFormat.A4, fmt, "Offset blocks should still fit on A4");
    }

    @Test
    void compilerCountsAnalogCoilAndStVariables() {
        Project project = buildMixedProject();
        BytecodeCompiler.CompilationReport report = BytecodeCompiler.compile(project);
        assertTrue(report.ramBytesByDataType().get(DataType.INT) >= 8,
                "Expected INT RAM to include LD ANALOG_COIL (4B) + ST CounterVal (4B)");
        assertTrue(report.ramBytesByDataType().get(DataType.BOOL) >= 1,
                "Expected BOOL RAM to include LD NO_CONTACT");
    }

    @Test
    void exportSvgAndPngAndPdfWithSearchableTextAndPortraitReport(@TempDir Path tmpDir) throws Exception {
        Project project = buildMixedProject();
        SimulationEngine sim = new SimulationEngine(() -> {});
        FbdCanvas fbd = new FbdCanvas(project, sim, noopListener());
        LadderCanvas ld = new LadderCanvas(project, sim, noopListener());

        // SVG export
        Path svgDir = tmpDir.resolve("svg");
        List<File> svgs = ProjectExporter.exportAllPagesToSvg(project, fbd, ld, svgDir.toFile());
        assertEquals(3, svgs.size());
        for (File svg : svgs) {
            String xml = Files.readString(svg.toPath());
            assertTrue(xml.contains("<svg"), "Valid SVG root missing in " + svg.getName());
            assertTrue(xml.contains("<text "), "Selectable <text> elements missing in " + svg.getName());
        }
        String stSvg = Files.readString(svgs.get(2).toPath());
        assertTrue(stSvg.contains("CounterVal"), "ST code text should be present in SVG");

        // PNG export
        Path pngDir = tmpDir.resolve("png");
        List<File> pngs = ProjectExporter.exportAllPagesToPng(project, fbd, ld, pngDir.toFile());
        assertEquals(3, pngs.size());
        for (File png : pngs) {
            assertTrue(png.length() > 500, "PNG file should be non-empty: " + png.getName());
        }

        // PDF export
        File pdfFile = tmpDir.resolve("project.pdf").toFile();
        ProjectExporter.exportProjectToPdf(project, fbd, ld, pdfFile);
        assertTrue(pdfFile.exists() && pdfFile.length() > 1000);

        try (PdfReader reader = new PdfReader(pdfFile.getAbsolutePath())) {
            assertEquals(4, reader.getNumberOfPages(), "Expected 3 schematic/code pages + 1 report page");

            Rectangle stBox = reader.getPageSize(3);
            assertTrue(stBox.getHeight() > stBox.getWidth(), "ST page must be portrait");

            Rectangle reportBox = reader.getPageSize(4);
            assertTrue(reportBox.getHeight() > reportBox.getWidth(), "Last page must be portrait A4");

            PdfTextExtractor extractor = new PdfTextExtractor(reader);
            String page1Text = extractor.getTextFromPage(1);
            String page3Text = extractor.getTextFromPage(3);
            String reportText = extractor.getTextFromPage(4);

            assertTrue(page1Text.contains("MainFBD"), "FBD sheet name should be searchable in PDF");
            assertTrue(page3Text.contains("CounterVal"), "ST code should be searchable in PDF");
            assertTrue(reportText.contains("Project Report"), "Report header should be searchable");
            assertTrue(reportText.contains("1. Project Summary"), "Project Summary section should be searchable");
            assertTrue(reportText.contains("2. Hardware Usage Estimates"), "Hardware Usage Estimates should be searchable");
            assertTrue(reportText.contains("3. Thread Usage Estimates"), "Thread Usage Estimates should be searchable");
            assertTrue(reportText.contains("4. GPIO Summary"), "GPIO Summary should be searchable");
            assertTrue(reportText.contains("5. Interrupt Events Summary"), "Interrupt Events Summary should be searchable");
            assertTrue(reportText.contains("Interrupts Not used"), "Unused interrupts should show fallback");
            assertTrue(reportText.contains("6. Safety Configuration"), "Safety Configuration should be searchable");
            assertTrue(reportText.contains("µs") || reportText.contains("\u00B5s"), "Thread time should use micro sign");
            assertTrue(reportText.contains("DI0"), "Used GPIO pin DI0 should be listed in GPIO Summary");
            assertFalse(reportText.contains("Channel"), "Disabled E-Stop should omit emergency GPIO mapping table");
            assertTrue(reportText.contains("Generated by MaraFlow"), "Footer should be searchable");
            assertFalse(reportText.contains("|"), "Report header should not contain '|' separators");
            assertFalse(reportText.contains("Author: "), "Banner should not contain Author (moved to Project Summary)");
            assertTrue(reportText.contains("RAM Distribution Graph"), "RAM graph section should be searchable");
        }
    }

    @Test
    void longStSplitsAcrossMultiplePortraitPagesAndWideStStepsUpPaper(@TempDir Path tmpDir) throws Exception {
        Project project = new Project("StPagingProj", "Tester", IecLanguage.ST, 1);
        project.getPages().clear();

        StringBuilder longSt = new StringBuilder();
        for (int i = 1; i <= 120; i++) {
            longSt.append("x := x + ").append(i).append(";\n");
        }
        Page longPage = new Page("LongST", IecLanguage.ST);
        longPage.setStCode(longSt.toString());
        project.getPages().add(longPage);

        Page widePage = new Page("WideST", IecLanguage.ST);
        widePage.setStCode("VeryLongVariableName := " + "A".repeat(120) + ";\n");
        project.getPages().add(widePage);

        SimulationEngine sim = new SimulationEngine(() -> {});
        FbdCanvas fbd = new FbdCanvas(project, sim, noopListener());
        LadderCanvas ld = new LadderCanvas(project, sim, noopListener());

        File pdfFile = tmpDir.resolve("st_paging.pdf").toFile();
        ProjectExporter.exportProjectToPdf(project, fbd, ld, pdfFile);

        try (PdfReader reader = new PdfReader(pdfFile.getAbsolutePath())) {
            // 120 lines at ~69-70 lines/A4 portrait page -> 2 pages for LongST + 1 page for WideST + 1 report = 4 pages
            assertTrue(reader.getNumberOfPages() >= 4, "Long ST should split across multiple portrait pages");
            Rectangle p1 = reader.getPageSize(1);
            Rectangle p3 = reader.getPageSize(reader.getNumberOfPages() - 1); // WideST
            assertTrue(p1.getHeight() > p1.getWidth(), "LongST page 1 should be portrait");
            assertTrue(p3.getHeight() > p3.getWidth(), "WideST page should be portrait");
            assertTrue(p3.getWidth() > p1.getWidth(), "WideST should step up to a larger portrait paper size than A4");

            String reportText = new PdfTextExtractor(reader).getTextFromPage(reader.getNumberOfPages());
            assertTrue(reportText.contains("-"), "Project with no I/O should show 'I/O Not used' in GPIO Summary");
        }
    }
}
