import { calculatePayroll, formatRupees } from './payroll.js';

const $ = (selector) => document.querySelector(selector);
const form = $('#payrollForm');
const toastElement = $('#toast');
let toastTimer;
let currentPayroll;

function toast(message) {
  toastElement.textContent = message;
  toastElement.classList.add('show');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => toastElement.classList.remove('show'), 2600);
}

function numberFrom(id) {
  const input = $(`#${id}`);
  const value = Number(input.value);
  return Number.isFinite(value) && value >= 0 ? value : NaN;
}

function getEmployee() {
  const month = $('#salaryMonth').value;
  return {
    id: $('#employeeId').value.trim(),
    name: $('#employeeName').value.trim(),
    department: $('#department').value,
    salaryMonth: month,
    basic: numberFrom('basicSalary'),
    metro: $('#cityType').value === 'metro',
    monthlyRent: numberFrom('monthlyRent'),
    other80C: numberFrom('other80C'),
    eligibleForRebate: true
  };
}

function validateEmployee(employee) {
  if (!employee.name) return 'Enter an employee name.';
  if (!employee.id) return 'Enter an employee ID.';
  if (!employee.salaryMonth) return 'Choose a salary month.';
  for (const [key, label] of [['basic', 'Basic salary'], ['monthlyRent', 'Monthly rent'], ['other80C', 'Other 80C investment']]) {
    if (!Number.isFinite(employee[key]) || employee[key] < 0) return `${label} must be zero or a positive amount.`;
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
  setText('chosenRegimeLabel', `${payroll.chosen.regime} selected`);
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
  const percent = value => `${Math.max(0, value / safeGross * 100)}%`;
  $('.bar-net').style.width = percent(Math.max(0, payroll.net));
  $('.bar-pf').style.width = percent(payroll.pf);
  $('.bar-pt').style.width = percent(payroll.pt);
  $('.bar-tax').style.width = percent(payroll.tds);
  $('#stackedBar').setAttribute('aria-label', `Take-home ${formatRupees(payroll.net)}, PF ${formatRupees(payroll.pf)}, professional tax ${formatRupees(payroll.pt)}, TDS ${formatRupees(payroll.tds)}`);
  setText('insightText', `Your estimated take-home is ${payroll.takeHomePercent.toFixed(1)}% of monthly gross salary.`);
}

function renderTaxComparison(payroll) {
  const { newTax, oldTax, chosen } = payroll;
  setText('newTaxAmount', `${formatRupees(newTax.tax)} `);
  $('#newTaxAmount').insertAdjacentHTML('beforeend', '<small>/ year</small>');
  setText('oldTaxAmount', `${formatRupees(oldTax.tax)} `);
  $('#oldTaxAmount').insertAdjacentHTML('beforeend', '<small>/ year</small>');
  setText('newTaxable', formatRupees(newTax.taxableIncome));
  setText('newTds', formatRupees(newTax.tax / 12));
  setText('oldTaxable', formatRupees(oldTax.taxableIncome));
  setText('oldTds', formatRupees(oldTax.tax / 12));
  setTaxCard('newTaxCard', 'newBadge', 'newFooter', chosen === newTax, 'Includes standard deduction and modeled rebate');
  setTaxCard('oldTaxCard', 'oldBadge', 'oldFooter', chosen === oldTax, 'Includes modeled HRA, 80C and professional tax deductions');
  const saving = Math.abs(newTax.tax - oldTax.tax);
  const regime = newTax.tax <= oldTax.tax ? 'New tax regime' : 'Old tax regime';
  setText('savingTitle', `${regime} has the lower modeled tax`);
  setText('savingDescription', saving === 0 ? 'Both modeled regimes produce the same annual income tax for these inputs.' : `Estimated annual income-tax difference between the two modeled regimes: ${formatRupees(saving)}.`);
  setText('savingAmount', `${formatRupees(saving)} `);
  $('#savingAmount').insertAdjacentHTML('beforeend', '<small>/ year</small>');
}

function renderScenarios(employee) {
  const values = [0, 50_000, 100_000, 150_000];
  const tbody = $('#scenarioRows');
  tbody.replaceChildren();
  values.forEach((investment) => {
    const scenario = calculatePayroll({ ...employee, other80C: investment });
    const oldRegimeNet = scenario.gross - scenario.pf - scenario.pt - scenario.oldTax.tax / 12;
    const tr = document.createElement('tr');
    const fields = [
      formatRupees(investment),
      formatRupees(scenario.oldTax.tax),
      formatRupees(scenario.newTax.tax),
      formatRupees(oldRegimeNet),
      ''
    ];
    fields.forEach((value) => {
      const td = document.createElement('td');
      td.textContent = value;
      tr.append(td);
    });
    const actionCell = tr.lastElementChild;
    const action = document.createElement('button');
    action.type = 'button';
    action.className = 'scenario-select';
    action.textContent = 'Use this';
    action.setAttribute('aria-label', `Use ${formatRupees(investment)} other 80C investment scenario`);
    action.addEventListener('click', () => {
      $('#other80C').value = String(investment);
      refreshPayroll();
      toast(`Applied ${formatRupees(investment)} annual other-80C scenario.`);
      $('#calculator').scrollIntoView({ behavior: 'smooth', block: 'start' });
    });
    actionCell.append(action);
    tbody.append(tr);
  });
}

function renderSlip(payroll) {
  const employee = payroll.employee;
  const period = monthName(employee.salaryMonth);
  setText('periodLabel', monthName(employee.salaryMonth, true));
  setText('paperMonth', monthName(employee.salaryMonth, true));
  setText('paperPeriod', period);
  setText('paperName', employee.name || '—');
  setText('paperId', employee.id || '—');
  setText('paperDepartment', employee.department || '—');
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

function refreshPayroll() {
  const employee = getEmployee();
  const error = validateEmployee(employee);
  const errorElement = $('#formError');
  if (error) {
    errorElement.textContent = error;
    errorElement.hidden = false;
    return false;
  }
  try {
    currentPayroll = calculatePayroll(employee);
    errorElement.hidden = true;
    renderBreakdown(currentPayroll);
    renderTaxComparison(currentPayroll);
    renderScenarios(employee);
    renderSlip(currentPayroll);
    return true;
  } catch (error) {
    errorElement.textContent = error instanceof Error ? error.message : 'Unable to calculate payroll.';
    errorElement.hidden = false;
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
    'DEMO DISCLAIMER: Estimates only. Validate applicable rules before actual payroll use.'
  ].join('\n');
}

function downloadSlip() {
  if (!refreshPayroll()) return;
  const text = salarySlipText(currentPayroll);
  const blob = new Blob([text], { type: 'text/plain;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  const safeId = (currentPayroll.employee.id || 'employee').replace(/[^a-z0-9_-]/gi, '_');
  link.href = url;
  link.download = `salary_slip_${safeId}_${currentPayroll.employee.salaryMonth}.txt`;
  document.body.append(link);
  link.click();
  link.remove();
  URL.revokeObjectURL(url);
  toast('Salary slip downloaded.');
}

form.addEventListener('submit', (event) => {
  event.preventDefault();
  if (refreshPayroll()) toast('Payroll calculated successfully.');
});
form.addEventListener('input', () => refreshPayroll());
form.addEventListener('change', () => refreshPayroll());
$('#loadDemo').addEventListener('click', () => {
  $('#employeeName').value = 'Asha Verma';
  $('#employeeId').value = 'E101';
  $('#department').value = 'Engineering';
  $('#basicSalary').value = '60000';
  $('#cityType').value = 'metro';
  $('#monthlyRent').value = '25000';
  $('#other80C').value = '40000';
  if (!$('#salaryMonth').value) {
    const today = new Date();
    $('#salaryMonth').value = `${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, '0')}`;
  }
  refreshPayroll();
  toast('Sample employee loaded.');
});
$('#downloadSlip').addEventListener('click', downloadSlip);
$('#downloadSlipTop').addEventListener('click', () => {
  downloadSlip();
  $('#salary-slip').scrollIntoView({ behavior: 'smooth', block: 'start' });
});
$('#printSlip').addEventListener('click', () => {
  if (refreshPayroll()) window.print();
});

// Highlight the visible workspace section in the sidebar (desktop).
const navLinks = [...document.querySelectorAll('.nav-link')];
const sectionObserver = new IntersectionObserver((entries) => {
  const visible = entries.filter(entry => entry.isIntersecting).sort((a, b) => b.intersectionRatio - a.intersectionRatio)[0];
  if (!visible) return;
  navLinks.forEach(link => link.classList.toggle('active', link.getAttribute('href') === `#${visible.target.id}`));
}, { rootMargin: '-15% 0px -65% 0px', threshold: [0, 0.1, 0.25, 0.5] });
['overview', 'calculator', 'tax-advisor', 'salary-slip'].forEach(id => {
  const target = document.getElementById(id);
  if (target) sectionObserver.observe(target);
});

// Keep the first-run pay period current; edits are respected after initialization.
const now = new Date();
$('#salaryMonth').value = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`;
refreshPayroll();
