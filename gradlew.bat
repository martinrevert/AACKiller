@echo off
rem Minimal gradlew.bat stub. Requires real Gradle or to generate wrapper.
where gradle >nul 2>nul
if %ERRORLEVEL%==0 (
  gradle %*
  exit /b %ERRORLEVEL%
)
echo Gradle not found. Run 'gradle wrapper --gradle-version 8.6' on a machine with Gradle installed.
exit /b 1
