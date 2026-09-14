package com.generator.mergedml;

import java.awt.BorderLayout;
import java.sql.Connection;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingWorker;

import oracle.dbtools.raptor.utils.Connections;
import oracle.dbtools.raptor.utils.DBObject;
import oracle.ide.Context;
import oracle.ide.Ide;
import oracle.ide.controller.Controller;
import oracle.ide.controller.IdeAction;
import oracle.ide.model.Element;

/**
 * Accion del menu contextual del navegador de conexiones (clic derecho sobre
 * una tabla): lee las filas de la tabla seleccionada y abre el dialogo de
 * generacion de MERGE con los datos ya cargados.
 *
 * La lectura se hace en segundo plano para no congelar el IDE con tablas
 * grandes.
 */
public class TableMergeGenController implements Controller {

    private static final String TITLE = "Generar MERGE desde tabla";

    @Override
    public boolean handleEvent(IdeAction action, Context context) {
        DBObject table = tableOf(context);
        if (table == null) {
            JOptionPane.showMessageDialog(Ide.getMainWindow(),
                    "Selecciona una tabla en el navegador de conexiones.",
                    TITLE, JOptionPane.WARNING_MESSAGE);
            return true;
        }

        String schema = table.getSchemaName();
        String name = table.getObjectName();
        final String qualified = (schema == null || schema.trim().isEmpty())
                ? TableDataExtractor.quoteIfNeeded(name)
                : TableDataExtractor.quoteIfNeeded(schema) + "."
                        + TableDataExtractor.quoteIfNeeded(name);

        Object input = JOptionPane.showInputDialog(Ide.getMainWindow(),
                "Filas maximas a leer de " + qualified + ":",
                TITLE, JOptionPane.QUESTION_MESSAGE, null, null,
                TableDataExtractor.DEFAULT_MAX_ROWS);
        if (input == null) {
            return true;
        }
        final int maxRows;
        try {
            maxRows = Math.max(1, Integer.parseInt(input.toString().trim()));
        } catch (NumberFormatException e) {
            JOptionPane.showMessageDialog(Ide.getMainWindow(),
                    "Numero de filas no valido: " + input,
                    TITLE, JOptionPane.WARNING_MESSAGE);
            return true;
        }

        extractAndOpen(table, qualified, maxRows);
        return true;
    }

    @Override
    public boolean update(IdeAction action, Context context) {
        action.setEnabled(tableOf(context) != null);
        return true;
    }

    // ------------------------------------------------------------- extraccion

    private void extractAndOpen(final DBObject table, final String qualified,
                                final int maxRows) {
        final JDialog progress = new JDialog(Ide.getMainWindow(), TITLE, true);
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBorder(BorderFactory.createEmptyBorder(14, 18, 14, 18));
        panel.add(new JLabel("Leyendo datos de " + qualified + "..."), BorderLayout.NORTH);
        JProgressBar bar = new JProgressBar();
        bar.setIndeterminate(true);
        panel.add(bar, BorderLayout.CENTER);
        progress.setContentPane(panel);
        progress.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
        progress.pack();
        progress.setLocationRelativeTo(Ide.getMainWindow());

        SwingWorker<TableDataExtractor.Result, Void> worker =
                new SwingWorker<TableDataExtractor.Result, Void>() {
            private Connection connection;
            private TableDataExtractor.Result result;
            private Exception error;

            @Override
            protected TableDataExtractor.Result doInBackground() {
                try {
                    connection = connectionOf(table);
                    if (connection == null) {
                        throw new IllegalStateException(
                                "La conexion '" + table.getConnectionName()
                                        + "' no esta abierta.");
                    }
                    result = TableDataExtractor.extractAsInserts(connection, qualified,
                            maxRows);
                } catch (Exception e) {
                    error = e;
                }
                return result;
            }

            @Override
            protected void done() {
                progress.dispose();
                if (error != null) {
                    showError(error);
                    return;
                }
                if (result == null || result.getStatements().isEmpty()) {
                    JOptionPane.showMessageDialog(Ide.getMainWindow(),
                            "La tabla " + qualified + " no tiene filas.",
                            TITLE, JOptionPane.INFORMATION_MESSAGE);
                    return;
                }
                List<String> warnings = result.getWarnings();
                new MergeDialog(Ide.getMainWindow(), result.getStatements(), connection,
                        warnings).setVisible(true);
            }
        };
        worker.execute();
        progress.setVisible(true);
    }

    private static void showError(Exception e) {
        JOptionPane.showMessageDialog(Ide.getMainWindow(),
                "No se pudieron leer los datos de la tabla:\n" + e.getMessage(),
                TITLE, JOptionPane.ERROR_MESSAGE);
    }

    // ----------------------------------------------------------------- utiles

    /** El objeto de base de datos del contexto, solo si es una tabla. */
    private static DBObject tableOf(Context context) {
        try {
            Element element = context == null ? null : context.getElement();
            if (element == null) {
                return null;
            }
            DBObject obj = new DBObject(element);
            return "TABLE".equalsIgnoreCase(obj.getObjectType()) ? obj : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Conexion del objeto del navegador; si no esta abierta se intenta abrir. */
    private static Connection connectionOf(DBObject table) {
        try {
            Connection connection = table.getConnection();
            if (connection != null) {
                return connection;
            }
        } catch (Throwable ignored) {
            // se intenta por nombre de conexion
        }
        String name = table.getConnectionName();
        if (name == null || name.trim().isEmpty()) {
            return null;
        }
        try {
            return Connections.getInstance().getConnection(name);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
