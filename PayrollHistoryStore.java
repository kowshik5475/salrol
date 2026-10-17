import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Append-only, local payroll journal. Each line is a Base64-encoded TSV snapshot. */
public final class PayrollHistoryStore {
    public static final String MODEL_VERSION = "paywise-model-1.0";
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
    private final Path historyFile;

    public record Entry(
            String recordId, String savedAt, String modelVersion,
            String employeeId, String employeeName, String department, String salaryMonth,
            boolean metro, double inputBasic, double monthlyRent, double other80C,
            boolean eligibleForRebate, String professionalTaxMode, String pfContributionMode,
            double manualPt, double basic, double da, double hra, double gross,
            double pf, double pt, double tds, double totalDeductions, double net,
            double annualGross, double annualTax, double annualNet, String regime) {

        static Entry fromPayroll(PayrollApp.Result result) {
            PayrollApp.Employee e = result.employee();
            return new Entry(UUID.randomUUID().toString(), Instant.now().toString(), MODEL_VERSION,
                    e.id(), e.name(), e.department(), e.salaryMonth(), e.metro(), e.basic(), e.monthlyRent(),
                    e.other80C(), e.eligibleForRebate(), e.professionalTaxMode(), e.pfContributionMode(),
                    e.manualPt(), result.basic(), result.da(), result.hra(), result.gross(), result.pf(),
                    result.pt(), result.tds(), result.totalDeductions(), result.net(), result.annualGross(),
                    result.chosen().tax(), result.annualNet(), result.chosen().regime());
        }

        List<String> fields() {
            return List.of(recordId, savedAt, modelVersion, employeeId, employeeName, department,
                    salaryMonth, Boolean.toString(metro), Double.toString(inputBasic), Double.toString(monthlyRent),
                    Double.toString(other80C), Boolean.toString(eligibleForRebate), professionalTaxMode,
                    pfContributionMode, Double.toString(manualPt), Double.toString(basic), Double.toString(da),
                    Double.toString(hra), Double.toString(gross), Double.toString(pf), Double.toString(pt),
                    Double.toString(tds), Double.toString(totalDeductions), Double.toString(net),
                    Double.toString(annualGross), Double.toString(annualTax), Double.toString(annualNet), regime);
        }

        String toLine() {
            return String.join("\t", fields().stream()
                    .map(s -> ENCODER.encodeToString(s.getBytes(StandardCharsets.UTF_8))).toList());
        }

        static Entry fromLine(String line) {
            try {
                String[] v = line.split("\\t", -1);
                if (v.length != 28) throw new IllegalArgumentException("invalid journal field count");
                List<String> f = new ArrayList<>(v.length);
                for (String item : v) f.add(new String(DECODER.decode(item), StandardCharsets.UTF_8));
                return new Entry(f.get(0), f.get(1), f.get(2), f.get(3), f.get(4), f.get(5), f.get(6),
                        Boolean.parseBoolean(f.get(7)), d(f, 8), d(f, 9), d(f, 10), Boolean.parseBoolean(f.get(11)),
                        f.get(12), f.get(13), d(f, 14), d(f, 15), d(f, 16), d(f, 17), d(f, 18), d(f, 19),
                        d(f, 20), d(f, 21), d(f, 22), d(f, 23), d(f, 24), d(f, 25), d(f, 26), f.get(27));
            } catch (RuntimeException ex) {
                throw new IllegalArgumentException("A stored payroll journal row is malformed.", ex);
            }
        }

        private static double d(List<String> values, int index) {
            double value = Double.parseDouble(values.get(index));
            if (!Double.isFinite(value)) throw new IllegalArgumentException("non-finite journal number");
            return value;
        }

        public java.util.Map<String, Object> toMap() {
            java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("recordId", recordId); m.put("savedAt", savedAt); m.put("modelVersion", modelVersion);
            m.put("employeeId", employeeId); m.put("employeeName", employeeName); m.put("department", department);
            m.put("salaryMonth", salaryMonth); m.put("metro", metro); m.put("inputBasic", inputBasic);
            m.put("monthlyRent", monthlyRent); m.put("other80C", other80C); m.put("eligibleForRebate", eligibleForRebate);
            m.put("professionalTaxMode", professionalTaxMode); m.put("pfContributionMode", pfContributionMode);
            m.put("manualPt", manualPt); m.put("basic", basic); m.put("da", da); m.put("hra", hra);
            m.put("gross", gross); m.put("pf", pf); m.put("pt", pt); m.put("tds", tds);
            m.put("totalDeductions", totalDeductions); m.put("net", net); m.put("annualGross", annualGross);
            m.put("annualTax", annualTax); m.put("annualNet", annualNet); m.put("regime", regime);
            return m;
        }

        public PayrollApp.Employee toEmployee() {
            return new PayrollApp.Employee(employeeId, employeeName, department, salaryMonth, inputBasic,
                    metro, monthlyRent, other80C, eligibleForRebate, professionalTaxMode,
                    pfContributionMode, manualPt);
        }
    }

    public PayrollHistoryStore(Path dataDirectory) {
        this.historyFile = dataDirectory.toAbsolutePath().normalize().resolve("payroll-history.tsv");
    }

    public Path filePath() { return historyFile; }

    public synchronized Entry append(PayrollApp.Result result) throws IOException {
        Files.createDirectories(historyFile.getParent());
        Entry entry = Entry.fromPayroll(result);
        Files.writeString(historyFile, entry.toLine() + System.lineSeparator(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        return entry;
    }

    public synchronized List<Entry> list(String employeeId, int limit) throws IOException {
        if (!Files.exists(historyFile)) return List.of();
        List<Entry> entries = new ArrayList<>();
        for (String line : Files.readAllLines(historyFile, StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            entries.add(Entry.fromLine(line));
        }
        String wanted = employeeId == null ? "" : employeeId.trim();
        if (!wanted.isEmpty()) entries.removeIf(e -> !e.employeeId().equalsIgnoreCase(wanted));
        entries.sort(Comparator.comparing(Entry::savedAt).reversed());
        return entries.stream().limit(Math.max(1, Math.min(500, limit))).toList();
    }

    /** Most recently saved record from a strictly earlier pay period for the same ID. */
    public synchronized Entry previousFor(String employeeId, String salaryMonth) throws IOException {
        if (!Files.exists(historyFile)) return null;
        return list(employeeId, 500).stream()
                .filter(e -> e.salaryMonth().compareTo(salaryMonth) < 0)
                .max(Comparator.comparing(Entry::salaryMonth).thenComparing(Entry::savedAt))
                .orElse(null);
    }
}
