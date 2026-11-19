package com.foodbolt.orders;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.captheorem.spectral.Provider;
import io.github.captheorem.spectral.SpectralClient;
import io.github.captheorem.spectral.SpectralException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;

final class Downstream {
    private static final int MAX_LOOKUPS_TO_AVOID = 4;

    record Reply(int status, JsonNode body, String raw, String instance) {
        boolean ok() {
            return status >= 200 && status < 300;
        }

        String error() {
            if (body != null && body.path("error").isTextual()) {
                return body.path("error").asText();
            }
            String text = raw == null ? "" : raw.strip();
            return text.length() > 512 ? text.substring(0, 512) : text;
        }
    }

    static final class Unavailable extends Exception {
        private final String instance;

        Unavailable(String message, String instance) {
            super(message);
            this.instance = instance;
        }

        String instance() {
            return instance;
        }
    }

    private final SpectralClient spectral;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(1))
            .build();

    Downstream(SpectralClient spectral, ObjectMapper json) {
        this.spectral = spectral;
        this.json = json;
    }

    Reply call(String requestId, String lookupName, String method, String path, Object body, Set<String> avoid)
            throws Unavailable {
        String service = baseName(lookupName);
        Provider provider;
        try {
            provider = lookupAvoiding(lookupName, avoid);
        } catch (SpectralException e) {
            Log.warn("lookup failed", "service", lookupName, "error", e.getMessage(), "request_id", requestId);
            throw new Unavailable("no instance of " + lookupName + ": " + e.getMessage(), null);
        }

        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(provider.endpoint() + path))
                .timeout(Duration.ofSeconds(10))
                .header("content-type", "application/json")
                .header("x-request-id", requestId);
        try {
            if (body == null) {
                request.method(method, HttpRequest.BodyPublishers.noBody());
            } else {
                request.method(method, HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body)));
            }
        } catch (IOException e) {
            throw new IllegalStateException("encode request body", e);
        }

        long start = System.nanoTime();
        HttpResponse<String> response;
        try {
            response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            Log.warn("call failed", "service", service, "instance", provider.instanceId(), "method", method, "path", path,
                    "ms", elapsedMs(start), "error", e.toString(), "request_id", requestId);
            throw new Unavailable(provider.instanceId() + " unreachable: " + e, provider.instanceId());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Unavailable("interrupted", provider.instanceId());
        }

        int status = response.statusCode();
        Object[] fields = {"service", service, "instance", provider.instanceId(), "method", method, "path", path,
                "status", status, "ms", elapsedMs(start), "request_id", requestId};
        if (status >= 500) {
            Log.warn("call", fields);
        } else {
            Log.info("call", fields);
        }

        JsonNode node = null;
        try {
            node = json.readTree(response.body());
        } catch (IOException ignored) {
            node = null;
        }
        return new Reply(status, node, response.body(), provider.instanceId());
    }

    private Provider lookupAvoiding(String lookupName, Set<String> avoid) {
        Provider provider = spectral.lookup(lookupName);
        for (int i = 1; i < MAX_LOOKUPS_TO_AVOID && avoid.contains(provider.instanceId()); i++) {
            provider = spectral.lookup(lookupName);
        }
        return provider;
    }

    private static String baseName(String lookupName) {
        int slash = lookupName.indexOf('/');
        return slash < 0 ? lookupName : lookupName.substring(0, slash);
    }

    private static double elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000.0;
    }
}
