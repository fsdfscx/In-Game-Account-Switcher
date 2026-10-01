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

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import ru.vidtu.ias.auth.microsoft.fields.MCProfile;

import java.util.UUID;

/**
 * Yggdrasil session that is already bound to a concrete character (profile).
 * <p>
 * Built from a {@link YggdrasilAuthResult} after the character has been chosen,
 * see {@link YggdrasilCreate} and {@code YggdrasilAccount#login}.
 *
 * @param accessToken Session access token (used for {@code joinServer})
 * @param clientToken Session client token (refreshed/validated together with the access token)
 * @param uuid        Selected profile UUID
 * @param name        Selected profile name
 * @author VidTu
 */
public record YggdrasilSession(@NotNull String accessToken, @NotNull String clientToken, @NotNull UUID uuid, @NotNull String name) {
    /**
     * Creates a session bound to the specified profile.
     *
     * @param result  Raw authentication result
     * @param profile Profile to bind to
     * @return Created session
     */
    @Contract(value = "_, _ -> new", pure = true)
    @NotNull
    public static YggdrasilSession of(@NotNull YggdrasilAuthResult result, @NotNull MCProfile profile) {
        return new YggdrasilSession(result.accessToken(), result.clientToken(), profile.uuid(), profile.name());
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