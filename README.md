# Paywise — Smart Payroll Calculator

Paywise is a responsive, browser-based payroll calculator. Enter employee details and salary inputs to calculate monthly earnings, deductions, estimated income tax, annual projections, and a printable or downloadable salary slip.

## Features

- Employee details and pay-period selection.
- Basic salary, dearness allowance (DA), house rent allowance (HRA), and employee provident fund (PF).
- Professional tax using the configured Andhra Pradesh schedule, a manually entered amount, or no professional-tax deduction when it does not apply.
- Estimated old- and new-regime income-tax comparison, with rebate eligibility explicitly selected by the user.
- Annual salary projection and an 80C what-if advisor.
- Print-ready salary slip preview and downloadable text payslip.
- Input validation and automated calculation tests.
- Responsive interface for desktop and mobile screens.

## Run Locally

Open `index.html` in a modern browser. For a local development server, run this command from the project directory:

```bash
python -m http.server 8000
```

Then visit <http://localhost:8000>.

## Run Tests

Install Node.js 18 or later, then run from the project directory:

```bash
npm test
```

## Project Structure

```text
paywise-smart-payroll/
├── index.html
├── styles.css
├── src/
│   ├── app.js
│   └── payroll.js
├── tests/
│   └── payroll.test.js
├── .github/workflows/pages.yml
├── package.json
├── LICENSE
└── README.md
```

## Calculation Scope and Limitations

Paywise performs calculations locally in the browser. It does not upload employee details to a server, authenticate users, or maintain a shared employee database. Refreshing the page does not restore the previous employee's form.

The calculator uses configurable salary assumptions: DA at 10% of basic, HRA at 50% or 40% of basic based on the selected city type, and employee PF at 12% of basic plus DA. The built-in professional-tax table is an Andhra Pradesh estimate; select manual entry when another jurisdiction's verified amount is needed. The tax engine is a simplified salary-only model. PF eligibility and ceilings, surcharge, special-rate and other income, many exemptions, age-specific rules, employer contributions, and many individual circumstances are outside its scope.

This is a functional educational calculator, not a complete payroll-compliance or tax-filing system. Verify current laws, employer policies, eligibility, and rounding requirements before using its outputs for actual payroll decisions. Official reference: [Income Tax Department of India](https://www.incometax.gov.in/).

## Privacy

Calculations occur in the browser. Employee details are not sent to a backend or stored in a database. Avoid using real personal data on public computers.

## License

See [`LICENSE`](LICENSE) for license terms.
