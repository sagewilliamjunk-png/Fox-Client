package dev.kitsune.client.screen;

import dev.kitsune.client.server.ServerRule;
import dev.kitsune.client.server.ServerRuleStore;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * Scrollable list of all server rules with Add / Edit / Delete controls.
 * Opened from the Fox Menu's "Server Rules" button.
 */
public class ServerRulesScreen extends Screen {
    private final Screen parent;
    private int scrollOffset = 0;
    private static final int ROW_HEIGHT = 50;
    private static final int LIST_TOP = 44;

    public ServerRulesScreen(Screen parent) {
        super(Component.literal("Server Rules"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int listBottom = this.height - 36;
        int visibleRows = (listBottom - LIST_TOP) / ROW_HEIGHT;

        // Add Rule button (top right)
        this.addRenderableWidget(Button.builder(
                Component.literal("+ Add Rule"),
                btn -> this.minecraft.setScreen(new ServerRuleEditScreen(this, null))
        ).bounds(this.width - 110, 8, 100, 20).build());

        // Rule rows
        List<ServerRule> rules = ServerRuleStore.all();
        for (int i = 0; i < rules.size(); i++) {
            int idx = i;
            ServerRule rule = rules.get(i);
            int rowY = LIST_TOP + (i - scrollOffset) * ROW_HEIGHT;
            if (rowY < LIST_TOP - ROW_HEIGHT || rowY > listBottom) continue;

            // Edit button
            this.addRenderableWidget(Button.builder(
                    Component.literal("Edit"),
                    btn -> this.minecraft.setScreen(new ServerRuleEditScreen(this, rule))
            ).bounds(this.width - 110, rowY + 2, 45, 18).build());

            // Delete button
            this.addRenderableWidget(Button.builder(
                    Component.literal("Del"),
                    btn -> {
                        ServerRuleStore.remove(rule);
                        this.rebuildWidgets();
                    }
            ).bounds(this.width - 60, rowY + 2, 45, 18).build());
        }

        // Done button (bottom center)
        this.addRenderableWidget(Button.builder(
                Component.literal("Done"),
                btn -> this.onClose()
        ).bounds(cx - 75, this.height - 28, 150, 20).build());
    }

    @Override
    public void render(GuiGraphics gfx, int mouseX, int mouseY, float delta) {
        super.render(gfx, mouseX, mouseY, delta);
        gfx.drawCenteredString(this.font, this.title, this.width / 2, 14, FoxTheme.FOX_ORANGE);

        List<ServerRule> rules = ServerRuleStore.all();
        if (rules.isEmpty()) {
            gfx.drawCenteredString(this.font,
                    Component.literal("\u00a77No server rules defined. Click '+ Add Rule' to create one."),
                    this.width / 2, this.height / 2, 0xFF888888);
            return;
        }

        int listBottom = this.height - 36;
        for (int i = 0; i < rules.size(); i++) {
            int rowY = LIST_TOP + (i - scrollOffset) * ROW_HEIGHT;
            if (rowY < LIST_TOP - ROW_HEIGHT || rowY > listBottom) continue;

            ServerRule rule = rules.get(i);
            // Background stripe
            int bg = (i % 2 == 0) ? 0x20FFFFFF : 0x10FFFFFF;
            gfx.fill(8, rowY, this.width - 8, rowY + ROW_HEIGHT - 2, bg);

            // Rule info
            String name = rule.name != null ? rule.name : "(unnamed)";
            String action = rule.action != null ? rule.action.name() : "?";
            int actionColor = rule.action == ServerRule.Action.DISABLE ? 0xFFFF4444 : 0xFFFFAA00;

            gfx.drawString(this.font, "\u00a7f" + name + " \u00a78- " + rule.hostPattern,
                    12, rowY + 4, 0xFFFFFFFF, false);
            gfx.drawString(this.font, "\u00a77Action: ", 12, rowY + 16, 0xFF888888, false);
            gfx.drawString(this.font, action, 12 + this.font.width("Action: "), rowY + 16, actionColor, false);

            // Show affected mods/features
            StringBuilder details = new StringBuilder("\u00a78");
            if (!rule.modIds.isEmpty()) details.append("Mods: ").append(String.join(", ", rule.modIds));
            if (!rule.featureIds.isEmpty()) {
                if (!rule.modIds.isEmpty()) details.append(" | ");
                details.append("Features: ").append(String.join(", ", rule.featureIds));
            }
            if (rule.note != null && !rule.note.isEmpty()) {
                details.append(" | ").append(rule.note);
            }
            String detailStr = details.toString();
            if (this.font.width(detailStr) > this.width - 130) {
                detailStr = detailStr.substring(0, Math.min(detailStr.length(), 60)) + "...";
            }
            gfx.drawString(this.font, detailStr, 12, rowY + 28, 0xFF666666, false);
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double deltaH, double deltaV) {
        int maxScroll = Math.max(0, ServerRuleStore.all().size() - ((this.height - 80) / ROW_HEIGHT));
        scrollOffset = Math.max(0, Math.min(maxScroll, scrollOffset - (int) deltaV));
        this.rebuildWidgets();
        return true;
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) this.minecraft.setScreen(parent);
    }
}
