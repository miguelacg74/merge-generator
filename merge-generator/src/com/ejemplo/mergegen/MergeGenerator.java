package com.generator.mergedml;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Convierte sentencias INSERT/UPDATE en sentencias MERGE de Oracle.
 *
 * Las sentencias de una misma tabla que comparten el mismo juego de columnas se
 * agrupan en un unico MERGE con varias filas en el USING (SELECT ... UNION ALL).
 */
public class MergeGenerator {

    private final MergeConfig config;
    private final List<String> warnings = new ArrayList<String>();

    public MergeGenerator(MergeConfig config) {
        this.config = config;
    }

    /** Avisos generados durante la ultima llamada a {@link #generate(List)}. */
    public List<String> getWarnings() {
        return warnings;
    }

    public String generate(List<DmlStatement> statements) {
        warnings.clear();
        StringBuilder out = new StringBuilder();

        if (config.isIncludeComments()) {
            out.append("-- MERGE generado a partir de ").append(statements.size())
                    .append(" sentencia(s) DML");
            out.append(" el ")
                    .append(new SimpleDateFormat("dd/MM/yyyy HH:mm:ss").format(new Date()));
            out.append('\n');
        }

        for (List<Row> group : groupRows(statements)) {
            String merge = buildMerge(group);
            if (merge != null) {
                out.append('\n').append(merge).append('\n');
            }
        }

        if (config.isIncludeCommit()) {
            out.append("\nCOMMIT;\n");
        }
        return out.toString();
    }

    // ------------------------------------------------------------------ filas

    /** Una fila del USING: columnas -> valores, mas la sentencia de origen. */
    private static final class Row {
        final String table;
        final List<String> columns;
        final List<String> values;
        final DmlStatement source;
        final boolean insertable;
        /** Columnas que solo vienen del WHERE de un UPDATE: identifican la fila, no se actualizan. */
        final List<String> filterColumns;

        Row(String table, List<String> columns, List<String> values,
            DmlStatement source, boolean insertable, List<String> filterColumns) {
            this.table = table;
            this.columns = columns;
            this.values = values;
            this.source = source;
            this.insertable = insertable;
            this.filterColumns = filterColumns;
        }

        String signature() {
            return table + "|" + insertable + "|" + String.join(",", columns);
        }
    }

    private List<List<Row>> groupRows(List<DmlStatement> statements) {
        Map<String, List<Row>> groups = new LinkedHashMap<String, List<Row>>();
        for (DmlStatement stmt : statements) {
            Row row = toRow(stmt);
            if (row == null) {
                continue;
            }
            String key = config.isGroupStatements()
                    ? row.signature()
                    : row.signature() + "#" + groups.size();
            List<Row> bucket = groups.get(key);
            if (bucket == null) {
                bucket = new ArrayList<Row>();
                groups.put(key, bucket);
            }
            bucket.add(row);
        }
        return new ArrayList<List<Row>>(groups.values());
    }

    private Row toRow(DmlStatement stmt) {
        if (stmt.getColumns().size() != stmt.getValues().size()) {
            warnings.add("Se ignora una sentencia de " + stmt.getTableName()
                    + ": el numero de columnas y valores no coincide.");
            return null;
        }
        if (stmt.getOperation() == DmlStatement.Operation.INSERT) {
            return new Row(stmt.getTableName(), stmt.getColumns(), stmt.getValues(), stmt, true,
                    new ArrayList<String>());
        }

        // UPDATE: las columnas del WHERE aportan los valores de la clave.
        List<String> columns = new ArrayList<String>(stmt.getColumns());
        List<String> values = new ArrayList<String>(stmt.getValues());
        Map<String, String> whereEquals = parseWhereEqualities(stmt.getWhereClause());
        if (whereEquals == null) {
            warnings.add("La clausula WHERE de un UPDATE sobre " + stmt.getTableName()
                    + " no es una lista de igualdades; el MERGE solo actualizara (sin INSERT).");
            whereEquals = new LinkedHashMap<String, String>();
        }
        List<String> filterColumns = new ArrayList<String>();
        for (Map.Entry<String, String> entry : whereEquals.entrySet()) {
            if (!containsIgnoreCase(columns, entry.getKey())) {
                columns.add(entry.getKey());
                values.add(entry.getValue());
                filterColumns.add(entry.getKey());
            }
        }
        boolean insertable = !whereEquals.isEmpty();
        return new Row(stmt.getTableName(), columns, values, stmt, insertable, filterColumns);
    }

    // ----------------------------------------------------------------- merge

    private String buildMerge(List<Row> rows) {
        if (rows.isEmpty()) {
            return null;
        }
        Row first = rows.get(0);
        String table = first.table;
        List<String> columns = first.columns;

        List<String> keys = resolveKeys(table, first);
        if (keys.isEmpty()) {
            warnings.add("No hay clave primaria definida para " + table
                    + "; se genera el MERGE con ON (1 = 0), revisalo antes de ejecutarlo.");
        }

        StringBuilder sb = new StringBuilder();
        if (config.isIncludeComments()) {
            sb.append("-- ").append(table).append(" (").append(rows.size())
                    .append(" fila(s), clave: ")
                    .append(keys.isEmpty() ? "SIN DEFINIR" : String.join(", ", keys))
                    .append(")\n");
        }

        sb.append("MERGE INTO ").append(table).append(" t\n");
        sb.append("USING (\n");
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            sb.append("  SELECT ");
            for (int c = 0; c < columns.size(); c++) {
                if (c > 0) {
                    sb.append(", ");
                }
                sb.append(valueFor(row, columns.get(c))).append(" AS ").append(columns.get(c));
            }
            sb.append(" FROM DUAL");
            if (i < rows.size() - 1) {
                sb.append(" UNION ALL");
            }
            sb.append('\n');
        }
        sb.append(") s\n");

        sb.append("ON (");
        if (keys.isEmpty()) {
            sb.append("1 = 0 /* TODO: define la clave */");
        } else {
            for (int i = 0; i < keys.size(); i++) {
                if (i > 0) {
                    sb.append(" AND ");
                }
                sb.append("t.").append(keys.get(i)).append(" = s.").append(keys.get(i));
            }
        }
        sb.append(")\n");

        List<String> updatable = new ArrayList<String>();
        for (String column : columns) {
            if (!containsIgnoreCase(keys, column) && !containsIgnoreCase(first.filterColumns, column)) {
                updatable.add(column);
            }
        }
        if (!updatable.isEmpty()) {
            sb.append("WHEN MATCHED THEN\n  UPDATE SET\n");
            for (int i = 0; i < updatable.size(); i++) {
                sb.append("    t.").append(updatable.get(i))
                        .append(" = s.").append(updatable.get(i));
                sb.append(i < updatable.size() - 1 ? ",\n" : "\n");
            }
        }

        if (first.insertable) {
            sb.append("WHEN NOT MATCHED THEN\n  INSERT (");
            sb.append(String.join(", ", columns));
            sb.append(")\n  VALUES (");
            for (int i = 0; i < columns.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append("s.").append(columns.get(i));
            }
            sb.append(")");
        }
        sb.append(";\n");
        return sb.toString();
    }

    /** Clave configurada para la tabla; si no hay, se intenta deducir del WHERE. */
    private List<String> resolveKeys(String table, Row row) {
        List<String> keys = config.getPrimaryKeys(table);
        List<String> present = new ArrayList<String>();
        for (String key : keys) {
            if (containsIgnoreCase(row.columns, key)) {
                present.add(key);
            } else {
                warnings.add("La clave " + key + " de " + table
                        + " no aparece en el DML; se omite de la condicion ON.");
            }
        }
        if (!present.isEmpty()) {
            return present;
        }
        if (row.source.getOperation() == DmlStatement.Operation.UPDATE) {
            Map<String, String> whereEquals = parseWhereEqualities(row.source.getWhereClause());
            if (whereEquals != null && !whereEquals.isEmpty()) {
                return new ArrayList<String>(whereEquals.keySet());
            }
        }
        return present;
    }

    private static String valueFor(Row row, String column) {
        for (int i = 0; i < row.columns.size(); i++) {
            if (row.columns.get(i).equalsIgnoreCase(column)) {
                return row.values.get(i);
            }
        }
        return "NULL";
    }

    /**
     * Convierte {@code COL1 = 1 AND COL2 = 'X'} en un mapa columna -> valor.
     * Devuelve null si el WHERE tiene algo que no sea una igualdad simple.
     */
    public static Map<String, String> parseWhereEqualities(String whereClause) {
        Map<String, String> result = new LinkedHashMap<String, String>();
        if (whereClause == null || whereClause.trim().isEmpty()) {
            return result;
        }
        for (String condition : splitByAnd(whereClause)) {
            String text = condition.trim();
            if (text.startsWith("(") && text.endsWith(")")) {
                text = text.substring(1, text.length() - 1).trim();
            }
            int eq = text.indexOf('=');
            if (eq <= 0) {
                return null;
            }
            String column = text.substring(0, eq).trim();
            String value = text.substring(eq + 1).trim();
            if (column.isEmpty() || value.isEmpty() || !isSimpleIdentifier(column)) {
                return null;
            }
            String plain = column.toUpperCase();
            int dot = plain.lastIndexOf('.');
            if (dot >= 0) {
                plain = plain.substring(dot + 1);
            }
            result.put(plain, value);
        }
        return result;
    }

    private static List<String> splitByAnd(String text) {
        List<String> parts = new ArrayList<String>();
        StringBuilder current = new StringBuilder();
        boolean inString = false;
        int depth = 0;
        String upper = text.toUpperCase();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                current.append(c);
                if (c == '\'') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '\'') {
                        current.append('\'');
                        i++;
                    } else {
                        inString = false;
                    }
                }
                continue;
            }
            if (c == '\'') {
                inString = true;
                current.append(c);
                continue;
            }
            if (c == '(') {
                depth++;
                current.append(c);
                continue;
            }
            if (c == ')') {
                depth = Math.max(0, depth - 1);
                current.append(c);
                continue;
            }
            if (depth == 0 && upper.startsWith("AND", i)
                    && isSpaceOrEdge(text, i - 1) && isSpaceOrEdge(text, i + 3)) {
                parts.add(current.toString());
                current.setLength(0);
                i += 2;
                continue;
            }
            current.append(c);
        }
        if (current.toString().trim().length() > 0) {
            parts.add(current.toString());
        }
        return parts;
    }

    private static boolean isSpaceOrEdge(String text, int index) {
        return index < 0 || index >= text.length() || Character.isWhitespace(text.charAt(index));
    }

    private static boolean isSimpleIdentifier(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '_' && c != '.' && c != '$' && c != '#' && c != '"') {
                return false;
            }
        }
        return !text.isEmpty();
    }

    private static boolean containsIgnoreCase(List<String> values, String target) {
        for (String value : values) {
            if (value.equalsIgnoreCase(target)) {
                return true;
            }
        }
        return false;
    }

    /** Tablas distintas presentes en las sentencias, en orden de aparicion. */
    public static Set<String> tablesOf(List<DmlStatement> statements) {
        Set<String> tables = new LinkedHashSet<String>();
        for (DmlStatement stmt : statements) {
            tables.add(stmt.getTableName());
        }
        return tables;
    }
}
