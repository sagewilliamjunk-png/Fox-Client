package dev.kitsune.client.command;

import dev.kitsune.client.KitsuneClient;
import dev.kitsune.client.config.ConfigManager;
import dev.kitsune.client.event.ChatSendEvent;
import dev.kitsune.client.event.EventBus;
import dev.kitsune.client.module.Module;
import dev.kitsune.client.module.ModuleManager;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * Chat-prefix command processor. Subscribes to {@link ChatSendEvent} and
 * intercepts any message starting with {@code .fox }. Cancelled messages
 * are replaced with a local-only feedback line so the server never sees them.
 */
public final class KitsuneCommand {
    private static final String PREFIX = ".fox";

    private KitsuneCommand() {}

    public static void register() {
        EventBus.subscribe(ChatSendEvent.class, KitsuneCommand::handle);
    }

    private static void handle(ChatSendEvent event) {
        if (event.cancelled) return;
        String msg = event.getMessage().trim();

        // First: try alias expansion (first word match). If the expanded
        // result still starts with .fox we handle it below; otherwise we
        // rewrite the message and let it flow through to the server.
        String expanded = AliasManager.expand(msg);
        if (expanded != null) {
            // Expansion of chained commands: just take the first for now and
            // rewrite the send. Multi-command chains would need a queue.
            String first = expanded.split(";", 2)[0].trim();
            if (first.startsWith(".fox")) {
                msg = first;
                event.setMessage(first);
            } else {
                event.setMessage(first);
                return;
            }
        }

        if (!msg.equals(PREFIX) && !msg.startsWith(PREFIX + " ")) return;
        event.cancelled = true;

        String[] parts = msg.length() == PREFIX.length()
                ? new String[0]
                : msg.substring(PREFIX.length() + 1).trim().split("\\s+");

        try {
            if (parts.length == 0 || parts[0].equalsIgnoreCase("help")) {
                help();
            } else if (parts[0].equalsIgnoreCase("toggle") && parts.length >= 2) {
                toggle(parts[1]);
            } else if (parts[0].equalsIgnoreCase("profile")) {
                profile(parts);
            } else if (parts[0].equalsIgnoreCase("bind") && parts.length >= 3) {
                bind(parts[1], parts[2]);
            } else if (parts[0].equalsIgnoreCase("list")) {
                listModules();
            } else if (parts[0].equalsIgnoreCase("alias")) {
                alias(parts);
            } else if (parts[0].equalsIgnoreCase("unalias") && parts.length >= 2) {
                unalias(parts[1]);
            } else if (parts[0].equalsIgnoreCase("loot")) {
                loot();
            } else if (parts[0].equalsIgnoreCase("hud")) {
                openHudEditor();
            } else if (parts[0].equalsIgnoreCase("settings") || parts[0].equalsIgnoreCase("config")) {
                openFoxSettings();
            } else {
                send("§cUnknown subcommand. Try §e.fox help");
            }
        } catch (Throwable t) {
            KitsuneClient.LOGGER.error("[Fox] command error", t);
            send("§cError: " + t.getMessage());
        }
    }

    private static void alias(String[] parts) {
        if (parts.length == 1 || parts[1].equalsIgnoreCase("list")) {
            var map = AliasManager.all();
            if (map.isEmpty()) { send("§7(no aliases defined)"); return; }
            send("§6Aliases (" + map.size() + "):");
            for (var e : map.entrySet()) {
                send(" §e" + e.getKey() + " §7→ §f" + e.getValue());
            }
            return;
        }
        if (parts.length < 3) {
            send("§cUsage: .fox alias <name> <expansion...>");
            send("§7Expansion supports $1..$9 and $* for arguments, ; to chain.");
            return;
        }
        String name = parts[1];
        StringBuilder sb = new StringBuilder();
        for (int i = 2; i < parts.length; i++) {
            if (i > 2) sb.append(' ');
            sb.append(parts[i]);
        }
        AliasManager.set(name, sb.toString());
        send("§aAlias §e" + name + " §a= §f" + sb);
    }

    private static void unalias(String name) {
        if (AliasManager.remove(name)) {
            send("§aRemoved alias §e" + name);
        } else {
            send("§cNo alias named §e" + name);
        }
    }

    private static void openHudEditor() {
        Minecraft mc = Minecraft.getInstance();
        // Defer to next tick so the chat screen closes first.
        mc.execute(() -> mc.setScreen(new dev.kitsune.client.hud.HudEditorScreen()));
    }

    private static void openFoxSettings() {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> mc.setScreen(
                new dev.kitsune.client.screen.FoxSettingsScreen(mc.screen)));
    }

    private static void loot() {
        var entries = dev.kitsune.client.command.LootHistory.recent(15);
        if (entries.isEmpty()) { send("§7(loot history is empty)"); return; }
        send("§6Recent loot (" + entries.size() + "):");
        for (var e : entries) send(" §7- §f" + e);
    }

    private static void help() {
        send("§6Fox §fcommands:");
        send("§e.fox help §7— this list");
        send("§e.fox list §7— list modules");
        send("§e.fox toggle <module> §7— enable/disable a module");
        send("§e.fox profile <load|save|list|delete> [name] §7— profile ops");
        send("§e.fox bind <module> <glfwKey> §7— rebind (GLFW key code, -1 = unbind)");
        send("§e.fox alias <name> <expansion> §7— define alias ($1..$9, $*)");
        send("§e.fox unalias <name> §7— remove alias");
        send("§e.fox loot §7— show recent loot history");
        send("§e.fox hud §7— open the draggable HUD editor");
        send("§e.fox settings §7— open the Fox Client settings screen");
    }

    private static void toggle(String name) {
        Module m = ModuleManager.getByName(name);
        if (m == null) {
            send("§cNo module named '§e" + name + "§c'");
            return;
        }
        m.toggle();
        send("§6" + m.name() + " §f" + (m.isEnabled() ? "§aON" : "§cOFF"));
    }

    private static void listModules() {
        List<Module> all = ModuleManager.all();
        if (all.isEmpty()) {
            send("§7(no modules registered yet)");
            return;
        }
        send("§6Modules (" + all.size() + "):");
        for (Module m : all) {
            send(" §7- §f" + m.name() + " §7[" + m.category().displayName() + "] "
                    + (m.isEnabled() ? "§aON" : "§cOFF"));
        }
    }

    private static void profile(String[] parts) {
        if (parts.length < 2) {
            send("§cUsage: .fox profile <load|save|list|delete> [name]");
            return;
        }
        String sub = parts[1].toLowerCase();
        switch (sub) {
            case "list" -> {
                List<String> names = ConfigManager.listProfiles();
                send("§6Profiles (active: §e" + ConfigManager.getActiveProfile() + "§6):");
                for (String n : names) send(" §7- §f" + n);
            }
            case "save" -> {
                String name = parts.length >= 3 ? parts[2] : ConfigManager.getActiveProfile();
                ConfigManager.saveProfile(name);
                send("§aSaved profile §e" + name);
            }
            case "load" -> {
                if (parts.length < 3) { send("§cUsage: .fox profile load <name>"); return; }
                ConfigManager.loadProfile(parts[2]);
                send("§aLoaded profile §e" + parts[2]);
            }
            case "delete" -> {
                if (parts.length < 3) { send("§cUsage: .fox profile delete <name>"); return; }
                ConfigManager.deleteProfile(parts[2]);
                send("§aDeleted profile §e" + parts[2]);
            }
            default -> send("§cUnknown profile subcommand: " + sub);
        }
    }

    private static void bind(String moduleName, String keyStr) {
        Module m = ModuleManager.getByName(moduleName);
        if (m == null) { send("§cNo module named '§e" + moduleName + "§c'"); return; }
        int key;
        try { key = Integer.parseInt(keyStr); }
        catch (NumberFormatException e) { send("§cKey must be a GLFW integer key code"); return; }
        m.setKeyBind(key);
        send("§6" + m.name() + " §fbound to GLFW key §e" + key);
    }

    private static void send(String text) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.sendSystemMessage(Component.literal(text));
    }
}
