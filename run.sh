#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")"
mkdir -p out
printf '[1/3] Compiling Java application and tests...\n'
javac -encoding UTF-8 -Xlint:all -d out PayrollApp.java PayrollServer.java PayrollHistoryStore.java PayrollInsights.java PayrollTests.java
printf '[2/3] Running payroll tests...\n'
java -ea -cp out PayrollTests
printf '[3/3] Starting Paywise. Open http://localhost:8080\n'
exec java -cp out PayrollServer
