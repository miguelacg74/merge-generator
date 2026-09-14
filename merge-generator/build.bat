@echo off
REM Compila y empaqueta la extension "Generador de MERGE" para Oracle SQL Developer 24.3.
REM Ajusta SQLDEV_HOME si tu instalacion esta en otra ruta.

cd /d "%~dp0"

if "%SQLDEV_HOME%"=="" set "SQLDEV_HOME=C:\app\sqldeveloper"
if "%JAVA_BIN%"=="" set "JAVA_BIN=%SQLDEV_HOME%\jdk\jre\bin"

set "CP=%SQLDEV_HOME%\ide\lib\*;%SQLDEV_HOME%\ide\extensions\*;%SQLDEV_HOME%\sqldeveloper\lib\*;%SQLDEV_HOME%\sqldeveloper\extensions\*;%SQLDEV_HOME%\jdev\lib\*;%SQLDEV_HOME%\modules\*;%SQLDEV_HOME%\modules\oracle.javatools\*"

if exist build rmdir /s /q build
if exist dist rmdir /s /q dist
mkdir build
mkdir dist

dir /s /b src\*.java > sources.txt
"%JAVA_BIN%\javac.exe" --release 11 -nowarn -encoding UTF-8 -cp "%CP%" -d build @sources.txt
if errorlevel 1 (echo ERROR DE COMPILACION & exit /b 1)
del sources.txt

mkdir build\META-INF
copy /y META-INF\extension.xml build\META-INF\ >nul
"%JAVA_BIN%\jar.exe" cfm dist\com.generator.mergedml.jar META-INF\MANIFEST.MF -C build .

echo.
echo JAR generado en dist\com.generator.mergedml.jar
echo Copialo a %SQLDEV_HOME%\dropins\ y reinicia SQL Developer.
