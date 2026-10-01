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

import com.google.errorprone.annotations.CheckReturnValue;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.vidtu.ias.IAS;
import ru.vidtu.ias.auth.microsoft.fields.MCProfile;
import ru.vidtu.ias.utils.GSONUtils;
import ru.vidtu.ias.utils.IUtils;
import ru.vidtu.ias.utils.exceptions.FriendlyException;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * HTTP client for the Yggdrasil (authlib-injector) authentication protocol.
 * <p>
 * This is the standard protocol used by external ("third-party", "skin-site") Minecraft
 * authentication servers, and is what {@code authlib-injector} itself speaks. The mod uses
 * it both to authenticate the account and to point the in-game session to the server.
 *
 * @author VidTu
 * @see <a href="https://wiki.vg/Authentication">wiki.vg/Authentication</a>
 * @see <a href="https://github.com/yushijinhun/authlib-injector/wiki">authlib-injector spec</a>
 */
public final class YggdrasilAuth {
    /**
     * Response header used by authlib-injector servers to advertise the real API root.
     * <p>
     * This allows a user to add a "short" URL (e.g. a website/portal) and still get
     * redirected to the actual Yggdrasil API root.
     */
    @NotNull
    public static final String API_LOCATION_HEADER = "x-authlib-injector-api-location";

    /**
     * Request client for credential-bearing requests.
     * <p>
     * Redirects are disabled on purpose: the {@code authenticate}/{@code refresh} bodies contain
     * the plain account password/access token, and an attacker-controlled (or misconfigured)
     * server could otherwise get them re-sent to another host via a {@code 30x} response.
     */
    @NotNull
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(IAS.TIMEOUT)
            .version(HttpClient.Version.HTTP_2)
            .followRedirects(HttpClient.Redirect.NEVER)
            .executor(IAS.executor())
            .build();

    /**
     * Request client for server discovery.
     * <p>
     * Redirects are followed here, since the request carries no credentials. Note that Java
     * refuses to follow an HTTPS-to-HTTP downgrade with {@link HttpClient.Redirect#NORMAL}.
     */
    @NotNull
    private static final HttpClient CLIENT_REDIRECT = HttpClient.newBuilder()
            .connectTimeout(IAS.TIMEOUT)
            .version(HttpClient.Version.HTTP_2)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .executor(IAS.executor())
            .build();

    /**
     * An instance of this class cannot be created.
     *
     * @throws AssertionError Always
     */
    @Contract(value = "-> fail", pure = true)
    private YggdrasilAuth() {
        throw new AssertionError("No instances.");
    }

    /**
     * Locates the Yggdrasil API root for the specified URL and fetches its metadata.
     * <p>
     * If the server responds with the {@code x-authlib-injector-api-location} header, the
     * URL is redirected to the value of that header, relative to the response URL. The
     * normalized API root is then requested, in order to read the server name and the
     * {@code non_email_login} feature flag.
     *
     * @param url Target server URL (with or without scheme)
     * @return Future that will complete with the located server or exceptionally
     */
    @CheckReturnValue
    @NotNull
    public static CompletableFuture<YggdrasilServer> locate(@NotNull String url) {
        // Normalize the URL.
        url = url.strip();
        if (!url.contains("://")) {
            url = "https://" + url;
        }
        final String target = url;

        // Validate the URL early, so that the user gets a proper error message.
        final URI uri;
        try {
            uri = URI.create(target);
            if (uri.getHost() == null) {
                throw new IllegalArgumentException("No host in URL: " + target);
            }
        } catch (Throwable t) {
            throw new FriendlyException("Invalid Yggdrasil server URL: " + target, t, "ias.error.yggdrasil.server");
        }

        // Refuse to send credentials over plaintext HTTP.
        requireHttps(uri);

        // Send the request.
        return CLIENT_REDIRECT.sendAsync(HttpRequest.newBuilder()
                .uri(uri)
                .header("User-Agent", IAS.USER_AGENT)
                .header("Accept", "application/json")
                .timeout(IAS.TIMEOUT)
                .GET()
                .build(), HttpResponse.BodyHandlers.ofString()).thenComposeAsync(response -> {
            // Process the response.
            try {
                // Check the code.
                int status = response.statusCode();
                if (status < 200 || status > 299) {
                    throw new FriendlyException("Invalid status code: " + status, "ias.error.yggdrasil.server");
                }

                // Follow the API location header, if present. (relative to the response URL)
                String location = response.headers().firstValue(API_LOCATION_HEADER).orElse(null);
                URI base = location != null ? response.uri().resolve(location.strip()) : response.uri();

                // Refuse an insecure API root, even if the original URL was secure.
                requireHttps(base);

                // Build and return the server.
                String root = base.toString();
                return metadata(root);
            } catch (Throwable t) {
                // Rethrow.
                throw friendly(new RuntimeException("Unable to locate Yggdrasil server at '" + target + "'.", t));
            }
        }, IAS.executor());
    }

    /**
     * Fetches the metadata for the (already located) Yggdrasil API root.
     *
     * @param apiRoot API root URL
     * @return Future that will complete with the located server or exceptionally
     */
    @CheckReturnValue
    @NotNull
    private static CompletableFuture<YggdrasilServer> metadata(@NotNull String apiRoot) {
        // Normalize the URL. (trailing slash is required for the endpoint builder)
        if (!apiRoot.endsWith("/")) {
            apiRoot = apiRoot + "/";
        }
        YggdrasilServer server = new YggdrasilServer(apiRoot, "", false);

        // Send the request.
        return CLIENT.sendAsync(HttpRequest.newBuilder()
                .uri(URI.create(server.apiRoot()))
                .header("User-Agent", IAS.USER_AGENT)
                .header("Accept", "application/json")
                .timeout(IAS.TIMEOUT)
                .GET()
                .build(), HttpResponse.BodyHandlers.ofString()).thenApplyAsync(response -> {
            // Metadata is optional: the server might as well not return it.
            String name = "";
            boolean nonEmailLogin = false;
            try {
                // Check the code.
                int status = response.statusCode();
                if (status < 200 || status > 299) {
                    throw new IllegalArgumentException("Invalid status code: " + status);
                }

                // Read the metadata.
                JsonObject json = GSONUtils.GSON.fromJson(response.body(), JsonObject.class);
                if (json != null && json.has("meta") && json.get("meta").isJsonObject()) {
                    JsonObject meta = json.getAsJsonObject("meta");
                    if (meta.has("serverName") && meta.get("serverName").isJsonPrimitive()) {
                        name = meta.get("serverName").getAsString();
                    }
                    if (meta.has("feature") && meta.get("feature").isJsonObject()) {
                        JsonObject feature = meta.getAsJsonObject("feature");
                        if (feature.has("non_email_login") && feature.get("non_email_login").isJsonPrimitive()) {
                            nonEmailLogin = feature.get("non_email_login").getAsBoolean();
                        }
                    }
                }
            } catch (Throwable ignored) {
                // Metadata is optional.
            }

            // Build and return the server.
            return new YggdrasilServer(server.apiRoot(), name, nonEmailLogin);
        }, IAS.executor()).exceptionallyAsync(t -> {
            // Rethrow.
            throw friendly(new RuntimeException("Unable to read Yggdrasil metadata for '" + server.apiRoot() + "'.", t));
        }, IAS.executor());
    }

    /**
     * Authenticates with the Yggdrasil server using the username and password.
     * <p>
     * The result is <b>raw</b>: if the account owns several characters and none was requested,
     * the server may leave {@code selectedProfile} empty, and the caller has to pick one from
     * the available profiles and authenticate again with it selected.
     *
     * @param server      Target server
     * @param username    Account username (email or, on some servers, a nickname)
     * @param password    Account password
     * @param clientToken Client token to use
     * @param profileId   Character UUID to select, {@code null} to let the server decide
     * @return Future that will complete with the raw result or exceptionally
     */
    @CheckReturnValue
    @NotNull
    public static CompletableFuture<YggdrasilAuthResult> authenticate(@NotNull YggdrasilServer server, @NotNull String username, @NotNull String password, @NotNull String clientToken, @Nullable UUID profileId) {
        // Create the payload.
        JsonObject agent = new JsonObject();
        agent.addProperty("name", "Minecraft");
        agent.addProperty("version", 1);

        JsonObject request = new JsonObject();
        request.add("agent", agent);
        request.addProperty("username", username);
        request.addProperty("password", password);
        request.addProperty("clientToken", clientToken);
        request.addProperty("requestUser", true);
        if (profileId != null) {
            request.addProperty("selectedProfile", profileId.toString().replace("-", ""));
        }
        String payload = GSONUtils.GSON.toJson(request);

        // Send the request.
        return CLIENT.sendAsync(HttpRequest.newBuilder()
                .uri(URI.create(server.endpoint("authserver/authenticate")))
                .header("User-Agent", IAS.USER_AGENT)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .timeout(IAS.TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build(), HttpResponse.BodyHandlers.ofString()).thenApplyAsync(response -> {
            // Process the response.
            try {
                // Check the code.
                int status = response.statusCode();
                if (status != 200) {
                    // Parse the error, if any.
                    String error = error(response.body());
                    String detail = errorMessage(response.body());
                    throw new FriendlyException("Yggdrasil authenticate failed: " + status + ", error: " + error, null,
                            authKey(detail != null ? detail : error), detail);
                }

                // Decode the result and return it.
                JsonObject json = GSONUtils.GSON.fromJson(response.body(), JsonObject.class);
                Objects.requireNonNull(json, "Response is null");
                return YggdrasilAuthResult.fromJson(json);
            } catch (Throwable t) {
                // Rethrow, trying to remove sensitive data.
                String message = "Unable to authenticate with '" + server + "' (" + response + " with " + response.headers() + "): " + response.body();
                message = message.replace(password, "[PASSWORD]");
                throw friendly(new RuntimeException(message, t));
            }
        }, IAS.executor());
    }

    /**
     * Validates the access token for the Yggdrasil server.
     *
     * @param server      Target server
     * @param accessToken Access token to validate
     * @param clientToken Client token to validate
     * @return Future that will complete with the validation result or exceptionally
     */
    @CheckReturnValue
    @NotNull
    public static CompletableFuture<Boolean> validate(@NotNull YggdrasilServer server, @NotNull String accessToken, @NotNull String clientToken) {
        // Create the payload.
        JsonObject request = new JsonObject();
        request.addProperty("accessToken", accessToken);
        request.addProperty("clientToken", clientToken);
        String payload = GSONUtils.GSON.toJson(request);

        // Send the request.
        return CLIENT.sendAsync(HttpRequest.newBuilder()
                .uri(URI.create(server.endpoint("authserver/validate")))
                .header("User-Agent", IAS.USER_AGENT)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .timeout(IAS.TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build(), HttpResponse.BodyHandlers.ofString()).thenApplyAsync(response -> {
            // Valid if 204 No Content.
            return response.statusCode() == 204;
        }, IAS.executor()).exceptionallyAsync(t -> {
            // Rethrow.
            throw friendly(new RuntimeException("Unable to validate Yggdrasil session for '" + server + "'.", t));
        }, IAS.executor());
    }

    /**
     * Refreshes the access token for the Yggdrasil server.
     *
     * @param server      Target server
     * @param accessToken Access token to refresh
     * @param clientToken Client token to refresh
     * @return Future that will complete with the refreshed session or exceptionally
     */
    @CheckReturnValue
    @NotNull
    public static CompletableFuture<YggdrasilAuthResult> refresh(@NotNull YggdrasilServer server, @NotNull String accessToken, @NotNull String clientToken) {
        return refresh(server, accessToken, clientToken, null);
    }

    /**
     * Refreshes the access token, optionally binding the session to the specified character.
     * <p>
     * Some servers bind an access token to a specific character and then refuse to join as any
     * other character, and only {@code authserver/refresh} can (re)bind it. Note that some
     * implementations (e.g. Blessing Skin) expect {@code selectedProfile} to be an object with the
     * {@code id} and {@code name}, rather than a plain UUID string.
     *
     * @param server      Target server
     * @param accessToken Access token to refresh
     * @param clientToken Client token to refresh
     * @param profile     Character to bind the session to, {@code null} to keep the current binding
     * @return Future that will complete with the refreshed session or exceptionally
     */
    @CheckReturnValue
    @NotNull
    public static CompletableFuture<YggdrasilAuthResult> refresh(@NotNull YggdrasilServer server, @NotNull String accessToken, @NotNull String clientToken, @Nullable MCProfile profile) {
        // Create the payload.
        JsonObject request = new JsonObject();
        request.addProperty("accessToken", accessToken);
        request.addProperty("clientToken", clientToken);
        request.addProperty("requestUser", true);
        if (profile != null) {
            JsonObject selected = new JsonObject();
            selected.addProperty("id", profile.uuid().toString().replace("-", ""));
            selected.addProperty("name", profile.name());
            request.add("selectedProfile", selected);
        }
        String payload = GSONUtils.GSON.toJson(request);

        // Send the request.
        return CLIENT.sendAsync(HttpRequest.newBuilder()
                .uri(URI.create(server.endpoint("authserver/refresh")))
                .header("User-Agent", IAS.USER_AGENT)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .timeout(IAS.TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build(), HttpResponse.BodyHandlers.ofString()).thenApplyAsync(response -> {
            // Process the response.
            try {
                // Check the code.
                int status = response.statusCode();
                if (status != 200) {
                    String detail = errorMessage(response.body());
                    throw new FriendlyException("Yggdrasil refresh failed: " + status, null, "ias.error.yggdrasil.session", detail);
                }

                // Decode the result and return it.
                JsonObject json = GSONUtils.GSON.fromJson(response.body(), JsonObject.class);
                Objects.requireNonNull(json, "Response is null");
                return YggdrasilAuthResult.fromJson(json);
            } catch (Throwable t) {
                // Rethrow, trying to remove sensitive data.
                String message = "Unable to refresh Yggdrasil session for '" + server + "' (" + response + " with " + response.headers() + "): " + response.body();
                message = message.replace(accessToken, "[ACCESS]");
                message = message.replace(clientToken, "[CLIENT]");
                throw friendly(new RuntimeException(message, t));
            }
        }, IAS.executor());
    }

    /**
     * Fetches the profile (name and UUID) for the specified UUID.
     *
     * @param server Target server
     * @param uuid   Profile UUID
     * @return Future that will complete with the profile or exceptionally
     */
    @CheckReturnValue
    @NotNull
    public static CompletableFuture<MCProfile> profile(@NotNull YggdrasilServer server, @NotNull UUID uuid) {
        // Build the endpoint. (Yggdrasil uses dashless UUIDs)
        String path = "sessionserver/session/minecraft/profile/" + uuid.toString().replace("-", "");

        // Send the request.
        return CLIENT.sendAsync(HttpRequest.newBuilder()
                .uri(URI.create(server.endpoint(path)))
                .header("User-Agent", IAS.USER_AGENT)
                .header("Accept", "application/json")
                .timeout(IAS.TIMEOUT)
                .GET()
                .build(), HttpResponse.BodyHandlers.ofString()).thenApplyAsync(response -> {
            // Process the response.
            try {
                // Check the code.
                int status = response.statusCode();
                if (status != 200) {
                    throw new FriendlyException("Yggdrasil profile failed: " + status, "ias.error.yggdrasil.profile");
                }

                // Decode the profile and return it.
                JsonObject json = GSONUtils.GSON.fromJson(response.body(), JsonObject.class);
                Objects.requireNonNull(json, "Response is null");
                return MCProfile.fromJson(json);
            } catch (Throwable t) {
                // Rethrow.
                throw friendly(new RuntimeException("Unable to fetch Yggdrasil profile for '" + uuid + "' from '" + server + "'.", t));
            }
        }, IAS.executor());
    }

    /**
     * Ensures the URI uses HTTPS, so that the credentials are never sent in plaintext.
     *
     * @param uri Target URI
     * @return The same URI
     * @throws FriendlyException If the URI is not an HTTPS one
     */
    @Contract(value = "_ -> param1", pure = true)
    @NotNull
    private static URI requireHttps(@NotNull URI uri) {
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new FriendlyException("Insecure (non-HTTPS) Yggdrasil server URL: " + uri, "ias.error.yggdrasil.https");
        }
        return uri;
    }

    /**
     * Extracts the {@code errorMessage}/{@code error} from the Yggdrasil error response.
     *
     * @param body Response body
     * @return Extracted error, {@code null} if unable to extract
     */
    @Contract(pure = true)
    @Nullable
    private static String error(@NotNull String body) {
        try {
            JsonObject json = GSONUtils.GSON.fromJson(body, JsonObject.class);
            if (json == null) return null;
            if (json.has("errorMessage") && json.get("errorMessage").isJsonPrimitive()) {
                return json.get("errorMessage").getAsString();
            }
            if (json.has("error") && json.get("error").isJsonPrimitive()) {
                return json.get("error").getAsString();
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Extracts only the human-readable {@code errorMessage} from the Yggdrasil error response.
     *
     * @param body Response body
     * @return Extracted message, {@code null} if unable to extract
     */
    @Contract(pure = true)
    @Nullable
    static String errorMessage(@NotNull String body) {
        try {
            JsonObject json = GSONUtils.GSON.fromJson(body, JsonObject.class);
            if (json == null) return null;
            if (json.has("errorMessage") && json.get("errorMessage").isJsonPrimitive()) {
                return json.get("errorMessage").getAsString();
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Picks the user-facing translation key for a failed authentication.
     * <p>
     * The Yggdrasil protocol only defines a coarse {@code error} code
     * ({@code ForbiddenOperationException} is used both for wrong credentials and for rate
     * limiting), so the human-readable message has to be inspected. Unknown messages fall back
     * to the generic authentication error; and, regardless of the key, the original message is
     * always shown to the user as-is.
     *
     * @param error Human-readable server error, {@code null} if unknown
     * @return Message translation key
     */
    @Contract(pure = true)
    @NotNull
    private static String authKey(@Nullable String error) {
        // Nothing to inspect.
        if (error == null || error.isBlank()) return "ias.error.yggdrasil.auth";
        String lower = error.toLowerCase(Locale.ROOT);

        // Too many requests. (e.g. Blessing Skin rate limiting the authenticate endpoint)
        if (lower.contains("频繁") || lower.contains("too many") || lower.contains("rate limit") || lower.contains("rate-limit")) {
            return "ias.error.yggdrasil.ratelimit";
        }

        // Banned or locked. (checked before the credentials, as those messages mention the account)
        if (lower.contains("封禁") || lower.contains("锁定") || lower.contains("banned") || lower.contains("locked")) {
            return "ias.error.yggdrasil.banned";
        }

        // Wrong credentials.
        if (lower.contains("密码") || lower.contains("账号") || lower.contains("credentials") || lower.contains("password") || lower.contains("username")) {
            return "ias.error.yggdrasil.credentials";
        }

        // Generic.
        return "ias.error.yggdrasil.auth";
    }

    /**
     * Wraps the exception, converting the probable connection errors into a friendly one.
     *
     * @param t Target exception
     * @return Original exception or a friendly wrapper
     */
    @Contract(value = "_ -> param1", pure = true)
    @NotNull
    private static RuntimeException friendly(@NotNull RuntimeException t) {
        // Already friendly.
        if (IUtils.anyInCausalChain(t, err -> err instanceof FriendlyException)) return t;

        // Probable case - no internet connection or an unresolvable host.
        if (IUtils.anyInCausalChain(t, err -> err instanceof UnresolvedAddressException || err instanceof UnknownHostException
                || err instanceof NoRouteToHostException || err instanceof HttpTimeoutException || err instanceof ConnectException)) {
            return new FriendlyException("Unable to connect to the Yggdrasil server.", t, "ias.error.connect");
        }

        // As-is.
        return t;
    }
}