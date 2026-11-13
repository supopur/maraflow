package cz.maramat.ui.help;

import cz.maramat.help.HelpContentManager;
import cz.maramat.help.HelpHtmlEditorKit;
import cz.maramat.help.HelpMarkdownRenderer;
import cz.maramat.help.HelpMarkdownRenderer.AnchorEntry;
import cz.maramat.help.HelpMarkdownRenderer.RenderedHelpHtml;
import cz.maramat.help.HelpPage;
import cz.maramat.help.HelpSearchIndex;
import cz.maramat.help.HelpSearchIndex.SearchResult;
import cz.maramat.help.HelpToc;
import cz.maramat.help.HelpToc.TocNode;
import cz.maramat.help.diagram.FbdDiagramRenderer;
import cz.maramat.help.diagram.LdDiagramRenderer;
import cz.maramat.settings.AppSettings;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JEditorPane;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextField;
import javax.swing.JTree;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.ToolTipManager;
import javax.swing.WindowConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.HyperlinkEvent;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Enumeration;
import java.util.List;
import java.util.function.Consumer;

/**
 * Built-in utilitarian Help/Manual viewer window.
 * Lazy-initialized on first open and releases caches + search index when closed.
 */
public final class HelpWindow extends JFrame {

    public static final String HOME_PAGE_ID = "getting-started/scan-cycle";
    private static final int LEFT_PANE_WIDTH = 250;

    private static HelpWindow instance;
    private static Consumer<String> openStSnippetHandler;

    private final HelpContentManager contentManager = new HelpContentManager();
    private final HelpMarkdownRenderer markdownRenderer = new HelpMarkdownRenderer();
    private final HelpSearchIndex searchIndex = new HelpSearchIndex();

    private final JButton backBtn = new JButton("Back");
    private final JButton forwardBtn = new JButton("Forward");
    private final JButton homeBtn = new JButton("Home");
    private final JComboBox<String> langCombo = new JComboBox<>(new String[]{"Auto", "English", "Cesky"});
    private final JLabel pathLabel = new JLabel("");

    private final JTree tocTree = new JTree();
    private final JTextField searchField = new JTextField();
    private final DefaultListModel<SearchResult> searchModel = new DefaultListModel<>();
    private final JList<SearchResult> searchList = new JList<>(searchModel);
    private final Timer searchDebounceTimer;

    private final JEditorPane editorPane = new JEditorPane();
    private final JScrollPane editorScroll = new JScrollPane(editorPane);
    private final DefaultListModel<AnchorEntry> anchorModel = new DefaultListModel<>();
    private final JList<AnchorEntry> anchorList = new JList<>(anchorModel);
    private final JPanel anchorPanel = new JPanel(new BorderLayout());

    private final List<String> history = new ArrayList<>();
    private int historyIndex = -1;
    private boolean updatingTreeSelection = false;
    private boolean updatingLangCombo = false;
    private String currentPageId = HOME_PAGE_ID;

    private HelpWindow() {
        super("Maraflow Help");
        setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);
        setSize(1020, 700);
        setMinimumSize(new Dimension(740, 480));
        setLocationRelativeTo(null);

        searchDebounceTimer = new Timer(150, e -> runSearch());
        searchDebounceTimer.setRepeats(false);

        initUi();
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                releaseResources();
            }
        });
    }

    public static void setOpenStSnippetHandler(Consumer<String> handler) {
        openStSnippetHandler = handler;
    }

    public static void open(Frame owner, String pageId) {
        if (instance == null) {
            instance = new HelpWindow();
            if (owner != null) {
                instance.setLocationRelativeTo(owner);
            }
        }
        instance.reloadToc();
        instance.syncLangCombo();
        String target = (pageId != null && !pageId.isBlank()) ? pageId : HOME_PAGE_ID;
        instance.navigateTo(target, true);
        instance.setVisible(true);
        instance.toFront();
        instance.requestFocus();
    }

    private void initUi() {
        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(Color.WHITE);

        // Top flat utilitarian toolbar
        JPanel toolbar = new JPanel(new BorderLayout());
        toolbar.setBackground(new Color(0xF0, 0xF0, 0xF0));
        toolbar.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(0x88, 0x88, 0x88)));

        JPanel navLeft = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 4));
        navLeft.setOpaque(false);
        backBtn.setFocusable(false);
        forwardBtn.setFocusable(false);
        homeBtn.setFocusable(false);
        navLeft.add(backBtn);
        navLeft.add(forwardBtn);
        navLeft.add(homeBtn);
        navLeft.add(Box.createHorizontalStrut(8));
        pathLabel.setForeground(new Color(0x44, 0x44, 0x44));
        navLeft.add(pathLabel);

        JPanel navRight = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 4));
        navRight.setOpaque(false);
        navRight.add(new JLabel("Lang:"));
        langCombo.setFocusable(false);
        navRight.add(langCombo);

        toolbar.add(navLeft, BorderLayout.WEST);
        toolbar.add(navRight, BorderLayout.EAST);
        root.add(toolbar, BorderLayout.NORTH);

        // Left Contents pane (no horizontal scrollbars; elided labels + full tooltip)
        tocTree.setRootVisible(false);
        tocTree.setShowsRootHandles(true);
        tocTree.setRowHeight(19);
        ToolTipManager.sharedInstance().registerComponent(tocTree);
        tocTree.setCellRenderer(new DefaultTreeCellRenderer() {
            @Override
            public Component getTreeCellRendererComponent(JTree tree, Object value, boolean sel,
                                                          boolean expanded, boolean leaf, int row, boolean hasFocus) {
                super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus);
                setOpenIcon(null);
                setClosedIcon(null);
                setLeafIcon(null);
                String text = value == null ? "" : value.toString();
                setToolTipText(text);
                if (text.length() > 30) {
                    setText(text.substring(0, 28) + "...");
                }
                return this;
            }
        });
        tocTree.addTreeSelectionListener(e -> {
            if (updatingTreeSelection) return;
            Object last = tocTree.getLastSelectedPathComponent();
            if (last instanceof DefaultMutableTreeNode dmtn && dmtn.getUserObject() instanceof TocNode tn) {
                if (tn.getPageId() != null) {
                    navigateTo(tn.getPageId(), true);
                }
            }
        });
        JScrollPane tocScroll = new JScrollPane(tocTree);
        tocScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        tocScroll.setBorder(BorderFactory.createEmptyBorder());

        // Left Search pane
        JPanel searchPanel = new JPanel(new BorderLayout(0, 4));
        searchPanel.setBackground(Color.WHITE);
        searchPanel.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        searchField.putClientProperty("JTextField.placeholderText", "Search help...");
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { searchDebounceTimer.restart(); }
            @Override public void removeUpdate(DocumentEvent e) { searchDebounceTimer.restart(); }
            @Override public void changedUpdate(DocumentEvent e) { searchDebounceTimer.restart(); }
        });
        searchField.addActionListener(e -> runSearch());
        searchPanel.add(searchField, BorderLayout.NORTH);

        searchList.setFixedCellWidth(LEFT_PANE_WIDTH - 24);
        searchList.setCellRenderer((list, value, index, isSelected, cellHasFocus) -> {
            JPanel cell = new JPanel(new BorderLayout(0, 1));
            cell.setOpaque(true);
            cell.setBackground(isSelected ? new Color(0xD8, 0xE6, 0xF3) : Color.WHITE);
            cell.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(0xE0, 0xE0, 0xE0)),
                    BorderFactory.createEmptyBorder(4, 4, 4, 4)
            ));
            JLabel titleLbl = new JLabel(truncate(value.title(), 32));
            titleLbl.setFont(titleLbl.getFont().deriveFont(Font.BOLD, 11f));
            titleLbl.setForeground(new Color(0x00, 0x33, 0x66));
            JLabel idLbl = new JLabel(truncate(value.pageId(), 36));
            idLbl.setFont(idLbl.getFont().deriveFont(Font.PLAIN, 10f));
            idLbl.setForeground(new Color(0x66, 0x66, 0x66));
            cell.add(titleLbl, BorderLayout.NORTH);
            cell.add(idLbl, BorderLayout.SOUTH);
            cell.setToolTipText(value.title() + " (" + value.pageId() + ")");
            return cell;
        });
        searchList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                SearchResult sel = searchList.getSelectedValue();
                if (sel != null) {
                    navigateTo(sel.pageId(), true);
                }
            }
        });
        JScrollPane searchScroll = new JScrollPane(searchList);
        searchScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        searchScroll.setBorder(BorderFactory.createLineBorder(new Color(0xC8, 0xC8, 0xC8)));
        searchPanel.add(searchScroll, BorderLayout.CENTER);

        JTabbedPane leftTabs = new JTabbedPane();
        leftTabs.addTab("Contents", tocScroll);
        leftTabs.addTab("Search", searchPanel);
        leftTabs.setPreferredSize(new Dimension(LEFT_PANE_WIDTH, 100));
        leftTabs.setMinimumSize(new Dimension(200, 100));
        leftTabs.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, new Color(0x88, 0x88, 0x88)));

        // Center HTML view
        editorPane.setEditable(false);
        editorPane.setEditorKit(new HelpHtmlEditorKit(FbdDiagramRenderer.DEFAULT_GRID_PX));
        editorPane.setBackground(Color.WHITE);
        editorPane.addHyperlinkListener(e -> {
            if (e.getEventType() == HyperlinkEvent.EventType.ACTIVATED) {
                handleHyperlink(e.getDescription());
            }
        });
        editorScroll.setBorder(BorderFactory.createEmptyBorder());
        editorScroll.getVerticalScrollBar().setUnitIncrement(16);

        // Right "On this page" compact outline (no horizontal scroll)
        anchorPanel.setBackground(new Color(0xF8, 0xF8, 0xF8));
        anchorPanel.setBorder(BorderFactory.createMatteBorder(0, 1, 0, 0, new Color(0xC8, 0xC8, 0xC8)));
        anchorPanel.setPreferredSize(new Dimension(170, 100));
        JLabel anchorHdr = new JLabel(" On this page");
        anchorHdr.setBorder(BorderFactory.createEmptyBorder(5, 4, 4, 4));
        anchorPanel.add(anchorHdr, BorderLayout.NORTH);
        anchorList.setBackground(new Color(0xF8, 0xF8, 0xF8));
        anchorList.setFixedCellWidth(156);
        anchorList.setCellRenderer((list, value, index, isSelected, cellHasFocus) -> {
            String raw = (value.level() == 3 ? "   " : " ") + value.text();
            JLabel lbl = new JLabel(truncate(raw, 24));
            lbl.setToolTipText(value.text());
            lbl.setOpaque(true);
            lbl.setBackground(isSelected ? new Color(0xD8, 0xE6, 0xF3) : new Color(0xF8, 0xF8, 0xF8));
            lbl.setForeground(new Color(0x22, 0x22, 0x22));
            lbl.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
            return lbl;
        });
        anchorList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                AnchorEntry sel = anchorList.getSelectedValue();
                if (sel != null) {
                    editorPane.scrollToReference(sel.id());
                }
            }
        });
        JScrollPane anchorScroll = new JScrollPane(anchorList);
        anchorScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        anchorScroll.setBorder(BorderFactory.createEmptyBorder());
        anchorPanel.add(anchorScroll, BorderLayout.CENTER);

        JPanel contentArea = new JPanel(new BorderLayout());
        contentArea.add(editorScroll, BorderLayout.CENTER);
        contentArea.add(anchorPanel, BorderLayout.EAST);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftTabs, contentArea);
        split.setDividerLocation(LEFT_PANE_WIDTH);
        split.setDividerSize(4);
        split.setBorder(BorderFactory.createEmptyBorder());
        root.add(split, BorderLayout.CENTER);

        backBtn.addActionListener(e -> goBack());
        forwardBtn.addActionListener(e -> goForward());
        homeBtn.addActionListener(e -> navigateTo(HOME_PAGE_ID, true));
        langCombo.addActionListener(e -> {
            if (updatingLangCombo) return;
            int idx = langCombo.getSelectedIndex();
            String setting = switch (idx) {
                case 1 -> "en";
                case 2 -> "cs";
                default -> "auto";
            };
            AppSettings.get().setHelpLanguage(setting);
            contentManager.releaseCaches();
            searchIndex.release();
            reloadToc();
            navigateTo(currentPageId, false);
            if (!searchField.getText().isBlank()) {
                runSearch();
            }
        });

        setContentPane(root);
        updateNavButtons();
    }

    private void runSearch() {
        String q = searchField.getText();
        searchModel.clear();
        if (q == null || q.isBlank()) return;
        String locale = AppSettings.get().resolveHelpLocale();
        for (SearchResult r : searchIndex.search(q, locale, contentManager)) {
            searchModel.addElement(r);
        }
    }

    private void syncLangCombo() {
        updatingLangCombo = true;
        try {
            String lang = AppSettings.get().getHelpLanguage();
            int idx = switch (lang) {
                case "en" -> 1;
                case "cs" -> 2;
                default -> 0;
            };
            langCombo.setSelectedIndex(idx);
        } finally {
            updatingLangCombo = false;
        }
    }

    private void reloadToc() {
        String locale = AppSettings.get().resolveHelpLocale();
        TocNode rootToc = HelpToc.load(locale, contentManager);
        DefaultMutableTreeNode treeRoot = buildTreeNode(rootToc);
        tocTree.setModel(new DefaultTreeModel(treeRoot));
        // Expand only non-Blocks top-level sections so the tree stays compact
        for (int i = 0; i < treeRoot.getChildCount(); i++) {
            DefaultMutableTreeNode child = (DefaultMutableTreeNode) treeRoot.getChildAt(i);
            String label = child.getUserObject() != null ? child.getUserObject().toString() : "";
            if (!"Blocks".equalsIgnoreCase(label) && !"Bloky".equalsIgnoreCase(label)) {
                tocTree.expandPath(new TreePath(child.getPath()));
            }
        }
    }

    private DefaultMutableTreeNode buildTreeNode(TocNode node) {
        DefaultMutableTreeNode treeNode = new DefaultMutableTreeNode(node);
        for (TocNode child : node.getChildren()) {
            treeNode.add(buildTreeNode(child));
        }
        return treeNode;
    }

    public void navigateTo(String pageTarget, boolean recordHistory) {
        String pageId = pageTarget;
        String anchor = null;
        int hashIdx = pageTarget.indexOf('#');
        if (hashIdx >= 0) {
            pageId = pageTarget.substring(0, hashIdx);
            anchor = pageTarget.substring(hashIdx + 1);
        }
        if (pageId.isBlank()) {
            pageId = currentPageId;
        }

        String locale = AppSettings.get().resolveHelpLocale();
        HelpPage page = contentManager.loadPage(pageId, locale);
        if (page == null) {
            editorPane.setText("<html><body><h1>Page Not Found</h1><p>Missing help page: <code>"
                    + pageId + "</code></p></body></html>");
            return;
        }

        currentPageId = pageId;
        pathLabel.setText(pageId);

        RenderedHelpHtml rendered = markdownRenderer.render(page, locale, FbdDiagramRenderer.DEFAULT_GRID_PX);
        editorPane.setText(rendered.html());

        anchorModel.clear();
        for (AnchorEntry a : rendered.anchors()) {
            anchorModel.addElement(a);
        }
        anchorPanel.setVisible(!rendered.anchors().isEmpty());

        final String targetAnchor = anchor;
        SwingUtilities.invokeLater(() -> {
            if (targetAnchor != null && !targetAnchor.isBlank()) {
                editorPane.scrollToReference(targetAnchor);
            } else {
                editorPane.setCaretPosition(0);
            }
        });

        if (recordHistory) {
            while (history.size() > historyIndex + 1) {
                history.removeLast();
            }
            if (history.isEmpty() || !history.get(historyIndex).equals(pageTarget)) {
                history.add(pageTarget);
                historyIndex = history.size() - 1;
            }
        }
        updateNavButtons();
        selectTreeNodeForPage(pageId);
    }

    private void handleHyperlink(String href) {
        if (href == null || href.isBlank()) return;
        if (href.startsWith("http://") || href.startsWith("https://")) {
            return; // Offline help: external links disabled
        }
        if (href.startsWith("open-st:")) {
            if (openStSnippetHandler != null) {
                String encoded = href.substring("open-st:".length());
                String code = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
                openStSnippetHandler.accept(code);
            }
            return;
        }
        if (href.startsWith("help:")) {
            navigateTo(href.substring("help:".length()), true);
        } else if (href.startsWith("#")) {
            editorPane.scrollToReference(href.substring(1));
        }
    }

    private void goBack() {
        if (historyIndex > 0) {
            historyIndex--;
            navigateTo(history.get(historyIndex), false);
        }
    }

    private void goForward() {
        if (historyIndex + 1 < history.size()) {
            historyIndex++;
            navigateTo(history.get(historyIndex), false);
        }
    }

    private void updateNavButtons() {
        backBtn.setEnabled(historyIndex > 0);
        forwardBtn.setEnabled(historyIndex >= 0 && historyIndex + 1 < history.size());
    }

    private void selectTreeNodeForPage(String pageId) {
        DefaultMutableTreeNode root = (DefaultMutableTreeNode) tocTree.getModel().getRoot();
        if (root == null) return;
        Enumeration<?> en = root.depthFirstEnumeration();
        while (en.hasMoreElements()) {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) en.nextElement();
            if (node.getUserObject() instanceof TocNode tn && pageId.equals(tn.getPageId())) {
                updatingTreeSelection = true;
                try {
                    TreePath tp = new TreePath(node.getPath());
                    tocTree.setSelectionPath(tp);
                    tocTree.scrollPathToVisible(tp);
                } finally {
                    updatingTreeSelection = false;
                }
                return;
            }
        }
    }

    private void releaseResources() {
        contentManager.releaseCaches();
        FbdDiagramRenderer.clearCache();
        LdDiagramRenderer.clearCache();
        searchIndex.release();
        editorPane.setText("");
    }

    private static String truncate(String s, int maxLen) {
        if (s == null || s.length() <= maxLen) return s == null ? "" : s;
        return s.substring(0, maxLen - 3) + "...";
    }
}
