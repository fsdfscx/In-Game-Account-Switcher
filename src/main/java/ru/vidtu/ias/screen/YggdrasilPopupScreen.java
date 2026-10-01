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

//? if >=26.2 {
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineLabel;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import org.joml.Matrix3x2fStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.vidtu.ias.account.Account;
import ru.vidtu.ias.account.MicrosoftAccount;
import ru.vidtu.ias.auth.handlers.CreateHandler;
import ru.vidtu.ias.auth.yggdrasil.YggdrasilCreate;
import ru.vidtu.ias.config.IASConfig;
import ru.vidtu.ias.crypt.Crypt;
import ru.vidtu.ias.crypt.PasswordCrypt;
import ru.vidtu.ias.platform.IStonecutter;
import ru.vidtu.ias.utils.exceptions.FriendlyException;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * External (Yggdrasil) add popup screen.
 *
 * @author VidTu
 */
final class YggdrasilPopupScreen extends Screen implements CreateHandler {
    /**
     * Logger for this class.
     */
    private static final Logger LOGGER = LoggerFactory.getLogger("IAS/YggdrasilPopupScreen");

    /**
     * Parent screen.
     */
    private final Screen parent;

    /**
     * Synchronization lock.
     */
    private final Object lock = new Object();

    /**
     * Account handler.
     */
    private final Consumer<Account> handler;

    /**
     * Crypt method, {@code null} to use a password.
     */
    private Crypt crypt;

    /**
     * Server URL box.
     */
    private PopupBox server;

    /**
     * Username box.
     */
    private PopupBox username;

    /**
     * Password box.
     */
    private PopupBox password;

    /**
     * Crypt password box. (only when using a password-based crypt)
     */
    private PopupBox cryptPassword;

    /**
     * Done button.
     */
    private PopupButton done;

    /**
     * Whether the account is already being added and the UI should be locked.
     */
    private boolean locked = false;

    /**
     * Current stage.
     */
    private Component stage = Component.translatable(YggdrasilCreate.LOCATING).withStyle(ChatFormatting.YELLOW);

    /**
     * Current stage label.
     */
    private MultiLineLabel label;

    /**
     * Non-NAN, if some sort of error is present.
     */
    private float error = Float.NaN;

    /**
     * Error note.
     */
    private MultiLineLabel errorNote;

    /**
     * Creates a new add screen.
     *
     * @param parent  Parent screen
     * @param handler Account handler
     * @param crypt   Crypt method, {@code null} to use a password
     */
    YggdrasilPopupScreen(Screen parent, Consumer<Account> handler, Crypt crypt) {
        super(Component.translatable("ias.yggdrasil"));
        this.parent = parent;
        this.handler = handler;
        this.crypt = crypt;
    }

    @Override
    public boolean cancelled() {
        // Bruh.
        assert this.minecraft != null;

        // Cancelled if no longer displayed.
        return this != this.currentScreen();
    }

    @Override
    protected void init() {
        // Bruh.
        assert this.minecraft != null;

        // Synchronize to prevent funny things.
        synchronized (this.lock) {
            // Unbake label.
            this.label = null;
        }

        // Init parent.
        if (this.parent != null) {
            this.parent.init(this.width, this.height);
        }

        // Layout.
        int cx = this.width / 2;
        int cy = this.height / 2;

        // Add server box.
        this.server = new PopupBox(this.font, cx - 125, cy - 66, 250, 20, this.server, Component.translatable("ias.yggdrasil.server"), this::submit, false);
        this.server.setHint(Component.translatable("ias.yggdrasil.server.hint").withStyle(ChatFormatting.DARK_GRAY));
        this.addRenderableWidget(this.server);

        // Add username box.
        this.username = new PopupBox(this.font, cx - 125, cy - 42, 250, 20, this.username, Component.translatable("ias.yggdrasil.username"), this::submit, false);
        this.addRenderableWidget(this.username);

        // Add password box.
        this.password = new PopupBox(this.font, cx - 125, cy - 18, 250, 20, this.password, Component.translatable("ias.yggdrasil.password"), this::submit, true);
        this.password.addFormatter((s, i) -> IASConfig.passwordEchoing ? FormattedCharSequence.forward("*".repeat(s.length()), Style.EMPTY) : FormattedCharSequence.EMPTY);
        this.addRenderableWidget(this.password);

        // Add crypt password box, if required.
        if (this.crypt == null) {
            this.cryptPassword = new PopupBox(this.font, cx - 125, cy + 6, 250, 20, this.cryptPassword, Component.translatable("ias.password"), this::submit, true);
            this.cryptPassword.addFormatter((s, i) -> IASConfig.passwordEchoing ? FormattedCharSequence.forward("*".repeat(s.length()), Style.EMPTY) : FormattedCharSequence.EMPTY);
            this.addRenderableWidget(this.cryptPassword);
        } else {
            this.cryptPassword = null;
        }

        // Add done button.
        this.done = new PopupButton(cx - 125, cy + 52, 122, 20, CommonComponents.GUI_DONE, btn -> this.submit(), Supplier::get);
        this.done.color(0.5F, 1.0F, 0.5F, true);
        this.addRenderableWidget(this.done);

        // Add cancel button.
        PopupButton cancel = new PopupButton(cx + 3, cy + 52, 122, 20, CommonComponents.GUI_CANCEL, btn -> this.onClose(), Supplier::get);
        cancel.color(1.0F, 1.0F, 1.0F, true);
        this.addRenderableWidget(cancel);

        // Update the done button.
        this.server.setResponder(value -> this.type(false));
        this.username.setResponder(value -> this.type(false));
        this.password.setResponder(value -> this.type(false));
        if (this.cryptPassword != null) {
            this.cryptPassword.setResponder(value -> this.type(false));
        }
        this.type(true);
    }

    /**
     * Updates the {@link #done} button.
     *
     * @param instant Whether the color change should be instant
     */
    private void type(boolean instant) {
        // Prevent NPE.
        if (this.done == null || this.server == null || this.username == null || this.password == null) return;

        // Gray out if locked.
        if (this.locked) {
            this.done.active = false;
            this.done.color(0.5F, 0.5F, 0.5F, instant);
            return;
        }

        // Require every box to be filled.
        boolean filled = !this.server.getValue().isBlank() && !this.username.getValue().isBlank() && !this.password.getValue().isBlank();
        if (this.cryptPassword != null && this.cryptPassword.getValue().isBlank()) {
            filled = false;
        }

        // Apply.
        this.done.active = filled;
        this.done.color(filled ? 0.5F : 1.0F, filled ? 1.0F : 0.5F, filled ? 0.5F : 0.5F, instant);
    }

    /**
     * Validates the input and starts the creation.
     */
    private void submit() {
        // Bruh.
        assert this.minecraft != null;

        // Prevent NPE and double submission.
        if (this.locked || this.server == null || this.username == null || this.password == null) return;

        // Validate.
        String url = this.server.getValue().strip();
        String user = this.username.getValue();
        String pass = this.password.getValue();
        if (url.isBlank() || user.isBlank() || pass.isBlank()) return;

        // Resolve the crypt.
        Crypt crypt = this.crypt;
        if (crypt == null) {
            // Prevent NPE.
            if (this.cryptPassword == null) return;

            // Require the crypt password.
            String cryptPassword = this.cryptPassword.getValue();
            if (cryptPassword.isBlank()) return;
            crypt = new PasswordCrypt(cryptPassword);
        }

        // Lock the UI.
        this.locked = true;
        this.type(false);

        // Start the creation.
        YggdrasilCreate.create(url, user, pass, crypt, this);
    }

    @Override
    public void onClose() {
        // Bruh.
        assert this.minecraft != null;

        // Close to parent.
        //$set_screen 'this.minecraft' 'this.parent'
        this.minecraft.gui.setScreen(this.parent);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        // Bruh.
        assert this.minecraft != null;
        Matrix3x2fStack pose = graphics.pose();

        // Render background and widgets.
        super.extractRenderState(graphics, mouseX, mouseY, delta);

        // Render the title.
        pose.pushMatrix();
        pose.scale(2.0F, 2.0F);
        graphics.centeredText(this.font, this.title, this.width / 4, this.height / 4 - 75 / 2, 0xFF_FF_FF_FF);
        pose.popMatrix();

        // Render box titles.
        int cx = this.width / 2;
        if (this.server != null) graphics.centeredText(this.font, this.server.getMessage(), cx, this.height / 2 - 76, 0xFF_FF_FF_FF);
        if (this.username != null) graphics.centeredText(this.font, this.username.getMessage(), cx, this.height / 2 - 52, 0xFF_FF_FF_FF);
        if (this.password != null) graphics.centeredText(this.font, this.password.getMessage(), cx, this.height / 2 - 28, 0xFF_FF_FF_FF);
        if (this.cryptPassword != null) graphics.centeredText(this.font, this.cryptPassword.getMessage(), cx, this.height / 2 - 4, 0xFF_FF_FF_FF);

        // Synchronize to prevent funny things.
        synchronized (this.lock) {
            // Label is unbaked.
            if (this.label == null) {
                // Get the component.
                Component component = Objects.requireNonNullElse(this.stage, Component.empty());

                // Bake the label.
                this.label = MultiLineLabel.create(this.font, component, 240);

                // Narrate.
                this.minecraft.getNarrator().saySystemQueued(component);
            }

            // Render the label.
            IStonecutter.renderMultilineLabelCentered(this.label, graphics, this.width / 2, this.height / 2 + 32);
        }

        // Render the error note, if errored.
        if (Float.isFinite(this.error)) {
            // Create it first.
            if (this.errorNote == null) {
                this.errorNote = MultiLineLabel.create(this.font, Component.translatable("ias.error.note").withStyle(ChatFormatting.AQUA), 245);
            }

            // Fade in.
            float opacityFloat;
            int opacityMask;
            if (this.error < 1.0F) {
                this.error = Math.min(this.error + delta * 0.1F, 1.0F);
                opacityFloat = (this.error * this.error * this.error * this.error);
                int opacity = Math.max(9, (int) (opacityFloat * 255.0F));
                opacityMask = opacity << 24;
            } else {
                opacityFloat = 1.0F;
                opacityMask = -16777216;
            }

            // Render BG.
            int w = this.errorNote.getWidth() / 4 + 2;
            int h = (this.errorNote.getLineCount() * 9) / 2 + 1;
            int sy = this.height / 2 + 87;
            graphics.fill(cx - w, sy, cx + w, sy + h, 0x101010 | opacityMask);
            graphics.fill(cx - w + 1, sy - 1, cx + w - 1, sy, 0x101010 | opacityMask);
            graphics.fill(cx - w + 1, sy + h, cx + w - 1, sy + h + 1, 0x101010 | opacityMask);

            // Render scaled.
            pose.pushMatrix();
            pose.scale(0.5F, 0.5F);
            var renderer = graphics.textRenderer();
            renderer.defaultParameters(renderer.defaultParameters().withOpacity(opacityFloat));
            this.errorNote.visitLines(net.minecraft.client.gui.TextAlignment.CENTER, this.width, this.height + 174, 9, renderer);
            pose.popMatrix();
        }
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        // Bruh.
        assert this.minecraft != null;

        // Render transparent background if parent exists.
        if (this.parent != null) {
            // Render gradient.
            this.parent.extractRenderStateWithTooltipAndSubtitles(graphics, 0, 0, delta);
            graphics.nextStratum();
            graphics.fill(0, 0, this.width, this.height, 0x80_00_00_00);
        } else {
            super.extractBackground(graphics, mouseX, mouseY, delta);
        }

        // Render "form".
        int centerX = this.width / 2;
        int centerY = this.height / 2;
        graphics.fill(centerX - 135, centerY - 85, centerX + 135, centerY + 80, 0xF8_20_20_30);
        graphics.fill(centerX - 134, centerY - 86, centerX + 134, centerY - 85, 0xF8_20_20_30);
        graphics.fill(centerX - 134, centerY + 80, centerX + 134, centerY + 81, 0xF8_20_20_30);
    }

    @Override
    public void stage(String stage, Object... args) {
        // Bruh.
        assert this.minecraft != null;

        // Skip if not current screen.
        if (this != this.currentScreen()) return;

        // Flush the stage.
        Component component = Component.translatable(stage, args).withStyle(ChatFormatting.YELLOW);
        synchronized (this.lock) {
            this.stage = component;
            this.label = null;
        }
    }

    @Override
    public void success(MicrosoftAccount account) {
        // Should never happen for external accounts.
        this.successAccount(account);
    }

    @Override
    public void successAccount(Account account) {
        // Bruh.
        assert this.minecraft != null;

        // Skip if not current screen.
        if (this != this.currentScreen()) return;

        // Schedule on main.
        this.minecraft.execute(() -> {
            // Skip if not current screen.
            if (this != this.currentScreen()) return;

            // Call the callback.
            this.handler.accept(account);
        });
    }

    @Override
    public void error(Throwable error) {
        // Bruh.
        assert this.minecraft != null;

        // Log it.
        LOGGER.error("IAS: Create error.", error);

        // Skip if not current screen.
        if (this != this.currentScreen()) return;

        // Unlock the UI.
        this.locked = false;

        // Flush the stage.
        FriendlyException probable = FriendlyException.friendlyInChain(error);
        String key = probable != null ? probable.key() : "ias.error";
        Component component = Component.translatable(key).withStyle(ChatFormatting.RED);
        synchronized (this.lock) {
            this.stage = component;
            this.label = null;
            this.error = 0.0F;
        }

        // Re-enable the form.
        this.minecraft.execute(() -> this.type(false));
    }

    @Override
    public String toString() {
        return "YggdrasilPopupScreen{" +
                "crypt=" + this.crypt +
                ", stage=" + this.stage +
                ", label=" + this.label +
                '}';
    }

    private Screen currentScreen() {
        return this.minecraft.gui.screen();
    }
}
//?} else {
/*// External login is only supported on Minecraft 26.2 and newer.
 *///?}