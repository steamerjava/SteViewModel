package dev.viewmodelmod;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.viewmodelmod.config.HighlightSettings;
import dev.viewmodelmod.config.ViewModelConfig;
import dev.viewmodelmod.config.ViewModelConfigEntry;
import dev.viewmodelmod.config.ViewModelTransform;
import dev.viewmodelmod.editor.EditorTarget;
import dev.viewmodelmod.editor.HandBounds;
import dev.viewmodelmod.render.ViewModelRenderer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Arm;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Owns the persisted config, the selected editor target and transient editor state. */
public final class ViewModelManager {
    private static final Gson GSON = new GsonBuilder()
        .setPrettyPrinting()
        .disableHtmlEscaping()
        .create();

    private final Path configPath = FabricLoader.getInstance().getConfigDir().resolve("viewmodelmod.json");
    private final Map<EditorTarget, HandBounds> handBounds = new EnumMap<>(EditorTarget.class);

    private ViewModelConfig config = new ViewModelConfig();
    private EditorTarget selectedTarget = EditorTarget.RIGHT_HAND;
    private boolean editorActive;
    private boolean dirty;
    private long lastDirtyAt;

    public void load() {
        if (Files.isRegularFile(this.configPath)) {
            try {
                String json = Files.readString(this.configPath, StandardCharsets.UTF_8);
                ViewModelConfig loaded = GSON.fromJson(json, ViewModelConfig.class);
                if (loaded != null) {
                    this.config = loaded;
                }
            } catch (Exception exception) {
                ViewModelMOD.LOGGER.error("Could not read {}. Using defaults.", this.configPath, exception);
            }
        }

        this.config.sanitize();
        this.dirty = false;
        this.saveNow();
    }

    public void tick() {
        if (this.dirty && System.currentTimeMillis() - this.lastDirtyAt >= 450L) {
            this.saveNow();
        }
    }

    public void saveNow() {
        try {
            Files.createDirectories(this.configPath.getParent());
            String json = GSON.toJson(this.config);
            Path temp = this.configPath.resolveSibling(this.configPath.getFileName() + ".tmp");
            Files.writeString(temp, json, StandardCharsets.UTF_8);
            try {
                Files.move(temp, this.configPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temp, this.configPath, StandardCopyOption.REPLACE_EXISTING);
            }
            this.dirty = false;
        } catch (IOException exception) {
            ViewModelMOD.LOGGER.error("Could not save ViewModel config to {}", this.configPath, exception);
        }
    }

    public ViewModelConfig config() {
        return this.config;
    }

    public List<ViewModelConfigEntry> configs() {
        return this.config.configs;
    }

    public ViewModelConfigEntry activeConfig() {
        int size = this.config.configs.size();
        if (size == 0) {
            this.config.configs.add(new ViewModelConfigEntry("Default"));
            this.config.activeConfig = 0;
        } else if (this.config.activeConfig < 0 || this.config.activeConfig >= size) {
            this.config.activeConfig = Math.max(0, Math.min(size - 1, this.config.activeConfig));
        }
        return this.config.configs.get(this.config.activeConfig);
    }

    public int activeConfigIndex() {
        this.activeConfig();
        return this.config.activeConfig;
    }

    public HighlightSettings highlight() {
        return this.config.highlight;
    }

    public EditorTarget selectedTarget() {
        return this.selectedTarget;
    }

    public void selectTarget(EditorTarget target) {
        if (target != null) {
            this.selectedTarget = target;
        }
    }

    public ViewModelTransform selectedTransform() {
        return this.selectedTarget.transform(this.activeConfig());
    }

    public void applyArmTransform(MatrixStack matrices, Arm arm) {
        ViewModelRenderer.applyHand(matrices, EditorTarget.forArm(arm, false).transform(this.activeConfig()));
    }

    public void resetCurrent() {
        this.selectedTransform().reset();
        this.markDirty();
    }

    public void resetAll() {
        this.activeConfig().resetTransforms();
        this.config.highlight.reset();
        this.config.dragSensitivity = 1.0F;
        this.config.panelOffsetX = 0.0F;
        this.config.panelOffsetY = 0.0F;
        this.markDirty();
    }

    public void loadDefaults() {
        String theme = this.config.theme;
        String language = this.config.language;
        this.config = new ViewModelConfig();
        this.config.configs.add(new ViewModelConfigEntry("Default"));
        this.config.theme = theme;
        this.config.language = language;
        this.config.sanitize();
        this.markDirty();
        this.saveNow();
    }

    /** Creates a copy of the active config so tweaks can be kept as a variant. */
    public ViewModelConfigEntry createConfig() {
        int number = 1;
        String base;
        do {
            base = "Config " + number++;
        } while (this.hasConfigNamed(base));

        ViewModelConfigEntry copy = this.activeConfig().copyAs(this.uniqueName(base));
        this.config.configs.add(copy);
        this.config.activeConfig = this.config.configs.size() - 1;
        this.markDirty();
        return copy;
    }

    public void selectConfig(int index) {
        if (index >= 0 && index < this.config.configs.size()) {
            this.config.activeConfig = index;
            this.markDirty();
        }
    }

    public boolean deleteActiveConfig() {
        if (this.config.configs.size() <= 1) {
            return false;
        }
        this.config.configs.remove(this.config.activeConfig);
        this.config.activeConfig = Math.max(0, Math.min(this.config.configs.size() - 1, this.config.activeConfig));
        this.markDirty();
        return true;
    }

    public void renameActiveConfig(String name) {
        if (name == null) {
            return;
        }
        String trimmed = name.strip();
        if (trimmed.isEmpty()) {
            return;
        }
        ViewModelConfigEntry active = this.activeConfig();
        if (this.hasConfigNamedExcept(trimmed, active)) {
            trimmed = this.uniqueName(trimmed);
        }
        active.name = trimmed;
        this.markDirty();
    }

    public void switchConfig(int direction) {
        int size = this.config.configs.size();
        if (size == 0) {
            return;
        }
        this.config.activeConfig = Math.floorMod(this.config.activeConfig + direction, size);
        this.markDirty();
    }

    private boolean hasConfigNamed(String name) {
        for (ViewModelConfigEntry entry : this.config.configs) {
            if (entry.name.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasConfigNamedExcept(String name, ViewModelConfigEntry ignored) {
        for (ViewModelConfigEntry entry : this.config.configs) {
            if (entry != ignored && entry.name.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    private String uniqueName(String base) {
        if (!this.hasConfigNamed(base)) {
            return base;
        }
        int suffix = 2;
        String candidate;
        do {
            candidate = base + " (" + suffix++ + ")";
        } while (this.hasConfigNamed(candidate));
        return candidate;
    }

    public String theme() {
        return this.config.theme;
    }

    public void setTheme(String theme) {
        this.config.theme = ViewModelConfig.THEME_LIGHT.equals(theme)
            ? ViewModelConfig.THEME_LIGHT
            : ViewModelConfig.THEME_DARK;
        this.markDirty();
    }

    public String language() {
        return this.config.language;
    }

    public void setLanguage(String language) {
        this.config.language = ViewModelConfig.LANGUAGE_RU.equals(language)
            ? ViewModelConfig.LANGUAGE_RU
            : ViewModelConfig.LANGUAGE_EN;
        this.markDirty();
    }

    public boolean isEditorActive() {
        return this.editorActive;
    }

    public void setEditorActive(boolean active) {
        this.editorActive = active;
        if (!active) {
            this.handBounds.clear();
        }
    }

    public void updateHandBounds(EditorTarget target, HandBounds bounds) {
        if (target != null && bounds != null) {
            this.handBounds.put(target, bounds);
        }
    }

    public HandBounds getHandBounds(EditorTarget target) {
        HandBounds bounds = this.handBounds.get(target);
        return bounds != null && bounds.isFresh() ? bounds : null;
    }

    public void markDirty() {
        this.dirty = true;
        this.lastDirtyAt = System.currentTimeMillis();
    }
}
