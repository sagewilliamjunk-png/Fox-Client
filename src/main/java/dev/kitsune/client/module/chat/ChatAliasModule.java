package dev.kitsune.client.module.chat;

import dev.kitsune.client.event.ChatSendEvent;
import dev.kitsune.client.event.EventBus;
import dev.kitsune.client.module.Category;
import dev.kitsune.client.module.Module;
import dev.kitsune.client.setting.BooleanSetting;
import dev.kitsune.client.setting.StringSetting;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Chat aliases — rewrite short triggers into longer commands or chat strings
 * before they go to the server. Replaces the old QuickCommandsModule, which
 * hardcoded /home, /spawn, /kit etc. (mostly Essentials-plugin-specific).
 *
 * <h3>Syntax</h3>
 * Aliases are stored as a semicolon-separated list of {@code trigger=expansion}
 * pairs. Whitespace around {@code =} and {@code ;} is trimmed. A trigger
 * starting with {@code /} only matches command sends; a trigger without
 * {@code /} only matches chat sends. Matching is case-insensitive and
 * requires the WHOLE message to equal the trigger (no prefix match).
 *
 * <p>Examples (set via the in-game Settings → Chat → Aliases field):
 * <pre>
 *   /gh = /gamemode survival ; gg = Good game, well played! ; /sc = /setworldspawn
 * </pre>
 *
 * <h3>Implementation</h3>
 * Hooks the Kitsune {@link ChatSendEvent} (fired by EventBusBridge before
 * Fabric's MODIFY callback returns). On match, calls
 * {@link ChatSendEvent#setMessage(String)} so the rewritten string goes out
 * exactly like any other typed message. We do NOT call setMessage when there
 * is no match — keeps the bus completely transparent for non-aliased lines.
 *
 * <p>The alias map is rebuilt only when the StringSetting value changes
 * (string-identity comparison) so the per-send cost is a HashMap.get plus a
 * toLowerCase on the input — fast enough to be safe for any chat volume.
 */
public class ChatAliasModule extends Module {

    private final BooleanSetting caseSensitive = addSetting(new BooleanSetting("Case Sensitive", false));
    private final StringSetting  aliasList     = addSetting(new StringSetting("Aliases",
            "/gh = /gamemode survival ; gg = gg wp"));

    /** Cached parsed map. Rebuilt when the raw string changes. */
    private final Map<String, String> chatAliases = new HashMap<>();
    private final Map<String, String> cmdAliases  = new HashMap<>();
    private String cachedRaw = null;
    private boolean cachedCaseSensitive = false;

    private final Consumer<ChatSendEvent> handler = this::onChatSend;

    public ChatAliasModule() {
        super("Chat Aliases", "Rewrite short triggers into longer chat messages or commands", Category.CHAT);
    }

    @Override
    protected void onEnable()  { EventBus.subscribe(ChatSendEvent.class, handler); }

    @Override
    protected void onDisable() { EventBus.unsubscribe(ChatSendEvent.class, handler); }

    private void onChatSend(ChatSendEvent event) {
        rebuildIfChanged();
        if (chatAliases.isEmpty() && cmdAliases.isEmpty()) return;
        String raw = event.getMessage();
        if (raw == null) return;
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return;
        String key = caseSensitive.get() ? trimmed : trimmed.toLowerCase();
        // Commands and chat live in separate maps so a "/gg" alias never
        // accidentally fires when the user types "gg" in chat.
        Map<String, String> table = event.isCommand ? cmdAliases : chatAliases;
        String expansion = table.get(key);
        if (expansion == null) return;
        event.setMessage(expansion);
    }

    /** Parse the alias string into per-mode maps. No-op when the source
     *  string AND the case-sensitivity flag are both unchanged. */
    private void rebuildIfChanged() {
        String raw = aliasList.get();
        if (raw == null) raw = "";
        if (raw.equals(cachedRaw) && cachedCaseSensitive == caseSensitive.get()) return;
        cachedRaw = raw;
        cachedCaseSensitive = caseSensitive.get();
        chatAliases.clear();
        cmdAliases.clear();
        for (String entry : raw.split(";")) {
            int eq = entry.indexOf('=');
            if (eq <= 0) continue;
            String trigger = entry.substring(0, eq).trim();
            String expansion = entry.substring(eq + 1).trim();
            if (trigger.isEmpty() || expansion.isEmpty()) continue;
            String key = caseSensitive.get() ? trigger : trigger.toLowerCase();
            // Slash-prefixed triggers only match commands; the slash isn't
            // included in the message text that comes through the event for
            // commands either, so we strip it from the key on insert.
            if (trigger.startsWith("/")) {
                cmdAliases.put(key.startsWith("/") ? key.substring(1) : key, expansion);
            } else {
                chatAliases.put(key, expansion);
            }
        }
    }
}
