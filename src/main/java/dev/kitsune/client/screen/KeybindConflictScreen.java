package dev.kitsune.client.screen;

import dev.kitsune.client.module.Module;
import dev.kitsune.client.util.KeybindManager;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Map;

/**
 * Shows every GLFW key that has 2+ modules bound to it, and lets the user
 * resolve the conflict by unbinding all-but-one of the offenders with a click.
 * Accessed from the Fox Settings screen.
 */
public class KeybindConflictScreen extends Screen {

    private final Screen parent;

    public KeybindConflictScreen(Screen parent) {
        super(Component.literal("Keybind Conflicts"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        Map<Integer, List<String>> conflicts = KeybindManager.getConflicts();

        int y = 50;
        if (conflicts.isEmpty()) {
            // No buttons to add; rendered in render()
        } else {
            for (var e : conflicts.entrySet()) {
                int key = e.getKey();
                String keyName = KeybindManager.getKeyName(key);
                List<Module> mods = KeybindManager.getModulesForKey(key);

                // Header label is drawn in render(); here we place one "Keep X, unbind others"
                // button per colliding module.
                int bx = cx - 150;
                int row = 0;
                for (Module m : mods) {
                    this.addRenderableWidget(Button.builder(
                            Component.literal("[" + keyName + "] Keep \u00a7e" + m.name()
                                    + "\u00a7r, unbind " + (mods.size() - 1) + " other"
                                    + (mods.size() - 1 == 1 ? "" : "s")),
                            btn -> {
                                for (Module other : KeybindManager.getModulesForKey(key)) {
                                    if (other != m) other.setKeyBind(-1);
                                }
                                this.rebuildWidgets();
                            }
                    ).bounds(bx, y + row * 22, 300, 20).build());
                    row++;
                }
                y += (mods.size() + 1) * 22;
                if (y > this.height - 60) break;
            }
        }

        this.addRenderableWidget(Button.builder(
                Component.literal("Done"),
                btn -> this.onClose()
        ).bounds(cx - 75, this.height - 28, 150, 20).build());
    }

    @Override
    public void render(GuiGraphics gfx, int mouseX, int mouseY, float delta) {
        super.render(gfx, mouseX, mouseY, delta);
        gfx.drawCenteredString(this.font, this.title, this.width / 2, 14, FoxTheme.FOX_ORANGE);
        Map<Integer, List<String>> conflicts = KeybindManager.getConflicts();
        if (conflicts.isEmpty()) {
            gfx.drawCenteredString(this.font,
                    Component.literal("\u00a7aNo keybind conflicts detected."),
                    this.width / 2, this.height / 2 - 10, 0xFFFFFFFF);
        } else {
            gfx.drawCenteredString(this.font,
                    Component.literal("\u00a7c" + conflicts.size() + " conflict"
                            + (conflicts.size() == 1 ? "" : "s") + " found"),
                    this.width / 2, 30, 0xFFFFFFFF);
        }
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) this.minecraft.setScreen(parent);
    }
}
