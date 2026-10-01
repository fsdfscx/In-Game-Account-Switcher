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
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.vidtu.ias.IAS;
import ru.vidtu.ias.account.YggdrasilAccount;
import ru.vidtu.ias.auth.handlers.CreateHandler;
import ru.vidtu.ias.auth.microsoft.fields.MCProfile;
import ru.vidtu.ias.crypt.Crypt;
import ru.vidtu.ias.utils.exceptions.FriendlyException;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Creation flow for external (Yggdrasil) accounts.
 * <p>
 * Locates the server, authenticates with the provided credentials and produces an
 * encrypted {@link YggdrasilAccount}, mirroring what {@code MSAuthClient} does for
 * Microsoft accounts.
 *
 * @author VidTu
 */
public final class YggdrasilCreate {
    /**
     * Locating the server.
     */
    @NotNull
    public static final String LOCATING = "ias.login.yggdrasil.locate";

    /**
     * Logger for this class.
     */
    @NotNull
    private static final Logger LOGGER = LoggerFactory.getLogger("IAS/YggdrasilCreate");

    /**
     * An instance of this class cannot be created.
     *
     * @throws AssertionError Always
     */
    @Contract(value = "-> fail", pure = true)
    private YggdrasilCreate() {
        throw new AssertionError("No instances.");
    }

    /**
     * Locates the server, authenticates and creates the account.
     *
     * @param url          Target server URL
     * @param username     Account username
     * @param password     Account password
     * @param savePassword Whether to persist the password (encrypted) for automatic re-login
     * @param crypt        Crypt to encrypt the account with
     * @param handler      Creation handler
     * @return Future that will complete on creation, with {@code null} on cancel, or exceptionally
     */
    @CheckReturnValue
    @NotNull
    public static CompletableFuture<Void> create(@NotNull String url, @NotNull String username, @NotNull String password, boolean savePassword, @NotNull Crypt crypt, @NotNull CreateHandler handler) {
        // Stop if cancelled.
        if (handler.cancelled()) return CompletableFuture.completedFuture(null);

        // Locate the server.
        LOGGER.info("IAS: Locating Yggdrasil server '{}'...", url);
        handler.stage(LOCATING);

        // Send the request.
        return YggdrasilAuth.locate(url).thenComposeAsync(server -> {
            // Stop if cancelled.
            if (handler.cancelled()) return CompletableFuture.<Void>completedFuture(null);

            // Authenticate.
            LOGGER.info("IAS: Authenticating with '{}'...", server);
            handler.stage(YggdrasilAccount.AUTHENTICATING, server.name());
            String clientToken = UUID.randomUUID().toString();
            return YggdrasilAuth.authenticate(server, username, password, clientToken, null).thenComposeAsync(result -> {
                // Stop if cancelled.
                if (handler.cancelled()) return CompletableFuture.<YggdrasilSession>completedFuture(null);

                // The account has no characters at all.
                if (result.empty()) {
                    throw new FriendlyException("The account has no characters.", "ias.error.yggdrasil.profile");
                }

                // The server already selected a character for us.
                if (result.selectedProfile() != null) {
                    return CompletableFuture.completedFuture(YggdrasilSession.of(result, result.selectedProfile()));
                }

                // Only one character - select it silently.
                if (result.availableProfiles().size() == 1) {
                    return select(server, username, password, clientToken, result.availableProfiles().get(0));
                }

                // Multiple characters - ask the user which one to use.
                LOGGER.info("IAS: The account has {} characters, asking the user...", result.availableProfiles().size());
                return handler.selectProfile(result.availableProfiles()).thenComposeAsync(chosen -> {
                    // Stop on cancel.
                    if (chosen == null || handler.cancelled()) {
                        throw new FriendlyException("Character was not selected.", "ias.error.yggdrasil.profile");
                    }

                    // Select and continue.
                    return select(server, username, password, clientToken, chosen);
                }, IAS.executor());
            }, IAS.executor()).thenAcceptAsync(session -> {
                // Stop if cancelled.
                if (session == null || handler.cancelled()) return;

                // Encrypt the account data. (the password is only stored if the user opted in)
                LOGGER.info("IAS: Encrypting tokens...");
                handler.stage(YggdrasilAccount.ENCRYPTING);
                byte[] data = encrypt(crypt, username, savePassword ? password : "", session.accessToken(), session.clientToken());

                // Create the account.
                LOGGER.info("IAS: Successfully added {}", session);
                handler.stage(YggdrasilAccount.FINALIZING);
                YggdrasilAccount account = new YggdrasilAccount(crypt.insecure(), server.apiRoot(), server.name(), session.uuid(), session.name(), data);
                handler.successAccount(account);
            }, IAS.executor());
        }, IAS.executor()).exceptionallyAsync(t -> {
            // Handle error.
            handler.error(new RuntimeException("Unable to create a Yggdrasil account.", t));

            // Return null.
            return null;
        }, IAS.executor());
    }

    /**
     * Re-authenticates with the chosen character selected.
     *
     * @param server      Target server
     * @param username    Account username
     * @param password    Account password
     * @param clientToken Client token
     * @param profile     Character to select
     * @return Future that will complete with the session bound to the character
     */
    @CheckReturnValue
    @NotNull
    private static CompletableFuture<YggdrasilSession> select(@NotNull YggdrasilServer server, @NotNull String username, @NotNull String password, @NotNull String clientToken, @NotNull MCProfile profile) {
        // Send the request.
        return YggdrasilAuth.authenticate(server, username, password, clientToken, profile.uuid()).thenApplyAsync(result -> {
            // Prefer the server-reported profile, fall back to the requested one.
            MCProfile selected = result.selectedProfile() != null ? result.selectedProfile() : profile;

            // Create and return.
            return YggdrasilSession.of(result, selected);
        }, IAS.executor());
    }

    /**
     * Encrypts the account data.
     *
     * @param crypt       Crypt to use
     * @param username    Account username
     * @param password    Account password, empty if the password should not be persisted
     * @param accessToken Session access token
     * @param clientToken Session client token
     * @return Encrypted account data (crypt type + encrypted payload)
     */
    @Contract(value = "_, _, _, _, _ -> new", pure = true)
    private static byte @NotNull [] encrypt(@NotNull Crypt crypt, @NotNull String username, @NotNull String password, @NotNull String accessToken, @NotNull String clientToken) {
        // Write the unencrypted data.
        byte[] unencrypted;
        try (ByteArrayOutputStream byteOut = new ByteArrayOutputStream();
             DataOutputStream out = new DataOutputStream(byteOut)) {
            // Write the credentials and tokens.
            out.writeUTF(username);
            out.writeUTF(password);
            out.writeUTF(accessToken);
            out.writeUTF(clientToken);

            // Flush it.
            unencrypted = byteOut.toByteArray();
        } catch (Throwable t) {
            throw new RuntimeException("Unable to write the tokens.", t);
        }

        // Encrypt the data.
        try (ByteArrayOutputStream byteOut = new ByteArrayOutputStream(unencrypted.length + 32);
             DataOutputStream out = new DataOutputStream(byteOut)) {
            // Encrypt.
            byte[] encrypted = crypt.encrypt(unencrypted);

            // Write data.
            out.writeUTF(crypt.type());
            out.write(encrypted);

            // Flush it.
            return byteOut.toByteArray();
        } catch (Throwable t) {
            throw new RuntimeException("Unable to encrypt the tokens.", t);
        }
    }
}