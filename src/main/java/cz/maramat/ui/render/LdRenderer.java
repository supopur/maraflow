package cz.maramat.ui.render;

import cz.maramat.model.BlockInstance;
import cz.maramat.model.DataType;
import cz.maramat.model.LadderBranch;
import cz.maramat.model.LadderElement;
import cz.maramat.model.LadderRung;
import cz.maramat.model.Page;
import cz.maramat.model.PinInstance;
import cz.maramat.ui.AppFonts;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.util.List;
import java.util.Locale;

/**
 * Stateless renderer for IEC 61131-3 Ladder Diagram (LD) {@link Page} instances.
 * Shared between {@link cz.maramat.ui.canvas.LadderCanvas}, SVG/PDF/PNG export, and the Help diagram engine.
 */
public final class LdRenderer {

    public static final Color COLOR_RAIL = new Color(0x1E, 0x29, 0x3B);
    public static final Color COLOR_WIRE_OFF = new Color(0x33, 0x33, 0x33);
    public static final Color COLOR_POWER_ON = new Color(0x1E, 0x84, 0x49);
    public static final Color COLOR_RUNG_HEADER_BG = new Color(0xEE, 0xF2, 0xF7);
    public static final Color COLOR_RUNG_SELECTED_BG = new Color(0xE1, 0xEC, 0xFA);
    public static final Color COLOR_ELEMENT_SELECTED = new Color(0x00, 0x66, 0xCC);

    private LdRenderer() {}

    public record RenderOptions(
            boolean liveSim,
            boolean showExecutionInfo,
            boolean exporting,
            int selectedRungIndex,
            LadderElement selectedElement
    ) {
        public static RenderOptions forExport(boolean liveSim, boolean showExecutionInfo) {
            return new RenderOptions(liveSim, showExecutionInfo, true, -1, null);
        }

        public static RenderOptions forDiagram(boolean liveSim, boolean showExecutionInfo) {
            return new RenderOptions(liveSim, showExecutionInfo, true, -1, null);
        }
    }

    public interface HitCollector {
        void onRung(int rungIndex, Rectangle bounds, Rectangle headerBounds);
        void onElement(int rungIndex, LadderElement element, LadderBranch parentBranch, int columnIndex, Rectangle bounds);
        void onPinOperand(int rungIndex, BlockInstance block, int pinIndex, boolean isInput, Rectangle bounds);
    }

    public static int computeExportRightRailX(Page page) {
        int maxContentX = 460;
        int leftRailX = 48;
        int startColX = leftRailX + 28;
        for (LadderRung rung : page.getRungs()) {
            int curX = startColX;
            for (LadderElement el : rung.getMainElements()) {
                int elW = (el.getKind() == LadderElement.Kind.FUNCTION_BLOCK) ? 186 : 88;
                curX += 16 + elW + 16;
            }
            for (LadderBranch br : rung.getParallelBranches()) {
                int brX = startColX + 16;
                for (LadderElement el : br.getElements()) {
                    int elW = (el.getKind() == LadderElement.Kind.FUNCTION_BLOCK) ? 186 : 88;
                    brX += 16 + elW + 16;
                }
                curX = Math.max(curX, brX + 10);
            }
            maxContentX = Math.max(maxContentX, curX + 190);
        }
        return Math.max(640, maxContentX);
    }

    public static Rectangle computeExportBounds(Page page) {
        if (page == null || page.getRungs().isEmpty()) {
            return new Rectangle(0, 0, 680, 180);
        }
        int rightRailX = computeExportRightRailX(page);
        int curY = 20;
        for (LadderRung rung : page.getRungs()) {
            curY += computeRungHeight(rung) + 12;
        }
        int railBottom = curY + 14;
        return new Rectangle(8, 0, rightRailX + 36, railBottom + 14);
    }

    public static int computeRungHeight(LadderRung rung) {
        int baseH = 98;
        for (LadderElement el : rung.getMainElements()) {
            if (el.getKind() == LadderElement.Kind.FUNCTION_BLOCK && el.getBlockInstance() != null) {
                int pinRows = Math.max(el.getBlockInstance().getInputs().size(), el.getBlockInstance().getOutputs().size());
                int fbH = 54 + pinRows * 18;
                baseH = Math.max(baseH, fbH + 38);
            }
        }
        int branchExtra = rung.getParallelBranches().size() * 58;
        int coilExtra = Math.max(0, rung.getCoils().size() - 1) * 48;
        return Math.max(baseH + branchExtra, 70 + coilExtra);
    }

    public static void paintPageForExport(Graphics2D g2, Page page, RenderOptions opts) {
        if (page == null) return;
        int leftRailX = 48;
        int rightRailX = computeExportRightRailX(page);
        paintPage(g2, page, leftRailX, rightRailX, 120, opts, null);
    }

    public static void paintPage(
            Graphics2D g2,
            Page page,
            int leftRailX,
            int rightRailX,
            int minRailBottom,
            RenderOptions opts,
            HitCollector hits
    ) {
        if (page == null) return;
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        int curY = 20;
        boolean liveSim = opts.liveSim();

        for (int ri = 0; ri < page.getRungs().size(); ri++) {
            LadderRung rung = page.getRungs().get(ri);
            int rungHeight = computeRungHeight(rung);
            if (hits != null) {
                Rectangle rungBounds = new Rectangle(12, curY, rightRailX - 12 + 24, rungHeight);
                Rectangle headerBounds = new Rectangle(leftRailX, curY, rightRailX - leftRailX, 22);
                hits.onRung(ri, rungBounds, headerBounds);
            }
            drawRung(g2, ri, rung, leftRailX, rightRailX, curY, rungHeight, liveSim, opts, hits);
            curY += rungHeight + 12;
        }

        int railBottom = Math.max(curY + (opts.exporting() ? 14 : 20), minRailBottom);
        Stroke oldStroke = g2.getStroke();
        g2.setStroke(new BasicStroke(3f));
        g2.setColor(liveSim ? COLOR_POWER_ON : COLOR_RAIL);
        g2.drawLine(leftRailX, 14, leftRailX, railBottom);
        g2.setColor(COLOR_RAIL);
        g2.drawLine(rightRailX, 14, rightRailX, railBottom);
        g2.setStroke(oldStroke);

        g2.setFont(AppFonts.ui(Font.BOLD, 11));
        g2.setColor(liveSim ? COLOR_POWER_ON : COLOR_RAIL);
        g2.drawString("L+ (24VDC)", leftRailX - 24, 12);
        g2.setColor(COLOR_RAIL);
        g2.drawString("L- (0V)", rightRailX - 18, 12);
    }

    private static void drawRung(
            Graphics2D g2,
            int ri,
            LadderRung rung,
            int leftRailX,
            int rightRailX,
            int topY,
            int rungHeight,
            boolean liveSim,
            RenderOptions opts,
            HitCollector hits
    ) {
        boolean isRungSelected = !opts.exporting() && (ri == opts.selectedRungIndex());

        if (isRungSelected) {
            g2.setColor(new Color(0xF6, 0xFA, 0xFF));
            g2.fillRect(leftRailX + 1, topY, rightRailX - leftRailX - 1, rungHeight);
        }

        g2.setColor(isRungSelected ? COLOR_RUNG_SELECTED_BG : COLOR_RUNG_HEADER_BG);
        g2.fillRect(leftRailX + 1, topY, rightRailX - leftRailX - 1, 22);
        g2.setColor(isRungSelected ? COLOR_ELEMENT_SELECTED : new Color(0xC5, 0xCE, 0xD8));
        g2.drawRect(leftRailX, topY, rightRailX - leftRailX, 22);

        g2.setFont(AppFonts.ui(Font.BOLD, 11));
        g2.setColor(isRungSelected ? COLOR_ELEMENT_SELECTED : new Color(0x44, 0x55, 0x66));
        String numStr = String.format(Locale.US, "%03d", ri + 1);
        g2.drawString(numStr, 14, topY + 15);

        StringBuilder hdr = new StringBuilder("Rung ").append(ri + 1);
        if (opts.showExecutionInfo()) {
            hdr.append("   [T:").append(rung.getThreadId()).append(" | O:").append(rung.getOrder()).append("]");
        }
        if (!rung.getComment().isBlank()) {
            hdr.append("   // ").append(rung.getComment());
        }
        g2.setFont(AppFonts.ui(Font.BOLD, 11));
        g2.setColor(new Color(0x1E, 0x29, 0x3B));
        g2.drawString(hdr.toString(), leftRailX + 8, topY + 15);

        int wireY = topY + 56;
        int startColX = leftRailX + 28;

        drawPowerLine(g2, leftRailX, wireY, startColX, wireY, liveSim);

        List<LadderElement> mainEls = rung.getMainElements();
        int[] colStartX = new int[mainEls.size() + 1];
        int[] colEndX = new int[mainEls.size() + 1];
        int curX = startColX;

        for (int col = 0; col < mainEls.size(); col++) {
            LadderElement el = mainEls.get(col);
            colStartX[col] = curX;
            int elWidth = (el.getKind() == LadderElement.Kind.FUNCTION_BLOCK) ? 186 : 88;

            boolean wireInOn = liveSim && el.isEnergizedIn();
            drawPowerLine(g2, curX, wireY, curX + 16, wireY, wireInOn);

            Rectangle elRect = drawSeriesElement(g2, ri, el, curX + 16, wireY, elWidth, liveSim, opts, hits);
            if (hits != null) {
                hits.onElement(ri, el, null, col, elRect);
            }

            boolean wireOutOn = liveSim && el.isEnergizedOut();
            int nextX = curX + 16 + elWidth + 16;
            drawPowerLine(g2, curX + 16 + elWidth, wireY, nextX, wireY, wireOutOn);

            colEndX[col] = nextX;
            curX = nextX;
        }
        colStartX[mainEls.size()] = curX;
        colEndX[mainEls.size()] = curX;

        int branchY = wireY + 56;
        for (LadderBranch br : rung.getParallelBranches()) {
            int sCol = Math.clamp(br.getStartColumn(), 0, Math.max(0, mainEls.size()));
            int eCol = Math.clamp(br.getEndColumn(), sCol, Math.max(0, mainEls.size() - 1));

            int dropX = (mainEls.isEmpty()) ? startColX : colStartX[sCol] + 6;
            int rejoinX = (mainEls.isEmpty()) ? (startColX + 120) : (colEndX[eCol] - 6);

            boolean dropOn = liveSim && br.isBranchEnergizedIn();
            boolean rejoinOn = liveSim && br.isBranchEnergizedOut();

            drawPowerLine(g2, dropX, wireY, dropX, branchY, dropOn);

            int brCurX = dropX + 14;
            drawPowerLine(g2, dropX, branchY, brCurX, branchY, dropOn);

            for (int bi = 0; bi < br.getElements().size(); bi++) {
                LadderElement bel = br.getElements().get(bi);
                int belW = 84;
                Rectangle belRect = drawSeriesElement(g2, ri, bel, brCurX, branchY, belW, liveSim, opts, hits);
                if (hits != null) {
                    hits.onElement(ri, bel, br, bi, belRect);
                }
                boolean belOut = liveSim && bel.isEnergizedOut();
                drawPowerLine(g2, brCurX + belW, branchY, brCurX + belW + 14, branchY, belOut);
                brCurX += belW + 14;
            }

            rejoinX = Math.max(rejoinX, brCurX + 6);
            drawPowerLine(g2, brCurX, branchY, rejoinX, branchY, rejoinOn);
            drawPowerLine(g2, rejoinX, branchY, rejoinX, wireY, rejoinOn);

            curX = Math.max(curX, rejoinX + 10);
            branchY += 56;
        }

        int coilStartX = rightRailX - 156;
        boolean finalRungPower = liveSim && rung.isRungOutputEnergized();
        if (curX < coilStartX) {
            drawPowerLine(g2, curX, wireY, coilStartX, wireY, finalRungPower);
        }

        List<LadderElement> coils = rung.getCoils();
        if (coils.isEmpty()) {
            drawPowerLine(g2, coilStartX, wireY, rightRailX, wireY, finalRungPower);
        } else {
            for (int ci = 0; ci < coils.size(); ci++) {
                LadderElement coil = coils.get(ci);
                int cY = wireY + ci * 46;
                if (ci > 0) {
                    drawPowerLine(g2, coilStartX, wireY, coilStartX, cY, finalRungPower);
                }
                int cWidth = (coil.getKind() == LadderElement.Kind.ANALOG_COIL) ? 124 : 84;
                drawPowerLine(g2, coilStartX, cY, coilStartX + 12, cY, finalRungPower);
                Rectangle cRect = drawCoilElement(g2, coil, coilStartX + 12, cY, cWidth, liveSim, opts);
                if (hits != null) {
                    hits.onElement(ri, coil, null, ci, cRect);
                }
                drawPowerLine(g2, coilStartX + 12 + cWidth, cY, rightRailX, cY, liveSim && coil.isEnergizedOut());
            }
        }
    }

    private static Rectangle drawSeriesElement(
            Graphics2D g2,
            int rungIndex,
            LadderElement el,
            int x,
            int wireY,
            int width,
            boolean liveSim,
            RenderOptions opts,
            HitCollector hits
    ) {
        if (el.getKind() == LadderElement.Kind.FUNCTION_BLOCK && el.getBlockInstance() != null) {
            return drawFunctionBlockOnRung(g2, rungIndex, el, x, wireY, width, liveSim, opts, hits);
        }

        int h = 42;
        int topY = wireY - h / 2;
        Rectangle bounds = new Rectangle(x, topY, width, h);

        boolean selected = !opts.exporting() && (el == opts.selectedElement());
        boolean energized = liveSim && el.isEnergizedOut();
        boolean opHigh = liveSim && el.isOperandState();

        if (selected) {
            g2.setColor(new Color(0xE8, 0xF2, 0xFF));
            g2.fillRect(x, topY, width, h);
            g2.setColor(COLOR_ELEMENT_SELECTED);
            g2.drawRect(x, topY, width, h);
        }

        int midX = x + width / 2;
        int barLeftX = midX - 9;
        int barRightX = midX + 9;

        drawPowerLine(g2, x, wireY, barLeftX, wireY, liveSim && el.isEnergizedIn());
        drawPowerLine(g2, barRightX, wireY, x + width, wireY, energized);

        Stroke oldStroke = g2.getStroke();
        g2.setStroke(new BasicStroke(2f));
        g2.setColor(energized ? COLOR_POWER_ON : (opHigh ? new Color(0x1F, 0x61, 0x8D) : Color.BLACK));
        g2.drawLine(barLeftX, wireY - 10, barLeftX, wireY + 10);
        g2.drawLine(barRightX, wireY - 10, barRightX, wireY + 10);

        switch (el.getKind()) {
            case NC_CONTACT -> g2.drawLine(barLeftX - 2, wireY + 9, barRightX + 2, wireY - 9);
            case POS_EDGE_CONTACT -> {
                g2.setFont(AppFonts.ui(Font.BOLD, 11));
                g2.drawString("P", midX - 4, wireY + 4);
            }
            case NEG_EDGE_CONTACT -> {
                g2.setFont(AppFonts.ui(Font.BOLD, 11));
                g2.drawString("N", midX - 4, wireY + 4);
            }
            default -> {}
        }
        g2.setStroke(oldStroke);

        g2.setFont(AppFonts.ui(Font.BOLD, 11));
        FontMetrics fm = g2.getFontMetrics();
        String opLabel = el.getOperand();
        if (liveSim) {
            opLabel += opHigh ? " (=1)" : " (=0)";
        }
        int tw = fm.stringWidth(opLabel);
        g2.setColor(opHigh ? COLOR_POWER_ON : new Color(0x11, 0x11, 0x11));
        g2.drawString(opLabel, midX - tw / 2, wireY - 13);

        if (!el.getComment().isBlank()) {
            g2.setFont(AppFonts.ui(Font.PLAIN, 9));
            FontMetrics cfm = g2.getFontMetrics();
            int cw = cfm.stringWidth(el.getComment());
            g2.setColor(new Color(0x55, 0x66, 0x77));
            g2.drawString(el.getComment(), midX - cw / 2, wireY + 21);
        }

        return bounds;
    }

    private static Rectangle drawCoilElement(
            Graphics2D g2,
            LadderElement coil,
            int x,
            int wireY,
            int width,
            boolean liveSim,
            RenderOptions opts
    ) {
        int h = 40;
        int topY = wireY - h / 2;
        Rectangle bounds = new Rectangle(x, topY, width, h);

        boolean selected = !opts.exporting() && (coil == opts.selectedElement());
        boolean energized = liveSim && coil.isEnergizedOut();
        boolean opHigh = liveSim && coil.isOperandState();

        if (coil.getKind() == LadderElement.Kind.ANALOG_COIL) {
            return drawAnalogCoilElement(g2, coil, x, wireY, width, liveSim, selected, energized);
        }

        if (selected) {
            g2.setColor(new Color(0xE8, 0xF2, 0xFF));
            g2.fillRect(x, topY, width, h);
            g2.setColor(COLOR_ELEMENT_SELECTED);
            g2.drawRect(x, topY, width, h);
        }

        int midX = x + width / 2;
        int coilLeftX = midX - 11;
        int coilRightX = midX + 11;

        drawPowerLine(g2, x, wireY, coilLeftX, wireY, liveSim && coil.isEnergizedIn());
        drawPowerLine(g2, coilRightX, wireY, x + width, wireY, energized);

        Stroke oldStroke = g2.getStroke();
        g2.setStroke(new BasicStroke(2f));
        g2.setColor((energized || opHigh) ? COLOR_POWER_ON : Color.BLACK);

        g2.drawLine(coilLeftX + 4, wireY - 10, coilLeftX, wireY - 5);
        g2.drawLine(coilLeftX, wireY - 5, coilLeftX, wireY + 5);
        g2.drawLine(coilLeftX, wireY + 5, coilLeftX + 4, wireY + 10);

        g2.drawLine(coilRightX - 4, wireY - 10, coilRightX, wireY - 5);
        g2.drawLine(coilRightX, wireY - 5, coilRightX, wireY + 5);
        g2.drawLine(coilRightX, wireY + 5, coilRightX - 4, wireY + 10);

        String innerSym = switch (coil.getKind()) {
            case NEGATED_COIL -> "/";
            case SET_COIL -> "S";
            case RESET_COIL -> "R";
            case POS_EDGE_COIL -> "P";
            case NEG_EDGE_COIL -> "N";
            default -> "";
        };
        if (!innerSym.isEmpty()) {
            g2.setFont(AppFonts.ui(Font.BOLD, 11));
            FontMetrics sfm = g2.getFontMetrics();
            g2.drawString(innerSym, midX - sfm.stringWidth(innerSym) / 2, wireY + 4);
        }
        g2.setStroke(oldStroke);

        g2.setFont(AppFonts.ui(Font.BOLD, 11));
        FontMetrics fm = g2.getFontMetrics();
        String opLabel = coil.getOperand();
        if (liveSim) {
            opLabel += opHigh ? " (=1)" : " (=0)";
        }
        int tw = fm.stringWidth(opLabel);
        g2.setColor(opHigh ? COLOR_POWER_ON : new Color(0x11, 0x11, 0x11));
        g2.drawString(opLabel, midX - tw / 2, wireY - 13);

        if (!coil.getComment().isBlank()) {
            g2.setFont(AppFonts.ui(Font.PLAIN, 9));
            FontMetrics cfm = g2.getFontMetrics();
            int cw = cfm.stringWidth(coil.getComment());
            g2.setColor(new Color(0x55, 0x66, 0x77));
            g2.drawString(coil.getComment(), midX - cw / 2, wireY + 21);
        }

        return bounds;
    }

    private static Rectangle drawAnalogCoilElement(
            Graphics2D g2,
            LadderElement coil,
            int x,
            int wireY,
            int width,
            boolean liveSim,
            boolean selected,
            boolean energized
    ) {
        int boxW = width - 16;
        int boxH = 30;
        int boxX = x + 8;
        int boxY = wireY - boxH / 2;
        Rectangle bounds = new Rectangle(x, boxY - 6, width, boxH + 12);

        drawPowerLine(g2, x, wireY, boxX, wireY, liveSim && coil.isEnergizedIn());
        drawPowerLine(g2, boxX + boxW, wireY, x + width, wireY, energized);

        g2.setColor(energized ? new Color(0xE8, 0xF8, 0xF0) : new Color(0xF0, 0xF4, 0xF8));
        g2.fillRect(boxX, boxY, boxW, boxH);

        Stroke old = g2.getStroke();
        g2.setStroke(new BasicStroke(selected ? 2f : 1.5f));
        g2.setColor(selected ? COLOR_ELEMENT_SELECTED : (energized ? COLOR_POWER_ON : new Color(0x1F, 0x61, 0x8D)));
        g2.drawRect(boxX, boxY, boxW, boxH);
        g2.setStroke(old);

        g2.setColor(energized ? COLOR_POWER_ON : new Color(0x1F, 0x61, 0x8D));
        g2.fillRect(boxX, boxY, boxW, 11);
        g2.setColor(Color.WHITE);
        g2.setFont(AppFonts.ui(Font.BOLD, 8));
        String targetOp = coil.getOperand() == null ? "" : coil.getOperand().toUpperCase();
        String hdr = (targetOp.startsWith("AO") || targetOp.startsWith("PWM")) ? "ANALOG OUT [:=]" : "WRITE REG [:=]";
        FontMetrics hfm = g2.getFontMetrics();
        g2.drawString(hdr, boxX + (boxW - hfm.stringWidth(hdr)) / 2, boxY + 9);

        g2.setFont(AppFonts.ui(Font.BOLD, 10));
        FontMetrics fm = g2.getFontMetrics();
        String expr = coil.getSourceOperand() + " -> " + coil.getOperand();
        g2.setColor(Color.BLACK);
        g2.drawString(expr, boxX + (boxW - fm.stringWidth(expr)) / 2, boxY + 24);

        if (liveSim) {
            String valLabel = coil.getOperand() + " = " + DataType.FLOAT.formatRuntimeValue(coil.getNumericRuntimeValue(), true);
            g2.setColor(COLOR_POWER_ON);
            g2.drawString(valLabel, boxX + (boxW - fm.stringWidth(valLabel)) / 2, boxY - 3);
        }

        return bounds;
    }

    private static Rectangle drawFunctionBlockOnRung(
            Graphics2D g2,
            int rungIndex,
            LadderElement el,
            int x,
            int wireY,
            int width,
            boolean liveSim,
            RenderOptions opts,
            HitCollector hits
    ) {
        BlockInstance b = el.getBlockInstance();
        List<PinInstance> ins = b.getInputs();
        List<PinInstance> outs = b.getOutputs();
        int rows = Math.max(1, Math.max(ins.size(), outs.size()));
        int headerH = 26;
        int rowH = 18;
        int bodyH = rows * rowH;
        int totalH = headerH + bodyH;

        int topY = wireY - headerH - (rowH / 2);
        int blockX = x + 44;
        int blockW = width - 88;

        Rectangle bounds = new Rectangle(blockX, topY, blockW, totalH);
        boolean selected = !opts.exporting() && (el == opts.selectedElement());

        drawPowerLine(g2, x, wireY, blockX, wireY, liveSim && el.isEnergizedIn());
        drawPowerLine(g2, blockX + blockW, wireY, x + width, wireY, liveSim && el.isEnergizedOut());

        g2.setColor(b.getHeaderColor().getColor());
        g2.fillRect(blockX, topY, blockW, headerH);
        g2.setColor(Color.BLACK);
        g2.drawRect(blockX, topY, blockW, headerH);

        g2.setColor(Color.WHITE);
        g2.setFont(AppFonts.ui(Font.BOLD, 10));
        FontMetrics hfm = g2.getFontMetrics();
        g2.drawString(b.getTypeName(), blockX + (blockW - hfm.stringWidth(b.getTypeName())) / 2, topY + 11);
        g2.drawString(b.getName(), blockX + (blockW - hfm.stringWidth(b.getName())) / 2, topY + 22);

        int bodyY = topY + headerH;
        g2.setColor(new Color(0xC3, 0xC7, 0xCB));
        g2.fillRect(blockX, bodyY, blockW, bodyH);
        g2.setColor(Color.BLACK);
        g2.drawRect(blockX, bodyY, blockW, bodyH);

        int pinColW = 26;
        for (int i = 0; i < ins.size(); i++) {
            PinInstance pin = ins.get(i);
            int py = bodyY + i * rowH;
            g2.setColor(pin.getDataType().getPinBackground());
            g2.fillRect(blockX, py, pinColW, rowH);
            g2.setColor(Color.BLACK);
            g2.drawRect(blockX, py, pinColW, rowH);
            g2.setFont(AppFonts.ui(Font.PLAIN, 9));
            g2.drawString(pin.getShortName(), blockX + 3, py + 13);

            if (!(i == 0 && pin.getShortName().equalsIgnoreCase("En"))) {
                String opStr = pin.getConstantValue();
                if (liveSim) {
                    opStr = opStr + "=" + pin.getDataType().formatRuntimeValue(pin.getRuntimeValue(), true);
                }
                Rectangle pillRect = new Rectangle(x - 2, py + 1, blockX - x + 2, rowH - 2);
                if (hits != null) {
                    hits.onPinOperand(rungIndex, b, i, true, pillRect);
                }

                g2.setColor(new Color(0xFF, 0xFE, 0xE8));
                g2.fillRect(pillRect.x, pillRect.y, pillRect.width, pillRect.height);
                g2.setColor(new Color(0x66, 0x66, 0x66));
                g2.drawRect(pillRect.x, pillRect.y, pillRect.width, pillRect.height);
                g2.setColor(Color.BLACK);
                g2.drawString(opStr, pillRect.x + 2, py + 12);
            }
        }

        for (int i = 0; i < outs.size(); i++) {
            PinInstance pin = outs.get(i);
            int py = bodyY + i * rowH;
            int px = blockX + blockW - pinColW;
            g2.setColor(pin.getDataType().getPinBackground());
            g2.fillRect(px, py, pinColW, rowH);
            g2.setColor(Color.BLACK);
            g2.drawRect(px, py, pinColW, rowH);
            g2.setFont(AppFonts.ui(Font.PLAIN, 9));
            g2.drawString(pin.getShortName(), px + 3, py + 13);

            String boundOut = pin.getConstantValue();
            boolean hasBoundTarget = boundOut != null && !boundOut.isBlank() && !boundOut.equals("0");

            Rectangle outPillRect = new Rectangle(blockX + blockW, py + 1, 44, rowH - 2);
            if (hits != null) {
                hits.onPinOperand(rungIndex, b, i, false, new Rectangle(px, py, pinColW + 44, rowH));
            }

            if (hasBoundTarget || i > 0 || pin.getDataType() != DataType.BOOL) {
                g2.setColor(hasBoundTarget ? new Color(0xE8, 0xF4, 0xFD) : new Color(0xF8, 0xF9, 0xFA));
                g2.fillRect(outPillRect.x, outPillRect.y, outPillRect.width, outPillRect.height);
                g2.setColor(new Color(0x77, 0x88, 0x99));
                g2.drawRect(outPillRect.x, outPillRect.y, outPillRect.width, outPillRect.height);

                String disp = hasBoundTarget ? boundOut : "->[..]";
                if (liveSim) {
                    disp = (hasBoundTarget ? (boundOut + "=") : "=")
                            + pin.getDataType().formatRuntimeValue(pin.getRuntimeValue(), true);
                }
                g2.setColor(hasBoundTarget ? new Color(0x1F, 0x61, 0x8D) : new Color(0x55, 0x55, 0x55));
                g2.drawString(disp, outPillRect.x + 2, py + 12);
            }
        }

        if (opts.showExecutionInfo()) {
            int courtX = blockX + pinColW;
            int courtW = blockW - 2 * pinColW;
            g2.setFont(AppFonts.ui(Font.BOLD, 9));
            FontMetrics cfm = g2.getFontMetrics();
            g2.setColor(Color.BLACK);
            String tStr = "T:" + b.getThreadId();
            String oStr = "O:" + b.getOrder();
            g2.drawString(tStr, courtX + (courtW - cfm.stringWidth(tStr)) / 2, bodyY + bodyH - 12);
            g2.drawString(oStr, courtX + (courtW - cfm.stringWidth(oStr)) / 2, bodyY + bodyH - 2);
        }

        if (selected) {
            Stroke old = g2.getStroke();
            g2.setStroke(new BasicStroke(2f));
            g2.setColor(COLOR_ELEMENT_SELECTED);
            g2.drawRect(blockX - 2, topY - 2, blockW + 4, totalH + 4);
            g2.setStroke(old);
        }

        return bounds;
    }

    private static void drawPowerLine(Graphics2D g2, int x1, int y1, int x2, int y2, boolean energized) {
        Stroke old = g2.getStroke();
        if (energized) {
            g2.setStroke(new BasicStroke(2f));
            g2.setColor(COLOR_POWER_ON);
        } else {
            g2.setStroke(new BasicStroke(1f));
            g2.setColor(COLOR_WIRE_OFF);
        }
        g2.drawLine(x1, y1, x2, y2);
        g2.setStroke(old);
    }
}
