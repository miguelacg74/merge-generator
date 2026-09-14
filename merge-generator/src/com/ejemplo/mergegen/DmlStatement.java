package com.generator.mergedml;

import java.util.ArrayList;
import java.util.List;

/**
 * Sentencia DML individual (INSERT o UPDATE) ya descompuesta en tabla,
 * columnas, valores y clausula WHERE.
 */
public class DmlStatement {

    public enum Operation {
        INSERT,
        UPDATE
    }

    private final String tableName;
    private final Operation operation;
    private final List<String> columns;
    private final List<String> values;
    private final String whereClause;

    public DmlStatement(String tableName,
                        Operation operation,
                        List<String> columns,
                        List<String> values,
                        String whereClause) {
        this.tableName = tableName;
        this.operation = operation;
        this.columns = new ArrayList<String>(columns);
        this.values = new ArrayList<String>(values);
        this.whereClause = whereClause;
    }

    public String getTableName() {
        return tableName;
    }

    public Operation getOperation() {
        return operation;
    }

    public List<String> getColumns() {
        return columns;
    }

    public List<String> getValues() {
        return values;
    }

    public String getWhereClause() {
        return whereClause;
    }

    /** Valor asociado a una columna, o null si la columna no aparece. */
    public String valueOf(String column) {
        for (int i = 0; i < columns.size() && i < values.size(); i++) {
            if (columns.get(i).equalsIgnoreCase(column)) {
                return values.get(i);
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return operation + " " + tableName + " " + columns + " = " + values
                + (whereClause == null ? "" : " WHERE " + whereClause);
    }
}
