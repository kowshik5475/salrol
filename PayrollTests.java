/** Lightweight dependency-free tests. Run with: java -ea PayrollTests */
public final class PayrollTests {
    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        test("salary components and gross", PayrollTests::salaryComponents);
        test("PF uses full basic plus DA when selected", PayrollTests::providentFund);
        test("PF monthly wage ceiling mode", PayrollTests::providentFundCeiling);
        test("Andhra Pradesh PT lower threshold", PayrollTests::andhraProfessionalTax);
        test("manual and no-PT modes", PayrollTests::professionalTaxModes);
        test("new regime slab boundary", PayrollTests::newRegimeSlabs);
        test("slab tax stays unrounded until tax calculation finishes", PayrollTests::slabTaxPrecision);
        test("taxable income and annual tax round to nearest ten rupees", PayrollTests::annualTaxRounding);
        test("malformed URL-encoded form is sanitized", PayrollTests::malformedForm);
        test("unsupported city type rejected", PayrollTests::invalidCityType);
        test("unsupported rebate eligibility rejected", PayrollTests::invalidRebateEligibility);
        test("new rebate for eligible resident", PayrollTests::newRegimeRebate);
        test("no rebate when ineligible", PayrollTests::rebateEligibility);
        test("marginal relief total tax cap", PayrollTests::marginalRelief);
        test("old regime 80C deduction cap", PayrollTests::deduction80CCap);
        test("zero rent prevents HRA exemption", PayrollTests::zeroRent);
        test("monthly and annual arithmetic", PayrollTests::annualArithmetic);
        test("investment scenarios include current amount", PayrollTests::investmentScenarios);
        test("negative salary rejected", PayrollTests::negativeSalary);
        test("invalid professional-tax mode rejected", PayrollTests::invalidProfessionalTaxMode);
        test("salary month validation", PayrollTests::invalidSalaryMonth);
        System.out.printf("%d/%d tests passed.%n", passed, 21);
        if (failed > 0) System.exit(1);
    }

    private static PayrollApp.Employee employee(double basic, boolean metro, double rent, double inv,
            String ptMode, double manualPt, boolean rebate) {
        return employee(basic, metro, rent, inv, ptMode, manualPt, rebate, "full-basic-da");
    }

    private static PayrollApp.Employee employee(double basic, boolean metro, double rent, double inv,
            String ptMode, double manualPt, boolean rebate, String pfMode) {
        return new PayrollApp.Employee("E101", "Test Employee", "Engineering", "2026-10",
                basic, metro, rent, inv, rebate, ptMode, pfMode, manualPt);
    }

    private static PayrollApp.Result calculate(double basic, boolean metro, double rent, double inv,
            String ptMode, double manualPt, boolean rebate) {
        return PayrollApp.calculate(employee(basic, metro, rent, inv, ptMode, manualPt, rebate));
    }

    private static void salaryComponents() {
        var r = calculate(60_000, true, 25_000, 40_000, "andhra-pradesh", 0, true);
        eq(6_000, r.da()); eq(30_000, r.hra()); eq(96_000, r.gross());
    }

    private static void providentFund() {
        var r = calculate(60_000, true, 25_000, 0, "none", 0, true);
        eq(7_920, r.pf());
    }

    private static void andhraProfessionalTax() {
        eq(0, calculate(9_000, true, 0, 0, "andhra-pradesh", 0, false).pt());
        eq(150, calculate(12_000, true, 0, 0, "andhra-pradesh", 0, false).pt());
        eq(200, calculate(13_000, true, 0, 0, "andhra-pradesh", 0, false).pt());
    }

    private static void professionalTaxModes() {
        eq(123.45, calculate(20_000, true, 0, 0, "manual", 123.45, false).pt());
        eq(0, calculate(20_000, true, 0, 0, "none", 0, false).pt());
    }

    private static void providentFundCeiling() {
        var full = PayrollApp.calculate(employee(20_000, true, 0, 0, "none", 0, false, "full-basic-da"));
        var ceiling = PayrollApp.calculate(employee(20_000, true, 0, 0, "none", 0, false, "wage-ceiling"));
        eq(2_640, full.pf());
        eq(1_800, ceiling.pf());
    }

    private static void newRegimeSlabs() {
        eq(20_000, PayrollApp.slabTax(800_000, new PayrollApp.Config().newSlabs));
    }

    private static void slabTaxPrecision() {
        double actual = PayrollApp.slabTax(800_000.37, new PayrollApp.Config().newSlabs);
        if (Math.abs(actual - 20_000.037) > 0.000001) fail("slab calculation was rounded early: " + actual);
    }

    private static void annualTaxRounding() {
        eq(1_230, PayrollApp.roundTaxToTen(1_234.99));
        eq(1_240, PayrollApp.roundTaxToTen(1_235.00));
        eq(0, PayrollApp.roundTaxToTen(4.99));
    }

    private static void malformedForm() {
        try {
            PayrollServer.parseForm("id=%ZZ");
            fail("expected malformed form error");
        } catch (IllegalArgumentException ex) {
            if (!"Invalid form data.".equals(ex.getMessage())) fail("internal parser error leaked: " + ex.getMessage());
        }
        try {
            PayrollServer.parseForm("%ZZ=value");
            fail("expected malformed key error");
        } catch (IllegalArgumentException ex) {
            if (!"Invalid form data.".equals(ex.getMessage())) fail("internal parser error leaked: " + ex.getMessage());
        }
    }

    private static void invalidCityType() {
        expectError(() -> PayrollServer.parseMetroCityType("unknown"));
    }

    private static void invalidRebateEligibility() {
        expectError(() -> PayrollServer.parseRebateEligibility("maybe"));
    }

    private static void newRegimeRebate() {
        // Metro gross = basic * 1.6; annual gross ₹12.75 lakh gives taxable ₹12 lakh.
        var r = calculate(66_406.24, true, 0, 0, "none", 0, true);
        eq(0, r.newTax().tax());
    }

    private static void rebateEligibility() {
        var eligible = calculate(50_000, true, 0, 0, "none", 0, true);
        var notEligible = calculate(50_000, true, 0, 0, "none", 0, false);
        eq(0, eligible.newTax().tax());
        if (!(notEligible.newTax().tax() > 0)) fail("expected positive tax when rebate is unavailable");
    }

    private static void marginalRelief() {
        // Annual gross ~₹12.85 lakh, less ₹75,000 standard deduction = ₹12.10 lakh taxable.
        double basic = 1_285_000 / 19.2;
        var r = calculate(basic, true, 0, 0, "none", 0, true);
        if (r.newTax().tax() > 10_000.01) fail("marginal relief exceeded taxable income above ₹12 lakh: " + r.newTax().tax());
    }

    private static void deduction80CCap() {
        var r = calculate(60_000, true, 25_000, 500_000, "none", 0, false);
        if (r.oldTax().deduction80C() > 150_000) fail("80C deduction exceeded cap");
    }

    private static void zeroRent() {
        var r = calculate(60_000, true, 0, 0, "none", 0, false);
        eq(0, r.oldTax().hraExemption());
    }

    private static void annualArithmetic() {
        var r = calculate(60_000, true, 25_000, 0, "none", 0, true);
        eq(r.gross() * 12, r.annualGross());
        eq(r.net() * 12, r.annualNet());
        eq(r.gross() - r.totalDeductions(), r.net());
    }

    private static void investmentScenarios() {
        var r = calculate(60_000, true, 25_000, 42_000, "none", 0, false);
        if (r.scenarios().stream().noneMatch(s -> Math.abs(s.investment() - 42_000) < 0.001)) fail("current investment amount missing");
    }

    private static void negativeSalary() {
        expectError(() -> calculate(-1, true, 0, 0, "none", 0, true));
    }

    private static void invalidProfessionalTaxMode() {
        var bad = new PayrollApp.Employee("E101", "Test", "IT", "2026-10", 10_000,
                true, 0, 0, true, "unknown", "full-basic-da", 0);
        expectError(() -> PayrollApp.calculate(bad));
    }

    private static void invalidSalaryMonth() {
        var bad = new PayrollApp.Employee("E101", "Test", "IT", "2026-13", 10_000,
                true, 0, 0, true, "none", "full-basic-da", 0);
        expectError(() -> PayrollApp.calculate(bad));
    }

    private static void test(String name, Runnable action) {
        try { action.run(); passed++; System.out.println("PASS: " + name); }
        catch (Throwable ex) { failed++; System.err.println("FAIL: " + name + " — " + ex.getMessage()); }
    }

    private static void eq(double expected, double actual) {
        if (Math.abs(expected - actual) > 0.02) fail("expected " + expected + " but got " + actual);
    }
    private static void expectError(Runnable action) {
        try { action.run(); fail("expected validation error"); }
        catch (IllegalArgumentException expected) { }
    }
    private static void fail(String message) { throw new AssertionError(message); }
}
