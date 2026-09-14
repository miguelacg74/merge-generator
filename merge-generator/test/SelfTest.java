import com.generator.mergedml.DmlParser;
import com.generator.mergedml.DmlStatement;
import com.generator.mergedml.MergeConfig;
import com.generator.mergedml.MergeGenerator;
import com.generator.mergedml.TableDataExtractor;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Prueba manual del parser y el generador (no requiere SQL Developer). */
public final class SelfTest {

    public static void main(String[] args) {
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

        System.out.println("OK: todas las comprobaciones pasaron");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
