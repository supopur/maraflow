package cz.maramat.ui.dialogs;

import cz.maramat.model.BlockInstance;
import cz.maramat.model.GraphicElement;
import cz.maramat.model.Page;
import cz.maramat.model.PinInstance;
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
     * Opens the Visual Button properties modal with a live search bar and list of every input pin in Blockname.Pinname format.
     */
    public static boolean showVisualButtonDialog(Frame owner, Page page, GraphicElement buttonElem) {
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

        // Pin selector with search bar
        List<String> allInputPins = new ArrayList<>();
        for (BlockInstance b : page.getBlocks()) {
            for (PinInstance pin : b.getInputs()) {
                allInputPins.add(b.getName() + "." + pin.getShortName());
            }
        }

        JPanel pinPanel = new JPanel(new BorderLayout(4, 4));
        pinPanel.setBorder(BorderFactory.createTitledBorder("Target Input Pin (Blockname.Pinname)"));

        JPanel searchRow = new JPanel(new BorderLayout(4, 0));
        searchRow.add(new JLabel("Search:"), BorderLayout.WEST);
        JTextField searchField = new JTextField();
        searchRow.add(searchField, BorderLayout.CENTER);
        pinPanel.add(searchRow, BorderLayout.NORTH);

        DefaultListModel<String> listModel = new DefaultListModel<>();
        for (String s : allInputPins) listModel.addElement(s);
        JList<String> pinList = new JList<>(listModel);
        pinList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        if (!buttonElem.getBoundTargetPin().isEmpty()) {
            pinList.setSelectedValue(buttonElem.getBoundTargetPin(), true);
        }

        searchField.getDocument().addDocumentListener(new DocumentListener() {
            private void filter() {
                String q = searchField.getText().trim().toLowerCase();
                listModel.clear();
                for (String item : allInputPins) {
                    if (q.isEmpty() || item.toLowerCase().contains(q)) {
                        listModel.addElement(item);
                    }
                }
            }
            @Override public void insertUpdate(DocumentEvent e) { filter(); }
            @Override public void removeUpdate(DocumentEvent e) { filter(); }
            @Override public void changedUpdate(DocumentEvent e) { filter(); }
        });

        JScrollPane listScroll = new JScrollPane(pinList);
        listScroll.setPreferredSize(new Dimension(320, 160));
        pinPanel.add(listScroll, BorderLayout.CENTER);
        root.add(pinPanel, BorderLayout.CENTER);

        JPanel btnBar = new JPanel(new FlowLayout(FlowLayout.CENTER, 14, 2));
        JButton okBtn = new JButton("OK", VectorIcons.greenCheckIcon());
        okBtn.setPreferredSize(new Dimension(96, 28));
        okBtn.addActionListener(e -> {
            buttonElem.setText(labelField.getText().trim().isEmpty() ? "BTN" : labelField.getText().trim());
            buttonElem.setToggleButton(modeCombo.getSelectedIndex() == 1);
            String sel = pinList.getSelectedValue();
            if (sel != null) {
                buttonElem.setBoundTargetPin(sel);
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
            int min = parseIntSafe(xVarBlock.getCustomProperties().getOrDefault("SliderMin", "0"), 0);
            int max = parseIntSafe(xVarBlock.getCustomProperties().getOrDefault("SliderMax", "100"), 100);
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

    private static int parseIntSafe(String s, int fallback) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return fallback;
        }
    }
}
