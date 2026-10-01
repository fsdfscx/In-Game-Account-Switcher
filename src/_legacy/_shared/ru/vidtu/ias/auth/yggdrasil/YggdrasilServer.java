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

/**
 * Located external authentication (Yggdrasil / authlib-injector) server.
 *
 * @param apiRoot       API root URL, always ending with a {@code /}
 * @param name          Human-readable server name (e.g. from {@code meta.serverName})
 * @param nonEmailLogin Whether the server allows non-email logins ({@code meta.feature.non_email_login})
 * @author VidTu
 * @see YggdrasilAuth#locate(String)
 */
public record YggdrasilServer(@NotNull String apiRoot, @NotNull String name, boolean nonEmailLogin) {
    /**
     * Creates a new located server, normalizing the API root to end with a {@code /}.
     *
     * @param apiRoot       API root URL
     * @param name          Server name, empty to use the host
     * @param nonEmailLogin Whether the server allows non-email logins
     */
    @Contract(pure = true)
    public YggdrasilServer(@NotNull String apiRoot, @NotNull String name, boolean nonEmailLogin) {
        // Normalize the API root.
        apiRoot = apiRoot.strip();
        if (!apiRoot.endsWith("/")) {
            apiRoot = apiRoot + "/";
        }

        // Flush the fields.
        this.apiRoot = apiRoot;
        this.name = name.isBlank() ? apiRoot : name;
        this.nonEmailLogin = nonEmailLogin;
    }

    /**
     * Builds an absolute endpoint URL for this server.
     *
     * @param path Endpoint path, relative to the API root (e.g. {@code authserver/authenticate})
     * @return Absolute endpoint URL
     */
    @Contract(pure = true)
    @NotNull
    public String endpoint(@NotNull String path) {
        // Trim the leading slash to avoid the double slash in the URL.
        // (authlib-injector servers are generally fine with it, but some proxies aren't)
        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        return this.apiRoot + path;
    }

    @Contract(pure = true)
    @Override
    @NotNull
    public String toString() {
        return "YggdrasilServer{" +
                "apiRoot='" + this.apiRoot + '\'' +
                ", name='" + this.name + '\'' +
                ", nonEmailLogin=" + this.nonEmailLogin +
                '}';
    }
}