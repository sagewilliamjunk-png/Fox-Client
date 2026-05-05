package dev.kitsune.client.screen;

import dev.kitsune.client.core.Profile;
import dev.kitsune.client.core.ProfileManager;
import dev.kitsune.client.features.FeatureRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * Full profile management: create, rename, duplicate, delete, set active.
 * Opened from the Fox Menu's "Manage Profiles" button.
 */
public class ProfileManagementScreen extends Screen {
    private final Screen parent;
    private static final int ROW_HEIGHT = 26;
    private static final int LIST_TOP = 44;

    public ProfileManagementScreen(Screen parent) {
        super(Component.literal("Manage Profiles"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        List<String> names = ProfileManager.getProfileNames();
        String activeName = ProfileManager.getActiveName();

        // "+ New Profile" button top-right
        this.addRenderableWidget(Button.builder(
                Component.literal("+ New Profile"),
                btn -> this.minecraft.setScreen(new ProfileNameDialog(this, "New Profile", "", name -> {
                    if (!name.trim().isEmpty() && !ProfileManager.exists(name.trim().toLowerCase())) {
                        ProfileManager.create(name.trim().toLowerCase());
                        this.minecraft.setScreen(new ProfileManagementScreen(parent));
                    }
                }))
        ).bounds(this.width - 120, 8, 110, 20).build());

        // Profile rows
        int listBottom = this.height - 36;
        for (int i = 0; i < names.size(); i++) {
            String name = names.get(i);
            boolean isActive = name.equals(activeName);
            int rowY = LIST_TOP + i * ROW_HEIGHT;
            if (rowY > listBottom) break;

            // Set Active button
            if (!isActive) {
                this.addRenderableWidget(Button.builder(
                        Component.literal("Activate"),
                        btn -> {
                            ProfileManager.switchTo(name, this.minecraft);
                            FeatureRegistry.syncEnabledStates();
                            this.rebuildWidgets();
                        }
                ).bounds(cx + 20, rowY + 2, 55, 18).build());
            }

            // Duplicate button
            this.addRenderableWidget(Button.builder(
                    Component.literal("Dup"),
                    btn -> {
                        String dupName = name + "_copy";
                        int c = 1;
                        while (ProfileManager.exists(dupName)) dupName = name + "_copy" + (c++);
                        Profile original = ProfileManager.get(name);
                        Profile dup = ProfileManager.create(dupName);
                        if (original != null && dup != null) {
                            // Copy settings from original to duplicate
                            String json = original.toJson().toString();
                            dup.copyFrom(original);
                        }
                        ProfileManager.save();
                        this.rebuildWidgets();
                    }
            ).bounds(cx + 80, rowY + 2, 35, 18).build());

            // Rename button
            this.addRenderableWidget(Button.builder(
                    Component.literal("Ren"),
                    btn -> this.minecraft.setScreen(new ProfileNameDialog(this, "Rename: " + name, name, newName -> {
                        if (!newName.trim().isEmpty() && !ProfileManager.exists(newName.trim().toLowerCase())) {
                            Profile p = ProfileManager.get(name);
                            if (p != null) {
                                ProfileManager.delete(name);
                                p.setName(newName.trim().toLowerCase());
                                // Re-create with new name
                                Profile fresh = ProfileManager.create(newName.trim().toLowerCase());
                                if (fresh != null) fresh.copyFrom(p);
                                ProfileManager.save();
                                if (name.equals(ProfileManager.getActiveName())) {
                                    ProfileManager.switchTo(newName.trim().toLowerCase(), this.minecraft);
                                }
                            }
                            this.minecraft.setScreen(new ProfileManagementScreen(parent));
                        }
                    }))
            ).bounds(cx + 120, rowY + 2, 35, 18).build());

            // Delete button (disabled if only 1 profile)
            if (names.size() > 1) {
                this.addRenderableWidget(Button.builder(
                        Component.literal("\u00a7cDel"),
                        btn -> {
                            ProfileManager.delete(name);
                            if (name.equals(ProfileManager.getActiveName())) {
                                List<String> remaining = ProfileManager.getProfileNames();
                                if (!remaining.isEmpty()) {
                                    ProfileManager.switchTo(remaining.get(0), this.minecraft);
                                    FeatureRegistry.syncEnabledStates();
                                }
                            }
                            this.rebuildWidgets();
                        }
                ).bounds(cx + 160, rowY + 2, 35, 18).build());
            }
        }

        // Done button
        this.addRenderableWidget(Button.builder(
                Component.literal("Done"),
                btn -> this.onClose()
        ).bounds(cx - 75, this.height - 28, 150, 20).build());
    }

    @Override
    public void render(GuiGraphics gfx, int mouseX, int mouseY, float delta) {
        super.render(gfx, mouseX, mouseY, delta);
        gfx.drawCenteredString(this.font, this.title, this.width / 2, 14, FoxTheme.FOX_ORANGE);

        List<String> names = ProfileManager.getProfileNames();
        String activeName = ProfileManager.getActiveName();
        int cx = this.width / 2;

        for (int i = 0; i < names.size(); i++) {
            String name = names.get(i);
            boolean isActive = name.equals(activeName);
            int rowY = LIST_TOP + i * ROW_HEIGHT;
            if (rowY > this.height - 36) break;

            // Background
            int bg = isActive ? 0x30FFA552 : (i % 2 == 0 ? 0x20FFFFFF : 0x10FFFFFF);
            gfx.fill(8, rowY, this.width - 8, rowY + ROW_HEIGHT - 2, bg);

            // Profile name + active indicator
            String display = FoxTheme.capitalize(name);
            if (isActive) display = "\u00a76\u2b50 " + display + " \u00a7a(active)";
            else display = "\u00a7f  " + display;
            gfx.drawString(this.font, display, 14, rowY + 7, 0xFFFFFFFF, false);
        }
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) this.minecraft.setScreen(parent);
    }
}
