package dev.kitsune.client.screen;

import dev.kitsune.client.server.ServerRule;
import dev.kitsune.client.server.ServerRuleStore;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Create or edit a single {@link ServerRule}. Fields:
 * name, host pattern, action (DISABLE/WARN), mod IDs, feature IDs, note.
 */
public class ServerRuleEditScreen extends Screen {
    private final Screen parent;
    private final ServerRule existing; // null = creating new

    private EditBox nameBox;
    private EditBox hostBox;
    private EditBox modsBox;
    private EditBox featuresBox;
    private EditBox noteBox;
    private ServerRule.Action currentAction;

    public ServerRuleEditScreen(Screen parent, ServerRule existing) {
        super(Component.literal(existing != null ? "Edit Server Rule" : "New Server Rule"));
        this.parent = parent;
        this.existing = existing;
        this.currentAction = existing != null ? existing.action : ServerRule.Action.DISABLE;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int fieldW = 260;
        int labelX = cx - fieldW / 2 - 60;
        int fieldX = cx - fieldW / 2;
        int y = 40;

        // Name
        nameBox = new EditBox(this.font, fieldX, y, fieldW, 18, Component.literal("Rule Name"));
        nameBox.setMaxLength(64);
        if (existing != null && existing.name != null) nameBox.setValue(existing.name);
        nameBox.setHint(Component.literal("e.g. Hypixel").withStyle(s -> s.withColor(0xFF888888)));
        this.addRenderableWidget(nameBox);
        y += 28;

        // Host pattern
        hostBox = new EditBox(this.font, fieldX, y, fieldW, 18, Component.literal("Host Pattern"));
        hostBox.setMaxLength(128);
        if (existing != null && existing.hostPattern != null) hostBox.setValue(existing.hostPattern);
        hostBox.setHint(Component.literal("e.g. *.hypixel.net").withStyle(s -> s.withColor(0xFF888888)));
        this.addRenderableWidget(hostBox);
        y += 28;

        // Action toggle
        this.addRenderableWidget(Button.builder(
                Component.literal("Action: " + currentAction.name()),
                btn -> {
                    currentAction = currentAction == ServerRule.Action.DISABLE
                            ? ServerRule.Action.WARN : ServerRule.Action.DISABLE;
                    btn.setMessage(Component.literal("Action: " + currentAction.name()));
                }
        ).bounds(fieldX, y, fieldW, 20).build());
        y += 28;

        // Mod IDs (comma-separated)
        modsBox = new EditBox(this.font, fieldX, y, fieldW, 18, Component.literal("Mod IDs"));
        modsBox.setMaxLength(256);
        if (existing != null && !existing.modIds.isEmpty()) {
            modsBox.setValue(String.join(", ", existing.modIds));
        }
        modsBox.setHint(Component.literal("Comma-separated mod IDs").withStyle(s -> s.withColor(0xFF888888)));
        this.addRenderableWidget(modsBox);
        y += 28;

        // Feature IDs (comma-separated)
        featuresBox = new EditBox(this.font, fieldX, y, fieldW, 18, Component.literal("Feature IDs"));
        featuresBox.setMaxLength(256);
        if (existing != null && !existing.featureIds.isEmpty()) {
            featuresBox.setValue(String.join(", ", existing.featureIds));
        }
        featuresBox.setHint(Component.literal("Comma-separated feature IDs").withStyle(s -> s.withColor(0xFF888888)));
        this.addRenderableWidget(featuresBox);
        y += 28;

        // Note
        noteBox = new EditBox(this.font, fieldX, y, fieldW, 18, Component.literal("Note"));
        noteBox.setMaxLength(256);
        if (existing != null && existing.note != null) noteBox.setValue(existing.note);
        noteBox.setHint(Component.literal("Optional note").withStyle(s -> s.withColor(0xFF888888)));
        this.addRenderableWidget(noteBox);
        y += 34;

        // Save / Cancel
        this.addRenderableWidget(Button.builder(
                Component.literal("Save"),
                btn -> saveAndClose()
        ).bounds(cx - 105, y, 100, 20).build());

        this.addRenderableWidget(Button.builder(
                Component.literal("Cancel"),
                btn -> this.onClose()
        ).bounds(cx + 5, y, 100, 20).build());
    }

    private void saveAndClose() {
        String host = hostBox.getValue().trim();
        if (host.isEmpty()) return; // require at least a host pattern

        ServerRule rule = existing != null ? existing : new ServerRule();
        rule.name = nameBox.getValue().trim().isEmpty() ? null : nameBox.getValue().trim();
        rule.hostPattern = host;
        rule.action = currentAction;
        rule.note = noteBox.getValue().trim().isEmpty() ? null : noteBox.getValue().trim();

        // Parse comma-separated mod IDs
        rule.modIds.clear();
        for (String s : modsBox.getValue().split(",")) {
            s = s.trim();
            if (!s.isEmpty()) rule.modIds.add(s);
        }

        // Parse comma-separated feature IDs
        rule.featureIds.clear();
        for (String s : featuresBox.getValue().split(",")) {
            s = s.trim();
            if (!s.isEmpty()) rule.featureIds.add(s);
        }

        if (existing == null) {
            ServerRuleStore.add(rule);
        } else {
            ServerRuleStore.save(); // existing rule was mutated in-place
        }
        this.onClose();
    }

    @Override
    public void render(GuiGraphics gfx, int mouseX, int mouseY, float delta) {
        super.render(gfx, mouseX, mouseY, delta);
        gfx.drawCenteredString(this.font, this.title, this.width / 2, 14, FoxTheme.FOX_ORANGE);

        int cx = this.width / 2;
        int labelX = cx - 130 - 60;
        int y = 40;
        gfx.drawString(this.font, "Name:", labelX, y + 5, 0xFFCCCCCC, false);
        y += 28;
        gfx.drawString(this.font, "Host:", labelX, y + 5, 0xFFCCCCCC, false);
        y += 56; // skip action button row
        gfx.drawString(this.font, "Mods:", labelX, y + 5, 0xFFCCCCCC, false);
        y += 28;
        gfx.drawString(this.font, "Features:", labelX, y + 5, 0xFFCCCCCC, false);
        y += 28;
        gfx.drawString(this.font, "Note:", labelX, y + 5, 0xFFCCCCCC, false);
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) this.minecraft.setScreen(parent);
    }
}
