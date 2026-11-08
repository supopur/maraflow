package cz.maramat.ui.panels;

import cz.maramat.model.BlockCatalog;
import cz.maramat.model.BlockDefinition;

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
import java.util.LinkedHashMap;
import java.util.Map;

public class LibraryTreePanel extends JPanel {
    private final JTree tree;
    private final DefaultMutableTreeNode rootNode;
    private final DefaultTreeModel treeModel;

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

    public void rebuildTree() {
        rootNode.removeAllChildren();
        Map<String, DefaultMutableTreeNode> folderNodes = new LinkedHashMap<>();

        // Pre-create top-level categories in exact specification order
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

        treeModel.reload();
        // Expand Bool and Visual by default like in the reference screenshot
        for (int i = 0; i < tree.getRowCount(); i++) {
            TreePath tp = tree.getPathForRow(i);
            if (tp != null) {
                String seg = tp.getLastPathComponent().toString();
                if (seg.equals("Bool") || seg.equals("Visual")) {
                    tree.expandRow(i);
                }
            }
        }
    }
}
