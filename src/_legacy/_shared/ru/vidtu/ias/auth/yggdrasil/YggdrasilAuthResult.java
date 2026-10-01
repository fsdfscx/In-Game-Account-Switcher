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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.vidtu.ias.auth.microsoft.fields.MCProfile;
import ru.vidtu.ias.utils.GSONUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Raw result of the Yggdrasil {@code authenticate}/{@code refresh} endpoints.
 * <p>
 * An account may own several characters ("profiles"). The server only fills
 * {@code selectedProfile} if one was requested (or if it has a default), otherwise
 * the caller has to pick one from {@code availableProfiles} and re-authenticate
 * with it selected.
 *
 * @param accessToken       Session access token
 * @param clientToken       Session client token
 * @param availableProfiles Characters available on this account
 * @param selectedProfile   Currently selected character, {@code null} if none was selected
 * @author VidTu
 * @see YggdrasilAuth#authenticate(YggdrasilServer, String, String, String, java.util.UUID)
 */
public record YggdrasilAuthResult(@NotNull String accessToken, @NotNull String clientToken,
                                  @NotNull List<MCProfile> availableProfiles, @Nullable MCProfile selectedProfile) {
    /**
     * Extracts the result from the JSON.
     *
     * @param json Target JSON
     * @return Extracted result
     * @throws JsonParseException If unable to extract
     */
    @Contract(value = "_ -> new", pure = true)
    @NotNull
    public static YggdrasilAuthResult fromJson(@NotNull JsonObject json) {
        try {
            // Extract the tokens.
            String accessToken = GSONUtils.getStringOrThrow(json, "accessToken");
            String clientToken = GSONUtils.getStringOrThrow(json, "clientToken");

            // Extract the available profiles. (some servers omit this)
            List<MCProfile> availableProfiles = new ArrayList<>(0);
            if (json.has("availableProfiles") && json.get("availableProfiles").isJsonArray()) {
                JsonArray array = json.getAsJsonArray("availableProfiles");
                for (JsonElement element : array) {
                    // Skip invalid ones.
                    if (!element.isJsonObject()) continue;

                    // Parse and add.
                    availableProfiles.add(MCProfile.fromJson(element.getAsJsonObject()));
                }
            }

            // Extract the selected profile, if any.
            MCProfile selectedProfile = null;
            if (json.has("selectedProfile") && json.get("selectedProfile").isJsonObject()) {
                selectedProfile = MCProfile.fromJson(json.getAsJsonObject("selectedProfile"));
            }

            // Create and return.
            return new YggdrasilAuthResult(accessToken, clientToken, List.copyOf(availableProfiles), selectedProfile);
        } catch (Throwable t) {
            // Rethrow.
            throw new JsonParseException("Unable to parse YggdrasilAuthResult: " + json, t);
        }
    }

    /**
     * Gets whether the caller has to pick a character and re-authenticate.
     *
     * @return Whether the character selection is required
     */
    @Contract(pure = true)
    public boolean requiresSelection() {
        return this.selectedProfile == null && this.availableProfiles.size() > 1;
    }

    /**
     * Gets whether the account has no characters at all.
     *
     * @return Whether the account is empty (no characters)
     */
    @Contract(pure = true)
    public boolean empty() {
        return this.selectedProfile == null && this.availableProfiles.isEmpty();
    }

    @Contract(pure = true)
    @Override
    @NotNull
    public String toString() {
        return "YggdrasilAuthResult{" +
                "accessToken=[TOKEN]" +
                ", clientToken=[TOKEN]" +
                ", availableProfiles=" + this.availableProfiles +
                ", selectedProfile=" + this.selectedProfile +
                '}';
    }
}