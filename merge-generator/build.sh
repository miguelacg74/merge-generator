#!/usr/bin/env bash
# Compila y empaqueta la extension "Generador de MERGE" (Linux/macOS/Git Bash).

set -euo pipefail

cd "$(dirname "$0")"

: "${SQLDEV_HOME:?Define SQLDEV_HOME apuntando a la carpeta de instalacion de SQL Developer}"

if [ ! -d "$SQLDEV_HOME/ide/lib" ]; then
  echo "No encuentro $SQLDEV_HOME/ide/lib ; revisa SQLDEV_HOME" >&2
  exit 1
fi

CP="$(find "$SQLDEV_HOME/ide" "$SQLDEV_HOME/sqldeveloper" "$SQLDEV_HOME/modules" -name '*.jar' 2>/dev/null | tr '\n' ':')"

rm -rf build dist
mkdir -p build dist

javac --release 11 -nowarn -encoding UTF-8 -cp "$CP" -d build $(find src -name '*.java')

mkdir -p build/META-INF
cp META-INF/extension.xml build/META-INF/
jar cfm dist/com.generator.mergedml.jar META-INF/MANIFEST.MF -C build .

echo
echo "JAR generado: $(pwd)/dist/com.generator.mergedml.jar"
echo "Copialo a \$SQLDEV_HOME/dropins/ y reinicia SQL Developer."
