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

        loadColumnsAndAsk(table, qualified);
        return true;
    }

    @Override
    public boolean update(IdeAction action, Context context) {
        action.setEnabled(tableOf(context) != null);
        return true;
    }

    // ------------------------------------------------------------- extraccion

    /**
     * Fase 1: abre la conexion y lee las columnas de la tabla en segundo
     * plano; al terminar muestra el dialogo de filtros. Si el usuario lo
     * acepta, arranca la extraccion (fase 2).
     */
    private void loadColumnsAndAsk(final DBObject table, final String qualified) {
        final JDialog progress = progressDialog(
                "Leyendo columnas de " + qualified + "...");

        SwingWorker<List<TableDataExtractor.TableColumn>, Void> worker =
                new SwingWorker<List<TableDataExtractor.TableColumn>, Void>() {
            private List<TableDataExtractor.TableColumn> columns;
            private Exception error;

            @Override
            protected List<TableDataExtractor.TableColumn> doInBackground() {
                try {
                    Connection connection = connectionOf(table);
                    if (connection == null) {
                        throw new IllegalStateException(
                                "La conexion '" + table.getConnectionName()
                                        + "' no esta abierta.");
                    }
                    columns = TableDataExtractor.columnsOf(connection, qualified);
                } catch (Exception e) {
                    error = e;
                }
                return columns;
            }

            @Override
            protected void done() {
                progress.dispose();
                if (error != null) {
                    showError(error, "SELECT * FROM " + qualified + " WHERE 1 = 0");
                    return;
                }
                TableFilter filter = TableFilterDialog.ask(Ide.getMainWindow(),
                        qualified, columns, table.getConnectionName());
                if (filter != null) {
                    extractAndOpen(table, qualified, filter);
                }
            }
        };
        worker.execute();
        progress.setVisible(true);
    }

    /**
     * Fase 2: vuelve a resolver la conexion (la de la fase 1 puede haberse
     * cerrado mientras el dialogo de filtros estuvo abierto), lee las filas
     * con el filtro elegido y abre el MergeDialog.
     */
    private void extractAndOpen(final DBObject table, final String qualified,
                                final TableFilter filter) {
        final JDialog progress = progressDialog("Leyendo datos de " + qualified + "...");

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
                            filter);
                } catch (Exception e) {
                    error = e;
                }
                return result;
            }

            @Override
            protected void done() {
                progress.dispose();
                String where = filter.whereClause();
                String sql = "SELECT * FROM " + qualified
                        + (where.isEmpty() ? "" : " WHERE " + where);
                if (error != null) {
                    showError(error, sql);
                    return;
                }
                if (result == null || result.getStatements().isEmpty()) {
                    JOptionPane.showMessageDialog(Ide.getMainWindow(),
                            "La tabla " + qualified + " no devolvio filas con ese filtro.",
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

    private static JDialog progressDialog(String message) {
        JDialog progress = new JDialog(Ide.getMainWindow(), TITLE, true);
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBorder(BorderFactory.createEmptyBorder(14, 18, 14, 18));
        panel.add(new JLabel(message), BorderLayout.NORTH);
        JProgressBar bar = new JProgressBar();
        bar.setIndeterminate(true);
        panel.add(bar, BorderLayout.CENTER);
        progress.setContentPane(panel);
        progress.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
        progress.pack();
        progress.setLocationRelativeTo(Ide.getMainWindow());
        return progress;
    }

    private static void showError(Exception e, String sql) {
        JOptionPane.showMessageDialog(Ide.getMainWindow(),
                "No se pudieron leer los datos de la tabla:\n" + e.getMessage()
                        + (sql == null ? "" : "\n\nConsulta ejecutada:\n" + sql),
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
            if (connection != null && !connection.isClosed()) {
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
