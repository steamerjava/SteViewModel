package dev.viewmodelmod.config;

import com.google.gson.annotations.SerializedName;

import java.util.ArrayList;
import java.util.List;

/** Root JSON object persisted to config/viewmodelmod.json. */
public class ViewModelConfig {
    public static final String THEME_DARK = "dark";
    public static final String THEME_LIGHT = "light";
    public static final String LANGUAGE_EN = "en";
    public static final String LANGUAGE_RU = "ru";

    public int version = 2;
    public List<ViewModelConfigEntry> configs = new ArrayList<>();
    public int activeConfig = 0;
    public HighlightSettings highlight = new HighlightSettings();
    /** Multiplier for mouse dragging, 0.25x .. 3x. */
    public float dragSensitivity = 1.0F;
    public String theme = THEME_DARK;
    public String language = LANGUAGE_EN;
    /** Editor panel position relative to its default centred position, in GUI pixels. */
    public float panelOffsetX = 0.0F;
    public float panelOffsetY = 0.0F;

    /**
     * Old v1 profile list. Only read once, then converted into {@link #configs}
     * and dropped from the saved file.
     */
    @SerializedName("profiles")
    public List<LegacyProfile> legacyProfiles;

    public ViewModelConfig() {
    }

    public void sanitize() {
        if (this.configs == null) {
            this.configs = new ArrayList<>();
        }
        if (this.configs.isEmpty() && this.legacyProfiles != null) {
            for (int i = 0; i < this.legacyProfiles.size(); i++) {
                LegacyProfile legacy = this.legacyProfiles.get(i);
                if (legacy == null) {
                    continue;
                }
                ViewModelConfigEntry entry = new ViewModelConfigEntry(legacy.name);
                entry.createdAt = System.currentTimeMillis() + i;
                entry.leftHand = legacy.leftHand;
                entry.rightHand = legacy.rightHand;
                entry.leftItem = legacy.leftItem;
                entry.rightItem = legacy.rightItem;
                this.configs.add(entry);
            }
        }
        this.legacyProfiles = null;

        if (this.configs.isEmpty()) {
            this.configs.add(new ViewModelConfigEntry("Default"));
        }
        for (int i = 0; i < this.configs.size(); i++) {
            ViewModelConfigEntry entry = this.configs.get(i);
            if (entry == null) {
                entry = new ViewModelConfigEntry("Config " + (i + 1));
                this.configs.set(i, entry);
            }
            entry.sanitize("Config " + (i + 1));
        }
        this.activeConfig = Math.max(0, Math.min(this.configs.size() - 1, this.activeConfig));

        if (this.highlight == null) {
            this.highlight = new HighlightSettings();
        }
        this.highlight.sanitize();

        if (!Float.isFinite(this.dragSensitivity)) {
            this.dragSensitivity = 1.0F;
        }
        this.dragSensitivity = Math.max(0.25F, Math.min(3.0F, this.dragSensitivity));

        this.theme = THEME_LIGHT.equalsIgnoreCase(this.theme) ? THEME_LIGHT : THEME_DARK;
        this.language = LANGUAGE_RU.equalsIgnoreCase(this.language) ? LANGUAGE_RU : LANGUAGE_EN;
        this.panelOffsetX = clamp(this.panelOffsetX, -4000.0F, 4000.0F);
        this.panelOffsetY = clamp(this.panelOffsetY, -4000.0F, 4000.0F);
        this.version = 2;
    }

    private static float clamp(float value, float min, float max) {
        if (!Float.isFinite(value)) {
            return 0.0F;
        }
        return Math.max(min, Math.min(max, value));
    }

    /** Shape of the old v1 profile, kept only for migration. */
    public static class LegacyProfile {
        public String name;
        public ViewModelTransform leftHand;
        public ViewModelTransform rightHand;
        public ViewModelTransform leftItem;
        public ViewModelTransform rightItem;
    }
}
