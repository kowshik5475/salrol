/**
 * Paywise payroll calculation engine.
 * Salary and tax rules are configurable estimates, not a complete legal-compliance engine.
 */
export const CONFIG = Object.freeze({
  daRate: 0.10,
  hraMetroRate: 0.50,
  hraNonMetroRate: 0.40,
  pfRate: 0.12,
  standardDeductionOld: 50_000,
  standardDeductionNew: 75_000,
  limit80C: 150_000,
  cessRate: 0.04,
  rebateLimitNew: 1_200_000,
  rebateLimitOld: 500_000,
  // Supported built-in professional-tax schedule. Other jurisdictions can use a
  // manually entered monthly amount until their rules are explicitly configured.
  professionalTaxMonthly(gross, employee = {}) {
    if (employee.professionalTaxMode === 'none') return 0;
    if (employee.professionalTaxMode === 'manual') {
      return finiteNonNegative(employee.manualPt ?? 0, 'Monthly professional tax');
    }
    if (gross <= 15_000) return 0;
    if (gross <= 20_000) return 150;
    return 200;
  },
  newSlabs: Object.freeze([
    Object.freeze({ upTo: 400_000, rate: 0 }),
    Object.freeze({ upTo: 800_000, rate: 0.05 }),
    Object.freeze({ upTo: 1_200_000, rate: 0.10 }),
    Object.freeze({ upTo: 1_600_000, rate: 0.15 }),
    Object.freeze({ upTo: 2_000_000, rate: 0.20 }),
    Object.freeze({ upTo: 2_400_000, rate: 0.25 }),
    Object.freeze({ upTo: Number.POSITIVE_INFINITY, rate: 0.30 })
  ]),
  oldSlabs: Object.freeze([
    Object.freeze({ upTo: 250_000, rate: 0 }),
    Object.freeze({ upTo: 500_000, rate: 0.05 }),
    Object.freeze({ upTo: 1_000_000, rate: 0.20 }),
    Object.freeze({ upTo: Number.POSITIVE_INFINITY, rate: 0.30 })
  ])
});

function finiteNonNegative(value, fieldName) {
  const number = Number(value);
  if (!Number.isFinite(number) || number < 0) {
    throw new RangeError(`${fieldName} must be a finite, non-negative number.`);
  }
  return number;
}

export function slabTax(taxableIncome, slabs) {
  const taxable = finiteNonNegative(taxableIncome, 'Taxable income');
  let tax = 0;
  let previousLimit = 0;

  for (const slab of slabs) {
    const upper = Math.min(taxable, slab.upTo);
    if (upper > previousLimit) tax += (upper - previousLimit) * slab.rate;
    if (taxable <= slab.upTo) break;
    previousLimit = slab.upTo;
  }
  return Math.max(0, tax);
}

function applyCess(tax, config) {
  return Math.round(tax * (1 + config.cessRate) * 100) / 100;
}

export function calculateNewRegime(annualGross, config = CONFIG, eligibleForRebate = true) {
  const gross = finiteNonNegative(annualGross, 'Annual gross salary');
  const taxable = Math.max(0, gross - config.standardDeductionNew);
  const taxBeforeRelief = slabTax(taxable, config.newSlabs);

  if (eligibleForRebate && taxable <= config.rebateLimitNew) {
    return { regime: 'New tax regime', taxableIncome: taxable, tax: 0, taxBeforeCess: 0 };
  }

  // Marginal relief is capped against the income-tax liability including cess,
  // so the modeled total liability cannot exceed income above the rebate limit.
  const taxWithCess = applyCess(taxBeforeRelief, config);
  if (eligibleForRebate && taxable > config.rebateLimitNew) {
    const marginalCap = taxable - config.rebateLimitNew;
    const finalTax = Math.min(taxWithCess, marginalCap);
    return {
      regime: 'New tax regime',
      taxableIncome: taxable,
      tax: finalTax,
      taxBeforeCess: finalTax / (1 + config.cessRate)
    };
  }

  return {
    regime: 'New tax regime',
    taxableIncome: taxable,
    tax: taxWithCess,
    taxBeforeCess: taxBeforeRelief
  };
}

export function calculateOldRegime({ annualGross, annualHra, annualBasicDa, annualPf, annualPt, monthlyRent, other80C, metro }, config = CONFIG, eligibleForRebate = true) {
  const gross = finiteNonNegative(annualGross, 'Annual gross salary');
  const rent = finiteNonNegative(monthlyRent, 'Monthly rent');
  const investments = finiteNonNegative(other80C, 'Other 80C investments');
  const hra = finiteNonNegative(annualHra, 'Annual HRA');
  const basicDa = finiteNonNegative(annualBasicDa, 'Annual basic plus DA');
  const pf = finiteNonNegative(annualPf, 'Annual PF');
  const pt = finiteNonNegative(annualPt, 'Annual professional tax');

  const hraCapByRent = rent * 12 - 0.10 * basicDa;
  const hraCapBySalary = (metro ? config.hraMetroRate : config.hraNonMetroRate) * basicDa;
  const hraExemption = Math.max(0, Math.min(hra, hraCapByRent, hraCapBySalary));
  const deduction80C = Math.min(config.limit80C, pf + investments);
  const taxableIncome = Math.max(0, gross - config.standardDeductionOld - hraExemption - deduction80C - pt);
  let taxBeforeCess = slabTax(taxableIncome, config.oldSlabs);

  if (eligibleForRebate && taxableIncome <= config.rebateLimitOld) taxBeforeCess = 0;
  return {
    regime: 'Old tax regime',
    taxableIncome,
    hraExemption,
    deduction80C,
    taxBeforeCess,
    tax: applyCess(taxBeforeCess, config)
  };
}

export function calculatePayroll(employee, config = CONFIG) {
  const basic = finiteNonNegative(employee.basic, 'Basic salary');
  const monthlyRent = finiteNonNegative(employee.monthlyRent ?? 0, 'Monthly rent');
  const other80C = finiteNonNegative(employee.other80C ?? 0, 'Other 80C investments');
  const professionalTaxMode = employee.professionalTaxMode || 'andhra-pradesh';
  const manualPt = finiteNonNegative(employee.manualPt ?? 0, 'Monthly professional tax');
  if (!['andhra-pradesh', 'manual', 'none'].includes(professionalTaxMode)) {
    throw new RangeError('Choose a supported professional-tax calculation method.');
  }
  const metro = employee.metro !== false;
  const da = basic * config.daRate;
  const hra = basic * (metro ? config.hraMetroRate : config.hraNonMetroRate);
  const gross = basic + da + hra;
  const pf = (basic + da) * config.pfRate;
  const pt = config.professionalTaxMonthly(gross, { ...employee, professionalTaxMode, manualPt });
  if (pt > gross) {
    throw new RangeError('Monthly professional tax cannot exceed monthly gross salary.');
  }
  const annualGross = gross * 12;
  const annualPf = pf * 12;
  const annualPt = pt * 12;
  const annualHra = hra * 12;
  const annualBasicDa = (basic + da) * 12;
  const isResident = employee.eligibleForRebate !== false;

  const newTax = calculateNewRegime(annualGross, config, isResident);
  const oldTax = calculateOldRegime({
    annualGross, annualHra, annualBasicDa, annualPf, annualPt,
    monthlyRent, other80C, metro
  }, config, isResident);
  const chosen = newTax.tax <= oldTax.tax ? newTax : oldTax;
  const other = chosen === newTax ? oldTax : newTax;
  const tds = chosen.tax / 12;
  const totalDeductions = pf + pt + tds;
  const net = gross - totalDeductions;

  return {
    employee: { ...employee, basic, monthlyRent, other80C, metro, professionalTaxMode, manualPt },
    basic, da, hra, gross, pf, pt, tds, totalDeductions, net,
    annualGross, annualPf, annualPt, annualHra, annualBasicDa,
    annualNet: net * 12,
    newTax, oldTax, chosen, other,
    annualTaxSaving: Math.max(0, other.tax - chosen.tax),
    takeHomePercent: gross > 0 ? (net / gross) * 100 : 0
  };
}

export function formatRupees(value, options = {}) {
  const amount = Number.isFinite(Number(value)) ? Number(value) : 0;
  return new Intl.NumberFormat('en-IN', {
    style: 'currency', currency: 'INR', maximumFractionDigits: options.decimals ?? 0,
    minimumFractionDigits: options.decimals ?? 0
  }).format(amount);
}
