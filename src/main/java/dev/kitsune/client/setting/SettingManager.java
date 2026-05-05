package dev.kitsune.client.setting;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.kitsune.client.KitsuneClient;

import java.util.List;

/**
 * Stateless (de)serializer for a module's {@link Setting} list. Each setting
 * is encoded as a {@code { "name": ..., "type": ..., "value": ... }} object,
 * and the list is stored as a JSON array. Unknown names/types are skipped
 * (forwards-compat: removing or renaming a setting between versions doesn't
 * brick the config file).
 */
public final class SettingManager {
    private SettingManager() {}

    public static JsonArray serialize(List<Setting<?>> settings) {
        JsonArray arr = new JsonArray();
        for (Setting<?> s : settings) {
            JsonObject obj = new JsonObject();
            obj.addProperty("name", s.name());
            obj.addProperty("type", s.type());
            writeValue(obj, s);
            arr.add(obj);
        }
        return arr;
    }

    public static void deserialize(List<Setting<?>> settings, JsonArray arr) {
        if (arr == null) return;
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) continue;
            JsonObject obj = el.getAsJsonObject();
            String name = obj.has("name") ? obj.get("name").getAsString() : null;
            if (name == null) continue;
            Setting<?> target = findByName(settings, name);
            if (target == null) continue;
            readValue(obj, target);
        }
    }

    private static Setting<?> findByName(List<Setting<?>> settings, String name) {
        for (Setting<?> s : settings) {
            if (s.name().equals(name)) return s;
        }
        return null;
    }

    private static void writeValue(JsonObject obj, Setting<?> s) {
        if (s instanceof BooleanSetting b) {
            obj.addProperty("value", b.get());
        } else if (s instanceof SliderSetting sl) {
            obj.addProperty("value", sl.get());
        } else if (s instanceof ModeSetting m) {
            obj.addProperty("value", m.get());
        } else if (s instanceof ColorSetting c) {
            obj.addProperty("value", c.get());
        } else if (s instanceof KeybindSetting k) {
            obj.addProperty("value", k.get());
        } else if (s instanceof StringSetting str) {
            obj.addProperty("value", str.get());
        }
    }

    @SuppressWarnings("unchecked")
    private static void readValue(JsonObject obj, Setting<?> s) {
        if (!obj.has("value")) return;
        JsonElement v = obj.get("value");
        try {
            if (s instanceof BooleanSetting b) {
                b.set(v.getAsBoolean());
            } else if (s instanceof SliderSetting sl) {
                sl.set(v.getAsDouble());
            } else if (s instanceof ModeSetting m) {
                m.set(v.getAsString());
            } else if (s instanceof ColorSetting c) {
                c.set(v.getAsInt());
            } else if (s instanceof KeybindSetting k) {
                k.set(v.getAsInt());
            } else if (s instanceof StringSetting str) {
                str.set(v.getAsString());
            }
        } catch (Exception ex) {
            // Don't crash the load — fall back to the existing default and
            // surface the mismatch so bad configs don't fail silently.
            KitsuneClient.LOGGER.warn(
                    "[Fox] setting '{}' has unreadable value {} ({}); keeping default",
                    s.name(), v, ex.getClass().getSimpleName());
        }
    }
}
