package net.Dispatcher.web.http;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Serves the built frontend from jar resources under assets/realism/web/
 * (populated by common's processResources from the committed web/dist).
 * index.html is served no-cache; Vite's hashed /assets/* files are immutable.
 */
public final class StaticFiles {
    private static final String PREFIX = "/assets/createdispatcher/web/";
    private static final Pattern SAFE_PATH = Pattern.compile("[A-Za-z0-9._/-]+");
    private static final Map<String, byte[]> CACHE = new ConcurrentHashMap<>();
    private static final byte[] MISSING = new byte[0];

    private StaticFiles() {}

    public static void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        if (!method.equals("GET") && !method.equals("HEAD")) {
            JsonHttp.error(exchange, 405, "method_not_allowed", method);
            return;
        }
        String path = exchange.getRequestURI().getPath();
        if (path.equals("/") || path.isEmpty()) {
            byte[] index = load("index.html");
            if (index == null) {
                JsonHttp.html(exchange, 503, "<pre>Dispatcher web UI is not built.\n\ncd web && npm install && npm run build\nthen restart the server.</pre>");
                return;
            }
            JsonHttp.bytes(exchange, 200, "text/html; charset=utf-8", index, "no-cache", true);
            return;
        }
        String rel = path.substring(1);
        if (rel.contains("..") || rel.contains("//") || !SAFE_PATH.matcher(rel).matches()) {
            JsonHttp.error(exchange, 404, "not_found", null);
            return;
        }
        byte[] data = load(rel);
        if (data == null) {
            JsonHttp.error(exchange, 404, "not_found", null);
            return;
        }
        String cache = rel.startsWith("assets/") ? "public, max-age=31536000, immutable" : "no-cache";
        JsonHttp.bytes(exchange, 200, contentType(rel), data, cache, compressible(rel));
    }

    private static byte[] load(String rel) {
        byte[] cached = CACHE.computeIfAbsent(rel, key -> {
            try (InputStream in = StaticFiles.class.getResourceAsStream(PREFIX + key)) {
                return in == null ? MISSING : in.readAllBytes();
            } catch (IOException e) {
                return MISSING;
            }
        });
        return cached == MISSING ? null : cached;
    }

    private static String contentType(String path) {
        int dot = path.lastIndexOf('.');
        String ext = dot < 0 ? "" : path.substring(dot + 1).toLowerCase();
        return switch (ext) {
            case "html" -> "text/html; charset=utf-8";
            case "js", "mjs" -> "text/javascript; charset=utf-8";
            case "css" -> "text/css; charset=utf-8";
            case "json", "map" -> "application/json; charset=utf-8";
            case "svg" -> "image/svg+xml";
            case "png" -> "image/png";
            case "webp" -> "image/webp";
            case "ico" -> "image/x-icon";
            case "woff2" -> "font/woff2";
            case "txt" -> "text/plain; charset=utf-8";
            default -> "application/octet-stream";
        };
    }

    private static boolean compressible(String path) {
        return !path.endsWith(".png") && !path.endsWith(".webp") && !path.endsWith(".woff2");
    }
}
