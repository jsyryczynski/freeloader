@echo off
setlocal enabledelayedexpansion

rem Builds a self-contained Freeloader.exe (bundled JRE included, no
rem separate Java install needed to run it). Does NOT need Maven --
rem compiles straight from src\ with javac/jar, using the dependency
rem jars it downloads into lib\, then packages everything with
rem jpackage (bundled with the JDK itself since 14, no extra tools
rem required for this app-image build).
rem
rem Needs a full JDK 25 (javac, jar and jpackage live under <JDK>\bin).
rem Uses JAVA_HOME if that is a JDK 25. Otherwise set JDK25 to one in
rem build-exe.local.bat next to this script (it isn't committed), e.g.:
rem   set "JDK25=D:\Java\jdk-25"

set "JDK25="
if exist "%~dp0build-exe.local.bat" call "%~dp0build-exe.local.bat"
set "USEJDK="
if defined JAVA_HOME if exist "%JAVA_HOME%\release" (
  findstr /b /r /c:"JAVA_VERSION=.25" "%JAVA_HOME%\release" >nul && set "USEJDK=%JAVA_HOME%"
)
if not defined USEJDK set "USEJDK=%JDK25%"
set "JAVA_HOME=%USEJDK%"
set "PATH=%JAVA_HOME%\bin;%PATH%"

if not exist "%JAVA_HOME%\bin\javac.exe" (
  echo Could not find a JDK 25.
  echo Set JAVA_HOME to a JDK 25, or set JDK25 in build-exe.local.bat.
  exit /b 1
)
echo Using the JDK 25 from JAVA_HOME or build-exe.local.bat

cd /d "%~dp0"

if exist build rmdir /s /q build
set "LIBSTAGE=build\lib"
mkdir "%LIBSTAGE%"

echo === Collecting dependency jars ===
rem None of these are committed (see pom.xml/.gitignore): each is
rem downloaded from Maven Central into lib\ the first time it's needed,
rem then copied into the build. Only the jars listed here are used, so
rem older versions left in lib\ don't end up in the app. Keep these
rem versions in step with pom.xml. The JavaFX jars are the
rem Windows-specific ("-win") builds, which carry the native libraries
rem the bundled app needs.

rem Maven Central, and Google's official mirror of it for when Central
rem refuses (it sometimes blocks the shared addresses of build machines)
set "CENTRAL=https://repo1.maven.org/maven2"
set "MIRROR=https://maven-central.storage-download.googleapis.com/maven2"
call :fetch commons-cli-1.11.0.jar commons-cli/commons-cli/1.11.0 || exit /b 1
call :fetch commons-codec-1.23.0.jar commons-codec/commons-codec/1.23.0 || exit /b 1
call :fetch reload4j-1.2.27.jar ch/qos/reload4j/reload4j/1.2.27 || exit /b 1
call :fetch javafx-base-27.0.1-win.jar org/openjfx/javafx-base/27.0.1 || exit /b 1
call :fetch javafx-graphics-27.0.1-win.jar org/openjfx/javafx-graphics/27.0.1 || exit /b 1
call :fetch javafx-controls-27.0.1-win.jar org/openjfx/javafx-controls/27.0.1 || exit /b 1
call :fetch atlantafx-base-3.0.0.jar io/github/mkpaz/atlantafx-base/3.0.0 || exit /b 1

echo === Compiling ===
set "CLASSES=build\classes"
mkdir "%CLASSES%"
dir /s /b src\*.java > build\sources.txt
javac -encoding UTF-8 -d "%CLASSES%" -cp "%LIBSTAGE%\*" @build\sources.txt
if errorlevel 1 (
  echo Compile failed.
  exit /b 1
)

echo === Copying resources ===
xcopy /y /s /i static\*.* "%CLASSES%\" >nul

rem The version comes from static\version.properties, the one place it
rem is set (pom.xml must match; a test checks). The build is stamped
rem with the git commit it was built from, marked "-modified" if there
rem were uncommitted changes, so the window title tells builds apart.
for /f "eol=# tokens=1,2 delims== " %%a in (static\version.properties) do set "%%a=%%b"
set "VERSION=!version.major!.!version.minor!.!version.tiny!"
set "REVISION="
for /f %%r in ('git rev-parse --short HEAD 2^>nul') do set "REVISION=%%r"
if defined REVISION (
  git diff --quiet HEAD 2>nul || set "REVISION=!REVISION!-modified"
) else (
  set "REVISION=exported"
)
echo version.revision = !REVISION!> "%CLASSES%\revision.properties"
echo Version !VERSION!-!REVISION!

echo === Building jar ===
set "STAGE=build\jpackage-input"
mkdir "%STAGE%"
rem JavaFX goes into the bundled runtime as real modules (see the
rem jpackage call below) rather than onto the classpath with the rest:
rem JavaFX loaded from the classpath works, but warns "Unsupported
rem JavaFX configuration: classes were loaded from 'unnamed module'" on
rem every start
set "FXMODULES=build\javafx-modules"
mkdir "%FXMODULES%"
for %%f in ("%LIBSTAGE%\*.jar") do (
  echo %%~nxf | findstr /i /b /l /c:"javafx-" >nul && copy /y "%%f" "%FXMODULES%\" >nul || copy /y "%%f" "%STAGE%\" >nul
)
(
  echo Manifest-Version: 1.0
  echo Main-Class: freeloader.Freeloader
) > build\MANIFEST.MF
jar cfm "%STAGE%\freeloader.jar" build\MANIFEST.MF -C "%CLASSES%" .
if errorlevel 1 (
  echo Jar build failed.
  exit /b 1
)

echo === Building the bundled Java runtime ===
rem Only the JDK modules the app uses, checked with jdeps on the app,
rem its libraries and JavaFX: java.base; java.desktop (JavaFX requires
rem it, and reload4j reads its settings with java.beans); java.prefs (the
rem window's settings). JavaFX brings in java.xml and java.datatransfer
rem itself. Without debug data, headers and man pages, but not compressed:
rem the .7z download (see the end) compresses it far better that way. No
rem jdk.localedata: numbers are always formatted with a dot (see Format).
rem jlink builds it, not jpackage: JDKs since 24 may come without the
rem jmods files, and then jpackage can't build a runtime itself.
set "JDKMODULES=java.base,java.desktop,java.prefs"
jlink --module-path "%FXMODULES%" ^
  --add-modules "%JDKMODULES%,javafx.base,javafx.graphics,javafx.controls" ^
  --strip-debug --no-header-files --no-man-pages ^
  --output build\runtime
if errorlevel 1 (
  echo jlink failed. The app in dist\ was left as it was.
  exit /b 1
)
rem jvm.lib is only for building native programs against the JVM
if exist build\runtime\lib\jvm.lib del build\runtime\lib\jvm.lib

echo === Running jpackage ===
rem Built next to the old app, which is replaced only once this worked,
rem so a failed build leaves the working app in dist\ alone.
jpackage ^
  --type app-image ^
  --input "%STAGE%" ^
  --dest build\image ^
  --name Freeloader ^
  --main-jar freeloader.jar ^
  --main-class freeloader.Freeloader ^
  --runtime-image build\runtime ^
  --java-options "--enable-native-access=javafx.graphics" ^
  --win-console ^
  --app-version %VERSION%

if errorlevel 1 (
  echo jpackage failed. The app in dist\ was left as it was.
  exit /b 1
)
if exist dist rmdir /s /q dist
if exist dist (
  echo Could not replace dist\: is Freeloader running, or a window open in that folder?
  echo The new build is in build\image\Freeloader.
  exit /b 1
)
mkdir dist
move build\image\Freeloader dist\ >nul

echo === Packing the download ===
rem One .7z (LZMA2) to hand out: about 22 MB, against 42 MB as a zip.
rem Windows 11 opens it in Explorer; older Windows needs 7-Zip. This is
rem the tar built into Windows, which can write .7z (Git's can't).
set "ARCHIVE=dist\Freeloader-%VERSION%.7z"
"%SystemRoot%\System32\tar.exe" --format 7zip --options 7zip:compression=lzma2,7zip:compression-level=9 -cf "%ARCHIVE%" -C dist Freeloader
if errorlevel 1 (
  echo Could not pack the download. The app in dist\Freeloader is fine.
  exit /b 1
)

echo.
echo Done. Run it with: dist\Freeloader\Freeloader.exe --gui
echo The download to hand out: %ARCHIVE%
echo (dist\Freeloader is fully self-contained: no Java install needed.)
endlocal
exit /b 0

rem :fetch <jar name> <directory in the Maven repository>
rem Downloads the jar into lib\ unless it's already there, from Maven
rem Central or else its mirror, then copies it into the build.
:fetch
if not exist lib mkdir lib
if not exist "lib\%~1" (
  echo   downloading %~1...
  powershell -NoProfile -Command "foreach ($base in '%CENTRAL%', '%MIRROR%') { try { Invoke-WebRequest -Uri ($base + '/%~2/%~1') -OutFile 'lib\%~1' -UseBasicParsing -ErrorAction Stop; break } catch { Write-Host ('    not from ' + $base + ': ' + $_.Exception.Message) } }"
)
if not exist "lib\%~1" (
  echo Could not obtain lib\%~1 -- check your internet connection, or place the file there manually.
  exit /b 1
)
copy /y "lib\%~1" "%LIBSTAGE%\" >nul
exit /b 0
