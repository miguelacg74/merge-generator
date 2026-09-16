package com.generator.mergedml;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.table.AbstractTableModel;

/**
 * Dialogo principal: muestra las tablas detectadas con su clave primaria
 * (precargada desde la base de datos cuando hay conexion), permite ajustarla y
 * genera el script MERGE.
 */
public class MergeDialog extends JDialog {

    private static final long serialVersionUID = 1L;

    private final List<DmlStatement> statements;
    private final List<String> initialWarnings;
    private final MergeConfig config = new MergeConfig();
    private final KeyTableModel keyModel;
    private final JTextArea output = new JTextArea();
    private final JCheckBox commitBox = new JCheckBox("Anadir COMMIT al final", true);
    private final JCheckBox commentBox = new JCheckBox("Anadir comentarios", true);
    private final JCheckBox groupBox = new JCheckBox("Agrupar filas por tabla", true);

    public MergeDialog(Frame owner, List<DmlStatement> statements, Connection connection) {
        this(owner, statements, connection, null);
    }

    /**
     * @param initialWarnings avisos previos (p.ej. de la extraccion de datos)
     *        que se muestran antes de los avisos del generador
     */
    public MergeDialog(Frame owner, List<DmlStatement> statements, Connection connection,
                       List<String> initialWarnings) {
        super(owner, "Generar MERGE desde DML", true);
        this.statements = statements;
        this.initialWarnings = initialWarnings == null
                ? new ArrayList<String>()
                : new ArrayList<String>(initialWarnings);

        boolean connected = connectionAvailable(connection);
        List<TableKey> keys = new ArrayList<TableKey>();
        for (String table : MergeGenerator.tablesOf(statements)) {
            List<String> pk = PrimaryKeyResolver.primaryKeyOf(connection, table);
            String origin;
            if (!pk.isEmpty()) {
                origin = "base de datos";
            } else {
                origin = connected ? "no encontrada" : "sin conexion";
            }
            keys.add(new TableKey(table, String.join(", ", pk), origin));
        }
        this.keyModel = new KeyTableModel(keys);

        buildUi();
        regenerate();
    }

    private void buildUi() {
        JPanel top = new JPanel(new BorderLayout(0, 4));
        top.setBorder(BorderFactory.createEmptyBorder(8, 8, 4, 8));
        top.add(new JLabel(statements.size() + " sentencia(s) DML, "
                + keyModel.getRowCount() + " tabla(s). Revisa la clave primaria de cada tabla:"),
                BorderLayout.NORTH);
        JTable table = new JTable(keyModel);
        table.setPreferredScrollableViewportSize(new Dimension(700, 110));
        top.add(new JScrollPane(table), BorderLayout.CENTER);

        JPanel options = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 2));
        options.add(commitBox);
        options.add(commentBox);
        options.add(groupBox);
        JButton generate = new JButton("Generar");
        options.add(generate);
        top.add(options, BorderLayout.SOUTH);

        output.setEditable(false);
        output.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JPanel bottom = new JPanel(new BorderLayout());
        bottom.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        bottom.add(new JScrollPane(output), BorderLayout.CENTER);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, top, bottom);
        split.setResizeWeight(0.35);

        JButton worksheet = new JButton("Abrir en hoja nueva");
        JButton clipboard = new JButton("Copiar");
        JButton close = new JButton("Cerrar");
        JPanel buttons = new JPanel();
        buttons.setLayout(new BoxLayout(buttons, BoxLayout.X_AXIS));
        buttons.setBorder(BorderFactory.createEmptyBorder(0, 8, 8, 8));
        buttons.add(Box.createHorizontalGlue());
        buttons.add(worksheet);
        buttons.add(Box.createHorizontalStrut(6));
        buttons.add(clipboard);
        buttons.add(Box.createHorizontalStrut(6));
        buttons.add(close);

        getContentPane().setLayout(new BorderLayout());
        getContentPane().add(split, BorderLayout.CENTER);
        getContentPane().add(buttons, BorderLayout.SOUTH);
        setSize(820, 620);
        setLocationRelativeTo(getOwner());

        ActionListener regenerate = new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                regenerate();
            }
        };
        generate.addActionListener(regenerate);
        commitBox.addActionListener(regenerate);
        commentBox.addActionListener(regenerate);
        groupBox.addActionListener(regenerate);

        clipboard.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                MergeOutput.copyToClipboard(output.getText());
            }
        });
        worksheet.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                try {
                    MergeOutput.openInNewWorksheet(output.getText());
                    dispose();
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(MergeDialog.this,
                            "No se pudo abrir la hoja nueva: " + ex.getMessage()
                                    + "\nEl script se copio al portapapeles.",
                            "Generar MERGE", JOptionPane.WARNING_MESSAGE);
                    MergeOutput.copyToClipboard(output.getText());
                }
            }
        });
        close.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                dispose();
            }
        });
    }

    private void regenerate() {
        config.setIncludeCommit(commitBox.isSelected());
        config.setIncludeComments(commentBox.isSelected());
        config.setGroupStatements(groupBox.isSelected());
        for (TableKey key : keyModel.rows) {
            config.setPrimaryKeys(key.table, splitColumns(key.columns));
        }

        MergeGenerator generator = new MergeGenerator(config);
        String script = generator.generate(statements);
        StringBuilder text = new StringBuilder();
        for (String warning : initialWarnings) {
            text.append("-- AVISO: ").append(warning).append('\n');
        }
        for (String warning : generator.getWarnings()) {
            text.append("-- AVISO: ").append(warning).append('\n');
        }
        text.append(script);
        output.setText(text.toString());
        output.setCaretPosition(0);
    }

    /** True si hay conexion usable para consultar la clave primaria. */
    private static boolean connectionAvailable(Connection connection) {
        try {
            return connection != null && !connection.isClosed();
        } catch (Exception e) {
            return false;
        }
    }

    private static List<String> splitColumns(String text) {
        List<String> columns = new ArrayList<String>();
        if (text != null) {
            for (String part : text.split(",")) {
                String column = part.trim();
                if (!column.isEmpty()) {
                    columns.add(column.toUpperCase());
                }
            }
        }
        return columns;
    }

    // ------------------------------------------------------------ modelo tabla

    private static final class TableKey {
        final String table;
        String columns;
        String origin;

        TableKey(String table, String columns, String origin) {
            this.table = table;
            this.columns = columns;
            this.origin = origin;
        }
    }

    private final class KeyTableModel extends AbstractTableModel {

        private static final long serialVersionUID = 1L;
        private final String[] titles = {"Tabla", "Clave primaria (separada por comas)", "Origen"};
        private final List<TableKey> rows;

        KeyTableModel(List<TableKey> rows) {
            this.rows = rows;
        }

        public int getRowCount() {
            return rows.size();
        }

        public int getColumnCount() {
            return titles.length;
        }

        @Override
        public String getColumnName(int column) {
            return titles[column];
        }

        public Object getValueAt(int row, int column) {
            TableKey key = rows.get(row);
            switch (column) {
                case 0: return key.table;
                case 1: return key.columns;
                default: return key.origin;
            }
        }

        @Override
        public boolean isCellEditable(int row, int column) {
            return column == 1;
        }

        @Override
        public void setValueAt(Object value, int row, int column) {
            if (column == 1) {
                TableKey key = rows.get(row);
                key.columns = value == null ? "" : value.toString();
                key.origin = "manual";
                fireTableRowsUpdated(row, row);
                regenerate();
            }
        }
    }
}
