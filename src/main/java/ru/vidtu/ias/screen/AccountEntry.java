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

import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
//? if >=26.1 {
import net.minecraft.client.gui.GuiGraphicsExtractor;
//?} else
/*import net.minecraft.client.gui.GuiGraphics;*/
import net.minecraft.client.gui.components.ObjectSelectionList;
//? if >=26.1 {
import net.minecraft.client.gui.components.PlayerFaceExtractor;
//?} else
/*import net.minecraft.client.gui.components.PlayerFaceRenderer;*/
import net.minecraft.client.gui.components.WidgetSprites;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.vidtu.ias.account.Account;
import ru.vidtu.ias.platform.IStonecutter;
import ru.vidtu.ias.config.IASConfig;

import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

//? if >= 1.21.10 {
import net.minecraft.world.entity.player.PlayerSkin;
//?} else
/*import net.minecraft.client.resources.PlayerSkin;*/

/**
 * Account GUI entry.
 *
 * @author VidTu
 */
final class AccountEntry extends ObjectSelectionList.Entry<AccountEntry> {
    /**
     * Up button sprites.
     */
    private static final WidgetSprites UP = new WidgetSprites(
            IStonecutter.identifier("up_plain"),
            IStonecutter.identifier("up_disabled"),
            IStonecutter.identifier("up_focus")
    );

    /**
     * Down button sprites.
     */
    private static final WidgetSprites DOWN = new WidgetSprites(
            IStonecutter.identifier("down_plain"),
            IStonecutter.identifier("down_disabled"),
            IStonecutter.identifier("down_focus")
    );

    /**
     * Warning sprites.
     */
    private static final WidgetSprites WARNING = new WidgetSprites(
            IStonecutter.identifier("warning_off"),
            IStonecutter.identifier("warning_on")
    );

    /**
     * Minecraft instance.
     */
    private final Minecraft minecraft;

    /**
     * Parent list.
     */
    private final AccountList list;

    /**
     * IAS account, {@code null} if this entry is a group header.
     */
    @Nullable
    private final Account account;

    /**
     * Group header title, {@code null} if this entry is an account.
     */
    @Nullable
    private final Component header;

    /**
     * Account tooltip.
     */
    private final List<FormattedCharSequence> tooltip;

    /**
     * Last click time.
     */
    private long clicked = IStonecutter.internalMillisClock();

    /**
     * Last non-hovered time.
     */
    private long lastFree = System.nanoTime();

    /**
     * Creates a new account list entry widget.
     *
     * @param minecraft Minecraft instance
     * @param list      Parent list
     * @param account   IAS account
     */
    AccountEntry(Minecraft minecraft, AccountList list, Account account) {
        this.minecraft = minecraft;
        this.list = list;
        this.account = account;
        this.header = null;
        this.tooltip = Stream.of(
                CommonComponents.optionNameValue(Component.translatable("ias.accounts.tip.nick"), Component.literal(account.name())),
                CommonComponents.optionNameValue(Component.translatable("ias.accounts.tip.uuid"), Component.literal(account.uuid().toString())),
                CommonComponents.optionNameValue(Component.translatable("ias.accounts.tip.type"), Component.translatable(account.typeTipKey()))
        ).map(Component::getVisualOrderText).toList();
    }

    /**
     * Creates a new group header entry.
     *
     * @param minecraft Minecraft instance
     * @param list      Parent list
     * @param title     Header title
     * @return Created header entry
     */
    @NotNull
    static AccountEntry header(Minecraft minecraft, AccountList list, Component title) {
        return new AccountEntry(minecraft, list, title);
    }

    /**
     * Creates a new group header entry.
     *
     * @param minecraft Minecraft instance
     * @param list      Parent list
     * @param header    Header title
     */
    private AccountEntry(Minecraft minecraft, AccountList list, Component header) {
        this.minecraft = minecraft;
        this.list = list;
        this.account = null;
        this.header = header;
        this.tooltip = List.of();
    }

    /**
     * Gets whether this entry is a group header.
     *
     * @return Whether this entry is a group header
     */
    boolean header() {
        return this.account == null;
    }

    @Override
    //? if >=26.1 {
    public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float delta) {
    //?} elif >=1.21.10 {
    /*public void renderContent(GuiGraphics graphics, int mouseX, int mouseY, boolean hovered, float delta) {
    *///?} else {
    /*public void render(GuiGraphics graphics, int index, int y, int x, int width, int height, int mouseX, int mouseY, boolean hovered, float delta) {*/
    //?}
        // Content bounds.
        //? if >=1.21.10 {
        int x = this.getContentX();
        int y = this.getContentY();
        int width = this.getContentWidth();
        int height = this.getContentHeight();
        //?}

        // Render the group header.
        if (this.account == null) {
            Component title = Objects.requireNonNullElse(this.header, Component.empty());
            //? if >=26.1 {
            graphics.text(this.minecraft.font, title, x + 2, y + 1, 0xFF_FF_D0_60);
            //?} else
            /*graphics.drawString(this.minecraft.font, title, x + 2, y + 1, 0xFF_FF_D0_60);*/
            return;
        }

        // Render tooltip.
        if (hovered) {
            if ((System.nanoTime() - this.lastFree) >= 500_000_000L) {
                graphics.setTooltipForNextFrame(this.tooltip, mouseX, mouseY);
            }
        } else {
            this.lastFree = System.nanoTime();
        }

        // Render the skin.
        PlayerSkin skin = this.list.skin(this);
        //? if >=26.1 {
        PlayerFaceExtractor.extractRenderState(graphics, skin, x, y, 8);
        //?} else
        /*PlayerFaceRenderer.draw(graphics, skin, x, y, 8);*/

        // Get the name color.
        User user = this.minecraft.getUser();
        int color;
        // Mods break user non-nullness.
        //noinspection ConstantValue
        if (user == null || !this.account.name().equalsIgnoreCase(user.getName())) {
            color = 0xFF_FF_FF_FF;
        } else if (this.account.uuid().equals(user.getProfileId())) {
            color = 0xFF_00_FF_00;
        } else if (this.account.name().equals(user.getName())) {
            color = 0xFF_FF_FF_00;
        } else {
            color = 0xFF_FF_80_00;
        }

        // Render name.
        //? if >=26.1 {
        graphics.text(this.minecraft.font, this.account.name(), x + 10, y, color);
        //?} else
        /*graphics.drawString(this.minecraft.font, this.account.name(), x + 10, y, color);*/

        // Render the account type on the right.
        Component subtitle = Component.translatable(this.account.typeTipKey());
        int subtitleX = x + width - 34 - this.minecraft.font.width(subtitle);
        //? if >=26.1 {
        graphics.text(this.minecraft.font, subtitle, subtitleX, y, 0xFF_90_90_90);
        //?} else
        /*graphics.drawString(this.minecraft.font, subtitle, subtitleX, y, 0xFF_90_90_90);*/

        // Render warning if insecure.
        if (this.account.insecure()) {
            boolean warning = (System.nanoTime() / 1_000_000_000L) % 2L == 0;
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, warning ? WARNING.enabled() : WARNING.enabledFocused(), x - 6, y - 1, 2, 10);
            if (mouseX >= x - 10 && mouseX <= x && mouseY >= y && mouseY <= y + height) {
                graphics.setTooltipForNextFrame(Component.translatable("ias.accounts.tip.insecure"), mouseX, mouseY);
            }
        }

        // Render only for focused, selected or hovered.
        if (this.equals(this.list.getFocused()) || this.equals(this.list.getSelected())) {
            // Render up widget.
            //? if >=1.21.11 {
            net.minecraft.resources.Identifier upTexture;
            //?} else
            /*net.minecraft.resources.ResourceLocation upTexture;*/
            int upX = x + width - 28;
            if (this == this.list.children().getFirst()) {
                upTexture = UP.disabled();
            } else if (mouseX >= upX && mouseY >= y && mouseX <= upX + 11 && mouseY <= y + height) {
                upTexture = UP.enabledFocused();
            } else {
                upTexture = UP.enabled();
            }
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, upTexture, upX, y, 11, 7);

            // Render down widget.
            //? if >=1.21.11 {
            net.minecraft.resources.Identifier downTexture;
            //?} else
            /*net.minecraft.resources.ResourceLocation downTexture;*/
            int downX = x + width - 15;
            if (this == this.list.children().getLast()) {
                downTexture = DOWN.disabled();
            } else if (mouseX >= downX && mouseY >= y && mouseX <= downX + 11 && mouseY <= y + height) {
                downTexture = DOWN.enabledFocused();
            } else {
                downTexture = DOWN.enabled();
            }
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, downTexture, downX, y, 11, 7);
        }
    }

    @Override
    //? if >=1.21.10 {
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
        double mouseX = event.x();
    //?} else
    /*public boolean mouseClicked(double mouseX, double mouseY, int button) {*/
        // Group headers have no actions beyond the selection.
        if (this.account == null) {
            return true;
        }

        // Swap if selected.
        if (this.equals(this.list.getFocused()) || this.equals(this.list.getSelected())) {
            int right = this.list.getRowRight();

            // Up widget.
            int upX = right - 28;
            if (mouseX >= upX && mouseX <= upX + 11) {
                this.list.swapUp(this);
                return true;
            }

            // Down widget.
            int downX = right - 15;
            if (mouseX >= downX && mouseX <= downX + 11) {
                this.list.swapDown(this);
                return true;
            }
        }

        // Login on double click.
        if (IStonecutter.internalMillisClock() - this.clicked < 250L) {
            //? if >=1.21.10 {
            this.list.login(!event.hasShiftDown(), IASConfig.closeOnLogin ? () -> {
                //$ set_screen minecraft 'this.list.screen().parent()'
                minecraft.gui.setScreen(this.list.screen().parent());
            } : null);
            //?} else
            /*this.list.login(!net.minecraft.client.gui.screens.Screen.hasShiftDown(), IASConfig.closeOnLogin ? () -> this.minecraft.setScreen(this.list.screen().parent()) : null);*/
        }

        // Set time for double click.
        this.clicked = IStonecutter.internalMillisClock();
        return true;
    }

    @Override
    @NotNull
    public Component getNarration() {
        if (this.account != null) return Component.literal(this.account.name());
        return Objects.requireNonNullElse(this.header, Component.empty());
    }

    /**
     * Gets the account.
     *
     * @return IAS account, {@code null} if this entry is a group header
     */
    @Nullable
    Account account() {
        return this.account;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof AccountEntry that)) return false;
        if (this.account != null) return Objects.equals(this.account, that.account);
        return Objects.equals(this.header, that.header);
    }

    @Override
    public int hashCode() {
        return this.account != null ? Objects.hashCode(this.account) : Objects.hashCode(this.header);
    }

    @Override
    public String toString() {
        return "AccountEntry{" +
                "account=" + this.account +
                '}';
    }
}
