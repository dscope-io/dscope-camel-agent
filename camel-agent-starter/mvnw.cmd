@echo off
setlocal
set SCRIPT_DIR=%~dp0
call "%SCRIPT_DIR%..\mvnw.cmd" %*
exit /b %ERRORLEVEL%
