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
     * Request client.
     */
    @NotNull
    private static final HttpClient CLIENT = HttpClient.newBuilder()
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
            uri = URI.create(url);
            if (uri.getHost() == null) {
                throw new IllegalArgumentException("No host in URL: " + url);
            }
        } catch (Throwable t) {
            throw new FriendlyException("Invalid Yggdrasil server URL: " + url, t, "ias.error.yggdrasil.server");
        }

        // Send the request.
        return CLIENT.sendAsync(HttpRequest.newBuilder()
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
     *
     * @param server      Target server
     * @param username    Account username (email or, on some servers, a nickname)
     * @param password    Account password
     * @param clientToken Client token to use
     * @return Future that will complete with the session or exceptionally
     */
    @CheckReturnValue
    @NotNull
    public static CompletableFuture<YggdrasilSession> authenticate(@NotNull YggdrasilServer server, @NotNull String username, @NotNull String password, @NotNull String clientToken) {
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
                    throw new FriendlyException("Yggdrasil authenticate failed: " + status + ", error: " + error, "ias.error.yggdrasil.auth");
                }

                // Decode the session and return it.
                JsonObject json = GSONUtils.GSON.fromJson(response.body(), JsonObject.class);
                Objects.requireNonNull(json, "Response is null");
                return YggdrasilSession.fromJson(json);
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
    public static CompletableFuture<YggdrasilSession> refresh(@NotNull YggdrasilServer server, @NotNull String accessToken, @NotNull String clientToken) {
        // Create the payload.
        JsonObject request = new JsonObject();
        request.addProperty("accessToken", accessToken);
        request.addProperty("clientToken", clientToken);
        request.addProperty("requestUser", true);
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
                    throw new FriendlyException("Yggdrasil refresh failed: " + status, "ias.error.yggdrasil.session");
                }

                // Decode the session and return it.
                JsonObject json = GSONUtils.GSON.fromJson(response.body(), JsonObject.class);
                Objects.requireNonNull(json, "Response is null");
                return YggdrasilSession.fromJson(json);
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