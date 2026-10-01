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

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import ru.vidtu.ias.auth.microsoft.fields.MCProfile;
import ru.vidtu.ias.utils.GSONUtils;

import java.util.UUID;

/**
 * Yggdrasil authentication session, as returned by the
 * {@code authserver/authenticate} and {@code authserver/refresh} endpoints.
 *
 * @param accessToken Session access token (used for {@code joinServer})
 * @param clientToken Session client token (refreshed/validated together with the access token)
 * @param uuid        Selected profile UUID
 * @param name        Selected profile name
 * @author VidTu
 * @see YggdrasilAuth#authenticate(YggdrasilServer, String, String, String)
 * @see YggdrasilAuth#refresh(YggdrasilServer, String, String)
 */
public record YggdrasilSession(@NotNull String accessToken, @NotNull String clientToken, @NotNull UUID uuid, @NotNull String name) {
    /**
     * Extracts the session from the JSON.
     *
     * @param json Target JSON
     * @return Extracted session
     * @throws JsonParseException If unable to extract
     */
    @Contract(value = "_ -> new", pure = true)
    @NotNull
    public static YggdrasilSession fromJson(@NotNull JsonObject json) {
        try {
            // Extract the tokens.
            String accessToken = GSONUtils.getStringOrThrow(json, "accessToken");
            String clientToken = GSONUtils.getStringOrThrow(json, "clientToken");

            // Extract the selected profile. (some servers may not return it)
            MCProfile profile = MCProfile.fromJson(GSONUtils.getObjectOrThrow(json, "selectedProfile"));

            // Create and return.
            return new YggdrasilSession(accessToken, clientToken, profile.uuid(), profile.name());
        } catch (Throwable t) {
            // Rethrow.
            throw new JsonParseException("Unable to parse YggdrasilSession: " + json, t);
        }
    }

    @Contract(pure = true)
    @Override
    @NotNull
    public String toString() {
        return "YggdrasilSession{" +
                "accessToken=[TOKEN]" +
                ", clientToken=[TOKEN]" +
                ", uuid=" + this.uuid +
                ", name='" + this.name + '\'' +
                '}';
    }
}