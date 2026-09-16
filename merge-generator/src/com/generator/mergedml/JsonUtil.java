package com.generator.mergedml;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Serializador/parser JSON minimo (sin librerias de terceros) para los
 * archivos de preset. Soporta los tipos que usa el preset: objeto
 * ({@link Map}), array ({@link List}), cadena, numero ({@link BigDecimal}),
 * booleano y null.
 *
 * El parser es tolerante con el formato (espacios, orden de claves) pero
 * rechaza entradas mal formadas con un error que indica la posicion.
 */
public final class JsonUtil {

    private JsonUtil() {
    }

    // ------------------------------------------------------------- escritura

    /** Serializa un valor (Map, List, String, Number, Boolean o null). */
    public static String write(Object value) {
        StringBuilder out = new StringBuilder();
        writeValue(out, value, 0);
        out.append('\n');
        return out.toString();
    }

    private static void writeValue(StringBuilder out, Object value, int indent) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String) {
            writeString(out, (String) value);
        } else if (value instanceof Boolean || value instanceof Number) {
            out.append(value);
        } else if (value instanceof Map) {
            writeObject(out, (Map<?, ?>) value, indent);
        } else if (value instanceof List) {
            writeArray(out, (List<?>) value, indent);
        } else {
            throw new IllegalArgumentException(
                    "Tipo no serializable a JSON: " + value.getClass().getName());
        }
    }

    private static void writeObject(StringBuilder out, Map<?, ?> map, int indent) {
        if (map.isEmpty()) {
            out.append("{}");
            return;
        }
        out.append('{');
        boolean first = true;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (first) {
                first = false;
            } else {
                out.append(',');
            }
            newline(out, indent + 1);
            writeString(out, String.valueOf(entry.getKey()));
            out.append(": ");
            writeValue(out, entry.getValue(), indent + 1);
        }
        newline(out, indent);
        out.append('}');
    }

    private static void writeArray(StringBuilder out, List<?> list, int indent) {
        if (list.isEmpty()) {
            out.append("[]");
            return;
        }
        out.append('[');
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            newline(out, indent + 1);
            writeValue(out, list.get(i), indent + 1);
        }
        newline(out, indent);
        out.append(']');
    }

    private static void newline(StringBuilder out, int indent) {
        out.append('\n');
        for (int i = 0; i < indent; i++) {
            out.append("  ");
        }
    }

    private static void writeString(StringBuilder out, String text) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                case '\b': out.append("\\b"); break;
                case '\f': out.append("\\f"); break;
                default:
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        out.append('"');
    }

    // --------------------------------------------------------------- lectura

    /**
     * Parsea un texto JSON y devuelve Map/List/String/BigDecimal/Boolean/null.
     *
     * @throws IllegalArgumentException si el texto no es JSON valido
     */
    public static Object parse(String text) {
        Parser parser = new Parser(text == null ? "" : text);
        Object value = parser.parseValue();
        parser.skipWhitespace();
        if (!parser.atEnd()) {
            throw parser.error("sobran caracteres tras el valor JSON");
        }
        return value;
    }

    private static final class Parser {

        private final String text;
        private int pos;

        Parser(String text) {
            this.text = text;
        }

        boolean atEnd() {
            return pos >= text.length();
        }

        void skipWhitespace() {
            while (!atEnd() && Character.isWhitespace(text.charAt(pos))) {
                pos++;
            }
        }

        IllegalArgumentException error(String detail) {
            return new IllegalArgumentException(
                    "JSON invalido en la posicion " + pos + ": " + detail);
        }

        Object parseValue() {
            skipWhitespace();
            if (atEnd()) {
                throw error("se esperaba un valor");
            }
            char c = text.charAt(pos);
            switch (c) {
                case '{': return parseObject();
                case '[': return parseArray();
                case '"': return parseString();
                case 't': return parseLiteral("true", Boolean.TRUE);
                case 'f': return parseLiteral("false", Boolean.FALSE);
                case 'n': return parseLiteral("null", null);
                default:
                    if (c == '-' || Character.isDigit(c)) {
                        return parseNumber();
                    }
                    throw error("caracter inesperado '" + c + "'");
            }
        }

        private Object parseLiteral(String literal, Object value) {
            if (!text.startsWith(literal, pos)) {
                throw error("se esperaba '" + literal + "'");
            }
            pos += literal.length();
            return value;
        }

        private Map<String, Object> parseObject() {
            Map<String, Object> map = new LinkedHashMap<String, Object>();
            pos++; // '{'
            skipWhitespace();
            if (!atEnd() && text.charAt(pos) == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWhitespace();
                if (atEnd() || text.charAt(pos) != '"') {
                    throw error("se esperaba el nombre de una clave");
                }
                String key = parseString();
                skipWhitespace();
                if (atEnd() || text.charAt(pos) != ':') {
                    throw error("se esperaba ':' tras la clave \"" + key + '"');
                }
                pos++;
                map.put(key, parseValue());
                skipWhitespace();
                if (atEnd()) {
                    throw error("objeto sin cerrar");
                }
                char c = text.charAt(pos);
                if (c == ',') {
                    pos++;
                } else if (c == '}') {
                    pos++;
                    return map;
                } else {
                    throw error("se esperaba ',' o '}'");
                }
            }
        }

        private List<Object> parseArray() {
            List<Object> list = new ArrayList<Object>();
            pos++; // '['
            skipWhitespace();
            if (!atEnd() && text.charAt(pos) == ']') {
                pos++;
                return list;
            }
            while (true) {
                list.add(parseValue());
                skipWhitespace();
                if (atEnd()) {
                    throw error("array sin cerrar");
                }
                char c = text.charAt(pos);
                if (c == ',') {
                    pos++;
                } else if (c == ']') {
                    pos++;
                    return list;
                } else {
                    throw error("se esperaba ',' o ']'");
                }
            }
        }

        private String parseString() {
            StringBuilder value = new StringBuilder();
            pos++; // '"'
            while (!atEnd()) {
                char c = text.charAt(pos++);
                if (c == '"') {
                    return value.toString();
                }
                if (c == '\\') {
                    if (atEnd()) {
                        break;
                    }
                    char esc = text.charAt(pos++);
                    switch (esc) {
                        case '"': value.append('"'); break;
                        case '\\': value.append('\\'); break;
                        case '/': value.append('/'); break;
                        case 'n': value.append('\n'); break;
                        case 'r': value.append('\r'); break;
                        case 't': value.append('\t'); break;
                        case 'b': value.append('\b'); break;
                        case 'f': value.append('\f'); break;
                        case 'u':
                            if (pos + 4 > text.length()) {
                                throw error("escape \\u incompleto");
                            }
                            try {
                                value.append((char) Integer.parseInt(
                                        text.substring(pos, pos + 4), 16));
                            } catch (NumberFormatException e) {
                                throw error("escape \\u invalido");
                            }
                            pos += 4;
                            break;
                        default:
                            throw error("escape invalido '\\" + esc + "'");
                    }
                } else {
                    value.append(c);
                }
            }
            throw error("cadena sin cerrar");
        }

        private BigDecimal parseNumber() {
            int start = pos;
            if (!atEnd() && text.charAt(pos) == '-') {
                pos++;
            }
            while (!atEnd() && Character.isDigit(text.charAt(pos))) {
                pos++;
            }
            if (!atEnd() && text.charAt(pos) == '.') {
                pos++;
                while (!atEnd() && Character.isDigit(text.charAt(pos))) {
                    pos++;
                }
            }
            if (!atEnd() && (text.charAt(pos) == 'e' || text.charAt(pos) == 'E')) {
                pos++;
                if (!atEnd() && (text.charAt(pos) == '+' || text.charAt(pos) == '-')) {
                    pos++;
                }
                while (!atEnd() && Character.isDigit(text.charAt(pos))) {
                    pos++;
                }
            }
            if (start == pos) {
                throw error("numero mal formado");
            }
            try {
                return new BigDecimal(text.substring(start, pos));
            } catch (NumberFormatException e) {
                throw error("numero mal formado");
            }
        }
    }
}
