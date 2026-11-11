package dev.viewmodelmod.config;

/** Highlight settings shared by all profiles. */
public class HighlightSettings {
    public boolean enabled = true;
    /** RGB, no alpha. Default is pure white. */
    public int color = 0xFFFFFF;
    /** Multiplies the highlight alpha. */
    public float intensity = 0.90F;
    /** Base transparency of the highlight overlay. */
    public float opacity = 0.70F;

    public void reset() {
        this.enabled = true;
        this.color = 0xFFFFFF;
        this.intensity = 0.90F;
        this.opacity = 0.70F;
    }

    public void sanitize() {
        this.color &= 0xFFFFFF;
        this.intensity = clamp(this.intensity, 0.0F, 2.0F);
        this.opacity = clamp(this.opacity, 0.0F, 1.0F);
    }

    public int argbColor() {
        int alpha = Math.round(255.0F * clamp(this.intensity * this.opacity, 0.0F, 1.0F));
        return alpha << 24 | this.color;
    }

    private static float clamp(float value, float min, float max) {
        if (!Float.isFinite(value)) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }
}
