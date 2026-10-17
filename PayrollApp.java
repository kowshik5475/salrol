import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Paywise payroll calculation engine.
 * All salary and tax calculations are performed in Java. The browser is only a UI.
 * Tax is an estimate for a salary-only example and is not legal/payroll advice.
 */
public final class PayrollApp {
    private PayrollApp() {}

    public record Employee(
            String id,
            String name,
            String department,
            String salaryMonth,
            double basic,
            boolean metro,
            double monthlyRent,
            double other80C,
            boolean eligibleForRebate,
            String professionalTaxMode,
            String pfContributionMode,
            double manualPt) {}

    public record Slab(double upTo, double rate) {}

    public record TaxResult(
            String regime,
            double taxableIncome,
            double tax,
            double taxBeforeCess,
            double hraExemption,
            double deduction80C) {}

    public record Scenario(
            double investment,
            double oldTax,
            double newTax,
            double oldRegimeNet,
            String lowerTaxRegime,
            double lowerTax) {}

    public record Result(
            Employee employee,
            double basic,
            double da,
            double hra,
            double gross,
            double pf,
            double pt,
            double tds,
            double totalDeductions,
            double net,
            double annualGross,
            double annualPf,
            double annualPt,
            double annualHra,
            double annualBasicDa,
            double annualNet,
            TaxResult newTax,
            TaxResult oldTax,
            TaxResult chosen,
            TaxResult other,
            double annualTaxSaving,
            double takeHomePercent,
            List<Scenario> scenarios) {}

    /** Business rules are kept together for easy review and future updates. */
    public static final class Config {
        public double daRate = 0.10;
        public double hraMetroRate = 0.50;
        public double hraNonMetroRate = 0.40;
        public double pfRate = 0.12;
        /** Simplified employee PF wage ceiling used only when that mode is selected. */
        public double pfMonthlyWageCeiling = 15_000;
        public double standardDeductionOld = 50_000;
        public double standardDeductionNew = 75_000;
        public double limit80C = 150_000;
        public double cessRate = 0.04;
        public double rebateLimitNew = 1_200_000;
        public double rebateLimitOld = 500_000;

        // Simplified salary-only tax bands for AY 2026-27 / TY 2026-27.
        // Verify statutory applicability, deductions, surcharge and special income before real payroll use.
        public Slab[] newSlabs = {
                new Slab(400_000, 0.00),
                new Slab(800_000, 0.05),
                new Slab(1_200_000, 0.10),
                new Slab(1_600_000, 0.15),
                new Slab(2_000_000, 0.20),
                new Slab(2_400_000, 0.25),
                new Slab(Double.MAX_VALUE, 0.30)
        };
        public Slab[] oldSlabs = {
                new Slab(250_000, 0.00),
                new Slab(500_000, 0.05),
                new Slab(1_000_000, 0.20),
                new Slab(Double.MAX_VALUE, 0.30)
        };

        /** Andhra Pradesh monthly salary-earner schedule; may require payroll-specific handling. */
        public double professionalTaxMonthly(double monthlyGross, Employee employee) {
            return switch (employee.professionalTaxMode()) {
                case "andhra-pradesh" -> monthlyGross <= 15_000 ? 0 : monthlyGross <= 20_000 ? 150 : 200;
                case "manual" -> employee.manualPt();
                case "none" -> 0;
                default -> throw new IllegalArgumentException("Select a supported professional-tax method.");
            };
        }
    }

    public static Result calculate(Employee employee) {
        return calculate(employee, new Config());
    }

    public static Result calculate(Employee e, Config c) {
        validateEmployee(e);

        double basic = money(e.basic());
        double da = money(basic * c.daRate);
        double hra = money(basic * (e.metro() ? c.hraMetroRate : c.hraNonMetroRate));
        double gross = money(basic + da + hra);
        double pfWageBase = switch (e.pfContributionMode()) {
            case "full-basic-da" -> basic + da;
            case "wage-ceiling" -> Math.min(basic + da, c.pfMonthlyWageCeiling);
            default -> throw new IllegalArgumentException("Select a supported PF contribution basis.");
        };
        double pf = money(pfWageBase * c.pfRate);
        double pt = money(c.professionalTaxMonthly(gross, e));
        if (pt > gross) throw new IllegalArgumentException("Professional tax cannot exceed monthly gross salary.");

        double annualGross = money(gross * 12);
        double annualPf = money(pf * 12);
        double annualPt = money(pt * 12);
        double annualHra = money(hra * 12);
        double annualBasicDa = money((basic + da) * 12);

        TaxResult newTax = newTax(annualGross, e.eligibleForRebate(), c);
        TaxResult oldTax = oldTax(annualGross, annualHra, annualBasicDa, annualPf,
                annualPt, e.monthlyRent(), e.other80C(), e.metro(), e.eligibleForRebate(), c);
        TaxResult chosen = newTax.tax() <= oldTax.tax() ? newTax : oldTax;
        TaxResult other = chosen == newTax ? oldTax : newTax;
        double tds = money(chosen.tax() / 12);
        double deductions = money(pf + pt + tds);
        double net = money(gross - deductions);
        double annualNet = money(net * 12);
        double saving = money(Math.max(0, other.tax() - chosen.tax()));
        double takeHomePercent = gross > 0 ? net / gross * 100 : 0;

        List<Scenario> scenarios = scenarios(e, c, gross, pf, pt, annualGross,
                annualHra, annualBasicDa, annualPf, annualPt);

        return new Result(e, basic, da, hra, gross, pf, pt, tds, deductions, net,
                annualGross, annualPf, annualPt, annualHra, annualBasicDa, annualNet,
                newTax, oldTax, chosen, other, saving, takeHomePercent, scenarios);
    }

    private static List<Scenario> scenarios(Employee e, Config c, double gross, double pf, double pt,
            double annualGross, double annualHra, double annualBasicDa, double annualPf, double annualPt) {
        double[] defaults = {0, 50_000, 100_000, 150_000};
        List<Double> values = new ArrayList<>();
        for (double value : defaults) values.add(value);
        if (values.stream().noneMatch(v -> Math.abs(v - e.other80C()) < 0.001)) values.add(e.other80C());
        values.sort(Double::compareTo);

        List<Scenario> result = new ArrayList<>();
        for (double investment : values) {
            TaxResult old = oldTax(annualGross, annualHra, annualBasicDa, annualPf,
                    annualPt, e.monthlyRent(), investment, e.metro(), e.eligibleForRebate(), c);
            TaxResult newer = newTax(annualGross, e.eligibleForRebate(), c);
            double oldNet = money(gross - pf - pt - old.tax() / 12);
            TaxResult best = newer.tax() <= old.tax() ? newer : old;
            result.add(new Scenario(investment, old.tax(), newer.tax(), oldNet, best.regime(), best.tax()));
        }
        return result;
    }

    public static double slabTax(double taxableIncome, Slab[] slabs) {
        requireNonNegative(taxableIncome, "Taxable income");
        double tax = 0;
        double previous = 0;
        for (Slab slab : slabs) {
            if (taxableIncome > previous) {
                tax += (Math.min(taxableIncome, slab.upTo()) - previous) * slab.rate();
            }
            if (taxableIncome <= slab.upTo()) break;
            previous = slab.upTo();
        }
        // Do not round here: tax across all slabs must remain full precision until cess/rebate.
        return Math.max(0, tax);
    }

    /** Round taxable income or final annual tax to the nearest ₹10 (paise are ignored by the model). */
    static double roundTaxToTen(double amount) {
        if (!Double.isFinite(amount) || amount < 0) {
            throw new IllegalArgumentException("Amount must be a finite, non-negative value.");
        }
        return Math.floor(amount / 10.0 + 0.5) * 10.0;
    }

    private static TaxResult newTax(double annualGross, boolean rebateEligible, Config c) {
        double taxable = roundTaxToTen(Math.max(0, annualGross - c.standardDeductionNew));
        double taxBeforeCess = slabTax(taxable, c.newSlabs);
        double taxBeforeCessAfterRelief = taxBeforeCess;

        if (rebateEligible && taxable <= c.rebateLimitNew) {
            taxBeforeCessAfterRelief = 0;
        } else if (rebateEligible && taxable > c.rebateLimitNew) {
            // Apply marginal relief to the liability including cess, before statutory rounding.
            double taxCapIncludingCess = taxable - c.rebateLimitNew;
            taxBeforeCessAfterRelief = Math.min(taxBeforeCess, taxCapIncludingCess / (1 + c.cessRate));
        }

        // Convention: keep intermediate slab math unrounded; add cess/apply relief, then round
        // the final annual tax to the nearest ₹10 as a modeled Indian tax-payable convention.
        double totalTax = roundTaxToTen(taxBeforeCessAfterRelief * (1 + c.cessRate));
        return new TaxResult("New tax regime", money(taxable), totalTax,
                money(taxBeforeCessAfterRelief), 0, 0);
    }

    private static TaxResult oldTax(double annualGross, double annualHra, double annualBasicDa,
            double annualPf, double annualPt, double monthlyRent, double other80C,
            boolean metro, boolean rebateEligible, Config c) {
        double hraRentLimit = monthlyRent * 12 - 0.10 * annualBasicDa;
        double hraSalaryLimit = (metro ? c.hraMetroRate : c.hraNonMetroRate) * annualBasicDa;
        double hraExemption = money(Math.max(0, Math.min(annualHra, Math.min(hraRentLimit, hraSalaryLimit))));
        double deduction80C = money(Math.min(c.limit80C, annualPf + other80C));
        double taxable = roundTaxToTen(Math.max(0, annualGross - c.standardDeductionOld - hraExemption - deduction80C - annualPt));
        double beforeCess = slabTax(taxable, c.oldSlabs);
        if (rebateEligible && taxable <= c.rebateLimitOld) beforeCess = 0;
        // Same convention as the new regime: add cess before rounding annual tax to ₹10.
        double tax = roundTaxToTen(beforeCess * (1 + c.cessRate));
        return new TaxResult("Old tax regime", taxable, tax, money(beforeCess), hraExemption, deduction80C);
    }

    private static void validateEmployee(Employee e) {
        if (e == null) throw new IllegalArgumentException("Employee details are required.");
        if (blank(e.id())) throw new IllegalArgumentException("Enter an employee ID.");
        if (e.id().length() > 60) throw new IllegalArgumentException("Employee ID must be 60 characters or fewer.");
        if (blank(e.name())) throw new IllegalArgumentException("Enter an employee name.");
        if (e.name().length() > 120) throw new IllegalArgumentException("Employee name must be 120 characters or fewer.");
        if (blank(e.department())) throw new IllegalArgumentException("Select a department.");
        if (e.salaryMonth() == null || !e.salaryMonth().matches("\\d{4}-(0[1-9]|1[0-2])")) throw new IllegalArgumentException("Choose a valid salary month.");
        requireNonNegative(e.basic(), "Basic salary");
        requireNonNegative(e.monthlyRent(), "Monthly rent");
        requireNonNegative(e.other80C(), "Other 80C investments");
        requireNonNegative(e.manualPt(), "Monthly professional tax");
        if (e.basic() > 100_000_000) throw new IllegalArgumentException("Basic salary is above the supported limit.");
        if (e.monthlyRent() > 100_000_000) throw new IllegalArgumentException("Monthly rent is above the supported limit.");
        if (e.other80C() > 100_000_000) throw new IllegalArgumentException("80C investments are above the supported limit.");
        if (blank(e.professionalTaxMode()) || !List.of("andhra-pradesh", "manual", "none").contains(e.professionalTaxMode())) {
            throw new IllegalArgumentException("Select a supported professional-tax method.");
        }
        if (blank(e.pfContributionMode()) || !List.of("full-basic-da", "wage-ceiling").contains(e.pfContributionMode())) {
            throw new IllegalArgumentException("Select a supported PF contribution basis.");
        }
        if (e.manualPt() > 100_000) throw new IllegalArgumentException("Professional tax is above the supported limit.");
    }

    private static boolean blank(String value) { return value == null || value.trim().isEmpty(); }

    private static void requireNonNegative(double value, String label) {
        if (!Double.isFinite(value) || value < 0) {
            throw new IllegalArgumentException(label + " must be a finite, non-negative number.");
        }
    }

    private static double money(double value) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException("Calculation produced a non-finite amount.");
        return Math.round(value * 100.0) / 100.0;
    }

    /** Converts a payroll result into JSON-compatible values for the HTTP layer. */
    public static Map<String, Object> toMap(Result r) {
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> employee = new LinkedHashMap<>();
        Employee e = r.employee();
        employee.put("id", e.id());
        employee.put("name", e.name());
        employee.put("department", e.department());
        employee.put("salaryMonth", e.salaryMonth());
        employee.put("basic", e.basic());
        employee.put("metro", e.metro());
        employee.put("monthlyRent", e.monthlyRent());
        employee.put("other80C", e.other80C());
        employee.put("eligibleForRebate", e.eligibleForRebate());
        employee.put("professionalTaxMode", e.professionalTaxMode());
        employee.put("pfContributionMode", e.pfContributionMode());
        employee.put("manualPt", e.manualPt());
        root.put("employee", employee);
        root.put("basic", r.basic()); root.put("da", r.da()); root.put("hra", r.hra());
        root.put("gross", r.gross()); root.put("pf", r.pf()); root.put("pt", r.pt());
        root.put("tds", r.tds()); root.put("totalDeductions", r.totalDeductions()); root.put("net", r.net());
        root.put("annualGross", r.annualGross()); root.put("annualPf", r.annualPf());
        root.put("annualPt", r.annualPt()); root.put("annualHra", r.annualHra());
        root.put("annualBasicDa", r.annualBasicDa()); root.put("annualNet", r.annualNet());
        root.put("newTax", taxMap(r.newTax())); root.put("oldTax", taxMap(r.oldTax()));
        root.put("chosen", taxMap(r.chosen())); root.put("other", taxMap(r.other()));
        root.put("annualTaxSaving", r.annualTaxSaving()); root.put("takeHomePercent", r.takeHomePercent());
        List<Map<String, Object>> scenarios = new ArrayList<>();
        for (Scenario scenario : r.scenarios()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("investment", scenario.investment());
            item.put("oldTax", scenario.oldTax());
            item.put("newTax", scenario.newTax());
            item.put("oldRegimeNet", scenario.oldRegimeNet());
            item.put("lowerTaxRegime", scenario.lowerTaxRegime());
            item.put("lowerTax", scenario.lowerTax());
            scenarios.add(item);
        }
        root.put("scenarios", scenarios);
        return root;
    }

    private static Map<String, Object> taxMap(TaxResult t) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("regime", t.regime()); map.put("taxableIncome", t.taxableIncome());
        map.put("tax", t.tax()); map.put("taxBeforeCess", t.taxBeforeCess());
        map.put("hraExemption", t.hraExemption()); map.put("deduction80C", t.deduction80C());
        return map;
    }
}
