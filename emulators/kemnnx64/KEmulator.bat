@rem KEmulator nnx64 starter, only for JRE 8!
@rem Uses the workspace-bundled Temurin JDK 8 explicitly, so it does not depend on
@rem whatever "java" happens to be on PATH - system JDK 17+/26 breaks KEmulator.

@echo off
set dir=%~dp0
set jdk8=%dir%..\..\tools\jdk8
set f=%1

if not exist "%jdk8%\bin\javaw.exe" goto nojdk

if defined f (
start "" "%jdk8%\bin\javaw.exe" -Djava.library.path=%dir% -Xmx512M -Dfile.encoding=UTF-8 -jar "%dir%KEmulator.jar" -jar "%1"
) else (
start "" "%jdk8%\bin\javaw.exe" -Djava.library.path=%dir% -Xmx512M -Dfile.encoding=UTF-8 -jar "%dir%KEmulator.jar"
)
goto :eof

:nojdk
echo Missing workspace JDK 8: %jdk8%
echo Fix: restore tools/jdk8/ - see TOOLCHAIN.md
pause
