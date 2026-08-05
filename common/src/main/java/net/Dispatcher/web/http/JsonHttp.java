package net.Dispatcher.web.http;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import java.io.ByteArrayOutputStream;

/** Response/request helpers for the embedded web server. */
public final class JsonHttp {
    public static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final int GZIP_THRESHOLD = 512;

    private JsonHttp() {}

    public static void json(HttpExchange exchange, int status, JsonObject body) throws IOException {
        bytes(exchange, status, "application/json; charset=utf-8",
                GSON.toJson(body).getBytes(StandardCharsets.UTF_8), "no-store", true);
    }

    public static void error(HttpExchange exchange, int status, String key, String detail) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("error", key);
        if (detail != null) body.addProperty("detail", detail);
        json(exchange, status, body);
    }

    public static void html(HttpExchange exchange, int status, String body) throws IOException {
        bytes(exchange, status, "text/html; charset=utf-8", body.getBytes(StandardCharsets.UTF_8), "no-store", true);
    }

    public static void redirect(HttpExchange exchange, String location) throws IOException {
        exchange.getResponseHeaders().set("Location", location);
        exchange.sendResponseHeaders(302, -1);
    }

    public static void empty(HttpExchange exchange, int status) throws IOException {
        exchange.sendResponseHeaders(status, -1);
    }

    /** Sends bytes with optional gzip; handles HEAD by sending headers only. */
    public static void bytes(HttpExchange exchange, int status, String contentType, byte[] data,
                             String cacheControl, boolean tryGzip) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        if (cacheControl != null) exchange.getResponseHeaders().set("Cache-Control", cacheControl);
        if (exchange.getRequestMethod().equals("HEAD")) {
            exchange.sendResponseHeaders(status, -1);
            return;
        }
        byte[] payload = data;
        if (tryGzip && data.length > GZIP_THRESHOLD && acceptsGzip(exchange)) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream(data.length / 3 + 16);
            try (GZIPOutputStream gzip = new GZIPOutputStream(buffer)) {
                gzip.write(data);
            }
            payload = buffer.toByteArray();
            exchange.getResponseHeaders().set("Content-Encoding", "gzip");
        }
        exchange.sendResponseHeaders(status, payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        }
    }

    /** Reads a bounded request body as UTF-8; throws when the cap is exceeded. */
    public static String readBody(HttpExchange exchange, int maxBytes) throws IOException {
        try (java.io.InputStream in = exchange.getRequestBody()) {
            byte[] data = in.readNBytes(maxBytes + 1);
            if (data.length > maxBytes)
                throw new IOException("request body exceeds " + maxBytes + " bytes");
            return new String(data, StandardCharsets.UTF_8);
        }
    }

    public static boolean acceptsGzip(HttpExchange exchange) {
        String accept = exchange.getRequestHeaders().getFirst("Accept-Encoding");
        return accept != null && accept.contains("gzip");
    }

    public static Map<String, String> cookies(HttpExchange exchange) {
        Map<String, String> cookies = new HashMap<>();
        for (String header : exchange.getRequestHeaders().getOrDefault("Cookie", java.util.List.of())) {
            for (String pair : header.split(";")) {
                int eq = pair.indexOf('=');
                if (eq > 0) cookies.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
            }
        }
        return cookies;
    }

    public static Map<String, String> query(HttpExchange exchange) {
        Map<String, String> params = new HashMap<>();
        String raw = exchange.getRequestURI().getRawQuery();
        if (raw == null) return params;
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                params.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            } else if (!pair.isEmpty()) {
                params.put(URLDecoder.decode(pair, StandardCharsets.UTF_8), "");
            }
        }
        return params;
    }

    /** Rate-limit key: first X-Forwarded-For hop when present (reverse proxy), else the socket address. */
    public static String clientIp(HttpExchange exchange) {
        String forwarded = exchange.getRequestHeaders().getFirst("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        return exchange.getRemoteAddress().getAddress().getHostAddress();
    }
}
