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
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3x2fStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.vidtu.ias.account.Account;
import ru.vidtu.ias.account.MicrosoftAccount;
import ru.vidtu.ias.auth.handlers.CreateHandler;
import ru.vidtu.ias.auth.microsoft.fields.MCProfile;
import ru.vidtu.ias.auth.yggdrasil.YggdrasilCreate;
import ru.vidtu.ias.config.IASConfig;
import ru.vidtu.ias.crypt.Crypt;
import ru.vidtu.ias.crypt.PasswordCrypt;
import ru.vidtu.ias.platform.IStonecutter;
import ru.vidtu.ias.utils.exceptions.FriendlyException;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * External (Yggdrasil) add popup screen.
 * <p>
 * The screen has two steps, mirroring {@code MicrosoftPopupScreen}:
 * first the Crypt is resolved (if it's password-based), then the server URL,
 * email/username and password are requested.
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
     * Crypt method, {@code null} until the password-based Crypt is set up.
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
     * Crypt password box. (only while resolving the Crypt)
     */
    private PopupBox cryptPassword;

    /**
     * Crypt password tip.
     */
    private MultiLineLabel cryptPasswordTip;

    /**
     * Done button.
     */
    private PopupButton done;

    /**
     * Save-password toggle.
     */
    private PopupButton savePasswordButton;

    /**
     * Whether to persist the account password for automatic re-login, {@code false} by default.
     */
    private boolean savePassword = false;

    /**
     * Character (profile) selection future, {@code null} unless the character is being chosen.
     */
    private CompletableFuture<MCProfile> profileFuture;

    /**
     * Characters (profiles) to choose from.
     */
    private List<MCProfile> profiles;

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
     * Detailed error text, shown below the panel.
     */
    private Component errorText;

    /**
     * Creates a new add screen.
     *
     * @param parent  Parent screen
     * @param handler Account handler
     * @param crypt   Crypt method, {@code null} to ask for a Crypt password first
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

        // Ask for the character, if required. (accounts with multiple characters)
        if (this.profileFuture != null) {
            this.server = null;
            this.username = null;
            this.password = null;
            this.done = null;
            this.savePasswordButton = null;
            this.cryptPassword = null;
            this.cryptPasswordTip = null;

            // Add a button per character.
            List<MCProfile> profiles = this.profiles != null ? this.profiles : List.of();
            int y = cy - (profiles.size() * 24) / 2;
            for (MCProfile profile : profiles) {
                PopupButton button = new PopupButton(cx - 100, y, 200, 20, Component.literal(profile.name()), btn -> this.pickProfile(profile), Supplier::get);
                button.color(0.5F, 0.8F, 1.0F, true);
                this.addRenderableWidget(button);
                y += 24;
            }

            // Add cancel button.
            this.addRenderableWidget(new PopupButton(cx - 75, y + 8, 150, 20, CommonComponents.GUI_CANCEL, btn -> this.pickProfile(null), Supplier::get));
            return;
        }

        // Ask for the Crypt password first, if not resolved yet.
        if (this.crypt == null) {
            this.server = null;
            this.username = null;
            this.password = null;
            this.done = null;
            this.savePasswordButton = null;

            // Add crypt password box.
            this.cryptPassword = new PopupBox(this.font, cx - 100, cy - 10 + 5, 178, 20, this.cryptPassword, Component.translatable("ias.password"), this::enterCryptPassword, true);
            this.cryptPassword.setHint(Component.translatable("ias.password.hint").withStyle(ChatFormatting.DARK_GRAY));
            this.cryptPassword.addFormatter((s, i) -> IASConfig.passwordEchoing ? FormattedCharSequence.forward("*".repeat(s.length()), Style.EMPTY) : FormattedCharSequence.EMPTY);
            this.cryptPassword.setMaxLength(32);
            this.addRenderableWidget(this.cryptPassword);

            // Add enter password button.
            Button enter = new PopupButton(cx - 100 + 180, cy - 10 + 5, 20, 20, Component.literal(">>"), btn -> this.enterCryptPassword(), Supplier::get);
            enter.active = !this.cryptPassword.getValue().isBlank();
            this.cryptPassword.setResponder(value -> enter.active = !value.isBlank());
            this.addRenderableWidget(enter);

            // Create tip.
            this.cryptPasswordTip = MultiLineLabel.create(this.font, Component.translatable("ias.password.tip"), 320);

            // Add cancel button.
            this.addRenderableWidget(new PopupButton(cx - 75, cy + 49 - 22, 150, 20, CommonComponents.GUI_CANCEL, btn -> this.onClose(), Supplier::get));
            return;
        }

        // Resolved, render the form.
        this.cryptPassword = null;
        this.cryptPasswordTip = null;

        // Add server box.
        this.server = new PopupBox(this.font, cx - 125, cy - 54, 250, 20, this.server, Component.translatable("ias.yggdrasil.server"), this::submit, false);
        this.server.setHint(Component.translatable("ias.yggdrasil.server.hint").withStyle(ChatFormatting.DARK_GRAY));
        this.server.setMaxLength(256);
        this.addRenderableWidget(this.server);

        // Add username box.
        this.username = new PopupBox(this.font, cx - 125, cy - 14, 250, 20, this.username, Component.translatable("ias.yggdrasil.username"), this::submit, false);
        this.username.setMaxLength(256);
        this.addRenderableWidget(this.username);

        // Add password box.
        this.password = new PopupBox(this.font, cx - 125, cy + 26, 250, 20, this.password, Component.translatable("ias.yggdrasil.password"), this::submit, true);
        this.password.setMaxLength(256);
        this.password.addFormatter((s, i) -> IASConfig.passwordEchoing ? FormattedCharSequence.forward("*".repeat(s.length()), Style.EMPTY) : FormattedCharSequence.EMPTY);
        this.addRenderableWidget(this.password);

        // Add save-password toggle.
        this.savePasswordButton = new PopupButton(cx - 125, cy + 50, 250, 20, Component.empty(), btn -> this.toggleSavePassword(), Supplier::get);
        this.savePasswordButton.setTooltip(Tooltip.create(Component.translatable("ias.yggdrasil.savePassword.tip")));
        this.savePasswordButton.setTooltipDelay(Duration.ofMillis(250L));
        this.addRenderableWidget(this.savePasswordButton);
        this.updateSavePasswordButton(true);

        // Add done button.
        this.done = new PopupButton(cx - 125, cy + 96, 122, 20, CommonComponents.GUI_DONE, btn -> this.submit(), Supplier::get);
        this.done.color(0.5F, 1.0F, 0.5F, true);
        this.addRenderableWidget(this.done);

        // Add cancel button.
        PopupButton cancel = new PopupButton(cx + 3, cy + 96, 122, 20, CommonComponents.GUI_CANCEL, btn -> this.onClose(), Supplier::get);
        cancel.color(1.0F, 1.0F, 1.0F, true);
        this.addRenderableWidget(cancel);

        // Update the done button.
        this.server.setResponder(value -> this.type(false));
        this.username.setResponder(value -> this.type(false));
        this.password.setResponder(value -> this.type(false));
        this.type(true);
    }

    /**
     * Resolves the password-based Crypt and re-opens the form.
     */
    private void enterCryptPassword() {
        // Bruh.
        assert this.minecraft != null;

        // Prevent NPE.
        if (this.crypt != null || this.cryptPassword == null) return;

        // Don't allow blank.
        String value = this.cryptPassword.getValue();
        if (value.isBlank()) return;

        // Set the crypt.
        this.crypt = new PasswordCrypt(value);
        this.cryptPassword = null;
        this.cryptPasswordTip = null;

        // Rebuild the UI.
        this.init(this.width, this.height);
    }

    /**
     * Asks the user which character to use, when the account owns several of them.
     *
     * @param profiles Characters available on the account
     * @return Future that will complete with the chosen character, with {@code null} on cancel
     */
    @Override
    public CompletableFuture<MCProfile> selectProfile(List<MCProfile> profiles) {
        // Bruh.
        assert this.minecraft != null;

        // Create the future.
        this.profileFuture = new CompletableFuture<>();
        this.profiles = profiles;

        // Show the selection title.
        this.stage = Component.translatable("ias.yggdrasil.profile").withStyle(ChatFormatting.YELLOW);
        synchronized (this.lock) {
            this.label = null;
        }

        // Redraw on main.
        this.minecraft.execute(() -> this.init(this.width, this.height));

        // Return it.
        return this.profileFuture;
    }

    /**
     * Completes the character selection.
     *
     * @param profile Chosen character, {@code null} on cancel
     */
    private void pickProfile(@Nullable MCProfile profile) {
        // Bruh.
        assert this.minecraft != null;

        // Complete the future.
        CompletableFuture<MCProfile> future = this.profileFuture;
        this.profileFuture = null;
        this.profiles = null;
        if (future != null) {
            future.complete(profile);
        }

        // Redraw.
        this.init(this.width, this.height);
    }

    /**
     * Toggles whether to persist the account password.
     */
    private void toggleSavePassword() {
        // Flip.
        this.savePassword = !this.savePassword;

        // Update.
        this.updateSavePasswordButton(false);
    }

    /**
     * Updates the {@link #savePasswordButton} text and color.
     *
     * @param instant Whether the color change should be instant
     */
    private void updateSavePasswordButton(boolean instant) {
        // Prevent NPE.
        if (this.savePasswordButton == null) return;

        // Update the text.
        this.savePasswordButton.setMessage(Component.translatable("ias.yggdrasil.savePassword",
                Component.translatable(this.savePassword ? "ias.yggdrasil.savePassword.on" : "ias.yggdrasil.savePassword.off")));

        // Update the color.
        if (this.savePassword) {
            this.savePasswordButton.color(0.5F, 1.0F, 0.5F, instant);
        } else {
            this.savePasswordButton.color(1.0F, 0.5F, 0.5F, instant);
        }
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
        if (this.locked || this.crypt == null || this.server == null || this.username == null || this.password == null) return;

        // Validate.
        String url = this.server.getValue().strip();
        String user = this.username.getValue();
        String pass = this.password.getValue();
        if (url.isBlank() || user.isBlank() || pass.isBlank()) return;

        // Lock the UI.
        this.locked = true;
        this.type(false);

        // Clear any previous error.
        this.error = Float.NaN;
        this.errorNote = null;

        // Start the creation.
        YggdrasilCreate.create(url, user, pass, this.savePassword, this.crypt, this);
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
        boolean cryptStep = this.crypt == null && this.cryptPassword != null;
        pose.pushMatrix();
        pose.scale(2.0F, 2.0F);
        graphics.centeredText(this.font, this.title, this.width / 4, this.height / 4 - (cryptStep ? 49 : 90) / 2, 0xFF_FF_FF_FF);
        pose.popMatrix();

        // Render the character selection step.
        if (this.profileFuture != null) {
            synchronized (this.lock) {
                // Bake the label, if needed.
                if (this.label == null) {
                    this.label = MultiLineLabel.create(this.font, Objects.requireNonNullElse(this.stage, Component.empty()), 250);
                }

                // Render it above the character buttons.
                IStonecutter.renderMultilineLabelCentered(this.label, graphics, this.width / 2, this.height / 2 - 50);
            }
            return;
        }

        // Render the Crypt password step.
        if (cryptStep) {
            graphics.centeredText(this.font, this.cryptPassword.getMessage(), this.width / 2, this.height / 2 - 10 - 5, 0xFF_FF_FF_FF);
            if (this.cryptPasswordTip != null) {
                pose.pushMatrix();
                pose.scale(0.5F, 0.5F);
                IStonecutter.renderMultilineLabelCentered(this.cryptPasswordTip, graphics, this.width, this.height + 40);
                pose.popMatrix();
            }
            return;
        }

        // Render box titles.
        int cx = this.width / 2;
        if (this.server != null) graphics.centeredText(this.font, this.server.getMessage(), cx, this.height / 2 - 64, 0xFF_FF_FF_FF);
        if (this.username != null) graphics.centeredText(this.font, this.username.getMessage(), cx, this.height / 2 - 24, 0xFF_FF_FF_FF);
        if (this.password != null) graphics.centeredText(this.font, this.password.getMessage(), cx, this.height / 2 + 16, 0xFF_FF_FF_FF);

        // Render the stage label.
        synchronized (this.lock) {
            // Label is unbaked.
            if (this.label == null) {
                // Get the component.
                Component component = Objects.requireNonNullElse(this.stage, Component.empty());

                // Bake the label.
                this.label = MultiLineLabel.create(this.font, component, 250);

                // Narrate.
                this.minecraft.getNarrator().saySystemQueued(component);
            }

            // Render the label.
            IStonecutter.renderMultilineLabelCentered(this.label, graphics, this.width / 2, this.height / 2 + 74);
        }

        // Render the error note, if errored.
        if (Float.isFinite(this.error)) {
            // Create it first.
            if (this.errorNote == null) {
                this.errorNote = MultiLineLabel.create(this.font, Objects.requireNonNullElse(this.errorText, Component.empty()).copy().withStyle(ChatFormatting.AQUA), 250);
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
            int sy = this.height / 2 + 134;
            graphics.fill(cx - w, sy, cx + w, sy + h, 0x101010 | opacityMask);
            graphics.fill(cx - w + 1, sy - 1, cx + w - 1, sy, 0x101010 | opacityMask);
            graphics.fill(cx - w + 1, sy + h, cx + w - 1, sy + h + 1, 0x101010 | opacityMask);

            // Render scaled.
            pose.pushMatrix();
            pose.scale(0.5F, 0.5F);
            var renderer = graphics.textRenderer();
            renderer.defaultParameters(renderer.defaultParameters().withOpacity(opacityFloat));
            this.errorNote.visitLines(net.minecraft.client.gui.TextAlignment.CENTER, this.width, this.height + 268, 9, renderer);
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
        int panelTop = this.crypt == null ? 50 : 95;
        int panelBottom = this.crypt == null ? 50 : 127;
        graphics.fill(centerX - 135, centerY - panelTop, centerX + 135, centerY + panelBottom, 0xF8_20_20_30);
        graphics.fill(centerX - 134, centerY - panelTop - 1, centerX + 134, centerY - panelTop, 0xF8_20_20_30);
        graphics.fill(centerX - 134, centerY + panelBottom, centerX + 134, centerY + panelBottom + 1, 0xF8_20_20_30);
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

        // Show a short stage, with the detailed reason below the panel.
        FriendlyException probable = FriendlyException.friendlyInChain(error);
        String key = probable != null ? probable.key() : "ias.error";
        synchronized (this.lock) {
            this.stage = Component.translatable("ias.error.short").withStyle(ChatFormatting.RED);
            this.errorText = Component.translatable(key);
            this.label = null;
            this.errorNote = null;
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