import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.tree.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.util.List;
import java.util.function.Consumer;

public class QueriesSidebar {

    // Marker types to distinguish node kinds in the tree renderer
    private record SavedSection(String label) { @Override public String toString() { return label; } }
    private record SavedCategory(String name) { @Override public String toString() { return name;  } }

    private final DuckDbManager db;
    private final Consumer<String> onQuerySelected;
    private final JPanel panel;
    private final DefaultTreeModel treeModel;
    private final DefaultMutableTreeNode savedSectionNode;
    private final JTree tree;

    public QueriesSidebar(DuckDbManager db, Consumer<String> onQuerySelected) {
        this.db = db;
        this.onQuerySelected = onQuerySelected;

        // ── Build tree ────────────────────────────────────────────────────────
        DefaultMutableTreeNode root = new DefaultMutableTreeNode("root");

        for (CannedQueries.Category cat : CannedQueries.ALL) {
            DefaultMutableTreeNode catNode = new DefaultMutableTreeNode(cat);
            for (CannedQueries.Query q : cat.queries()) catNode.add(new DefaultMutableTreeNode(q));
            root.add(catNode);
        }

        savedSectionNode = new DefaultMutableTreeNode(new SavedSection("★  Saved Queries"));
        root.add(savedSectionNode);

        treeModel = new DefaultTreeModel(root);
        tree = new JTree(treeModel);
        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);

        // ── Cell renderer ─────────────────────────────────────────────────────
        tree.setCellRenderer(new DefaultTreeCellRenderer() {
            @Override
            public Component getTreeCellRendererComponent(
                    JTree t, Object value, boolean sel, boolean expanded,
                    boolean leaf, int row, boolean hasFocus) {
                super.getTreeCellRendererComponent(t, value, sel, expanded, leaf, row, hasFocus);
                setIcon(null);
                Object obj = ((DefaultMutableTreeNode) value).getUserObject();
                if (obj instanceof SavedSection) {
                    setFont(getFont().deriveFont(Font.BOLD | Font.ITALIC));
                } else if (obj instanceof CannedQueries.Category || obj instanceof SavedCategory) {
                    setFont(getFont().deriveFont(Font.BOLD));
                } else {
                    setFont(getFont().deriveFont(Font.PLAIN));
                }
                return this;
            }
        });

        // Expand built-in categories
        for (int i = 0; i < tree.getRowCount(); i++) tree.expandRow(i);

        // ── Selection → run query ─────────────────────────────────────────────
        tree.addTreeSelectionListener(e -> {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) tree.getLastSelectedPathComponent();
            if (node == null || !node.isLeaf()) return;
            Object obj = node.getUserObject();
            String sql = null;
            if      (obj instanceof CannedQueries.Query q)      sql = q.sql().strip();
            else if (obj instanceof DuckDbManager.SavedQuery sq) sql = sq.sql().strip();
            if (sql != null) onQuerySelected.accept(sql);
        });

        // ── Right-click delete on saved queries ───────────────────────────────
        tree.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e)  { if (e.isPopupTrigger()) showPopup(e); }
            @Override public void mouseReleased(MouseEvent e) { if (e.isPopupTrigger()) showPopup(e); }
        });

        // ── Import / Export toolbar ───────────────────────────────────────────
        JButton importBtn = new JButton("Import...");
        JButton exportBtn = new JButton("Export...");
        JPanel btnBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        btnBar.add(importBtn);
        btnBar.add(exportBtn);

        importBtn.addActionListener(e -> importQueries());
        exportBtn.addActionListener(e -> exportQueries());

        panel = new JPanel(new BorderLayout(0, 2));
        panel.setBorder(BorderFactory.createTitledBorder("Queries"));
        panel.add(btnBar, BorderLayout.NORTH);
        panel.add(new JScrollPane(tree), BorderLayout.CENTER);

        refresh();
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public void refresh() {
        new Thread(() -> {
            try {
                List<DuckDbManager.SavedQuery> queries = db.loadSavedQueries();
                SwingUtilities.invokeLater(() -> rebuildSavedSection(queries));
            } catch (Exception ignored) {
                // table not yet created or DB not ready — silently skip
            }
        }, "DuckDuckBurp-sidebar-refresh").start();
    }

    public Component uiComponent() { return panel; }

    private Window frame() { return SwingUtilities.getWindowAncestor(panel); }

    // ── Private helpers ───────────────────────────────────────────────────────

    private void rebuildSavedSection(List<DuckDbManager.SavedQuery> queries) {
        savedSectionNode.removeAllChildren();

        String currentCat = null;
        DefaultMutableTreeNode catNode = null;
        for (DuckDbManager.SavedQuery sq : queries) {
            if (!sq.category().equals(currentCat)) {
                currentCat = sq.category();
                catNode = new DefaultMutableTreeNode(new SavedCategory(currentCat));
                savedSectionNode.add(catNode);
            }
            catNode.add(new DefaultMutableTreeNode(sq));
        }

        treeModel.reload(savedSectionNode);
        tree.expandPath(new TreePath(savedSectionNode.getPath()));
        for (int i = 0; i < tree.getRowCount(); i++) {
            Object obj = ((DefaultMutableTreeNode) tree.getPathForRow(i)
                    .getLastPathComponent()).getUserObject();
            if (obj instanceof SavedCategory) tree.expandRow(i);
        }
    }

    private void showPopup(MouseEvent e) {
        int row = tree.getRowForLocation(e.getX(), e.getY());
        if (row < 0) return;
        tree.setSelectionRow(row);
        DefaultMutableTreeNode node = (DefaultMutableTreeNode) tree.getLastSelectedPathComponent();
        if (node == null || !(node.getUserObject() instanceof DuckDbManager.SavedQuery sq)) return;

        JPopupMenu menu = new JPopupMenu();
        JMenuItem del = new JMenuItem("Delete \"" + sq.name() + "\"");
        del.addActionListener(ae -> {
            int ok = JOptionPane.showConfirmDialog(frame(),
                    "Delete \"" + sq.name() + "\"?", "Delete Query", JOptionPane.YES_NO_OPTION);
            if (ok != JOptionPane.YES_OPTION) return;
            new Thread(() -> {
                try {
                    db.deleteSavedQuery(sq.id());
                    refresh();
                } catch (Exception ex) {
                    SwingUtilities.invokeLater(() ->
                            JOptionPane.showMessageDialog(frame(), ex.getMessage(),
                                    "Delete Error", JOptionPane.ERROR_MESSAGE));
                }
            }, "DuckDuckBurp-delete-query").start();
        });
        menu.add(del);
        menu.show(tree, e.getX(), e.getY());
    }

    private void importQueries() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Import queries from JSON...");
        chooser.setFileFilter(new FileNameExtensionFilter("JSON files", "json"));
        if (chooser.showOpenDialog(frame()) != JFileChooser.APPROVE_OPTION) return;
        String path = chooser.getSelectedFile().getAbsolutePath().replace("'", "\\'");
        new Thread(() -> {
            try {
                db.importSavedQueries(path);
                refresh();
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() ->
                        JOptionPane.showMessageDialog(frame(), ex.getMessage(),
                                "Import Error", JOptionPane.ERROR_MESSAGE));
            }
        }, "DuckDuckBurp-import").start();
    }

    private void exportQueries() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Export saved queries as JSON...");
        chooser.setFileFilter(new FileNameExtensionFilter("JSON files", "json"));
        chooser.setSelectedFile(new File("duckduckburp-queries.json"));
        if (chooser.showSaveDialog(frame()) != JFileChooser.APPROVE_OPTION) return;
        String path = chooser.getSelectedFile().getAbsolutePath().replace("'", "\\'");
        new Thread(() -> {
            try {
                db.exportSavedQueries(path);
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() ->
                        JOptionPane.showMessageDialog(frame(), ex.getMessage(),
                                "Export Error", JOptionPane.ERROR_MESSAGE));
            }
        }, "DuckDuckBurp-export-queries").start();
    }
}
