/*
 * Copyright 2012-2014, Luis Filipe da Cruz Nassif
 * 
 * This file is part of Indexador e Processador de Evidências Digitais (IPED).
 *
 * IPED is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * IPED is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with IPED.  If not, see <http://www.gnu.org/licenses/>.
 */
package dpf.sp.gpinf.indexer.desktop;

import java.awt.Component;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.text.Collator;
import java.util.List;

import javax.swing.JTable;
import javax.swing.RowSorter.SortKey;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dpf.sp.gpinf.indexer.search.IPEDSearcher;
import dpf.sp.gpinf.indexer.search.MultiSearchResult;
import iped3.IItem;
import iped3.IItemId;
import iped3.util.BasicProps;

public class ResultTableListener implements ListSelectionListener, MouseListener, KeyListener {

    public static boolean syncingSelectedItems = false;
    private static Logger logger = LoggerFactory.getLogger(ResultTableListener.class);
    private long lastKeyTime = -1;
    private String lastKeyString = ""; //$NON-NLS-1$
    private Collator collator = Collator.getInstance();

    public ResultTableListener() {
        collator.setStrength(Collator.PRIMARY);
    }

    @Override
    public void valueChanged(ListSelectionEvent evt) {

        GerenciadorMarcadores.updateCounters();

        if (App.get().resultsTable.getSelectedRowCount() == 0 || evt.getValueIsAdjusting()) {
            return;
        }

        int resultTableLeadSelIdx = App.get().resultsTable.getSelectionModel().getLeadSelectionIndex();
        Rectangle a = App.get().resultsTable.getCellRect(resultTableLeadSelIdx, 0, false);
        Rectangle b = App.get().resultsTable.getVisibleRect();
        a.setBounds(b.x, a.y, a.width, a.height);
        App.get().resultsTable.scrollRectToVisible(a);

        if (!syncingSelectedItems) {
            syncingSelectedItems = true;
            App.get().gallery.getDefaultEditor(GalleryCellRenderer.class).stopCellEditing();
            int galleryRow = resultTableLeadSelIdx / App.get().galleryModel.colCount;
            int galleyCol = resultTableLeadSelIdx % App.get().galleryModel.colCount;
            App.get().gallery.scrollRectToVisible(App.get().gallery.getCellRect(galleryRow, galleyCol, false));

            App.get().gallery.clearSelection();
            App.get().gallery.getSelectionModel().setValueIsAdjusting(true);
            int[] selRows = App.get().resultsTable.getSelectedRows();
            int start = 0;
            while (start < selRows.length) {
                int i = start + 1;
                while (i < selRows.length && selRows[i] - selRows[i - 1] == 1) {
                    i++;
                }
                App.get().gallery.setCellSelectionInterval(selRows[start], selRows[i - 1]);
                start = i;
            }
            App.get().gallery.getSelectionModel().setValueIsAdjusting(false);
            syncingSelectedItems = false;
        }

        processSelectedFile();

    }

    private synchronized void processSelectedFile() {

        // if(App.get().resultsTable.getSelectedRowCount() > 1)
        // return;
        int viewIndex = App.get().resultsTable.getSelectionModel().getLeadSelectionIndex();

        if (viewIndex != -1) {
            int modelIdx = App.get().resultsTable.convertRowIndexToModel(viewIndex);
            IItemId item = App.get().ipedResult.getItem(modelIdx);
            int docId = App.get().appCase.getLuceneId(item);
            if (docId != App.get().getParams().lastSelectedDoc) {

                App.get().hitsTable.scrollRectToVisible(new Rectangle());
                App.get().getTextViewer().textTable.scrollRectToVisible(new Rectangle());
                App.get().hitsDock.setTitleText(Messages.getString("AppListener.NoHits")); //$NON-NLS-1$
                App.get().subitemDock.setTitleText(Messages.getString("SubitemTableModel.Subitens")); //$NON-NLS-1$
                App.get().duplicateDock.setTitleText(Messages.getString("DuplicatesTableModel.Duplicates")); //$NON-NLS-1$
                App.get().parentDock.setTitleText(Messages.getString("ParentTableModel.ParentCount")); //$NON-NLS-1$

                FileProcessor parsingTask = new FileProcessor(docId, true);
                parsingTask.execute();
            }

        }

    }

    @Override
    public void mouseReleased(MouseEvent evt) {

        IItemId itemId = getSelectedItemId();
        if (evt.getClickCount() == 2) {
            int docId = App.get().appCase.getLuceneId(itemId);
            ExternalFileOpen.open(docId);

        } else if (evt.isPopupTrigger()) {
            showContextMenu(itemId, evt);

        } else {
            processSelectedFile();

        }

    }

    private IItemId getSelectedItemId() {
        int viewIndex = App.get().resultsTable.getSelectedRow();
        if (viewIndex != -1) {
            int modelIdx = App.get().resultsTable.convertRowIndexToModel(viewIndex);
            return App.get().ipedResult.getItem(modelIdx);
        }
        return null;
    }

    private void showContextMenu(IItemId itemId, MouseEvent evt) {
        IItem item = itemId == null ? null : App.get().appCase.getItemByItemId(itemId);
        new MenuClass(item).show((Component) evt.getSource(), evt.getX(), evt.getY());
    }

    @Override
    public void mouseClicked(MouseEvent e) {
    }

    @Override
    public void mouseEntered(MouseEvent arg0) {
    }

    @Override
    public void mouseExited(MouseEvent arg0) {
    }

    @Override
    public void mousePressed(MouseEvent evt) {
        // needed for Linux
        if (evt.isPopupTrigger()) {
            IItemId itemId = getSelectedItemId();
            showContextMenu(itemId, evt);
        }
    }

    @Override
    public void keyPressed(KeyEvent evt) {
    }

    @Override
    public void keyReleased(KeyEvent evt) {
        if (App.get().resultsTable.getSelectedRow() == -1) {
            return;
        }

        if (evt.getKeyCode() == KeyEvent.VK_C && ((evt.getModifiers() & KeyEvent.CTRL_MASK) != 0)) {

            int selCol = App.get().resultsTable.getSelectedColumn();
            if (selCol < 0) {
                return;
            }
            String value = getCell(App.get().resultsTable, App.get().resultsTable.getSelectedRow(), selCol);
            StringSelection selection = new StringSelection(value);
            Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
            clipboard.setContents(selection, selection);

        } else if (evt.getKeyCode() == KeyEvent.VK_SPACE)
            itemSelection();
        else if (evt.getKeyCode() == KeyEvent.VK_R && ((evt.getModifiers() & KeyEvent.CTRL_MASK) != 0)) // Shortcut to
                                                                                                        // Deep-Selection
                                                                                                        // (Item plus
                                                                                                        // sub-items)
            recursiveItemSelection(true);
        else if (evt.getKeyCode() == KeyEvent.VK_R && ((evt.getModifiers() & KeyEvent.ALT_MASK) != 0)) // Shortcut to
                                                                                                       // Deep-Selection
                                                                                                       // (Item plus
                                                                                                       // sub-items)
            recursiveItemSelection(false);
        else if (evt.getKeyCode() == KeyEvent.VK_B && ((evt.getModifiers() & KeyEvent.CTRL_MASK) != 0)) // Shortcut to
                                                                                                        // BookmarkManager
                                                                                                        // Window
            GerenciadorMarcadores.setVisible();
        else
            GerenciadorMarcadores.get().keyReleased(evt);

    }

    public void itemSelection() {
        int col = App.get().resultsTable.convertColumnIndexToView(1);
        int firstRow = App.get().resultsTable.getSelectedRow();
        boolean value = true;
        if (firstRow != -1 && (Boolean) App.get().resultsTable.getValueAt(firstRow, col)) {
            value = false;
        }

        MarcadoresController.get().setMultiSetting(true);
        App.get().resultsTable.setUpdateSelectionOnSort(false);
        int[] selectedRows = App.get().resultsTable.getSelectedRows();
        for (int i = 0; i < selectedRows.length; i++) {
            if (i == selectedRows.length - 1) {
                MarcadoresController.get().setMultiSetting(false);
                App.get().resultsTable.setUpdateSelectionOnSort(true);
            }
            App.get().resultsTable.setValueAt(value, selectedRows[i], col);
        }
    }

    public void recursiveItemSelection(boolean value) {
        int col = App.get().resultsTable.convertColumnIndexToView(1);
        MarcadoresController.get().setMultiSetting(true);
        App.get().resultsTable.setUpdateSelectionOnSort(false);
        int[] selectedRows = App.get().resultsTable.getSelectedRows();
        for (int i = 0; i < selectedRows.length; i++) {
            if (i == selectedRows.length - 1) {
                MarcadoresController.get().setMultiSetting(false);
                App.get().resultsTable.setUpdateSelectionOnSort(true);
            }
            App.get().resultsTable.setValueAt(value, selectedRows[i], col);

            int modelIndex = App.get().resultsTable.convertRowIndexToModel(selectedRows[i]);
            selectAllSubitems(value, App.get().ipedResult.getItem(modelIndex));
        }
        MarcadoresController.get().atualizarGUI();
        App.get().subItemTable.repaint();
    }

    /**
     * Perform selection of all subitems
     * 
     * @param state
     *            - which state to set true or false
     * @param rootID
     *            - parent of the selection
     */
    private void selectAllSubitems(boolean state, IItemId rootID) {
        try {
            IItem item = App.get().appCase.getItemByItemId(rootID);
            if (item.hasChildren() || item.isDir()) { // Filter subItems which have children or are directories.
                logger.debug("Searching items with evidenceUUID {} id {}", item.getDataSource().getUUID(),
                        item.getId());
                String query = BasicProps.EVIDENCE_UUID + ":" + item.getDataSource().getUUID() + " AND "
                        + BasicProps.PARENTIDs + ":" + rootID.getId();
                IPEDSearcher task = new IPEDSearcher(App.get().appCase, query);
                MultiSearchResult result = task.multiSearch();
                if (result.getLength() > 0) {
                    logger.debug("Found {} subitems of sourceId {} id {}", result.getLength(), rootID.getSourceId(),
                            rootID.getId());
                    for (IItemId subItem : result.getIterator()) {
                        App.get().appCase.getMultiMarcadores().setSelected((Boolean) state, subItem);
                    }
                }
            }

        } catch (Exception e) {
            logger.debug("Error :" + e.getLocalizedMessage());
            e.printStackTrace();
        }
    }

    /**
     * Add a simple "type-to-find" feature to the table.
     * It works on the currently sorted column, if it uses a String comparator,
     * so it will not work on numeric and date columns.
     */
    @Override
    public void keyTyped(KeyEvent evt) {
        char c = evt.getKeyChar();
        if (c == ' ' || (evt.getModifiers() & (InputEvent.CTRL_MASK | InputEvent.ALT_MASK | InputEvent.SHIFT_MASK)) != 0 ||
               c == KeyEvent.VK_TAB || c == KeyEvent.VK_ESCAPE || c == KeyEvent.VK_BACK_SPACE || c == KeyEvent.VK_DELETE)
            return;

        JTable table = App.get().resultsTable;
        List<? extends SortKey> sortKeys = table.getRowSorter().getSortKeys();
        if (sortKeys.isEmpty())
            return;

        int sortCol = sortKeys.get(0).getColumn();
        int viewCol = table.convertColumnIndexToView(sortCol);

        // Only works on String columns 
        if (!((RowComparator) ((ResultTableRowSorter) table.getRowSorter()).getComparator(sortCol))
                .isStringComparator())
            return;

        if (GerenciadorMarcadores.get().hasSingleKeyShortcut())
            return;
        
        long t = System.currentTimeMillis();
        if (t - lastKeyTime > 500)
            lastKeyString = ""; //$NON-NLS-1$

        lastKeyTime = t;
        if (lastKeyString.length() != 1 || lastKeyString.charAt(0) != c)
            lastKeyString += c;

        int initialRow = table.getSelectedRow();
        if (initialRow < 0)
            initialRow = table.getRowCount() - 1;

        int currRow = initialRow;
        int foundRow = findRow(table, currRow + 1, table.getRowCount() - 1, viewCol, lastKeyString);
        if (foundRow < 0)
            foundRow = findRow(table, 0, currRow - 1, viewCol, lastKeyString);
        if (foundRow >= 0) {
            table.setRowSelectionInterval(foundRow, foundRow);
            table.scrollRectToVisible(table.getCellRect(foundRow, viewCol, true));
        }
        evt.consume();
    }

    private int findRow(JTable table, int from, int to, int col, String search) {
        while (from < to) {
            int mid = (from + to) >> 1;
            int cmp = compare(getCell(table, mid, col), search);
            if (cmp > 0)
                to = mid - 1;
            else if (cmp < 0)
                from = mid + 1;
            else
                to = mid;
        }
        if (from == to && compare(getCell(table, from, col), search) == 0)
            return from;
        return -1;
    }

    private int compare(String a, String b) {
        if (a.isEmpty() && !b.isEmpty())
            return -1;
        if (a.length() > b.length())
            a = a.substring(0, b.length());
        return collator.compare(a, b);
    }

    private String getCell(JTable table, int row, int col) {
        String cell = table.getValueAt(row, col).toString();
        return cell.replace("<html><nobr>", "").replace("</html>", "") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                .replace(App.get().getParams().HIGHLIGHT_START_TAG, "")
                .replace(App.get().getParams().HIGHLIGHT_END_TAG, ""); //$NON-NLS-1$
    }

}