# Paywise Intelligence — Java Payroll Application

Paywise is a **Java 17+ payroll application with a responsive browser frontend**. The Java backend calculates salary and tax estimates, compares job offers, checks saved payroll for unusual changes, explains differences between pay periods, and maintains a local audit journal. HTML, CSS, and JavaScript provide the interface only; all payroll and recommendation decisions run in Java.

> **Educational project, not a certified payroll system.** Tax, HRA, PF and professional-tax calculations use simplified configurable assumptions. Verify the applicable law and employer rules before using a result for actual payroll.

## Features

### 1. Payroll calculator
- Calculates Basic, DA, HRA, gross salary, employee PF, professional tax, modeled TDS, deductions, net salary and annual projections.
- Compares modeled old and new tax regimes and shows 80C what-if scenarios.
- Generates a printable salary slip and text export.

### 2. Salary Offer Simulator
- Compares two salary structures using the same Java payroll engine.
- Allows each offer to use different basic salary, metro/non-metro HRA assumptions, rent, other 80C investments, PF model and professional-tax option.
- Shows monthly gross, PF, professional tax, modeled tax, monthly take-home and annual net projections.
- Identifies which entered offer has the higher modeled monthly take-home and quantifies the difference.
- Does not currently value bonuses, stock/equity, employer insurance, joining/retention bonuses, commute costs, job security, role fit or career growth. Compare the full compensation package before making a decision.

### 3. Smart Payroll Audit
- Performs explainable, rule-based checks for component reconciliation, unusually high deductions, negative/zero modeled net salary, high manual professional tax, and the configured 80C cap.
- Compares gross pay, net pay, and TDS with the latest saved earlier period for the same employee ID.
- Flags gross or take-home changes of 20% or more and TDS changes of ₹5,000 or more as review prompts.
- Alerts are **not proof of an error**; legitimate raises, unpaid leave, arrears, policy changes or corrected inputs can trigger a flag.

### 4. Salary Change Explainer
- Compares the current calculation with the latest saved snapshot for a strictly earlier pay period.
- Shows changes in Basic, DA, HRA, gross, PF, professional tax, TDS, total deductions and take-home pay.
- Explains the arithmetic difference using saved values instead of guessing why the change occurred.
- Save at least one earlier period for an employee before a period-to-period explanation can be displayed. Employee IDs are used to match history; keep them consistent between periods.

### 5. Payroll History and Audit Trail
- Saves snapshots **only when the user clicks** “Save payroll snapshot to audit trail”.
- Records employee inputs, calculated output, save timestamp, and calculation-model version. Repeated saves create additional entries rather than overwriting the earlier record.
- Lists recent snapshots and allows an earlier record's inputs to be loaded and recalculated under the current model. The original snapshot stays in the journal.
- Uses a dependency-free append-only file at `data/payroll-history.tsv` (or the directory configured by `PAYWISE_DATA_DIR`). This is local file persistence, not a database; Base64 encoding is for field separation and is **not encryption**.

## Technology

- **Backend and payroll engine:** Java 17+ (`jdk.httpserver`, no third-party dependencies)
- **Frontend:** HTML5, CSS3 and JavaScript ES modules
- **API:** Java HTTP API with form-urlencoded requests and JSON responses
- **Audit journal:** local append-only TSV file
- **Tests:** dependency-free Java test runner
- **Deployment examples:** Dockerfile and Render Blueprint configuration

Java performs all salary arithmetic, offer comparison, anomaly rules, and salary-change explanations. JavaScript submits inputs, displays API results, and handles page interactions.

## Run on Windows

1. Install a JDK 17 or later. Confirm `java -version` and `javac -version` work in Command Prompt.
2. Extract or clone this repository.
3. Double-click `run.bat`, or open Command Prompt in this folder and run it.
4. The script compiles the Java classes, runs all unit tests, and starts the server only if the tests pass.
5. Open **http://localhost:8080**. Keep the terminal open while using the app. Press `Ctrl+C` to stop the server.

## Run on macOS / Linux

Install JDK 17+, open a terminal in the project folder, and run:

```bash
./run.sh
```

Then open **http://localhost:8080**. To compile and test manually:

```bash
mkdir -p out
javac -encoding UTF-8 -Xlint:all -d out PayrollApp.java PayrollServer.java PayrollHistoryStore.java PayrollInsights.java PayrollTests.java
java -ea -cp out PayrollTests
java -cp out PayrollServer
```

## API endpoints

| Method | Endpoint | Purpose |
|---|---|---|
| `GET` | `/api/health` | Confirms that the Java server is online. |
| `POST` | `/api/payroll` | Calculates one employee payroll and returns audit insights against earlier saved history. |
| `POST` | `/api/offer-compare` | Calculates two configured offers and returns a comparison. |
| `GET` | `/api/history` | Lists the latest saved snapshots; optional `?employeeId=E101&limit=100` filters the list. |
| `POST` | `/api/history` | Calculates and appends a payroll snapshot to the local audit journal. |
| `GET` | `/api/audit` | Alias for viewing saved audit/history entries. |

POST endpoints accept `application/x-www-form-urlencoded` and return JSON.

## Tests

```bash
java -ea -cp out PayrollTests
```

The test suite covers salary components, tax boundaries and rounding, rebate assumptions, PF and professional-tax modes, validation, history serialization and lookup, explainable salary-change flags, and shared-engine offer comparison. A full API smoke test should also verify `GET /api/health`, `POST /api/payroll`, `POST /api/offer-compare`, `POST /api/history`, and `GET /api/history`.

## Project structure

```text
paywise-java-frontend/
├── PayrollApp.java          # Salary/tax models and calculation engine
├── PayrollServer.java       # Java API and frontend server
├── PayrollHistoryStore.java # Append-only payroll snapshot journal
├── PayrollInsights.java     # Rule-based audit and pay-change explanation
├── PayrollTests.java        # Dependency-free automated tests
├── index.html               # Browser frontend structure
├── styles.css              # Responsive dashboard styling
├── src/app.js              # API client and UI rendering only
├── run.bat / run.sh         # Compile, test, run launchers
├── Dockerfile               # Container build
├── render.yaml              # Example hosted-service configuration
└── data/                    # Generated local history data (git-ignored)
```

## Online hosting and data persistence

GitHub is for source control; **GitHub Pages cannot execute the Java backend**. Deploy the Java application to a Java-capable host or container service using the supplied Dockerfile. The server binds to the `PORT` and `HOST` environment variables; set `HOST=0.0.0.0` in a container. The audit journal directory can be changed with `PAYWISE_DATA_DIR`.

Attach a persistent disk/volume and point `PAYWISE_DATA_DIR` to its mount path if saved records must survive online redeploys or restarts. Without persistent storage, a host may discard the journal. Never commit `data/payroll-history.tsv`, because it may contain salary and employee details.

## Assumptions, privacy and production limitations

- DA, HRA, employee PF, PT and tax values use configurable educational assumptions. Taxation depends on applicable tax year, taxpayer circumstances, income types and deductions.
- The model compares **basic-salary-based structures**, not full CTC. Employer PF, gratuity, bonus/arrears, equity/ESOPs, insurance valuation, surcharge, all exemptions and all payroll-period exceptions are not comprehensively modeled.
- Monetary arithmetic uses Java `double` for this compact student prototype. A production payroll should use `BigDecimal`, explicit statutory rounding rules, comprehensive tests and a qualified payroll/tax review.
- The audit journal stores a snapshot of inputs and modeled outputs; it does not implement authentication, access control, database encryption or data-retention policies. It is local file persistence, not encryption or a relational database.
- A public deployment should add authentication, HTTPS, role-based access, protected data storage, backups and a privacy/security review before processing real employee data.

## License

See [`LICENSE`](LICENSE).
