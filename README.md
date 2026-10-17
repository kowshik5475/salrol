# Paywise — Java Smart Payroll Calculator

Paywise is a **Java 17+ payroll application with a responsive browser frontend**. The frontend collects employee information and displays the results, while `PayrollApp.java` performs all salary and modeled income-tax calculations on the Java server.

This is a functional college project. It calculates each submission in memory and does not yet save employee records in a database or provide logins. Tax and deduction rules are simplified estimates; verify payroll rules before using results for official payments.

## Features

- Employee details and salary-period input
- Basic salary, DA, HRA and gross salary calculations
- Employee PF and professional tax deductions
- Selectable PF contribution model: full Basic + DA or a simplified ₹15,000 monthly wage ceiling
- Andhra Pradesh salary-earner professional-tax schedule, manual PT amount, or no PT
- Modeled old and new tax regime comparison with Section 87A eligibility as an explicit input
- Annual gross, annual tax and annual take-home projection
- 80C what-if scenarios calculated by Java
- Salary slip preview, print and text export
- Input validation and a JSON HTTP API
- Automated Java tests without third-party libraries
- Optional Docker deployment configuration for a Java-capable host

## Technology

- **Backend and payroll logic:** Java 17+ (`jdk.httpserver`, no external dependencies)
- **Frontend:** HTML5, CSS3 and JavaScript for user interaction and rendering
- **API:** `GET /api/health`, `POST /api/payroll`
- **Tests:** dependency-free Java test runner

JavaScript does not calculate salary, PF, PT or income tax. It submits the form to the Java backend and renders the JSON response.

## Run on Windows

1. Install a JDK 17 or later. Confirm `java -version` and `javac -version` work in Command Prompt.
2. Extract this repository ZIP or clone the repository.
3. Double-click `run.bat`, or run it in Command Prompt from the project folder.
4. The script compiles the Java code, runs tests, then starts the web server.
5. Open **http://localhost:8080** in your browser.
6. Enter employee details and select **Calculate payroll**.

Keep the terminal open while using the app. Press `Ctrl+C` in that terminal to stop it.

## Run on macOS / Linux

Install JDK 17+, open a terminal in the project folder and run:

```bash
./run.sh
```

Then open **http://localhost:8080**. Alternatively, compile and run manually:

```bash
javac -encoding UTF-8 -d out PayrollApp.java PayrollServer.java PayrollTests.java
java -ea -cp out PayrollTests
java -cp out PayrollServer
```

## Test the Java payroll engine

```bash
javac -encoding UTF-8 -d out PayrollApp.java PayrollServer.java PayrollTests.java
java -ea -cp out PayrollTests
```

The test runner covers gross salary, DA/HRA/PF modes, professional-tax modes and thresholds, tax-slab precision, ₹10 tax rounding, rebate eligibility, marginal relief, 80C limits, HRA exemption, annual arithmetic, malformed form data, scenarios and invalid inputs. All 21 tests should pass before the server starts.

## Project structure

```text
paywise-java-frontend/
├── PayrollApp.java        # Java payroll domain model and calculation engine
├── PayrollServer.java     # Java HTTP server and JSON API
├── PayrollTests.java      # Dependency-free automated tests
├── index.html             # Frontend page
├── styles.css             # Responsive design
├── src/
│   └── app.js             # Form, API calls, results and salary-slip UI only
├── run.bat                # Windows compile, test and run command
├── run.sh                 # macOS/Linux compile, test and run command
├── Dockerfile             # Container image for Java hosting
├── render.yaml            # Render Blueprint configuration
├── LICENSE
└── README.md
```

## GitHub and online hosting

GitHub is where you store and review the source code. **GitHub Pages cannot execute this Java server** because it hosts static files only. To run the full app online, deploy the Java server to a Java-capable host or container service.

This repository includes `Dockerfile` and `render.yaml` for a Docker-capable hosting service. For a Render Blueprint-style deployment:

1. Push the complete project folder to your GitHub repository.
2. In Render, choose **New → Blueprint** and connect that repository.
3. Review the service settings detected from `render.yaml`, then deploy.
4. Open the generated service URL and confirm `/api/health` responds with `"status":"ok"` and `"engine":"Java"`.
5. Open the service root URL to use the frontend.

Hosting-provider interfaces and free-tier availability can change. The included deployment configuration is a starting point; check the build and runtime logs if the host's setup differs.

To run it on your own computer after uploading the files to GitHub, use the Windows or macOS/Linux instructions above.

## Calculation assumptions and tax disclaimer

The example uses configurable salary assumptions: DA 10% of basic salary, HRA 50% of basic for metro and 40% for non-metro. The employee PF deduction can be modeled as 12% of full Basic + DA or 12% of a ₹15,000 monthly PF wage ceiling. These are selectable educational models, not a determination of statutory EPF applicability; employee membership, wage definition, higher-wage contributions and employer rules may change the correct result.

For the tax model, slab calculations retain precision until cess and rebate/marginal relief are applied, then taxable income and annual tax are rounded to the nearest ₹10. This follows the model's chosen interpretation of the rounding approach described by the Income Tax Department in [Section 288B](https://www.incometaxindia.gov.in/w/section-288b-59) and the Income-tax Act, 2025 rounding provision. The configured new-regime bands match the Department's published **AY 2026–27** slabs and the bands published for **Tax Year 2026–27**; see the official [tax rates and computation](https://www.incometaxindia.gov.in/w/tax-rates-and-computation) and [Budget 2026 tax FAQ](https://www.incometaxindia.gov.in/Documents/Budget2026/FAQs-Budget-2026.pdf). The app is still a simplified salary-only estimate and does not cover every taxpayer circumstance or payroll rule, including surcharge, special-rate income, bonuses/arrears, all exemptions or every payroll-period exception. Rebate eligibility depends on statutory conditions, and the rules should be reverified before real payroll use.

The Andhra Pradesh professional-tax schedule is modeled as nil up to ₹15,000 monthly salary, ₹150 from ₹15,001–₹20,000, and ₹200 above ₹20,000; confirm current local rules and payroll-specific applicability. See the [Andhra Pradesh profession-tax schedule](https://indiankanoon.org/doc/88069109/) as a reference, and verify the latest rules with the applicable authority.

The application continues to use `double` values for a compact dependency-free student project. For production-grade payroll, replace money values with `BigDecimal`, define explicit rounding modes at every statutory step, and obtain a professional tax/payroll review.

This project is for education and demonstration, **not tax advice or a certified payroll system**.

## Privacy

The local server processes submitted employee details in memory to calculate the response. It does not write employee details to a database or persistent storage. Use fictional details in public demonstrations. Anyone who can access a publicly deployed instance may be able to submit data; do not use this deployment as a real HR system without authentication, HTTPS, privacy controls and security review.

## License

See [`LICENSE`](LICENSE).
