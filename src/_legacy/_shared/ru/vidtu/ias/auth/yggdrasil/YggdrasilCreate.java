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
import ru.vidtu.ias.account.Account;
import ru.vidtu.ias.account.YggdrasilAccount;
import ru.vidtu.ias.auth.handlers.CreateHandler;
import ru.vidtu.ias.auth.microsoft.fields.MCProfile;
import ru.vidtu.ias.crypt.Crypt;
import ru.vidtu.ias.utils.exceptions.FriendlyException;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

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
     * Delay between two per-character authentication requests.
     * <p>
     * Each character is bound with its own {@code authserver/authenticate} request, and some
     * servers (e.g. Blessing Skin installations) rate-limit that endpoint to one request per
     * second, replying with a 403 "too many requests" error otherwise.
     */
    private static final long THROTTLE_MILLIS = 1200L;

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

            // Authenticate. (this also gives us the characters of the account)
            LOGGER.info("IAS: Authenticating with '{}'...", server);
            handler.stage(YggdrasilAccount.AUTHENTICATING, server.name());
            return YggdrasilAuth.authenticate(server, username, password, UUID.randomUUID().toString(), null)
                    .thenComposeAsync(result -> {
                        // Stop if cancelled.
                        if (handler.cancelled()) return CompletableFuture.<List<Account>>completedFuture(null);

                        // Authenticate every character. (the session is bound to a character)
                        handler.stage(YggdrasilAccount.ENCRYPTING);
                        return create(handler, server, username, password, savePassword, crypt, result);
                    }, IAS.executor())
                    .thenAcceptAsync(accounts -> {
                        // Stop if cancelled.
                        if (accounts == null || handler.cancelled()) return;

                        // Hand the accounts over.
                        LOGGER.info("IAS: Successfully added {} character(s) of '{}'.", accounts.size(), server.name());
                        handler.progress(accounts.size(), accounts.size());
                        handler.stage(YggdrasilAccount.FINALIZING);
                        handler.successAccounts(accounts);
                    }, IAS.executor());
        }, IAS.executor()).exceptionallyAsync(t -> {
            // Handle error.
            handler.error(new RuntimeException("Unable to create a Yggdrasil account.", t));

            // Return null.
            return null;
        }, IAS.executor());
    }

    /**
     * Authenticates every character of the account and builds an account for each of them.
     *
     * @param handler     Creation handler
     * @param server      Target server
     * @param username    Account username
     * @param password    Account password
     * @param savePassword Whether to persist the password
     * @param crypt       Crypt to encrypt the accounts with
     * @param result      Result of the initial authentication
     * @return Future that will complete with the created accounts
     */
    @CheckReturnValue
    @NotNull
    private static CompletableFuture<List<Account>> create(@NotNull CreateHandler handler, @NotNull YggdrasilServer server, @NotNull String username, @NotNull String password, boolean savePassword, @NotNull Crypt crypt, @NotNull YggdrasilAuthResult result) {
        // Determine the characters to add. (every character of the account)
        List<MCProfile> profiles = new ArrayList<>(result.availableProfiles());
        MCProfile selected = result.selectedProfile();
        if (selected != null && profiles.stream().noneMatch(profile -> profile.uuid().equals(selected.uuid()))) {
            profiles.add(selected);
        }
        if (profiles.isEmpty()) {
            throw new FriendlyException("The account has no characters.", "ias.error.yggdrasil.profile");
        }

        // Log the characters, so that a mismatch can be spotted in the logs.
        for (MCProfile profile : profiles) {
            LOGGER.info("IAS: Character '{}' ({}) of '{}'.", profile.name(), profile.uuid(), server.name());
        }

        // Authenticate each character, one by one. The requests are throttled, because some
        // servers rate-limit the authenticate endpoint to one request per second.
        int count = profiles.size();
        CompletableFuture<List<Account>> chain = CompletableFuture.completedFuture(new ArrayList<>(count));
        for (int i = 0; i < count; i++) {
            final MCProfile profile = profiles.get(i);
            final int position = i + 1;
            chain = chain.thenComposeAsync(accounts -> {
                // Show which character is currently being bound, before the throttling wait below.
                handler.progress(position - 1, count);
                handler.stage(YggdrasilAccount.CHARACTER, profile.name(), position, count);
                return delay().thenComposeAsync(
                        ignored -> authenticate(server, username, password, savePassword, crypt, profile),
                        IAS.executor()
                ).thenApplyAsync(account -> {
                    accounts.add(account);
                    return accounts;
                }, IAS.executor());
            }, IAS.executor());
        }
        return chain;
    }

    /**
     * Completes after the per-character authentication throttle interval.
     *
     * @return Future that will complete after the throttle
     */
    @CheckReturnValue
    @NotNull
    private static CompletableFuture<Void> delay() {
        return CompletableFuture.runAsync(() -> {
            // NO-OP
        }, CompletableFuture.delayedExecutor(THROTTLE_MILLIS, TimeUnit.MILLISECONDS, IAS.executor()));
    }

    /**
     * Authenticates the account with the character selected and builds the account for it.
     *
     * @param server       Target server
     * @param username     Account username
     * @param password     Account password
     * @param savePassword Whether to persist the password
     * @param crypt        Crypt to encrypt the account with
     * @param profile      Character to bind to
     * @return Future that will complete with the created account
     */
    @CheckReturnValue
    @NotNull
    private static CompletableFuture<YggdrasilAccount> authenticate(@NotNull YggdrasilServer server, @NotNull String username, @NotNull String password, boolean savePassword, @NotNull Crypt crypt, @NotNull MCProfile profile) {
        // Use a distinct client token per character, so that the server keeps a separate session for
        // each of them. Reusing a single client token makes servers that store one session per
        // (account, client token) - e.g. Blessing Skin - overwrite the bound character on every
        // request, which then makes joining any but the last character fail on the server with a
        // token/character mismatch.
        String clientToken = UUID.randomUUID().toString();

        // Send the request.
        return YggdrasilAuth.authenticate(server, username, password, clientToken, profile.uuid()).thenApplyAsync(result -> {
            // Prefer the character reported by the server, fall back to the requested one.
            MCProfile selected = result.selectedProfile() != null ? result.selectedProfile() : profile;

            // Encrypt the account data. (the password is only stored if the user opted in)
            byte[] data = encrypt(crypt, username, savePassword ? password : "", result.accessToken(), result.clientToken());

            // Create and return.
            return new YggdrasilAccount(crypt.insecure(), server.apiRoot(), server.name(), selected.uuid(), selected.name(), data);
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