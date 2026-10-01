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

package ru.vidtu.ias.account;

import com.google.errorprone.annotations.CheckReturnValue;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.vidtu.ias.IAS;
import ru.vidtu.ias.auth.LoginData;
import ru.vidtu.ias.auth.handlers.LoginHandler;
import ru.vidtu.ias.auth.microsoft.fields.MCProfile;
import ru.vidtu.ias.auth.yggdrasil.YggdrasilAuth;
import ru.vidtu.ias.auth.yggdrasil.YggdrasilServer;
import ru.vidtu.ias.auth.yggdrasil.YggdrasilSession;
import ru.vidtu.ias.crypt.Crypt;
import ru.vidtu.ias.utils.Holder;
import ru.vidtu.ias.utils.IUtils;
import ru.vidtu.ias.utils.exceptions.FriendlyException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * External (Yggdrasil / authlib-injector) account instance.
 * <p>
 * Unlike {@link MicrosoftAccount}, this account authenticates against a user-provided
 * third-party authentication server ("skin site") using the standard Yggdrasil protocol.
 * The stored data is encrypted with a {@link Crypt} and contains the username, password
 * and the last known session tokens, so that the account can be re-authenticated when
 * the session expires.
 *
 * @author VidTu
 * @see YggdrasilAuth
 */
public final class YggdrasilAccount implements Account {
    /**
     * Initializing login.
     */
    @NotNull
    public static final String INITIALIZING = "ias.login.initializing";

    /**
     * Decrypting tokens.
     */
    @NotNull
    public static final String DECRYPTING = "ias.login.decrypting";

    /**
     * Encrypting tokens.
     */
    @NotNull
    public static final String ENCRYPTING = "ias.login.encrypting";

    /**
     * Validating the session.
     */
    @NotNull
    public static final String VALIDATING = "ias.login.yggdrasil.validate";

    /**
     * Authenticating with the server.
     */
    @NotNull
    public static final String AUTHENTICATING = "ias.login.yggdrasil.auth";

    /**
     * Adding a character. (one of many)
     */
    @NotNull
    public static final String CHARACTER = "ias.login.yggdrasil.character";

    /**
     * Finalizing login.
     */
    @NotNull
    public static final String FINALIZING = "ias.login.finalizing";

    /**
     * Logger for this class.
     */
    @NotNull
    public static final Logger LOGGER = LoggerFactory.getLogger("IAS/YggdrasilAccount");

    /**
     * Whether the account is insecurely stored.
     */
    private final boolean insecure;

    /**
     * API root of the authentication server.
     */
    @NotNull
    private final String server;

    /**
     * Human-readable name of the authentication server (e.g. "LittleSkin"), may be empty.
     */
    @NotNull
    private final String serverName;

    /**
     * Account UUID.
     */
    @NotNull
    private UUID uuid;

    /**
     * Account name.
     */
    @NotNull
    private String name;

    /**
     * Encrypted account data. (username, password, access token, client token)
     */
    private byte @NotNull [] data;

    /**
     * Creates a new external account.
     *
     * @param insecure   Whether the account is insecurely stored
     * @param server     API root of the authentication server
     * @param serverName Display name of the authentication server, empty if unknown
     * @param uuid       Account UUID
     * @param name       Account name
     * @param data       Encrypted account data
     */
    @Contract(pure = true)
    public YggdrasilAccount(boolean insecure, @NotNull String server, @NotNull String serverName, @NotNull UUID uuid, @NotNull String name, byte @NotNull [] data) {
        this.insecure = insecure;
        this.server = server;
        this.serverName = serverName;
        this.uuid = uuid;
        this.name = name;
        this.data = data.clone();
    }

    @Contract(pure = true)
    @Override
    @NotNull
    public String type() {
        return "ias:yggdrasil_v2";
    }

    @Contract(pure = true)
    @Override
    @NotNull
    public String typeTipKey() {
        return "ias.accounts.tip.type.yggdrasil";
    }

    @Contract(pure = true)
    @Override
    @NotNull
    public UUID uuid() {
        return this.uuid;
    }

    @Contract(pure = true)
    @Override
    @NotNull
    public String name() {
        return this.name;
    }

    /**
     * Gets the API root of the authentication server.
     *
     * @return Authentication server API root
     */
    @Contract(pure = true)
    @NotNull
    public String server() {
        return this.server;
    }

    /**
     * Gets the display name of the authentication server.
     *
     * @return Authentication server display name, empty if unknown
     */
    @Contract(pure = true)
    @NotNull
    public String serverName() {
        return this.serverName;
    }

    /**
     * Gets the name to show for the authentication server, falling back to its host.
     *
     * @return Authentication server source name
     */
    @Contract(pure = true)
    @NotNull
    public String sourceName() {
        // Use the located name, if any.
        if (!this.serverName.isBlank()) return this.serverName;

        // Fall back to the URL host.
        try {
            String host = URI.create(this.server).getHost();
            if (host != null && !host.isBlank()) return host;
        } catch (Throwable ignored) {
            // NO-OP
        }

        // Fall back to the URL itself.
        return this.server;
    }

    @Contract(value = "-> true", pure = true)
    @Override
    public boolean canLogin() {
        // External accounts can always be re-authenticated.
        return true;
    }

    @Contract(pure = true)
    @Override
    public boolean insecure() {
        return this.insecure;
    }

    @Contract(pure = true)
    @Override
    @NotNull
    public UUID skin() {
        return this.uuid;
    }

    @Override
    public void login(@NotNull LoginHandler handler, Runnable onComplete) {
        try {
            // Skip if cancelled.
            if (handler.cancelled()) return;

            // Log it and display progress.
            LOGGER.info("IAS: Logging (Yggdrasil) as {}/{} at {}", this.uuid, this.name, this.server);
            handler.stage(INITIALIZING);

            // Value holders.
            Holder<Crypt> crypt = new Holder<>();
            Holder<Boolean> recrypt = new Holder<>(false);
            Holder<String> username = new Holder<>();
            Holder<String> password = new Holder<>();
            Holder<String> client = new Holder<>();

            // Read the crypt.
            CompletableFuture<Crypt> future;
            byte[] crypted;
            try (ByteArrayInputStream byteIn = new ByteArrayInputStream(this.data);
                 DataInputStream in = new DataInputStream(byteIn)) {
                // Read and process the crypt.
                future = Crypt.readType(in, handler::password);

                // Crypted data.
                crypted = in.readAllBytes();
            }

            // Decrypt.
            future.thenApplyAsync(value -> {
                // Skip if cancelled.
                if (value == null || handler.cancelled()) return null;

                // Log it and display progress.
                LOGGER.info("IAS: Decrypting tokens...");
                handler.stage(DECRYPTING);

                // Decrypt.
                byte[] data = value.decrypt(crypted);

                // Migrate and set the crypt.
                Crypt migrate = value.migrate();
                if (migrate != null) {
                    crypt.set(migrate);
                    recrypt.set(true);
                } else {
                    crypt.set(value);
                }

                // Continue.
                return data;
            }, IAS.executor()).thenComposeAsync(value -> {
                // Skip if cancelled.
                if (value == null || handler.cancelled()) return CompletableFuture.completedFuture(null);

                // Read the decrypted data.
                final String access;
                try (ByteArrayInputStream byteIn = new ByteArrayInputStream(value);
                     DataInputStream in = new DataInputStream(byteIn)) {
                    // Read the credentials and tokens.
                    username.set(in.readUTF());
                    password.set(in.readUTF());
                    access = in.readUTF();
                    client.set(in.readUTF());

                    // Verify the buffer.
                    int available = in.available();
                    if (available != 0) {
                        throw new IOException("Leftover: " + available);
                    }
                } catch (Throwable t) {
                    throw new RuntimeException("Unable to read the tokens.", t);
                }

                // Validate the stored session.
                final YggdrasilServer server = new YggdrasilServer(this.server, this.serverName, false);
                LOGGER.info("IAS: Validating session...");
                handler.stage(VALIDATING);
                return YggdrasilAuth.validate(server, access, client.get()).thenComposeAsync(valid -> {
                    // Skip if cancelled.
                    if (handler.cancelled()) return CompletableFuture.completedFuture(null);

                    // Return the stored session if it's still valid.
                    if (valid) {
                        return CompletableFuture.completedFuture(new YggdrasilSession(access, client.get(), this.uuid, this.name));
                    }

                    // Re-authenticate with the credentials.
                    LOGGER.info("IAS: Session is (probably) expired. Re-authenticating...");
                    handler.stage(AUTHENTICATING);
                    recrypt.set(true);

                    // Resolve the password: the stored one, or ask the user.
                    CompletableFuture<String> credentials;
                    if (!password.get().isEmpty()) {
                        credentials = CompletableFuture.completedFuture(password.get());
                    } else {
                        LOGGER.info("IAS: No stored password, asking the user...");
                        credentials = handler.accountPassword(this.server, username.get()).thenApplyAsync(entered -> {
                            // Stop on cancel.
                            if (entered == null) {
                                throw new FriendlyException("Account password was not provided.", "ias.error.yggdrasil.password");
                            }

                            // Continue with the entered password. (not persisted)
                            return entered;
                        }, IAS.executor());
                    }

                    // Authenticate, selecting the character stored in this account.
                    return credentials.thenComposeAsync(pw -> YggdrasilAuth.authenticate(server, username.get(), pw, client.get(), this.uuid), IAS.executor())
                            .thenApplyAsync(result -> {
                                // Prefer the character reported by the server.
                                MCProfile selected = result.selectedProfile();
                                if (selected == null) {
                                    // Fall back to the character stored in this account.
                                    selected = result.availableProfiles().stream()
                                            .filter(profile -> profile.uuid().equals(this.uuid))
                                            .findFirst().orElse(null);
                                }

                                // The character is gone (deleted on the server?).
                                if (selected == null) {
                                    throw new FriendlyException("The character is no longer available.", "ias.error.yggdrasil.profile");
                                }

                                // Create and return.
                                return YggdrasilSession.of(result, selected);
                            }, IAS.executor());
                }, IAS.executor());
            }, IAS.executor()).thenAcceptAsync(session -> {
                // Skip if cancelled.
                if (session == null || handler.cancelled()) return;

                // Re-encrypt if required.
                boolean saveStorage = false;
                if (recrypt.get()) {
                    // Log it and display progress.
                    LOGGER.info("IAS: Encrypting tokens...");
                    handler.stage(ENCRYPTING);

                    // Write the credentials and tokens.
                    byte[] unencrypted;
                    try (ByteArrayOutputStream byteOut = new ByteArrayOutputStream();
                         DataOutputStream out = new DataOutputStream(byteOut)) {
                        // Write the credentials and tokens.
                        out.writeUTF(username.get());
                        out.writeUTF(password.get());
                        out.writeUTF(session.accessToken());
                        out.writeUTF(session.clientToken());

                        // Flush it.
                        unencrypted = byteOut.toByteArray();
                    } catch (Throwable t) {
                        throw new RuntimeException("Unable to write the tokens.", t);
                    }

                    // Encrypt the tokens.
                    try (ByteArrayOutputStream byteOut = new ByteArrayOutputStream(unencrypted.length + 32);
                         DataOutputStream out = new DataOutputStream(byteOut)) {
                        // Encrypt.
                        Crypt val = crypt.get();
                        byte[] encrypted = val.encrypt(unencrypted);

                        // Write data.
                        out.writeUTF(val.type());
                        out.write(encrypted);

                        // Flush it.
                        this.data = byteOut.toByteArray();
                        saveStorage = true;
                    } catch (Throwable t) {
                        throw new RuntimeException("Unable to encrypt the tokens.", t);
                    }
                }

                // Authentication successful, refresh the profile.
                UUID uuid = session.uuid();
                String name = session.name();
                if (!this.uuid.equals(uuid) || !this.name.equals(name)) {
                    this.uuid = uuid;
                    this.name = name;
                    saveStorage = true;
                }

                // Log it and display progress.
                LOGGER.info("IAS: Successful login as {}", session);
                handler.stage(FINALIZING);

                // Create and return the data.
                LoginData login = new LoginData(this.name, this.uuid, session.accessToken(), true, this.server);
                handler.success(login, saveStorage);

                // Run onComplete.
                if (onComplete != null) onComplete.run();
            }, IAS.executor()).exceptionallyAsync(t -> {
                // Probable case - no internet connection.
                if (IUtils.anyInCausalChain(t, err -> err instanceof UnresolvedAddressException || err instanceof UnknownHostException || err instanceof NoRouteToHostException || err instanceof HttpTimeoutException || err instanceof ConnectException)) {
                    // Handle error.
                    handler.error(new FriendlyException("Unable to connect to the Yggdrasil server.", t, "ias.error.connect"));

                    // Return null.
                    return null;
                }

                // Handle error.
                handler.error(new RuntimeException("Unable to login as Yggdrasil account", t));

                // Return null.
                return null;
            }, IAS.executor());
        } catch (Throwable t) {
            // Handle.
            handler.error(new RuntimeException("Unable to begin Yggdrasil auth.", t));
        }
    }

    @Contract(value = "null -> false", pure = true)
    @Override
    public boolean equals(@Nullable Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof YggdrasilAccount that)) return false;
        return Objects.equals(this.server, that.server) && Objects.equals(this.uuid, that.uuid) && Objects.equals(this.name, that.name);
    }

    @Contract(pure = true)
    @Override
    public int hashCode() {
        int hash = 1;
        hash = 31 * hash + Objects.hashCode(this.server);
        hash = 31 * hash + Objects.hashCode(this.uuid);
        hash = 31 * hash + Objects.hashCode(this.name);
        return hash;
    }

    @Contract(pure = true)
    @Override
    @NotNull
    public String toString() {
        return "YggdrasilAccount{" +
                "server='" + this.server + '\'' +
                ", uuid=" + this.uuid +
                ", name='" + this.name + '\'' +
                ", data='[DATA]'" +
                '}';
    }

    @Override
    public void write(@NotNull DataOutput out) throws IOException {
        // Write the insecure.
        out.writeBoolean(this.insecure);

        // Write the server.
        out.writeUTF(this.server);

        // Write the server name.
        out.writeUTF(this.serverName);

        // Write the UUID.
        out.writeLong(this.uuid.getMostSignificantBits());
        out.writeLong(this.uuid.getLeastSignificantBits());

        // Write the name.
        out.writeUTF(this.name);

        // Write the data.
        out.writeShort(this.data.length);
        out.write(this.data);
    }

    /**
     * Reads the account (version 1, without the server name) from the input.
     *
     * @param in Target input
     * @return Read account
     * @throws IOException On I/O error
     */
    @CheckReturnValue
    @NotNull
    public static YggdrasilAccount readV1(@NotNull DataInput in) throws IOException {
        // Read the insecure.
        boolean insecure = in.readBoolean();

        // Read the server.
        String server = in.readUTF();

        // Read the UUID.
        UUID uuid = new UUID(in.readLong(), in.readLong());

        // Read the name.
        String name = in.readUTF();

        // Read the data.
        int length = in.readUnsignedShort();
        byte[] data = new byte[length];
        in.readFully(data);

        // Create and return. (the server name is unknown, it'll fall back to the host)
        return new YggdrasilAccount(insecure, server, "", uuid, name, data);
    }

    /**
     * Reads the account (version 2) from the input.
     *
     * @param in Target input
     * @return Read account
     * @throws IOException On I/O error
     */
    @CheckReturnValue
    @NotNull
    public static YggdrasilAccount readV2(@NotNull DataInput in) throws IOException {
        // Read the insecure.
        boolean insecure = in.readBoolean();

        // Read the server.
        String server = in.readUTF();

        // Read the server name.
        String serverName = in.readUTF();

        // Read the UUID.
        UUID uuid = new UUID(in.readLong(), in.readLong());

        // Read the name.
        String name = in.readUTF();

        // Read the data.
        int length = in.readUnsignedShort();
        byte[] data = new byte[length];
        in.readFully(data);

        // Create and return.
        return new YggdrasilAccount(insecure, server, serverName, uuid, name, data);
    }
}