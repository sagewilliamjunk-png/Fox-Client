package dev.kitsune.client.server;

import dev.kitsune.client.core.ModJarSwapper;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Modal screen explaining that joining {@code targetAddress} requires Fox Client
 * to disable a list of mods, and that doing so requires restarting Minecraft.
 *
 * Buttons:
 *   - "Restart with these disabled" → queues every move via {@link ModJarSwapper},
 *     persists the {@link PendingJoin}, then closes Minecraft.
 *   - "Cancel" → returns to the previous screen with no changes.
 *
 * The actual JAR moves happen on the NEXT launch in {@link dev.kitsune.client.PreLaunchBootstrap}
 * (mods are loaded into the JVM at startup; you can't unload them mid-session).
 */
public class RestartConfirmScreen extends Screen {

    private final Screen parent;
    private final String targetAddress;
    private final List<String> modsToDisable;

    public RestartConfirmScreen(Screen parent, String targetAddress, Set<String> modsToDisable) {
        super(Component.translatable("kitsune.restart.title"));
        this.parent = parent;
        this.targetAddress = targetAddress;
        this.modsToDisable = new ArrayList<>(modsToDisable);
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int by = this.height - 60;

        this.addRenderableWidget(Button.builder(
                Component.translatable("kitsune.restart.confirm"),
                btn -> confirmRestart()
        ).bounds(cx - 205, by, 200, 20).build());

        this.addRenderableWidget(Button.builder(
                Component.translatable("kitsune.restart.cancel"),
                btn -> this.onClose()
        ).bounds(cx + 5, by, 200, 20).build());
    }

    private void confirmRestart() {
        // Queue every move
        for (String modId : modsToDisable) {
            ModJarSwapper.queueMove(modId, ModJarSwapper.Direction.DISABLE);
        }
        // Remember where to reconnect
        PendingJoin.persist(targetAddress);
        // Close MC. The user relaunches normally; PreLaunchBootstrap processes the queue.
        if (this.minecraft != null) this.minecraft.stop();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor gfx, int mouseX, int mouseY, float delta) {
        super.extractRenderState(gfx, mouseX, mouseY, delta);
        gfx.centeredText(this.font, this.title, this.width / 2, 30, 0xFFA060);
        Component msg = Component.translatable("kitsune.restart.message",
                Component.literal(targetAddress).withStyle(s -> s.withColor(0xFFFFFFFF)));
        gfx.centeredText(this.font, msg, this.width / 2, 60, 0xFFFFFF);

        int y = 90;
        for (String mod : modsToDisable) {
            gfx.centeredText(this.font, Component.literal("\u2022 " + mod), this.width / 2, y, 0xFF8060);
            y += 12;
        }
        gfx.centeredText(
                this.font,
                Component.literal("(Fox Client will close. Relaunch to apply.)").withStyle(s -> s.withColor(0xFF888888)),
                this.width / 2, y + 10, 0xAAAAAA
        );
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) this.minecraft.setScreen(parent);
    }
}
