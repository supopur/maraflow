package cz.maramat.ui.panels;

import cz.maramat.model.BlockCatalog;
import cz.maramat.model.BlockDefinition;
import cz.maramat.model.IecLanguage;
import cz.maramat.model.Page;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTree;
import javax.swing.TransferHandler;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.BorderLayout;
import java.awt.Font;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public class LibraryTreePanel extends JPanel {
    private final JTree tree;
    private final DefaultMutableTreeNode rootNode;
    private final DefaultTreeModel treeModel;
    private Page activePage = null;

    public LibraryTreePanel() {
        super(new BorderLayout());
        rootNode = new DefaultMutableTreeNode("Library");
        treeModel = new DefaultTreeModel(rootNode);
        tree = new JTree(treeModel);
        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.setRowHeight(18);
        tree.putClientProperty("JTree.lineStyle", "Angled");
        tree.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

        if (tree.getCellRenderer() instanceof DefaultTreeCellRenderer renderer) {
            renderer.setFont(cz.maramat.ui.AppFonts.ui(Font.PLAIN, 11));
            renderer.setOpenIcon(null);
            renderer.setClosedIcon(null);
            renderer.setLeafIcon(null);
        }

        // Standard file-like drag and drop for leaf nodes only
        tree.setDragEnabled(true);
        tree.setTransferHandler(new TransferHandler() {
            @Override
            public int getSourceActions(JComponent c) {
                return COPY;
            }

            @Override
            protected Transferable createTransferable(JComponent c) {
                TreePath path = tree.getSelectionPath();
                if (path == null) return null;
                DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
                if (!node.isLeaf()) return null;
                Object userObj = node.getUserObject();
                if (userObj instanceof BlockDefinition def) {
                    return new StringSelection(def.getId());
                }
                return null;
            }
        });

        rebuildTree();

        JScrollPane scroll = new JScrollPane(tree);
        scroll.setBorder(null);
        add(scroll, BorderLayout.CENTER);
    }

    public void setActivePage(Page activePage) {
        this.activePage = activePage;
        rebuildTree();
    }

    public void rebuildTree() {
        Set<String> expanded = getExpandedPathStrings();
        rootNode.removeAllChildren();
        Map<String, DefaultMutableTreeNode> folderNodes = new LinkedHashMap<>();

        boolean isLd = (activePage != null && activePage.getLanguage() == IecLanguage.LD);
        boolean isMacroPage = (activePage != null && activePage.isMacro());
        String currentMacroCleanName = isMacroPage && activePage.getName().length() > 1
                ? activePage.getName().substring(1).trim()
                : "";

        if (isLd) {
            DefaultMutableTreeNode ldRoot = new DefaultMutableTreeNode("Ladder (LD)");
            folderNodes.put("Ladder (LD)", ldRoot);
            rootNode.add(ldRoot);

            for (BlockDefinition def : BlockCatalog.getAllDefinitions().values()) {
                if (!def.getId().startsWith("Ladder.")) continue;
                addDefToTree(def, folderNodes);
            }

            treeModel.reload();
            if (!expanded.isEmpty()) {
                restoreExpandedPathStrings(expanded);
            } else {
                for (int i = 0; i < tree.getRowCount(); i++) {
                    tree.expandRow(i);
                }
            }
            return;
        }

        // FBD Top-Level or Macro Page: show only FBD categories
        String[] topCategories = {
                "Bool",
                "Math",
                "I/O",
                "Communication",
                "Main",
                "Macro",
                "Visual"
        };
        for (String cat : topCategories) {
            DefaultMutableTreeNode n = new DefaultMutableTreeNode(cat);
            folderNodes.put(cat, n);
            rootNode.add(n);
        }

        for (BlockDefinition def : BlockCatalog.getAllDefinitions().values()) {
            String id = def.getId();
            if (id.startsWith("Ladder.")) continue;
            if ((id.equals("Macro.MacroIn") || id.equals("Macro.MacroOut")) && !isMacroPage) {
                continue;
            }
            if (isMacroPage && id.startsWith("Macro.User.") && def.getTypeName().equalsIgnoreCase(currentMacroCleanName)) {
                continue;
            }
            addDefToTree(def, folderNodes);
        }

        treeModel.reload();
        if (!expanded.isEmpty()) {
            restoreExpandedPathStrings(expanded);
        } else {
            for (int i = 0; i < tree.getRowCount(); i++) {
                TreePath tp = tree.getPathForRow(i);
                if (tp != null) {
                    String seg = tp.getLastPathComponent().toString();
                    if (seg.equals("Bool") || seg.equals("Visual") || seg.equals("Macro")) {
                        tree.expandRow(i);
                    }
                }
            }
        }
    }

    private Set<String> getExpandedPathStrings() {
        Set<String> set = new HashSet<>();
        for (int i = 0; i < tree.getRowCount(); i++) {
            if (tree.isExpanded(i)) {
                TreePath path = tree.getPathForRow(i);
                if (path != null) {
                    set.add(pathToString(path));
                }
            }
        }
        return set;
    }

    private String pathToString(TreePath path) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < path.getPathCount(); i++) {
            if (i > 0) sb.append("/");
            sb.append(path.getPathComponent(i).toString());
        }
        return sb.toString();
    }

    private void restoreExpandedPathStrings(Set<String> expanded) {
        for (int i = 0; i < tree.getRowCount(); i++) {
            TreePath path = tree.getPathForRow(i);
            if (path != null && expanded.contains(pathToString(path))) {
                tree.expandRow(i);
            }
        }
    }

    private void addDefToTree(BlockDefinition def, Map<String, DefaultMutableTreeNode> folderNodes) {
        String path = def.getCategoryPath();
        String[] parts = path.equals("I/O") ? new String[]{"I/O"} : path.split("/");
        DefaultMutableTreeNode parent = rootNode;
        String accum = "";
        for (String part : parts) {
            accum = accum.isEmpty() ? part : (accum + "/" + part);
            DefaultMutableTreeNode folder = folderNodes.get(accum);
            if (folder == null) {
                folder = new DefaultMutableTreeNode(part);
                folderNodes.put(accum, folder);
                parent.add(folder);
            }
            parent = folder;
        }
        parent.add(new DefaultMutableTreeNode(def));
    }
}
