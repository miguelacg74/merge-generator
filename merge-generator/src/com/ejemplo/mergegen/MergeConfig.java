package com.generator.mergedml;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Opciones de generacion del MERGE. */
public class MergeConfig {

    private final Map<String, List<String>> primaryKeysByTable = new HashMap<String, List<String>>();
    private boolean includeCommit = true;
    private boolean includeComments = true;
    private boolean groupStatements = true;

    /** Claves primarias de una tabla (lista vacia si no se conocen). */
    public List<String> getPrimaryKeys(String table) {
        List<String> keys = primaryKeysByTable.get(normalize(table));
        return keys == null ? new ArrayList<String>() : new ArrayList<String>(keys);
    }

    public void setPrimaryKeys(String table, List<String> columns) {
        List<String> upper = new ArrayList<String>();
        for (String c : columns) {
            String col = c == null ? "" : c.trim().toUpperCase();
            if (!col.isEmpty()) {
                upper.add(col);
            }
        }
        primaryKeysByTable.put(normalize(table), upper);
    }

    public boolean hasPrimaryKeys(String table) {
        return !getPrimaryKeys(table).isEmpty();
    }

    public Map<String, List<String>> getPrimaryKeysByTable() {
        return primaryKeysByTable;
    }

    public boolean isIncludeCommit() {
        return includeCommit;
    }

    public void setIncludeCommit(boolean includeCommit) {
        this.includeCommit = includeCommit;
    }

    public boolean isIncludeComments() {
        return includeComments;
    }

    public void setIncludeComments(boolean includeComments) {
        this.includeComments = includeComments;
    }

    public boolean isGroupStatements() {
        return groupStatements;
    }

    public void setGroupStatements(boolean groupStatements) {
        this.groupStatements = groupStatements;
    }

    private static String normalize(String table) {
        return table == null ? "" : table.trim().toUpperCase();
    }
}
