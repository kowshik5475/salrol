import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;

/** Java HTTP API and static frontend server for Paywise. */
public final class PayrollServer {
    private static final int MAX_BODY_BYTES = 64 * 1024;
    private static final Path PROJECT_ROOT = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
    private static final Path DATA_DIRECTORY = Path.of(System.getenv().getOrDefault(
            "PAYWISE_DATA_DIR", PROJECT_ROOT.resolve("data").toString())).toAbsolutePath().normalize();
    private static final PayrollHistoryStore HISTORY = new PayrollHistoryStore(DATA_DIRECTORY);

    private PayrollServer() {}

    public static void main(String[] args) throws Exception {
        int port = integerEnvironment("PORT", 8080);
        String host = System.getenv().getOrDefault("HOST", "127.0.0.1");
        HttpServer server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.createContext("/api/health", PayrollServer::health);
        server.createContext("/api/payroll", PayrollServer::payroll);
        server.createContext("/api/offer-compare", PayrollServer::compareOffers);
        server.createContext("/api/history", PayrollServer::history);
        server.createContext("/api/audit", PayrollServer::history);
        server.createContext("/", PayrollServer::staticFile);
        server.setExecutor(Executors.newFixedThreadPool(Math.max(4, Runtime.getRuntime().availableProcessors())));
        Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(1)));
        server.start();
        System.out.printf("Paywise Java server running at http://%s:%d%n", host, port);
        System.out.println("Payroll calculations and rule-based insights execute in Java.");
        System.out.println("Payroll journal: " + HISTORY.filePath());
        System.out.println("Press Ctrl+C to stop the server.");
    }

    private static void health(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, Map.of("error", "Use GET for this endpoint."));
            return;
        }
        sendJson(exchange, 200, Map.of("status", "ok", "engine", "Java", "application", "Paywise Intelligence"));
    }

    private static void payroll(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, Map.of("error", "Use POST to calculate payroll."));
            return;
        }
        try {
            Map<String, String> fields = readFormFields(exchange);
            PayrollApp.Employee employee = employeeFromFields(fields);
            PayrollApp.Result result = PayrollApp.calculate(employee);
            Map<String, Object> payload = new LinkedHashMap<>(PayrollApp.toMap(result));
            PayrollHistoryStore.Entry previous = HISTORY.previousFor(employee.id(), employee.salaryMonth());
            payload.put("insights", PayrollInsights.analyze(result, previous));
            sendJson(exchange, 200, payload);
        } catch (HttpProblem ex) {
            sendJson(exchange, ex.status, Map.of("error", ex.getMessage()));
        } catch (IllegalArgumentException ex) {
            sendJson(exchange, 400, Map.of("error", safeMessage(ex, "Invalid payroll input.")));
        } catch (IOException ex) {
            sendJson(exchange, 500, Map.of("error", "Payroll history could not be read. Check the configured data directory."));
        } catch (Exception ex) {
            ex.printStackTrace(System.err);
            sendJson(exchange, 500, Map.of("error", "Unexpected server error while calculating payroll."));
        }
    }

    private static void compareOffers(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, Map.of("error", "Use POST to compare salary offers."));
            return;
        }
        try {
            Map<String, String> fields = readFormFields(exchange);
            String month = required(fields, "offerSalaryMonth");
            String eligibility = required(fields, "offerRebateEligibility");
            boolean rebate = parseRebateEligibility(eligibility);
            String labelA = text(fields, "offerAName", "Offer A", 120);
            String labelB = text(fields, "offerBName", "Offer B", 120);
            PayrollApp.Employee employeeA = offerEmployee(fields, "offerA", "OFFER-A", labelA, month, rebate);
            PayrollApp.Employee employeeB = offerEmployee(fields, "offerB", "OFFER-B", labelB, month, rebate);
            PayrollApp.Result resultA = PayrollApp.calculate(employeeA);
            PayrollApp.Result resultB = PayrollApp.calculate(employeeB);

            Map<String, Object> a = new LinkedHashMap<>(PayrollApp.toMap(resultA));
            Map<String, Object> b = new LinkedHashMap<>(PayrollApp.toMap(resultB));
            a.put("offerLabel", labelA);
            b.put("offerLabel", labelB);
            boolean aWins = resultA.net() >= resultB.net();
            PayrollApp.Result winner = aWins ? resultA : resultB;
            double monthlyDiff = Math.abs(resultA.net() - resultB.net());
            double annualDiff = Math.abs(resultA.annualNet() - resultB.annualNet());
            Map<String, Object> comparison = new LinkedHashMap<>();
            comparison.put("winner", aWins ? labelA : labelB);
            comparison.put("winnerCode", aWins ? "A" : "B");
            comparison.put("monthlyNetDifference", money(monthlyDiff));
            comparison.put("annualNetDifference", money(annualDiff));
            comparison.put("grossDifference", money(Math.abs(resultA.gross() - resultB.gross())));
            comparison.put("annualTaxDifference", money(Math.abs(resultA.chosen().tax() - resultB.chosen().tax())));
            comparison.put("winnerMonthlyNet", winner.net());
            comparison.put("winnerAnnualNet", winner.annualNet());
            comparison.put("summary", monthlyDiff < 0.01
                    ? "Both modeled offers produce the same monthly take-home pay. Compare benefits, growth and non-salary terms too."
                    : String.format(Locale.ROOT, "%s provides about ₹%,.2f more modeled take-home pay per month (₹%,.2f per year) under these assumptions.",
                    aWins ? labelA : labelB, monthlyDiff, annualDiff));
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("offerA", a); output.put("offerB", b); output.put("comparison", comparison);
            output.put("notice", "This compares the modeled salary structures entered here. It does not value bonuses, stock, insurance, joining/retention pay, role fit or career growth.");
            sendJson(exchange, 200, output);
        } catch (HttpProblem ex) {
            sendJson(exchange, ex.status, Map.of("error", ex.getMessage()));
        } catch (IllegalArgumentException ex) {
            sendJson(exchange, 400, Map.of("error", safeMessage(ex, "Invalid offer comparison input.")));
        } catch (Exception ex) {
            ex.printStackTrace(System.err);
            sendJson(exchange, 500, Map.of("error", "Unexpected server error while comparing offers."));
        }
    }

    private static PayrollApp.Employee offerEmployee(Map<String, String> fields, String prefix, String id,
            String label, String month, boolean rebate) {
        boolean metro = parseMetroCityType(required(fields, prefix + "CityType"));
        return new PayrollApp.Employee(id, label, "Salary offer comparison", month,
                number(fields, prefix + "Basic"), metro, number(fields, prefix + "Rent"),
                number(fields, prefix + "80C"), rebate, required(fields, prefix + "PtMode"),
                required(fields, prefix + "PfMode"), number(fields, prefix + "ManualPt"));
    }

    private static void history(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (!"/api/history".equals(path) && !"/api/audit".equals(path)) {
            sendJson(exchange, 404, Map.of("error", "Not found."));
            return;
        }
        try {
            if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                Map<String, String> query = parseForm(exchange.getRequestURI().getRawQuery());
                String employeeId = query.getOrDefault("employeeId", "").trim();
                int limit = query.containsKey("limit") ? parseLimit(query.get("limit")) : 100;
                List<Map<String, Object>> records = HISTORY.list(employeeId, limit).stream().map(PayrollHistoryStore.Entry::toMap).toList();
                Map<String, Object> response = new LinkedHashMap<>();
                response.put("records", records); response.put("count", records.size());
                response.put("modelVersion", PayrollHistoryStore.MODEL_VERSION);
                response.put("storage", "local append-only file");
                sendJson(exchange, 200, response);
                return;
            }
            if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                Map<String, String> fields = readFormFields(exchange);
                PayrollApp.Employee employee = employeeFromFields(fields);
                PayrollApp.Result result = PayrollApp.calculate(employee);
                PayrollHistoryStore.Entry saved = HISTORY.append(result);
                Map<String, Object> response = new LinkedHashMap<>(saved.toMap());
                response.put("message", "Payroll snapshot saved to the local audit journal.");
                sendJson(exchange, 201, response);
                return;
            }
            sendJson(exchange, 405, Map.of("error", "Use GET to view history or POST to save a payroll snapshot."));
        } catch (HttpProblem ex) {
            sendJson(exchange, ex.status, Map.of("error", ex.getMessage()));
        } catch (IllegalArgumentException ex) {
            sendJson(exchange, 400, Map.of("error", safeMessage(ex, "Invalid history request.")));
        } catch (IOException ex) {
            sendJson(exchange, 500, Map.of("error", "Payroll history could not be accessed. Check the configured data directory."));
        }
    }

    private static Map<String, String> readFormFields(HttpExchange exchange) throws IOException {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith("application/x-www-form-urlencoded")) {
            throw new HttpProblem(415, "Send inputs as application/x-www-form-urlencoded.");
        }
        byte[] body = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) throw new HttpProblem(413, "Submitted form is too large.");
        return parseForm(new String(body, StandardCharsets.UTF_8));
    }

    private static PayrollApp.Employee employeeFromFields(Map<String, String> fields) {
        boolean metro = parseMetroCityType(required(fields, "cityType"));
        boolean eligible = parseRebateEligibility(required(fields, "rebateEligibility"));
        return new PayrollApp.Employee(required(fields, "id"), required(fields, "name"), required(fields, "department"),
                required(fields, "salaryMonth"), number(fields, "basic"), metro, number(fields, "monthlyRent"),
                number(fields, "other80C"), eligible, required(fields, "professionalTaxMode"),
                required(fields, "pfContributionMode"), number(fields, "manualPt"));
    }

    private static String text(Map<String, String> fields, String key, String fallback, int maxLength) {
        String value = fields.getOrDefault(key, fallback).trim();
        if (value.isEmpty()) throw new IllegalArgumentException("Enter a name for " + key + ".");
        if (value.length() > maxLength) throw new IllegalArgumentException(key + " must be " + maxLength + " characters or fewer.");
        return value;
    }

    private static void staticFile(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod()) && !"HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendText(exchange, 405, "Method not allowed", "text/plain; charset=utf-8");
            return;
        }
        String requestPath = exchange.getRequestURI().getPath();
        if (requestPath == null || requestPath.equals("/")) requestPath = "/index.html";
        if (!List.of("/index.html", "/styles.css", "/src/app.js").contains(requestPath)) {
            sendText(exchange, 404, "Not found", "text/plain; charset=utf-8"); return;
        }
        Path file = PROJECT_ROOT.resolve(requestPath.substring(1)).normalize();
        if (!file.startsWith(PROJECT_ROOT) || !Files.isRegularFile(file)) {
            sendText(exchange, 404, "Not found", "text/plain; charset=utf-8"); return;
        }
        String type = requestPath.endsWith(".html") ? "text/html; charset=utf-8"
                : requestPath.endsWith(".css") ? "text/css; charset=utf-8" : "text/javascript; charset=utf-8";
        byte[] bytes = Files.readAllBytes(file);
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", type); headers.set("X-Content-Type-Options", "nosniff");
        headers.set("Cache-Control", "no-store");
        headers.set("Content-Security-Policy", "default-src 'self' data:; style-src 'self' 'unsafe-inline'; script-src 'self'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'");
        if ("HEAD".equalsIgnoreCase(exchange.getRequestMethod())) exchange.sendResponseHeaders(200, -1);
        else {
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        }
    }

    static boolean parseMetroCityType(String cityType) {
        if (!List.of("metro", "nonmetro").contains(cityType)) throw new IllegalArgumentException("Select a supported city type for the HRA estimate.");
        return "metro".equals(cityType);
    }

    static boolean parseRebateEligibility(String eligibility) {
        if (!List.of("eligible", "ineligible").contains(eligibility)) throw new IllegalArgumentException("Choose a valid Section 87A rebate-eligibility option.");
        return "eligible".equals(eligibility);
    }

    static Map<String, String> parseForm(String body) {
        Map<String, String> values = new LinkedHashMap<>();
        if (body == null || body.isBlank()) return values;
        try {
            for (String pair : body.split("&", -1)) {
                int separator = pair.indexOf('=');
                String key = separator >= 0 ? pair.substring(0, separator) : pair;
                String value = separator >= 0 ? pair.substring(separator + 1) : "";
                values.put(URLDecoder.decode(key, StandardCharsets.UTF_8), URLDecoder.decode(value, StandardCharsets.UTF_8));
            }
        } catch (IllegalArgumentException malformedEncoding) {
            throw new IllegalArgumentException("Invalid form data.");
        }
        return values;
    }

    private static String required(Map<String, String> fields, String key) {
        String value = fields.get(key);
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException("Missing required field: " + key + ".");
        return value.trim();
    }

    private static double number(Map<String, String> fields, String key) {
        String value = fields.get(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Enter a value for " + key + ".");
        try {
            double parsed = Double.parseDouble(value.trim());
            if (!Double.isFinite(parsed) || parsed < 0) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(key + " must be a finite, non-negative number.");
        }
    }

    private static int parseLimit(String value) {
        try { return Math.max(1, Math.min(500, Integer.parseInt(value))); }
        catch (RuntimeException ex) { throw new IllegalArgumentException("History limit must be an integer from 1 to 500."); }
    }

    private static String safeMessage(IllegalArgumentException ex, String fallback) {
        return ex.getMessage() == null || ex.getMessage().isBlank() ? fallback : ex.getMessage();
    }

    private static double money(double value) { return Math.round(value * 100.0) / 100.0; }

    private static void sendJson(HttpExchange exchange, int status, Object data) throws IOException {
        sendText(exchange, status, toJson(data), "application/json; charset=utf-8");
    }

    private static void sendText(HttpExchange exchange, int status, String body, String contentType) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", contentType); headers.set("X-Content-Type-Options", "nosniff"); headers.set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var out = exchange.getResponseBody()) { out.write(bytes); }
    }

    private static String toJson(Object value) {
        if (value == null) return "null";
        if (value instanceof String s) return "\"" + escapeJson(s) + "\"";
        if (value instanceof Number n) {
            double d = n.doubleValue(); return Double.isFinite(d) ? n.toString() : "null";
        }
        if (value instanceof Boolean b) return b.toString();
        if (value instanceof Map<?, ?> map) {
            List<String> entries = new ArrayList<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) entries.add(toJson(String.valueOf(entry.getKey())) + ":" + toJson(entry.getValue()));
            return "{" + String.join(",", entries) + "}";
        }
        if (value instanceof Iterable<?> iterable) {
            List<String> entries = new ArrayList<>(); for (Object item : iterable) entries.add(toJson(item));
            return "[" + String.join(",", entries) + "]";
        }
        return toJson(value.toString());
    }

    private static String escapeJson(String value) {
        StringBuilder out = new StringBuilder(value.length() + 16);
        for (char ch : value.toCharArray()) {
            switch (ch) {
                case '"' -> out.append("\\\""); case '\\' -> out.append("\\\\"); case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f"); case '\n' -> out.append("\\n"); case '\r' -> out.append("\\r"); case '\t' -> out.append("\\t");
                default -> { if (ch < 0x20) out.append(String.format("\\u%04x", (int) ch)); else out.append(ch); }
            }
        }
        return out.toString();
    }

    private static int integerEnvironment(String key, int fallback) {
        String raw = System.getenv(key);
        if (raw == null || raw.isBlank()) return fallback;
        try { int value = Integer.parseInt(raw); if (value < 1 || value > 65535) throw new NumberFormatException(); return value; }
        catch (NumberFormatException ex) { throw new IllegalArgumentException(key + " must be a port number from 1 to 65535."); }
    }

    private static final class HttpProblem extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final int status;
        HttpProblem(int status, String message) { super(message); this.status = status; }
    }
}
