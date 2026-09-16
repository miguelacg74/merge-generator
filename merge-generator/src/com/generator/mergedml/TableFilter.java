package com.generator.mergedml;

import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

import com.generator.mergedml.TableDataExtractor.TableColumn;

/**
 * Filtro elegido por el usuario para la lectura de una tabla: una lista de
 * condiciones (combinadas con AND), una condicion libre opcional y el limite
 * de filas, que puede estar desactivado para leer la tabla completa.
 *
 * Alternativamente puede llevar una consulta SELECT personalizada
 * ({@link #setCustomQuery(String)} + {@link #setUseCustomQuery(boolean)}):
 * en ese caso el extractor ejecuta ese SQL en lugar del {@code SELECT *}
 * construido con los filtros, y las columnas del resultado se validan contra
 * la tabla destino.
 *
 * Es Java puro (sin APIs del IDE) para poder probarlo fuera de SQL Developer.
 */
public final class TableFilter {

    /** Operadores disponibles en el dialogo de filtros. */
    public static final String[] OPERATORS = {
            "=", "<>", "<", "<=", ">", ">=", "LIKE", "NOT LIKE", "IN",
            "IS NULL", "IS NOT NULL"};

    private final List<Condition> conditions = new ArrayList<Condition>();
    private String extraCondition = "";
    private boolean limitEnabled = true;
    private int maxRows = TableDataExtractor.DEFAULT_MAX_ROWS;
    private String customQuery = "";
    private boolean useCustomQuery;

    /** Una condicion columna-operador-valor. */
    public static final class Condition {

        private final TableColumn column;
        private final String operator;
        private final String value;
        private final boolean expression;

        /**
         * @param column columna filtrada
         * @param operator uno de {@link #OPERATORS}
         * @param value valor tal cual lo escribio el usuario (vacio para
         *        IS NULL / IS NOT NULL)
         * @param expression true si el valor es una expresion SQL que va cruda
         *        (p.ej. SYSDATE, UPPER('X')); false si hay que convertirlo a
         *        literal segun el tipo de la columna
         */
        public Condition(TableColumn column, String operator, String value,
                         boolean expression) {
            this.column = column;
            this.operator = operator;
            this.value = value == null ? "" : value;
            this.expression = expression;
        }

        public TableColumn getColumn() {
            return column;
        }

        public String getOperator() {
            return operator;
        }

        public String getValue() {
            return value;
        }

        public boolean isExpression() {
            return expression;
        }

        /** Texto SQL de la condicion, listo para el WHERE. */
        public String toSql() {
            String col = column.quotedName();
            if ("IS NULL".equals(operator) || "IS NOT NULL".equals(operator)) {
                return col + ' ' + operator;
            }
            if ("IN".equals(operator)) {
                return col + " IN (" + inValues() + ')';
            }
            return col + ' ' + operator + ' ' + operand();
        }

        private String operand() {
            return expression ? value.trim() : literalFor(column, value);
        }

        private String inValues() {
            if (expression) {
                return value.trim();
            }
            StringBuilder sql = new StringBuilder();
            for (String item : value.split(",")) {
                String text = item.trim();
                if (text.isEmpty()) {
                    continue;
                }
                if (sql.length() > 0) {
                    sql.append(',');
                }
                sql.append(literalFor(column, text));
            }
            // IN () seria SQL invalido; IN (NULL) no coincide con nada
            return sql.length() == 0 ? "NULL" : sql.toString();
        }

        @Override
        public String toString() {
            return toSql();
        }
    }

    public List<Condition> getConditions() {
        return conditions;
    }

    public void addCondition(Condition condition) {
        conditions.add(condition);
    }

    public void removeCondition(int index) {
        conditions.remove(index);
    }

    /** Condicion SQL libre opcional; se anade al WHERE con AND y parentesis. */
    public String getExtraCondition() {
        return extraCondition;
    }

    public void setExtraCondition(String extraCondition) {
        this.extraCondition = extraCondition == null ? "" : extraCondition;
    }

    /** Si es false se lee la tabla completa, sin setMaxRows. */
    public boolean isLimitEnabled() {
        return limitEnabled;
    }

    public void setLimitEnabled(boolean limitEnabled) {
        this.limitEnabled = limitEnabled;
    }

    public int getMaxRows() {
        return maxRows;
    }

    public void setMaxRows(int maxRows) {
        this.maxRows = maxRows;
    }

    /**
     * Consulta SELECT libre escrita por el usuario. Solo se usa si
     * {@link #isUseCustomQuery()} es true; en otro caso se ignora aunque
     * tenga texto (el dialogo conserva lo escrito al cambiar de modo).
     */
    public String getCustomQuery() {
        return customQuery;
    }

    public void setCustomQuery(String customQuery) {
        this.customQuery = customQuery == null ? "" : customQuery;
    }

    public boolean isUseCustomQuery() {
        return useCustomQuery;
    }

    public void setUseCustomQuery(boolean useCustomQuery) {
        this.useCustomQuery = useCustomQuery;
    }

    /** True si la extraccion debe ejecutar la consulta del usuario. */
    public boolean hasCustomQuery() {
        return useCustomQuery && !customQuery.trim().isEmpty();
    }

    /**
     * Clausula WHERE sin la palabra WHERE, o cadena vacia si no hay filtro.
     * Las condiciones se combinan con AND.
     */
    public String whereClause() {
        StringBuilder where = new StringBuilder();
        for (Condition condition : conditions) {
            and(where).append(condition.toSql());
        }
        if (!extraCondition.trim().isEmpty()) {
            and(where).append('(').append(extraCondition.trim()).append(')');
        }
        return where.toString();
    }

    private static StringBuilder and(StringBuilder where) {
        if (where.length() > 0) {
            where.append(" AND ");
        }
        return where;
    }

    /**
     * Convierte el texto del usuario en el literal adecuado al tipo JDBC de la
     * columna: los numericos van crudos, las fechas como TO_DATE/TO_TIMESTAMP
     * y el resto como cadenas entrecomilladas (N'...' para tipos nacionales).
     */
    public static String literalFor(TableColumn column, String raw) {
        String value = raw.trim();
        switch (column.getSqlType()) {
            case Types.BIGINT:
            case Types.INTEGER:
            case Types.SMALLINT:
            case Types.TINYINT:
            case Types.NUMERIC:
            case Types.DECIMAL:
            case Types.FLOAT:
            case Types.DOUBLE:
            case Types.REAL:
                return value;
            case Types.DATE:
                return "TO_DATE('" + escape(normalizeDateTime(value))
                        + "', '" + dateFormat(value) + "')";
            case Types.TIMESTAMP:
                return "TO_TIMESTAMP('" + escape(normalizeDateTime(value))
                        + "', '" + dateFormat(value) + "')";
            case Types.NCHAR:
            case Types.NVARCHAR:
            case Types.LONGNVARCHAR:
            case Types.NCLOB:
                return "N'" + escape(value) + "'";
            default:
                return "'" + escape(value) + "'";
        }
    }

    private static String escape(String text) {
        return text.replace("'", "''");
    }

    /**
     * Rellena hora/segundos cuando el usuario escribe solo la fecha o
     * fecha y minutos, para que el formato HH24:MI:SS siempre calce.
     */
    private static String normalizeDateTime(String value) {
        if (value.matches("\\d{4}-\\d{2}-\\d{2}")) {
            return value + " 00:00:00";
        }
        if (value.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}")) {
            return value + ":00";
        }
        return value;
    }

    private static String dateFormat(String value) {
        return value.contains(".")
                ? "YYYY-MM-DD HH24:MI:SS.FF" : "YYYY-MM-DD HH24:MI:SS";
    }
}
