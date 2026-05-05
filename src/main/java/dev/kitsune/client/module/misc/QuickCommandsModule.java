package dev.kitsune.client.module.misc;

import dev.kitsune.client.module.Category;
import dev.kitsune.client.module.Module;
import dev.kitsune.client.setting.BooleanSetting;
import dev.kitsune.client.setting.ModeSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

import java.util.List;

/**
 * Quick Command Hotkeys — sends predefined slash commands or chat messages
 * via configurable one-shot settings.
 *
 * <p>Each setting acts as a trigger: flipping it to {@code true} causes the
 * corresponding action to fire once and resets the toggle. This keeps it
 * compatible with the existing BooleanSetting infrastructure without needing
 * a key-binding system.
 *
 * <p>Additional quick actions (home position, spawn, kit) can be added as
 * more BooleanSetting triggers below the existing ones.
 */
public class QuickCommandsModule extends Module {

    private final BooleanSetting shareCoords  = addSetting(new BooleanSetting("Share Coords",     false));
    private final BooleanSetting goHome       = addSetting(new BooleanSetting("Go Home (/home)",  false));
    private final BooleanSetting goSpawn      = addSetting(new BooleanSetting("Go Spawn (/spawn)",false));
    private final BooleanSetting callKit      = addSetting(new BooleanSetting("Get Kit (/kit)",   false));
    private final BooleanSetting listPlayers  = addSetting(new BooleanSetting("List Players (/list)", false));
    private final BooleanSetting sendGG       = addSetting(new BooleanSetting("Send \"gg\"",      false));
    private final ModeSetting    coordFormat  = addSetting(new ModeSetting("Coords Format", "XYZ",
            List.of("XYZ", "X/Y/Z", "Full sentence")));

    public QuickCommandsModule() {
        super("Quick Commands", "One-shot quick-send for common commands", Category.MISC);
    }

    @Override
    public void onTick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || player.connection == null) return;

        if (shareCoords.get()) {
            String msg = buildCoordsMsg(player);
            player.connection.sendChat(msg);
            shareCoords.set(false);
        }

        if (goHome.get()) {
            player.connection.sendCommand("home");
            goHome.set(false);
        }

        if (goSpawn.get()) {
            player.connection.sendCommand("spawn");
            goSpawn.set(false);
        }

        if (callKit.get()) {
            player.connection.sendCommand("kit");
            callKit.set(false);
        }

        if (listPlayers.get()) {
            player.connection.sendCommand("list");
            listPlayers.set(false);
        }

        if (sendGG.get()) {
            player.connection.sendChat("gg");
            sendGG.set(false);
        }
    }

    private String buildCoordsMsg(LocalPlayer player) {
        int x = player.getBlockX();
        int y = player.getBlockY();
        int z = player.getBlockZ();
        return switch (coordFormat.get()) {
            case "X/Y/Z"        -> x + "/" + y + "/" + z;
            case "Full sentence" -> "I'm at X:" + x + " Y:" + y + " Z:" + z;
            default             -> "My coords: " + x + " " + y + " " + z;
        };
    }
}
