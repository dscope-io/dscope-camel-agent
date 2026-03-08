@echo off
setlocal

if defined MVN_CMD if exist "%MVN_CMD%" (
  "%MVN_CMD%" %*
  exit /b %ERRORLEVEL%
)

if defined MAVEN_HOME if exist "%MAVEN_HOME%\bin\mvn.cmd" (
  "%MAVEN_HOME%\bin\mvn.cmd" %*
  exit /b %ERRORLEVEL%
)

where mvn >nul 2>nul
if %ERRORLEVEL% EQU 0 (
  mvn %*
  exit /b %ERRORLEVEL%
)

if exist "C:\Users\roman\.sdkman\candidates\maven\current\bin\mvn.cmd" (
  "C:\Users\roman\.sdkman\candidates\maven\current\bin\mvn.cmd" %*
  exit /b %ERRORLEVEL%
)

echo Error: Maven executable not found. Set MVN_CMD or MAVEN_HOME, or install Maven on PATH. 1>&2
exit /b 1
