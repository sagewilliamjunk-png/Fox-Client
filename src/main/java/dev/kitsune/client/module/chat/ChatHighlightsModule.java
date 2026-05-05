package dev.kitsune.client.module.chat;

import dev.kitsune.client.module.Category;
import dev.kitsune.client.module.Module;
import dev.kitsune.client.setting.BooleanSetting;
import dev.kitsune.client.setting.ColorSetting;
import dev.kitsune.client.setting.ModeSetting;
import dev.kitsune.client.setting.StringSetting;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Highlights your username and configurable keywords in incoming chat messages.
 *
 * <p>The ChatComponentMixin intercepts incoming packets and applies Minecraft
 * colour codes around matched text. Name matches use the "Name colour" setting;
 * keyword matches use the "Keyword colour" setting.
 *
 * <p>Keywords are entered as a comma-separated list (e.g. "gg,nice,ez").
 * Matching is case-insensitive. Whole-word matching is optional.
 */
public class ChatHighlightsModule extends Module {

    private final BooleanSetting highlightName  = addSetting(new BooleanSetting("Highlight Name",    true));
    private final BooleanSetting playSound       = addSetting(new BooleanSetting("Play Sound",        true));
    private final BooleanSetting highlightKeys   = addSetting(new BooleanSetting("Highlight Keywords", true));
    private final BooleanSetting wholeWord       = addSetting(new BooleanSetting("Whole Word Only",   false));
    private final BooleanSetting caseSensitive   = addSetting(new BooleanSetting("Case Sensitive",    false));
    private final ColorSetting   nameColor       = addSetting(new ColorSetting("Name Colour",    0xFFFFCC00));
    private final ColorSetting   keywordColor    = addSetting(new ColorSetting("Keyword Colour", 0xFFFFFF44));
    private final ModeSetting    soundType       = addSetting(new ModeSetting("Sound Type", "Ping",
            List.of("Ping", "Note", "Pling")));
    /** User-editable comma-separated keyword list. */
    private final StringSetting  keywords        = addSetting(new StringSetting("Keywords",
            "fox,kitsune,hi,hello"));

    public ChatHighlightsModule() {
        super("Chat Highlights", "Highlights your name and keywords in chat", Category.CHAT);
    }

    // ---- API for mixins / external code ----

    public boolean shouldHighlightName()    { return highlightName.get(); }
    public boolean shouldPlaySound()        { return playSound.get(); }
    public boolean shouldHighlightKeywords(){ return highlightKeys.get(); }
    public boolean isWholeWordOnly()        { return wholeWord.get(); }
    public boolean isCaseSensitive()        { return caseSensitive.get(); }

    /** ARGB colour used when a name match is found. */
    public int getNameColor()    { return nameColor.get(); }
    /** ARGB colour used when a keyword match is found. */
    public int getKeywordColor() { return keywordColor.get(); }

    /**
     * Minecraft-format colour code prefix for the name highlight colour.
     * Returns the closest vanilla code rather than a hex value so it works in
     * the vanilla chat renderer without needing a custom font shader.
     */
    public String getNameFormatCode()    { return argbToFormatCode(nameColor.get()); }
    public String getKeywordFormatCode() { return argbToFormatCode(keywordColor.get()); }

    /**
     * Returns the keyword list as trimmed, non-empty tokens.
     */
    public List<String> getParsedKeywords() {
        return Arrays.stream(keywords.get().split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    /** Raw comma-separated string. */
    public String getKeywordList() { return keywords.get(); }
    public void   setKeywordList(String v) { keywords.set(v); }

    public String getSoundType() { return soundType.get(); }

    // ---- helpers ----

    /**
     * Best-effort mapping from an ARGB colour to a nearby Minecraft §x code.
     * Falls back to §e (yellow) which is the classic highlight colour.
     */
    private static String argbToFormatCode(int argb) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >>  8) & 0xFF;
        int b =  argb        & 0xFF;
        // Very simple: pick the dominant channel
        if (r > 200 && g > 200 && b < 80) return "\u00a7e"; // yellow
        if (r > 200 && g > 150 && b < 50) return "\u00a76"; // gold
        if (r > 200 && g < 100 && b < 100) return "\u00a7c"; // red
        if (r < 100 && g > 200 && b < 100) return "\u00a7a"; // green
        if (r < 100 && g < 100 && b > 200) return "\u00a79"; // blue
        if (r > 200 && g < 100 && b > 200) return "\u00a7d"; // light purple
        if (r < 100 && g > 200 && b > 200) return "\u00a7b"; // aqua
        return "\u00a7e"; // fallback: yellow
    }
}
