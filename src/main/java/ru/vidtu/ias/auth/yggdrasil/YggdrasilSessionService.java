/*
 * In-Game Account Switcher is a mod for Minecraft that allows you to change your logged in account in-game, without restarting Minecraft.
 * Copyright (C) 2015-2022 The_Fireplace
 * Copyright (C) 2021-2026 VidTu
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>
 */

package ru.vidtu.ias.auth.yggdrasil;

//? if >=26.2 {
import com.google.errorprone.annotations.CheckReturnValue;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.SignatureState;
import com.mojang.authlib.exceptions.AuthenticationException;
import com.mojang.authlib.exceptions.AuthenticationUnavailableException;
import com.mojang.authlib.minecraft.InsecurePublicKeyException;
import com.mojang.authlib.minecraft.MinecraftProfileTexture;
import com.mojang.authlib.minecraft.MinecraftProfileTextures;
import com.mojang.authlib.properties.Property;
//? if >=26.3 {
import com.mojang.authlib.minecraft.SessionService;
import com.mojang.authlib.services.ProfileResult;
//?} else {
/*import com.mojang.authlib.minecraft.MinecraftSessionService;
import com.mojang.authlib.yggdrasil.ProfileResult;*/
//?}
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.vidtu.ias.IAS;
import ru.vidtu.ias.utils.GSONUtils;

import java.net.InetAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Session service that redirects the in-game session (join/hasJoined/profile) of an
 * external (Yggdrasil / authlib-injector) account to its own authentication server.
 * <p>
 * Minecraft only talks to the session service via {@code Minecraft.services().sessionService()},
 * which the mod replaces when switching accounts (see {@code IASMinecraft#account}). Injecting
 * this service is the in-mod equivalent of what {@code authlib-injector} does at launch time,
 * and it is why external accounts can join third-party online-mode servers without a javaagent.
 * <p>
 * Only the session-relevant methods are implemented here: skin/texture/secure-property helpers
 * are delegated to the original (vanilla) session service, as they are not server-specific.
 *
 * @author VidTu
 * @see <a href="https://wiki.vg/Protocol_Encryption">wiki.vg/Protocol_Encryption</a>
 */
//? if >=26.3 {
public final class YggdrasilSessionService implements SessionService {
//?} else {
/*public final class YggdrasilSessionService implements MinecraftSessionService {*/
//?}
    /**
     * Dashless UUID pattern.
     */
    @NotNull
    private static final Pattern UUID_DASHLESS = Pattern.compile("(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})");

    /**
     * Dashed UUID replacer.
     */
    @NotNull
    private static final String UUID_DASHED = "$1-$2-$3-$4-$5";

    /**
     * Logger for this class.
     */
    @NotNull
    private static final Logger LOGGER = LoggerFactory.getLogger("IAS/YggdrasilSessionService");

    /**
     * Request client.
     */
    @NotNull
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(IAS.TIMEOUT)
            .version(HttpClient.Version.HTTP_2)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .executor(Runnable::run)
            .build();

    /**
     * API root of the authentication server.
     */
    @NotNull
    private final YggdrasilServer server;

    /**
     * Session access token of the current account.
     */
    @NotNull
    private final String accessToken;

    /**
     * Original session service, used for the non-session-specific methods.
     */
    //? if >=26.3 {
    @NotNull
    private final SessionService delegate;
    //?} else {
    /*@NotNull
    private final MinecraftSessionService delegate;*/
    //?}

    /**
     * Creates a new external session service.
     *
     * @param server      API root of the authentication server
     * @param accessToken Session access token
     * @param delegate    Original session service (for skin/texture/key helpers)
     */
    @Contract(pure = true)
    //? if >=26.3 {
    public YggdrasilSessionService(@NotNull String server, @NotNull String accessToken, @NotNull SessionService delegate) {
    //?} else {
    /*public YggdrasilSessionService(@NotNull String server, @NotNull String accessToken, @NotNull MinecraftSessionService delegate) {*/
    //?}
        this.server = new YggdrasilServer(server, "", false);
        this.accessToken = accessToken;
        this.delegate = delegate;
    }

    /**
     * Joins the server, redirecting the request to the external authentication server.
     *
     * @param profileId   Player UUID
     * @param accessToken Session access token
     * @param serverId    Server hash
     * @throws AuthenticationException On authentication error
     */
    @Override
    public void joinServer(@NotNull UUID profileId, @NotNull String accessToken, @NotNull String serverId) throws AuthenticationException {
        // Create the payload.
        JsonObject request = new JsonObject();
        request.addProperty("accessToken", accessToken);
        request.addProperty("selectedProfile", profileId.toString().replace("-", ""));
        request.addProperty("serverId", serverId);
        String payload = GSONUtils.GSON.toJson(request);

        // Send the request.
        HttpResponse<String> response;
        try {
            response = CLIENT.send(HttpRequest.newBuilder()
                    .uri(URI.create(this.server.endpoint("sessionserver/session/minecraft/join")))
                    .header("User-Agent", IAS.USER_AGENT)
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .timeout(IAS.TIMEOUT)
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build(), HttpResponse.BodyHandlers.ofString());
        } catch (Throwable t) {
            // Rethrow, trying to remove sensitive data.
            throw new AuthenticationUnavailableException("Unable to join the server via '" + this.server + "'.", t);
        }

        // Check the code. (204 No Content on success)
        int code = response.statusCode();
        if (code == 204) return;

        // Rethrow, trying to remove sensitive data.
        String message = "Unable to join the server via '" + this.server + "': " + code + ", body: " + response.body();
        message = message.replace(accessToken, "[ACCESS]");
        message = message.replace(this.accessToken, "[ACCESS]");
        throw new AuthenticationException(message);
    }

    @Override
    @Nullable
    public ProfileResult hasJoinedServer(@NotNull String userName, @NotNull String serverId, @Nullable InetAddress address) throws AuthenticationUnavailableException {
        // Build the URL.
        StringBuilder url = new StringBuilder(this.server.endpoint("sessionserver/session/minecraft/hasJoined"))
                .append("?username=").append(URLEncoder.encode(userName, StandardCharsets.UTF_8))
                .append("&serverId=").append(URLEncoder.encode(serverId, StandardCharsets.UTF_8));
        if (address != null) {
            url.append("&ip=").append(URLEncoder.encode(address.getHostAddress(), StandardCharsets.UTF_8));
        }

        // Send the request.
        HttpResponse<String> response;
        try {
            response = CLIENT.send(HttpRequest.newBuilder()
                    .uri(URI.create(url.toString()))
                    .header("User-Agent", IAS.USER_AGENT)
                    .header("Accept", "application/json")
                    .timeout(IAS.TIMEOUT)
                    .GET()
                    .build(), HttpResponse.BodyHandlers.ofString());
        } catch (Throwable t) {
            // Rethrow.
            throw new AuthenticationUnavailableException("Unable to check the session via '" + this.server + "'.", t);
        }

        // Skip if there is no session.
        int code = response.statusCode();
        if (code == 204 || response.body().isBlank()) return null;

        // Rethrow on errors.
        if (code != 200) {
            throw new AuthenticationUnavailableException("Unable to check the session via '" + this.server + "': " + code);
        }

        // Parse and return.
        return new ProfileResult(profile(parse(response.body())));
    }

    @Override
    @Nullable
    public Property getPackedTextures(@NotNull GameProfile profile) {
        // Fetch our profile, which contains the (unsigned) textures property.
        ProfileResult result = this.fetchProfile(profile.id(), false);
        if (result == null) return null;
        for (Property property : result.profile().properties().get("textures")) {
            return property;
        }
        return null;
    }

    @Override
    @Nullable
    public MinecraftProfileTextures unpackTextures(@NotNull Property packedTextures) {
        try {
            // Decode the base64 textures payload.
            byte[] decoded = Base64.getDecoder().decode(packedTextures.value());
            JsonObject json = GSONUtils.GSON.fromJson(new String(decoded, StandardCharsets.UTF_8), JsonObject.class);
            if (json == null) return this.delegate.unpackTextures(packedTextures);

            // Parse the textures.
            Map<MinecraftProfileTexture.Type, MinecraftProfileTexture> textures = new EnumMap<>(MinecraftProfileTexture.Type.class);
            if (json.has("textures") && json.get("textures").isJsonObject()) {
                JsonObject all = json.getAsJsonObject("textures");
                for (MinecraftProfileTexture.Type type : MinecraftProfileTexture.Type.values()) {
                    // Skip missing ones.
                    if (!all.has(type.name()) || !all.get(type.name()).isJsonObject()) continue;
                    JsonObject texture = all.getAsJsonObject(type.name());
                    if (!texture.has("url") || !texture.get("url").isJsonPrimitive()) continue;

                    // Read the metadata. (e.g. the "slim" model marker)
                    Map<String, String> metadata = new HashMap<>(0);
                    if (texture.has("metadata") && texture.get("metadata").isJsonObject()) {
                        for (Map.Entry<String, JsonElement> entry : texture.getAsJsonObject("metadata").entrySet()) {
                            metadata.put(entry.getKey(), entry.getValue().getAsString());
                        }
                    }

                    // Put it.
                    textures.put(type, new MinecraftProfileTexture(texture.get("url").getAsString(), metadata));
                }
            }

            // Note: third-party signatures can't be verified with the official key set, so the
            // textures are treated as unsigned instead of being dropped.
            return new MinecraftProfileTextures(textures.get(MinecraftProfileTexture.Type.SKIN),
                    textures.get(MinecraftProfileTexture.Type.CAPE),
                    textures.get(MinecraftProfileTexture.Type.ELYTRA),
                    SignatureState.UNSIGNED);
        } catch (Throwable t) {
            // Log and fall back to the original session service.
            LOGGER.warn("IAS: Unable to decode the textures of '{}'.", this.server, t);
            return this.delegate.unpackTextures(packedTextures);
        }
    }

    @Override
    @Nullable
    public ProfileResult fetchProfile(@NotNull UUID profileId, boolean requireSecure) {
        // Build the endpoint.
        String path = "sessionserver/session/minecraft/profile/" + profileId.toString().replace("-", "");

        // Send the request.
        HttpResponse<String> response;
        try {
            response = CLIENT.send(HttpRequest.newBuilder()
                    .uri(URI.create(this.server.endpoint(path)))
                    .header("User-Agent", IAS.USER_AGENT)
                    .header("Accept", "application/json")
                    .timeout(IAS.TIMEOUT)
                    .GET()
                    .build(), HttpResponse.BodyHandlers.ofString());
        } catch (Throwable t) {
            // Log and return null. (this is a cache-like lookup, the game handles null)
            LOGGER.warn("IAS: Unable to fetch profile from '{}'.", this.server, t);
            return null;
        }

        // Skip if there is no profile.
        int code = response.statusCode();
        if (code == 204 || response.body().isBlank()) return null;

        // Skip on errors.
        if (code != 200) {
            LOGGER.warn("IAS: Unable to fetch profile from '{}': {}", this.server, code);
            return null;
        }

        // Parse and return.
        try {
            return new ProfileResult(profile(parse(response.body())));
        } catch (Throwable t) {
            LOGGER.warn("IAS: Unable to parse profile from '{}'.", this.server, t);
            return null;
        }
    }

    @Override
    @NotNull
    public String getSecurePropertyValue(@NotNull Property property) throws InsecurePublicKeyException {
        return this.delegate.getSecurePropertyValue(property);
    }

    @Contract(pure = true)
    @Override
    @NotNull
    public String toString() {
        return "YggdrasilSessionService{" +
                "server=" + this.server +
                '}';
    }

    /**
     * Parses the response body into a JSON object.
     *
     * @param body Target body
     * @return Parsed object
     * @throws com.google.gson.JsonParseException If the body is empty
     */
    @CheckReturnValue
    @NotNull
    private static JsonObject parse(@NotNull String body) {
        JsonObject json = GSONUtils.GSON.fromJson(body, JsonObject.class);
        if (json == null) throw new com.google.gson.JsonParseException("Empty profile response.");
        return json;
    }

    /**
     * Parses the profile (with properties) from the JSON.
     *
     * @param json Target JSON
     * @return Parsed profile
     */
    @CheckReturnValue
    @NotNull
    private static GameProfile profile(@NotNull JsonObject json) {
        // Read the ID and name.
        String id = GSONUtils.getStringOrThrow(json, "id");
        String name = GSONUtils.getStringOrThrow(json, "name");

        // Convert the UUID.
        Matcher matcher = UUID_DASHLESS.matcher(id);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Invalid UUID: " + id);
        }
        GameProfile profile = new GameProfile(UUID.fromString(matcher.replaceAll(UUID_DASHED)), name);

        // Read the properties. (textures, mostly)
        JsonArray properties = json.has("properties") && json.get("properties").isJsonArray() ? json.getAsJsonArray("properties") : null;
        if (properties != null) {
            for (JsonElement element : properties) {
                // Skip invalid ones.
                if (!element.isJsonObject()) continue;
                JsonObject property = element.getAsJsonObject();

                // Read the property.
                String pName = GSONUtils.getStringOrThrow(property, "name");
                String pValue = GSONUtils.getStringOrThrow(property, "value");
                String pSignature = property.has("signature") && property.get("signature").isJsonPrimitive() ? property.get("signature").getAsString() : null;

                // Put it.
                if (pSignature == null) {
                    profile.properties().put(pName, new Property(pName, pValue));
                } else {
                    profile.properties().put(pName, new Property(pName, pValue, pSignature));
                }
            }
        }

        // Return it.
        Objects.requireNonNull(profile, "Profile is null");
        return profile;
    }
}
//?} else {
/*// External login is only supported on Minecraft 26.2 and newer.
 *///?}