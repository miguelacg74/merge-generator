package com.generator.mergedml;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Lee las filas de una tabla con JDBC y las convierte en sentencias INSERT
 * (modelo {@link DmlStatement}) listas para alimentar al
 * {@link MergeGenerator}: seleccionar una tabla en el navegador basta para
 * generar el MERGE con todos sus datos.
 *
 * Los valores se vuelcan como literales SQL segun el tipo de columna
 * (TO_DATE/TO_TIMESTAMP, HEXTORAW, literales N'...', etc.). Los tipos que no
 * caben en un literal (BLOB, INTERVAL, tipos objeto) se emiten como NULL y se
 * notifica en los avisos.
 */
public final class TableDataExtractor {

    /** Limite de filas por defecto cuando el usuario no indica otro. */
    public static final int DEFAULT_MAX_ROWS = 10000;

    private static final int FETCH_SIZE = 500;
    private static final int MAX_SQL_LITERAL = 4000;
    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** Codigos de tipo propios de Oracle no cubiertos por java.sql.Types. */
    private static final int ORA_TIMESTAMP_TZ = -101;
    private static final int ORA_TIMESTAMP_LTZ = -102;
    private static final int ORA_BINARY_FLOAT = 100;
    private static final int ORA_BINARY_DOUBLE = 101;

    private TableDataExtractor() {
    }

    /** Columna de la tabla leida de la metadata del ResultSet. */
    public static final class TableColumn {

        private final String name;
        private final int sqlType;
        private final String typeName;

        public TableColumn(String name, int sqlType, String typeName) {
            this.name = name;
            this.sqlType = sqlType;
            this.typeName = typeName;
        }

        public String getName() {
            return name;
        }

        /** Nombre listo para SQL: entrecomillado si hace falta. */
        public String quotedName() {
            return quoteIfNeeded(name);
        }

        public int getSqlType() {
            return sqlType;
        }

        public String getTypeName() {
            return typeName;
        }

        @Override
        public String toString() {
            return name + " (" + typeName + ')';
        }
    }

    /**
     * Devuelve las columnas de la tabla (nombre y tipo) consultando la
     * metadata de un {@code SELECT * ... WHERE 1 = 0}: no necesita permisos
     * sobre el diccionario de datos y es instantaneo.
     */
    public static List<TableColumn> columnsOf(Connection connection,
                                              String qualifiedTable) throws SQLException {
        Statement st = connection.createStatement();
        try {
            ResultSet rs = st.executeQuery(
                    "SELECT * FROM " + qualifiedTable + " WHERE 1 = 0");
            try {
                ResultSetMetaData md = rs.getMetaData();
                List<TableColumn> columns = new ArrayList<TableColumn>();
                for (int i = 1; i <= md.getColumnCount(); i++) {
                    columns.add(new TableColumn(md.getColumnLabel(i),
                            md.getColumnType(i), md.getColumnTypeName(i)));
                }
                return columns;
            } finally {
                rs.close();
            }
        } finally {
            st.close();
        }
    }

    /** Resultado de la extraccion: sentencias, avisos y flag de truncado. */
    public static final class Result {

        private final List<DmlStatement> statements;
        private final List<String> warnings;
        private final boolean truncated;
        private final String sql;

        private Result(List<DmlStatement> statements, List<String> warnings,
                       boolean truncated, String sql) {
            this.statements = statements;
            this.warnings = warnings;
            this.truncated = truncated;
            this.sql = sql;
        }

        public List<DmlStatement> getStatements() {
            return statements;
        }

        public List<String> getWarnings() {
            return warnings;
        }

        public boolean isTruncated() {
            return truncated;
        }

        /** Consulta SELECT ejecutada contra la base de datos. */
        public String getSql() {
            return sql;
        }
    }

    /**
     * Ejecuta {@code SELECT * FROM tabla} con limite de filas y devuelve un
     * INSERT por fila. Equivale a un {@link TableFilter} sin condiciones.
     *
     * @param qualifiedTable nombre cualificado ({@code ESQUEMA.TABLA}); usa
     *        {@link #quoteIfNeeded(String)} para identificadores especiales
     * @param maxRows maximo de filas a leer (&lt;= 0 usa {@link #DEFAULT_MAX_ROWS})
     */
    public static Result extractAsInserts(Connection connection, String qualifiedTable,
                                          int maxRows) throws SQLException {
        TableFilter filter = new TableFilter();
        filter.setMaxRows(maxRows);
        return extractAsInserts(connection, qualifiedTable, filter);
    }

    /**
     * Ejecuta {@code SELECT * FROM tabla [WHERE filtro]} y devuelve un INSERT
     * por fila. El limite de filas solo se aplica si el filtro lo tiene
     * activado ({@link TableFilter#isLimitEnabled()}).
     *
     * @param filter filtro elegido por el usuario; null equivale a uno vacio
     *        con el limite por defecto activado
     */
    public static Result extractAsInserts(Connection connection, String qualifiedTable,
                                          TableFilter filter) throws SQLException {
        if (filter == null) {
            filter = new TableFilter();
        }
        List<DmlStatement> statements = new ArrayList<DmlStatement>();
        List<String> warnings = new ArrayList<String>();
        Set<String> warned = new LinkedHashSet<String>();
        boolean limited = filter.isLimitEnabled();
        int limit = filter.getMaxRows() > 0 ? filter.getMaxRows() : DEFAULT_MAX_ROWS;
        String where = filter.whereClause();
        String sql = "SELECT * FROM " + qualifiedTable
                + (where.isEmpty() ? "" : " WHERE " + where);

        Statement st = connection.createStatement();
        try {
            if (limited) {
                st.setMaxRows(limit + 1); // una fila extra solo para detectar el truncado
                st.setFetchSize(Math.min(FETCH_SIZE, limit + 1));
            } else {
                st.setFetchSize(FETCH_SIZE);
            }
            ResultSet rs = st.executeQuery(sql);
            try {
                ResultSetMetaData md = rs.getMetaData();
                List<String> columns = new ArrayList<String>();
                for (int i = 1; i <= md.getColumnCount(); i++) {
                    columns.add(quoteIfNeeded(md.getColumnLabel(i)));
                }
                boolean truncated = false;
                int rows = 0;
                while (rs.next()) {
                    rows++;
                    if (limited && rows > limit) {
                        truncated = true;
                        break;
                    }
                    List<String> values = new ArrayList<String>();
                    for (int i = 1; i <= md.getColumnCount(); i++) {
                        values.add(literal(rs, md, i, columns.get(i - 1), warnings, warned));
                    }
                    statements.add(new DmlStatement(qualifiedTable,
                            DmlStatement.Operation.INSERT, columns, values, null));
                }
                if (truncated) {
                    warnings.add("Se extrajo el maximo de " + limit + " filas de "
                            + qualifiedTable + "; hay mas datos con ese filtro.");
                }
                return new Result(statements, warnings, truncated, sql);
            } finally {
                rs.close();
            }
        } finally {
            st.close();
        }
    }

    /**
     * Devuelve el identificador tal cual si es un nombre Oracle normal
     * (mayusculas, digitos, _, $, #); en caso contrario lo entrecomilla para
     * preservar mayusculas/minusculas y caracteres especiales.
     */
    public static String quoteIfNeeded(String identifier) {
        if (identifier != null && identifier.matches("[A-Z0-9_$#]+")) {
            return identifier;
        }
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }

    // -------------------------------------------------------------- literales

    private static String literal(ResultSet rs, ResultSetMetaData md, int i,
                                  String column, List<String> warnings,
                                  Set<String> warned) throws SQLException {
        int type = md.getColumnType(i);
        Object value;
        switch (type) {
            case Types.DATE:
            case Types.TIMESTAMP:
                value = rs.getTimestamp(i);
                break;
            case Types.BINARY:
            case Types.VARBINARY:
            case Types.LONGVARBINARY:
                value = rs.getBytes(i);
                break;
            case Types.CLOB:
            case Types.NCLOB:
            case ORA_TIMESTAMP_TZ:
            case ORA_TIMESTAMP_LTZ:
            case Types.ROWID:
                value = rs.getString(i);
                break;
            default:
                value = rs.getObject(i);
        }
        if (value == null || rs.wasNull()) {
            return "NULL";
        }
        return toLiteral(type, value, column, warnings, warned);
    }

    /**
     * Convierte un valor JDBC en un literal SQL de Oracle. Los tipos sin
     * representacion literal razonable devuelven NULL y generan un aviso.
     * Es publico para poder probarlo desde SelfTest sin base de datos.
     */
    public static String toLiteral(int sqlType, Object value, String column,
                                   List<String> warnings) {
        return toLiteral(sqlType, value, column, warnings, new LinkedHashSet<String>());
    }

    private static String toLiteral(int sqlType, Object value, String column,
                                    List<String> warnings, Set<String> warned) {
        if (value == null) {
            return "NULL";
        }
        switch (sqlType) {
            case Types.BIGINT:
            case Types.INTEGER:
            case Types.SMALLINT:
            case Types.TINYINT:
            case Types.NUMERIC:
            case Types.DECIMAL:
                return value instanceof BigDecimal
                        ? ((BigDecimal) value).toPlainString()
                        : value.toString();

            case Types.FLOAT:
            case Types.DOUBLE:
            case Types.REAL:
            case ORA_BINARY_FLOAT:
            case ORA_BINARY_DOUBLE:
                return floatingLiteral(value, sqlType);

            case Types.BOOLEAN:
                return Boolean.TRUE.equals(value) ? "TRUE" : "FALSE";

            case Types.CHAR:
            case Types.VARCHAR:
            case Types.LONGVARCHAR:
            case Types.CLOB:
                return quoted(value.toString(), false, column, warnings, warned);

            case Types.NCHAR:
            case Types.NVARCHAR:
            case Types.LONGNVARCHAR:
            case Types.NCLOB:
                return quoted(value.toString(), true, column, warnings, warned);

            case Types.DATE:
                return "TO_DATE('" + dateTimeText(value) + "', 'YYYY-MM-DD HH24:MI:SS')";

            case Types.TIMESTAMP:
                return timestampLiteral(value);

            case ORA_TIMESTAMP_TZ:
            case ORA_TIMESTAMP_LTZ:
                warnOnce(warned, "tz:" + column, warnings,
                        "La columna " + column + " es TIMESTAMP WITH TIME ZONE; "
                                + "revisa el literal generado.");
                return "TO_TIMESTAMP_TZ('" + value.toString().replace("'", "''")
                        + "', 'YYYY-MM-DD HH24:MI:SS.FF TZH:TZM')";

            case Types.ROWID:
                return "CHARTOROWID('" + value.toString().replace("'", "''") + "')";

            case Types.BINARY:
            case Types.VARBINARY:
            case Types.LONGVARBINARY:
                byte[] bytes = (byte[]) value;
                if (bytes.length > 2000) {
                    warnOnce(warned, "raw:" + column, warnings,
                            "La columna " + column + " supera los 2000 bytes; "
                                    + "el literal RAW puede no ser valido.");
                }
                return "HEXTORAW('" + toHex(bytes) + "')";

            case Types.BLOB:
                warnOnce(warned, "blob:" + column, warnings,
                        "La columna " + column + " es BLOB y no se exporta; "
                                + "quedara a NULL en el MERGE.");
                return "NULL";

            default:
                if (value instanceof String) {
                    return quoted((String) value, false, column, warnings, warned);
                }
                warnOnce(warned, "type:" + column, warnings,
                        "La columna " + column + " tiene un tipo no soportado (JDBC "
                                + sqlType + "); se emite NULL.");
                return "NULL";
        }
    }

    private static String quoted(String text, boolean national, String column,
                                 List<String> warnings, Set<String> warned) {
        if (text.length() > MAX_SQL_LITERAL) {
            warnOnce(warned, "len:" + column, warnings,
                    "La columna " + column + " supera los " + MAX_SQL_LITERAL
                            + " caracteres; un literal tan largo puede fallar en SQL.");
        }
        return (national ? "N'" : "'") + text.replace("'", "''") + "'";
    }

    private static String floatingLiteral(Object value, int sqlType) {
        double d = ((Number) value).doubleValue();
        if (Double.isNaN(d)) {
            return sqlType == ORA_BINARY_FLOAT ? "BINARY_FLOAT_NAN" : "BINARY_DOUBLE_NAN";
        }
        if (d == Double.POSITIVE_INFINITY) {
            return sqlType == ORA_BINARY_FLOAT
                    ? "BINARY_FLOAT_INFINITY" : "BINARY_DOUBLE_INFINITY";
        }
        if (d == Double.NEGATIVE_INFINITY) {
            return '-' + (sqlType == ORA_BINARY_FLOAT
                    ? "BINARY_FLOAT_INFINITY" : "BINARY_DOUBLE_INFINITY");
        }
        return value.toString();
    }

    private static String dateTimeText(Object value) {
        if (value instanceof Timestamp) {
            return ((Timestamp) value).toLocalDateTime().format(DATE_TIME);
        }
        return value.toString();
    }

    private static String timestampLiteral(Object value) {
        if (value instanceof Timestamp) {
            Timestamp ts = (Timestamp) value;
            String text = ts.toLocalDateTime().format(DATE_TIME);
            if (ts.getNanos() == 0) {
                return "TO_TIMESTAMP('" + text + "', 'YYYY-MM-DD HH24:MI:SS')";
            }
            String fraction = String.format("%09d", ts.getNanos());
            return "TO_TIMESTAMP('" + text + '.' + fraction
                    + "', 'YYYY-MM-DD HH24:MI:SS.FF')";
        }
        return "TO_TIMESTAMP('" + value.toString() + "', 'YYYY-MM-DD HH24:MI:SS.FF')";
    }

    private static String toHex(byte[] bytes) {
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16))
                    .append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString().toUpperCase();
    }

    private static void warnOnce(Set<String> warned, String key, List<String> warnings,
                                 String message) {
        if (warned.add(key)) {
            warnings.add(message);
        }
    }
}
