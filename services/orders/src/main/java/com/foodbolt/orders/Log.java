package com.foodbolt.orders;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

final class Log {
    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private Log() {}

    static String iso(Instant t) {
        return TS.format(t);
    }

    static void info(String msg, Object... kv) {
        write("INFO", msg, kv);
    }

    static void warn(String msg, Object... kv) {
        write("WARN", msg, kv);
    }

    static void error(String msg, Object... kv) {
        write("ERROR", msg, kv);
    }

    private static void write(String level, String msg, Object[] kv) {
        StringBuilder sb = new StringBuilder(160);
        sb.append(iso(Instant.now())).append(' ').append(level).append(' ').append(msg);
        for (int i = 0; i + 1 < kv.length; i += 2) {
            Object value = kv[i + 1];
            if (value == null) {
                continue;
            }
            sb.append(' ').append(kv[i]).append('=').append(format(value));
        }
        System.out.println(sb);
    }

    private static String format(Object value) {
        String s = value instanceof Double d ? String.format(Locale.ROOT, "%.1f", d) : String.valueOf(value);
        boolean quote = s.isEmpty();
        for (int i = 0; i < s.length() && !quote; i++) {
            char c = s.charAt(i);
            quote = c == ' ' || c == '"' || c == '=' || c == '\t' || c == '\n';
        }
        if (!quote) {
            return s;
        }
        return '"' + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + '"';
    }
}
