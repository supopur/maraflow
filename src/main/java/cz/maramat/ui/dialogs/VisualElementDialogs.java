package cz.maramat.ui.dialogs;

import cz.maramat.model.BlockInstance;
import cz.maramat.model.DataType;
import cz.maramat.model.GraphicElement;
import cz.maramat.model.Page;
import cz.maramat.model.PinInstance;
import cz.maramat.model.Project;
import cz.maramat.ui.icons.VectorIcons;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JColorChooser;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.List;

public final class VisualElementDialogs {

    private VisualElementDialogs() {}

    /**
     * Opens the Visual Button properties modal with a live search bar and list of free BOOL input pins and BoolVars.
     */
    public static boolean showVisualButtonDialog(Frame owner, Project project, Page page, GraphicElement buttonElem) {
        JDialog dlg = new JDialog(owner, "Visual Button Properties", true);
        final boolean[] confirmed = {false};

        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        JPanel topForm = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(3, 4, 3, 4);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        JTextField labelField = new JTextField(buttonElem.getText(), 16);
        JComboBox<String> modeCombo = new JComboBox<>(new String[]{"Momentary (Push)", "Toggle (Switch)"});
        modeCombo.setSelectedIndex(buttonElem.isToggleButton() ? 1 : 0);

        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0;
        topForm.add(new JLabel("Button Label:"), gbc);
        gbc.gridx = 1; gbc.weightx = 1;
        topForm.add(labelField, gbc);

        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0;
        topForm.add(new JLabel("Action Mode:"), gbc);
        gbc.gridx = 1; gbc.weightx = 1;
        topForm.add(modeCombo, gbc);

        root.add(topForm, BorderLayout.NORTH);

        // Pin & BoolVar selector with 1-driver mutex
        record TargetItem(String ref, String display, boolean available) {
            @Override public String toString() { return display; }
        }
        List<TargetItem> allTargets = new ArrayList<>();
        if (project != null) {
            for (Page p : project.getPages()) {
                for (BlockInstance b : p.getBlocks()) {
                    if ("Visual.Vars.BoolVar".equals(b.getDefinitionId())) {
                        String ref = b.getName() + ".Y";
                        allTargets.add(new TargetItem(ref, ref + " [BoolVar - Shared with SCADA]", true));
                    }
                }
            }
        }
        for (BlockInstance b : page.getBlocks()) {
            for (int ii = 0; ii < b.getInputs().size(); ii++) {
                PinInstance pin = b.getInputs().get(ii);
                if (pin.getDataType() != DataType.BOOL) continue;
                String ref = b.getName() + "." + pin.getShortName();
                boolean available = true;
                String suffix = "";
                if (project != null) {
                    Project.PinDriverInfo drv = project.getInputPinDriver(page, b, ii);
                    boolean ownedBySelf = drv.kind() == Project.PinDriverKind.VISUAL_BUTTON
                            && ref.equalsIgnoreCase(buttonElem.getBoundTargetPin());
                    if (drv.isDriven() && !ownedBySelf) {
                        available = false;
                        suffix = " [Driven by " + drv.label() + "]";
                    }
                }
                allTargets.add(new TargetItem(ref, ref + suffix, available));
            }
        }

        JPanel pinPanel = new JPanel(new BorderLayout(4, 4));
        pinPanel.setBorder(BorderFactory.createTitledBorder("Target BOOL Input Pin or BoolVar (1-Driver Mutex)"));

        JPanel searchRow = new JPanel(new BorderLayout(4, 0));
        searchRow.add(new JLabel("Search:"), BorderLayout.WEST);
        JTextField searchField = new JTextField();
        searchRow.add(searchField, BorderLayout.CENTER);
        pinPanel.add(searchRow, BorderLayout.NORTH);

        DefaultListModel<TargetItem> listModel = new DefaultListModel<>();
        for (TargetItem s : allTargets) listModel.addElement(s);
        JList<TargetItem> pinList = new JList<>(listModel);
        pinList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        pinList.setCellRenderer((list, value, index, isSelected, cellHasFocus) -> {
            JLabel lbl = new JLabel(value.display());
            lbl.setOpaque(true);
            if (!value.available()) {
                lbl.setForeground(Color.GRAY);
                lbl.setBackground(Color.WHITE);
            } else if (isSelected) {
                lbl.setBackground(list.getSelectionBackground());
                lbl.setForeground(list.getSelectionForeground());
            } else {
                lbl.setBackground(list.getBackground());
                lbl.setForeground(list.getForeground());
            }
            return lbl;
        });
        if (!buttonElem.getBoundTargetPin().isEmpty()) {
            for (TargetItem item : allTargets) {
                if (item.ref().equalsIgnoreCase(buttonElem.getBoundTargetPin())) {
                    pinList.setSelectedValue(item, true);
                    break;
                }
            }
        }

        searchField.getDocument().addDocumentListener(new DocumentListener() {
            private void filter() {
                String q = searchField.getText().trim().toLowerCase();
                listModel.clear();
                for (TargetItem item : allTargets) {
                    if (q.isEmpty() || item.display().toLowerCase().contains(q)) {
                        listModel.addElement(item);
                    }
                }
            }
            @Override public void insertUpdate(DocumentEvent e) { filter(); }
            @Override public void removeUpdate(DocumentEvent e) { filter(); }
            @Override public void changedUpdate(DocumentEvent e) { filter(); }
        });

        JScrollPane listScroll = new JScrollPane(pinList);
        listScroll.setPreferredSize(new Dimension(360, 170));
        pinPanel.add(listScroll, BorderLayout.CENTER);
        root.add(pinPanel, BorderLayout.CENTER);

        JPanel btnBar = new JPanel(new FlowLayout(FlowLayout.CENTER, 14, 2));
        JButton okBtn = new JButton("OK", VectorIcons.greenCheckIcon());
        okBtn.setPreferredSize(new Dimension(96, 28));
        okBtn.addActionListener(e -> {
            TargetItem sel = pinList.getSelectedValue();
            if (sel != null && !sel.available()) {
                javax.swing.JOptionPane.showMessageDialog(
                        dlg,
                        "Cannot bind to " + sel.ref() + " because it is already driven.",
                        "Pin Driver Conflict",
                        javax.swing.JOptionPane.WARNING_MESSAGE
                );
                return;
            }
            buttonElem.setText(labelField.getText().trim().isEmpty() ? "BTN" : labelField.getText().trim());
            buttonElem.setToggleButton(modeCombo.getSelectedIndex() == 1);
            if (sel != null) {
                buttonElem.setBoundTargetPin(sel.ref());
            }
            confirmed[0] = true;
            dlg.dispose();
        });

        JButton cancelBtn = new JButton("Cancel", VectorIcons.redCrossIcon());
        cancelBtn.setPreferredSize(new Dimension(96, 28));
        cancelBtn.addActionListener(e -> dlg.dispose());

        btnBar.add(okBtn);
        btnBar.add(cancelBtn);
        root.add(btnBar, BorderLayout.SOUTH);

        dlg.setContentPane(root);
        dlg.pack();
        dlg.setLocationRelativeTo(owner);
        dlg.setVisible(true);
        return confirmed[0];
    }

    /**
     * Opens properties dialog for decorative shapes, lines, and text annotations.
     */
    public static boolean showGraphicPropertiesDialog(Frame owner, GraphicElement elem) {
        return showGraphicPropertiesDialog(owner, null, elem);
    }

    /**
     * Opens properties dialog for decorative shapes, lines, text annotations, and live indicators.
     */
    public static boolean showGraphicPropertiesDialog(Frame owner, Page page, GraphicElement elem) {
        if (elem.getKind() == GraphicElement.Kind.VISUAL_BUTTON) return false;
        JDialog dlg = new JDialog(owner, "Graphic Element Properties (" + elem.getKind().getDisplayName() + ")", true);
        final boolean[] confirmed = {false};

        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 6, 4, 6);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        final Color[] strokeColor = {elem.getStrokeColor()};
        final Color[] fillColor = {elem.getFillColor()};
        final Color[] actColor = {elem.getIndicatorActiveColor()};
        final Color[] inactColor = {elem.getIndicatorInactiveColor()};

        JButton strokeBtn = new JButton("Pick Stroke Color");
        strokeBtn.setBackground(strokeColor[0]);
        strokeBtn.addActionListener(e -> {
            Color c = JColorChooser.showDialog(dlg, "Select Stroke Color", strokeColor[0]);
            if (c != null) {
                strokeColor[0] = c;
                strokeBtn.setBackground(c);
            }
        });

        JButton fillBtn = new JButton("Pick Fill Color");
        fillBtn.setBackground(fillColor[0]);
        fillBtn.addActionListener(e -> {
            Color c = JColorChooser.showDialog(dlg, "Select Fill Color", fillColor[0]);
            if (c != null) {
                fillColor[0] = c;
                fillBtn.setBackground(c);
            }
        });

        JCheckBox filledChk = new JCheckBox("Fill Shape Interior", elem.isFilled());
        JSpinner widthSpinner = new JSpinner(new SpinnerNumberModel(elem.getStrokeWidth(), 1, 8, 1));
        JCheckBox dashedChk = new JCheckBox("Dashed Outline", elem.isDashed());
        JSpinner sidesSpinner = new JSpinner(new SpinnerNumberModel(elem.getNgonSides(), 3, 32, 1));
        JTextField textField = new JTextField(elem.getText(), 16);
        JSpinner fontSizeSpinner = new JSpinner(new SpinnerNumberModel(elem.getFontSize(), 8, 48, 1));

        int r = 0;
        gbc.gridx = 0; gbc.gridy = r; form.add(new JLabel("Stroke Color:"), gbc);
        gbc.gridx = 1; form.add(strokeBtn, gbc);
        r++;

        if (elem.getKind() != GraphicElement.Kind.LINE && elem.getKind() != GraphicElement.Kind.TEXT) {
            gbc.gridx = 0; gbc.gridy = r; form.add(new JLabel("Fill Color:"), gbc);
            gbc.gridx = 1; form.add(fillBtn, gbc);
            r++;
            gbc.gridx = 0; gbc.gridy = r; form.add(new JLabel("Fill Mode:"), gbc);
            gbc.gridx = 1; form.add(filledChk, gbc);
            r++;
        }

        gbc.gridx = 0; gbc.gridy = r; form.add(new JLabel("Stroke Width (px):"), gbc);
        gbc.gridx = 1; form.add(widthSpinner, gbc);
        r++;

        gbc.gridx = 0; gbc.gridy = r; form.add(new JLabel("Line Style:"), gbc);
        gbc.gridx = 1; form.add(dashedChk, gbc);
        r++;

        if (elem.getKind() == GraphicElement.Kind.CUSTOM_NGON) {
            gbc.gridx = 0; gbc.gridy = r; form.add(new JLabel("Polygon Sides (N):"), gbc);
            gbc.gridx = 1; form.add(sidesSpinner, gbc);
            r++;
        }

        if (elem.getKind() == GraphicElement.Kind.TEXT) {
            gbc.gridx = 0; gbc.gridy = r; form.add(new JLabel("Text Content:"), gbc);
            gbc.gridx = 1; form.add(textField, gbc);
            r++;
            gbc.gridx = 0; gbc.gridy = r; form.add(new JLabel("Font Size:"), gbc);
            gbc.gridx = 1; form.add(fontSizeSpinner, gbc);
            r++;
        }

        JPanel centerPanel = new JPanel(new BorderLayout(8, 8));
        centerPanel.add(form, BorderLayout.NORTH);

        JCheckBox indicatorChk = null;
        JList<String> pinList = null;
        if (elem.getKind() != GraphicElement.Kind.LINE && elem.getKind() != GraphicElement.Kind.TEXT) {
            indicatorChk = new JCheckBox("Use Shape as Live Indicator (Bind to Output Pin)", elem.isIndicatorEnabled());
            indicatorChk.setFont(cz.maramat.ui.AppFonts.uiBold());

            JButton actColBtn = new JButton("Active (ON) Fill Color");
            actColBtn.setBackground(actColor[0]);
            actColBtn.addActionListener(e -> {
                Color c = JColorChooser.showDialog(dlg, "Select Indicator Active Color", actColor[0]);
                if (c != null) {
                    actColor[0] = c;
                    actColBtn.setBackground(c);
                }
            });

            JButton inactColBtn = new JButton("Inactive (OFF) Fill Color");
            inactColBtn.setBackground(inactColor[0]);
            inactColBtn.addActionListener(e -> {
                Color c = JColorChooser.showDialog(dlg, "Select Indicator Inactive Color", inactColor[0]);
                if (c != null) {
                    inactColor[0] = c;
                    inactColBtn.setBackground(c);
                }
            });

            List<String> allOutputPins = new ArrayList<>();
            if (page != null) {
                for (BlockInstance b : page.getBlocks()) {
                    for (PinInstance p : b.getOutputs()) {
                        allOutputPins.add(b.getName() + "." + p.getShortName());
                    }
                }
            }

            JPanel indicatorPanel = new JPanel(new BorderLayout(4, 4));
            indicatorPanel.setBorder(BorderFactory.createTitledBorder("Live Output Indicator Binding"));

            JPanel indTop = new JPanel(new GridLayout(3, 1, 3, 3));
            indTop.add(indicatorChk);
            JPanel colRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            colRow.add(new JLabel("Active:"));
            colRow.add(actColBtn);
            colRow.add(new JLabel("Inactive:"));
            colRow.add(inactColBtn);
            indTop.add(colRow);

            JPanel searchRow = new JPanel(new BorderLayout(4, 0));
            searchRow.add(new JLabel("Search Output Pin:"), BorderLayout.WEST);
            JTextField searchField = new JTextField();
            searchRow.add(searchField, BorderLayout.CENTER);
            indTop.add(searchRow);
            indicatorPanel.add(indTop, BorderLayout.NORTH);

            DefaultListModel<String> listModel = new DefaultListModel<>();
            for (String s : allOutputPins) listModel.addElement(s);
            pinList = new JList<>(listModel);
            pinList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            if (!elem.getIndicatorPin().isEmpty()) {
                pinList.setSelectedValue(elem.getIndicatorPin(), true);
            }

            searchField.getDocument().addDocumentListener(new DocumentListener() {
                private void filter() {
                    String q = searchField.getText().trim().toLowerCase();
                    listModel.clear();
                    for (String item : allOutputPins) {
                        if (q.isEmpty() || item.toLowerCase().contains(q)) {
                            listModel.addElement(item);
                        }
                    }
                }
                @Override public void insertUpdate(DocumentEvent e) { filter(); }
                @Override public void removeUpdate(DocumentEvent e) { filter(); }
                @Override public void changedUpdate(DocumentEvent e) { filter(); }
            });

            JScrollPane scroll = new JScrollPane(pinList);
            scroll.setPreferredSize(new Dimension(300, 100));
            indicatorPanel.add(scroll, BorderLayout.CENTER);
            centerPanel.add(indicatorPanel, BorderLayout.CENTER);
        }

        root.add(centerPanel, BorderLayout.CENTER);

        JPanel btnBar = new JPanel(new FlowLayout(FlowLayout.CENTER, 14, 2));
        JButton okBtn = new JButton("OK", VectorIcons.greenCheckIcon());
        okBtn.setPreferredSize(new Dimension(96, 28));

        JCheckBox finalIndicatorChk = indicatorChk;
        JList<String> finalPinList = pinList;

        okBtn.addActionListener(e -> {
            elem.setStrokeColor(strokeColor[0]);
            elem.setFillColor(fillColor[0]);
            elem.setFilled(filledChk.isSelected());
            elem.setStrokeWidth((Integer) widthSpinner.getValue());
            elem.setDashed(dashedChk.isSelected());
            elem.setNgonSides((Integer) sidesSpinner.getValue());
            elem.setText(textField.getText());
            elem.setFontSize((Integer) fontSizeSpinner.getValue());

            if (finalIndicatorChk != null) {
                elem.setIndicatorEnabled(finalIndicatorChk.isSelected());
                elem.setIndicatorActiveColor(actColor[0]);
                elem.setIndicatorInactiveColor(inactColor[0]);
                if (finalPinList != null && finalPinList.getSelectedValue() != null) {
                    elem.setIndicatorPin(finalPinList.getSelectedValue());
                }
            }

            confirmed[0] = true;
            dlg.dispose();
        });

        JButton cancelBtn = new JButton("Cancel", VectorIcons.redCrossIcon());
        cancelBtn.setPreferredSize(new Dimension(96, 28));
        cancelBtn.addActionListener(e -> dlg.dispose());

        btnBar.add(okBtn);
        btnBar.add(cancelBtn);
        root.add(btnBar, BorderLayout.SOUTH);

        dlg.setContentPane(root);
        dlg.pack();
        dlg.setLocationRelativeTo(owner);
        dlg.setVisible(true);
        return confirmed[0];
    }

    /**
     * Triggers the runtime action on a Visual -> Vars block (BoolVar, IntVar, LongVar, FloatVar):
     * - Switch mode: immediately toggles between ValA and ValB.
     * - Slider mode: opens a slider modal where closing/exiting commits the value.
     */
    public static void triggerXVarAction(Frame owner, BlockInstance xVarBlock, Runnable repaintCallback) {
        String inputType = xVarBlock.getCustomProperties().getOrDefault("InputType", "Switch");
        String curStr = xVarBlock.getCustomProperties().getOrDefault("CurrentValue", "0");
        PinInstance outPin = xVarBlock.getOutputs().getFirst();

        if ("Switch".equalsIgnoreCase(inputType)) {
            String valA = xVarBlock.getCustomProperties().getOrDefault("ValA", "0");
            String valB = xVarBlock.getCustomProperties().getOrDefault("ValB", "1");
            String nextVal = curStr.equals(valA) ? valB : valA;
            xVarBlock.getCustomProperties().put("CurrentValue", nextVal);
            outPin.setRuntimeValue(outPin.getDataType().toNumericValue(nextVal));
            if (repaintCallback != null) repaintCallback.run();
        } else {
            // Slider GUI: exiting the slider sets the value
            int min = parseIntSafe(xVarBlock.getCustomProperties().getOrDefault("ValA", xVarBlock.getCustomProperties().getOrDefault("SliderMin", "0")), 0);
            int max = parseIntSafe(xVarBlock.getCustomProperties().getOrDefault("ValB", xVarBlock.getCustomProperties().getOrDefault("SliderMax", "100")), 100);
            if (max <= min) max = min + 100;
            int cur = Math.clamp((int) Math.round(outPin.getDataType().toNumericValue(curStr)), min, max);

            JDialog sliderDlg = new JDialog(owner, xVarBlock.getName() + " - Slider (" + min + " .. " + max + ")", true);
            JPanel p = new JPanel(new BorderLayout(8, 8));
            p.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));

            JLabel valLbl = new JLabel("Value: " + cur);
            JSlider slider = new JSlider(min, max, cur);
            slider.setMajorTickSpacing(Math.max(1, (max - min) / 4));
            slider.setPaintTicks(true);
            slider.setPaintLabels(true);
            slider.addChangeListener(e -> valLbl.setText("Value: " + slider.getValue()));

            Runnable commitValue = () -> {
                String vStr = Integer.toString(slider.getValue());
                xVarBlock.getCustomProperties().put("CurrentValue", vStr);
                outPin.setRuntimeValue(outPin.getDataType().toNumericValue(vStr));
                if (repaintCallback != null) repaintCallback.run();
            };

            sliderDlg.addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosing(WindowEvent e) {
                    commitValue.run();
                }
            });

            JButton doneBtn = new JButton("Set & Close", VectorIcons.greenCheckIcon());
            doneBtn.addActionListener(e -> {
                commitValue.run();
                sliderDlg.dispose();
            });

            p.add(valLbl, BorderLayout.NORTH);
            p.add(slider, BorderLayout.CENTER);
            p.add(doneBtn, BorderLayout.SOUTH);

            sliderDlg.setContentPane(p);
            sliderDlg.pack();
            sliderDlg.setMinimumSize(new Dimension(300, 140));
            sliderDlg.setLocationRelativeTo(owner);
            sliderDlg.setVisible(true);
        }
    }

    public static boolean isSimClickableBlock(BlockInstance b) {
        if (b == null) return false;
        String defId = b.getDefinitionId();
        return switch (defId) {
            case "Comm.I2C.TMP75", "Comm.I2C.BME280", "Comm.I2C.SHT40", "Comm.DS18B20.DS18B20Read" -> true;
            case "Comm.REST.RestGet", "Comm.REST.RestPost" ->
                    "Simulate".equalsIgnoreCase(cz.maramat.settings.AppSettings.get().getRestSimMode());
            default -> false;
        };
    }

    /**
     * Opens a live simulation popup for sensor blocks (and REST blocks when REST mode is "Simulate")
     * with sliders for INT/FLOAT outputs and ON/OFF toggle buttons for BOOL outputs.
     */
    public static void showSimulatedBlockPopup(Frame owner, BlockInstance block, Runnable repaintCallback) {
        JDialog dlg = new JDialog(owner, block.getName() + " - Simulate Outputs", true);
        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 6, 4, 6);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        int row = 0;

        for (PinInstance pin : block.getOutputs()) {
            String key = "SimOut." + pin.getShortName();
            double curVal = pin.getRuntimeValue();
            String saved = block.getCustomProperties().get(key);
            if (saved != null && !saved.isBlank()) {
                try { curVal = Double.parseDouble(saved.trim()); } catch (NumberFormatException ignored) {}
            }

            gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0.0;
            form.add(new JLabel(pin.getShortName() + " (" + pin.getFullName() + "):"), gbc);

            if (pin.getDataType() == DataType.BOOL) {
                boolean isOn = curVal != 0.0;
                javax.swing.JToggleButton toggle = new javax.swing.JToggleButton(isOn ? "ON (1)" : "OFF (0)", isOn);
                toggle.addActionListener(ev -> {
                    boolean sel = toggle.isSelected();
                    toggle.setText(sel ? "ON (1)" : "OFF (0)");
                    double nv = sel ? 1.0 : 0.0;
                    block.getCustomProperties().put(key, sel ? "1" : "0");
                    pin.setRuntimeValue(nv);
                    if (repaintCallback != null) repaintCallback.run();
                });
                gbc.gridx = 1; gbc.weightx = 1.0;
                form.add(toggle, gbc);
            } else {
                boolean isInt = pin.getDataType() == DataType.INT;
                double minD, maxD;
                int scale;
                switch (pin.getShortName()) {
                    case "Tmp" -> { minD = -40.0; maxD = 125.0; scale = 10; }
                    case "Hum" -> { minD = 0.0; maxD = 100.0; scale = 10; }
                    case "Prs" -> { minD = 300.0; maxD = 1100.0; scale = 10; }
                    case "Sts" -> { minD = 0.0; maxD = 599.0; scale = 1; }
                    default -> { minD = -100.0; maxD = 100.0; scale = isInt ? 1 : 10; }
                }
                int sMin = (int) Math.round(minD * scale);
                int sMax = (int) Math.round(maxD * scale);
                int sCur = Math.clamp((int) Math.round(curVal * scale), sMin, sMax);

                JPanel sliderRow = new JPanel(new BorderLayout(6, 0));
                JLabel valLbl = new JLabel(isInt ? Integer.toString((int) Math.round(curVal))
                        : String.format(java.util.Locale.ROOT, "%.1f", sCur / (double) scale));
                valLbl.setPreferredSize(new Dimension(60, 20));
                JSlider slider = new JSlider(sMin, sMax, sCur);
                slider.addChangeListener(ev -> {
                    double v = slider.getValue() / (double) scale;
                    String s = isInt ? Integer.toString((int) Math.round(v))
                            : String.format(java.util.Locale.ROOT, "%.2f", v);
                    valLbl.setText(s);
                    block.getCustomProperties().put(key, s);
                    pin.setRuntimeValue(v);
                    if (repaintCallback != null) repaintCallback.run();
                });
                sliderRow.add(slider, BorderLayout.CENTER);
                sliderRow.add(valLbl, BorderLayout.EAST);
                gbc.gridx = 1; gbc.weightx = 1.0;
                form.add(sliderRow, gbc);
            }
            row++;
        }

        JButton closeBtn = new JButton("Close", VectorIcons.greenCheckIcon());
        closeBtn.addActionListener(e -> dlg.dispose());
        JPanel btnBar = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        btnBar.add(closeBtn);

        root.add(form, BorderLayout.CENTER);
        root.add(btnBar, BorderLayout.SOUTH);
        dlg.setContentPane(root);
        dlg.pack();
        dlg.setMinimumSize(new Dimension(420, Math.max(160, 90 + row * 40)));
        dlg.setLocationRelativeTo(owner);
        dlg.setVisible(true);
    }

    private static int parseIntSafe(String s, int fallback) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return fallback;
        }
    }
}
