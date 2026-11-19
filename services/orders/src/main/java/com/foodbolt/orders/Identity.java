package com.foodbolt.orders;

import java.net.InetAddress;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

record Identity(
        String service,
        String instance,
        String machine,
        int port,
        String endpoint,
        Duration leaseTtl,
        long startedNanos) {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String BASE36 = "0123456789abcdefghijklmnopqrstuvwxyz";

    static Identity fromEnv(String defaultService, String instancePrefix, int defaultPort) {
        String hostname = hostname();
        int port = Integer.parseInt(env("PORT", Integer.toString(defaultPort)));
        double ttl = Double.parseDouble(env("LEASE_TTL_SECONDS", "5"));
        String suffix = HexFormat.of().toHexDigits((short) RANDOM.nextInt(0x10000));

        return new Identity(
                env("SERVICE_NAME", defaultService),
                env("INSTANCE_ID", instancePrefix + "-" + suffix),
                env("MACHINE", hostname),
                port,
                env("ADVERTISE_URL", "http://" + advertiseHost() + ":" + port),
                Duration.ofMillis((long) ((ttl > 0 ? ttl : 5) * 1000)),
                System.nanoTime());
    }

    String newId() {
        StringBuilder sb = new StringBuilder(instance.length() + 7).append(instance).append('.');
        for (int i = 0; i < 6; i++) {
            sb.append(BASE36.charAt(RANDOM.nextInt(BASE36.length())));
        }
        return sb.toString();
    }

    static String owner(String resourceId) {
        int dot = resourceId.lastIndexOf('.');
        return dot <= 0 ? null : resourceId.substring(0, dot);
    }

    Map<String, Object> hop(long startNanos) {
        Map<String, Object> hop = new LinkedHashMap<>();
        hop.put("service", service);
        hop.put("instance", instance);
        hop.put("machine", machine);
        hop.put("latency_ms", Math.round((System.nanoTime() - startNanos) / 1_000.0) / 1_000.0);
        return hop;
    }

    Map<String, Object> info() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("service", service);
        info.put("instance", instance);
        info.put("machine", machine);
        info.put("endpoint", endpoint);
        info.put("uptime_s", (System.nanoTime() - startedNanos) / 1_000_000_000L);
        return info;
    }

    static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isEmpty() ? fallback : value;
    }

    private static String hostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "localhost";
        }
    }

    private static String advertiseHost() {
        try {
            InetAddress addr = InetAddress.getLocalHost();
            return addr.isLoopbackAddress() ? "127.0.0.1" : addr.getHostAddress();
        } catch (Exception e) {
            return "127.0.0.1";
        }
    }
}
