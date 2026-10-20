@echo off
rem VgmBrowser v1.1.9 - KEmulator launcher
setlocal
set ROOT=%~dp0..\..
set KEM_DIR=%ROOT%\emulators\kemnnx64
set JDK8=%ROOT%\tools\jdk8
set JAR=%~dp0VgmBrowser.jar

if not exist "%JDK8%\bin\java.exe" (
  echo JDK 8 not found: %JDK8%
  pause
  exit /b 1
)
if not exist "%KEM_DIR%\KEmulator.jar" (
  echo KEmulator not found: %KEM_DIR%
  pause
  exit /b 1
)

start "" "%JDK8%\bin\java.exe" -Xmx512M -Djava.library.path="%KEM_DIR%" -Dfile.encoding=UTF-8 -Dswt.autoScale=false -jar "%KEM_DIR%\KEmulator.jar" "%JAR%"
endlocal
