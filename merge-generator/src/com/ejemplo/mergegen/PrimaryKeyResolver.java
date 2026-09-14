package com.generator.mergedml;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

/**
 * Lee la clave primaria de una tabla usando la conexion activa del worksheet.
 * Si no hay conexion, o la tabla no existe / no tiene PK, devuelve lista vacia
 * y el usuario indica la clave a mano.
 */
public final class PrimaryKeyResolver {

    private static final String SQL =
            "SELECT cc.column_name "
            + "FROM all_constraints c "
            + "JOIN all_cons_columns cc "
            + "  ON c.owner = cc.owner AND c.constraint_name = cc.constraint_name "
            + "WHERE c.constraint_type = 'P' "
            + "  AND c.table_name = ? "
            + "  AND c.owner = NVL(?, SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA')) "
            + "ORDER BY cc.position";

    private PrimaryKeyResolver() {
    }

    /**
     * @param qualifiedName nombre de tabla, opcionalmente con esquema (ESQUEMA.TABLA)
     * @return columnas de la clave primaria en orden, o lista vacia
     */
    public static List<String> primaryKeyOf(Connection connection, String qualifiedName) {
        List<String> columns = new ArrayList<String>();
        if (connection == null || qualifiedName == null || qualifiedName.trim().isEmpty()) {
            return columns;
        }
        String owner = null;
        String table = qualifiedName.trim().toUpperCase();
        int dot = table.lastIndexOf('.');
        if (dot > 0) {
            owner = unquote(table.substring(0, dot));
            table = table.substring(dot + 1);
        }
        table = unquote(table);

        PreparedStatement ps = null;
        ResultSet rs = null;
        try {
            ps = connection.prepareStatement(SQL);
            ps.setString(1, table);
            ps.setString(2, owner);
            rs = ps.executeQuery();
            while (rs.next()) {
                columns.add(rs.getString(1));
            }
        } catch (Exception e) {
            // Sin privilegios sobre el diccionario o conexion no Oracle: se pide a mano.
            columns.clear();
        } finally {
            close(rs);
            close(ps);
        }
        return columns;
    }

    private static String unquote(String text) {
        String value = text.trim();
        if (value.length() > 1 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static void close(AutoCloseable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (Exception ignored) {
                // sin accion
            }
        }
    }
}
