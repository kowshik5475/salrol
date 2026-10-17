# Paywise — Smart Payroll Calculator

A responsive, single-page payroll calculator built with HTML, CSS, and vanilla JavaScript. It turns the `PayrollApp.java` console prototype into an interactive web demo that can be hosted on GitHub Pages.

## Features

- Employee details form with live calculations.
- Basic salary, DA, HRA, gross salary, PF, professional tax, TDS, total deductions, and net salary.
- Annual gross, tax, and net-pay projections.
- Modeled comparison of India's old and new income-tax regimes.
- What-if scenarios for annual other-80C investment amounts.
- Salary-slip preview, browser print, and downloadable `.txt` slip.
- Responsive layout for desktop, tablet, and mobile.
- Calculation engine separated from UI, with built-in automated tests.
- No backend, account, API key, or dependency installation required to run the site.

## Run locally

### Option 1 — Open the page

Open `index.html` in a modern browser. The calculator itself runs in the browser. For module/test development, use Option 2.

### Option 2 — Serve the folder locally

With Python installed:

```bash
python -m http.server 8000
```

Then open <http://localhost:8000>.

### Run tests

Node.js 18+ is required for the test suite; the live site does not require Node.js.

```bash
npm test
```

## Publish on GitHub Pages

This repository includes `.github/workflows/pages.yml`. After you push the project to a GitHub repository:

1. Open the repository on GitHub and go to **Settings → Pages**.
2. Under **Build and deployment**, choose **GitHub Actions** as the source.
3. Push a commit to the `main` branch, or start **Deploy Paywise to GitHub Pages** from the **Actions** tab.
4. Wait for the workflow to finish. The deployment job shows the public site URL.

The workflow runs `npm test` before it deploys the static files. No framework build, secrets, backend, API key, or package installation is needed for the live site. GitHub's guide: <https://docs.github.com/en/pages/getting-started-with-github-pages/using-custom-workflows-with-github-pages>.

### Upload the project

Create a repository such as `paywise-smart-payroll`, then upload the contents of this project folder to the repository root (not the outer `payroll-webapp` directory). Ensure `index.html`, `styles.css`, `src/`, `tests/`, `package.json`, `README.md`, `LICENSE`, and `.github/workflows/pages.yml` are included.

Alternatively, from this directory after creating an empty GitHub repository, run:

```bash
git init
git add .
git commit -m "Build responsive payroll calculator demo"
git branch -M main
git remote add origin https://github.com/YOUR-USERNAME/YOUR-REPOSITORY.git
git push -u origin main
```

Replace `YOUR-USERNAME` and `YOUR-REPOSITORY` with the correct values. Do not put access tokens or passwords into source files.

## Calculation assumptions and limitations

This is a learning/demo application, not a legally authoritative payroll or tax system. The default income-tax model follows the published AY 2026–27 general slab structure and standard deduction assumptions in the supplied prototype. The app assumes a salaried resident individual eligible for the modeled Section 87A rebate; it does not calculate surcharge, special-rate income, other income, age-dependent old-regime slabs, employer-side PF, gratuity, bonuses, arrears, or other exemptions. The Andhra Pradesh professional-tax tiers are a configurable demo schedule and must be verified against current local rules before use. PF eligibility and wage-ceiling rules are simplified. The UI displays estimates to whole rupees; internal calculations use JavaScript `Number`, not production-grade decimal arithmetic.

Tax rules can change. Review official guidance before using any figures outside this educational demo: <https://www.incometaxindia.gov.in/w/tax-rates-and-computation>.

## Privacy

The app does not send employee data to a server or store it in a database. Calculations are performed locally in the browser. Downloaded salary slips are generated on demand. Use fictional details for public demos and screenshots.

## Project structure

```text
payroll-webapp/
├── index.html
├── styles.css
├── package.json
├── README.md
├── .gitignore
├── .nojekyll
├── LICENSE
├── .github/workflows/pages.yml
├── src/
│   ├── app.js
│   └── payroll.js
└── tests/
    └── payroll.test.js
```
