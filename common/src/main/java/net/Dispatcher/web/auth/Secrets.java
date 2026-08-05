package net.Dispatcher.web.auth;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * config/createdispatcher/secrets.json — Discord app credentials + the HMAC session secret.
 * Created with an auto-generated session secret on first start; 600 permissions attempted.
 */
public final class Secrets {
    public final String discordClientId;
    public final String discordClientSecret;
    public final byte[] sessionSecret;

    private Secrets(String discordClientId, String discordClientSecret, byte[] sessionSecret) {
        this.discordClientId = discordClientId;
        this.discordClientSecret = discordClientSecret;
        this.sessionSecret = sessionSecret;
    }

    public boolean discordConfigured() {
        return !discordClientId.isBlank() && !discordClientSecret.isBlank();
    }

    public static Secrets load(Path file) throws IOException {
        if (!Files.exists(file)) {
            Secrets fresh = new Secrets("", "", randomSecret());
            write(file, fresh);
            return fresh;
        }
        JsonObject json = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
        String clientId = json.has("discordClientId") ? json.get("discordClientId").getAsString() : "";
        String clientSecret = json.has("discordClientSecret") ? json.get("discordClientSecret").getAsString() : "";
        String secret = json.has("sessionSecret") ? json.get("sessionSecret").getAsString() : "";
        if (secret.isBlank()) {
            Secrets regenerated = new Secrets(clientId, clientSecret, randomSecret());
            write(file, regenerated);
            return regenerated;
        }
        return new Secrets(clientId, clientSecret, Base64.getUrlDecoder().decode(secret));
    }

    private static byte[] randomSecret() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }

    private static void write(Path file, Secrets secrets) throws IOException {
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        JsonObject json = new JsonObject();
        json.addProperty("discordClientId", secrets.discordClientId);
        json.addProperty("discordClientSecret", secrets.discordClientSecret);
        json.addProperty("sessionSecret", Base64.getUrlEncoder().withoutPadding().encodeToString(secrets.sessionSecret));
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, gson.toJson(json), StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (Exception ignored) {
            // Non-POSIX filesystem (Windows) — nothing to do.
        }
    }
}
