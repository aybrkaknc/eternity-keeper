@echo off
rem Development launcher: runs target\eternity-keeper.jar from this checkout.
rem A release uses "Eternity Keeper.exe" instead.
rem
rem Eternity Keeper needs Java 8 (its embedded browser's natives are Java 8
rem era). Prefer the JDK bundled next to the checkout; fall back to whatever
rem java is on PATH only if that is missing.
rem
rem Settings and the log stay in this checkout (ek.data) rather than in
rem %APPDATA%\Eternity Keeper, so a development copy never touches a
rem release's settings. Add -Dek.debugPort=13002 to drive the UI over the
rem Chrome DevTools protocol (the UI test scripts do).
set "EK_JAVA=%~dp0..\tools\jdk8u492-b09\bin\java.exe"
if not exist "%EK_JAVA%" set "EK_JAVA=java"

"%EK_JAVA%" "-Dek.data=%~dp0." "-Djava.library.path=%~dp0lib\native\win64" %* -jar "%~dp0target\eternity-keeper.jar"
