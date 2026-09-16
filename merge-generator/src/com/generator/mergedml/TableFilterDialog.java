package com.generator.mergedml;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;

import com.generator.mergedml.TableDataExtractor.TableColumn;
import com.generator.mergedml.TableFilter.Condition;

/**
 * Dialogo de filtros previo a la extraccion de una tabla: permite armar el
 * WHERE eligiendo columna, operador y valor (o una expresion SQL cruda), y
 * decidir si se limita el numero de filas leidas.
 *
 * Es Swing puro: la lista de columnas llega ya cargada por el constructor.
 */
public class TableFilterDialog extends JDialog {

    private static final long serialVersionUID = 1L;

    private final TableFilter filter = new TableFilter();
    private final ConditionsModel model = new ConditionsModel(filter);
    private final JComboBox<TableColumn> columnBox;
    private final JComboBox<String> operatorBox =
            new JComboBox<String>(TableFilter.OPERATORS);
    private final JTextField valueField = new JTextField(14);
    private final JCheckBox expressionBox = new JCheckBox("expresion SQL");
    private final JTextField extraField = new JTextField();
    private final JCheckBox limitBox = new JCheckBox("Limitar a", true);
    private final JSpinner rowsSpinner = new JSpinner(
            new SpinnerNumberModel(TableDataExtractor.DEFAULT_MAX_ROWS, 1,
                    1000000000, 1000));
    private final JTextArea preview = new JTextArea(2, 40);
    private boolean accepted;

    /**
     * Muestra el dialogo modal y devuelve el filtro elegido, o null si el
     * usuario cancelo.
     *
     * @param connectionName nombre de la conexion del navegador, solo para
     *        mostrarla (puede ser null)
     */
    public static TableFilter ask(Frame owner, String qualifiedTable,
                                  List<TableColumn> columns, String connectionName) {
        TableFilterDialog dialog = new TableFilterDialog(owner, qualifiedTable,
                columns, connectionName);
        dialog.setVisible(true);
        return dialog.accepted ? dialog.filter : null;
    }

    private TableFilterDialog(Frame owner, String qualifiedTable,
                              List<TableColumn> columns, String connectionName) {
        super(owner, "Filtrar datos de la tabla", true);
        this.columnBox = new JComboBox<TableColumn>(
                columns.toArray(new TableColumn[0]));
        buildUi(qualifiedTable, connectionName);
        updatePreview();
    }

    private void buildUi(String qualifiedTable, String connectionName) {
        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.setBorder(BorderFactory.createEmptyBorder(8, 8, 4, 8));

        String where = "Tabla: " + qualifiedTable
                + (connectionName == null || connectionName.trim().isEmpty()
                        ? "" : "   |   Conexion: " + connectionName);
        JLabel title = new JLabel(where + "  -  agrega las condiciones que necesites:");
        title.setAlignmentX(LEFT_ALIGNMENT);
        top.add(title);
        top.add(Box.createVerticalStrut(6));

        JPanel addRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        addRow.setAlignmentX(LEFT_ALIGNMENT);
        addRow.add(columnBox);
        addRow.add(operatorBox);
        addRow.add(valueField);
        addRow.add(expressionBox);
        JButton add = new JButton("Agregar");
        addRow.add(add);
        top.add(addRow);

        JLabel hint = new JLabel("Fechas: YYYY-MM-DD [HH:MI:SS]. "
                + "Marca 'expresion SQL' para funciones (SYSDATE, TO_DATE(...), ...).");
        hint.setAlignmentX(LEFT_ALIGNMENT);
        hint.setFont(hint.getFont().deriveFont(Font.PLAIN, 11f));
        top.add(hint);

        JTable table = new JTable(model);
        table.setPreferredScrollableViewportSize(new Dimension(640, 110));
        JPanel middle = new JPanel(new BorderLayout(0, 4));
        middle.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        middle.add(new JScrollPane(table), BorderLayout.CENTER);
        JButton remove = new JButton("Quitar condicion");
        JPanel removeRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        removeRow.add(remove);
        middle.add(removeRow, BorderLayout.SOUTH);

        JPanel bottom = new JPanel();
        bottom.setLayout(new BoxLayout(bottom, BoxLayout.Y_AXIS));
        bottom.setBorder(BorderFactory.createEmptyBorder(4, 8, 8, 8));

        JPanel extraRow = new JPanel(new BorderLayout(6, 0));
        extraRow.setAlignmentX(LEFT_ALIGNMENT);
        extraRow.add(new JLabel("Otra condicion (SQL, opcional):"), BorderLayout.WEST);
        extraRow.add(extraField, BorderLayout.CENTER);
        bottom.add(extraRow);
        bottom.add(Box.createVerticalStrut(6));

        JPanel limitRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        limitRow.setAlignmentX(LEFT_ALIGNMENT);
        rowsSpinner.setEditor(new JSpinner.NumberEditor(rowsSpinner, "#"));
        limitRow.add(limitBox);
        limitRow.add(rowsSpinner);
        limitRow.add(new JLabel("filas (desmarcar para leer la tabla completa)"));
        bottom.add(limitRow);
        bottom.add(Box.createVerticalStrut(6));

        preview.setEditable(false);
        preview.setLineWrap(true);
        preview.setWrapStyleWord(true);
        preview.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        preview.setBorder(BorderFactory.createTitledBorder("Filtro"));
        preview.setAlignmentX(LEFT_ALIGNMENT);
        preview.setMaximumSize(new Dimension(Integer.MAX_VALUE, 50));
        bottom.add(preview);
        bottom.add(Box.createVerticalStrut(8));

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.setAlignmentX(LEFT_ALIGNMENT);
        JButton ok = new JButton("Continuar");
        JButton cancel = new JButton("Cancelar");
        buttons.add(ok);
        buttons.add(cancel);
        bottom.add(buttons);

        getContentPane().setLayout(new BorderLayout());
        getContentPane().add(top, BorderLayout.NORTH);
        getContentPane().add(middle, BorderLayout.CENTER);
        getContentPane().add(bottom, BorderLayout.SOUTH);
        pack();
        setMinimumSize(new Dimension(620, getHeight()));
        setLocationRelativeTo(getOwner());

        operatorBox.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                boolean needsValue = needsValue();
                valueField.setEnabled(needsValue);
                expressionBox.setEnabled(needsValue);
            }
        });
        add.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                addCondition();
            }
        });
        remove.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                int row = table.getSelectedRow();
                if (row < 0) {
                    JOptionPane.showMessageDialog(TableFilterDialog.this,
                            "Selecciona la condicion a quitar.",
                            "Filtrar datos", JOptionPane.INFORMATION_MESSAGE);
                    return;
                }
                filter.removeCondition(row);
                model.fireTableRowsDeleted(row);
                updatePreview();
            }
        });
        extraField.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) {
                extraChanged();
            }

            public void removeUpdate(DocumentEvent e) {
                extraChanged();
            }

            public void changedUpdate(DocumentEvent e) {
                extraChanged();
            }
        });
        limitBox.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                rowsSpinner.setEnabled(limitBox.isSelected());
            }
        });
        ok.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                filter.setExtraCondition(extraField.getText());
                filter.setLimitEnabled(limitBox.isSelected());
                filter.setMaxRows(((Number) rowsSpinner.getValue()).intValue());
                accepted = true;
                dispose();
            }
        });
        cancel.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                dispose();
            }
        });
    }

    private boolean needsValue() {
        Object operator = operatorBox.getSelectedItem();
        return !("IS NULL".equals(operator) || "IS NOT NULL".equals(operator));
    }

    private void addCondition() {
        String value = valueField.getText().trim();
        if (needsValue() && value.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "Indica un valor para la condicion.",
                    "Filtrar datos", JOptionPane.WARNING_MESSAGE);
            return;
        }
        filter.addCondition(new Condition(
                (TableColumn) columnBox.getSelectedItem(),
                (String) operatorBox.getSelectedItem(),
                value, expressionBox.isSelected()));
        int row = filter.getConditions().size() - 1;
        model.fireTableRowsInserted(row);
        valueField.setText("");
        updatePreview();
    }

    private void extraChanged() {
        filter.setExtraCondition(extraField.getText());
        updatePreview();
    }

    private void updatePreview() {
        String where = filter.whereClause();
        preview.setText(where.isEmpty() ? "(sin filtro: se lee toda la tabla)"
                : "WHERE " + where);
        preview.setCaretPosition(0);
    }

    // ------------------------------------------------------------ modelo tabla

    private static final class ConditionsModel extends AbstractTableModel {

        private static final long serialVersionUID = 1L;
        private final String[] titles = {"Columna", "Operador", "Valor"};
        private final TableFilter filter;

        ConditionsModel(TableFilter filter) {
            this.filter = filter;
        }

        public int getRowCount() {
            return filter.getConditions().size();
        }

        public int getColumnCount() {
            return titles.length;
        }

        @Override
        public String getColumnName(int column) {
            return titles[column];
        }

        public Object getValueAt(int row, int column) {
            Condition condition = filter.getConditions().get(row);
            switch (column) {
                case 0: return condition.getColumn().getName();
                case 1: return condition.getOperator();
                default:
                    return condition.getValue()
                            + (condition.isExpression() ? "  (SQL)" : "");
            }
        }

        void fireTableRowsInserted(int row) {
            super.fireTableRowsInserted(row, row);
        }

        void fireTableRowsDeleted(int row) {
            super.fireTableRowsDeleted(row, row);
        }
    }
}
