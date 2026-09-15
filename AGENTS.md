# Reglas del proyecto: Generador de MERGE (plugin SQL Developer)

Extension OSGi para Oracle SQL Developer clasico (probado en 24.3.1.347.1826).
Codigo y documentacion en espanol, sin tildes en fuentes/comentarios (ASCII).

## Build, instalacion y pruebas

- Compilar: `merge-generator\build.bat` (Windows). Asume `SQLDEV_HOME=C:\app\sqldeveloper`
  y el JDK en `%SQLDEV_HOME%\jdk\jre\bin`. En bash: `SQLDEV_HOME=... ./build.sh`.
- Instalar: copiar `dist\com.generator.mergedml.jar` a `%SQLDEV_HOME%\dropins\`
  (NUNCA a `sqldeveloper\extensions\`, esa carpeta solo carga bundles registrados
  en `configuration\bundles.info`) y reiniciar SQL Developer.
- Prueba sin IDE: `javac -d out -cp build test\SelfTest.java` y
  `java -cp "out;build" SelfTest`. Ojo: `java.exe` es binario Windows; usa rutas
  Windows y `;` como separador (las rutas `/tmp` de Git Bash no resuelven).
- `build/` y `dist/` NO se versionan (estan en `.gitignore`); se regeneran con
  cada `build.bat`. No re-añadirlos con `git add -f` ni committeandolos a mano.

## Arquitectura (respetar el pipeline)

Flujo unico: **parser/extractor -> `DmlStatement` -> `MergeGenerator`(+`MergeConfig`)
-> `MergeDialog` -> `MergeOutput`**.

Hay DOS puntos de entrada que convergen en el mismo pipeline:
- `MergeGenController`: texto del worksheet -> `DmlParser` -> INSERTs/UPDATEs.
- `TableMergeGenController`: tabla del navegador -> `TableDataExtractor` -> INSERTs.

Regla de oro: cualquier fuente nueva de datos debe producir `DmlStatement` y entrar
por `MergeDialog`. No generar MERGE por caminos paralelos ni duplicar logica del
generador. Los componentes son stateless por llamada (instancias nuevas cada vez);
no introducir estado compartido.

## Sin dependencias externas

`DmlParser`, `MergeGenerator`, `TableDataExtractor` y `SelfTest` son Java puro
(JDBC + colecciones): deben seguir compilando/ejecutandose SIN los jars de
SQL Developer. Solo los controllers, `MergeDialog` y `MergeOutput` pueden usar
APIs del IDE. Mantener esa frontera es lo que permite probar la logica fuera
del IDE.

## APIs internas de SQL Developer (fragiles entre versiones)

- Verificado contra 24.3. Ante clases `oracle.dbtools.raptor.*` usar siempre
  `try/catch (Throwable)` con fallback, como hacen los controllers actuales.
- `Context.getElement()` -> `new DBObject(element)` da tipo/nombre/esquema/
  conexion del objeto del navegador. `Connections.getInstance().getConnection(name)`
  abre conexion por nombre.
- Si se usan paquetes nuevos, comprobar con `unzip -p <jar> META-INF/MANIFEST.MF`
  que el bundle que los exporta este en `Require-Bundle` de `MANIFEST.MF`
  (`oracle.dbtools.raptor.utils/navigator` los exporta `oracle.sqldeveloper`,
  ya requerido). Compilar contra un jar no basta: OSGi solo ve paquetes exportados.

## extension.xml (romperlo rompe TODA la extension)

- `<rules>` es hijo de `<trigger-hooks>`, ANTES de `<triggers>` (son hermanos).
- Sitios de menu contextual: `editor` (worksheet) y `db_nav` (navegador de
  conexiones). Para filtrar por tipo de objeto: rule-type
  `oracle.dbtools.raptor.utils.ObjectTypeRuleFunction` + param `ObjectType`
  (ej. `TABLE`), replicando el bloque `mergegen-has-table` ya declarado.
- Cada accion necesita: `<action>` + `<controller>` con `update-rule` + item de
  menu/hook. Validar el XML completo tras editarlo; un error de sintaxis impide
  cargar el plugin entero.

## Concurrencia (EDT)

- Ninguna consulta JDBC en el hilo de interfaz: PK lookup y extraccion de tabla
  van en `SwingWorker`/segundo plano. Ver patron en `TableMergeGenController`
  (dialogo modal de progreso + `done()` encolado en el EDT).
- `MergeDialog` es modal; los avisos previos se inyectan por el constructor con
  `initialWarnings`.

## Generacion SQL (correctitud primero)

- NUNCA inventar una clave primaria: sin PK (ni de BD ni manual) se emite
  `ON (1 = 0 /* TODO */)` + aviso.
- Literales SQL de Oracle: limite 4000 caracteres (avisar si se supera);
  `''` para escapar comillas; `TO_DATE`/`TO_TIMESTAMP`/`HEXTORAW`/`N'...'` segun
  tipo; BLOB y tipos sin literal -> `NULL` + aviso (mejor perder el dato con
  aviso que generar SQL invalido).
- La extraccion de tablas SIEMPRE con limite de filas (`setMaxRows`+1 para
  detectar truncado y avisar).
- Limitaciones conocidas y asumidas: `INSERT ... SELECT`, `VALUES` multi-fila,
  `INSERT` sin lista de columnas y quoting `q'[...]'` no soportados; WHERE de
  UPDATE solo igualdades con `AND`; PK manual en el dialogo se mayusculea
  (rompe identificadores case-sensitive); la PK debe aparecer en el DML.

## Estilo

- Java 11 (`--release 11`), sin librerias de terceros, `StringBuilder` para
  concatenar SQL, listeners anonimos (como los existentes), `try/finally` para
  recursos JDBC. Comentarios de clase/metodo en espanol como el resto.
- Quirk intencionado: los fuentes viven en `src/com/generator/mergedml/` pero
  declaran `package com.generator.mergedml`. No "arreglar" la carpeta sin
  actualizar el bundle.
