@echo off
rem KEmulator nnx64 launcher (Windows) -- always uses the workspace-bundled Temurin JDK 8.
rem Mirrors run-kemulator.sh. Usage: run-kemulator.bat [midlet.jar]
rem Without an argument it starts an empty emulator (File - Open MIDlet).

setlocal
set ROOT=%~dp0
set KEM_DIR=%ROOT%emulators\kemnnx64
set JDK8=%ROOT%tools\jdk8

if not exist "%JDK8%\bin\java.exe" goto nojdk
if not exist "%KEM_DIR%\KEmulator.jar" goto nokem

start "" "%JDK8%\bin\java.exe" -Xmx512M -Djava.library.path="%KEM_DIR%" -Dfile.encoding=UTF-8 -Dswt.autoScale=false -jar "%KEM_DIR%\KEmulator.jar" %*
goto :eof

:nojdk
echo Missing workspace JDK 8: %JDK8%
echo Fix: restore tools/jdk8/ - see TOOLCHAIN.md
pause
goto :eof

:nokem
echo Missing KEmulator.jar: %KEM_DIR%
pause
