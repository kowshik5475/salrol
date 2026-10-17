const formatRupees = (value) => new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 0 }).format(Number(value) || 0);

const $ = (selector) => document.querySelector(selector);
const form = $('#payrollForm');
const toastElement = $('#toast');
let toastTimer;
let currentPayroll = null;
let hasCalculated = false;
let requestSequence = 0;
let inputDebounce = null;

function toast(message) {
  toastElement.textContent = message;
  toastElement.classList.add('show');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => toastElement.classList.remove('show'), 2600);
}

function numberFrom(id) {
  const input = $(`#${id}`);
  const value = Number(input.value);
  return input.value.trim() !== '' && Number.isFinite(value) && value >= 0 ? value : NaN;
}

function getEmployee() {
  return {
    id: $('#employeeId').value.trim(),
    name: $('#employeeName').value.trim(),
    department: $('#department').value,
    salaryMonth: $('#salaryMonth').value,
    basic: numberFrom('basicSalary'),
    metro: $('#cityType').value === 'metro',
    monthlyRent: numberFrom('monthlyRent'),
    other80C: numberFrom('other80C'),
    eligibleForRebate: $('#rebateEligibility').value === 'eligible',
    professionalTaxMode: $('#professionalTaxMode').value,
    pfContributionMode: $('#pfContributionMode').value,
    manualPt: $('#professionalTaxMode').value === 'manual' ? numberFrom('manualPt') : 0
  };
}

function validateEmployee(employee) {
  if (!employee.name) return 'Enter an employee name.';
  if (!employee.id) return 'Enter an employee ID.';
  if (!employee.department) return 'Select a department.';
  if (!employee.salaryMonth || !/^\d{4}-\d{2}$/.test(employee.salaryMonth)) return 'Choose a valid salary month.';
  if (!$('#cityType').value) return 'Select the applicable city type for the HRA estimate.';
  if (!$('#rebateEligibility').value) return 'Choose the employee’s Section 87A rebate eligibility.';
  if (!employee.professionalTaxMode) return 'Choose a professional-tax calculation method.';
  if (!employee.pfContributionMode) return 'Choose an employee PF contribution basis.';

  const numericFields = [
    ['basic', 'Basic salary', 100_000_000],
    ['monthlyRent', 'Monthly rent', 100_000_000],
    ['other80C', 'Other 80C investments', 100_000_000],
    ['manualPt', 'Monthly professional tax', 100_000]
  ];
  for (const [key, label, max] of numericFields) {
    if (!Number.isFinite(employee[key]) || employee[key] < 0 || employee[key] > max) {
      return `${label} must be between ₹0 and ${formatRupees(max)}.`;
    }
  }
  return '';
}

function monthName(value, upper = false) {
  if (!value || !/^\d{4}-\d{2}$/.test(value)) return 'PAY PERIOD';
  const [year, month] = value.split('-').map(Number);
  const label = new Intl.DateTimeFormat('en-IN', { month: 'long', year: 'numeric' }).format(new Date(year, month - 1, 1));
  return upper ? label.toLocaleUpperCase('en-IN') : label;
}

function setText(id, value) {
  const element = $(`#${id}`);
  if (element) element.textContent = value;
}

function setAmountWithPeriod(id, value, suffix = '/ year') {
  const element = $(`#${id}`);
  element.replaceChildren(document.createTextNode(value));
  const small = document.createElement('small');
  small.textContent = suffix;
  element.append(' ', small);
}

function setSlipActionsEnabled(enabled) {
  ['downloadSlipTop', 'downloadSlip', 'printSlip'].forEach((id) => {
    $(`#${id}`).disabled = !enabled;
  });
}

function setTaxCard(cardId, badgeId, footerId, chosen, label) {
  const card = $(`#${cardId}`);
  const badge = $(`#${badgeId}`);
  card.classList.toggle('recommended', chosen);
  badge.classList.toggle('hidden', !chosen);
  setText(footerId, chosen ? 'Selected for lower modeled tax' : label);
}

function renderBreakdown(payroll) {
  setText('netMetric', formatRupees(payroll.net));
  setText('grossMetric', formatRupees(payroll.gross));
  setText('deductionMetric', formatRupees(payroll.totalDeductions));
  setText('annualNetMetric', formatRupees(payroll.annualNet));
  setText('chosenRegimeLabel', `${payroll.chosen.regime} selected · estimate`);
  setText('grossBreakdown', formatRupees(payroll.gross));
  setText('legendNet', formatRupees(payroll.net));
  setText('legendPf', formatRupees(payroll.pf));
  setText('legendPt', formatRupees(payroll.pt));
  setText('legendTds', formatRupees(payroll.tds));
  setText('basicBreakdown', formatRupees(payroll.basic));
  setText('daBreakdown', formatRupees(payroll.da));
  setText('hraBreakdown', formatRupees(payroll.hra));
  setText('grossTotalBreakdown', formatRupees(payroll.gross));

  const safeGross = payroll.gross > 0 ? payroll.gross : 1;
  const percent = (value) => `${Math.max(0, value / safeGross * 100)}%`;
  $('.bar-net').style.width = percent(Math.max(0, payroll.net));
  $('.bar-pf').style.width = percent(payroll.pf);
  $('.bar-pt').style.width = percent(payroll.pt);
  $('.bar-tax').style.width = percent(payroll.tds);
  $('#stackedBar').setAttribute('aria-label', `Take-home ${formatRupees(payroll.net)}, PF ${formatRupees(payroll.pf)}, professional tax ${formatRupees(payroll.pt)}, TDS ${formatRupees(payroll.tds)}`);
  setText('insightText', `Your estimated take-home is ${payroll.takeHomePercent.toFixed(1)}% of monthly gross salary.`);
}

function renderTaxComparison(payroll) {
  const { newTax, oldTax, chosen } = payroll;
  setAmountWithPeriod('newTaxAmount', formatRupees(newTax.tax));
  setAmountWithPeriod('oldTaxAmount', formatRupees(oldTax.tax));
  setText('newTaxable', formatRupees(newTax.taxableIncome));
  setText('newTds', formatRupees(newTax.tax / 12));
  setText('oldTaxable', formatRupees(oldTax.taxableIncome));
  setText('oldTds', formatRupees(oldTax.tax / 12));
  setTaxCard('newTaxCard', 'newBadge', 'newFooter', chosen.regime === newTax.regime, 'Includes modeled standard deduction and rebate rules');
  setTaxCard('oldTaxCard', 'oldBadge', 'oldFooter', chosen.regime === oldTax.regime, 'Includes modeled HRA, 80C and professional-tax deductions');
  const saving = Math.abs(newTax.tax - oldTax.tax);
  const regime = newTax.tax <= oldTax.tax ? 'New tax regime' : 'Old tax regime';
  setText('savingTitle', `${regime} has the lower modeled tax`);
  setText('savingDescription', saving === 0
    ? 'Both modeled regimes produce the same annual income-tax estimate for these inputs.'
    : `Estimated annual income-tax difference between the two modeled regimes: ${formatRupees(saving)}.`);
  setAmountWithPeriod('savingAmount', formatRupees(saving));
}

function renderScenarios(employee, scenarios) {
  const tbody = $('#scenarioRows');
  tbody.replaceChildren();
  scenarios.forEach((scenario) => {
    const investment = scenario.investment;
    const tr = document.createElement('tr');
    [
      formatRupees(investment),
      formatRupees(scenario.oldTax),
      formatRupees(scenario.newTax),
      formatRupees(scenario.oldRegimeNet)
    ].forEach((value) => {
      const td = document.createElement('td');
      td.textContent = value;
      tr.append(td);
    });
    const actionCell = document.createElement('td');
    const action = document.createElement('button');
    action.type = 'button';
    action.className = 'scenario-select';
    action.textContent = Math.abs(investment - employee.other80C) < 0.001 ? 'Current' : 'Apply';
    action.disabled = Math.abs(investment - employee.other80C) < 0.001;
    action.setAttribute('aria-label', `Apply ${formatRupees(investment)} annual other-80C investment scenario`);
    action.addEventListener('click', async () => {
      $('#other80C').value = String(investment);
      const ok = await refreshPayroll({ live: true });
      if (ok) toast(`Applied ${formatRupees(investment)} annual 80C investment amount.`);
      $('#calculator').scrollIntoView({ behavior: 'smooth', block: 'start' });
    });
    actionCell.append(action);
    tr.append(actionCell);
    tbody.append(tr);
  });
}

function renderSlip(payroll) {
  const employee = payroll.employee;
  const period = monthName(employee.salaryMonth);
  setText('periodLabel', monthName(employee.salaryMonth, true));
  setText('paperMonth', monthName(employee.salaryMonth, true));
  setText('paperPeriod', period);
  setText('paperName', employee.name);
  setText('paperId', employee.id);
  setText('paperDepartment', employee.department);
  setText('paperBasic', formatRupees(payroll.basic));
  setText('paperDa', formatRupees(payroll.da));
  setText('paperHra', formatRupees(payroll.hra));
  setText('paperGross', formatRupees(payroll.gross));
  setText('paperPf', formatRupees(payroll.pf));
  setText('paperPt', formatRupees(payroll.pt));
  setText('paperTds', formatRupees(payroll.tds));
  setText('paperDeductions', formatRupees(payroll.totalDeductions));
  setText('paperRegime', `${payroll.chosen.regime} · estimate`);
  setText('paperNet', formatRupees(payroll.net));
  setText('paperAnnualGross', formatRupees(payroll.annualGross));
  setText('paperAnnualTax', formatRupees(payroll.chosen.tax));
  setText('paperAnnualNet', formatRupees(payroll.annualNet));
}

function renderEmptyState() {
  currentPayroll = null;
  setSlipActionsEnabled(false);
  const ids = [
    'netMetric', 'grossMetric', 'deductionMetric', 'annualNetMetric', 'grossBreakdown',
    'legendNet', 'legendPf', 'legendPt', 'legendTds', 'basicBreakdown', 'daBreakdown',
    'hraBreakdown', 'grossTotalBreakdown', 'newTaxAmount', 'oldTaxAmount', 'newTaxable',
    'newTds', 'oldTaxable', 'oldTds', 'savingAmount', 'paperName', 'paperId',
    'paperDepartment', 'paperBasic', 'paperDa', 'paperHra', 'paperGross', 'paperPf',
    'paperPt', 'paperTds', 'paperDeductions', 'paperNet', 'paperAnnualGross',
    'paperAnnualTax', 'paperAnnualNet'
  ];
  ids.forEach((id) => setText(id, '—'));
  setText('chosenRegimeLabel', 'Complete the form to calculate');
  setText('savingTitle', 'Tax regime comparison');
  setText('savingDescription', 'Calculate payroll to compare annual tax estimates.');
  setText('insightText', 'Enter employee details and calculate payroll to see the breakdown.');
  setText('paperMonth', monthName($('#salaryMonth').value, true));
  setText('paperPeriod', monthName($('#salaryMonth').value));
  setText('paperRegime', 'Estimate not yet calculated');
  setText('periodLabel', monthName($('#salaryMonth').value, true));
  setText('newFooter', 'Enter details to compare the modeled regime');
  setText('oldFooter', 'Enter details to compare the modeled regime');
  $('#newTaxCard').classList.remove('recommended');
  $('#oldTaxCard').classList.remove('recommended');
  $('#newBadge').classList.add('hidden');
  $('#oldBadge').classList.add('hidden');
  ['.bar-net', '.bar-pf', '.bar-pt', '.bar-tax'].forEach((selector) => { $(selector).style.width = '0%'; });
  $('#stackedBar').setAttribute('aria-label', 'Calculate payroll to view take-home and deductions');
  $('#scenarioRows').replaceChildren();
  const row = document.createElement('tr');
  const cell = document.createElement('td');
  cell.colSpan = 5;
  cell.textContent = 'Calculate payroll to explore investment scenarios.';
  row.append(cell);
  $('#scenarioRows').append(row);
}

async function refreshPayroll({ live = false } = {}) {
  const sequence = ++requestSequence;
  const employee = getEmployee();
  const errorElement = $('#formError');
  const error = validateEmployee(employee);
  if (error) {
    if (hasCalculated || !live) {
      errorElement.textContent = error;
      errorElement.hidden = false;
      renderEmptyState();
    }
    return false;
  }

  try {
    const payload = new URLSearchParams({
      id: employee.id,
      name: employee.name,
      department: employee.department,
      salaryMonth: employee.salaryMonth,
      basic: String(employee.basic),
      cityType: employee.metro ? 'metro' : 'nonmetro',
      monthlyRent: String(employee.monthlyRent),
      other80C: String(employee.other80C),
      rebateEligibility: $('#rebateEligibility').value,
      professionalTaxMode: employee.professionalTaxMode,
      pfContributionMode: employee.pfContributionMode,
      manualPt: String(employee.manualPt)
    });
    const response = await fetch('/api/payroll', {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8' },
      body: payload.toString()
    });
    if (sequence !== requestSequence) return false;
    let data;
    try {
      data = await response.json();
    } catch {
      throw new Error('The Java server returned an unreadable response. Please try again.');
    }
    if (!response.ok) {
      errorElement.textContent = data.error || 'The Java payroll server rejected these inputs.';
      errorElement.hidden = false;
      renderEmptyState();
      return false;
    }

    currentPayroll = data;
    hasCalculated = true;
    errorElement.hidden = true;
    renderBreakdown(currentPayroll);
    renderTaxComparison(currentPayroll);
    renderScenarios(employee, currentPayroll.scenarios);
    renderSlip(currentPayroll);
    setSlipActionsEnabled(true);
    return true;
  } catch (error) {
    if (sequence !== requestSequence) return false;
    const message = error instanceof Error ? error.message : 'Unable to calculate payroll.';
    // Only show startup advice for connection failures; never append it to validation errors.
    const isConnectionFailure = error instanceof TypeError;
    errorElement.textContent = isConnectionFailure
      ? 'Cannot connect to the Java payroll server. Start Paywise with run.bat (Windows) or ./run.sh (macOS/Linux), then open http://localhost:8080.'
      : message;
    errorElement.hidden = false;
    renderEmptyState();
    return false;
  }
}

function salarySlipText(payroll) {
  const employee = payroll.employee;
  const period = monthName(employee.salaryMonth);
  return [
    '='.repeat(58),
    `PAYWISE — SALARY SLIP | ${period.toUpperCase()}`,
    '='.repeat(58),
    `Employee : ${employee.name}`,
    `ID       : ${employee.id}`,
    `Dept.    : ${employee.department}`,
    '-'.repeat(58),
    'EARNINGS',
    `Basic salary                    ${formatRupees(payroll.basic)}`,
    `Dearness allowance              ${formatRupees(payroll.da)}`,
    `House rent allowance            ${formatRupees(payroll.hra)}`,
    `GROSS SALARY                    ${formatRupees(payroll.gross)}`,
    '-'.repeat(58),
    'DEDUCTIONS',
    `Provident fund                  ${formatRupees(payroll.pf)}`,
    `Professional tax                ${formatRupees(payroll.pt)}`,
    `Income tax / TDS                ${formatRupees(payroll.tds)}`,
    `TOTAL DEDUCTIONS                ${formatRupees(payroll.totalDeductions)}`,
    '='.repeat(58),
    `NET SALARY PAYABLE              ${formatRupees(payroll.net)}`,
    '='.repeat(58),
    `Annual gross projection         ${formatRupees(payroll.annualGross)}`,
    `Annual tax estimate             ${formatRupees(payroll.chosen.tax)}`,
    `Annual net projection           ${formatRupees(payroll.annualNet)}`,
    `Selected regime                 ${payroll.chosen.regime}`,
    '',
    'ESTIMATE ONLY: Review all applicable rules before using this for payroll processing.'
  ].join('\n');
}

function downloadSlip() {
  if (!currentPayroll) { toast('Calculate payroll before exporting a salary slip.'); return; }
  const text = salarySlipText(currentPayroll);
  const blob = new Blob([text], { type: 'text/plain;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  const safeId = currentPayroll.employee.id.replace(/[^a-z0-9_-]/gi, '_');
  link.href = url;
  link.download = `salary_slip_${safeId}_${currentPayroll.employee.salaryMonth}.txt`;
  document.body.append(link);
  link.click();
  link.remove();
  URL.revokeObjectURL(url);
  toast('Salary slip downloaded.');
}

$('#professionalTaxMode').addEventListener('change', () => {
  const isManual = $('#professionalTaxMode').value === 'manual';
  $('#manualPtField').classList.toggle('hidden', !isManual);
  $('#manualPt').required = isManual;
  if (hasCalculated) refreshPayroll({ live: true });
});

form.addEventListener('submit', async (event) => {
  event.preventDefault();
  if (await refreshPayroll()) toast('Payroll calculated by the Java engine.');
});
form.addEventListener('input', () => {
  if (!hasCalculated) return;
  clearTimeout(inputDebounce);
  inputDebounce = setTimeout(() => refreshPayroll({ live: true }), 280);
});
form.addEventListener('change', () => {
  if (hasCalculated) refreshPayroll({ live: true });
});
$('#clearForm').addEventListener('click', () => {
  form.reset();
  const today = new Date();
  $('#salaryMonth').value = `${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, '0')}`;
  $('#manualPtField').classList.add('hidden');
  $('#manualPt').required = false;
  hasCalculated = false;
  $('#formError').hidden = true;
  $('#formError').textContent = '';
  renderEmptyState();
  toast('Form cleared. Enter employee details to calculate a new payroll.');
});

$('#downloadSlip').addEventListener('click', downloadSlip);
$('#downloadSlipTop').addEventListener('click', () => {
  downloadSlip();
  if (currentPayroll) $('#salary-slip').scrollIntoView({ behavior: 'smooth', block: 'start' });
});
$('#printSlip').addEventListener('click', () => {
  if (currentPayroll) window.print();
});

// Highlight the visible workspace section in the sidebar (desktop).
const navLinks = [...document.querySelectorAll('.nav-link')];
if ('IntersectionObserver' in window) {
  const sectionObserver = new IntersectionObserver((entries) => {
    const visible = entries.filter(entry => entry.isIntersecting).sort((a, b) => b.intersectionRatio - a.intersectionRatio)[0];
    if (!visible) return;
    navLinks.forEach(link => link.classList.toggle('active', link.getAttribute('href') === `#${visible.target.id}`));
  }, { rootMargin: '-15% 0px -65% 0px', threshold: [0, 0.1, 0.25, 0.5] });
  ['overview', 'calculator', 'tax-advisor', 'salary-slip'].forEach(id => {
    const target = document.getElementById(id);
    if (target) sectionObserver.observe(target);
  });
}

// Start with no employee or payroll figures preloaded.
const now = new Date();
$('#salaryMonth').value = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`;
renderEmptyState();

// Confirm that the real Java backend is reachable; no salary arithmetic is done in this file.
fetch('/api/health').then((response) => {
  if (!response.ok) throw new Error('Java backend unavailable');
  return response.json();
}).then((health) => {
  const badge = document.querySelector('.workspace-pill');
  if (badge) badge.innerHTML = '<span></span> JAVA ENGINE ONLINE';
  document.title = 'Paywise — Java Payroll Calculator';
}).catch(() => {
  const badge = document.querySelector('.workspace-pill');
  if (badge) badge.innerHTML = '<span></span> START JAVA SERVER';
  const error = $('#formError');
  error.textContent = 'Java server is not connected. Start the app with run.bat (Windows) or ./run.sh (macOS/Linux), then open http://localhost:8080.';
  error.hidden = false;
});
