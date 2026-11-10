package dev.viewmodelmod.editor;

import dev.viewmodelmod.ViewModelMOD;
import dev.viewmodelmod.ViewModelManager;
import dev.viewmodelmod.config.HighlightSettings;
import dev.viewmodelmod.config.ViewModelConfig;
import dev.viewmodelmod.config.ViewModelConfigEntry;
import dev.viewmodelmod.config.ViewModelTransform;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Arm;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import net.minecraft.util.math.MathHelper;
import org.lwjgl.glfw.GLFW;

import java.net.URI;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Immediate-mode editor drawn on top of the vanilla chat screen. The layout
 * follows the WexSide click GUI: a rounded panel with a left navigation
 * sidebar, a slim header and a content area.
 *
 * <p>All positions are in scaled GUI coordinates, the same space used by mouse
 * events, so the panel behaves correctly with every GUI scale.</p>
 */
public final class ViewModelEditorOverlay {
    private static final int PANEL_MAX_W = 440;
    private static final int PANEL_MAX_H = 272;
    private static final int PANEL_RADIUS = 10;
    private static final int NAV_RADIUS = 6;

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    /** Bundled SF Pro Display Medium (the WexSide UI font), used only by the editor. */
    private static final Identifier EDITOR_FONT_ID = Identifier.of(ViewModelMOD.MOD_ID, "sf_pro_display");
    private static final Style EDITOR_STYLE = Style.EMPTY.withFont(EDITOR_FONT_ID);

    private static final Map<Screen, ViewModelEditorOverlay> OVERLAYS = new WeakHashMap<>();
    private static ViewModelEditorOverlay active;

    private final MinecraftClient client;
    private final Screen screen;
    private final ViewModelManager manager;
    private final MouseDragHandler handDrag = new MouseDragHandler();

    private final List<NavEntry> navEntries = new ArrayList<>();
    private final List<Button> buttons = new ArrayList<>();
    private final List<Slider> sliders = new ArrayList<>();
    private final List<ConfigRow> configRows = new ArrayList<>();
    private final List<Label> labels = new ArrayList<>();
    private final Map<Object, Float> hoverAmounts = new HashMap<>();

    private long frameNanos;
    private float frameDelta = 0.016F;

    private boolean panelVisible = true;
    private Tab tab = Tab.POSITION;
    private EditMode editMode = EditMode.MOVE;
    private DragPlane plane = DragPlane.XY;
    private TextEdit editing;
    private Field sliderDrag;
    private boolean handDragging;
    private String status;
    private long statusUntil;
    private boolean compact;

    /** Header drag state for moving the panel around the screen. */
    private boolean panelDragging;
    private double panelDragMouseX;
    private double panelDragMouseY;
    private float panelDragStartX;
    private float panelDragStartY;

    /** 1 -> 0 slide-in animation after a tab switch. */
    private float contentSlide;
    private float contentSlideDirection = 1.0F;
    private final Map<Object, Float> flashAmounts = new HashMap<>();

    /** 0 -> 1 open animation, scaled and lerped like WexSide's panels. */
    private float openAmount;
    private int scaledWidth;
    private int scaledHeight;

    /** Temporary self-test: opens Configs and clicks the first row. */
    private static final boolean DEBUG_CLICKS = System.getenv("VMM_DEBUG") != null;
    private int debugFrames;

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int sidebarW;
    private int headerH;
    private int contentX;
    private int contentY;
    private int contentW;
    private int contentBottom;
    private int rowH;
    private int rowGap;
    private int buttonH;

    private int configScroll;
    private int configListX;
    private int configListY;
    private int configListW;
    private int configListH;
    private int configRowH;

    private ViewModelEditorOverlay(MinecraftClient client, Screen screen) {
        this.client = client;
        this.screen = screen;
        this.manager = ViewModelMOD.getManager();
    }

    /** Registers the chat-screen hook once, during client initialisation. */
    public static void registerEvents() {
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (!(screen instanceof ChatScreen)) {
                return;
            }

            ViewModelEditorOverlay overlay = OVERLAYS.get(screen);
            if (overlay == null) {
                overlay = new ViewModelEditorOverlay(client, screen);
                OVERLAYS.put(screen, overlay);
            }
            // Fabric rebuilds every screen event instance during init/resize, so
            // the callbacks have to be registered again for the new instances.
            overlay.registerScreenEvents();
            overlay.onScreenInit();
        });
    }

    /** Called by the keyboard mixin so non-ASCII text also works in value fields. */
    public static boolean handleGlobalCharTyped(int codePoint) {
        ViewModelEditorOverlay overlay = active;
        return overlay != null && overlay.handleCharTyped(codePoint);
    }

    private void registerScreenEvents() {
        ScreenEvents.afterRender(this.screen).register(this::render);
        ScreenEvents.remove(this.screen).register(ignored -> this.close());
        ScreenMouseEvents.allowMouseClick(this.screen).register(this::onMouseClick);
        ScreenMouseEvents.allowMouseRelease(this.screen).register(this::onMouseRelease);
        ScreenMouseEvents.allowMouseScroll(this.screen).register(this::onMouseScroll);
        ScreenKeyboardEvents.allowKeyPress(this.screen).register(this::onKeyPress);
    }

    private void onScreenInit() {
        active = this;
        if (this.manager != null) {
            // A resize must not silently re-enable a panel the user closed.
            this.manager.setEditorActive(this.panelVisible);
        }
    }

    private void close() {
        this.cancelEditing();
        this.endPointerDrags();
        if (this.manager != null) {
            this.manager.setEditorActive(false);
            this.manager.saveNow();
        }
        OVERLAYS.remove(this.screen);
        if (active == this) {
            active = null;
        }
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    private void render(Screen ignored, DrawContext context, int mouseX, int mouseY, float delta) {
        if (this.manager == null) {
            return;
        }

        this.tickPointerDrags(mouseX, mouseY);
        this.updateStatus();

        long now = System.nanoTime();
        this.frameDelta = this.frameNanos == 0L ? 0.016F : Math.min(0.1F, (now - this.frameNanos) / 1.0E9F);
        this.frameNanos = now;
        if (this.contentSlide > 0.0F) {
            this.contentSlide = Math.max(0.0F, this.contentSlide - this.frameDelta * 6.5F);
        }
        // WexSide opens its panels at 15 and closes them faster at 30.
        this.openAmount = this.lerpTowards(this.openAmount, this.panelVisible ? 1.0F : 0.0F,
            this.panelVisible ? 15.0F : 30.0F);
        if (!this.flashAmounts.isEmpty()) {
            this.flashAmounts.replaceAll((key, value) -> value - this.frameDelta * 3.2F);
            this.flashAmounts.values().removeIf(value -> value <= 0.0F);
        }

        this.layout();
        if (this.manager.isEditorActive()) {
            this.drawHandHighlight(context);
        }
        if (DEBUG_CLICKS) {
            this.debugTick();
        }
        if (this.openAmount > 0.02F) {
            this.drawPanel(context, mouseX, mouseY);
        }
    }

    private void drawPanel(DrawContext context, int mouseX, int mouseY) {
        Palette p = this.palette();

        float open = easeOutCubic(MathHelper.clamp(this.openAmount, 0.0F, 1.0F));
        // WexSide fades the panel in over a dimmed background.
        context.fill(0, 0, this.scaledWidth, this.scaledHeight,
            withAlpha(0xFF000000, Math.round(0x30 * open)));
        if (open < 0.999F) {
            context.getMatrices().push();
            float scale = 0.94F + 0.06F * open;
            float centerX = this.panelX + this.panelW / 2.0F;
            float centerY = this.panelY + this.panelH / 2.0F;
            context.getMatrices().translate(centerX, centerY, 0.0F);
            context.getMatrices().scale(scale, scale, 1.0F);
            context.getMatrices().translate(-centerX, -centerY, 0.0F);
        }

        fillRounded(context, this.panelX + 2, this.panelY + 4, this.panelW, this.panelH, PANEL_RADIUS, 0x44000000);
        fillRounded(context, this.panelX, this.panelY, this.panelW, this.panelH, PANEL_RADIUS, p.borderSoft());
        fillRounded(context, this.panelX + 1, this.panelY + 1, this.panelW - 2, this.panelH - 2,
            PANEL_RADIUS - 1, p.backgroundPrimary());
        fillRoundedCorners(context, this.panelX + 1, this.panelY + 1, this.sidebarW, this.panelH - 2,
            PANEL_RADIUS - 1, 0, 0, PANEL_RADIUS - 1, p.backgroundSecondary());

        int dividerX = this.panelX + this.sidebarW;
        int dividerY = this.panelY + this.headerH;
        context.fill(dividerX, this.panelY, dividerX + 1, this.panelY + this.panelH, p.borderPrimary());
        context.fill(dividerX, dividerY, this.panelX + this.panelW, dividerY + 1, p.borderPrimary());
        context.fill(this.panelX + 8, dividerY - 6, this.panelX + this.sidebarW - 8, dividerY - 5, withAlpha(p.accent(), 0x66));

        this.drawNav(context, mouseX, mouseY, p);

        for (Label label : this.labels) {
            if (label.section) {
                context.fill(label.x, label.y + 1, label.x + 2, label.y + 8, p.accent());
                this.drawText(context, label.text, label.x + 7, label.y, label.color);
            } else {
                this.drawText(context, label.text, label.x, label.y, label.color);
            }
        }

        for (ConfigRow row : this.configRows) {
            this.drawConfigRow(context, row, mouseX, mouseY, p);
        }

        for (Button button : this.buttons) {
            this.drawButton(context, button, mouseX, mouseY, p);
        }

        for (Slider slider : this.sliders) {
            this.drawSlider(context, slider, mouseX, mouseY, p);
        }

        this.drawFooter(context, p);

        if (open < 0.999F) {
            context.getMatrices().pop();
        }
    }

    private void drawNav(DrawContext context, int mouseX, int mouseY, Palette p) {
        for (NavEntry entry : this.navEntries) {
            boolean hovered = this.panelVisible && entry.contains(mouseX, mouseY);
            float hover = this.animate(entry.tab, hovered);
            boolean selected = entry.tab == this.tab;
            // WexSide slides an accent marker in instead of popping it.
            float active = this.animate("active:" + entry.tab.name(), selected);

            if (active > 0.01F) {
                drawRoundedBox(context, entry.x, entry.y, entry.w, entry.h,
                    NAV_RADIUS,
                    mix(p.backgroundSecondary(), p.borderSubtle(), active),
                    withAlpha(p.borderPrimary(), Math.round(0xFF * active)));
                int barHeight = Math.round((entry.h - 8) * active);
                int barY = entry.y + (entry.h - barHeight) / 2;
                context.fill(entry.x + 2, barY, entry.x + 3, barY + barHeight,
                    withAlpha(p.accent(), Math.round(0xFF * active)));
            } else if (hover > 0.01F) {
                drawRoundedBox(context, entry.x, entry.y, entry.w, entry.h, NAV_RADIUS,
                    mix(p.backgroundSecondary(), p.backgroundHover(), hover),
                    mix(p.backgroundSecondary(), p.borderSoft(), hover * 0.8F));
            }

            int iconColor = mix(mix(p.textMuted(), p.textSecondary(), hover), p.accent(),
                Math.max(active, hover * 0.25F));
            drawNavIcon(context, entry.tab, entry.x + 7, entry.y + (entry.h - 8) / 2, iconColor);

            int textColor = mix(mix(p.textSecondary(), p.textPrimary(), hover), p.textPrimary(), active);
            this.drawText(context, this.navLabel(entry.tab),
                entry.x + 19, entry.y + (entry.h - 8) / 2, textColor);
        }
    }

    private void drawNavIcon(DrawContext context, Tab tab, int x, int y, int color) {
        switch (tab) {
            case POSITION -> {
                context.fill(x + 3, y, x + 4, y + 8, color);
                context.fill(x, y + 3, x + 8, y + 4, color);
            }
            case GLOW -> {
                context.fill(x + 3, y, x + 5, y + 1, color);
                context.fill(x + 2, y + 1, x + 6, y + 2, color);
                context.fill(x + 1, y + 2, x + 7, y + 4, color);
                context.fill(x + 2, y + 4, x + 6, y + 5, color);
                context.fill(x + 3, y + 5, x + 5, y + 6, color);
            }
            case CONFIGS -> {
                context.fill(x, y + 1, x + 8, y + 2, color);
                context.fill(x, y + 4, x + 8, y + 5, color);
                context.fill(x, y + 7, x + 8, y + 8, color);
            }
            case SETTINGS -> {
                context.fill(x, y, x + 2, y + 2, color);
                context.fill(x + 6, y, x + 8, y + 2, color);
                context.fill(x, y + 6, x + 2, y + 8, color);
                context.fill(x + 6, y + 6, x + 8, y + 8, color);
                context.fill(x + 3, y + 3, x + 5, y + 5, color);
            }
        }
    }

    private void drawButton(DrawContext context, Button button, int mouseX, int mouseY, Palette p) {
        boolean hovered = this.panelVisible && button.enabled && button.contains(mouseX, mouseY);
        float hover = this.animate(button.action.name() + ":" + button.data, hovered);
        float flash = this.flashAmounts.getOrDefault(button.action.name() + ":" + button.data, 0.0F);

        int fill;
        int border;
        int textColor;
        if (!button.enabled) {
            fill = mix(p.backgroundPrimary(), p.controlFill(), 0.45F);
            border = p.borderSoft();
            textColor = p.textDisabled();
        } else if (button.variant == VARIANT_DANGER) {
            fill = mix(p.controlFill(), p.danger(), 0.16F + 0.08F * hover);
            border = mix(p.borderSoft(), p.danger(), 0.65F + 0.35F * hover);
            textColor = p.textPrimary();
        } else if (button.variant == VARIANT_GHOST) {
            fill = mix(p.backgroundPrimary(), p.backgroundHover(), hover);
            border = mix(p.backgroundPrimary(), p.borderSoft(), hover);
            textColor = mix(p.textSecondary(), p.textPrimary(), hover);
        } else if (button.active) {
            fill = mix(p.controlFill(), p.accent(), 0.22F + 0.06F * hover);
            fill = mix(fill, p.accent(), flash * 0.35F);
            border = mix(mix(p.accent(), p.borderStrong(), 0.25F), p.textPrimary(), flash * 0.55F);
            textColor = p.textPrimary();
        } else {
            fill = mix(p.controlFill(), p.backgroundHover(), hover);
            fill = mix(fill, p.accent(), flash * 0.18F);
            border = mix(p.borderSoft(), p.accent(), hover * 0.55F + flash * 0.45F);
            textColor = mix(p.textSecondary(), p.textPrimary(), hover);
        }

        drawRoundedBox(context, button.x, button.y, button.w, button.h, 5, fill, border);

        if (button.action == Action.EDIT_HEX) {
            HighlightSettings settings = this.manager.highlight();
            int swatch = 0xFF000000 | (settings.color & 0xFFFFFF);
            int swatchX = button.x + 7;
            int swatchY = button.y + (button.h - 9) / 2;
            drawRoundedBox(context, swatchX, swatchY, 9, 9, 2, swatch, 0xFF000000);
            String text = this.editing != null && this.editing.kind == EditKind.HEX
                ? "HEX  " + this.editing.display()
                : String.format("HEX  #%06X", settings.color & 0xFFFFFF);
            this.drawText(context, text, swatchX + 14, button.y + (button.h - 8) / 2, textColor);
        } else {
            String label = button.label;
            if (button.action == Action.CONFIG_RENAME && this.editing != null && this.editing.kind == EditKind.NAME) {
                label = this.editing.display();
            }
            this.drawCenteredText(context, label, button.x, button.y, button.w, button.h, textColor);
        }
    }

    private void drawSlider(DrawContext context, Slider slider, int mouseX, int mouseY, Palette p) {
        int centerY = slider.y + slider.h / 2;
        float value = this.value(slider.field);
        float normalized = MathHelper.clamp((value - slider.min) / (slider.max - slider.min), 0.0F, 1.0F);

        boolean editingThis = this.editing != null
            && this.editing.kind == EditKind.NUMERIC
            && this.editing.field == slider.field;
        boolean hoveredValue = mouseX >= slider.valueX && mouseX < slider.valueX + slider.valueW
            && mouseY >= slider.y && mouseY < slider.y + slider.h;
        boolean hoveredTrack = mouseX >= slider.trackX && mouseX < slider.trackX + slider.trackW
            && mouseY >= slider.y && mouseY < slider.y + slider.h;
        float hover = this.animate(slider.field, hoveredTrack || hoveredValue || this.sliderDrag == slider.field);

        this.drawText(context, slider.label, slider.x, centerY - 4,
            mix(p.textSecondary(), p.textPrimary(), hover));

        fillRounded(context, slider.trackX, centerY - 2, slider.trackW, 4, 2, p.backgroundControl());
        int filled = Math.round(slider.trackW * normalized);
        if (filled > 0) {
            fillRounded(context, slider.trackX, centerY - 2, Math.max(4, filled), 4, 2,
                mix(p.accent(), p.textPrimary(), hover * 0.15F));
        }

        int knobX = slider.trackX + filled;
        fillRounded(context, knobX - 5, centerY - 5, 10, 10, 5, withAlpha(p.accent(), 0x66));
        fillRounded(context, knobX - 2, centerY - 2, 5, 5, 3, p.textPrimary());

        drawRoundedBox(context, slider.valueX, slider.y + 1, slider.valueW, slider.h - 2, 4,
            p.formatFieldFill(), mix(p.borderSoft(), p.accent(), hover));

        String text = editingThis ? this.editing.display() : this.formatValue(slider.field, value);
        this.drawCenteredText(context, text, slider.valueX, slider.y + 1, slider.valueW, slider.h - 2,
            editingThis ? p.accent() : p.textPrimary());
    }

    private void drawConfigRow(DrawContext context, ConfigRow row, int mouseX, int mouseY, Palette p) {
        List<ViewModelConfigEntry> configs = this.manager.configs();
        if (row.index < 0 || row.index >= configs.size()) {
            return;
        }
        ViewModelConfigEntry entry = configs.get(row.index);
        boolean selected = row.index == this.manager.activeConfigIndex();
        boolean hovered = this.panelVisible && row.contains(mouseX, mouseY);
        float hover = this.animate(entry, hovered);

        int fill = selected ? p.accentTint() : mix(p.backgroundPrimary(), p.backgroundHover(), hover);
        int border = selected ? p.accent() : mix(p.borderSoft(), p.borderStrong(), hover);
        drawRoundedBox(context, row.x, row.y, row.w, row.h, NAV_RADIUS, fill, border);

        if (selected) {
            context.fill(row.x + 3, row.y + 4, row.x + 5, row.y + row.h - 4, p.accent());
        }

        String name = this.fit(entry.name, row.w - 70);
        this.drawText(context, name, row.x + 10, row.y + 4,
            selected ? p.textPrimary() : mix(p.textSecondary(), p.textPrimary(), hover));
        this.drawText(context, this.formatDate(entry.createdAt), row.x + 10,
            row.y + row.h - 10, p.textMuted());

        if (selected) {
            int activeWidth = this.width("ACTIVE");
            int activeX = row.x + row.w - activeWidth - 8;
            if (activeX > row.x + 10 + this.width(name) + 4) {
                this.drawText(context, "ACTIVE",
                    activeX, row.y + (row.h - 8) / 2, p.accent());
            }
        } else if (isEntryEmpty(entry)) {
            // An all-default config looks like the mod "did nothing" when it is
            // selected, so say it out loud.
            String tag = this.tr("EMPTY", "ПУСТО");
            int tagWidth = this.width(tag);
            this.drawText(context, tag, row.x + row.w - tagWidth - 8,
                row.y + (row.h - 8) / 2, p.textMuted());
        }
    }

    /** True when every transform of the entry is still at its default value. */
    private static boolean isEntryEmpty(ViewModelConfigEntry entry) {
        return entry.leftHand.isIdentity() && entry.rightHand.isIdentity()
            && entry.leftItem.isIdentity() && entry.rightItem.isIdentity();
    }

    private void drawFooter(DrawContext context, Palette p) {
        int lineY = this.panelY + this.panelH - 22;
        int left = this.panelX + this.sidebarW + 12;
        int right = this.panelX + this.panelW - 12;
        context.fill(left, lineY, right, lineY + 1, p.separator());

        String hint = this.status != null
            ? this.status
            : this.compact
                ? this.tr("Wheel = size  ·  F6 hide", "Колесо = размер  ·  F6 скрыть")
                : this.tr("Drag LMB  ·  Wheel = size  ·  F6 hide",
                          "ЛКМ – тянуть  ·  колесо = размер  ·  F6 – скрыть");
        // The compact footer also has to fit the config counter, so the hint is
        // shorter there instead of being clipped.
        String fitted = this.fit(hint, Math.max(30, (right - left) - (this.compact ? 66 : 92)));
        this.drawText(context, fitted, left, lineY + 6,
            this.status != null ? p.accent() : p.textMuted());

        ViewModelConfigEntry active = this.manager.activeConfig();
        String config = (this.manager.activeConfigIndex() + 1) + "/" + this.manager.configs().size()
            + "  " + active.name;
        String fittedConfig = this.fit(config, 88);
        int width = this.width(fittedConfig);
        this.drawText(context, fittedConfig, right - width, lineY + 6, p.textSecondary());
    }

    private void drawHandHighlight(DrawContext context) {
        HighlightSettings settings = this.manager.highlight();
        if (!settings.enabled) {
            return;
        }

        EditorTarget shown = this.visibleTarget();
        HandBounds bounds = this.manager.getHandBounds(shown);
        if (bounds == null) {
            return;
        }

        int base = settings.argbColor();
        int alpha = base >>> 24;
        if (alpha <= 0) {
            return;
        }
        int rgb = base & 0xFFFFFF;

        float pulse = 0.80F + 0.20F * MathHelper.sin((float) (System.nanoTime() / 1.0E9) * 3.1F);
        int lineColor = (MathHelper.clamp(Math.round(alpha * pulse), 1, 255) << 24) | rgb;
        int softColor = (Math.max(6, Math.round(alpha * 0.24F * pulse)) << 24) | rgb;
        int haloColor = (Math.max(4, Math.round(alpha * 0.10F * pulse)) << 24) | rgb;

        float[] hull = bounds.hull;
        if (hull != null && hull.length >= 6) {
            // Soft halo band and a crisp silhouette line on the same contour.
            this.drawGlowOutline(context, hull, 11.0F, haloColor);
            this.drawGlowOutline(context, hull, 6.0F, softColor);
            this.drawGlowOutline(context, hull, 2.5F, lineColor);
        } else {
            int x1 = Math.round(bounds.minX);
            int y1 = Math.round(bounds.minY);
            int x2 = Math.round(bounds.maxX);
            int y2 = Math.round(bounds.maxY);
            int width = Math.max(1, x2 - x1);
            int height = Math.max(1, y2 - y1);
            drawRoundedOutline(context, x1 - 4, y1 - 4, width + 8, height + 8, 8, haloColor);
            drawRoundedOutline(context, x1 - 2, y1 - 2, width + 4, height + 4, 7, softColor);
            drawRoundedOutline(context, x1, y1, width, height, 6, lineColor);
        }

        ViewModelTransform transform = shown.transform(this.manager.activeConfig());
        String labelName = this.compact ? this.shortName(shown) : shown.displayName();
        String label = labelName + "   " + String.format(Locale.ROOT, "%.2f", transform.scale) + "x";
        int labelWidth = this.width(label) + 12;
        int screenWidth = this.client.getWindow().getScaledWidth();
        int maxX = Math.max(2, screenWidth - labelWidth - 2);
        int minX = 2;
        if (this.panelVisible) {
            int panelRight = this.panelX + this.panelW + 4;
            if (panelRight < maxX) {
                minX = panelRight;
            }
        }
        int labelX = MathHelper.clamp(Math.round(bounds.centerX()) - labelWidth / 2, minX, maxX);
        int labelY = Math.max(2, Math.round(bounds.minY) - 15);
        drawRoundedBox(context, labelX, labelY, labelWidth, 13, 4,
            0xE6101216,
            (Math.max(48, Math.round(alpha * 0.55F)) << 24) | rgb);
        this.drawText(context, label, labelX + 6, labelY + 3, lineColor);
    }

    private void drawGlowOutline(DrawContext context, float[] hull, float thickness, int color) {
        int alpha = color >>> 24;
        int count = hull.length / 2;
        if (alpha <= 0 || thickness <= 0.0F || count < 3) {
            return;
        }

        // Flush the batched GUI geometry first, then emit the whole contour as
        // one triangle batch. Per-pixel fills would cost thousands of draw
        // calls for a single outline.
        context.draw();
        RenderSystem.setShader(GameRenderer::getPositionColorProgram);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.disableDepthTest();

        float half = thickness * 0.5F;
        BufferBuilder buffer = Tessellator.getInstance().begin(
            VertexFormat.DrawMode.TRIANGLES, VertexFormats.POSITION_COLOR);
        for (int i = 0; i < count; i++) {
            int next = (i + 1) % count;
            float x1 = hull[i * 2];
            float y1 = hull[i * 2 + 1];
            float x2 = hull[next * 2];
            float y2 = hull[next * 2 + 1];
            float dx = x2 - x1;
            float dy = y2 - y1;
            float length = MathHelper.sqrt(dx * dx + dy * dy);
            if (length < 0.0001F) {
                continue;
            }
            float nx = -dy / length * half;
            float ny = dx / length * half;
            buffer.vertex(x1 + nx, y1 + ny, 0.0F).color(color);
            buffer.vertex(x1 - nx, y1 - ny, 0.0F).color(color);
            buffer.vertex(x2 - nx, y2 - ny, 0.0F).color(color);
            buffer.vertex(x1 + nx, y1 + ny, 0.0F).color(color);
            buffer.vertex(x2 - nx, y2 - ny, 0.0F).color(color);
            buffer.vertex(x2 + nx, y2 + ny, 0.0F).color(color);
        }
        BufferRenderer.drawWithGlobalProgram(buffer.end());

        RenderSystem.enableCull();
        RenderSystem.enableDepthTest();
        context.draw();
    }

    private void drawCenteredText(DrawContext context, String text, int x, int y, int width, int height, int color) {
        String fitted = this.fit(text, width - 4);
        int textWidth = this.width(fitted);
        this.drawText(context, fitted,
            x + Math.max(2, (width - textWidth) / 2), y + Math.max(1, (height - 8) / 2), color);
    }

    // ------------------------------------------------------------------
    // Layout
    // ------------------------------------------------------------------

    private void layout() {
        int screenWidth = this.client.getWindow().getScaledWidth();
        int screenHeight = this.client.getWindow().getScaledHeight();
        this.scaledWidth = screenWidth;
        this.scaledHeight = screenHeight;
        this.compact = screenHeight < 300 || screenWidth < 420;

        this.panelW = Math.min(this.compact ? 336 : PANEL_MAX_W, screenWidth - 10);
        this.panelH = Math.min(this.compact ? 249 : PANEL_MAX_H, screenHeight - 10);
        // In compact mode the panel hugs the left edge so the first-person hand
        // on the bottom right stays visible while editing.
        int defaultX = this.compact ? 6 : (screenWidth - this.panelW) / 2;
        int defaultY = (screenHeight - this.panelH) / 2;
        ViewModelConfig config = this.manager.config();
        this.panelX = MathHelper.clamp(defaultX + Math.round(config.panelOffsetX),
            2, Math.max(2, screenWidth - this.panelW - 2));
        this.panelY = MathHelper.clamp(defaultY + Math.round(config.panelOffsetY),
            2, Math.max(2, screenHeight - this.panelH - 2));

        this.sidebarW = this.compact ? 82 : 92;
        this.headerH = this.compact ? 19 : 24;
        this.contentX = this.panelX + this.sidebarW + 12;
        this.contentY = this.panelY + this.headerH + 9;
        this.contentW = this.panelW - this.sidebarW - 24;
        this.contentBottom = this.panelY + this.panelH - 26;
        this.rowH = this.compact ? 12 : 17;
        this.rowGap = 2;
        this.buttonH = this.compact ? 14 : 19;

        this.navEntries.clear();
        this.buttons.clear();
        this.sliders.clear();
        this.configRows.clear();
        this.labels.clear();

        this.addChrome();
        // Slide the body in from the direction of the newly selected tab. The
        // header and sidebar stay put, so dragging the panel is unaffected.
        if (this.contentSlide > 0.0F) {
            float remaining = 1.0F - this.contentSlide;
            float eased = 1.0F - remaining * remaining;
            this.contentX += Math.round(eased * 14.0F * this.contentSlideDirection);
            this.contentY += Math.round(eased * 4.0F);
        }
        switch (this.tab) {
            case POSITION -> this.buildPositionTab();
            case GLOW -> this.buildGlowTab();
            case CONFIGS -> this.buildConfigsTab();
            case SETTINGS -> this.buildSettingsTab();
        }
    }

    private void addChrome() {
        int navX = this.panelX + 7;
        int navW = this.sidebarW - 14;
        int navY = this.panelY + this.headerH + 8;
        int navH = this.compact ? 17 : 19;
        for (Tab value : Tab.values()) {
            this.navEntries.add(new NavEntry(value, navX, navY, navW, navH));
            navY += navH + 2;
        }

        Palette p = this.palette();
        int titleY = this.panelY + (this.headerH - 8) / 2;
        this.addLabel("VIEW", this.panelX + 10, titleY, p.textPrimary(), false);
        int viewWidth = this.width("VIEW");
        this.addLabel("MODEL", this.panelX + 10 + viewWidth, titleY, p.accent(), false);

        this.addLabel("EDITOR", this.contentX, titleY, p.textMuted(), false);
        int editorWidth = this.width("EDITOR");
        this.addLabel("/", this.contentX + editorWidth + 4, titleY, p.textMuted(), false);
        this.addLabel(this.tabLabel(this.tab), this.contentX + editorWidth + 13, titleY, p.textPrimary(), false);

        if (!this.compact) {
            ViewModelConfigEntry active = this.manager.activeConfig();
            String config = this.fit(active.name, Math.max(40, this.contentW - 170));
            int width = this.width(config);
            // The active config name doubles as a shortcut to the Configs tab.
            this.addButton(Action.OPEN_CONFIGS, config,
                this.contentX + this.contentW - 22 - width - 6,
                this.panelY + (this.headerH - 12) / 2, width + 12, 14,
                false, true, VARIANT_GHOST, 0);
        }

        int closeSize = this.compact ? 14 : 16;
        this.addButton(Action.HIDE_UI, "x",
            this.panelX + this.panelW - 8 - closeSize,
            this.panelY + (this.headerH - closeSize) / 2,
            closeSize, closeSize, false, true, VARIANT_GHOST, 0);

        this.addLabel("v1.0", this.panelX + 10, this.panelY + this.panelH - 14, p.textMuted(), false);
    }

    private void buildPositionTab() {
        int sectionH = this.compact ? 10 : 11;
        int sectionGap = this.compact ? 5 : 8;
        int y = this.contentY;

        this.addSection(this.tr("TARGET", "ЦЕЛЬ"), this.contentX, y);
        y += sectionH;
        int segmentGap = 3;
        int segmentW = (this.contentW - segmentGap * 3) / 4;
        EditorTarget selected = this.manager.selectedTarget();
        int x = this.contentX;
        this.addButton(Action.TARGET_LEFT_HAND, "L ARM", x, y, segmentW, this.buttonH, selected == EditorTarget.LEFT_HAND, true, 0, 0);
        x += segmentW + segmentGap;
        this.addButton(Action.TARGET_RIGHT_HAND, "R ARM", x, y, segmentW, this.buttonH, selected == EditorTarget.RIGHT_HAND, true, 0, 0);
        x += segmentW + segmentGap;
        this.addButton(Action.TARGET_LEFT_ITEM, "L ITEM", x, y, segmentW, this.buttonH, selected == EditorTarget.LEFT_ITEM, true, 0, 0);
        x += segmentW + segmentGap;
        this.addButton(Action.TARGET_RIGHT_ITEM, "R ITEM", x, y, this.contentX + this.contentW - x, this.buttonH, selected == EditorTarget.RIGHT_ITEM, true, 0, 0);

        y += this.buttonH + sectionGap;
        this.addSection(this.tr("TRANSFORM", "ТРАНСФОРМАЦИЯ"), this.contentX, y);
        y += sectionH;

        y = this.addSlider(y, Field.X, "X", -1.0F, 1.0F);
        y = this.addSlider(y, Field.Y, "Y", -1.0F, 1.0F);
        y = this.addSlider(y, Field.Z, "Z", -1.0F, 1.0F);
        y = this.addSlider(y, Field.SCALE, "SCALE", 0.2F, 2.8F);
        y = this.addSlider(y, Field.ROT_X, "ROT X", -180.0F, 180.0F);
        y = this.addSlider(y, Field.ROT_Y, "ROT Y", -180.0F, 180.0F);
        y = this.addSlider(y, Field.ROT_Z, "ROT Z", -180.0F, 180.0F);

        y += 2;
        int third = (this.contentW - this.rowGap * 2) / 3;
        this.addButton(Action.MODE_TOGGLE, this.editMode == EditMode.MOVE ? this.tr("MOVE", "ДВИЖ.") : this.tr("ROTATE", "ВРАЩ."),
            this.contentX, y, third, this.buttonH, false, true, 0, 0);
        this.addButton(Action.PLANE_TOGGLE, this.plane.label(),
            this.contentX + third + this.rowGap, y, third, this.buttonH, false, true, 0, 0);
        this.addButton(Action.RESET_TARGET, this.tr("RESET", "СБРОС"),
            this.contentX + (third + this.rowGap) * 2, y, this.contentX + this.contentW - (this.contentX + (third + this.rowGap) * 2),
            this.buttonH, false, true, VARIANT_DANGER, 0);
    }

    private void buildGlowTab() {
        int sectionH = this.compact ? 10 : 11;
        int y = this.contentY;
        HighlightSettings settings = this.manager.highlight();

        this.addSection(this.tr("HIGHLIGHT", "ПОДСВЕТКА"), this.contentX, y);
        y += sectionH;
        this.addButton(Action.GLOW_TOGGLE,
            settings.enabled ? this.tr("OUTLINE: ON", "ОБВОДКА: ВКЛ") : this.tr("OUTLINE: OFF", "ОБВОДКА: ВЫКЛ"),
            this.contentX, y, this.contentW, this.buttonH, settings.enabled, true, 0, 0);
        y += this.buttonH + this.rowGap;
        this.addButton(Action.EDIT_HEX, "HEX", this.contentX, y, this.contentW, this.buttonH, false, true, 0, 0);
        y += this.buttonH + (this.compact ? 3 : 5);

        y = this.addSlider(y, Field.GLOW_R, this.tr("RED", "КРАСН."), 0.0F, 255.0F);
        y = this.addSlider(y, Field.GLOW_G, this.tr("GREEN", "ЗЕЛЁН."), 0.0F, 255.0F);
        y = this.addSlider(y, Field.GLOW_B, this.tr("BLUE", "СИНИЙ"), 0.0F, 255.0F);
        y = this.addSlider(y, Field.GLOW_INTENSITY, this.tr("INTENSITY", "ЯРКОСТЬ"), 0.0F, 2.0F);
        y = this.addSlider(y, Field.GLOW_OPACITY, this.tr("OPACITY", "ПРОЗРАЧН."), 0.0F, 1.0F);

        this.addLabel(this.tr("The outline follows this colour.", "Обводка окрашивается в этот цвет."),
            this.contentX, y + 2, this.palette().textMuted(), false);
    }

    private void buildConfigsTab() {
        int sectionH = this.compact ? 10 : 11;
        int sectionGap = this.compact ? 5 : 8;
        int y = this.contentY;
        ViewModelConfigEntry active = this.manager.activeConfig();
        Palette p = this.palette();

        this.addSection(this.tr("ACTIVE CONFIG", "АКТИВНЫЙ КОНФИГ"), this.contentX, y);
        y += sectionH;

        this.addButton(Action.CONFIG_RENAME, active.name, this.contentX, y, this.contentW, this.buttonH,
            true, true, 0, 0);
        y += this.buttonH + 1;

        this.addLabel(this.tr("Created ", "Создан ") + this.formatDate(active.createdAt),
            this.contentX, y, p.textMuted(), false);
        y += 11;

        int gap = this.rowGap;
        int third = (this.contentW - gap * 2) / 3;
        this.addButton(Action.CONFIG_NEW, this.tr("NEW", "НОВЫЙ"),
            this.contentX, y, third, this.buttonH, false, true, 0, 0);
        this.addButton(Action.CONFIG_SAVE, this.tr("SAVE", "СОХРАНИТЬ"),
            this.contentX + third + gap, y, third, this.buttonH, false, true, 0, 0);
        this.addButton(Action.CONFIG_DELETE, this.tr("DELETE", "УДАЛИТЬ"),
            this.contentX + (third + gap) * 2, y,
            this.contentX + this.contentW - (this.contentX + (third + gap) * 2),
            this.buttonH, false, this.manager.configs().size() > 1, VARIANT_DANGER, 0);
        y += this.buttonH + sectionGap;

        this.addSection(this.tr("SAVED CONFIGS", "СОХРАНЁННЫЕ") + "  " + this.manager.configs().size(),
            this.contentX, y);
        y += sectionH;

        this.configListX = this.contentX;
        this.configListY = y;
        this.configListW = this.contentW;
        this.configListH = Math.max(this.compact ? 20 : 24, this.contentBottom - y);
        this.configRowH = this.compact ? 25 : 27;
        int maxRows = Math.max(1, this.configListH / this.configRowH);
        this.configScroll = Math.max(0, Math.min(this.configScroll, Math.max(0, this.manager.configs().size() - maxRows)));

        int end = Math.min(this.manager.configs().size(), this.configScroll + maxRows);
        int rowY = y;
        for (int i = this.configScroll; i < end; i++) {
            this.configRows.add(new ConfigRow(i, this.configListX, rowY, this.configListW, this.configRowH - 2));
            rowY += this.configRowH;
        }

        if (this.manager.configs().size() > maxRows) {
            String range = (this.configScroll + 1) + "-" + end + " / " + this.manager.configs().size();
            int width = this.width(range);
            this.addLabel(range, this.contentX + this.contentW - width, y - sectionH, p.textMuted(), false);
        }
    }

    private void buildSettingsTab() {
        int sectionH = this.compact ? 10 : 11;
        int sectionGap = this.compact ? 5 : 8;
        int gap = this.rowGap;
        int y = this.contentY;
        Palette p = this.palette();

        this.addSection(this.tr("APPEARANCE", "ВНЕШНИЙ ВИД"), this.contentX, y);
        y += sectionH;

        int labelW = this.compact ? 60 : 74;
        int controlX = this.contentX + labelW;
        int controlW = this.contentW - labelW;
        int third = (controlW - gap * 2) / 3;
        this.addLabel(this.tr("Theme", "Тема"), this.contentX, y + (this.buttonH - 8) / 2, p.textSecondary(), false);
        boolean glass = ViewModelConfig.THEME_GLASS.equals(this.manager.theme());
        boolean dark = ViewModelConfig.THEME_DARK.equals(this.manager.theme());
        this.addButton(Action.THEME_DARK, this.tr("DARK", "ТЁМНАЯ"), controlX, y, third, this.buttonH, dark && !glass, true, 0, 0);
        this.addButton(Action.THEME_LIGHT, this.tr("LIGHT", "СВЕТЛАЯ"), controlX + third + gap, y, third, this.buttonH, !dark && !glass, true, 0, 0);
        this.addButton(Action.THEME_GLASS, "GLASS", controlX + (third + gap) * 2, y, controlX + controlW - (controlX + (third + gap) * 2), this.buttonH, glass, true, 0, 0);
        y += this.buttonH + sectionGap;

        this.addSection(this.tr("LANGUAGE", "ЯЗЫК"), this.contentX, y);
        y += sectionH;
        boolean russian = ViewModelConfig.LANGUAGE_RU.equals(this.manager.language());
        int half = (controlW - gap) / 2;
        this.addButton(Action.LANG_EN, "ENGLISH", controlX, y, half, this.buttonH, !russian, true, 0, 0);
        this.addButton(Action.LANG_RU, "РУССКИЙ", controlX + half + gap, y,
            controlX + controlW - (controlX + half + gap), this.buttonH, russian, true, 0, 0);
        y += this.buttonH + sectionGap;

        this.addSection(this.tr("CONTROLS", "УПРАВЛЕНИЕ"), this.contentX, y);
        y += sectionH;
        y = this.addSlider(y, Field.SENSITIVITY, this.tr("DRAG", "ТЯГА"), 0.25F, 3.0F);
        y += sectionGap - this.rowGap;

        this.addSection(this.tr("LINKS", "ССЫЛКИ"), this.contentX, y);
        y += sectionH;
        this.addButton(Action.OPEN_GITHUB, "GITHUB  ·  emosteamer-bit",
            this.contentX, y, this.contentW, this.buttonH, false, true, 0, 0);
        y += this.buttonH + (this.compact ? 5 : 8);

        this.addLabel("ViewModelMOD  ·  Fabric 1.21.1", this.contentX, y, p.textMuted(), false);
        y += 13;

        int halfButton = (this.contentW - gap) / 2;
        this.addButton(Action.LOAD_DEFAULTS, this.tr("DEFAULTS", "ПО УМОЛЧАНИЮ"),
            this.contentX, y, halfButton, this.buttonH, false, true, 0, 0);
        this.addButton(Action.RESET_ALL, this.tr("RESET ALL", "СБРОС ВСЕГО"),
            this.contentX + halfButton + gap, y,
            this.contentX + this.contentW - (this.contentX + halfButton + gap),
            this.buttonH, false, true, VARIANT_DANGER, 0);
    }

    private int addSlider(int y, Field field, String label, float min, float max) {
        int labelW = this.compact ? 42 : 52;
        int valueW = this.compact ? 40 : 46;
        int trackX = this.contentX + labelW + 4;
        int trackW = Math.max(24, this.contentW - labelW - valueW - 8);
        int valueX = trackX + trackW + 4;
        this.sliders.add(new Slider(field, label, min, max, this.contentX, y, this.contentW, this.rowH,
            trackX, trackW, valueX, valueW));
        return y + this.rowH + this.rowGap;
    }

    private void addSection(String text, int x, int y) {
        this.labels.add(new Label(text, x, y, this.palette().textMuted(), true));
    }

    private void addLabel(String text, int x, int y, int color, boolean section) {
        this.labels.add(new Label(text, x, y, color, section));
    }

    private Button addButton(Action action, String label, int x, int y, int w, int h,
                             boolean active, boolean enabled, int variant, int data) {
        Button button = new Button(action, label, x, y, w, h, active, enabled, variant, data);
        this.buttons.add(button);
        return button;
    }

    // ------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------

    private boolean onMouseClick(Screen ignored, double mouseX, double mouseY, int button) {
        if (this.manager == null) {
            return true;
        }

        this.layout();
        this.commitEditing();
        boolean insidePanel = this.panelVisible && this.isInsidePanel(mouseX, mouseY);
        ViewModelMOD.LOGGER.info("[vmclick] x={} y={} btn={} tab={} inside={} panel={},{} {}x{} rows={}",
            mouseX, mouseY, button, this.tab, insidePanel,
            this.panelX, this.panelY, this.panelW, this.panelH, this.configRows);

        if (insidePanel) {
            if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                this.handlePanelClick(mouseX, mouseY);
            }
            return false;
        }

        if (!this.manager.isEditorActive()) {
            return true;
        }

        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            EditorTarget target = this.pickTarget(mouseX, mouseY);
            if (target != null) {
                this.manager.selectTarget(target);
                this.handDrag.begin(target, target.transform(this.manager.activeConfig()), mouseX, mouseY);
                this.handDragging = true;
                return false;
            }
        } else if (button == GLFW.GLFW_MOUSE_BUTTON_MIDDLE) {
            this.manager.resetCurrent();
            this.setStatus(this.tr("Reset ", "Сброшено: ") + this.manager.selectedTarget().displayName());
            return false;
        } else if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            if (this.pickTarget(mouseX, mouseY) != null) {
                this.editMode = this.editMode.next();
                this.setStatus(this.tr("Mode: ", "Режим: ") + this.editMode.label());
                return false;
            }
        }

        return true;
    }

    private boolean onMouseRelease(Screen ignored, double mouseX, double mouseY, int button) {
        if (this.panelDragging) {
            this.panelDragging = false;
            return false;
        }
        if (this.handDragging) {
            this.handDrag.end();
            this.handDragging = false;
            return false;
        }
        if (this.sliderDrag != null) {
            this.sliderDrag = null;
            return false;
        }
        return true;
    }

    private boolean onMouseScroll(Screen ignored, double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (this.manager == null) {
            return true;
        }

        this.layout();
        if (this.panelVisible && this.isInsidePanel(mouseX, mouseY)) {
            if (this.tab == Tab.CONFIGS && this.isInsideConfigList(mouseX, mouseY) && verticalAmount != 0.0) {
                int maxRows = Math.max(1, this.configListH / this.configRowH);
                int maxScroll = Math.max(0, this.manager.configs().size() - maxRows);
                this.configScroll = MathHelper.clamp(this.configScroll - (int) Math.signum(verticalAmount), 0, maxScroll);
                return false;
            }

            Slider hovered = this.sliderAt(mouseX, mouseY);
            if (hovered != null && verticalAmount != 0.0) {
                float step = (hovered.max - hovered.min) * 0.02F * (float) Math.signum(verticalAmount);
                this.setSliderValue(hovered, this.value(hovered.field) + step);
            }
            return false;
        }

        if (verticalAmount == 0.0 || !this.manager.isEditorActive()) {
            return true;
        }

        // Outside the panel: the wheel resizes the arm / item under the cursor.
        EditorTarget target = this.pickTarget(mouseX, mouseY);
        if (target == null) {
            return true;
        }

        this.manager.selectTarget(target);
        ViewModelTransform transform = target.transform(this.manager.activeConfig());
        float step = Math.max(0.02F, transform.scale * 0.06F) * (float) Math.signum(verticalAmount);
        transform.scale = MathHelper.clamp(transform.scale + step, 0.2F, 2.8F);
        this.manager.markDirty();
        this.setStatus((target.isItem() ? this.tr("Item", "Предмет") : this.tr("Arm", "Рука"))
            + " scale " + String.format(Locale.ROOT, "%.2f", transform.scale));
        return false;
    }

    private boolean onKeyPress(Screen ignored, int keyCode, int scanCode, int modifiers) {
        if (this.manager == null) {
            return true;
        }

        if (keyCode == GLFW.GLFW_KEY_F6) {
            this.togglePanel();
            return false;
        }

        if (this.editing != null) {
            this.handleEditingKey(keyCode);
            return false;
        }

        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            if (this.panelVisible) {
                this.panelVisible = false;
                this.endPointerDrags();
                this.manager.setEditorActive(false);
                this.manager.saveNow();
                return false;
            }
            return true;
        }

        return true;
    }

    private void debugTick() {
        this.debugFrames++;
        if (this.debugFrames == 30) {
            this.switchTab(Tab.CONFIGS);
            ViewModelMOD.LOGGER.info("[vmdtest] switched to CONFIGS");
        } else if (this.debugFrames == 60) {
            this.layout();
            ViewModelMOD.LOGGER.info("[vmdtest] rows={} list={},{} {}x{} panel={},{} {}x{}",
                this.configRows.size(), this.configListX, this.configListY,
                this.configListW, this.configListH, this.panelX, this.panelY,
                this.panelW, this.panelH);
            if (this.configRows.isEmpty()) {
                ViewModelMOD.LOGGER.info("[vmdtest] NO ROWS tab={}", this.tab);
            } else {
                ConfigRow row = this.configRows.get(0);
                ViewModelMOD.LOGGER.info("[vmdtest] clicking row {} at {},{} {}x{}",
                    row.index, row.x + row.w / 2, row.y + row.h / 2, row.w, row.h);
                this.handlePanelClick(row.x + row.w / 2.0, row.y + row.h / 2.0);
                ViewModelMOD.LOGGER.info("[vmdtest] after click: active={} name={} rhScale={}",
                    this.manager.activeConfigIndex(), this.manager.activeConfig().name,
                    this.manager.activeConfig().rightHand.scale);
            }
        }
    }

    private void handlePanelClick(double mouseX, double mouseY) {
        // The header drags the whole panel. The close button lives there too,
        // so buttons win over the drag area.
        if (mouseY >= this.panelY && mouseY < this.panelY + this.headerH
            && mouseX >= this.panelX && mouseX < this.panelX + this.panelW) {
            for (Button button : this.buttons) {
                if (button.contains(mouseX, mouseY)) {
                    ViewModelMOD.LOGGER.info("[vmclick] header button {}", button.action);
                    if (button.enabled) {
                        this.clickButton(button);
                    }
                    return;
                }
            }
            ViewModelMOD.LOGGER.info("[vmclick] header drag");
            this.beginPanelDrag(mouseX, mouseY);
            return;
        }

        for (NavEntry entry : this.navEntries) {
            if (entry.contains(mouseX, mouseY)) {
                ViewModelMOD.LOGGER.info("[vmclick] nav {}", entry.tab);
                this.switchTab(entry.tab);
                return;
            }
        }

        for (Slider slider : this.sliders) {
            if (mouseY < slider.y || mouseY >= slider.y + slider.h) {
                continue;
            }
            if (mouseX >= slider.valueX && mouseX < slider.valueX + slider.valueW) {
                this.beginNumericEditing(slider.field);
                return;
            }
            if (mouseX >= slider.trackX && mouseX < slider.trackX + slider.trackW) {
                this.setSliderValueFromMouse(slider, mouseX);
                this.sliderDrag = slider.field;
                return;
            }
            if (mouseX >= slider.x && mouseX < slider.trackX) {
                this.beginNumericEditing(slider.field);
                return;
            }
        }

        for (ConfigRow row : this.configRows) {
            if (row.contains(mouseX, mouseY)) {
                this.manager.selectConfig(row.index);
                ViewModelMOD.LOGGER.info("[vmclick] config row {} -> active {}", row.index,
                    this.manager.activeConfigIndex());
                this.playToast(1.12F);
                this.setStatus(this.tr("Loaded ", "Загружен ") + this.manager.activeConfig().name);
                return;
            }
        }

        for (Button button : this.buttons) {
            if (button.contains(mouseX, mouseY)) {
                ViewModelMOD.LOGGER.info("[vmclick] button {}", button.action);
                if (button.enabled) {
                    this.clickButton(button);
                }
                return;
            }
        }

        ViewModelMOD.LOGGER.info("[vmclick] nothing hit (rows={}, buttons={})",
            this.configRows.size(), this.buttons.size());
    }

    /** Runs a button action with click feedback and a toggle flash. */
    private void clickButton(Button button) {
        boolean wasActive = button.active;
        this.perform(button.action, button.data);
        this.flash(button.action.name() + ":" + button.data);
        switch (button.action) {
            case GLOW_TOGGLE, THEME_DARK, THEME_LIGHT, LANG_EN, LANG_RU ->
                this.playToast(wasActive ? 0.92F : 1.22F);
            case CONFIG_DELETE -> this.playToastOut(0.9F);
            case CONFIG_NEW -> this.playToast(1.28F);
            case CONFIG_SAVE -> this.playToast(1.02F);
            default -> this.playClick(wasActive ? 0.86F : 1.18F);
        }
    }

    private void switchTab(Tab next) {
        if (next == this.tab) {
            return;
        }
        this.contentSlideDirection = next.ordinal() > this.tab.ordinal() ? 1.0F : -1.0F;
        this.contentSlide = 1.0F;
        this.tab = next;
        this.playClick(1.08F);
    }

    private void beginPanelDrag(double mouseX, double mouseY) {
        this.panelDragging = true;
        this.panelDragMouseX = mouseX;
        this.panelDragMouseY = mouseY;
        this.panelDragStartX = this.manager.config().panelOffsetX;
        this.panelDragStartY = this.manager.config().panelOffsetY;
    }

    private void playClick(float pitch) {
        this.client.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.UI_BUTTON_CLICK, pitch));
    }

    /** WexSide uses the toast sound for notifications (config loaded, toggled). */
    private void playToast(float pitch) {
        this.client.getSoundManager().play(
            PositionedSoundInstance.master(SoundEvents.UI_TOAST_IN, pitch, 0.7F));
    }

    /** Lower, softer variant for destructive actions. */
    private void playToastOut(float pitch) {
        this.client.getSoundManager().play(
            PositionedSoundInstance.master(SoundEvents.UI_TOAST_OUT, pitch, 0.6F));
    }

    private void flash(Object key) {
        this.flashAmounts.put(key, 1.0F);
    }

    private void perform(Action action, int data) {
        switch (action) {
            case TARGET_LEFT_HAND -> this.manager.selectTarget(EditorTarget.LEFT_HAND);
            case TARGET_RIGHT_HAND -> this.manager.selectTarget(EditorTarget.RIGHT_HAND);
            case TARGET_LEFT_ITEM -> this.manager.selectTarget(EditorTarget.LEFT_ITEM);
            case TARGET_RIGHT_ITEM -> this.manager.selectTarget(EditorTarget.RIGHT_ITEM);
            case MODE_TOGGLE -> this.editMode = this.editMode.next();
            case PLANE_TOGGLE -> this.plane = this.plane.next();
            case GLOW_TOGGLE -> {
                HighlightSettings settings = this.manager.highlight();
                settings.enabled = !settings.enabled;
                this.manager.markDirty();
            }
            case EDIT_HEX -> this.beginHexEditing();
            case RESET_TARGET -> {
                this.manager.resetCurrent();
                this.setStatus(this.tr("Reset ", "Сброшено: ") + this.manager.selectedTarget().displayName());
            }
            case RESET_ALL -> {
                this.manager.resetAll();
                this.setStatus(this.tr("Reset all settings", "Все настройки сброшены"));
            }
            case LOAD_DEFAULTS -> {
                this.manager.loadDefaults();
                this.setStatus(this.tr("Loaded defaults", "Загружены значения по умолчанию"));
            }
            case CONFIG_RENAME -> this.beginNameEditing();
            case CONFIG_NEW -> {
                ViewModelConfigEntry created = this.manager.createConfig();
                this.setStatus(this.tr("Created ", "Создан ") + created.name);
                this.beginNameEditing();
            }
            case CONFIG_SAVE -> {
                this.manager.saveNow();
                this.setStatus(this.tr("Saved ", "Сохранён ") + this.manager.activeConfig().name);
            }
            case CONFIG_DELETE -> {
                String name = this.manager.activeConfig().name;
                if (this.manager.deleteActiveConfig()) {
                    this.setStatus(this.tr("Deleted ", "Удалён ") + name);
                }
            }
            case THEME_DARK -> this.manager.setTheme(ViewModelConfig.THEME_DARK);
            case THEME_LIGHT -> this.manager.setTheme(ViewModelConfig.THEME_LIGHT);
            case LANG_EN -> this.manager.setLanguage(ViewModelConfig.LANGUAGE_EN);
            case LANG_RU -> this.manager.setLanguage(ViewModelConfig.LANGUAGE_RU);
            case OPEN_GITHUB -> {
                try {
                    Util.getOperatingSystem().open(URI.create("https://github.com/emosteamer-bit?tab=projects"));
                    this.setStatus(this.tr("Opened GitHub", "GitHub открыт"));
                } catch (Exception exception) {
                    this.setStatus(this.tr("Could not open browser", "Не удалось открыть браузер"));
                }
            }
            case OPEN_CONFIGS -> this.switchTab(Tab.CONFIGS);
            case HIDE_UI -> this.togglePanel();
        }
    }

    private void tickPointerDrags(int mouseX, int mouseY) {
        boolean leftPressed = GLFW.glfwGetMouseButton(this.client.getWindow().getHandle(), GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;

        if (this.panelDragging) {
            if (leftPressed) {
                ViewModelConfig config = this.manager.config();
                config.panelOffsetX = this.panelDragStartX + (float) (mouseX - this.panelDragMouseX);
                config.panelOffsetY = this.panelDragStartY + (float) (mouseY - this.panelDragMouseY);
                this.manager.markDirty();
            } else {
                this.panelDragging = false;
            }
        }

        if (this.handDragging) {
            if (leftPressed) {
                this.handDrag.update(this.manager, mouseX, mouseY, this.editMode, this.plane, isShiftDown());
            } else {
                this.handDrag.end();
                this.handDragging = false;
            }
        }

        if (this.sliderDrag != null) {
            if (leftPressed) {
                Slider slider = this.findSlider(this.sliderDrag);
                if (slider != null) {
                    this.setSliderValueFromMouse(slider, mouseX);
                }
            } else {
                this.sliderDrag = null;
            }
        }
    }

    private void endPointerDrags() {
        this.handDrag.end();
        this.handDragging = false;
        this.sliderDrag = null;
        this.panelDragging = false;
        this.editing = null;
    }

    private void togglePanel() {
        this.panelVisible = !this.panelVisible;
        if (!this.panelVisible) {
            this.endPointerDrags();
            this.playToastOut(1.0F);
        } else {
            this.playToast(1.2F);
        }
        this.manager.setEditorActive(true);
        this.setStatus(this.panelVisible
            ? this.tr("UI shown", "Интерфейс показан")
            : this.tr("UI hidden - settings stay active (F6)", "Интерфейс скрыт – настройки активны (F6)"));
    }

    // ------------------------------------------------------------------
    // Editing
    // ------------------------------------------------------------------

    private void beginNumericEditing(Field field) {
        this.editing = new TextEdit(EditKind.NUMERIC, field, this.formatValue(field, this.value(field)));
    }

    private void beginHexEditing() {
        this.editing = new TextEdit(EditKind.HEX, null, String.format("%06X", this.manager.highlight().color & 0xFFFFFF));
    }

    private void beginNameEditing() {
        this.editing = new TextEdit(EditKind.NAME, null, this.manager.activeConfig().name);
    }

    private boolean handleCharTyped(int codePoint) {
        if (!this.panelVisible || this.editing == null) {
            return false;
        }
        if (this.client.currentScreen != this.screen) {
            return false;
        }
        char chr = (char) codePoint;
        if (this.editing.accept(chr)) {
            this.editing.insert(chr);
        }
        return true;
    }

    private void handleEditingKey(int keyCode) {
        TextEdit edit = this.editing;
        if (edit == null) {
            return;
        }

        switch (keyCode) {
            case GLFW.GLFW_KEY_ESCAPE -> this.editing = null;
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> this.commitEditing();
            case GLFW.GLFW_KEY_BACKSPACE -> edit.backspace();
            case GLFW.GLFW_KEY_DELETE -> edit.delete();
            case GLFW.GLFW_KEY_LEFT -> edit.moveCursor(-1);
            case GLFW.GLFW_KEY_RIGHT -> edit.moveCursor(1);
            case GLFW.GLFW_KEY_HOME -> edit.cursor = 0;
            case GLFW.GLFW_KEY_END -> edit.cursor = edit.buffer.length();
            case GLFW.GLFW_KEY_V -> {
                if (isControlDown()) {
                    String clipboard = this.client.keyboard.getClipboard();
                    for (int i = 0; i < clipboard.length() && edit.buffer.length() < edit.maxLength; i++) {
                        char chr = clipboard.charAt(i);
                        if (edit.accept(chr)) {
                            edit.insert(chr);
                        }
                    }
                }
            }
            default -> {
            }
        }
    }

    private void commitEditing() {
        TextEdit edit = this.editing;
        if (edit == null) {
            return;
        }
        this.editing = null;

        String value = edit.buffer.toString().strip();
        try {
            switch (edit.kind) {
                case NUMERIC -> {
                    if (edit.field == null || value.isEmpty()) {
                        return;
                    }
                    float parsed = Float.parseFloat(value.replace(',', '.'));
                    float[] range = range(edit.field);
                    this.set(edit.field, MathHelper.clamp(parsed, range[0], range[1]));
                    this.manager.markDirty();
                }
                case HEX -> {
                    String hex = value.startsWith("#") ? value.substring(1) : value;
                    if (hex.isEmpty() || hex.length() > 6) {
                        return;
                    }
                    this.manager.highlight().color = Integer.parseInt(hex, 16) & 0xFFFFFF;
                    this.manager.markDirty();
                }
                case NAME -> this.manager.renameActiveConfig(value);
            }
        } catch (NumberFormatException ignored) {
            // Invalid input simply keeps the previous value.
        }
    }

    private void cancelEditing() {
        this.editing = null;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private float value(Field field) {
        ViewModelTransform transform = this.manager.selectedTransform();
        HighlightSettings highlight = this.manager.highlight();
        return switch (field) {
            case X -> transform.x;
            case Y -> transform.y;
            case Z -> transform.z;
            case SCALE -> transform.scale;
            case ROT_X -> transform.rotationX;
            case ROT_Y -> transform.rotationY;
            case ROT_Z -> transform.rotationZ;
            case GLOW_R -> (highlight.color >> 16) & 0xFF;
            case GLOW_G -> (highlight.color >> 8) & 0xFF;
            case GLOW_B -> highlight.color & 0xFF;
            case GLOW_INTENSITY -> highlight.intensity;
            case GLOW_OPACITY -> highlight.opacity;
            case SENSITIVITY -> this.manager.config().dragSensitivity;
        };
    }

    private void set(Field field, float value) {
        ViewModelTransform transform = this.manager.selectedTransform();
        HighlightSettings highlight = this.manager.highlight();
        float[] range = range(field);
        float clamped = MathHelper.clamp(value, range[0], range[1]);
        switch (field) {
            case X -> transform.x = clamped;
            case Y -> transform.y = clamped;
            case Z -> transform.z = clamped;
            case SCALE -> transform.scale = clamped;
            case ROT_X -> transform.rotationX = clamped;
            case ROT_Y -> transform.rotationY = clamped;
            case ROT_Z -> transform.rotationZ = clamped;
            case GLOW_R -> highlight.color = (highlight.color & 0x00FFFF) | ((int) clamped << 16);
            case GLOW_G -> highlight.color = (highlight.color & 0xFF00FF) | ((int) clamped << 8);
            case GLOW_B -> highlight.color = (highlight.color & 0xFFFF00) | (int) clamped;
            case GLOW_INTENSITY -> highlight.intensity = clamped;
            case GLOW_OPACITY -> highlight.opacity = clamped;
            case SENSITIVITY -> this.manager.config().dragSensitivity = clamped;
        }
    }

    private static float[] range(Field field) {
        return switch (field) {
            case X, Y, Z -> new float[]{-1.0F, 1.0F};
            case SCALE -> new float[]{0.2F, 2.8F};
            case ROT_X, ROT_Y, ROT_Z -> new float[]{-180.0F, 180.0F};
            case GLOW_R, GLOW_G, GLOW_B -> new float[]{0.0F, 255.0F};
            case GLOW_INTENSITY -> new float[]{0.0F, 2.0F};
            case GLOW_OPACITY -> new float[]{0.0F, 1.0F};
            case SENSITIVITY -> new float[]{0.25F, 3.0F};
        };
    }

    private String formatValue(Field field, float value) {
        return switch (field) {
            case X, Y, Z, SCALE, GLOW_INTENSITY, GLOW_OPACITY, SENSITIVITY -> String.format(Locale.ROOT, "%.2f", value);
            case ROT_X, ROT_Y, ROT_Z -> String.format(Locale.ROOT, "%.1f", value);
            case GLOW_R, GLOW_G, GLOW_B -> Integer.toString(Math.round(value));
        };
    }

    private void setSliderValueFromMouse(Slider slider, double mouseX) {
        float normalized = MathHelper.clamp((float) ((mouseX - slider.trackX) / (double) slider.trackW), 0.0F, 1.0F);
        this.setSliderValue(slider, slider.min + normalized * (slider.max - slider.min));
    }

    private void setSliderValue(Slider slider, float value) {
        this.set(slider.field, value);
        this.manager.markDirty();
    }

    private Slider findSlider(Field field) {
        for (Slider slider : this.sliders) {
            if (slider.field == field) {
                return slider;
            }
        }
        return null;
    }

    private Slider sliderAt(double mouseX, double mouseY) {
        for (Slider slider : this.sliders) {
            if (mouseY >= slider.y && mouseY < slider.y + slider.h
                && mouseX >= slider.trackX && mouseX < slider.valueX + slider.valueW) {
                return slider;
            }
        }
        return null;
    }

    /**
     * Returns the element actually under the cursor.
     *
     * <p>The selected element kind is preferred, but an empty hand has no item
     * box and a held item has no separate arm box, so this falls back to
     * whichever box of that hand does exist. Selecting the fallback target keeps
     * clicks, drags and the wheel editing something visible instead of silently
     * modifying an element that is not drawn.</p>
     */
    /**
     * The selected target, or the visible element of the same hand when the
     * selected box does not exist yet (an empty hand has no item box, a held
     * item has no separate arm box).
     */
    private EditorTarget visibleTarget() {
        EditorTarget selected = this.manager.selectedTarget();
        EditorTarget alternate = EditorTarget.forArm(selected.arm(), !selected.isItem());
        HandBounds selectedBounds = this.manager.getHandBounds(selected);
        HandBounds alternateBounds = this.manager.getHandBounds(alternate);
        if (selectedBounds == null) {
            return alternateBounds != null ? alternate : selected;
        }
        // While holding an item the arm is not drawn, but its last capture can
        // still be inside the freshness window. Prefer the outline that is
        // actually being refreshed right now.
        if (alternateBounds != null
            && alternateBounds.capturedAtNanos - selectedBounds.capturedAtNanos > 100_000_000L) {
            return alternate;
        }
        return selected;
    }

    private EditorTarget pickTarget(double mouseX, double mouseY) {
        EditorTarget selected = this.visibleTarget();
        EditorTarget best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Arm arm : Arm.values()) {
            EditorTarget chosen = null;
            HandBounds chosenBounds = null;
            for (boolean item : new boolean[] { selected.isItem(), !selected.isItem() }) {
                EditorTarget candidate = EditorTarget.forArm(arm, item);
                HandBounds bounds = this.manager.getHandBounds(candidate);
                if (bounds != null) {
                    chosen = candidate;
                    chosenBounds = bounds;
                    break;
                }
            }
            if (chosenBounds == null || !chosenBounds.contains(mouseX, mouseY, 4.0)) {
                continue;
            }
            double dx = mouseX - chosenBounds.centerX();
            double dy = mouseY - chosenBounds.centerY();
            double distance = dx * dx + dy * dy;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = chosen;
            }
        }
        return best;
    }

    private boolean isInsidePanel(double mouseX, double mouseY) {
        return mouseX >= this.panelX && mouseX < this.panelX + this.panelW
            && mouseY >= this.panelY && mouseY < this.panelY + this.panelH;
    }

    private boolean isInsideConfigList(double mouseX, double mouseY) {
        return mouseX >= this.configListX && mouseX < this.configListX + this.configListW
            && mouseY >= this.configListY && mouseY < this.configListY + this.configListH;
    }

    private void setStatus(String message) {
        this.status = message;
        this.statusUntil = System.currentTimeMillis() + 2500L;
    }

    private void updateStatus() {
        if (this.status != null && System.currentTimeMillis() > this.statusUntil) {
            this.status = null;
        }
    }

    private Palette palette() {
        return ViewModelConfig.THEME_LIGHT.equals(this.manager.theme()) ? Palette.LIGHT : Palette.DARK;
    }

    private String tr(String english, String russian) {
        return ViewModelConfig.LANGUAGE_RU.equals(this.manager.language()) ? russian : english;
    }

    private String navLabel(Tab tab) {
        return switch (tab) {
            case POSITION -> this.tr("Position", "Позиция");
            case GLOW -> this.tr("Glow", "Свечение");
            case CONFIGS -> this.tr("Configs", "Конфиги");
            case SETTINGS -> this.tr("Settings", "Настройки");
        };
    }

    private String tabLabel(Tab tab) {
        return this.navLabel(tab).toUpperCase(Locale.ROOT);
    }

    /** Compact label for the floating target pill. */
    private String shortName(EditorTarget target) {
        return switch (target) {
            case LEFT_HAND -> "L ARM";
            case RIGHT_HAND -> "R ARM";
            case LEFT_ITEM -> "L ITEM";
            case RIGHT_ITEM -> "R ITEM";
        };
    }

    private String formatDate(long epochMillis) {
        try {
            return DATE_FORMAT.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()));
        } catch (Exception exception) {
            return "--";
        }
    }

    private static boolean isShiftDown() {
        long handle = MinecraftClient.getInstance().getWindow().getHandle();
        return GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_LEFT_SHIFT) == GLFW.GLFW_PRESS
            || GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS;
    }

    private static boolean isControlDown() {
        long handle = MinecraftClient.getInstance().getWindow().getHandle();
        return GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_LEFT_CONTROL) == GLFW.GLFW_PRESS
            || GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_RIGHT_CONTROL) == GLFW.GLFW_PRESS;
    }

    /** Draws editor text with the bundled SF Pro Display font. */
    private void drawText(DrawContext context, String text, int x, int y, int color) {
        context.drawText(this.client.textRenderer, Text.literal(text).setStyle(EDITOR_STYLE), x, y, color, false);
    }

    private int width(String text) {
        return this.client.textRenderer.getWidth(Text.literal(text).setStyle(EDITOR_STYLE));
    }

    /** Smooth hover interpolation, so highlights fade instead of popping. */
    private float animate(Object key, boolean target) {
        float goal = target ? 1.0F : 0.0F;
        float current = this.hoverAmounts.getOrDefault(key, goal);
        float next = this.lerpTowards(current, goal, 20.0F);
        this.hoverAmounts.put(key, next);
        return next;
    }

    /**
     * WexSide's FrameInterpolator: frame-time scaled approach with a clamped
     * progress so the speed is independent of the frame rate.
     */
    private float lerpTowards(float current, float target, float speed) {
        float progress = MathHelper.clamp(this.frameDelta * speed, 0.0F, 1.0F);
        return MathHelper.lerp(progress, current, target);
    }

    /** WexSide's Easing.EASE_OUT_CUBIC. */
    private static float easeOutCubic(float value) {
        return 1.0F - (float) Math.pow(1.0F - value, 3.0);
    }

    /** Clips text to a pixel width with a plain ASCII ellipsis. */
    private String fit(String text, int maxWidth) {
        if (text == null) {
            return "";
        }
        if (this.width(text) <= maxWidth) {
            return text;
        }
        String ellipsis = "..";
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            if (this.width(builder.toString() + text.charAt(i) + ellipsis) > maxWidth) {
                break;
            }
            builder.append(text.charAt(i));
        }
        return builder + ellipsis;
    }

    private static int withAlpha(int color, int alpha) {
        return (MathHelper.clamp(alpha, 0, 255) << 24) | (color & 0xFFFFFF);
    }

    private static int mix(int from, int to, float amount) {
        float t = MathHelper.clamp(amount, 0.0F, 1.0F);
        int a = channel(from, 24) + Math.round((channel(to, 24) - channel(from, 24)) * t);
        int r = channel(from, 16) + Math.round((channel(to, 16) - channel(from, 16)) * t);
        int g = channel(from, 8) + Math.round((channel(to, 8) - channel(from, 8)) * t);
        int b = channel(from, 0) + Math.round((channel(to, 0) - channel(from, 0)) * t);
        return a << 24 | r << 16 | g << 8 | b;
    }

    private static int channel(int color, int shift) {
        return color >>> shift & 0xFF;
    }

    private static void fillRounded(DrawContext context, int x, int y, int width, int height, int radius, int color) {
        fillRoundedCorners(context, x, y, width, height, radius, radius, radius, radius, color);
    }

    private static void fillRoundedCorners(
        DrawContext context,
        int x,
        int y,
        int width,
        int height,
        int topLeft,
        int topRight,
        int bottomRight,
        int bottomLeft,
        int color
    ) {
        if (width <= 0 || height <= 0 || (color >>> 24) == 0) {
            return;
        }

        int maxTop = Math.min(topLeft, topRight);
        int maxBottom = Math.min(bottomLeft, bottomRight);
        if (maxTop <= 0 && maxBottom <= 0) {
            context.fill(x, y, x + width, y + height, color);
            return;
        }

        for (int i = 0; i < height; i++) {
            int insetLeft = 0;
            int insetRight = 0;
            if (i < topLeft) {
                insetLeft = inset(topLeft, i);
            } else if (i >= height - bottomLeft) {
                insetLeft = inset(bottomLeft, height - 1 - i);
            }
            if (i < topRight) {
                insetRight = inset(topRight, i);
            } else if (i >= height - bottomRight) {
                insetRight = inset(bottomRight, height - 1 - i);
            }
            context.fill(x + insetLeft, y + i, x + width - insetRight, y + i + 1, color);
        }
    }

    private static int inset(int radius, int row) {
        if (radius <= 0) {
            return 0;
        }
        double dy = (double) radius - row - 0.5;
        double value = (double) radius * radius - dy * dy;
        if (value <= 0.0) {
            return 0;
        }
        return radius - (int) Math.round(Math.sqrt(value));
    }

    private static void drawRoundedBox(DrawContext context, int x, int y, int width, int height, int radius, int fill, int border) {
        if (width <= 0 || height <= 0) {
            return;
        }
        fillRounded(context, x, y, width, height, radius, border);
        fillRounded(context, x + 1, y + 1, width - 2, height - 2, Math.max(0, radius - 1), fill);
    }

    private static void drawRoundedOutline(DrawContext context, int x, int y, int width, int height, int radius, int color) {
        if (width <= 1 || height <= 1) {
            return;
        }
        int r = Math.max(0, Math.min(radius, Math.min(width, height) / 2));
        context.fill(x + r, y, x + width - r, y + 1, color);
        context.fill(x + r, y + height - 1, x + width - r, y + height, color);
        for (int i = 0; i < height; i++) {
            int insetLeft;
            int insetRight;
            if (r > 0 && i < r) {
                insetLeft = inset(r, i);
                insetRight = inset(r, i);
            } else if (r > 0 && i >= height - r) {
                insetLeft = inset(r, height - 1 - i);
                insetRight = inset(r, height - 1 - i);
            } else {
                insetLeft = 0;
                insetRight = 0;
            }
            context.fill(x + insetLeft, y + i, x + insetLeft + 1, y + i + 1, color);
            int right = x + width - insetRight - 1;
            context.fill(right, y + i, right + 1, y + i + 1, color);
        }
    }

    // ------------------------------------------------------------------
    // Small data holders
    // ------------------------------------------------------------------

    private static final int VARIANT_NORMAL = 0;
    private static final int VARIANT_DANGER = 1;
    private static final int VARIANT_GHOST = 2;

    private enum Tab {
        POSITION, GLOW, CONFIGS, SETTINGS
    }

    private enum Field {
        X, Y, Z, SCALE, ROT_X, ROT_Y, ROT_Z,
        GLOW_R, GLOW_G, GLOW_B, GLOW_INTENSITY, GLOW_OPACITY,
        SENSITIVITY
    }

    private enum Action {
        TARGET_LEFT_HAND, TARGET_RIGHT_HAND, TARGET_LEFT_ITEM, TARGET_RIGHT_ITEM,
        MODE_TOGGLE, PLANE_TOGGLE,
        GLOW_TOGGLE, EDIT_HEX,
        RESET_TARGET, RESET_ALL, LOAD_DEFAULTS,
        CONFIG_RENAME, CONFIG_NEW, CONFIG_SAVE, CONFIG_DELETE,
        THEME_DARK, THEME_LIGHT, LANG_EN, LANG_RU, OPEN_GITHUB,
        OPEN_CONFIGS,
        HIDE_UI
    }

    private enum EditKind {
        NUMERIC, HEX, NAME
    }

    private record NavEntry(Tab tab, int x, int y, int w, int h) {
        private boolean contains(double mouseX, double mouseY) {
            return mouseX >= this.x && mouseX < this.x + this.w
                && mouseY >= this.y && mouseY < this.y + this.h;
        }
    }

    private record ConfigRow(int index, int x, int y, int w, int h) {
        private boolean contains(double mouseX, double mouseY) {
            return mouseX >= this.x && mouseX < this.x + this.w
                && mouseY >= this.y && mouseY < this.y + this.h;
        }
    }

    private record Label(String text, int x, int y, int color, boolean section) {
    }

    private record Slider(Field field, String label, float min, float max,
                          int x, int y, int w, int h,
                          int trackX, int trackW, int valueX, int valueW) {
    }

    private static final class Button {
        private final Action action;
        private final String label;
        private final int x;
        private final int y;
        private final int w;
        private final int h;
        private final boolean active;
        private final boolean enabled;
        private final int variant;
        private final int data;

        private Button(Action action, String label, int x, int y, int w, int h,
                       boolean active, boolean enabled, int variant, int data) {
            this.action = action;
            this.label = label;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.active = active;
            this.enabled = enabled;
            this.variant = variant;
            this.data = data;
        }

        private boolean contains(double mouseX, double mouseY) {
            return mouseX >= this.x && mouseX < this.x + this.w
                && mouseY >= this.y && mouseY < this.y + this.h;
        }
    }

    private static final class TextEdit {
        private final EditKind kind;
        private final Field field;
        private final StringBuilder buffer = new StringBuilder();
        private final int maxLength;
        private int cursor;

        private TextEdit(EditKind kind, Field field, String initial) {
            this.kind = kind;
            this.field = field;
            this.maxLength = switch (kind) {
                case NUMERIC -> 10;
                case HEX -> 7;
                case NAME -> 28;
            };
            this.buffer.append(initial);
            this.cursor = this.buffer.length();
        }

        private boolean accept(char chr) {
            return switch (this.kind) {
                case NUMERIC -> Character.isDigit(chr) || chr == '-' || chr == '.' || chr == ',';
                case HEX -> Character.digit(chr, 16) >= 0 || chr == '#';
                case NAME -> !Character.isISOControl(chr) && chr != '\u00a7';
            };
        }

        private void insert(char chr) {
            if (this.buffer.length() >= this.maxLength) {
                return;
            }
            this.buffer.insert(this.cursor, chr);
            this.cursor++;
        }

        private void backspace() {
            if (this.cursor > 0) {
                this.buffer.deleteCharAt(this.cursor - 1);
                this.cursor--;
            }
        }

        private void delete() {
            if (this.cursor < this.buffer.length()) {
                this.buffer.deleteCharAt(this.cursor);
            }
        }

        private void moveCursor(int direction) {
            this.cursor = MathHelper.clamp(this.cursor + direction, 0, this.buffer.length());
        }

        private String display() {
            return this.buffer.substring(0, this.cursor) + "_" + this.buffer.substring(this.cursor);
        }
    }

    /**
     * WexSide palette, decoded from BuiltInThemes.themePalette (dark) and
     * themePalette2 (light).
     */
    private record Palette(
        int backgroundPrimary,
        int backgroundSecondary,
        int backgroundControl,
        int backgroundHover,
        int controlFill,
        int borderPrimary,
        int borderSubtle,
        int borderSoft,
        int borderStrong,
        int formatFieldFill,
        int textPrimary,
        int textSecondary,
        int textMuted,
        int textDisabled,
        int accent,
        int accentTint,
        int danger,
        int separator
    ) {
        private static final Palette DARK = new Palette(
            0xFF101216, 0xFF161920, 0xFF1C2029, 0xFF232833, 0xFF2A303D,
            0xFF303744, 0xFF394251, 0xFF252B35, 0xFF465160, 0xFF1B1F27,
            0xFFF4F6FA, 0xFFB8C0CC, 0xFF858F9E, 0xFF505967,
            0xFF7C5CFF, 0x337C5CFF, 0xFFFF5D73, 0xFF343B47
        );

        private static final Palette LIGHT = new Palette(
            0xFFF5F6F8, 0xFFFFFFFF, 0xFFE9ECF1, 0xFFE1E5EB, 0xFFD8DDE5,
            0xFFD0D6DF, 0xFFBCC4D0, 0xFFDDE2E9, 0xFFADB7C5, 0xFFEDF0F4,
            0xFF171A20, 0xFF414854, 0xFF737D8B, 0xFFADB4BE,
            0xFF6C4DFF, 0x336C4DFF, 0xFFE2475F, 0xFFD5DAE2
        );
    }
}
