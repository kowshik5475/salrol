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
import java.util.Map;
import java.util.concurrent.Executors;

/** Local HTTP server: Java handles payroll calculations; HTML/CSS/JS provide the UI. */
public final class PayrollServer {
    private static final int MAX_BODY_BYTES = 64 * 1024;
    private static final Path PROJECT_ROOT = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();

    private PayrollServer() {}

    public static void main(String[] args) throws Exception {
        int port = integerEnvironment("PORT", 8080);
        String host = System.getenv().getOrDefault("HOST", "127.0.0.1");
        HttpServer server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.createContext("/api/health", PayrollServer::health);
        server.createContext("/api/payroll", PayrollServer::payroll);
        server.createContext("/", PayrollServer::staticFile);
        server.setExecutor(Executors.newFixedThreadPool(Math.max(4, Runtime.getRuntime().availableProcessors())));
        Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(1)));
        server.start();
        System.out.printf("Paywise Java server running at http://%s:%d%n", host, port);
        System.out.println("Payroll calculations are executed by PayrollApp.java.");
        System.out.println("Press Ctrl+C to stop the server.");
    }

    private static void health(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, Map.of("error", "Use GET for this endpoint."));
            return;
        }
        sendJson(exchange, 200, Map.of("status", "ok", "engine", "Java", "application", "Paywise Smart Payroll"));
    }

    private static void payroll(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, Map.of("error", "Use POST to calculate payroll."));
            return;
        }
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !contentType.toLowerCase().startsWith("application/x-www-form-urlencoded")) {
            sendJson(exchange, 415, Map.of("error", "Send payroll inputs as application/x-www-form-urlencoded."));
            return;
        }
        try {
            byte[] body = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
            if (body.length > MAX_BODY_BYTES) {
                sendJson(exchange, 413, Map.of("error", "Submitted form is too large."));
                return;
            }
            Map<String, String> fields = parseForm(new String(body, StandardCharsets.UTF_8));
            String cityType = required(fields, "cityType");
            boolean metro = parseMetroCityType(cityType);
            String rebateEligibility = required(fields, "rebateEligibility");
            boolean eligibleForRebate = parseRebateEligibility(rebateEligibility);
            PayrollApp.Employee employee = new PayrollApp.Employee(
                    required(fields, "id"),
                    required(fields, "name"),
                    required(fields, "department"),
                    required(fields, "salaryMonth"),
                    number(fields, "basic"),
                    metro,
                    number(fields, "monthlyRent"),
                    number(fields, "other80C"),
                    eligibleForRebate,
                    required(fields, "professionalTaxMode"),
                    required(fields, "pfContributionMode"),
                    number(fields, "manualPt"));
            PayrollApp.Result result = PayrollApp.calculate(employee);
            sendJson(exchange, 200, PayrollApp.toMap(result));
        } catch (IllegalArgumentException ex) {
            sendJson(exchange, 400, Map.of("error", ex.getMessage() == null ? "Invalid payroll input." : ex.getMessage()));
        } catch (Exception ex) {
            ex.printStackTrace(System.err);
            sendJson(exchange, 500, Map.of("error", "Unexpected server error while calculating payroll."));
        }
    }

    private static void staticFile(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod()) && !"HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendText(exchange, 405, "Method not allowed", "text/plain; charset=utf-8");
            return;
        }
        String requestPath = exchange.getRequestURI().getPath();
        if (requestPath == null || requestPath.equals("/")) requestPath = "/index.html";
        // Serve only application frontend files, never Java sources, repository metadata, or user paths.
        if (!List.of("/index.html", "/styles.css", "/src/app.js").contains(requestPath)) {
            sendText(exchange, 404, "Not found", "text/plain; charset=utf-8");
            return;
        }
        Path file = PROJECT_ROOT.resolve(requestPath.substring(1)).normalize();
        if (!file.startsWith(PROJECT_ROOT) || !Files.isRegularFile(file)) {
            sendText(exchange, 404, "Not found", "text/plain; charset=utf-8");
            return;
        }
        String type = requestPath.endsWith(".html") ? "text/html; charset=utf-8"
                : requestPath.endsWith(".css") ? "text/css; charset=utf-8"
                : "text/javascript; charset=utf-8";
        byte[] bytes = Files.readAllBytes(file);
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", type);
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("Cache-Control", "no-store");
        headers.set("Content-Security-Policy", "default-src 'self' data:; style-src 'self' 'unsafe-inline'; script-src 'self'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'");
        if ("HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(200, -1);
        } else {
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        }
    }

    static boolean parseMetroCityType(String cityType) {
        if (!List.of("metro", "nonmetro").contains(cityType)) {
            throw new IllegalArgumentException("Select a supported city type for the HRA estimate.");
        }
        return "metro".equals(cityType);
    }

    static boolean parseRebateEligibility(String eligibility) {
        if (!List.of("eligible", "ineligible").contains(eligibility)) {
            throw new IllegalArgumentException("Choose a valid Section 87A rebate-eligibility option.");
        }
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
            // Never expose URLDecoder internals or raw parser details in an API response.
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

    private static void sendJson(HttpExchange exchange, int status, Object data) throws IOException {
        sendText(exchange, status, toJson(data), "application/json; charset=utf-8");
    }

    private static void sendText(HttpExchange exchange, int status, String body, String contentType) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", contentType);
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var out = exchange.getResponseBody()) { out.write(bytes); }
    }

    private static String toJson(Object value) {
        if (value == null) return "null";
        if (value instanceof String s) return "\"" + escapeJson(s) + "\"";
        if (value instanceof Number n) {
            double d = n.doubleValue();
            return Double.isFinite(d) ? n.toString() : "null";
        }
        if (value instanceof Boolean b) return b.toString();
        if (value instanceof Map<?, ?> map) {
            List<String> entries = new ArrayList<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                entries.add(toJson(String.valueOf(entry.getKey())) + ":" + toJson(entry.getValue()));
            }
            return "{" + String.join(",", entries) + "}";
        }
        if (value instanceof Iterable<?> iterable) {
            List<String> entries = new ArrayList<>();
            for (Object item : iterable) entries.add(toJson(item));
            return "[" + String.join(",", entries) + "]";
        }
        return toJson(value.toString());
    }

    private static String escapeJson(String value) {
        StringBuilder out = new StringBuilder(value.length() + 16);
        for (char ch : value.toCharArray()) {
            switch (ch) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> { if (ch < 0x20) out.append(String.format("\\u%04x", (int) ch)); else out.append(ch); }
            }
        }
        return out.toString();
    }

    private static int integerEnvironment(String key, int fallback) {
        String raw = System.getenv(key);
        if (raw == null || raw.isBlank()) return fallback;
        try {
            int value = Integer.parseInt(raw);
            if (value < 1 || value > 65535) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(key + " must be a port number from 1 to 65535.");
        }
    }
}
