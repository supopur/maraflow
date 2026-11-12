package cz.maramat.io;

import com.lowagie.text.Document;
import com.lowagie.text.PageSize;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.DefaultFontMapper;
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfGraphics2D;
import com.lowagie.text.pdf.PdfTemplate;
import com.lowagie.text.pdf.PdfWriter;
import cz.maramat.compiler.BytecodeCompiler;
import cz.maramat.model.BlockInstance;
import cz.maramat.model.DataType;
import cz.maramat.model.EStopConfig;
import cz.maramat.model.IecLanguage;
import cz.maramat.model.LadderBranch;
import cz.maramat.model.LadderElement;
import cz.maramat.model.LadderRung;
import cz.maramat.model.Page;
import cz.maramat.model.PlcThread;
import cz.maramat.model.Project;
import cz.maramat.ui.canvas.FbdCanvas;
import cz.maramat.ui.canvas.LadderCanvas;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Exports all project pages (FBD, LD, ST) to multi-page PDF (with trailing A4 portrait project report),
 * per-page vector SVG, or per-page high-DPI PNG in classic electrical CAD style.
 */
public final class ProjectExporter {

    private static final double MM_TO_PT = 72.0 / 25.4;
    private static final double PX_TO_PT = 0.75; // 96 px/in -> 72 pt/in
    private static final int EXPORT_GRID_PX = 12;

    private static final double FRAME_MARGIN_PT = 14.0;
    private static final double INNER_PAD_PT = 10.0;
    private static final double TITLE_BLOCK_W_PT = 310.0;
    private static final double TITLE_BLOCK_H_PT = 64.0;

    private static final Color LOGO_BLUE = new Color(0x0F, 0x60, 0xAA);
    private static final Color CAD_INK = Color.BLACK;
    private static final Color CAD_SUBTLE = Color.BLACK;
    private static final Color CAD_GRID = Color.BLACK;
    private static final Color CAD_HEADER_BG = Color.WHITE;

    public enum PaperFormat {
        A4("A4", 297, 210),
        B4("B4", 353, 250),
        A3("A3", 420, 297),
        B3("B3", 500, 353),
        A2("A2", 594, 420),
        B2("B2", 707, 500),
        A1("A1", 841, 594),
        B1("B1", 1000, 707),
        A0("A0", 1189, 841),
        B0("B0", 1414, 1000);

        public final String label;
        public final double widthPt;
        public final double heightPt;

        PaperFormat(String label, double widthMm, double heightMm) {
            this.label = label;
            this.widthPt = widthMm * MM_TO_PT;
            this.heightPt = heightMm * MM_TO_PT;
        }
    }

    private ProjectExporter() {}

    /**
     * Selects the smallest ISO 216 A/B landscape paper size that fits {@code contentWidthPt x contentHeightPt}.
     */
    public static PaperFormat selectPaperForContent(double contentWidthPt, double contentHeightPt) {
        for (PaperFormat fmt : PaperFormat.values()) {
            double availW = fmt.widthPt - 2 * FRAME_MARGIN_PT - 2 * INNER_PAD_PT;
            double availH = fmt.heightPt - 2 * FRAME_MARGIN_PT - TITLE_BLOCK_H_PT - 2 * INNER_PAD_PT;
            if (contentWidthPt <= availW && contentHeightPt <= availH) {
                return fmt;
            }
        }
        return PaperFormat.B0;
    }

    /**
     * Selects the smallest ISO 216 A/B portrait paper size whose width fits {@code contentWidthPt}.
     */
    public static PaperFormat selectPortraitPaperForWidth(double contentWidthPt) {
        for (PaperFormat fmt : PaperFormat.values()) {
            double portW = fmt.heightPt;
            double availW = portW - 2 * FRAME_MARGIN_PT - 2 * INNER_PAD_PT;
            if (contentWidthPt <= availW) {
                return fmt;
            }
        }
        return PaperFormat.B0;
    }

    private record ExportSheet(
            Page page,
            PaperFormat paper,
            boolean portrait,
            java.awt.Rectangle bounds,
            int stStartLine,
            int stEndLine,
            boolean stStartsInBlockComment,
            int partIndex,
            int partCount
    ) {
        double widthPt() { return portrait ? paper.heightPt : paper.widthPt; }
        double heightPt() { return portrait ? paper.widthPt : paper.heightPt; }
    }

    private static List<ExportSheet> buildExportSheets(Project project, FbdCanvas fbdCanvas, LadderCanvas ladderCanvas) {
        List<ExportSheet> sheets = new ArrayList<>();
        for (Page page : project.getPages()) {
            if (page.getLanguage() == IecLanguage.ST) {
                String code = page.getStCode() == null ? "" : page.getStCode();
                String[] lines = code.split("\r?\n", -1);
                int maxLen = 40;
                for (String line : lines) {
                    maxLen = Math.max(maxLen, line.length());
                }
                int neededPxW = 58 + maxLen * 7 + 20;
                PaperFormat fmt = selectPortraitPaperForWidth(neededPxW * PX_TO_PT);
                double portW = fmt.heightPt;
                double portH = fmt.widthPt;

                int fullPxW = (int) Math.floor((portW - 2 * FRAME_MARGIN_PT - 12.0) / PX_TO_PT);
                int fullPxH = (int) Math.floor((portH - 2 * FRAME_MARGIN_PT - 12.0) / PX_TO_PT);
                double upperBottomPt = portH - FRAME_MARGIN_PT - TITLE_BLOCK_H_PT - 6.0;
                int upperMaxPy = (int) Math.floor((upperBottomPt - (FRAME_MARGIN_PT + 6.0)) / PX_TO_PT);
                double tbLeftPt = portW - FRAME_MARGIN_PT - TITLE_BLOCK_W_PT - 6.0;
                int bottomZoneMaxPxW = (int) Math.floor((tbLeftPt - (FRAME_MARGIN_PT + 6.0) - 8.0) / PX_TO_PT);
                int totalMaxPy = fullPxH - 10;

                int totalLines = Math.max(1, lines.length);
                List<int[]> ranges = new ArrayList<>();
                int cur = 0;
                while (cur < totalLines) {
                    int start = cur;
                    int py = 36;
                    while (cur < totalLines) {
                        if (py <= upperMaxPy) {
                            cur++;
                            py += 15;
                        } else if (py <= totalMaxPy) {
                            String lineStr = cur < lines.length ? lines[cur] : "";
                            int linePxW = 54 + lineStr.length() * 7 + 8;
                            if (linePxW <= bottomZoneMaxPxW) {
                                cur++;
                                py += 15;
                            } else {
                                break;
                            }
                        } else {
                            break;
                        }
                    }
                    if (cur == start) {
                        // Safety fallback for single degenerate line
                        cur++;
                    }
                    ranges.add(new int[]{start, cur});
                }

                int partCount = ranges.size();
                boolean inBlockComment = false;
                for (int p = 0; p < partCount; p++) {
                    int start = ranges.get(p)[0];
                    int end = ranges.get(p)[1];
                    int sliceH = Math.min(fullPxH, Math.max(120, 36 + (end - start) * 15));
                    if (p < partCount - 1) {
                        sliceH = fullPxH;
                    }
                    java.awt.Rectangle bounds = new java.awt.Rectangle(0, 0, fullPxW, sliceH);
                    sheets.add(new ExportSheet(page, fmt, true, bounds, start, end, inBlockComment, p + 1, partCount));
                    inBlockComment = advanceBlockCommentState(lines, start, end, inBlockComment);
                }
            } else {
                java.awt.Rectangle bounds = computePageBounds(page, fbdCanvas, ladderCanvas);
                PaperFormat fmt = selectPaperForContent(bounds.width * PX_TO_PT, bounds.height * PX_TO_PT);
                sheets.add(new ExportSheet(page, fmt, false, bounds, 0, 0, false, 1, 1));
            }
        }
        return sheets;
    }

    private static boolean advanceBlockCommentState(String[] lines, int start, int end, boolean inBlockComment) {
        boolean state = inBlockComment;
        for (int i = start; i < end && i < lines.length; i++) {
            String line = lines[i];
            int idx = 0;
            while (idx < line.length()) {
                if (state) {
                    int close = line.indexOf("*)", idx);
                    if (close < 0) break;
                    state = false;
                    idx = close + 2;
                } else {
                    if (line.startsWith("//", idx)) break;
                    if (line.startsWith("(*", idx)) {
                        state = true;
                        idx += 2;
                    } else if (line.charAt(idx) == '\'') {
                        int q = idx + 1;
                        while (q < line.length() && line.charAt(q) != '\'') q++;
                        idx = Math.min(line.length(), q + 1);
                    } else {
                        idx++;
                    }
                }
            }
        }
        return state;
    }

    public static void exportProjectToPdf(Project project, FbdCanvas fbdCanvas, LadderCanvas ladderCanvas, File outPdf)
            throws IOException {
        if (project == null) throw new IllegalArgumentException("Project must not be null");
        List<ExportSheet> sheets = buildExportSheets(project, fbdCanvas, ladderCanvas);
        int totalSheetCount = sheets.size() + 1; // +1 for final A4 portrait project report

        DefaultFontMapper fontMapper = createFontMapper();

        Document doc = new Document(PageSize.A4.rotate(), 0, 0, 0, 0);
        try (FileOutputStream fos = new FileOutputStream(outPdf)) {
            PdfWriter writer = PdfWriter.getInstance(doc, fos);
            doc.open();

            for (int i = 0; i < sheets.size(); i++) {
                ExportSheet sheet = sheets.get(i);
                float sw = (float) sheet.widthPt();
                float sh = (float) sheet.heightPt();

                doc.setPageSize(new Rectangle(sw, sh));
                doc.newPage();

                PdfContentByte cb = writer.getDirectContent();
                PdfTemplate tpl = cb.createTemplate(sw, sh);
                Graphics2D g2 = new PdfGraphics2D(tpl, sw, sh, fontMapper, false, false, 0f);
                try {
                    renderSheet(g2, project, sheet, i + 1, totalSheetCount, fbdCanvas, ladderCanvas);
                } finally {
                    g2.dispose();
                }
                cb.addTemplate(tpl, 0, 0);
            }

            // Final Page: Portrait A4 Project Report
            float repW = PageSize.A4.getWidth();
            float repH = PageSize.A4.getHeight();
            doc.setPageSize(PageSize.A4);
            doc.newPage();

            PdfContentByte cb = writer.getDirectContent();
            PdfTemplate tpl = cb.createTemplate(repW, repH);
            Graphics2D g2 = new PdfGraphics2D(tpl, repW, repH, fontMapper, false, false, 0f);
            try {
                renderProjectReport(g2, project, totalSheetCount, totalSheetCount, repW, repH);
            } finally {
                g2.dispose();
            }
            cb.addTemplate(tpl, 0, 0);
            doc.close();
        } catch (com.lowagie.text.DocumentException ex) {
            throw new IOException("PDF generation failed: " + ex.getMessage(), ex);
        }
    }

    public static List<File> exportAllPagesToSvg(Project project, FbdCanvas fbdCanvas, LadderCanvas ladderCanvas, File outDir)
            throws IOException {
        if (!outDir.exists() && !outDir.mkdirs()) {
            throw new IOException("Could not create export directory: " + outDir);
        }
        List<ExportSheet> sheets = buildExportSheets(project, fbdCanvas, ladderCanvas);
        List<File> written = new ArrayList<>();
        int totalSheets = sheets.size();
        for (int i = 0; i < sheets.size(); i++) {
            ExportSheet sheet = sheets.get(i);

            SvgGraphics2D svg = new SvgGraphics2D();
            renderSheet(svg, project, sheet, i + 1, totalSheets, fbdCanvas, ladderCanvas);
            String xml = svg.toSvgString(sheet.widthPt(), sheet.heightPt());

            String suffix = sheet.partCount() > 1 ? "_p" + sheet.partIndex() : "";
            String fileName = String.format(Locale.US, "%02d_%s%s.svg", i + 1, sanitizeFilename(sheet.page().getName()), suffix);
            File outFile = new File(outDir, fileName);
            Files.writeString(outFile.toPath(), xml, StandardCharsets.UTF_8);
            written.add(outFile);
        }
        return written;
    }

    public static List<File> exportAllPagesToPng(Project project, FbdCanvas fbdCanvas, LadderCanvas ladderCanvas, File outDir)
            throws IOException {
        if (!outDir.exists() && !outDir.mkdirs()) {
            throw new IOException("Could not create export directory: " + outDir);
        }
        List<ExportSheet> sheets = buildExportSheets(project, fbdCanvas, ladderCanvas);
        List<File> written = new ArrayList<>();
        int totalSheets = sheets.size();
        double scale = 2.0; // Crisp 144+ DPI rasterization
        for (int i = 0; i < sheets.size(); i++) {
            ExportSheet sheet = sheets.get(i);

            int imgW = (int) Math.ceil(sheet.widthPt() * scale);
            int imgH = (int) Math.ceil(sheet.heightPt() * scale);
            BufferedImage img = new BufferedImage(imgW, imgH, BufferedImage.TYPE_INT_RGB);
            Graphics2D g2 = img.createGraphics();
            try {
                g2.scale(scale, scale);
                renderSheet(g2, project, sheet, i + 1, totalSheets, fbdCanvas, ladderCanvas);
            } finally {
                g2.dispose();
            }

            String suffix = sheet.partCount() > 1 ? "_p" + sheet.partIndex() : "";
            String fileName = String.format(Locale.US, "%02d_%s%s.png", i + 1, sanitizeFilename(sheet.page().getName()), suffix);
            File outFile = new File(outDir, fileName);
            ImageIO.write(img, "png", outFile);
            written.add(outFile);
        }
        return written;
    }

    private static DefaultFontMapper createFontMapper() {
        DefaultFontMapper mapper = new DefaultFontMapper();
        String[] fontDirs = {
                "/System/Library/Fonts/Supplemental",
                "/Library/Fonts",
                "/usr/share/fonts/truetype",
                "C:\\Windows\\Fonts"
        };
        for (String dir : fontDirs) {
            File d = new File(dir);
            if (d.isDirectory()) {
                mapper.insertDirectory(dir);
            }
        }
        return mapper;
    }

    static java.awt.Rectangle computePageBounds(Page page, FbdCanvas fbdCanvas, LadderCanvas ladderCanvas) {
        if (page == null) return new java.awt.Rectangle(0, 0, 240, 140);
        return switch (page.getLanguage()) {
            case FBD -> fbdCanvas.computeExportBounds(page, EXPORT_GRID_PX);
            case LD -> ladderCanvas.computeExportBounds(page);
            case ST -> computeStBounds(page);
        };
    }

    private static java.awt.Rectangle computeStBounds(Page page) {
        String code = page.getStCode() == null ? "" : page.getStCode();
        String[] lines = code.split("\r?\n", -1);
        int maxLen = 40;
        for (String line : lines) {
            maxLen = Math.max(maxLen, line.length());
        }
        int lineCount = Math.max(1, lines.length);
        int width = 58 + maxLen * 7 + 24;
        int height = 24 + lineCount * 15 + 16;
        return new java.awt.Rectangle(0, 0, width, height);
    }

    private static void renderSheet(Graphics2D g2, Project project, ExportSheet sheet, int sheetNum, int totalSheets,
                                    FbdCanvas fbdCanvas, LadderCanvas ladderCanvas) {
        Page page = sheet.page();
        java.awt.Rectangle bounds = sheet.bounds();
        double w = sheet.widthPt();
        double h = sheet.heightPt();

        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        // White CAD paper background
        g2.setColor(Color.WHITE);
        g2.fill(new Rectangle2D.Double(0, 0, w, h));

        // Double CAD sheet border
        drawCadBorder(g2, w, h);

        // Content area above the bottom title block reserve (ST uses full frame and avoids title block per-line)
        boolean isSt = page.getLanguage() == IecLanguage.ST;
        double contentLeft = isSt ? FRAME_MARGIN_PT + 6.0 : FRAME_MARGIN_PT + INNER_PAD_PT;
        double contentTop = isSt ? FRAME_MARGIN_PT + 6.0 : FRAME_MARGIN_PT + INNER_PAD_PT;
        double contentW = isSt ? w - 2 * FRAME_MARGIN_PT - 12.0 : w - 2 * FRAME_MARGIN_PT - 2 * INNER_PAD_PT;
        double contentH = isSt ? h - 2 * FRAME_MARGIN_PT - 12.0 : h - 2 * FRAME_MARGIN_PT - TITLE_BLOCK_H_PT - 2 * INNER_PAD_PT;

        double naturalW = Math.max(1.0, bounds.width * PX_TO_PT);
        double naturalH = Math.max(1.0, bounds.height * PX_TO_PT);
        double fitScale = isSt ? 1.0 : Math.min(1.0, Math.min(contentW / naturalW, contentH / naturalH));
        double drawScale = PX_TO_PT * fitScale;

        double drawnW = bounds.width * drawScale;
        double drawnH = bounds.height * drawScale;
        double offsetX = isSt ? contentLeft : contentLeft + (contentW - drawnW) / 2.0;
        double offsetY = isSt ? contentTop : contentTop + (contentH - drawnH) / 2.0;

        Graphics2D cg = (Graphics2D) g2.create();
        try {
            if (isSt) {
                cg.clip(new Rectangle2D.Double(FRAME_MARGIN_PT + 3, FRAME_MARGIN_PT + 3,
                        w - 2 * FRAME_MARGIN_PT - 6, h - 2 * FRAME_MARGIN_PT - 6));
            } else {
                cg.clip(new Rectangle2D.Double(FRAME_MARGIN_PT + 2, FRAME_MARGIN_PT + 2,
                        w - 2 * FRAME_MARGIN_PT - 4, h - 2 * FRAME_MARGIN_PT - TITLE_BLOCK_H_PT - 4));
            }
            cg.translate(offsetX, offsetY);
            cg.scale(drawScale, drawScale);
            cg.translate(-bounds.x, -bounds.y);

            switch (page.getLanguage()) {
                case FBD -> fbdCanvas.paintPageForExport(cg, page, EXPORT_GRID_PX);
                case LD -> ladderCanvas.paintPageForExport(cg, page);
                case ST -> paintStCode(cg, sheet);
            }
        } finally {
            cg.dispose();
        }

        // CAD Title Block at bottom-right
        drawTitleBlock(g2, project, sheet, sheetNum, totalSheets, w, h);
    }

    private static void drawCadBorder(Graphics2D g2, double w, double h) {
        g2.setColor(CAD_INK);
        g2.setStroke(new BasicStroke(1.3f));
        g2.draw(new Rectangle2D.Double(FRAME_MARGIN_PT, FRAME_MARGIN_PT,
                w - 2 * FRAME_MARGIN_PT, h - 2 * FRAME_MARGIN_PT));

        g2.setStroke(new BasicStroke(0.6f));
        g2.draw(new Rectangle2D.Double(FRAME_MARGIN_PT + 3, FRAME_MARGIN_PT + 3,
                w - 2 * FRAME_MARGIN_PT - 6, h - 2 * FRAME_MARGIN_PT - 6));
    }

    private static void drawTitleBlock(Graphics2D g2, Project project, ExportSheet sheet, int sheetNum, int totalSheets,
                                       double pageW, double pageH) {
        Page page = sheet.page();
        PaperFormat paper = sheet.paper();
        double tbW = Math.min(TITLE_BLOCK_W_PT, pageW - 2 * FRAME_MARGIN_PT);
        double tbH = TITLE_BLOCK_H_PT;
        double x0 = pageW - FRAME_MARGIN_PT - tbW;
        double y0 = pageH - FRAME_MARGIN_PT - tbH;

        g2.setColor(Color.WHITE);
        g2.fill(new Rectangle2D.Double(x0, y0, tbW, tbH));

        g2.setColor(CAD_INK);
        g2.setStroke(new BasicStroke(1.1f));
        g2.draw(new Rectangle2D.Double(x0, y0, tbW, tbH));

        // Logo cell on left
        double logoW = 104.0;
        g2.setStroke(new BasicStroke(0.7f));
        g2.draw(new Rectangle2D.Double(x0, y0, logoW, tbH));
        drawMaramatLogo(g2, x0 + 6, y0 + 18, logoW - 12, 24);

        g2.setFont(new Font("SansSerif", Font.BOLD, 6));
        g2.setColor(CAD_INK);
        g2.drawString("Maraflow", (float) (x0 + 36), (float) (y0 + 54));

        // Right metadata grid: 3 rows
        double gx = x0 + logoW;
        double gw = tbW - logoW;
        double r1H = 22.0;
        double r2H = 21.0;
        double r3H = tbH - r1H - r2H;

        g2.setColor(CAD_INK);
        g2.draw(new Rectangle2D.Double(gx, y0, gw, r1H));
        g2.draw(new Rectangle2D.Double(gx, y0 + r1H, gw, r2H));
        g2.draw(new Rectangle2D.Double(gx, y0 + r1H + r2H, gw, r3H));

        double c2a = gw * 0.42;
        double c2b = gw * 0.30;
        g2.draw(new Rectangle2D.Double(gx, y0 + r1H, c2a, r2H));
        g2.draw(new Rectangle2D.Double(gx + c2a, y0 + r1H, c2b, r2H));
        double c3a = gw * 0.36;
        double c3b = gw * 0.34;
        g2.draw(new Rectangle2D.Double(gx, y0 + r1H + r2H, c3a, r3H));
        g2.draw(new Rectangle2D.Double(gx + c3a, y0 + r1H + r2H, c3b, r3H));

        String author = System.getProperty("user.name");
        if (author == null || author.isBlank()) {
            author = project.getAuthor();
        }
        int blockOrRungCount = switch (page.getLanguage()) {
            case FBD -> page.getBlocks().size();
            case LD -> page.getRungs().size();
            case ST -> Math.max(1, (page.getStCode() == null ? "" : page.getStCode()).split("\r?\n", -1).length);
        };
        String countLabel = switch (page.getLanguage()) {
            case FBD -> "BLOCKS";
            case LD -> "RUNGS";
            case ST -> "LINES";
        };
        int macroCalls = countMacroCallsOnPage(page);

        String sheetTitle = project.getProjectName() + " - " + page.getName();
        if (sheet.partCount() > 1) {
            sheetTitle += " (" + sheet.partIndex() + "/" + sheet.partCount() + ")";
        }

        drawTitleCell(g2, gx, y0, gw, r1H, "PROJECT / TITLE", sheetTitle, 8.5f, true);
        drawTitleCell(g2, gx, y0 + r1H, c2a, r2H, "AUTHOR", author, 7.5f, false);
        drawTitleCell(g2, gx + c2a, y0 + r1H, c2b, r2H, "DATE", LocalDate.now().toString(), 7.5f, false);
        drawTitleCell(g2, gx + c2a + c2b, y0 + r1H, gw - c2a - c2b, r2H, "LANG / SIZE",
                page.getLanguage().getShortCode() + " (" + paper.label + ")", 7.5f, false);
        drawTitleCell(g2, gx, y0 + r1H + r2H, c3a, r3H, countLabel,
                Integer.toString(blockOrRungCount), 7.5f, false);
        drawTitleCell(g2, gx + c3a, y0 + r1H + r2H, c3b, r3H, "MACRO CALLS",
                Integer.toString(macroCalls), 7.5f, false);
        drawTitleCell(g2, gx + c3a + c3b, y0 + r1H + r2H, gw - c3a - c3b, r3H, "SHEET",
                sheetNum + " / " + totalSheets, 8.0f, true);
    }

    private static void drawTitleCell(Graphics2D g2, double x, double y, double w, double h,
                                      String label, String value, float valueSize, boolean bold) {
        g2.setFont(new Font("SansSerif", Font.BOLD, 5));
        g2.setColor(CAD_SUBTLE);
        g2.drawString(label, (float) (x + 3), (float) (y + 7));

        g2.setFont(new Font("SansSerif", bold ? Font.BOLD : Font.PLAIN, Math.round(valueSize)));
        g2.setColor(CAD_INK);
        String clipped = clipText(value == null ? "" : value, g2.getFontMetrics(), (int) Math.max(20, w - 6));
        g2.drawString(clipped, (float) (x + 3), (float) (y + h - 4));
    }

    private static String clipText(String text, FontMetrics fm, int maxWidth) {
        if (fm.stringWidth(text) <= maxWidth) return text;
        String ell = "...";
        int ellW = fm.stringWidth(ell);
        int end = text.length();
        while (end > 0 && fm.stringWidth(text.substring(0, end)) + ellW > maxWidth) {
            end--;
        }
        return end <= 0 ? ell : text.substring(0, end) + ell;
    }

    private static int countMacroCallsOnPage(Page page) {
        int count = 0;
        for (BlockInstance b : page.getAllBlockInstances()) {
            if (b.getDefinitionId().startsWith("Macro.User.")) {
                count++;
            }
        }
        return count;
    }

    // =========================================================================
    // Syntax-Highlighted ST Renderer
    // =========================================================================

    private static final Set<String> ST_KEYWORDS = Set.of(
            "IF", "THEN", "ELSIF", "ELSE", "END_IF", "CASE", "OF", "END_CASE",
            "FOR", "TO", "BY", "DO", "END_FOR", "WHILE", "END_WHILE",
            "REPEAT", "UNTIL", "END_REPEAT", "VAR", "VAR_INPUT", "VAR_OUTPUT",
            "VAR_IN_OUT", "END_VAR", "TRUE", "FALSE", "AND", "OR", "XOR", "NOT",
            "MOD", "RETURN", "EXIT", "PROGRAM", "END_PROGRAM"
    );

    private static final Set<String> ST_TYPES = Set.of(
            "BOOL", "INT", "DINT", "REAL", "TIME", "STRING", "BYTE", "WORD", "DWORD"
    );

    private static void paintStCode(Graphics2D g2, ExportSheet sheet) {
        Page page = sheet.page();
        int width = sheet.bounds().width;
        int height = sheet.bounds().height;

        g2.setColor(Color.WHITE);
        g2.fillRect(0, 0, width, height);

        // CAD code header bar
        g2.setColor(CAD_INK);
        g2.setStroke(new BasicStroke(0.8f));
        g2.drawRect(0, 0, width - 1, height - 1);
        g2.drawLine(0, 20, width - 1, 20);
        g2.drawLine(46, 20, 46, height - 1);

        g2.setFont(new Font("SansSerif", Font.BOLD, 10));
        String hdr = "STRUCTURED TEXT: " + page.getName()
                + (sheet.partCount() > 1 ? " (" + sheet.partIndex() + "/" + sheet.partCount() + ")" : "");
        g2.drawString(hdr, 8, 14);

        String code = page.getStCode() == null ? "" : page.getStCode();
        String[] lines = code.split("\r?\n", -1);

        Font monoPlain = new Font("Courier New", Font.PLAIN, 11);
        Font monoBold = new Font("Courier New", Font.BOLD, 11);
        Font monoItalic = new Font("Courier New", Font.ITALIC, 11);
        FontMetrics fm = g2.getFontMetrics(monoPlain);
        int charW = Math.max(6, fm.charWidth('M'));

        boolean inBlockComment = sheet.stStartsInBlockComment();
        int y = 36;
        int start = sheet.stStartLine();
        int endLine = Math.min(lines.length, sheet.stEndLine());
        for (int i = start; i < endLine; i++) {
            // Line number
            g2.setFont(monoPlain);
            g2.setColor(CAD_INK);
            g2.drawString(String.format(Locale.US, "%4d", i + 1), 6, y);

            String line = lines[i];
            int x = 54;
            int idx = 0;
            int len = line.length();
            while (idx < len) {
                if (inBlockComment) {
                    int end = line.indexOf("*)", idx);
                    if (end < 0) {
                        drawStToken(g2, line.substring(idx), x, y, monoItalic, new Color(0x00, 0x7A, 0x00));
                        idx = len;
                    } else {
                        String tok = line.substring(idx, end + 2);
                        drawStToken(g2, tok, x, y, monoItalic, new Color(0x00, 0x7A, 0x00));
                        x += tok.length() * charW;
                        idx = end + 2;
                        inBlockComment = false;
                    }
                    continue;
                }
                if (line.startsWith("(*", idx)) {
                    inBlockComment = true;
                    continue;
                }
                if (line.startsWith("//", idx)) {
                    drawStToken(g2, line.substring(idx), x, y, monoItalic, new Color(0x00, 0x7A, 0x00));
                    break;
                }
                char c = line.charAt(idx);
                if (c == '\'') {
                    int end = idx + 1;
                    while (end < len && line.charAt(end) != '\'') end++;
                    if (end < len) end++;
                    String tok = line.substring(idx, end);
                    drawStToken(g2, tok, x, y, monoPlain, new Color(0xA3, 0x15, 0x15));
                    x += tok.length() * charW;
                    idx = end;
                } else if (Character.isDigit(c)) {
                    int end = idx + 1;
                    while (end < len && (Character.isDigit(line.charAt(end)) || line.charAt(end) == '.'
                            || line.charAt(end) == '_' || Character.isLetter(line.charAt(end)))) {
                        end++;
                    }
                    String tok = line.substring(idx, end);
                    drawStToken(g2, tok, x, y, monoPlain, new Color(0x09, 0x86, 0x58));
                    x += tok.length() * charW;
                    idx = end;
                } else if (Character.isLetter(c) || c == '_') {
                    int end = idx + 1;
                    while (end < len && (Character.isLetterOrDigit(line.charAt(end)) || line.charAt(end) == '_')) {
                        end++;
                    }
                    String tok = line.substring(idx, end);
                    String upper = tok.toUpperCase(Locale.ROOT);
                    if (ST_KEYWORDS.contains(upper)) {
                        drawStToken(g2, tok, x, y, monoBold, new Color(0x00, 0x00, 0xCC));
                    } else if (ST_TYPES.contains(upper)) {
                        drawStToken(g2, tok, x, y, monoBold, new Color(0x00, 0x7A, 0x7A));
                    } else {
                        drawStToken(g2, tok, x, y, monoPlain, CAD_INK);
                    }
                    x += tok.length() * charW;
                    idx = end;
                } else {
                    int end = idx + 1;
                    while (end < len && !Character.isLetterOrDigit(line.charAt(end))
                            && line.charAt(end) != '_' && line.charAt(end) != '\''
                            && !line.startsWith("//", end) && !line.startsWith("(*", end)) {
                        end++;
                    }
                    String tok = line.substring(idx, end);
                    drawStToken(g2, tok, x, y, monoPlain, CAD_INK);
                    x += tok.length() * charW;
                    idx = end;
                }
            }
            y += 15;
        }
    }

    private static void drawStToken(Graphics2D g2, String text, int x, int y, Font font, Color color) {
        g2.setFont(font);
        g2.setColor(color);
        g2.drawString(text, x, y);
    }

    // =========================================================================
    // Portrait A4 Project Report (Final PDF Page)
    // =========================================================================

    private static void renderProjectReport(Graphics2D g2, Project project, int sheetNum, int totalSheets,
                                            double w, double h) {
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        g2.setColor(Color.WHITE);
        g2.fill(new Rectangle2D.Double(0, 0, w, h));
        drawCadBorder(g2, w, h);

        BytecodeCompiler.CompilationReport report = BytecodeCompiler.compile(project);

        double x = FRAME_MARGIN_PT + 12;
        double cw = w - 2 * FRAME_MARGIN_PT - 24;
        double y = FRAME_MARGIN_PT + 14;

        // Report Header Banner
        g2.setColor(Color.WHITE);
        g2.fill(new Rectangle2D.Double(x, y, cw, 46));
        g2.setColor(CAD_INK);
        g2.setStroke(new BasicStroke(0.9f));
        g2.draw(new Rectangle2D.Double(x, y, cw, 46));

        drawMaramatLogo(g2, x + 8, y + 9, 110, 28);
        g2.setFont(new Font("SansSerif", Font.BOLD, 13));
        g2.setColor(CAD_INK);
        g2.drawString("Project Report", (float) (x + 128), (float) (y + 20));
        g2.setFont(new Font("SansSerif", Font.PLAIN, 8));
        g2.setColor(CAD_INK);
        String author = System.getProperty("user.name", project.getAuthor());
        g2.drawString("Project: " + project.getProjectName()
                + "     Date: " + LocalDate.now(), (float) (x + 128), (float) (y + 35));

        y += 58;

        // 1. Project & Program Structure Summary
        int fbdBlocks = 0;
        int ldRungs = 0;
        int stPages = 0;
        int definedMacros = 0;
        int macroCalls = 0;
        int[] threadBlocks = new int[5];

        for (Page p : project.getPages()) {
            if (p.isMacro()) definedMacros++;
            if (p.getLanguage() == IecLanguage.FBD) {
                fbdBlocks += p.getBlocks().size();
            } else if (p.getLanguage() == IecLanguage.LD) {
                ldRungs += p.getRungs().size();
                for (LadderRung rung : p.getRungs()) {
                    int tid = Math.clamp(rung.getThreadId() - 1, 0, 4);
                    threadBlocks[tid]++;
                }
            } else if (p.getLanguage() == IecLanguage.ST) {
                stPages++;
                int tid = Math.clamp(p.getStThreadId() - 1, 0, 4);
                threadBlocks[tid]++;
            }
            for (BlockInstance b : p.getAllBlockInstances()) {
                if (b.getDefinitionId().startsWith("Macro.User.")) {
                    macroCalls++;
                }
                if (p.getLanguage() == IecLanguage.FBD) {
                    int tid = Math.clamp(b.getThreadId() - 1, 0, 4);
                    threadBlocks[tid]++;
                }
            }
        }

        y = drawSectionHeader(g2, x, y, cw, "1. Project Summary");
        String[][] summaryRows = {
                {"Target", project.getTargetPlatform(), "Total Pages", Integer.toString(project.getPages().size())},
                {"FBD Blocks", Integer.toString(fbdBlocks), "Ladder Rungs", Integer.toString(ldRungs)},
                {"ST Program Units", Integer.toString(stPages), "Defined Macros", Integer.toString(definedMacros)},
                {"Macro Calls", Integer.toString(macroCalls), "Author", author}
        };
        y = drawKeyValueGrid(g2, x, y, cw, summaryRows);
        y += 12;

        // 2. Memory & CPU Utilization + RAM Variable Usage Graph
        y = drawSectionHeader(g2, x, y, cw, "2. Hardware Usage Estimates");
        y = drawMetricBar(g2, x, y, cw, "RAM Usage", report.ramUsedBytes(), report.ramMaxBytes(), "B", new Color(0x0F, 0x60, 0xAA));
        y = drawMetricBar(g2, x, y, cw, "Flash Estimate", report.flashUsedBytes(), report.flashMaxBytes(), "B", new Color(0x16, 0xA3, 0x4A));
        y = drawMetricBar(g2, x, y, cw, "EEPROM Usage", report.eepromUsedBytes(), report.eepromMaxBytes(), "B", new Color(0xD9, 0x77, 0x06));
        y = drawMetricBar(g2, x, y, cw, "Total CPU Load", report.totalCpuPercent(), 100, "%", new Color(0xDC, 0x26, 0x26));
        y += 6;

        y = drawRamUsageGraph(g2, x, y, cw, report);
        y += 10;

        // 3. Thread Execution & Block Distribution
        y = drawSectionHeader(g2, x, y, cw, "3. Thread Usage Estimates");
        y = drawThreadTable(g2, x, y, cw, report, threadBlocks);
        y += 10;

        // 4. GPIO Summary
        y = drawSectionHeader(g2, x, y, cw, "4. GPIO Summary");
        y = drawGpioSummaryTable(g2, x, y, cw, project);
        y += 10;

        // 5. Interrupt Events Summary
        y = drawSectionHeader(g2, x, y, cw, "5. Interrupt Events Summary");
        y = drawInterruptSummaryTable(g2, x, y, cw, project);
        y += 10;

        // 6. E-Stop Safety Configuration
        y = drawSectionHeader(g2, x, y, cw, "6. Safety Configuration");
        drawEStopSection(g2, x, y, cw, project.getEStopConfig());

        // Footer
        g2.setFont(new Font("SansSerif", Font.PLAIN, 7));
        g2.setColor(CAD_INK);
        g2.drawString("Generated by MaraFlow", (float) x, (float) (h - FRAME_MARGIN_PT - 8));
        g2.drawString("Sheet " + sheetNum + " / " + totalSheets, (float) (x + cw - 54), (float) (h - FRAME_MARGIN_PT - 8));
    }

    private static double drawSectionHeader(Graphics2D g2, double x, double y, double w, String title) {
        g2.setColor(Color.WHITE);
        g2.fill(new Rectangle2D.Double(x, y, w, 18));
        g2.setColor(CAD_INK);
        g2.setStroke(new BasicStroke(0.9f));
        g2.draw(new Rectangle2D.Double(x, y, w, 18));
        g2.setFont(new Font("SansSerif", Font.BOLD, 9));
        g2.drawString(title, (float) (x + 6), (float) (y + 12));
        return y + 18;
    }

    private static double drawKeyValueGrid(Graphics2D g2, double x, double y, double w, String[][] rows) {
        double rowH = 18.0;
        double q = w / 4.0;
        g2.setStroke(new BasicStroke(0.6f));
        for (int r = 0; r < rows.length; r++) {
            double ry = y + r * rowH;
            for (int c = 0; c < 4; c++) {
                double cx = x + c * q;
                g2.setColor(Color.WHITE);
                g2.fill(new Rectangle2D.Double(cx, ry, q, rowH));
                g2.setColor(CAD_INK);
                g2.draw(new Rectangle2D.Double(cx, ry, q, rowH));
                g2.setFont(new Font("SansSerif", c % 2 == 0 ? Font.PLAIN : Font.BOLD, 8));
                String clipped = clipText(rows[r][c] == null ? "" : rows[r][c], g2.getFontMetrics(), (int) (q - 8));
                g2.drawString(clipped, (float) (cx + 5), (float) (ry + 12));
            }
        }
        g2.setColor(CAD_INK);
        g2.draw(new Rectangle2D.Double(x, y, w, rows.length * rowH));
        return y + rows.length * rowH;
    }

    private static double drawMetricBar(Graphics2D g2, double x, double y, double w, String label,
                                        int used, int max, String unit, Color fillColor) {
        double rowH = 18.0;
        double labelW = 105.0;
        double valW = 115.0;
        double barW = w - labelW - valW - 10;

        g2.setFont(new Font("SansSerif", Font.BOLD, 8));
        g2.setColor(CAD_INK);
        g2.drawString(label, (float) (x + 4), (float) (y + 12));

        double bx = x + labelW;
        double by = y + 4;
        double bh = 10.0;
        g2.setColor(new Color(0xE2, 0xE8, 0xF0));
        g2.fill(new Rectangle2D.Double(bx, by, barW, bh));

        double ratio = max > 0 ? Math.min(1.0, Math.max(0.0, (double) used / max)) : 0.0;
        g2.setColor(fillColor);
        g2.fill(new Rectangle2D.Double(bx, by, barW * ratio, bh));

        g2.setColor(CAD_INK);
        g2.setStroke(new BasicStroke(0.5f));
        g2.draw(new Rectangle2D.Double(bx, by, barW, bh));

        g2.setFont(new Font("SansSerif", Font.PLAIN, 8));
        String txt = used + " / " + max + " " + unit + String.format(Locale.US, " (%.1f%%)", ratio * 100.0);
        g2.drawString(txt, (float) (bx + barW + 6), (float) (y + 12));
        return y + rowH;
    }

    private static double drawRamUsageGraph(Graphics2D g2, double x, double y, double w,
                                            BytecodeCompiler.CompilationReport report) {
        Map<String, Integer> slices = new LinkedHashMap<>();
        for (Map.Entry<DataType, Integer> e : report.ramBytesByDataType().entrySet()) {
            if (e.getValue() > 0) {
                slices.put(e.getKey().getDisplayName(), e.getValue());
            }
        }
        slices.put("VM Overhead", report.vmOverheadRamBytes());

        int total = Math.max(1, report.ramUsedBytes());
        Color[] palette = {
                new Color(0x0F, 0x60, 0xAA),
                new Color(0x16, 0xA3, 0x4A),
                new Color(0xD9, 0x77, 0x06),
                new Color(0x93, 0x33, 0xEA),
                new Color(0x08, 0x91, 0xB2),
                new Color(0x64, 0x74, 0x8B)
        };

        g2.setFont(new Font("SansSerif", Font.BOLD, 8));
        g2.setColor(CAD_INK);
        g2.drawString("RAM Distribution Graph (Total: " + total + " B):", (float) (x + 4), (float) (y + 10));
        y += 14;

        // Stacked horizontal bar graph
        double barH = 18.0;
        double curX = x;
        int idx = 0;
        for (Map.Entry<String, Integer> e : slices.entrySet()) {
            double segW = w * ((double) e.getValue() / total);
            g2.setColor(palette[idx % palette.length]);
            g2.fill(new Rectangle2D.Double(curX, y, Math.max(1.0, segW), barH));
            curX += segW;
            idx++;
        }
        g2.setColor(CAD_INK);
        g2.setStroke(new BasicStroke(0.6f));
        g2.draw(new Rectangle2D.Double(x, y, w, barH));
        y += barH + 8;

        // Legend grid (3 columns)
        int col = 0;
        idx = 0;
        double colW = w / 3.0;
        for (Map.Entry<String, Integer> e : slices.entrySet()) {
            double lx = x + col * colW;
            g2.setColor(palette[idx % palette.length]);
            g2.fill(new Rectangle2D.Double(lx + 4, y + 2, 8, 8));
            g2.setColor(CAD_INK);
            g2.draw(new Rectangle2D.Double(lx + 4, y + 2, 8, 8));

            g2.setFont(new Font("SansSerif", Font.PLAIN, 8));
            double pct = 100.0 * e.getValue() / total;
            g2.drawString(String.format(Locale.US, "%s: %d B (%.1f%%)", e.getKey(), e.getValue(), pct),
                    (float) (lx + 16), (float) (y + 9));

            col++;
            idx++;
            if (col == 3) {
                col = 0;
                y += 14;
            }
        }
        if (col != 0) y += 14;
        return y;
    }

    private static double drawThreadTable(Graphics2D g2, double x, double y, double w,
                                          BytecodeCompiler.CompilationReport report, int[] threadBlocks) {
        double[] colW = {w * 0.34, w * 0.14, w * 0.16, w * 0.18, w * 0.18};
        String[] headers = {"Thread", "Period", "Blocks / Units", "Est. Time", "Budget Max"};
        double rowH = 16.0;

        g2.setStroke(new BasicStroke(0.5f));
        double cx = x;
        for (int c = 0; c < headers.length; c++) {
            g2.setColor(Color.WHITE);
            g2.fill(new Rectangle2D.Double(cx, y, colW[c], rowH));
            g2.setColor(CAD_INK);
            g2.draw(new Rectangle2D.Double(cx, y, colW[c], rowH));
            g2.setFont(new Font("SansSerif", Font.BOLD, 8));
            g2.drawString(headers[c], (float) (cx + 4), (float) (y + 11));
            cx += colW[c];
        }
        y += rowH;

        PlcThread[] threads = PlcThread.values();
        for (int i = 0; i < threads.length; i++) {
            PlcThread t = threads[i];
            int estUs = i < report.threadEstUs().length ? report.threadEstUs()[i] : 0;
            int maxUs = i < report.threadMaxUs().length ? report.threadMaxUs()[i] : t.getMaxAllowedUs();
            int blkCount = i < threadBlocks.length ? threadBlocks[i] : 0;
            String[] vals = {
                    t.getDisplayLabel(),
                    t.getPeriodMs() + " ms",
                    Integer.toString(blkCount),
                    estUs + " \u00B5s",
                    maxUs + " \u00B5s"
            };
            cx = x;
            for (int c = 0; c < vals.length; c++) {
                g2.setColor(Color.WHITE);
                g2.fill(new Rectangle2D.Double(cx, y, colW[c], rowH));
                g2.setColor(CAD_GRID);
                g2.draw(new Rectangle2D.Double(cx, y, colW[c], rowH));
                g2.setColor(CAD_INK);
                g2.setFont(new Font("SansSerif", Font.PLAIN, 8));
                g2.drawString(vals[c], (float) (cx + 4), (float) (y + 11));
                cx += colW[c];
            }
            y += rowH;
        }
        g2.setColor(CAD_INK);
        g2.draw(new Rectangle2D.Double(x, y - (threads.length + 1) * rowH, w, (threads.length + 1) * rowH));
        return y;
    }

    private static double drawGpioSummaryTable(Graphics2D g2, double x, double y, double w, Project project) {
        java.util.Comparator<String> pinOrder = java.util.Comparator
                .comparing((String s) -> s.replaceAll("\\d+", ""))
                .thenComparingInt(s -> {
                    String digits = s.replaceAll("\\D+", "");
                    return digits.isEmpty() ? 0 : Integer.parseInt(digits);
                });
        java.util.Set<String> di = new java.util.TreeSet<>(pinOrder);
        java.util.Set<String> dOut = new java.util.TreeSet<>(pinOrder);
        java.util.Set<String> ai = new java.util.TreeSet<>(pinOrder);
        java.util.Set<String> ao = new java.util.TreeSet<>(pinOrder);

        collectGpioPins(project, di, dOut, ai, ao);

        double colW = w / 4.0;
        String[] headers = {"DI", "DO", "AI", "AO"};
        double rowH = 16.0;
        g2.setStroke(new BasicStroke(0.6f));
        for (int c = 0; c < 4; c++) {
            double cx = x + c * colW;
            g2.setColor(Color.WHITE);
            g2.fill(new Rectangle2D.Double(cx, y, colW, rowH));
            g2.setColor(CAD_INK);
            g2.draw(new Rectangle2D.Double(cx, y, colW, rowH));
            g2.setFont(new Font("SansSerif", Font.BOLD, 8));
            g2.drawString(headers[c], (float) (cx + 4), (float) (y + 11));
        }
        y += rowH;

        boolean anyUsed = !di.isEmpty() || !dOut.isEmpty() || !ai.isEmpty() || !ao.isEmpty();
        if (!anyUsed) {
            for (int c = 0; c < 4; c++) {
                double cx = x + c * colW;
                g2.setColor(Color.WHITE);
                g2.fill(new Rectangle2D.Double(cx, y, colW, rowH));
                g2.setColor(CAD_INK);
                g2.draw(new Rectangle2D.Double(cx, y, colW, rowH));
                g2.setFont(new Font("SansSerif", Font.PLAIN, 8));
                g2.drawString("-", (float) (cx + 4), (float) (y + 11));
            }
            return y + rowH;
        }

        List<String> cells = List.of(
                di.isEmpty() ? "-" : String.join(", ", di),
                dOut.isEmpty() ? "-" : String.join(", ", dOut),
                ai.isEmpty() ? "-" : String.join(", ", ai),
                ao.isEmpty() ? "-" : String.join(", ", ao)
        );

        g2.setFont(new Font("SansSerif", Font.PLAIN, 8));
        FontMetrics fm = g2.getFontMetrics();
        List<List<String>> wrappedCols = new ArrayList<>(4);
        int maxLines = 1;
        for (String cell : cells) {
            List<String> lines = wrapText(cell, fm, (int) (colW - 8));
            wrappedCols.add(lines);
            maxLines = Math.max(maxLines, lines.size());
        }

        double dataH = Math.max(rowH, maxLines * 11.0 + 6.0);
        for (int c = 0; c < 4; c++) {
            double cx = x + c * colW;
            g2.setColor(Color.WHITE);
            g2.fill(new Rectangle2D.Double(cx, y, colW, dataH));
            g2.setColor(CAD_INK);
            g2.draw(new Rectangle2D.Double(cx, y, colW, dataH));
            List<String> lines = wrappedCols.get(c);
            for (int li = 0; li < lines.size(); li++) {
                g2.drawString(lines.get(li), (float) (cx + 4), (float) (y + 11 + li * 11.0));
            }
        }
        return y + dataH;
    }

    private static List<String> wrapText(String text, FontMetrics fm, int maxWidth) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty() || fm.stringWidth(text) <= maxWidth) {
            out.add(text == null ? "" : text);
            return out;
        }
        String[] words = text.split(",\\s*");
        StringBuilder cur = new StringBuilder();
        for (String w : words) {
            String candidate = cur.isEmpty() ? w : cur + ", " + w;
            if (fm.stringWidth(candidate) <= maxWidth || cur.isEmpty()) {
                cur.setLength(0);
                cur.append(candidate);
            } else {
                out.add(cur.toString() + ",");
                cur.setLength(0);
                cur.append(w);
            }
        }
        if (!cur.isEmpty()) {
            out.add(cur.toString());
        }
        return out;
    }

    private static void collectGpioPins(Project project,
                                        java.util.Set<String> di,
                                        java.util.Set<String> dOut,
                                        java.util.Set<String> ai,
                                        java.util.Set<String> ao) {
        java.util.regex.Pattern gpioPat = java.util.regex.Pattern.compile("\\b(DI|DO|AI|AO)\\d+\\b", java.util.regex.Pattern.CASE_INSENSITIVE);
        for (Page p : project.getPages()) {
            if (p.getLanguage() == IecLanguage.FBD) {
                Map<String, BlockInstance> byId = new java.util.HashMap<>();
                for (BlockInstance b : p.getBlocks()) {
                    byId.put(b.getId(), b);
                }
                for (cz.maramat.model.Wire w : p.getWires()) {
                    BlockInstance src = byId.get(w.getSourceBlockId());
                    if (src != null && w.getSourcePinIndex() >= 0 && w.getSourcePinIndex() < src.getOutputs().size()) {
                        String defId = src.getDefinitionId();
                        if ("IO.DI".equals(defId) || "IO.AI".equals(defId)) {
                            addGpioToken(src.getOutputs().get(w.getSourcePinIndex()).getShortName(), di, dOut, ai, ao);
                        }
                    }
                    BlockInstance dst = byId.get(w.getTargetBlockId());
                    if (dst != null && w.getTargetPinIndex() >= 0 && w.getTargetPinIndex() < dst.getInputs().size()) {
                        String defId = dst.getDefinitionId();
                        if ("IO.DO".equals(defId) || "IO.AO".equals(defId)) {
                            addGpioToken(dst.getInputs().get(w.getTargetPinIndex()).getShortName(), di, dOut, ai, ao);
                        }
                    }
                }
            } else if (p.getLanguage() == IecLanguage.LD) {
                for (LadderRung rung : p.getRungs()) {
                    for (LadderElement el : rung.getMainElements()) {
                        scanGpioMatches(el.getOperand(), gpioPat, di, dOut, ai, ao);
                        scanGpioMatches(el.getSourceOperand(), gpioPat, di, dOut, ai, ao);
                    }
                    for (LadderBranch br : rung.getParallelBranches()) {
                        for (LadderElement el : br.getElements()) {
                            scanGpioMatches(el.getOperand(), gpioPat, di, dOut, ai, ao);
                            scanGpioMatches(el.getSourceOperand(), gpioPat, di, dOut, ai, ao);
                        }
                    }
                    for (LadderElement coil : rung.getCoils()) {
                        scanGpioMatches(coil.getOperand(), gpioPat, di, dOut, ai, ao);
                        scanGpioMatches(coil.getSourceOperand(), gpioPat, di, dOut, ai, ao);
                    }
                }
            } else if (p.getLanguage() == IecLanguage.ST) {
                String code = p.getStCode();
                if (code != null && !code.isBlank()) {
                    String stripped = code.replaceAll("(?s)\\(\\*.*?\\*\\)", " ").replaceAll("//.*", " ");
                    scanGpioMatches(stripped, gpioPat, di, dOut, ai, ao);
                }
            }
        }
    }

    private static void scanGpioMatches(String text, java.util.regex.Pattern pat,
                                        java.util.Set<String> di, java.util.Set<String> dOut,
                                        java.util.Set<String> ai, java.util.Set<String> ao) {
        if (text == null || text.isBlank()) return;
        java.util.regex.Matcher m = pat.matcher(text);
        while (m.find()) {
            addGpioToken(m.group(), di, dOut, ai, ao);
        }
    }

    private static void addGpioToken(String raw,
                                     java.util.Set<String> di, java.util.Set<String> dOut,
                                     java.util.Set<String> ai, java.util.Set<String> ao) {
        if (raw == null || raw.isBlank()) return;
        String pin = raw.trim().toUpperCase(Locale.ROOT);
        if (pin.startsWith("DI")) di.add(pin);
        else if (pin.startsWith("DO")) dOut.add(pin);
        else if (pin.startsWith("AI")) ai.add(pin);
        else if (pin.startsWith("AO")) ao.add(pin);
    }

    private static double drawInterruptSummaryTable(Graphics2D g2, double x, double y, double w, Project project) {
        List<String> sources = cz.maramat.sim.SimulationEngine.getListeningInterruptSources(project);
        double rowH = 16.0;
        g2.setStroke(new BasicStroke(0.6f));

        if (sources.isEmpty()) {
            g2.setColor(Color.WHITE);
            g2.fill(new Rectangle2D.Double(x, y, w, rowH));
            g2.setColor(CAD_INK);
            g2.draw(new Rectangle2D.Double(x, y, w, rowH));
            g2.setFont(new Font("SansSerif", Font.PLAIN, 8));
            g2.drawString("Interrupts Not used", (float) (x + 4), (float) (y + 11));
            return y + rowH;
        }

        String joined = String.join(", ", sources);
        g2.setFont(new Font("SansSerif", Font.PLAIN, 8));
        List<String> lines = wrapText(joined, g2.getFontMetrics(), (int) (w - 8));
        double dataH = Math.max(rowH, lines.size() * 11.0 + 6.0);
        g2.setColor(Color.WHITE);
        g2.fill(new Rectangle2D.Double(x, y, w, dataH));
        g2.setColor(CAD_INK);
        g2.draw(new Rectangle2D.Double(x, y, w, dataH));
        for (int i = 0; i < lines.size(); i++) {
            g2.drawString(lines.get(i), (float) (x + 4), (float) (y + 11 + i * 11.0));
        }
        return y + dataH;
    }

    private static void drawEStopSection(Graphics2D g2, double x, double y, double w, EStopConfig cfg) {
        boolean enabled = cfg.isEnabled();
        String[][] meta = {
                {"E-Stop Enabled", enabled ? "YES" : "NO", "Manual Reset", enabled ? (cfg.isManualResetRequired() ? "Required" : "Auto") : "-"},
                {"Reaction Mode", enabled ? cfg.getMode().getLabel() : "-", "E-Stop Page", enabled ? cfg.getDedicatedEStopPageName() : "-"}
        };
        y = drawKeyValueGrid(g2, x, y, w, meta);
        if (!enabled) {
            return;
        }
        y += 6;

        List<EStopConfig.InputRule> rules = cfg.getInputRules();
        if (rules.isEmpty()) {
            g2.setFont(new Font("SansSerif", Font.ITALIC, 8));
            g2.setColor(CAD_SUBTLE);
            g2.drawString("No E-Stop input rules configured.", (float) (x + 4), (float) (y + 11));
            return;
        }

        double[] colW = {w * 0.14, w * 0.16, w * 0.16, w * 0.54};
        String[] headers = {"Channel", "State", "Polarity", "Description"};
        double rowH = 16.0;
        double cx = x;
        for (int c = 0; c < headers.length; c++) {
            g2.setColor(Color.WHITE);
            g2.fill(new Rectangle2D.Double(cx, y, colW[c], rowH));
            g2.setColor(CAD_INK);
            g2.draw(new Rectangle2D.Double(cx, y, colW[c], rowH));
            g2.setFont(new Font("SansSerif", Font.BOLD, 8));
            g2.drawString(headers[c], (float) (cx + 4), (float) (y + 11));
            cx += colW[c];
        }
        y += rowH;

        for (EStopConfig.InputRule r : rules) {
            String[] vals = {
                    r.getInputChannel(),
                    r.isEnabled() ? "ENABLED" : "DISABLED",
                    r.isActiveLow() ? "Active Low (NC)" : "Active High (NO)",
                    r.getDescription()
            };
            cx = x;
            for (int c = 0; c < vals.length; c++) {
                g2.setColor(Color.WHITE);
                g2.fill(new Rectangle2D.Double(cx, y, colW[c], rowH));
                g2.setColor(CAD_GRID);
                g2.draw(new Rectangle2D.Double(cx, y, colW[c], rowH));
                g2.setColor(CAD_INK);
                g2.setFont(new Font("SansSerif", Font.PLAIN, 8));
                String clipped = clipText(vals[c], g2.getFontMetrics(), (int) (colW[c] - 8));
                g2.drawString(clipped, (float) (cx + 4), (float) (y + 11));
                cx += colW[c];
            }
            y += rowH;
        }
        g2.setColor(CAD_INK);
        g2.draw(new Rectangle2D.Double(x, y - (rules.size() + 1) * rowH, w, (rules.size() + 1) * rowH));
    }

    // =========================================================================
    // Embedded Vector Maramat Logo (from Maramat Loga_Banner long.svg)
    // =========================================================================

    private static void drawMaramatLogo(Graphics2D g2, double x, double y, double w, double h) {
        Graphics2D lg = (Graphics2D) g2.create();
        try {
            double sx = w / 500.0;
            double sy = h / 100.0;
            double s = Math.min(sx, sy);
            double ox = x + (w - 500.0 * s) / 2.0;
            double oy = y + (h - 100.0 * s) / 2.0;
            lg.translate(ox, oy);
            lg.scale(s, s);
            lg.setColor(LOGO_BLUE);

            // Corner L-brackets
            lg.fill(new Rectangle2D.Double(30.29, 44.85, 6.14, 46.95));
            lg.fill(new Rectangle2D.Double(36.42, 85.78, 41.53, 6.01));
            lg.fill(new Rectangle2D.Double(463.58, 8.20, 6.14, 46.95));
            lg.fill(new Rectangle2D.Double(422.05, 8.20, 41.53, 6.01));

            // M A R A M A T letter paths
            lg.fill(buildLogoM(43.04, 12.33));
            lg.fill(buildLogoA(131.11, 145.29));
            lg.fill(buildLogoR());
            lg.fill(buildLogoA(245.93, 260.11));
            lg.fill(buildLogoM(287.91, 12.33));
            lg.fill(buildLogoA(375.98, 390.16));
            lg.fill(buildLogoT());
        } finally {
            lg.dispose();
        }
    }

    private static Path2D buildLogoM(double startX, double leftLegW) {
        Path2D p = new Path2D.Double();
        double x = startX;
        p.moveTo(x, 20.83);
        p.lineTo(x + leftLegW, 20.83);
        p.lineTo(x + leftLegW + 17.33, 45.49);
        p.lineTo(x + leftLegW + 34.65, 20.83);
        p.lineTo(x + leftLegW + 46.82, 20.83);
        p.lineTo(x + leftLegW + 46.82, 79.17);
        p.lineTo(x + leftLegW + 34.01, 79.17);
        p.lineTo(x + leftLegW + 34.01, 41.22);
        p.lineTo(x + leftLegW + 17.33, 65.72);
        p.lineTo(x + leftLegW, 41.22);
        p.lineTo(x + leftLegW, 79.17);
        p.lineTo(x, 79.17);
        p.closePath();
        return p;
    }

    private static Path2D buildLogoA(double apexX, double innerRightX) {
        Path2D p = new Path2D.Double(Path2D.WIND_EVEN_ODD);
        p.moveTo(apexX, 20.83);
        p.lineTo(apexX + 13.05, 20.83);
        p.lineTo(apexX + 36.42, 79.17);
        p.lineTo(apexX + 22.72, 79.17);
        p.lineTo(apexX + 17.97, 66.92);
        p.lineTo(apexX - 5.48, 66.92);
        p.lineTo(apexX - 10.23, 79.17);
        p.lineTo(apexX - 23.36, 79.17);
        p.closePath();

        // Inner triangle cutout
        p.moveTo(innerRightX, 56.85);
        p.lineTo(apexX + 6.45, 35.57);
        p.lineTo(innerRightX - 15.79, 56.85);
        p.closePath();
        return p;
    }

    private static Path2D buildLogoR() {
        Path2D p = new Path2D.Double(Path2D.WIND_EVEN_ODD);
        p.moveTo(173.09, 20.83);
        p.lineTo(194.52, 20.83);
        p.curveTo(200.81, 20.83, 205.72, 21.88, 209.75, 24.78);
        p.curveTo(213.78, 27.68, 216.52, 32.44, 216.52, 38.80);
        p.curveTo(216.52, 46.54, 212.33, 52.42, 205.24, 55.16);
        p.lineTo(221.11, 79.17);
        p.lineTo(206.44, 79.17);
        p.lineTo(192.10, 57.01);
        p.lineTo(185.90, 57.01);
        p.lineTo(185.90, 79.17);
        p.lineTo(173.09, 79.17);
        p.closePath();

        p.moveTo(193.80, 46.86);
        p.curveTo(197.02, 46.86, 199.20, 46.46, 200.97, 45.25);
        p.curveTo(202.74, 44.04, 203.71, 41.79, 203.71, 39.37);
        p.curveTo(203.71, 36.79, 202.66, 34.62, 200.97, 33.41);
        p.curveTo(199.28, 32.20, 197.02, 31.80, 193.80, 31.80);
        p.lineTo(185.90, 31.80);
        p.lineTo(185.90, 46.86);
        p.closePath();
        return p;
    }

    private static Path2D buildLogoT() {
        Path2D p = new Path2D.Double();
        p.moveTo(425.29, 32.03);
        p.lineTo(406.43, 32.03);
        p.lineTo(406.43, 20.83);
        p.lineTo(456.95, 20.83);
        p.lineTo(456.95, 32.03);
        p.lineTo(438.09, 32.03);
        p.lineTo(438.09, 79.17);
        p.lineTo(425.29, 79.17);
        p.closePath();
        return p;
    }

    private static String sanitizeFilename(String name) {
        if (name == null || name.isBlank()) return "Sheet";
        return name.replaceAll("[^a-zA-Z0-9._-]+", "_");
    }
}
