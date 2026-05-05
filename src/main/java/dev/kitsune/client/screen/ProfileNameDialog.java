package dev.kitsune.client.screen;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

/**
 * Simple text input dialog for profile naming/renaming.
 */
public class ProfileNameDialog extends Screen {
    private final Screen parent;
    private final String initialValue;
    private final Consumer<String> onConfirm;
    private EditBox nameBox;

    public ProfileNameDialog(Screen parent, String title, String initialValue, Consumer<String> onConfirm) {
        super(Component.literal(title));
        this.parent = parent;
        this.initialValue = initialValue;
        this.onConfirm = onConfirm;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int cy = this.height / 2;

        nameBox = new EditBox(this.font, cx - 100, cy - 10, 200, 20, Component.literal("Profile Name"));
        nameBox.setMaxLength(32);
        nameBox.setValue(initialValue);
        nameBox.setFocused(true);
        this.addRenderableWidget(nameBox);

        this.addRenderableWidget(Button.builder(
                Component.literal("Confirm"),
                btn -> {
                    onConfirm.accept(nameBox.getValue());
                }
        ).bounds(cx - 105, cy + 20, 100, 20).build());

        this.addRenderableWidget(Button.builder(
                Component.literal("Cancel"),
                btn -> this.onClose()
        ).bounds(cx + 5, cy + 20, 100, 20).build());
    }

    @Override
    public void render(GuiGraphics gfx, int mouseX, int mouseY, float delta) {
        super.render(gfx, mouseX, mouseY, delta);
        gfx.drawCenteredString(this.font, this.title, this.width / 2, this.height / 2 - 30, FoxTheme.FOX_ORANGE);
        gfx.drawCenteredString(this.font,
                Component.literal("\u00a77Lowercase letters, numbers, and underscores only."),
                this.width / 2, this.height / 2 + 46, 0xFF888888);
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) this.minecraft.setScreen(parent);
    }
}
