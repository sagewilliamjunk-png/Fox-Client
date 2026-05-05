package dev.kitsune.client.setting;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Round-trip tests for {@link SettingManager} — every concrete {@link Setting}
 * type must survive serialize -> deserialize with its value intact.
 *
 * Test inputs avoid the catch branches in SettingManager.readValue so we never
 * touch KitsuneClient.LOGGER (which would trigger Fabric class init and fail
 * outside a Minecraft runtime).
 */
class SettingManagerTest {

    @Test
    void boolean_round_trip_preserves_value() {
        BooleanSetting flagged = new BooleanSetting("flag", false);
        flagged.set(true);

        BooleanSetting target = new BooleanSetting("flag", false);
        roundTrip(List.of(flagged), List.of(target));

        assertTrue(target.get());
    }

    @Test
    void slider_round_trip_preserves_value() {
        SliderSetting source = new SliderSetting("opacity", 0.5, 0.0, 1.0, 0.01);
        source.set(0.73);

        SliderSetting target = new SliderSetting("opacity", 0.5, 0.0, 1.0, 0.01);
        roundTrip(List.of(source), List.of(target));

        assertEquals(0.73, target.get(), 1e-9);
    }

    @Test
    void mode_round_trip_preserves_value() {
        ModeSetting source = new ModeSetting("style", "compact", List.of("compact", "verbose", "minimal"));
        source.set("verbose");

        ModeSetting target = new ModeSetting("style", "compact", List.of("compact", "verbose", "minimal"));
        roundTrip(List.of(source), List.of(target));

        assertEquals("verbose", target.get());
    }

    @Test
    void color_round_trip_preserves_value() {
        ColorSetting source = new ColorSetting("tint", 0xFF000000);
        source.set(0xCCFF8C42);

        ColorSetting target = new ColorSetting("tint", 0xFF000000);
        roundTrip(List.of(source), List.of(target));

        assertEquals(source.get(), target.get());
    }

    @Test
    void string_round_trip_preserves_value() {
        StringSetting source = new StringSetting("label", "");
        source.set("hello, world");

        StringSetting target = new StringSetting("label", "");
        roundTrip(List.of(source), List.of(target));

        assertEquals("hello, world", target.get());
    }

    @Test
    void keybind_round_trip_preserves_value() {
        KeybindSetting source = new KeybindSetting("toggle", -1);
        source.set(82); // arbitrary GLFW code

        KeybindSetting target = new KeybindSetting("toggle", -1);
        roundTrip(List.of(source), List.of(target));

        assertEquals(82, target.get());
    }

    @Test
    void unknown_names_are_skipped() {
        BooleanSetting target = new BooleanSetting("flag", false);

        JsonArray arr = new JsonArray();
        JsonObject stranger = new JsonObject();
        stranger.addProperty("name", "not-a-real-setting");
        stranger.addProperty("type", "bool");
        stranger.addProperty("value", true);
        arr.add(stranger);

        SettingManager.deserialize(new ArrayList<>(List.of(target)), arr);

        assertFalse(target.get(), "unknown setting name must not flip another setting");
    }

    @Test
    void slider_clamps_out_of_range_values_on_load() {
        SliderSetting source = new SliderSetting("speed", 0.5, 0.0, 1.0, 0.01);
        // Bypass the constructor's clamp so we can craft a malformed array as if
        // the JSON file was hand-edited.
        JsonArray arr = new JsonArray();
        JsonObject obj = new JsonObject();
        obj.addProperty("name", "speed");
        obj.addProperty("type", "slider");
        obj.addProperty("value", 99.0);
        arr.add(obj);

        SettingManager.deserialize(new ArrayList<>(List.of(source)), arr);
        assertEquals(1.0, source.get(), 1e-9, "slider must clamp to its declared max");
    }

    @Test
    void mode_rejects_unlisted_value_on_load() {
        ModeSetting source = new ModeSetting("style", "compact", List.of("compact", "verbose"));

        JsonArray arr = new JsonArray();
        JsonObject obj = new JsonObject();
        obj.addProperty("name", "style");
        obj.addProperty("type", "mode");
        obj.addProperty("value", "garbage");
        arr.add(obj);

        SettingManager.deserialize(new ArrayList<>(List.of(source)), arr);
        assertEquals("compact", source.get(), "unlisted mode value must be rejected, default preserved");
    }

    private static void roundTrip(List<Setting<?>> source, List<Setting<?>> target) {
        JsonArray arr = SettingManager.serialize(source);
        SettingManager.deserialize(new ArrayList<>(target), arr);
    }
}
