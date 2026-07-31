@echo off
REM ============================================================
REM  sapient-harness-java — Windows one-shot build script
REM ============================================================
REM  Cleans, builds all 4 modules, runs tests, and reports where
REM  the runnable JARs landed. Fails loudly on any error.
REM
REM  Usage:
REM    build.bat                 - clean + install + test (default)
REM    build.bat fast            - skip tests (~10s vs ~15s)
REM    build.bat package         - clean + package (no install to local .m2)
REM    build.bat run-cli         - build then launch CLI help
REM    build.bat run-ui          - build then launch the JavaFX UI
REM    build.bat wipe            - nuke target/ folders + local .m2 install
REM ============================================================

setlocal EnableDelayedExpansion

REM --- Change to script's own directory so ye can run this from anywhere ---
pushd "%~dp0"

echo.
echo === sapient-harness-java build script ===
echo Working directory: %CD%
echo.

REM --- Sanity: Java 17+ ---
where java >nul 2>&1
if errorlevel 1 (
    echo [ERROR] java not found on PATH.
    echo         Install a JDK 17+ ^(Temurin recommended: winget install EclipseAdoptium.Temurin.17.JDK^)
    goto :fail
)

REM --- Sanity: Maven 3.9+ ---
where mvn >nul 2>&1
if errorlevel 1 (
    echo [ERROR] mvn not found on PATH.
    echo         Install Maven: winget install Apache.Maven
    echo         Then close and reopen this terminal.
    goto :fail
)

echo -- java version --
java -version 2>&1
echo.
echo -- mvn version --
call mvn -v
echo.

REM --- Dispatch on first arg ---
set "MODE=%~1"
if "%MODE%"=="" set "MODE=install"

if /I "%MODE%"=="wipe"    goto :wipe
if /I "%MODE%"=="fast"    goto :fast
if /I "%MODE%"=="package" goto :package
if /I "%MODE%"=="run-cli" goto :run_cli
if /I "%MODE%"=="run-ui"  goto :run_ui
if /I "%MODE%"=="install" goto :install
if /I "%MODE%"=="help"    goto :usage
if /I "%MODE%"=="-h"      goto :usage
if /I "%MODE%"=="--help"  goto :usage

echo [ERROR] Unknown mode: %MODE%
goto :usage

REM ============================================================
:install
echo === Running: mvn clean install ===
echo.
call mvn clean install
if errorlevel 1 goto :fail
goto :report

REM ============================================================
:fast
echo === Running: mvn clean install -DskipTests ===
echo.
call mvn clean install -DskipTests
if errorlevel 1 goto :fail
goto :report

REM ============================================================
:package
echo === Running: mvn clean package ===
echo.
call mvn clean package
if errorlevel 1 goto :fail
goto :report

REM ============================================================
:run_cli
echo === Building then launching CLI help ===
echo.
call mvn -q clean install -DskipTests
if errorlevel 1 goto :fail
echo.
echo -- Launching sapient-cli --
java -jar sapient-cli\target\sapient-cli-0.1.0-SNAPSHOT.jar
goto :ok

REM ============================================================
:run_ui
echo === Building then launching JavaFX UI ===
echo.
call mvn -q clean install -DskipTests
if errorlevel 1 goto :fail
echo.
echo -- Launching sapient-ui --
start "sapient-harness-java UI" java -jar sapient-ui\target\sapient-ui-0.1.0-SNAPSHOT.jar
goto :ok

REM ============================================================
:wipe
echo === Wiping target\ folders + local .m2 install ===
for /d %%D in (sapient-core sapient-net sapient-cli sapient-ui) do (
    if exist "%%D\target" (
        echo   rmdir /S /Q "%%D\target"
        rmdir /S /Q "%%D\target"
    )
)
if exist "%USERPROFILE%\.m2\repository\com\mdudel\sapient" (
    echo   rmdir /S /Q "%USERPROFILE%\.m2\repository\com\mdudel\sapient"
    rmdir /S /Q "%USERPROFILE%\.m2\repository\com\mdudel\sapient"
)
echo.
echo Wipe complete. Run 'build.bat' to rebuild from scratch.
goto :ok

REM ============================================================
:usage
echo.
echo Usage:
echo    build.bat              - clean + install + test ^(default^)
echo    build.bat fast         - skip tests
echo    build.bat package      - clean + package only ^(no local install^)
echo    build.bat run-cli      - build then launch CLI ^(prints help^)
echo    build.bat run-ui       - build then launch the JavaFX UI
echo    build.bat wipe         - nuke target\ folders + local .m2 install
echo    build.bat help         - this message
echo.
goto :ok

REM ============================================================
:report
echo.
echo ============================================================
echo   BUILD SUCCESS
echo ============================================================
echo.
echo Runnable artifacts:
echo.
if exist sapient-cli\target\sapient-cli-0.1.0-SNAPSHOT.jar (
    for %%A in ("sapient-cli\target\sapient-cli-0.1.0-SNAPSHOT.jar") do (
        echo   CLI:  %%~fA   ^(%%~zA bytes^)
    )
)
if exist sapient-ui\target\sapient-ui-0.1.0-SNAPSHOT.jar (
    for %%A in ("sapient-ui\target\sapient-ui-0.1.0-SNAPSHOT.jar") do (
        echo   UI:   %%~fA   ^(%%~zA bytes^)
    )
)
echo.
echo Quick-launch:
echo   java -jar sapient-cli\target\sapient-cli-0.1.0-SNAPSHOT.jar receive --port 12000
echo   java -jar sapient-cli\target\sapient-cli-0.1.0-SNAPSHOT.jar send --host 127.0.0.1 --port 12000
echo   java -jar sapient-ui\target\sapient-ui-0.1.0-SNAPSHOT.jar
echo.
goto :ok

REM ============================================================
:fail
echo.
echo ============================================================
echo   BUILD FAILED
echo ============================================================
echo Check the Maven output above for the error.
echo Common fixes:
echo   * Wrong Java version: need JDK 17+, check 'java -version'
echo   * Corp proxy blocking Maven Central: see docs/WIRE_FORMAT.md
echo   * Stale local cache: try 'build.bat wipe' then 'build.bat'
echo.
popd
endlocal
exit /b 1

:ok
popd
endlocal
exit /b 0
