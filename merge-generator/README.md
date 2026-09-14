# Generador de MERGE para Oracle SQL Developer 24.3

Extension (plugin) para SQL Developer clasico que convierte un listado de
sentencias `INSERT` / `UPDATE` en sentencias `MERGE` de Oracle.

Probado en Oracle SQL Developer 24.3.1.347.1826.

## Que hace

- Lee el SQL **seleccionado** en la hoja de trabajo (o todo el contenido si no hay seleccion).
- Parsea `INSERT INTO tabla (cols) VALUES (...)` y `UPDATE tabla SET ... WHERE ...`.
- Alternativa: **clic derecho sobre una tabla** en el navegador de conexiones lee sus
  datos (`SELECT *`) y genera el MERGE directamente, sin escribir INSERTs a mano.
- Consulta la **clave primaria real** en la base de datos usando la conexion de la hoja
  (`ALL_CONSTRAINTS` / `ALL_CONS_COLUMNS`); si no hay conexion o la tabla no tiene PK,
  la escribes a mano en el dialogo.
- Agrupa las filas de una misma tabla en un unico `MERGE` con `SELECT ... FROM DUAL UNION ALL`.
- Vuelca el resultado en una **hoja de trabajo nueva** o en el portapapeles.

## Uso

1. Abre la hoja con tus INSERT/UPDATE.
2. Selecciona el texto (opcional) y usa **Tools -> Generar MERGE desde DML...**
   o el **clic derecho** en el editor -> *Generar MERGE desde DML...*.
3. Revisa la clave primaria de cada tabla en la tabla superior (columna editable,
   varias columnas separadas por comas), ajusta las opciones y pulsa *Generar*.
4. *Abrir en hoja nueva* o *Copiar*.

### Desde una tabla del navegador

1. En el panel **Conexiones**, expande tu conexion hasta *Tables* y haz
   **clic derecho** sobre la tabla -> *Generar MERGE desde tabla...*.
2. Indica el maximo de filas a leer (por defecto 10000).
3. El dialogo se abre con los datos de la tabla ya cargados; revisa la clave y
   genera como siempre.

Los valores se emiten como literales segun su tipo (`TO_DATE`, `TO_TIMESTAMP`,
`HEXTORAW`, `N'...'`). Columnas `BLOB` y tipos sin literal quedan a `NULL` con
un aviso en la salida.

## Compilar e instalar (Windows)

```bat
cd C:\Users\compr\Documents\proyectos\plugin\merge-generator
build.bat
copy /y dist\com.generator.mergedml.jar C:\app\sqldeveloper\dropins\
```

Reinicia SQL Developer. `build.bat` asume `SQLDEV_HOME=C:\app\sqldeveloper` y el JDK en
`%SQLDEV_HOME%\jdk\jre\bin`; cambia esas variables si tu instalacion esta en otra ruta.

El JAR es un bundle OSGi y **debe ir en `dropins\`**, no en `sqldeveloper\extensions\`
(esa carpeta solo carga los bundles registrados en `configuration\bundles.info`).

En Linux/macOS/Git Bash: `SQLDEV_HOME=/ruta/sqldeveloper ./build.sh`.

## Prueba sin SQL Developer

```bash
javac -d /tmp/st -cp build test/SelfTest.java && java -cp /tmp/st:build SelfTest
```

Comprueba el parser y el generador con un script de ejemplo (requiere haber compilado antes).

## Limitaciones actuales

- `INSERT ... SELECT` no esta soportado (se ignora).
- El `INSERT` debe llevar la lista de columnas.
- `VALUES` multi-fila en una sola sentencia no esta soportado.
- Del `WHERE` de un `UPDATE` solo se entienden igualdades simples unidas por `AND`
  (`COL = valor`); esas columnas identifican la fila y no se incluyen en el `UPDATE SET`.
- Si no hay clave (ni de la BD ni escrita a mano) se genera `ON (1 = 0 /* TODO */)`
  y un aviso, para no inventar una clave incorrecta.

## Estructura

```
src/com/ejemplo/mergegen/
  DmlStatement.java            modelo de una sentencia INSERT/UPDATE
  DmlParser.java               troceado del script y parseo (sin librerias externas)
  MergeConfig.java             claves por tabla y opciones
  MergeGenerator.java          generacion del MERGE + avisos
  PrimaryKeyResolver.java      PK desde la conexion activa (JDBC)
  MergeDialog.java             dialogo Swing (claves, opciones, resultado)
  MergeOutput.java             salida: hoja nueva o portapapeles
  MergeGenController.java      accion del IDE (menu Tools y menu contextual del editor)
  TableMergeGenController.java accion del menu contextual del navegador (tablas)
  TableDataExtractor.java      SELECT * -> INSERTs con literales por tipo (JDBC)
META-INF/MANIFEST.MF       bundle OSGi
META-INF/extension.xml     descriptor de la extension (accion + menus)
test/SelfTest.java         prueba manual del parser y el generador
```
