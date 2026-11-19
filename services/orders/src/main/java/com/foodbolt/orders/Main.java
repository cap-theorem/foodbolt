package com.foodbolt.orders;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.captheorem.spectral.SpectralClient;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Main {
    private static final Pattern ORDER_PATH = Pattern.compile("^/v1/orders/([A-Za-z0-9_.\\-]+)(/events|/cancel)?$");
    private static final SecureRandom RANDOM = new SecureRandom();

    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final Identity id;
    private final Orders orders;

    private Main(Identity id, Orders orders) {
        this.id = id;
        this.orders = orders;
    }

    public static void main(String[] args) throws IOException {
        Identity id = Identity.fromEnv("orders", "ord", 8081);
        SpectralClient spectral = SpectralClient.create();
        Orders orders = new Orders(id, new Downstream(spectral, JSON), JSON);
        Main app = new Main(id, orders);

        HttpServer server = HttpServer.create(new InetSocketAddress(id.port()), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", app::handle);
        server.start();
        Log.info("listening", "service", id.service(), "instance", id.instance(), "port", id.port(), "endpoint", id.endpoint());

        Thread shared = Registrar.start(spectral, id, id.service());
        Thread own = Registrar.start(spectral, id, id.service() + "/" + id.instance());
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            Log.info("shutting down", "instance", id.instance());
            shared.interrupt();
            own.interrupt();
            server.stop(2);
        }));
    }

    private void handle(HttpExchange ex) throws IOException {
        long start = System.nanoTime();
        String method = ex.getRequestMethod();
        String path = ex.getRequestURI().getPath();
        String header = ex.getRequestHeaders().getFirst("x-request-id");
        String requestId = header == null || header.isEmpty() ? newRequestId() : header;
        boolean quiet = path.equals("/healthz");
        int status = 500;
        try {
            Orders.Result result = route(ex, method, path, requestId, start);
            status = result.status();
            if (result.body() instanceof String text) {
                sendText(ex, status, text);
            } else {
                send(ex, status, result.body());
            }
        } catch (Exception e) {
            Log.error("request failed", "method", method, "path", path, "error", e.toString(), "request_id", requestId);
            status = 500;
            send(ex, status, Map.of("error", String.valueOf(e.getMessage())));
        } finally {
            ex.close();
            if (!quiet) {
                Object[] fields = {"method", method, "path", path, "status", status,
                        "ms", (System.nanoTime() - start) / 1_000_000.0, "request_id", requestId};
                if (status >= 500) {
                    Log.warn("http", fields);
                } else {
                    Log.info("http", fields);
                }
            }
        }
    }

    private Orders.Result route(HttpExchange ex, String method, String path, String requestId, long start) {
        if (method.equals("GET") && path.equals("/healthz")) {
            return new Orders.Result(200, "ok");
        }
        if (method.equals("GET") && path.equals("/info")) {
            return new Orders.Result(200, id.info());
        }
        if (method.equals("GET") && path.equals("/v1/stats")) {
            return new Orders.Result(200, orders.stats());
        }
        if (path.equals("/v1/orders")) {
            if (method.equals("POST")) {
                JsonNode body = readBody(ex);
                if (body == null) {
                    return new Orders.Result(400, Map.of("error", "request body must be a JSON object"));
                }
                return orders.place(body, requestId, start);
            }
            if (method.equals("GET")) {
                return orders.list();
            }
        }
        Matcher m = ORDER_PATH.matcher(path);
        if (m.matches()) {
            String orderId = m.group(1);
            String action = m.group(2);
            if (action == null && method.equals("GET")) {
                return orders.get(orderId);
            }
            if ("/cancel".equals(action) && method.equals("POST")) {
                return orders.cancel(orderId, requestId);
            }
            if ("/events".equals(action) && method.equals("POST")) {
                JsonNode body = readBody(ex);
                if (body == null) {
                    return new Orders.Result(400, Map.of("error", "request body must be a JSON object"));
                }
                return orders.event(orderId, body, ex.getRequestHeaders().getFirst("x-request-id") == null ? null : requestId);
            }
        }
        return new Orders.Result(404, Map.of("error", "unknown route " + method + " " + path));
    }

    private static JsonNode readBody(HttpExchange ex) {
        try (InputStream body = ex.getRequestBody()) {
            JsonNode node = JSON.readTree(body);
            return node != null && node.isObject() ? node : null;
        } catch (IOException e) {
            return null;
        }
    }

    private static String newRequestId() {
        byte[] b = new byte[8];
        RANDOM.nextBytes(b);
        return "req_" + HexFormat.of().formatHex(b);
    }

    private static void send(HttpExchange ex, int status, Object body) throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(body);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
    }

    private static void sendText(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/plain");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
    }
}
