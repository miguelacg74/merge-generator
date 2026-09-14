package com.generator.mergedml;

import java.awt.Component;
import java.awt.KeyboardFocusManager;
import java.awt.datatransfer.DataFlavor;
import java.awt.Toolkit;
import java.sql.Connection;
import java.util.List;

import javax.swing.JOptionPane;
import javax.swing.text.JTextComponent;

import oracle.dbtools.worksheet.editor.Worksheet;
import oracle.ide.Context;
import oracle.ide.Ide;
import oracle.ide.controller.Controller;
import oracle.ide.controller.IdeAction;
import oracle.javatools.editor.BasicEditorPane;

/**
 * Accion "Generar MERGE desde DML": toma el SQL seleccionado en la hoja de
 * trabajo (o todo su contenido si no hay seleccion) y abre el dialogo de
 * generacion.
 */
public class MergeGenController implements Controller {

    private static final String TITLE = "Generar MERGE";

    @Override
    public boolean handleEvent(IdeAction action, Context context) {
        Worksheet worksheet = worksheetOf(context);
        String sql = sqlFrom(worksheet);

        if (sql == null || sql.trim().isEmpty()) {
            JOptionPane.showMessageDialog(Ide.getMainWindow(),
                    "No hay SQL que procesar.\nAbre una hoja de trabajo con sentencias "
                            + "INSERT/UPDATE (o selecciona el texto) y vuelve a intentarlo.",
                    TITLE, JOptionPane.WARNING_MESSAGE);
            return true;
        }

        List<DmlStatement> statements = DmlParser.parseScript(sql);
        if (statements.isEmpty()) {
            JOptionPane.showMessageDialog(Ide.getMainWindow(),
                    "No se encontraron sentencias INSERT o UPDATE validas en el texto.\n"
                            + "Recuerda que los INSERT deben llevar la lista de columnas y que "
                            + "INSERT ... SELECT no esta soportado.",
                    TITLE, JOptionPane.WARNING_MESSAGE);
            return true;
        }

        MergeDialog dialog = new MergeDialog(Ide.getMainWindow(), statements,
                connectionOf(worksheet));
        dialog.setVisible(true);
        return true;
    }

    @Override
    public boolean update(IdeAction action, Context context) {
        action.setEnabled(true);
        return true;
    }

    private static Worksheet worksheetOf(Context context) {
        try {
            return context == null ? null : Worksheet.getWorksheetFromContext(context);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Connection connectionOf(Worksheet worksheet) {
        if (worksheet == null) {
            return null;
        }
        try {
            return worksheet.getConnection();
        } catch (Throwable ignored) {
            // hoja sin conexion asignada: se pediran las claves a mano
            return null;
        }
    }

    /** Texto seleccionado (o completo) de la hoja, del editor con foco o del portapapeles. */
    private static String sqlFrom(Worksheet worksheet) {
        if (worksheet != null) {
            try {
                BasicEditorPane pane = worksheet.getFocusedEditorPane();
                String text = textOf(pane);
                if (text != null) {
                    return text;
                }
            } catch (Throwable ignored) {
                // se intenta con el componente enfocado
            }
        }
        Component focused = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        if (focused instanceof JTextComponent) {
            String text = textOf((JTextComponent) focused);
            if (text != null) {
                return text;
            }
        }
        return clipboardText();
    }

    private static String textOf(JTextComponent component) {
        if (component == null) {
            return null;
        }
        String selected = component.getSelectedText();
        if (selected != null && !selected.trim().isEmpty()) {
            return selected;
        }
        String all = component.getText();
        return (all != null && !all.trim().isEmpty()) ? all : null;
    }

    private static String clipboardText() {
        try {
            Object data = Toolkit.getDefaultToolkit().getSystemClipboard()
                    .getData(DataFlavor.stringFlavor);
            return data == null ? null : data.toString();
        } catch (Throwable ignored) {
            return null;
        }
    }
}
