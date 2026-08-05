package net.Dispatcher.web.auth;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import net.Dispatcher.web.http.JsonHttp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * HMAC-signed tokens: session cookies, OAuth state, and one-time login tokens
 * (minted by {@code /dispatcher web session}, single-use, short-lived).
 *
 * <p>Sessions carry identity only; the tier is re-resolved from the allowlist on every request so
 * revocation is immediate. Synthetic sessions from one-time tokens carry a fixed tier instead.
 */
public final class WebAuth {
    public static final String COOKIE = "dispatcher_session";
    private static final long STATE_TTL_MS = 10 * 60 * 1000;
    private static final long LOGIN_TOKEN_TTL_MS = 5 * 60 * 1000;

    public record Session(String sub, String name, Tier fixedTier, long exp) {}

    private final Secrets secrets;
    private final Allowlist allowlist;
    private final String publicUrl;
    private final boolean secureCookies;
    private final long sessionTtlMs;
    /** jti -> expiry; a one-time token is valid only while its jti is pending. */
    private final Map<String, Long> pendingLoginTokens = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    public WebAuth(Secrets secrets, Allowlist allowlist, String publicUrl, int sessionHours) {
        this.secrets = secrets;
        this.allowlist = allowlist;
        this.publicUrl = publicUrl == null ? "" : publicUrl.replaceAll("/+$", "");
        this.secureCookies = this.publicUrl.startsWith("https://");
        this.sessionTtlMs = sessionHours * 3600L * 1000L;
    }

    public Allowlist allowlist() {
        return allowlist;
    }

    public Secrets secrets() {
        return secrets;
    }

    public String publicUrl() {
        return publicUrl;
    }

    // --- token codec ---

    private String sign(JsonObject payload) {
        String body = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.toString().getBytes(StandardCharsets.UTF_8));
        return body + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(hmac(body));
    }

    private JsonObject verify(String token) {
        if (token == null) return null;
        int dot = token.lastIndexOf('.');
        if (dot <= 0) return null;
        String body = token.substring(0, dot);
        byte[] expected = hmac(body);
        byte[] actual;
        try {
            actual = Base64.getUrlDecoder().decode(token.substring(dot + 1));
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (!MessageDigest.isEqual(expected, actual)) return null;
        try {
            String json = new String(Base64.getUrlDecoder().decode(body), StandardCharsets.UTF_8);
            JsonObject payload = JsonParser.parseString(json).getAsJsonObject();
            if (!payload.has("exp") || payload.get("exp").getAsLong() < System.currentTimeMillis()) return null;
            return payload;
        } catch (Exception e) {
            return null;
        }
    }

    private byte[] hmac(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secrets.sessionSecret, "HmacSHA256"));
            return mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    // --- sessions ---

    public String mintSession(String sub, String name, Tier fixedTier) {
        JsonObject payload = new JsonObject();
        payload.addProperty("sub", sub);
        payload.addProperty("name", name);
        if (fixedTier != null) payload.addProperty("t", fixedTier.name());
        payload.addProperty("exp", System.currentTimeMillis() + sessionTtlMs);
        return sign(payload);
    }

    public Session parseSession(String token) {
        JsonObject payload = verify(token);
        if (payload == null || !payload.has("sub")) return null;
        Tier fixed = payload.has("t") ? Tier.parse(payload.get("t").getAsString()) : null;
        return new Session(payload.get("sub").getAsString(),
                payload.has("name") ? payload.get("name").getAsString() : "",
                fixed, payload.get("exp").getAsLong());
    }

    public Tier tierOf(Session session) {
        return session.fixedTier() != null ? session.fixedTier() : allowlist.tierOf(session.sub());
    }

    public String sessionCookie(String value, long maxAgeSeconds) {
        return COOKIE + "=" + value + "; Path=/; HttpOnly; SameSite=Lax; Max-Age=" + maxAgeSeconds
                + (secureCookies ? "; Secure" : "");
    }

    public long sessionTtlSeconds() {
        return sessionTtlMs / 1000;
    }

    // --- OAuth state ---

    public String mintState(String returnHash) {
        JsonObject payload = new JsonObject();
        payload.addProperty("ret", returnHash == null ? "" : returnHash);
        payload.addProperty("exp", System.currentTimeMillis() + STATE_TTL_MS);
        return sign(payload);
    }

    /** @return the return-hash the login started with, or null when the state is invalid/expired. */
    public String parseState(String token) {
        JsonObject payload = verify(token);
        return payload == null ? null : payload.has("ret") ? payload.get("ret").getAsString() : "";
    }

    // --- one-time login tokens ---

    public String mintLoginToken(Tier tier) {
        byte[] jtiBytes = new byte[12];
        random.nextBytes(jtiBytes);
        String jti = Base64.getUrlEncoder().withoutPadding().encodeToString(jtiBytes);
        long exp = System.currentTimeMillis() + LOGIN_TOKEN_TTL_MS;
        pendingLoginTokens.put(jti, exp);
        JsonObject payload = new JsonObject();
        payload.addProperty("jti", jti);
        payload.addProperty("t", tier.name());
        payload.addProperty("exp", exp);
        return sign(payload);
    }

    /** Single use: valid only while its jti is pending; consuming removes it. */
    public Tier consumeLoginToken(String token) {
        JsonObject payload = verify(token);
        if (payload == null || !payload.has("jti") || !payload.has("t")) return null;
        if (pendingLoginTokens.remove(payload.get("jti").getAsString()) == null) return null;
        return Tier.parse(payload.get("t").getAsString());
    }

    public void cleanup() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, Long>> it = pendingLoginTokens.entrySet().iterator();
        while (it.hasNext())
            if (it.next().getValue() < now) it.remove();
    }

    // --- request gate ---

    /**
     * Authenticates the exchange and enforces the tier plus CSRF/Origin rules for mutations.
     * Sends the error response itself and returns null when the request must not proceed.
     * {@code min == Tier.NONE} accepts any valid session (used by /api/me).
     */
    public Session require(HttpExchange exchange, Tier min) throws IOException {
        Session session = parseSession(JsonHttp.cookies(exchange).get(COOKIE));
        if (session == null) {
            JsonHttp.error(exchange, 401, "unauthorized", "log in first");
            return null;
        }
        String method = exchange.getRequestMethod();
        boolean mutating = !method.equals("GET") && !method.equals("HEAD") && !method.equals("OPTIONS");
        if (mutating) {
            if (exchange.getRequestHeaders().getFirst("X-Dispatcher-Csrf") == null) {
                JsonHttp.error(exchange, 403, "csrf", "missing X-Dispatcher-Csrf header");
                return null;
            }
            String origin = exchange.getRequestHeaders().getFirst("Origin");
            if (origin != null && !publicUrl.isBlank() && !origin.equalsIgnoreCase(publicUrl)) {
                JsonHttp.error(exchange, 403, "bad_origin", origin);
                return null;
            }
        }
        if (!tierOf(session).atLeast(min)) {
            JsonHttp.error(exchange, 403, "forbidden", "requires " + min.name().toLowerCase()
                    + ", you are " + tierOf(session).name().toLowerCase());
            return null;
        }
        return session;
    }
}
