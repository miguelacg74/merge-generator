package com.generator.mergedml;

import java.util.ArrayList;
import java.util.List;

/**
 * Parser de scripts DML: extrae las sentencias INSERT y UPDATE de un texto SQL.
 *
 * El analisis es lexico (no usa el parser de Oracle) pero respeta literales
 * entre comillas, comentarios y parentesis anidados, de modo que valores como
 * {@code 'texto; con punto y coma'} o {@code TO_DATE('01/01/2024','DD/MM/YYYY')}
 * no rompen la division de sentencias.
 */
public final class DmlParser {

    private DmlParser() {
    }

    /** Divide el script en sentencias, ignorando ';' dentro de literales o comentarios. */
    public static List<String> splitStatements(String script) {
        List<String> result = new ArrayList<String>();
        if (script == null) {
            return result;
        }
        StringBuilder current = new StringBuilder();
        boolean inString = false;
        boolean inLineComment = false;
        boolean inBlockComment = false;

        for (int i = 0; i < script.length(); i++) {
            char c = script.charAt(i);
            char next = (i + 1 < script.length()) ? script.charAt(i + 1) : '\0';

            if (inLineComment) {
                if (c == '\n') {
                    inLineComment = false;
                    current.append(c);
                }
                continue;
            }
            if (inBlockComment) {
                if (c == '*' && next == '/') {
                    inBlockComment = false;
                    i++;
                }
                continue;
            }
            if (inString) {
                current.append(c);
                if (c == '\'') {
                    if (next == '\'') { // comilla escapada
                        current.append(next);
                        i++;
                    } else {
                        inString = false;
                    }
                }
                continue;
            }

            if (c == '-' && next == '-') {
                inLineComment = true;
                i++;
                continue;
            }
            if (c == '/' && next == '*') {
                inBlockComment = true;
                i++;
                continue;
            }
            if (c == '\'') {
                inString = true;
                current.append(c);
                continue;
            }
            if (c == ';') {
                addIfNotBlank(result, current);
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        addIfNotBlank(result, current);
        return result;
    }

    private static void addIfNotBlank(List<String> target, StringBuilder sb) {
        String text = sb.toString().trim();
        if (!text.isEmpty()) {
            target.add(text);
        }
    }

    /** Parsea todas las sentencias INSERT/UPDATE del script; el resto se ignora. */
    public static List<DmlStatement> parseScript(String script) {
        List<DmlStatement> statements = new ArrayList<DmlStatement>();
        for (String raw : splitStatements(script)) {
            DmlStatement stmt = parseStatement(raw);
            if (stmt != null) {
                statements.add(stmt);
            }
        }
        return statements;
    }

    /** Parsea una sentencia suelta; devuelve null si no es INSERT ni UPDATE. */
    public static DmlStatement parseStatement(String statement) {
        String sql = statement == null ? "" : statement.trim();
        if (sql.isEmpty()) {
            return null;
        }
        String upper = sql.toUpperCase();
        if (upper.startsWith("INSERT")) {
            return parseInsert(sql);
        }
        if (upper.startsWith("UPDATE")) {
            return parseUpdate(sql);
        }
        return null;
    }

    // ------------------------------------------------------------------ INSERT

    private static DmlStatement parseInsert(String sql) {
        int into = indexOfKeyword(sql, "INTO", 0);
        if (into < 0) {
            return null;
        }
        int pos = into + 4;
        pos = skipSpaces(sql, pos);
        int nameEnd = pos;
        while (nameEnd < sql.length() && isIdentifierChar(sql.charAt(nameEnd))) {
            nameEnd++;
        }
        String tableName = sql.substring(pos, nameEnd).trim().toUpperCase();
        if (tableName.isEmpty()) {
            return null;
        }

        int valuesIdx = indexOfKeyword(sql, "VALUES", nameEnd);
        if (valuesIdx < 0) {
            return null; // INSERT ... SELECT no soportado
        }

        List<String> columns = new ArrayList<String>();
        int colOpen = sql.indexOf('(', nameEnd);
        if (colOpen >= 0 && colOpen < valuesIdx) {
            int colClose = matchingParen(sql, colOpen);
            if (colClose > colOpen) {
                for (String c : splitTopLevel(sql.substring(colOpen + 1, colClose))) {
                    String col = c.trim().toUpperCase();
                    if (!col.isEmpty()) {
                        columns.add(col);
                    }
                }
            }
        }

        int valOpen = sql.indexOf('(', valuesIdx);
        if (valOpen < 0) {
            return null;
        }
        int valClose = matchingParen(sql, valOpen);
        if (valClose <= valOpen) {
            return null;
        }
        List<String> values = new ArrayList<String>();
        for (String v : splitTopLevel(sql.substring(valOpen + 1, valClose))) {
            values.add(v.trim());
        }

        if (columns.isEmpty()) {
            // Sin lista de columnas no se puede construir un MERGE fiable.
            return null;
        }
        return new DmlStatement(tableName, DmlStatement.Operation.INSERT, columns, values, null);
    }

    // ------------------------------------------------------------------ UPDATE

    private static DmlStatement parseUpdate(String sql) {
        int pos = skipSpaces(sql, "UPDATE".length());
        int nameEnd = pos;
        while (nameEnd < sql.length() && isIdentifierChar(sql.charAt(nameEnd))) {
            nameEnd++;
        }
        String tableName = sql.substring(pos, nameEnd).trim().toUpperCase();
        if (tableName.isEmpty()) {
            return null;
        }

        int setIdx = indexOfKeyword(sql, "SET", nameEnd);
        if (setIdx < 0) {
            return null;
        }
        int whereIdx = indexOfKeyword(sql, "WHERE", setIdx + 3);

        String setClause = (whereIdx < 0)
                ? sql.substring(setIdx + 3)
                : sql.substring(setIdx + 3, whereIdx);
        String whereClause = (whereIdx < 0)
                ? null
                : sql.substring(whereIdx + 5).trim();

        List<String> columns = new ArrayList<String>();
        List<String> values = new ArrayList<String>();
        for (String assignment : splitTopLevel(setClause)) {
            int eq = indexOfTopLevel(assignment, '=');
            if (eq <= 0) {
                continue;
            }
            String col = assignment.substring(0, eq).trim().toUpperCase();
            String val = assignment.substring(eq + 1).trim();
            if (col.isEmpty() || val.isEmpty()) {
                continue;
            }
            columns.add(col);
            values.add(val);
        }
        if (columns.isEmpty()) {
            return null;
        }
        return new DmlStatement(tableName, DmlStatement.Operation.UPDATE, columns, values, whereClause);
    }

    // ----------------------------------------------------------------- helpers

    /** Divide por comas de nivel superior (fuera de comillas y parentesis). */
    public static List<String> splitTopLevel(String text) {
        List<String> parts = new ArrayList<String>();
        StringBuilder current = new StringBuilder();
        boolean inString = false;
        int depth = 0;
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
            switch (c) {
                case '\'':
                    inString = true;
                    current.append(c);
                    break;
                case '(':
                    depth++;
                    current.append(c);
                    break;
                case ')':
                    depth = Math.max(0, depth - 1);
                    current.append(c);
                    break;
                case ',':
                    if (depth == 0) {
                        parts.add(current.toString());
                        current.setLength(0);
                    } else {
                        current.append(c);
                    }
                    break;
                default:
                    current.append(c);
            }
        }
        if (current.toString().trim().length() > 0) {
            parts.add(current.toString());
        }
        return parts;
    }

    /** Posicion del caracter indicado fuera de comillas y parentesis, o -1. */
    private static int indexOfTopLevel(String text, char target) {
        boolean inString = false;
        int depth = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (c == '\'') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '\'') {
                        i++;
                    } else {
                        inString = false;
                    }
                }
                continue;
            }
            if (c == '\'') {
                inString = true;
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth = Math.max(0, depth - 1);
            } else if (c == target && depth == 0) {
                return i;
            }
        }
        return -1;
    }

    /** Busca una palabra clave completa fuera de literales y parentesis. */
    private static int indexOfKeyword(String sql, String keyword, int from) {
        String upper = sql.toUpperCase();
        boolean inString = false;
        int depth = 0;
        for (int i = 0; i < upper.length(); i++) {
            char c = upper.charAt(i);
            if (inString) {
                if (c == '\'') {
                    if (i + 1 < upper.length() && upper.charAt(i + 1) == '\'') {
                        i++;
                    } else {
                        inString = false;
                    }
                }
                continue;
            }
            if (c == '\'') {
                inString = true;
                continue;
            }
            if (c == '(') {
                depth++;
                continue;
            }
            if (c == ')') {
                depth = Math.max(0, depth - 1);
                continue;
            }
            if (i < from || depth != 0) {
                continue;
            }
            if (upper.startsWith(keyword, i)
                    && isBoundary(upper, i - 1)
                    && isBoundary(upper, i + keyword.length())) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isBoundary(String text, int index) {
        if (index < 0 || index >= text.length()) {
            return true;
        }
        return !isIdentifierChar(text.charAt(index));
    }

    private static boolean isIdentifierChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '.' || c == '$' || c == '#' || c == '"';
    }

    private static int skipSpaces(String text, int from) {
        int i = from;
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        return i;
    }

    /** Indice del parentesis que cierra al de {@code open}, o -1. */
    private static int matchingParen(String text, int open) {
        boolean inString = false;
        int depth = 0;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (c == '\'') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '\'') {
                        i++;
                    } else {
                        inString = false;
                    }
                }
                continue;
            }
            if (c == '\'') {
                inString = true;
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }
}
