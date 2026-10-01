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

package ru.vidtu.ias.screen;

//? if >=26.3 {
import com.mojang.authlib.services.ProfileResult;
//?} else {
/*import com.mojang.authlib.yggdrasil.ProfileResult;*/
//?}
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.vidtu.ias.IAS;
import ru.vidtu.ias.account.Account;
import ru.vidtu.ias.account.MicrosoftAccount;
import ru.vidtu.ias.account.OfflineAccount;
import ru.vidtu.ias.account.YggdrasilAccount;
import ru.vidtu.ias.auth.LoginData;
//? if >=26.2 {
import ru.vidtu.ias.auth.AccountProfiles;
//?}
import ru.vidtu.ias.config.IASStorage;
import ru.vidtu.ias.platform.IStonecutter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

//? if >= 1.21.10 {
import net.minecraft.world.entity.player.PlayerSkin;
//?} else
/*import net.minecraft.client.resources.PlayerSkin;*/

/**
 * Account GUI list.
 *
 * @author VidTu
 */
final class AccountList extends ObjectSelectionList<AccountEntry> {
    /**
     * Skins cache.
     */
    private static final Map<UUID, PlayerSkin> SKINS = new WeakHashMap<>(4);

    /**
     * Logger for this class.
     */
    private static final Logger LOGGER = LoggerFactory.getLogger("IAS/AccountList");

    /**
     * Parent screen.
     */
    private final AccountScreen screen;

    /**
     * Creates a new accounts list widget.
     *
     * @param minecraft Minecraft instance
     * @param width     List width
     * @param height    List height
     * @param offset    List Y offset
     * @param item      Entry height
     */
    AccountList(AccountScreen screen, Minecraft minecraft, int width, int height, int offset, int item) {
        super(minecraft, width, height, offset, item);
        this.screen = screen;
        this.update(this.screen.search().getValue());
    }

    @Override
    public int getRowWidth() {
        return Math.min(super.getRowWidth(), this.screen.width - (85 + 10) * 2);
    }

    @Override
    public void setSelected(@Nullable AccountEntry entry) {
        // Select.
        super.setSelected(entry);

        // Notify parent.
        this.screen.updateSelected();
    }

    /**
     * Update the list by query.
     *
     * @param query Search query
     */
    void update(String query) {
        // Keep the selection, if possible.
        AccountEntry selected = this.getSelected();

        // Rebuild the grouped entries.
        this.replaceEntries(this.entries(query));
        this.setSelected(this.children().contains(selected) ? selected : null);

        // Notify the root.
        this.screen.updateSelected();
    }

    /**
     * Builds the grouped entries for the query.
     * <p>
     * The accounts are grouped by their source (Microsoft, offline, or a specific external
     * authentication server), and each group is preceded by a header entry.
     *
     * @param query Search query
     * @return Grouped entries
     */
    @NotNull
    private List<AccountEntry> entries(@Nullable String query) {
        // Collect the matching accounts, preserving the storage order.
        List<Account> matching = new ArrayList<>(IASStorage.ACCOUNTS);
        if (query != null && !query.isBlank()) {
            String lowerQuery = query.toLowerCase(Locale.ROOT);
            matching.removeIf(account -> !account.name().toLowerCase(Locale.ROOT).contains(lowerQuery));
            matching.sort((f, s) -> Boolean.compare(
                    s.name().toLowerCase(Locale.ROOT).startsWith(lowerQuery),
                    f.name().toLowerCase(Locale.ROOT).startsWith(lowerQuery)
            ));
        }

        // Group them by the source, preserving the first-appearance order. (LinkedHashMap)
        Map<String, List<Account>> groups = new LinkedHashMap<>();
        for (Account account : matching) {
            groups.computeIfAbsent(sourceKey(account), key -> new ArrayList<>(4)).add(account);
        }

        // Flatten into the entry list, with a header per group.
        List<AccountEntry> entries = new ArrayList<>(matching.size() + groups.size());
        for (List<Account> group : groups.values()) {
            entries.add(AccountEntry.header(this.minecraft, this, sourceTitle(group.get(0))));
            for (Account account : group) {
                entries.add(new AccountEntry(this.minecraft, this, account));
            }
        }
        return entries;
    }

    /**
     * Gets the grouping key (the source) of the account.
     *
     * @param account Target account
     * @return Grouping key
     */
    @NotNull
    private static String sourceKey(@NotNull Account account) {
        if (account instanceof YggdrasilAccount yggdrasil) return "yggdrasil:" + yggdrasil.server();
        if (account instanceof MicrosoftAccount) return "microsoft";
        return "offline";
    }

    /**
     * Gets the group title for the account.
     *
     * @param account Any account of the group
     * @return Group title
     */
    @NotNull
    private static Component sourceTitle(@NotNull Account account) {
        // External accounts are grouped by their authentication server.
        if (account instanceof YggdrasilAccount yggdrasil) {
            return Component.translatable("ias.accounts.group", yggdrasil.sourceName(),
                    Component.translatable("ias.accounts.tip.type.yggdrasil"));
        }

        // Microsoft and offline accounts are grouped by their type.
        return Component.translatable(account.typeTipKey());
    }

    /**
     * Gets the account of the selected entry.
     *
     * @return Selected account, {@code null} if nothing or a group header is selected
     */
    @Nullable
    private Account selectedAccount() {
        AccountEntry selected = this.getSelected();
        return selected != null ? selected.account() : null;
    }

    /**
     * Log in to this account.
     *
     * @param online Whether to try using online authentication
     * @apiNote The {@code online} parameter may be ignored if the current account doesn't support online authentication
     */
    void login(boolean online, Runnable onComplete) {
        // Skip if nothing is selected.
        Account account = this.selectedAccount();
        if (account == null) return;

        // Check if we should log in online.
        if (online && account.canLogin()) {
            // Initialize and set the login screen.
            LoginPopupScreen login = new LoginPopupScreen(this.screen);
            //$ set_screen 'this.minecraft' 'login'
            this.minecraft.gui.setScreen(login);

            // Start login.
            IAS.executor().execute(() -> {
                account.login(login, onComplete);
            });
            // Don't process further.
            return;
        }

        // Initialize and set the login screen.
        LoginPopupScreen login = new LoginPopupScreen(this.screen);
        //$ set_screen 'this.minecraft' 'login'
        this.minecraft.gui.setScreen(login);

        // Login offline.
        String name = account.name();
        LoginData data = new LoginData(name, OfflineAccount.uuid(name), "ias:offline", false);
        login.success(data, false);
        if (onComplete != null) onComplete.run();
    }

    void edit() {
        // Skip if nothing is selected.
        Account original = this.selectedAccount();
        if (original == null) return;
        int index = IASStorage.ACCOUNTS.indexOf(original);
        if (index < 0) return;

        // Replace in storage.
        final Screen add = new AddPopupScreen(this.screen, true, account -> {
            //$ set_screen 'this.minecraft' 'this.screen'
            this.minecraft.gui.setScreen(this.screen);

            // Add the account and save it.
            IASStorage.ACCOUNTS.removeIf(Predicate.isEqual(account));
            if (index >= IASStorage.ACCOUNTS.size()) {
                IASStorage.ACCOUNTS.add(account);
            } else {
                IASStorage.ACCOUNTS.set(index, account);
            }

            // Save storage.
            try {
                IAS.disclaimersStorage();
                IAS.saveStorage();
            } catch (Throwable t) {
                LOGGER.error("IAS: Unable to save storage.", t);
            }

            // Update the list.
            this.update(this.screen.search().getValue());
        });
        //$ set_screen 'this.minecraft' add
        this.minecraft.gui.setScreen(add);
    }

    /**
     * Deletes the selected account.
     * Does nothing if nothing is selected.
     *
     * @param confirm Whether to show the confirmation
     */
    void delete(boolean confirm) {
        // Skip if nothing is selected.
        Account account = this.selectedAccount();
        if (account == null) return;

        // Skip confirmation if shift is pressed.
        if (!confirm) {
            // Remove.
            IASStorage.ACCOUNTS.remove(account);

            // Save storage.
            try {
                IAS.disclaimersStorage();
                IAS.saveStorage();
            } catch (Throwable t) {
                LOGGER.error("IAS: Unable to save storage.", t);
            }

            // Update.
            this.update(this.screen.search().getValue());
            return;
        }

        // Display confirmation screen.
        final Screen delete = new DeletePopupScreen(this.screen, account, () -> {
            // Delete if confirmed.
            IASStorage.ACCOUNTS.removeIf(Predicate.isEqual(account));

            // Save storage.
            try {
                IAS.disclaimersStorage();
                IAS.saveStorage();
            } catch (Throwable t) {
                LOGGER.error("IAS: Unable to save storage.", t);
            }

            // Update.
            this.update(this.screen.search().getValue());
        });
        //$ set_screen 'this.minecraft' delete
        this.minecraft.gui.setScreen(delete);
    }

    /**
     * Opens the account adding screen.
     */
    void add() {
        final Screen add = new AddPopupScreen(this.screen, false, account -> {
            // Set to this.
            //$ set_screen 'this.minecraft' 'this.screen'
            this.minecraft.gui.setScreen(this.screen);

            // Add the account.
            IASStorage.ACCOUNTS.removeIf(Predicate.isEqual(account));
            IASStorage.ACCOUNTS.add(account);

            // Save storage.
            try {
                IAS.disclaimersStorage();
                IAS.saveStorage();
            } catch (Throwable t) {
                LOGGER.error("IAS: Unable to save storage.", t);
            }

            // Update the list.
            this.update(this.screen.search().getValue());
        });
        //$ set_screen 'this.minecraft' 'add'
        this.minecraft.gui.setScreen(add);
    }

    /**
     * Gets the skin for the account entry.
     *
     * @param entry Target account entry
     * @return Player skin, fetched or default
     */
    PlayerSkin skin(AccountEntry entry) {
        // Group headers have no skin.
        Account account = entry.account();
        if (account == null) return DefaultPlayerSkin.get(IStonecutter.NIL_UUID);

        // Get and return the skin if already stored.
        UUID uuid = account.skin();
        PlayerSkin skin = SKINS.get(uuid);
        if (skin != null) return skin;

        // Quickly put the replacer to avoid fetch spam.
        skin = DefaultPlayerSkin.get(uuid);
        SKINS.put(uuid, skin);

        // Offline accounts have name-derived UUIDs and no real skin to fetch.
        // Note: they must be detected by their type, not by the UUID version, because some
        // authentication servers also use name-based (version 3) UUIDs for real characters.
        if (account instanceof OfflineAccount) return skin;

        // Load the skin.
        CompletableFuture.supplyAsync(() -> {
            // Fetch the profile from the account's own authentication source: external accounts
            // must be queried on their own server, and the vanilla ones on the original session
            // service, because the currently active one may belong to a different server.
            //? if >=26.2 {
            ProfileResult own = AccountProfiles.profile(entry.account(), uuid);
            if (own != null) return own.profile();
            //?}
            //? if >=1.21.10 {
            ProfileResult result = this.minecraft.services().sessionService().fetchProfile(uuid, false);
            //?} else
            /*ProfileResult result = this.minecraft.getMinecraftSessionService().fetchProfile(uuid, false);*/

            // Skip if profile is null.
            if (result == null) return null;

            // Return the profile.
            return result.profile();
        }, IAS.executor()).thenComposeAsync(profile -> {
            // Skip if profile is null.
            if (profile == null) return CompletableFuture.completedFuture(null);

            // Load the skin.
            //? if >= 1.21.10 {
            return this.minecraft.getSkinManager().get(profile);
            //?} else
            /*return this.minecraft.getSkinManager().getOrLoad(profile);*/
        }, IAS.executor()).thenAcceptAsync(loaded -> {
            // Skip if the skin manager returned nothing. (e.g. a conflicting skin mod)
            if (loaded == null) return;

            // Put into map.
            loaded.ifPresent(newSkin -> SKINS.put(uuid, newSkin));
        }, this.minecraft).exceptionally(t -> {
            // Log it.
            LOGGER.warn("IAS: Unable to load skin: {}", entry, t);

            // Return null.
            return null;
        });

        // Return quick skin.
        return skin;
    }

    /**
     * Swaps the entry with the account above, if possible.
     *
     * @param entry Target entry
     */
    void swapUp(@Nullable AccountEntry entry) {
        if (entry != null) this.swap(entry, -1);
    }

    /**
     * Swaps the entry with the account below, if possible.
     *
     * @param entry Target entry
     */
    void swapDown(@Nullable AccountEntry entry) {
        if (entry != null) this.swap(entry, 1);
    }

    /**
     * Swaps the entry with the nearest account of the same source, if possible.
     *
     * @param entry     Target entry
     * @param direction Swap direction, {@code -1} for up and {@code 1} for down
     */
    private void swap(@NotNull AccountEntry entry, int direction) {
        // Group headers can't be moved.
        Account account = entry.account();
        if (account == null) return;

        // Find the storage index.
        int idx = IASStorage.ACCOUNTS.indexOf(account);
        if (idx < 0) return;

        // Find the nearest account of the same source in the requested direction.
        // (so that an account can't jump over another source's group)
        String key = sourceKey(account);
        int target = -1;
        for (int i = idx + direction; i >= 0 && i < IASStorage.ACCOUNTS.size(); i += direction) {
            if (sourceKey(IASStorage.ACCOUNTS.get(i)).equals(key)) {
                target = i;
                break;
            }
        }
        if (target < 0) return;

        // Move the storage.
        IASStorage.ACCOUNTS.set(idx, IASStorage.ACCOUNTS.get(target));
        IASStorage.ACCOUNTS.set(target, account);

        // Save storage.
        try {
            IAS.disclaimersStorage();
            IAS.saveStorage();
        } catch (Throwable t) {
            LOGGER.error("IAS: Unable to save storage.", t);
        }

        // Rebuild the list and keep the moved account selected.
        this.update(this.screen.search().getValue());
        for (AccountEntry child : this.children()) {
            if (Objects.equals(child.account(), account)) {
                this.setSelected(child);
                break;
            }
        }
    }

    /**
     * Gets the screen.
     *
     * @return Parent accounts screen
     */
    AccountScreen screen() {
        return this.screen;
    }

    @Override
    public String toString() {
        return "AccountList{" +
                "children=" + this.children() +
                '}';
    }
}
