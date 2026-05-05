package dev.kitsune.client.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * A single rule mapping a server host pattern to a set of mods (and/or features)
 * that should be disabled when joining matching servers.
 *
 * Host pattern uses simple glob wildcards: {@code *.hypixel.net}, {@code 2b2t.org},
 * {@code play.example.*}, etc.
 *
 * Action.DISABLE moves matching mod JARs out of mods/ via {@link dev.kitsune.client.core.ModJarSwapper}
 * (requires restart). Disabled feature IDs are toggled at runtime via
 * {@link dev.kitsune.client.features.FeatureRegistry#setServerOverride(String, boolean)}.
 *
 * Action.WARN only displays a warning toast on connect — no automatic action.
 */
public class ServerRule {

    public enum Action { DISABLE, WARN }

    public String name;                  // human label
    public String hostPattern;           // glob, e.g. "*.hypixel.net"
    public List<String> modIds;          // Fabric mod IDs to disable (jar move + restart)
    public List<String> featureIds;      // Fox feature IDs to override-off at runtime
    public Action action;
    public String note;                  // optional explanation shown to user

    public ServerRule() {
        this.modIds = new ArrayList<>();
        this.featureIds = new ArrayList<>();
        this.action = Action.DISABLE;
    }

    public ServerRule(String name, String hostPattern, Action action) {
        this();
        this.name = name;
        this.hostPattern = hostPattern;
        this.action = action;
    }

    /** Test whether the given server host matches this rule's pattern. */
    public boolean matches(String host) {
        if (host == null || hostPattern == null) return false;
        return globMatch(hostPattern.toLowerCase(), host.toLowerCase());
    }

    /** Tiny glob matcher: only handles {@code *} (any sequence). Sufficient for hostnames. */
    public static boolean globMatch(String pattern, String text) {
        // Convert glob to a simple two-pointer match
        int p = 0, t = 0, starIdx = -1, matchIdx = 0;
        while (t < text.length()) {
            if (p < pattern.length() && (pattern.charAt(p) == text.charAt(t))) {
                p++; t++;
            } else if (p < pattern.length() && pattern.charAt(p) == '*') {
                starIdx = p++;
                matchIdx = t;
            } else if (starIdx != -1) {
                p = starIdx + 1;
                t = ++matchIdx;
            } else {
                return false;
            }
        }
        while (p < pattern.length() && pattern.charAt(p) == '*') p++;
        return p == pattern.length();
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        if (name != null) o.addProperty("name", name);
        o.addProperty("hostPattern", hostPattern);
        o.addProperty("action", action.name());
        if (note != null) o.addProperty("note", note);
        JsonArray ms = new JsonArray();
        for (String s : modIds) ms.add(s);
        o.add("modIds", ms);
        JsonArray fs = new JsonArray();
        for (String s : featureIds) fs.add(s);
        o.add("featureIds", fs);
        return o;
    }

    public static ServerRule fromJson(JsonObject o) {
        ServerRule r = new ServerRule();
        if (o.has("name")) r.name = o.get("name").getAsString();
        if (o.has("hostPattern")) r.hostPattern = o.get("hostPattern").getAsString();
        if (o.has("action")) {
            try { r.action = Action.valueOf(o.get("action").getAsString()); }
            catch (IllegalArgumentException ex) { r.action = Action.WARN; }
        }
        if (o.has("note")) r.note = o.get("note").getAsString();
        if (o.has("modIds")) for (JsonElement el : o.getAsJsonArray("modIds")) r.modIds.add(el.getAsString());
        if (o.has("featureIds")) for (JsonElement el : o.getAsJsonArray("featureIds")) r.featureIds.add(el.getAsString());
        return r;
    }
}
