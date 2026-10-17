import test from 'node:test';
import assert from 'node:assert/strict';
import { calculatePayroll, calculateNewRegime, calculateOldRegime, slabTax, CONFIG, formatRupees } from '../src/payroll.js';

test('calculates basic, DA, HRA, gross and PF for entered salary inputs', () => {
  const result = calculatePayroll({ basic: 60_000, metro: true, monthlyRent: 25_000, other80C: 40_000 });
  assert.equal(result.da, 6_000);
  assert.equal(result.hra, 30_000);
  assert.equal(result.gross, 96_000);
  assert.equal(result.pf, 7_920);
  assert.equal(result.pt, 200);
});

test('uses the non-metro HRA rate', () => {
  const result = calculatePayroll({ basic: 60_000, metro: false, monthlyRent: 20_000, other80C: 0 });
  assert.equal(result.hra, 24_000);
  assert.equal(result.gross, 90_000);
});

test('slab tax handles zero and slab boundaries', () => {
  assert.equal(slabTax(0, CONFIG.newSlabs), 0);
  assert.equal(slabTax(400_000, CONFIG.newSlabs), 0);
  assert.equal(slabTax(800_000, CONFIG.newSlabs), 20_000);
  assert.equal(slabTax(1_200_000, CONFIG.newSlabs), 60_000);
});

test('new regime rebate reduces tax to zero for eligible taxable income up to 12 lakh', () => {
  const tax = calculateNewRegime(1_200_000, CONFIG, true);
  assert.equal(tax.taxableIncome, 1_125_000);
  assert.equal(tax.tax, 0);
});

test('new regime marginal relief caps total modeled liability at income above the threshold', () => {
  const gross = 1_210_000 + CONFIG.standardDeductionNew;
  const tax = calculateNewRegime(gross, CONFIG, true);
  assert.equal(tax.tax, 10_000);
  assert.ok(tax.tax <= tax.taxableIncome - CONFIG.rebateLimitNew);
});

test('old regime caps combined PF and other 80C deductions at 1.5 lakh', () => {
  const tax = calculateOldRegime({
    annualGross: 1_800_000,
    annualHra: 300_000,
    annualBasicDa: 720_000,
    annualPf: 90_000,
    annualPt: 2_400,
    monthlyRent: 25_000,
    other80C: 200_000,
    metro: true
  });
  assert.equal(tax.deduction80C, 150_000);
});

test('professional tax model follows configured Andhra Pradesh tiers', () => {
  assert.equal(CONFIG.professionalTaxMonthly(15_000), 0);
  assert.equal(CONFIG.professionalTaxMonthly(18_000), 150);
  assert.equal(CONFIG.professionalTaxMonthly(25_000), 200);
});

test('rejects negative salary values', () => {
  assert.throws(() => calculatePayroll({ basic: -1, metro: true }), RangeError);
});

test('zero salary remains non-negative and tax-free', () => {
  const result = calculatePayroll({ basic: 0, metro: true, monthlyRent: 0, other80C: 0 });
  assert.equal(result.gross, 0);
  assert.equal(result.net, 0);
  assert.equal(result.chosen.tax, 0);
});


test('monthly and annual payroll identities remain consistent', () => {
  const result = calculatePayroll({ basic: 60_000, metro: true, monthlyRent: 25_000, other80C: 40_000 });
  assert.equal(result.gross, result.basic + result.da + result.hra);
  assert.equal(result.totalDeductions, result.pf + result.pt + result.tds);
  assert.equal(result.net, result.gross - result.totalDeductions);
  assert.equal(result.annualGross, result.gross * 12);
  assert.equal(result.annualNet, result.net * 12);
});

test('HRA exemption respects the least-of-three rule and cannot be negative', () => {
  const tax = calculateOldRegime({
    annualGross: 1_152_000, annualHra: 360_000, annualBasicDa: 792_000,
    annualPf: 95_040, annualPt: 2_400, monthlyRent: 25_000, other80C: 40_000, metro: true
  });
  assert.equal(tax.hraExemption, Math.min(360_000, 300_000 - 0.1 * 792_000, 0.5 * 792_000));
  const noRent = calculateOldRegime({
    annualGross: 1_152_000, annualHra: 360_000, annualBasicDa: 792_000,
    annualPf: 95_040, annualPt: 2_400, monthlyRent: 0, other80C: 0, metro: true
  });
  assert.equal(noRent.hraExemption, 0);
});

test('ineligible employee does not receive the modeled Section 87A rebate', () => {
  const tax = calculateNewRegime(1_000_000, CONFIG, false);
  assert.ok(tax.tax > 0);
});

test('professional tax can be entered manually or disabled when not applicable', () => {
  const base = { basic: 30_000, metro: true, monthlyRent: 0, other80C: 0 };
  assert.equal(calculatePayroll({ ...base, professionalTaxMode: 'manual', manualPt: 125 }).pt, 125);
  assert.equal(calculatePayroll({ ...base, professionalTaxMode: 'none' }).pt, 0);
});

test('rejects manual professional tax greater than gross salary', () => {
  assert.throws(
    () => calculatePayroll({ basic: 1_000, metro: true, professionalTaxMode: 'manual', manualPt: 2_000 }),
    /cannot exceed monthly gross salary/
  );
});

test('rejects unsupported professional-tax calculation methods', () => {
  assert.throws(() => calculatePayroll({ basic: 10_000, professionalTaxMode: 'unknown' }), RangeError);
});

test('currency formatter uses Indian grouping', () => {
  assert.equal(formatRupees(1234567), '₹12,34,567');
});
