import com.generator.mergedml.DmlParser;
import com.generator.mergedml.DmlStatement;
import com.generator.mergedml.FilterPreset;
import com.generator.mergedml.MergeConfig;
import com.generator.mergedml.MergeGenerator;
import com.generator.mergedml.TableDataExtractor;
import com.generator.mergedml.TableDataExtractor.TableColumn;
import com.generator.mergedml.TableFilter;
import com.generator.mergedml.TableFilter.Condition;

import java.io.File;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Prueba manual del parser y el generador (no requiere SQL Developer). */
public final class SelfTest {

    public static void main(String[] args) throws Exception {
        String script =
                "-- carga inicial\n"
                + "INSERT INTO HR.EMP (EMP_ID, NAME, HIRED) VALUES (10, 'MIGUEL; JR', TO_DATE('2024-01-31','YYYY-MM-DD'));\n"
                + "INSERT INTO HR.EMP (EMP_ID, NAME, HIRED) VALUES (11, 'ANA', SYSDATE);\n"
                + "UPDATE HR.EMP SET NAME = 'PEDRO' WHERE EMP_ID = 12 AND COMPANY_ID = 2;\n"
                + "/* ignorada */ DELETE FROM HR.EMP WHERE EMP_ID = 99;\n";

        List<DmlStatement> statements = DmlParser.parseScript(script);
        check(statements.size() == 3, "se esperaban 3 sentencias, hubo " + statements.size());
        check("HR.EMP".equals(statements.get(0).getTableName()), "tabla mal parseada");
        check(statements.get(0).getValues().get(1).equals("'MIGUEL; JR'"), "valor con ; mal partido");
        check(statements.get(0).getValues().get(2).startsWith("TO_DATE("), "TO_DATE mal partido");
        check(statements.get(2).getOperation() == DmlStatement.Operation.UPDATE, "UPDATE no detectado");

        MergeConfig config = new MergeConfig();
        config.setPrimaryKeys("HR.EMP", Arrays.asList("EMP_ID"));
        MergeGenerator generator = new MergeGenerator(config);
        String merge = generator.generate(statements);

        check(merge.contains("MERGE INTO HR.EMP"), "falta MERGE INTO");
        check(merge.contains("FROM DUAL"), "falta FROM DUAL");
        check(merge.contains("UNION ALL"), "falta UNION ALL de las 2 filas del INSERT");
        check(merge.contains("WHEN MATCHED THEN"), "falta WHEN MATCHED");
        check(merge.contains("WHEN NOT MATCHED THEN"), "falta WHEN NOT MATCHED");
        check(merge.contains("COMMIT"), "falta COMMIT");
        check(!merge.contains("1 = 0"), "no deberia usar el ON de emergencia");

        System.out.println(merge);
        System.out.println("avisos: " + generator.getWarnings());

        // sin clave configurada -> aviso y ON seguro
        MergeGenerator sinPk = new MergeGenerator(new MergeConfig());
        String merge2 = sinPk.generate(DmlParser.parseScript(
                "INSERT INTO T (A, B) VALUES (1, 2);"));
        check(merge2.contains("1 = 0"), "sin PK deberia generar ON (1 = 0 ...)");
        check(!sinPk.getWarnings().isEmpty(), "sin PK deberia avisar");

        // literales del extractor de tablas
        List<String> w = new ArrayList<String>();
        check("'O''Brien'".equals(TableDataExtractor.toLiteral(
                Types.VARCHAR, "O'Brien", "C", w)), "string mal escapado");
        check("N'n'".equals(TableDataExtractor.toLiteral(
                Types.NVARCHAR, "n", "C", w)), "literal nacional mal emitido");
        check("12.30".equals(TableDataExtractor.toLiteral(
                Types.NUMERIC, new BigDecimal("12.30"), "C", w)), "numero mal emitido");
        check(TableDataExtractor.toLiteral(Types.DATE,
                Timestamp.valueOf("2024-01-31 14:30:00"), "C", w)
                .startsWith("TO_DATE('2024-01-31 14:30:00'"), "fecha mal emitida");
        check(TableDataExtractor.toLiteral(Types.TIMESTAMP,
                Timestamp.valueOf("2024-01-31 14:30:00.123"), "C", w)
                .contains("HH24:MI:SS.FF"), "timestamp sin fraccion");
        check("NULL".equals(TableDataExtractor.toLiteral(
                Types.VARCHAR, null, "C", w)), "null mal emitido");
        check("NULL".equals(TableDataExtractor.toLiteral(
                Types.BLOB, new byte[]{1}, "C", w)) && !w.isEmpty(),
                "BLOB deberia ser NULL con aviso");
        check("\"miCol\"".equals(TableDataExtractor.quoteIfNeeded("miCol")),
                "identificador case-sensitive sin comillas");
        check("EMP".equals(TableDataExtractor.quoteIfNeeded("EMP")),
                "identificador normal no deberia llevar comillas");

        // filtro de tabla: condiciones, literales por tipo y limite opcional
        TableColumn id = new TableColumn("ID", Types.NUMERIC, "NUMBER");
        TableColumn nom = new TableColumn("NOMBRE", Types.VARCHAR, "VARCHAR2");
        TableColumn fec = new TableColumn("FEC", Types.DATE, "DATE");

        TableFilter f = new TableFilter();
        check(f.isLimitEnabled(), "el limite deberia venir activado");
        check("".equals(f.whereClause()), "sin condiciones no deberia haber WHERE");

        f.addCondition(new Condition(id, ">=", "10", false));
        f.addCondition(new Condition(nom, "LIKE", "A%", false));
        f.addCondition(new Condition(nom, "IN", "a, b", false));
        f.addCondition(new Condition(fec, "=", "2024-01-31", false));
        f.addCondition(new Condition(nom, "IS NOT NULL", "", false));
        f.addCondition(new Condition(nom, "<>", "UPPER('x')", true));
        String esperado = "ID >= 10 AND NOMBRE LIKE 'A%' AND NOMBRE IN ('a','b')"
                + " AND FEC = TO_DATE('2024-01-31 00:00:00', 'YYYY-MM-DD HH24:MI:SS')"
                + " AND NOMBRE IS NOT NULL AND NOMBRE <> UPPER('x')";
        check(esperado.equals(f.whereClause()), "where mal armado: " + f.whereClause());

        f.setExtraCondition("ID < 999 OR NOMBRE = 'X'");
        check(f.whereClause().endsWith("AND (ID < 999 OR NOMBRE = 'X')"),
                "condicion libre mal anadida: " + f.whereClause());

        TableFilter f2 = new TableFilter();
        f2.addCondition(new Condition(id, "IN", "1,2 ,3", false));
        check("ID IN (1,2,3)".equals(f2.whereClause()),
                "IN numerico mal armado: " + f2.whereClause());

        f.setLimitEnabled(false);
        check(!f.isLimitEnabled(), "el limite deberia poder desactivarse");

        // extractAsInserts contra una conexion JDBC falsa (Proxy): la tabla
        // simulada tiene columnas ID/NOMBRE y 2 filas
        final String[] executedSql = new String[1];
        Connection fake = fakeConnection(new String[][]{{"1", "ana"}, {"2", "bob"}},
                executedSql);

        TableDataExtractor.Result r =
                TableDataExtractor.extractAsInserts(fake, "T", f2);
        check(r.getStatements().size() == 2,
                "se esperaban 2 filas extraidas, hubo " + r.getStatements().size());
        check("SELECT * FROM T WHERE ID IN (1,2,3)".equals(executedSql[0]),
                "SQL ejecutado inesperado: " + executedSql[0]);
        check("T".equals(r.getStatements().get(0).getTableName()),
                "tabla del insert mal asignada");
        check("1".equals(r.getStatements().get(0).getValues().get(0)),
                "primer valor deberia ser 1: " + r.getStatements().get(0).getValues());
        check("'ana'".equals(r.getStatements().get(0).getValues().get(1)),
                "segundo valor deberia ser 'ana'");

        TableDataExtractor.Result sinFiltro =
                TableDataExtractor.extractAsInserts(fake, "T", new TableFilter());
        check(sinFiltro.getStatements().size() == 2, "sin filtro deberia leer todo");
        check("SELECT * FROM T".equals(executedSql[0]),
                "sin filtro no deberia llevar WHERE: " + executedSql[0]);

        check(TableDataExtractor.columnsOf(fake, "T").size() == 2,
                "columnsOf deberia devolver 2 columnas");

        // consulta personalizada: ejecuta el SQL del usuario (sin ';' final)
        // y mapea las etiquetas del resultado a las columnas del destino
        TableFilter custom = new TableFilter();
        custom.setCustomQuery("  SELECT id, nombre FROM otra WHERE x > 0;  ");
        custom.setUseCustomQuery(true);
        TableDataExtractor.Result rc =
                TableDataExtractor.extractAsInserts(fake, "T", custom);
        check(rc.getStatements().size() == 2,
                "la consulta personalizada deberia extraer 2 filas");
        check("SELECT id, nombre FROM otra WHERE x > 0".equals(executedSql[0]),
                "el SQL personalizado deberia ejecutarse sin ';': "
                        + executedSql[0]);
        check("T".equals(rc.getStatements().get(0).getTableName()),
                "la tabla destino deberia ser la seleccionada, no la del FROM");
        check(rc.getStatements().get(0).getColumns().contains("ID"),
                "las columnas deberian resolverse al nombre canonico del destino");

        // modo consulta apagado con texto: se ignora y se usa el filtro
        TableFilter off = new TableFilter();
        off.setCustomQuery("select 1 from dual");
        TableDataExtractor.extractAsInserts(fake, "T", off);
        check("SELECT * FROM T".equals(executedSql[0]),
                "con el modo consulta apagado deberia ignorar el texto: "
                        + executedSql[0]);

        // etiqueta que no existe en la tabla destino -> error que la nombra
        Connection mismatch = fakeConnection(
                new String[][]{{"1", "ana"}}, executedSql,
                new String[]{"IDX", "NOMBRE"});
        TableFilter bad = new TableFilter();
        bad.setCustomQuery("select idx, nombre from otra");
        bad.setUseCustomQuery(true);
        try {
            TableDataExtractor.extractAsInserts(mismatch, "T", bad);
            check(false, "una etiqueta desconocida deberia fallar");
        } catch (SQLException e) {
            check(e.getMessage() != null && e.getMessage().contains("IDX"),
                    "el error deberia nombrar la columna: " + e.getMessage());
        }

        // consulta que no es SELECT -> error antes de ejecutar nada
        TableFilter notSelect = new TableFilter();
        notSelect.setCustomQuery("DELETE FROM t");
        notSelect.setUseCustomQuery(true);
        try {
            TableDataExtractor.extractAsInserts(fake, "T", notSelect);
            check(false, "una consulta no-SELECT deberia fallar");
        } catch (SQLException e) {
            check(e.getMessage() != null && e.getMessage().contains("SELECT"),
                    "el error deberia pedir un SELECT: " + e.getMessage());
        }

        // preset: ida y vuelta por JSON conservando todo el estado
        TableFilter src = new TableFilter();
        src.addCondition(new Condition(id, ">=", "10", false));
        src.addCondition(new Condition(nom, "<>", "UPPER('x')", true));
        src.setExtraCondition("ID < 999");
        src.setLimitEnabled(false);
        src.setMaxRows(500);
        src.setCustomQuery("select id, nombre from v");
        src.setUseCustomQuery(true);

        FilterPreset back = FilterPreset.fromJson(
                FilterPreset.of("HR.EMP", src).toJson());
        check("HR.EMP".equals(back.getTable()), "tabla del preset mal leida");
        check(back.isUseCustomQuery(), "modo consulta del preset mal leido");
        check("select id, nombre from v".equals(back.getCustomQuery()),
                "consulta del preset mal leida");

        List<String> pw = new ArrayList<String>();
        TableFilter res = back.toFilter(Arrays.asList(id, nom, fec), pw);
        check(pw.isEmpty(), "el preset no deberia dar avisos: " + pw);
        check(!res.isLimitEnabled() && res.getMaxRows() == 500,
                "limite del preset mal leido");
        check(res.hasCustomQuery(),
                "la consulta personalizada deberia restaurarse");
        check("ID >= 10 AND NOMBRE <> UPPER('x') AND (ID < 999)"
                .equals(res.whereClause()),
                "condiciones del preset mal leidas: " + res.whereClause());

        // preset guardado y leido de disco
        File tmp = File.createTempFile("preset", ".json");
        try {
            FilterPreset.of("HR.EMP", src).save(tmp);
            check("HR.EMP".equals(FilterPreset.load(tmp).getTable()),
                    "preset en disco mal leido");
        } finally {
            tmp.delete();
        }

        // columna que ya no existe en la tabla -> aviso y se omite
        FilterPreset incompleto = FilterPreset.fromJson(
                "{\"version\":1,\"table\":\"T\",\"conditions\":[{"
                + "\"column\":\"NOPE\",\"operator\":\"=\","
                + "\"value\":\"1\",\"expression\":false}]}");
        List<String> pw2 = new ArrayList<String>();
        TableFilter res2 = incompleto.toFilter(Arrays.asList(id), pw2);
        check(res2.getConditions().isEmpty(),
                "condicion con columna inexistente deberia omitirse");
        check(!pw2.isEmpty(), "deberia avisar de la columna inexistente");
        check("".equals(res2.whereClause()),
                "sin condiciones no deberia haber WHERE");

        // JSON invalido -> error claro en vez de un preset corrupto
        try {
            FilterPreset.fromJson("{no es json");
            check(false, "un JSON invalido deberia fallar");
        } catch (IllegalArgumentException e) {
            check(e.getMessage() != null && e.getMessage().contains("JSON"),
                    "el error deberia explicar que el JSON es invalido");
        }

        System.out.println("OK: todas las comprobaciones pasaron");
    }

    /**
     * Conexion JDBC falsa via Proxy: la tabla simulada tiene columnas
     * ID (NUMBER) y NOMBRE (VARCHAR2) y las filas indicadas como texto.
     * Guarda el ultimo SQL ejecutado en executedSql[0].
     */
    private static Connection fakeConnection(final String[][] rows,
                                             final String[] executedSql) {
        return fakeConnection(rows, executedSql, null);
    }

    /**
     * Variante para probar consultas personalizadas: {@code queryLabels} son
     * las etiquetas que expone la metadata de las consultas de datos (las que
     * no llevan {@code WHERE 1 = 0}); null = las mismas de la tabla.
     */
    private static Connection fakeConnection(final String[][] rows,
                                             final String[] executedSql,
                                             final String[] queryLabels) {
        final int[] cursor = {-1};
        final ResultSet rsTable = resultSet(metaData(new String[]{"ID", "NOMBRE"}),
                rows, cursor);
        final ResultSet rsData = resultSet(
                metaData(queryLabels == null ? new String[]{"ID", "NOMBRE"}
                        : queryLabels),
                rows, cursor);
        final Statement st = (Statement) proxy(Statement.class, new InvocationHandler() {
            public Object invoke(Object p, Method m, Object[] a) {
                if ("executeQuery".equals(m.getName())) {
                    executedSql[0] = (String) a[0];
                    cursor[0] = -1;
                    return executedSql[0].contains("WHERE 1 = 0") ? rsTable : rsData;
                }
                return defaultValue(m.getReturnType());
            }
        });
        return (Connection) proxy(Connection.class, new InvocationHandler() {
            public Object invoke(Object p, Method m, Object[] a) {
                if ("createStatement".equals(m.getName())) {
                    return st;
                }
                return defaultValue(m.getReturnType());
            }
        });
    }

    /** Metadata falsa: la primera columna NUMBER y el resto VARCHAR2. */
    private static ResultSetMetaData metaData(final String[] labels) {
        return (ResultSetMetaData) proxy(
                ResultSetMetaData.class, new InvocationHandler() {
            public Object invoke(Object p, Method m, Object[] a) {
                switch (m.getName()) {
                    case "getColumnCount": return labels.length;
                    case "getColumnLabel":
                        return labels[((Integer) a[0]) - 1];
                    case "getColumnType":
                        return ((Integer) a[0]) == 1 ? Types.NUMERIC : Types.VARCHAR;
                    case "getColumnTypeName":
                        return ((Integer) a[0]) == 1 ? "NUMBER" : "VARCHAR2";
                    default: return defaultValue(m.getReturnType());
                }
            }
        });
    }

    private static ResultSet resultSet(final ResultSetMetaData md,
                                       final String[][] rows, final int[] cursor) {
        return (ResultSet) proxy(ResultSet.class, new InvocationHandler() {
            public Object invoke(Object p, Method m, Object[] a) {
                switch (m.getName()) {
                    case "next": return ++cursor[0] < rows.length;
                    case "getObject":
                        Object v = rows[cursor[0]][((Integer) a[0]) - 1];
                        return ((Integer) a[0]) == 1 ? new BigDecimal((String) v) : v;
                    case "wasNull": return false;
                    case "getMetaData": return md;
                    default: return defaultValue(m.getReturnType());
                }
            }
        });
    }

    private static Object proxy(Class<?> iface, InvocationHandler handler) {
        return Proxy.newProxyInstance(SelfTest.class.getClassLoader(),
                new Class<?>[]{iface}, handler);
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == double.class) {
            return 0d;
        }
        if (type == float.class) {
            return 0f;
        }
        return 0;
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
