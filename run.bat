@echo off
setlocal
cd /d "%~dp0"
if not exist out mkdir out
echo [1/3] Compiling Java application and tests...
javac -encoding UTF-8 -Xlint:all -d out PayrollApp.java PayrollServer.java PayrollTests.java
if errorlevel 1 (
  echo Compilation failed. Install JDK 17 or later and retry.
  exit /b 1
)
echo [2/3] Running payroll tests...
java -ea -cp out PayrollTests
if errorlevel 1 (
  echo Tests failed. Server will not start.
  exit /b 1
)
echo [3/3] Starting Paywise. Open http://localhost:8080
java -cp out PayrollServer
endlocal
