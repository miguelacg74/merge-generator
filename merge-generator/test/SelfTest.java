import com.generator.mergedml.DmlParser;
import com.generator.mergedml.DmlStatement;
import com.generator.mergedml.MergeConfig;
import com.generator.mergedml.MergeGenerator;
import com.generator.mergedml.TableDataExtractor;
import com.generator.mergedml.TableDataExtractor.TableColumn;
import com.generator.mergedml.TableFilter;
import com.generator.mergedml.TableFilter.Condition;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
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

        System.out.println("OK: todas las comprobaciones pasaron");
    }

    /**
     * Conexion JDBC falsa via Proxy: devuelve un ResultSet con columnas
     * ID (NUMBER) y NOMBRE (VARCHAR2) y las filas indicadas como texto.
     * Guarda el ultimo SQL ejecutado en executedSql[0].
     */
    private static Connection fakeConnection(final String[][] rows,
                                             final String[] executedSql) {
        final int[] cursor = {-1};
        final ResultSetMetaData md = (ResultSetMetaData) proxy(
                ResultSetMetaData.class, new InvocationHandler() {
            public Object invoke(Object p, Method m, Object[] a) {
                switch (m.getName()) {
                    case "getColumnCount": return 2;
                    case "getColumnLabel":
                        return ((Integer) a[0]) == 1 ? "ID" : "NOMBRE";
                    case "getColumnType":
                        return ((Integer) a[0]) == 1 ? Types.NUMERIC : Types.VARCHAR;
                    case "getColumnTypeName":
                        return ((Integer) a[0]) == 1 ? "NUMBER" : "VARCHAR2";
                    default: return defaultValue(m.getReturnType());
                }
            }
        });
        final ResultSet rs = (ResultSet) proxy(ResultSet.class, new InvocationHandler() {
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
        final Statement st = (Statement) proxy(Statement.class, new InvocationHandler() {
            public Object invoke(Object p, Method m, Object[] a) {
                if ("executeQuery".equals(m.getName())) {
                    executedSql[0] = (String) a[0];
                    cursor[0] = -1;
                    return rs;
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
