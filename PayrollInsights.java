import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Rule-based explainable checks; alerts are prompts for review, not proof of payroll error. */
public final class PayrollInsights {
    private PayrollInsights() {}

    public static Map<String, Object> analyze(PayrollApp.Result current, PayrollHistoryStore.Entry previous) {
        List<Map<String, Object>> anomalies = new ArrayList<>();
        PayrollApp.Employee e = current.employee();
        double componentGross = current.basic() + current.da() + current.hra();
        if (Math.abs(componentGross - current.gross()) > 0.02) {
            add(anomalies, "high", "GROSS_MISMATCH", "Gross salary does not reconcile",
                    "Basic + DA + HRA differs from the calculated gross salary. Review the salary components.");
        }
        if (Math.abs((current.gross() - current.totalDeductions()) - current.net()) > 0.02) {
            add(anomalies, "high", "NET_MISMATCH", "Net salary does not reconcile",
                    "Gross salary minus total deductions differs from the calculated net salary.");
        }
        if (current.gross() > 0 && current.totalDeductions() / current.gross() >= 0.50) {
            add(anomalies, "warning", "HIGH_DEDUCTIONS", "Deductions exceed 50% of gross pay",
                    "Review PF, professional tax and the modeled TDS estimate. This may be valid for some salary structures.");
        }
        if (current.gross() > 0 && current.net() <= 0) {
            add(anomalies, "high", "NON_POSITIVE_NET", "Net salary is zero or negative",
                    "The modeled deductions consume the entire gross salary. Verify every input and rule selection.");
        }
        if (current.gross() > 0 && current.pt() > current.gross() * 0.02) {
            add(anomalies, "warning", "HIGH_PT", "Professional tax is unusually high",
                    "The configured monthly professional-tax amount exceeds 2% of gross salary. Confirm the state schedule and pay period.");
        }
        if (current.annualPf() + e.other80C() > 150_000.01) {
            add(anomalies, "info", "80C_CAP", "80C deduction cap applies",
                    "The model caps PF plus other eligible 80C amounts at ₹1,50,000 for the old-regime estimate.");
        }

        Map<String, Object> change = new LinkedHashMap<>();
        if (previous == null) {
            change.put("available", false);
            change.put("message", "No earlier saved pay period exists for this employee ID. Save a previous month's payroll record to enable a salary-change explanation.");
        } else {
            double grossDelta = cents(current.gross() - previous.gross());
            double netDelta = cents(current.net() - previous.net());
            double deductionsDelta = cents(current.totalDeductions() - previous.totalDeductions());
            double grossPct = percent(grossDelta, previous.gross());
            double netPct = percent(netDelta, previous.net());
            change.put("available", true);
            change.put("previousRecordId", previous.recordId());
            change.put("previousMonth", previous.salaryMonth());
            change.put("previousSavedAt", previous.savedAt());
            change.put("previousModelVersion", previous.modelVersion());
            change.put("previousRegime", previous.regime());
            change.put("regimeChanged", !previous.regime().equals(current.chosen().regime()));
            change.put("previousGross", previous.gross());
            change.put("previousNet", previous.net());
            change.put("previousDeductions", previous.totalDeductions());
            change.put("grossDelta", grossDelta);
            change.put("netDelta", netDelta);
            change.put("deductionsDelta", deductionsDelta);
            change.put("annualTaxDelta", cents(current.chosen().tax() - previous.annualTax()));
            change.put("grossPercent", grossPct);
            change.put("netPercent", netPct);
            List<String> explanations = new ArrayList<>();
            explanations.add("Gross changed by " + signedRupees(grossDelta) + ": Basic "
                    + signedRupees(current.basic() - previous.basic()) + ", DA "
                    + signedRupees(current.da() - previous.da()) + ", HRA "
                    + signedRupees(current.hra() - previous.hra()) + ".");
            explanations.add("Net pay changed by " + signedRupees(netDelta) + ". Total deductions changed by "
                    + signedRupees(deductionsDelta) + " (PF " + signedRupees(current.pf() - previous.pf())
                    + ", professional tax " + signedRupees(current.pt() - previous.pt()) + ", TDS "
                    + signedRupees(current.tds() - previous.tds()) + ").");
            explanations.add("Annual modeled tax changed by " + signedRupees(current.chosen().tax() - previous.annualTax())
                    + ". Selected regime: " + previous.regime() + " → " + current.chosen().regime() + ".");
            if (!PayrollHistoryStore.MODEL_VERSION.equals(previous.modelVersion())) {
                add(anomalies, "info", "MODEL_VERSION_CHANGE", "Calculation model version changed",
                        "Previous snapshot used " + previous.modelVersion() + "; current model is " + PayrollHistoryStore.MODEL_VERSION + ". Treat the period comparison cautiously because assumptions may differ.");
            }
            change.put("explanations", explanations);
            if (previous.gross() > 0 && Math.abs(grossPct) >= 20.0) {
                add(anomalies, "warning", "GROSS_CHANGE", "Gross salary changed by " + String.format(java.util.Locale.ROOT, "%.1f%%", grossPct),
                        "Compared with saved period " + previous.salaryMonth() + ", gross pay changed by " + signedRupees(grossDelta) + ". Confirm any raise, unpaid leave, arrears or changed salary inputs.");
            }
            if (previous.net() > 0 && Math.abs(netPct) >= 20.0) {
                add(anomalies, "warning", "NET_CHANGE", "Take-home pay changed by " + String.format(java.util.Locale.ROOT, "%.1f%%", netPct),
                        "Compared with saved period " + previous.salaryMonth() + ", net pay changed by " + signedRupees(netDelta) + ". Review both earnings and deductions.");
            }
            if (Math.abs(current.tds() - previous.tds()) >= 5_000) {
                add(anomalies, "info", "TDS_CHANGE", "Monthly TDS changed materially",
                        "Modeled TDS changed by " + signedRupees(current.tds() - previous.tds()) + " compared with " + previous.salaryMonth() + ". Review annual projections and the selected tax assumptions.");
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("modelVersion", PayrollHistoryStore.MODEL_VERSION);
        out.put("anomalyCount", anomalies.size());
        out.put("anomalies", anomalies);
        out.put("change", change);
        out.put("summary", anomalies.isEmpty() ? "No rule-based review flags for the current inputs." : anomalies.size() + " item(s) need review.");
        out.put("notice", "Rule-based checks can flag legitimate changes. Review context before deciding a payroll result is incorrect.");
        return out;
    }

    private static void add(List<Map<String, Object>> list, String severity, String code, String title, String detail) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("severity", severity); a.put("code", code); a.put("title", title); a.put("detail", detail);
        list.add(a);
    }

    private static double percent(double delta, double previous) {
        return previous == 0 ? 0 : Math.round(delta / previous * 1000.0) / 10.0;
    }

    private static double cents(double v) { return Math.round(v * 100.0) / 100.0; }
    private static String signedRupees(double v) {
        return (v >= 0 ? "+₹" : "−₹") + String.format(java.util.Locale.ROOT, "%,.2f", Math.abs(v));
    }
}
