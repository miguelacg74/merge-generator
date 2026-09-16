package com.generator.mergedml;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.generator.mergedml.TableDataExtractor.TableColumn;
import com.generator.mergedml.TableFilter.Condition;

/**
 * Preset de extraccion: guarda en un archivo JSON la configuracion del
 * dialogo de filtros (condiciones, condicion libre, limite de filas y
 * consulta personalizada) junto con la tabla para la que se creo, para
 * poder recargarla en otra sesion.
 *
 * Las condiciones se guardan por nombre de columna; al cargar se vuelven a
 * resolver contra las columnas actuales de la tabla y las que ya no existan
 * se omiten con un aviso.
 *
 * Es Java puro (sin APIs del IDE) para poder probarlo fuera de SQL Developer.
 */
public final class FilterPreset {

    /** Version del formato de archivo; subir si cambia el esquema. */
    public static final int CURRENT_VERSION = 1;

    private final String table;
    private final TableFilter filter;

    private FilterPreset(String table, TableFilter filter) {
        this.table = table == null ? "" : table;
        this.filter = filter;
    }

    /** Tabla destino para la que se creo el preset (puede ser ""). */
    public String getTable() {
        return table;
    }

    /** Consulta personalizada guardada, haya o no modo consulta activo. */
    public String getCustomQuery() {
        return filter.getCustomQuery();
    }

    public boolean isUseCustomQuery() {
        return filter.isUseCustomQuery();
    }

    /**
     * Captura el estado del filtro (y la tabla) como preset listo para
     * guardar.
     */
    public static FilterPreset of(String table, TableFilter filter) {
        TableFilter copy = new TableFilter();
        for (Condition c : filter.getConditions()) {
            copy.addCondition(c);
        }
        copy.setExtraCondition(filter.getExtraCondition());
        copy.setLimitEnabled(filter.isLimitEnabled());
        copy.setMaxRows(filter.getMaxRows());
        copy.setCustomQuery(filter.getCustomQuery());
        copy.setUseCustomQuery(filter.isUseCustomQuery());
        return new FilterPreset(table, copy);
    }

    /**
     * Reconstruye el filtro resolviendo las columnas del preset contra las
     * columnas actuales de la tabla. Las condiciones cuya columna ya no
     * exista se omiten y se anota un aviso.
     *
     * @param columns columnas actuales de la tabla destino
     * @param warnings lista donde se acumulan los avisos (puede ser null)
     */
    public TableFilter toFilter(List<TableColumn> columns, List<String> warnings) {
        TableFilter result = new TableFilter();
        for (Condition stored : filter.getConditions()) {
            TableColumn column = findColumn(columns, stored.getColumn().getName());
            if (column == null) {
                if (warnings != null) {
                    warnings.add("La columna " + stored.getColumn().getName()
                            + " del preset no existe en la tabla; "
                            + "se omitio la condicion.");
                }
                continue;
            }
            result.addCondition(new Condition(column, stored.getOperator(),
                    stored.getValue(), stored.isExpression()));
        }
        result.setExtraCondition(filter.getExtraCondition());
        result.setLimitEnabled(filter.isLimitEnabled());
        result.setMaxRows(filter.getMaxRows());
        result.setCustomQuery(filter.getCustomQuery());
        result.setUseCustomQuery(filter.isUseCustomQuery());
        return result;
    }

    private static TableColumn findColumn(List<TableColumn> columns, String name) {
        if (columns != null) {
            for (TableColumn column : columns) {
                if (column.getName().equalsIgnoreCase(name)) {
                    return column;
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ JSON

    /** Serializa el preset a texto JSON. */
    public String toJson() {
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("version", BigDecimal.valueOf(CURRENT_VERSION));
        root.put("table", table);
        root.put("useCustomQuery", filter.isUseCustomQuery());
        root.put("customQuery", filter.getCustomQuery());
        root.put("limitEnabled", filter.isLimitEnabled());
        root.put("maxRows", BigDecimal.valueOf(filter.getMaxRows()));
        root.put("extraCondition", filter.getExtraCondition());
        List<Object> conditions = new ArrayList<Object>();
        for (Condition c : filter.getConditions()) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("column", c.getColumn().getName());
            item.put("operator", c.getOperator());
            item.put("value", c.getValue());
            item.put("expression", c.isExpression());
            conditions.add(item);
        }
        root.put("conditions", conditions);
        return JsonUtil.write(root);
    }

    /**
     * Reconstruye un preset desde texto JSON.
     *
     * @throws IllegalArgumentException si el JSON o el formato no son validos
     */
    public static FilterPreset fromJson(String text) {
        Object parsed = JsonUtil.parse(text);
        if (!(parsed instanceof Map)) {
            throw new IllegalArgumentException(
                    "El preset debe ser un objeto JSON.");
        }
        Map<?, ?> root = (Map<?, ?>) parsed;
        int version = number(root.get("version"), -1).intValue();
        if (version != CURRENT_VERSION) {
            throw new IllegalArgumentException(
                    "Formato de preset no soportado (version " + version + ").");
        }

        TableFilter filter = new TableFilter();
        filter.setUseCustomQuery(bool(root.get("useCustomQuery"), false));
        filter.setCustomQuery(string(root.get("customQuery")));
        filter.setLimitEnabled(bool(root.get("limitEnabled"), true));
        filter.setMaxRows(number(root.get("maxRows"),
                BigDecimal.valueOf(TableDataExtractor.DEFAULT_MAX_ROWS)).intValue());
        filter.setExtraCondition(string(root.get("extraCondition")));

        Object conditions = root.get("conditions");
        if (conditions instanceof List) {
            for (Object item : (List<?>) conditions) {
                if (!(item instanceof Map)) {
                    continue;
                }
                Map<?, ?> c = (Map<?, ?>) item;
                String column = string(c.get("column"));
                if (column.isEmpty()) {
                    continue;
                }
                filter.addCondition(new Condition(
                        new TableColumn(column, 0, ""), string(c.get("operator")),
                        string(c.get("value")), bool(c.get("expression"), false)));
            }
        }
        return new FilterPreset(string(root.get("table")), filter);
    }

    private static String string(Object value) {
        return value == null ? "" : value.toString();
    }

    private static boolean bool(Object value, boolean fallback) {
        return value instanceof Boolean ? (Boolean) value : fallback;
    }

    private static BigDecimal number(Object value, int fallback) {
        return number(value, BigDecimal.valueOf(fallback));
    }

    private static BigDecimal number(Object value, BigDecimal fallback) {
        return value instanceof BigDecimal ? (BigDecimal) value : fallback;
    }

    // ------------------------------------------------------------- archivos

    /** Escribe el preset en un archivo JSON (UTF-8). */
    public void save(File file) throws IOException {
        File dir = file.getParentFile();
        if (dir != null && !dir.isDirectory()) {
            dir.mkdirs();
        }
        Writer writer = new OutputStreamWriter(
                new FileOutputStream(file), StandardCharsets.UTF_8);
        try {
            writer.write(toJson());
        } finally {
            writer.close();
        }
    }

    /** Lee un preset desde un archivo JSON (UTF-8). */
    public static FilterPreset load(File file) throws IOException {
        Reader reader = new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8);
        try {
            StringBuilder text = new StringBuilder();
            char[] buffer = new char[4096];
            int read;
            while ((read = reader.read(buffer)) >= 0) {
                text.append(buffer, 0, read);
            }
            return fromJson(text.toString());
        } finally {
            reader.close();
        }
    }
}
