package net.Dispatcher.web.auth;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** Discord OAuth2 authorization-code flow, identify scope only. Outbound calls via the JDK HttpClient. */
public final class DiscordOAuth {
    public record DiscordUser(String id, String username, String avatar) {}

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private DiscordOAuth() {}

    public static String authorizeUrl(String clientId, String redirectUri, String state) {
        return "https://discord.com/oauth2/authorize?response_type=code&scope=identify"
                + "&client_id=" + encode(clientId)
                + "&redirect_uri=" + encode(redirectUri)
                + "&state=" + encode(state);
    }

    public static String exchangeCode(String clientId, String clientSecret, String redirectUri, String code)
            throws IOException, InterruptedException {
        String form = "grant_type=authorization_code"
                + "&client_id=" + encode(clientId)
                + "&client_secret=" + encode(clientSecret)
                + "&redirect_uri=" + encode(redirectUri)
                + "&code=" + encode(code);
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://discord.com/api/oauth2/token"))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200)
            throw new IOException("Discord token exchange failed: HTTP " + response.statusCode());
        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
        if (!json.has("access_token")) throw new IOException("Discord token exchange returned no access_token");
        return json.get("access_token").getAsString();
    }

    public static DiscordUser fetchUser(String accessToken) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://discord.com/api/users/@me"))
                .timeout(Duration.ofSeconds(5))
                .header("Authorization", "Bearer " + accessToken)
                .GET()
                .build();
        HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200)
            throw new IOException("Discord user fetch failed: HTTP " + response.statusCode());
        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
        return new DiscordUser(
                json.get("id").getAsString(),
                json.has("global_name") && !json.get("global_name").isJsonNull()
                        ? json.get("global_name").getAsString()
                        : json.get("username").getAsString(),
                json.has("avatar") && !json.get("avatar").isJsonNull() ? json.get("avatar").getAsString() : "");
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
