package com.generator.mergedml;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
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
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.AbstractTableModel;

import com.generator.mergedml.TableDataExtractor.TableColumn;
import com.generator.mergedml.TableFilter.Condition;

/**
 * Dialogo previo a la extraccion de una tabla: permite armar el WHERE
 * eligiendo columna, operador y valor (o una expresion SQL cruda), pegar una
 * consulta SELECT personalizada cuyo resultado debe coincidir con las
 * columnas de la tabla destino, y decidir si se limita el numero de filas.
 *
 * La configuracion puede guardarse en un archivo preset (JSON) y recargarse
 * despues con los botones "Guardar preset" / "Cargar preset".
 *
 * Es Swing puro: la lista de columnas llega ya cargada por el constructor.
 */
public class TableFilterDialog extends JDialog {

    private static final long serialVersionUID = 1L;

    /** Ultima carpeta usada para presets (dura lo que dure la sesion del IDE). */
    private static File lastPresetDir;

    private final TableFilter filter = new TableFilter();
    private final ConditionsModel model = new ConditionsModel(filter);
    private final String qualifiedTable;
    private final List<TableColumn> columns;
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
    private final JCheckBox customBox =
            new JCheckBox("Usar consulta personalizada (SELECT / WITH)");
    private final JTextArea queryArea = new JTextArea(8, 60);
    private final JPanel cards = new JPanel(new CardLayout());
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
        this.qualifiedTable = qualifiedTable;
        this.columns = columns;
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

        JPanel modeRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        modeRow.setAlignmentX(LEFT_ALIGNMENT);
        modeRow.add(customBox);
        top.add(modeRow);

        cards.add(filterCard(), "filtros");
        cards.add(queryCard(), "consulta");

        JPanel bottom = new JPanel();
        bottom.setLayout(new BoxLayout(bottom, BoxLayout.Y_AXIS));
        bottom.setBorder(BorderFactory.createEmptyBorder(4, 8, 8, 8));

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
        preview.setBorder(BorderFactory.createTitledBorder("Resumen"));
        preview.setAlignmentX(LEFT_ALIGNMENT);
        preview.setMaximumSize(new Dimension(Integer.MAX_VALUE, 50));
        bottom.add(preview);
        bottom.add(Box.createVerticalStrut(8));

        JPanel presetRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        presetRow.setAlignmentX(LEFT_ALIGNMENT);
        JButton loadPreset = new JButton("Cargar preset...");
        JButton savePreset = new JButton("Guardar preset...");
        presetRow.add(new JLabel("Preset:"));
        presetRow.add(loadPreset);
        presetRow.add(savePreset);
        bottom.add(presetRow);
        bottom.add(Box.createVerticalStrut(4));

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.setAlignmentX(LEFT_ALIGNMENT);
        JButton ok = new JButton("Continuar");
        JButton cancel = new JButton("Cancelar");
        buttons.add(ok);
        buttons.add(cancel);
        bottom.add(buttons);

        getContentPane().setLayout(new BorderLayout());
        getContentPane().add(top, BorderLayout.NORTH);
        getContentPane().add(cards, BorderLayout.CENTER);
        getContentPane().add(bottom, BorderLayout.SOUTH);
        pack();
        setMinimumSize(new Dimension(620, getHeight()));
        setLocationRelativeTo(getOwner());

        customBox.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                showCard();
                updatePreview();
            }
        });
        operatorBox.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                boolean needsValue = needsValue();
                valueField.setEnabled(needsValue);
                expressionBox.setEnabled(needsValue);
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
        queryArea.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) {
                updatePreview();
            }

            public void removeUpdate(DocumentEvent e) {
                updatePreview();
            }

            public void changedUpdate(DocumentEvent e) {
                updatePreview();
            }
        });
        limitBox.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                rowsSpinner.setEnabled(limitBox.isSelected());
            }
        });
        loadPreset.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                loadPreset();
            }
        });
        savePreset.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                savePreset();
            }
        });
        ok.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                accept();
            }
        });
        cancel.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                dispose();
            }
        });
    }

    /** Panel del modo filtros: condiciones, tabla y condicion libre. */
    private JPanel filterCard() {
        JPanel card = new JPanel();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));

        JPanel addRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        addRow.setAlignmentX(LEFT_ALIGNMENT);
        addRow.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));
        addRow.add(columnBox);
        addRow.add(operatorBox);
        addRow.add(valueField);
        addRow.add(expressionBox);
        JButton add = new JButton("Agregar");
        add.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                addCondition();
            }
        });
        addRow.add(add);
        card.add(addRow);

        JLabel hint = new JLabel("Fechas: YYYY-MM-DD [HH:MI:SS]. "
                + "Marca 'expresion SQL' para funciones (SYSDATE, TO_DATE(...), ...).");
        hint.setAlignmentX(LEFT_ALIGNMENT);
        hint.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));
        hint.setFont(hint.getFont().deriveFont(Font.PLAIN, 11f));
        card.add(hint);

        JTable table = new JTable(model);
        table.setPreferredScrollableViewportSize(new Dimension(640, 110));
        JPanel middle = new JPanel(new BorderLayout(0, 4));
        middle.setAlignmentX(LEFT_ALIGNMENT);
        middle.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        middle.add(new JScrollPane(table), BorderLayout.CENTER);
        JButton remove = new JButton("Quitar condicion");
        remove.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                removeCondition(table);
            }
        });
        JPanel removeRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        removeRow.add(remove);
        middle.add(removeRow, BorderLayout.SOUTH);
        card.add(middle);

        JPanel extraRow = new JPanel(new BorderLayout(6, 0));
        extraRow.setAlignmentX(LEFT_ALIGNMENT);
        extraRow.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));
        extraRow.add(new JLabel("Otra condicion (SQL, opcional):"), BorderLayout.WEST);
        extraRow.add(extraField, BorderLayout.CENTER);
        card.add(extraRow);

        return card;
    }

    /** Panel del modo consulta personalizada. */
    private JPanel queryCard() {
        JPanel card = new JPanel(new BorderLayout(0, 4));
        card.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));

        JLabel hint = new JLabel("<html>Pega una consulta SELECT (o WITH ... SELECT)."
                + " Cada columna del resultado debe coincidir con una columna de la"
                + " tabla destino; usa alias (expresion AS COLUMNA) para expresiones."
                + " Puede devolver un subconjunto de columnas.</html>");
        hint.setFont(hint.getFont().deriveFont(Font.PLAIN, 11f));
        card.add(hint, BorderLayout.NORTH);

        queryArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        queryArea.setLineWrap(true);
        card.add(new JScrollPane(queryArea), BorderLayout.CENTER);
        return card;
    }

    private void showCard() {
        ((CardLayout) cards.getLayout()).show(cards,
                customBox.isSelected() ? "consulta" : "filtros");
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

    private void removeCondition(JTable table) {
        int row = table.getSelectedRow();
        if (row < 0) {
            JOptionPane.showMessageDialog(this,
                    "Selecciona la condicion a quitar.",
                    "Filtrar datos", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        filter.removeCondition(row);
        model.fireTableRowsDeleted(row);
        updatePreview();
    }

    private void extraChanged() {
        filter.setExtraCondition(extraField.getText());
        updatePreview();
    }

    private void updatePreview() {
        if (customBox.isSelected()) {
            String query = queryArea.getText().trim();
            preview.setText(query.isEmpty() ? "(consulta personalizada vacia)"
                    : query);
        } else {
            String where = filter.whereClause();
            preview.setText(where.isEmpty()
                    ? "(sin filtro: se lee toda la tabla)" : "WHERE " + where);
        }
        preview.setCaretPosition(0);
    }

    /** Vuelca el estado de la interfaz al filtro antes de aceptar/guardar. */
    private void syncFilter() {
        filter.setExtraCondition(extraField.getText());
        filter.setLimitEnabled(limitBox.isSelected());
        filter.setMaxRows(((Number) rowsSpinner.getValue()).intValue());
        filter.setCustomQuery(queryArea.getText());
        filter.setUseCustomQuery(customBox.isSelected());
    }

    private void accept() {
        if (customBox.isSelected() && queryArea.getText().trim().isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "La consulta personalizada esta vacia.",
                    "Filtrar datos", JOptionPane.WARNING_MESSAGE);
            return;
        }
        syncFilter();
        accepted = true;
        dispose();
    }

    // ---------------------------------------------------------------- preset

    private void savePreset() {
        JFileChooser chooser = presetChooser();
        chooser.setSelectedFile(new File(presetDir(), suggestedName()));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File file = withJsonExtension(chooser.getSelectedFile());
        if (file.exists()
                && JOptionPane.showConfirmDialog(this,
                        "El archivo " + file.getName() + " ya existe. Sobrescribir?",
                        "Guardar preset", JOptionPane.YES_NO_OPTION)
                        != JOptionPane.YES_OPTION) {
            return;
        }
        syncFilter();
        try {
            FilterPreset.of(qualifiedTable, filter).save(file);
            lastPresetDir = file.getParentFile();
            JOptionPane.showMessageDialog(this,
                    "Preset guardado en:\n" + file.getAbsolutePath(),
                    "Guardar preset", JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this,
                    "No se pudo guardar el preset:\n" + e.getMessage(),
                    "Guardar preset", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void loadPreset() {
        JFileChooser chooser = presetChooser();
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File file = chooser.getSelectedFile();
        FilterPreset preset;
        try {
            preset = FilterPreset.load(file);
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this,
                    "No se pudo leer el preset:\n" + e.getMessage(),
                    "Cargar preset", JOptionPane.ERROR_MESSAGE);
            return;
        }
        lastPresetDir = file.getParentFile();

        if (!preset.getTable().isEmpty()
                && !preset.getTable().equalsIgnoreCase(qualifiedTable)
                && JOptionPane.showConfirmDialog(this,
                        "El preset es de la tabla " + preset.getTable()
                                + ", distinta de " + qualifiedTable + ".\n"
                                + "Cargarlo de todos modos?",
                        "Cargar preset", JOptionPane.YES_NO_OPTION)
                        != JOptionPane.YES_OPTION) {
            return;
        }

        List<String> warnings = new ArrayList<String>();
        applyFilter(preset.toFilter(columns, warnings));
        if (!warnings.isEmpty()) {
            StringBuilder message = new StringBuilder(
                    "El preset se cargo con avisos:\n");
            for (String warning : warnings) {
                message.append("- ").append(warning).append('\n');
            }
            JOptionPane.showMessageDialog(this, message.toString(),
                    "Cargar preset", JOptionPane.WARNING_MESSAGE);
        }
    }

    /** Aplica un filtro cargado de preset a todos los controles. */
    private void applyFilter(TableFilter loaded) {
        filter.getConditions().clear();
        for (Condition condition : loaded.getConditions()) {
            filter.addCondition(condition);
        }
        model.fireTableDataChanged();
        extraField.setText(loaded.getExtraCondition());
        limitBox.setSelected(loaded.isLimitEnabled());
        rowsSpinner.setValue(loaded.getMaxRows());
        rowsSpinner.setEnabled(loaded.isLimitEnabled());
        queryArea.setText(loaded.getCustomQuery());
        customBox.setSelected(loaded.isUseCustomQuery());
        showCard();
        updatePreview();
    }

    private JFileChooser presetChooser() {
        JFileChooser chooser = new JFileChooser(presetDir());
        chooser.setFileFilter(new FileNameExtensionFilter(
                "Preset MERGE (*.json)", "json"));
        return chooser;
    }

    private static File presetDir() {
        if (lastPresetDir != null && lastPresetDir.isDirectory()) {
            return lastPresetDir;
        }
        File dir = new File(System.getProperty("user.home"),
                ".merge-generator" + File.separator + "presets");
        return dir.isDirectory() ? dir
                : new File(System.getProperty("user.home"));
    }

    /** Nombre de archivo sugerido: la tabla destino, sanitizada. */
    private String suggestedName() {
        return qualifiedTable.replaceAll("[^A-Za-z0-9._-]+", "_")
                + ".merge.json";
    }

    private static File withJsonExtension(File file) {
        return file.getName().toLowerCase().endsWith(".json") ? file
                : new File(file.getParentFile(), file.getName() + ".json");
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
