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

package ru.vidtu.ias.auth.handlers;

import com.google.errorprone.annotations.CheckReturnValue;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.vidtu.ias.account.Account;
import ru.vidtu.ias.account.MicrosoftAccount;
import ru.vidtu.ias.auth.microsoft.fields.MCProfile;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Handler for creating accounts.
 *
 * @author VidTu
 * @apiNote All methods in this class can be called from another thread
 */
public interface CreateHandler {
    /**
     * Gets the cancelled state.
     *
     * @return Whether the authentication is cancelled
     */
    boolean cancelled();

    /**
     * Changes the authentication stage.
     *
     * @param stage New auth stage translation key
     * @param args  New auth stage translation args
     */
    void stage(@NotNull String stage, @Nullable Object @NotNull ... args);

    /**
     * Called when an authentication has performed successfully.
     *
     * @param account Created account
     */
    void success(@NotNull MicrosoftAccount account);

    /**
     * Called when an authentication of any account type has performed successfully.
     * <p>
     * This exists as a separate method (instead of overloading {@link #success(MicrosoftAccount)})
     * to keep the older, Microsoft-only implementations source-compatible without changes.
     *
     * @param account Created account
     */
    default void successAccount(@NotNull Account account) {
        // Delegate to the Microsoft-only implementation for backward compatibility.
        if (account instanceof MicrosoftAccount microsoft) {
            this.success(microsoft);
            return;
        }

        // Unknown.
        throw new UnsupportedOperationException("Unable to handle created account: " + account);
    }

    /**
     * Requests the character (profile) to use, when the account owns several of them.
     *
     * @param profiles Characters available on the account
     * @return Future that will complete with the chosen character, with {@code null} on cancel, exceptionally on error
     * @implNote Completes with {@code null} (cancel) by default, to keep the older handlers source-compatible
     */
    @CheckReturnValue
    @NotNull
    default CompletableFuture<MCProfile> selectProfile(@NotNull List<MCProfile> profiles) {
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Called when an authentication has failed.
     *
     * @param error Failure reason
     */
    void error(@NotNull Throwable error);
}
