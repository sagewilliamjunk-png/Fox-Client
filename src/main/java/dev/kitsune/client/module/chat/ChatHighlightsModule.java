package dev.kitsune.client.module.chat;

import dev.kitsune.client.module.Category;
import dev.kitsune.client.module.Module;
import dev.kitsune.client.setting.BooleanSetting;
import dev.kitsune.client.setting.ColorSetting;
import dev.kitsune.client.setting.ModeSetting;
import dev.kitsune.client.setting.StringSetting;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
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
    /** When on, each comma-separated entry is treated as a Java regex pattern.
     *  Compiled lazily and cached in {@link #patternCache}. Invalid patterns
     *  log a warning and fall back to literal matching for that entry. */
    private final BooleanSetting useRegex        = addSetting(new BooleanSetting("Use Regex", false));
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

    public boolean isRegexEnabled() { return useRegex.get(); }

    /**
     * One highlight rule parsed from the keywords field. Each entry in the
     * comma-separated list can carry up to two extra pipe-delimited segments:
     * <pre>
     *   diamond|#FFD700|pling
     *   ^^^^^^^^^^^^^^^^^^^^^^^^^^
     *   |        |       |
     *   pattern  color   sound
     * </pre>
     * Color may be {@code #RGB}, {@code #RRGGBB}, or {@code #AARRGGBB}; missing
     * values fall back to the module-global defaults. Sound is one of the
     * {@link #soundType} mode values (Ping / Note / Pling) — anything else
     * also falls back to the global default.
     */
    public record HighlightRule(String rawPattern, int color, String sound, Pattern compiled) {}

    /** Lazily parsed rule list. Same caching pattern as patternCache. */
    private final List<HighlightRule> ruleCache = new ArrayList<>();
    private String   cachedRuleSource = null;
    private boolean  cachedRuleRegexFlag = false;

    /** Returns the parsed rules. Recompiles when the keyword source string
     *  or the regex toggle changes. */
    public List<HighlightRule> getParsedRules() {
        String src = keywords.get();
        if (src == null) src = "";
        if (src.equals(cachedRuleSource) && cachedRuleRegexFlag == useRegex.get()) {
            return new ArrayList<>(ruleCache);
        }
        cachedRuleSource    = src;
        cachedRuleRegexFlag = useRegex.get();
        ruleCache.clear();
        int flags = caseSensitive.get() ? 0 : Pattern.CASE_INSENSITIVE;
        for (String raw : src.split(",")) {
            String entry = raw.trim();
            if (entry.isEmpty()) continue;
            String[] parts = entry.split("\\|", 3);
            String pattern = parts[0].trim();
            if (pattern.isEmpty()) continue;
            int color = parts.length > 1 ? parseColorOrDefault(parts[1].trim(), keywordColor.get()) : keywordColor.get();
            String sound = parts.length > 2 ? parts[2].trim() : soundType.get();
            Pattern compiled;
            if (useRegex.get()) {
                try { compiled = Pattern.compile(pattern, flags); }
                catch (PatternSyntaxException pse) {
                    dev.kitsune.client.KitsuneClient.LOGGER.warn(
                            "[ChatHighlights] invalid regex '{}': {} — using literal fallback",
                            pattern, pse.getDescription());
                    compiled = Pattern.compile(Pattern.quote(pattern), flags);
                }
            } else {
                compiled = Pattern.compile(Pattern.quote(pattern), flags);
            }
            ruleCache.add(new HighlightRule(pattern, color, sound, compiled));
        }
        return new ArrayList<>(ruleCache);
    }

    private static int parseColorOrDefault(String s, int fallback) {
        if (s == null || s.isEmpty()) return fallback;
        if (s.startsWith("#")) s = s.substring(1);
        try {
            if (s.length() == 3) {
                int r = Integer.parseInt(s.substring(0, 1), 16);
                int g = Integer.parseInt(s.substring(1, 2), 16);
                int b = Integer.parseInt(s.substring(2, 3), 16);
                return 0xFF000000 | (r * 0x11 << 16) | (g * 0x11 << 8) | (b * 0x11);
            }
            if (s.length() == 6) return 0xFF000000 | Integer.parseInt(s, 16);
            if (s.length() == 8) return (int) Long.parseLong(s, 16);
        } catch (NumberFormatException ignored) {}
        return fallback;
    }

    /** Cached regex patterns keyed by raw pattern string. Invalidated whenever
     *  the regex toggle flips or the keyword string changes (handled below by
     *  string-identity comparison in {@link #getCompiledPatterns}). */
    private final Map<String, Pattern> patternCache = new HashMap<>();
    private String cachedKeywordSource = null;
    private boolean cachedRegexFlag = false;

    /** Returns compiled patterns when regex mode is on. Each cache miss tries
     *  to compile; a Pattern.LITERAL fallback is stored on failure so the
     *  caller still gets a substring match. */
    public List<Pattern> getCompiledPatterns() {
        if (!useRegex.get()) return List.of();
        String src = keywords.get();
        // Rebuild only when the source string OR regex toggle changed.
        if (src.equals(cachedKeywordSource) && cachedRegexFlag == useRegex.get()) {
            return new ArrayList<>(patternCache.values());
        }
        patternCache.clear();
        cachedKeywordSource = src;
        cachedRegexFlag = useRegex.get();
        int flags = caseSensitive.get() ? 0 : Pattern.CASE_INSENSITIVE;
        for (String raw : getParsedKeywords()) {
            try {
                patternCache.put(raw, Pattern.compile(raw, flags));
            } catch (PatternSyntaxException pse) {
                // Fall back to literal so the user still gets some match.
                patternCache.put(raw, Pattern.compile(Pattern.quote(raw), flags));
                dev.kitsune.client.KitsuneClient.LOGGER.warn(
                        "[ChatHighlights] invalid regex '{}': {} — using literal fallback",
                        raw, pse.getDescription());
            }
        }
        return new ArrayList<>(patternCache.values());
    }

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
