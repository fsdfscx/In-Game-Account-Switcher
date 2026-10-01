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

package ru.vidtu.ias.auth;

//? if >=26.2 {
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
//? if >=26.3 {
import com.mojang.authlib.minecraft.SessionService;
import com.mojang.authlib.services.ProfileResult;
//?} else {
/*import com.mojang.authlib.minecraft.MinecraftSessionService;
import com.mojang.authlib.yggdrasil.ProfileResult;*/
//?}
import ru.vidtu.ias.account.Account;
import ru.vidtu.ias.account.YggdrasilAccount;
import ru.vidtu.ias.auth.yggdrasil.YggdrasilSessionService;

import java.util.UUID;

/**
 * Resolves the profile (and, therefore, the skin) of an account, using that account's
 * <b>own</b> authentication source.
 * <p>
 * The game resolves skins via {@code Minecraft#services()}, which the mod replaces when the
 * current account changes. Using it for every list entry is wrong: an external account would
 * be queried on Mojang (and vice versa), so its skin would silently fall back to the default.
 *
 * @author VidTu
 */
public final class AccountProfiles {
    /**
     * Logger for this class.
     */
    @NotNull
    private static final Logger LOGGER = LoggerFactory.getLogger("IAS/AccountProfiles");

    /**
     * Vanilla (Mojang) session service captured before the first account switch.
     */
    //? if >=26.3 {
    @Nullable
    private static volatile SessionService vanilla;
    //?} else {
    /*@Nullable
    private static volatile MinecraftSessionService vanilla;*/
    //?}

    /**
     * An instance of this class cannot be created.
     *
     * @throws AssertionError Always
     */
    private AccountProfiles() {
        throw new AssertionError("No instances.");
    }

    /**
     * Remembers the session service, if it wasn't remembered yet.
     * <p>
     * Must be called <b>before</b> the services are replaced, so that the first call captures
     * the original (vanilla) one.
     *
     * @param service Current session service
     */
    //? if >=26.3 {
    public static void capture(@NotNull SessionService service) {
    //?} else {
    /*public static void capture(@NotNull MinecraftSessionService service) {*/
    //?}
        if (vanilla == null) {
            vanilla = service;
        }
    }

    /**
     * Fetches the profile from the captured vanilla session service.
     *
     * @param profileId Target profile UUID
     * @return Fetched profile, {@code null} if there is none, it wasn't captured, or on error
     */
    @Nullable
    public static ProfileResult vanilla(@NotNull UUID profileId) {
        //? if >=26.3 {
        SessionService service = vanilla;
        //?} else {
        /*MinecraftSessionService service = vanilla;*/
        //?}
        if (service == null) return null;

        // Fetch.
        try {
            return service.fetchProfile(profileId, false);
        } catch (Throwable t) {
            LOGGER.warn("IAS: Unable to fetch the profile of {} from the vanilla service.", profileId, t);
            return null;
        }
    }

    /**
     * Fetches the profile of the account from its own authentication source.
     *
     * @param account   Target account
     * @param profileId Target profile UUID
     * @return Fetched profile, {@code null} if there is none or on error
     */
    @Nullable
    public static ProfileResult profile(@NotNull Account account, @NotNull UUID profileId) {
        // External accounts live on their own authentication server.
        if (account instanceof YggdrasilAccount yggdrasil) {
            return YggdrasilSessionService.fetchProfile(yggdrasil.server(), profileId);
        }

        // Everything else is a Mojang account.
        return vanilla(profileId);
    }
}
//?} else {
/*// External login is only supported on Minecraft 26.2 and newer.
 *///?}